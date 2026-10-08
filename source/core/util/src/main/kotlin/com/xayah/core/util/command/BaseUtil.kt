package com.xayah.core.util.command

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import com.topjohnwu.superuser.Shell
import com.xayah.core.common.util.BuildConfigUtil
import com.xayah.core.common.util.trim
import com.xayah.core.datastore.readCustomSUFile
import com.xayah.core.util.BinArchiveName
import com.xayah.core.util.LogUtil
import com.xayah.core.util.LogUtil.TAG_SHELL_CODE
import com.xayah.core.util.LogUtil.TAG_SHELL_IN
import com.xayah.core.util.LogUtil.TAG_SHELL_OUT
import com.xayah.core.util.LogUtil.log
import com.xayah.core.util.SymbolUtil.QUOTE
import com.xayah.core.util.SymbolUtil.USD
import com.xayah.core.util.binArchivePath
import com.xayah.core.util.binDir
import com.xayah.core.util.filesDir
import com.xayah.core.util.logDir
import com.xayah.core.util.model.ShellResult
import com.xayah.core.util.withIOContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.lingala.zip4j.ZipFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

private class EnvInitializer : Shell.Initializer() {
    companion object {
        fun initShell(shell: Shell, context: Context) {
            shell.newJob()
                .add("nsenter --mount=/proc/1/ns/mnt sh") // Switch to global namespace
                .add("export PATH=${context.binDir()}:${USD}PATH")
                .add("export HOME=${context.filesDir()}")
                .add("set -o pipefail") // Ensure that the exit code of each command is correct.
                .add("alias awk=${QUOTE}busybox awk$QUOTE")
                .add("alias ps=${QUOTE}busybox ps$QUOTE")
                .exec()
        }
    }

    override fun onInit(context: Context, shell: Shell): Boolean {
        initShell(shell, context)
        return true
    }
}

object BaseUtil {
    // 保存应用上下文：shell 会话失效时重建默认 builder 需要重新读取自定义 su 配置
    private var appContext: Context? = null

    private suspend fun getShellBuilder(context: Context) = Shell.Builder.create()
        .setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_REDIRECT_STDERR)
        .setInitializers(EnvInitializer::class.java)
        .setCommands(context.readCustomSUFile().first())
        // shell 验证超时：官方默认 20 秒，过短会在 su 启动稍慢时验证超时并强制关闭 shell，
        // 导致后续所有命令连环失败（表现为备份过程中 Broken pipe 后全部秒失败）
        .setTimeout(20)

    private suspend fun getNewShell(context: Context): Shell? = runCatching { getShellBuilder(context).build() }.getOrNull()

    suspend fun initializeEnvironment(context: Context) = run {
        // Set up shell environment.
        appContext = context
        Shell.enableVerboseLogging = BuildConfigUtil.ENABLE_VERBOSE
        Shell.setDefaultBuilder(getShellBuilder(context))

        // Set up LogUtil.
        LogUtil.initialize(context, context.logDir())
    }

    /**
     * 执行单条命令并返回退出码与输出，异常照常抛出。
     */
    private suspend fun runJob(input: String, shell: Shell?): Pair<Int, List<String>> = if (shell == null) {
        Shell.cmd(input).exec().let { it.code to it.out }
    } else {
        val outList = mutableListOf<String>()
        shell.newJob().to(outList, outList).add(input).exec().let { it.code to outList }
    }

    suspend fun execute(vararg args: String, shell: Shell? = null, log: Boolean = true): ShellResult = withIOContext {
        val shellResult = ShellResult(code = -1, input = args.toList().trim(), out = listOf())

        if (log) {
            log { TAG_SHELL_IN to shellResult.inputString }
        }

        // 首次执行抛异常视为 shell 会话失效（Broken pipe、验证超时等）：
        // 重建默认 shell 后重试一次，避免一次会话故障导致后续命令连环失败。
        // 外部传入的 shell 无法重建，保持原行为（异常照常抛出）。
        val (code, out) = try {
            runJob(input = shellResult.inputString, shell = shell)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (shell != null || appContext == null) throw e
            log { "BaseUtil" to "Shell session broken, rebuilding and retrying: ${e.message}" }
            Shell.setDefaultBuilder(getShellBuilder(appContext ?: throw e))
            runJob(input = shellResult.inputString, shell = shell)
        }
        shellResult.code = code
        shellResult.out = out

        if (log) {
            if (shellResult.outString.trim().isNotEmpty())
                log { TAG_SHELL_OUT to shellResult.outString }
            log { TAG_SHELL_CODE to shellResult.code.toString() }
        }

        shellResult
    }

    suspend fun execute(vararg args: String, shell: Shell, log: Boolean = true, timeout: Long): ShellResult = withIOContext {
        var shellResult = ShellResult(code = -1, input = args.toList().trim(), out = listOf())

        val job = launch {
            shellResult = execute(args = args, shell = shell, log = log)
        }
        delay(50)
        runCatching {
            if (timeout != -1L) {
                shell.waitAndClose(timeout, TimeUnit.SECONDS)
            } else {
                shell.waitAndClose()
            }
        }.onFailure {
            log("Execution timeout.")
        }
        job.join()

        shellResult
    }

    suspend fun kill(context: Context, vararg keys: String) {
        val shell = getNewShell(context)
        if (shell != null) {
            // ps -A | grep -w $key1 | grep -w $key2 | ... | awk 'NF>1{print $1}' | xargs kill -9
            val keysArg = keys.map { "| grep -w $it" }.toTypedArray()
            execute(
                "ps -A",
                *keysArg,
                "| awk 'NF>1{print ${USD}1}'",
                "| xargs kill -9",
                shell = shell,
                timeout = -1
            )
        } else {
            log { "kill" to "Failed to get a new shell!" }
        }
    }

    suspend fun killPackage(context: Context, userId: Int, packageName: String) {
        val shell = getNewShell(context)
        if (shell != null) {
            val cmd = """
            until [[ $USD(dumpsys activity processes | grep "packageList" | cut -d '{' -f2 | cut -d '}' -f1 | egrep -w "$packageName" | sed -n '1p') = "" ]]; do
                killall -9 "$packageName" &>/dev/null
                am force-stop --user "$userId" "$packageName" &>/dev/null
                am kill "$packageName" &>/dev/null
            done
        """.trimIndent()
            execute(cmd, shell = shell, timeout = 3)
        } else {
            log { "killPackage" to "Failed to get a new shell!" }
        }
    }

    suspend fun mkdirs(dst: String) = withIOContext {
        runCatching {
            val file = File(dst)
            if (file.exists().not()) file.mkdirs() else true
        }
    }

    suspend fun writeIcon(icon: Drawable, dst: String) = withIOContext {
        runCatching {
            val byteArrayOutputStream = ByteArrayOutputStream()
            icon.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
            val byteArray = byteArrayOutputStream.toByteArray()
            byteArrayOutputStream.flush()
            byteArrayOutputStream.close()
            File(dst).writeBytes(byteArray)
        }
    }

    fun readIconFromPackageName(context: Context, pkgName: String): Drawable? = runCatching { context.packageManager.getApplicationIcon(pkgName) }.getOrNull()

    suspend fun readIcon(context: Context, src: String): Drawable? = withIOContext {
        runCatching {
            val bytes = File(src).readBytes()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size).toDrawable(context.resources)
        }.getOrNull()
    }

    @SuppressLint("SetWorldWritable", "SetWorldReadable")
    fun File.setAllPermissions(): Boolean = run {
        setExecutable(true, false).also {
            if (it.not()) return@run false
        }
        setWritable(true, false).also {
            if (it.not()) return@run false
        }
        setReadable(true, false).also {
            if (it.not()) return@run false
        }
    }

    /**
     * Unzip and return file headers.
     */
    private suspend fun unzip(src: String, dst: String): List<String> = withIOContext {
        runCatching {
            val zip = ZipFile(src)
            zip.extractAll(dst)
            zip.fileHeaders.map { it.fileName }
        }.getOrElse { listOf() }
    }

    private suspend fun releaseAssets(context: Context, src: String, child: String) {
        withIOContext {
            runCatching {
                val assets = File(context.filesDir(), child)
                if (!assets.exists()) {
                    val outStream = FileOutputStream(assets)
                    val inputStream = context.resources.assets.open(src)
                    inputStream.copyTo(outStream)
                    assets.setExecutable(true)
                    assets.setReadable(true)
                    assets.setWritable(true)
                    outStream.flush()
                    inputStream.close()
                    outStream.close()
                }
            }
        }
    }

    suspend fun releaseBase(context: Context): Boolean = withIOContext {
        val bin = File(context.binDir())
        val binArchive = File(context.binArchivePath())

        // Remove old bin files
        bin.deleteRecursively()
        binArchive.deleteRecursively()

        // Release binaries
        releaseAssets(context = context, src = BinArchiveName, child = BinArchiveName)
        unzip(src = context.binArchivePath(), dst = context.binDir())

        // All binaries need full permissions
        bin.listFiles()?.forEach { file ->
            if (file.setAllPermissions().not()) return@withIOContext false
        }

        // Remove binary archive
        binArchive.deleteRecursively()

        return@withIOContext true
    }

    suspend fun readLink(pid: String) = run {
        // readlink /proc/$pid/ns/mnt
        execute(
            "readlink",
            "/proc/$pid/ns/mnt",
            log = false,
        ).outString
    }

    suspend fun readVariable(variable: String) = run {
        // echo "$variable"
        execute(
            "echo",
            "${QUOTE}$USD$variable${QUOTE}",
            log = false,
        ).outString
    }

    suspend fun readSuVersion(su: String) = run {
        // su -v
        execute(
            su,
            "-v",
            log = false,
        ).outString
    }
}
