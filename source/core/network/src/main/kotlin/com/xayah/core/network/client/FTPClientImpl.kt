package com.xayah.core.network.client

import android.content.Context
import com.xayah.core.common.util.toPathString
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.FTPExtra
import com.xayah.core.network.R
import com.xayah.core.network.io.CountingInputStreamImpl
import com.xayah.core.network.io.CountingOutputStreamImpl
import com.xayah.core.network.util.getExtraEntity
import com.xayah.core.rootservice.parcelables.PathParcelable
import com.xayah.core.util.GsonUtil
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.toPathList
import com.xayah.core.util.withMainContext
import com.xayah.libpickyou.PickYouLauncher
import com.xayah.libpickyou.parcelables.DirChildrenParcelable
import com.xayah.libpickyou.parcelables.FileParcelable
import com.xayah.libpickyou.ui.model.PickerType
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.security.auth.login.LoginException

class FTPClientImpl(private val entity: CloudEntity, private val extra: FTPExtra) : CloudClient {
    private var client: FTPClient? = null

    private fun log(msg: () -> String): String = run {
        LogUtil.log { "FTPClientImpl" to msg() }
        msg()
    }

    private fun withClient(block: (client: FTPClient) -> Unit) = run {
        if (client == null) throw NullPointerException("Client is null.")
        block(client!!)
    }

    /**
     * 控制连接可能在两次传输之间的空闲窗口（本地打包/压缩间隙）被服务端或网络设备按空闲超时断开，
     * 此后每条命令都会以 Broken pipe / connection abort 快速失败，殃及整个剩余任务。
     * 断连后重新登录并恢复工作目录，将命令重试一次以自愈；仅重试一次，真正的错误仍会正常抛出。
     */
    private fun withClientRetry(block: (client: FTPClient) -> Unit) = run {
        // 先记录当前工作目录；连接已断开时取不到，退回 “/”（mkdirRecursively 收尾的默认状态），保证相对路径落点一致
        val cwd = runCatching { client?.printWorkingDirectory() }.getOrNull() ?: "/"
        try {
            withClient(block)
        } catch (e: IOException) {
            log { "Connection lost: ${e.localizedMessage}, reconnecting and retrying once." }
            reconnect()
            withClient { it.changeWorkingDirectory(cwd) }
            withClient(block)
        }
    }

    private fun reconnect() = run {
        // 旧连接多半已被对端断开，静默关闭 socket 即可，避免残留
        runCatching { client?.disconnect() }
        client = null
        connect()
    }

    override fun connect() {
        client = FTPClient().apply {
            autodetectUTF8 = true
            // 大文件传输期间控制连接长时间无命令，周期发送 NOOP 保活，避免被服务端/网络设备按空闲超时断开
            controlKeepAliveTimeout = 30L
            connect(entity.host, extra.port)
            if (login(entity.user, entity.pass).not()) throw LoginException("Failed to login, user: ${entity.user}, pass: ${entity.pass}.")
            enterLocalPassiveMode()
            val fileType = FTP.BINARY_FILE_TYPE
            if (setFileType(fileType).not()) throw LoginException("Failed to set file type: $fileType.")
        }
    }

    override fun disconnect() {
        withClient { client ->
            if (client.isConnected) {
                if (client.logout().not()) throw LoginException("Failed to logout.")
                client.disconnect()
            }
        }
        client = null
    }

    override fun mkdir(dst: String) = withClient { client ->
        log { "mkdir: $dst" }
        if (client.makeDirectory(dst).not()) throw IOException("Failed to mkdir: $dst.")
    }

    // 每个备份目录的创建都可能撞上断连，重试使后续应用不再被一次断连整体跳过
    override fun mkdirRecursively(dst: String) {
        val dirs = dst.split("/")
        withClientRetry { client ->
            for (i in dirs) {
                if (client.changeWorkingDirectory(i).not()) {
                    mkdir(i)
                    client.changeWorkingDirectory(i)
                }
            }
            client.changeWorkingDirectory("/")
        }
    }

    override fun renameTo(src: String, dst: String) = withClient { client ->
        log { "renameTo: from $src to $dst" }
        if (client.rename(src, dst).not()) throw IOException("Failed to rename file from $src to $dst.")
    }

    override fun upload(src: String, dst: String, onUploading: (read: Long, total: Long) -> Unit) = withClientRetry { client ->
        val name = PathUtil.getFileName(src)
        val dstPath = "$dst/$name"
        log { "upload: $src to $dstPath" }
        val srcFile = File(src)
        val srcFileSize = srcFile.length()
        val srcInputStream = FileInputStream(srcFile)
        val countingStream = CountingInputStreamImpl(srcInputStream, srcFileSize) { read, total -> onUploading(read, total) }
        // storeFile 返回 false 表示传输未被服务端确认（如中途 426 中止），此时可能已写入部分字节，
        // 必须显式判失败，否则截断的归档会被当作上传成功
        if (client.storeFile(dstPath, countingStream).not()) throw IOException("Failed to write remote file.")
        srcInputStream.close()
        countingStream.close()
        if (countingStream.byteCount == 0L) throw IOException("Failed to write remote file: 0 byte.")
        onUploading(countingStream.byteCount, countingStream.byteCount)
    }

    override fun download(src: String, dst: String, onDownloading: (written: Long, total: Long) -> Unit) = withClient { client ->
        val name = PathUtil.getFileName(src)
        val dstPath = "$dst/$name"
        log { "download: $src to $dstPath" }
        val dstFile = File(dstPath)
        val srcInputStream: InputStream = client.retrieveFileStream(src)
        val dstOutPutStream: OutputStream = dstFile.outputStream()
        val countingStream = CountingOutputStreamImpl(dstOutPutStream, -1) { written, total -> onDownloading(written, total) }
        srcInputStream.copyTo(countingStream)
        client.completePendingCommand()
        srcInputStream.close()
        dstOutPutStream.close()
        countingStream.close()
        onDownloading(countingStream.byteCount, countingStream.byteCount)
    }

    override fun deleteFile(src: String) = withClient { client ->
        log { "deleteFile: $src" }
        if (client.deleteFile(src).not()) throw IOException("Failed to delete file: $src.")
    }

    override fun removeDirectory(src: String) = withClient { client ->
        log { "removeDirectory: $src" }
        if (client.removeDirectory(src).not()) throw IOException("Failed to remove dir: $src.")
    }

    override fun clearEmptyDirectoriesRecursively(src: String) = withClient { client ->
        val srcFile = listFile(src)
        if (srcFile.isDirectory) {
            val emptyDirs = mutableListOf<String>()
            val paths = mutableListOf(src)

            while (paths.isNotEmpty()) {
                val dir = paths.removeAt(0)
                val files = client.listFiles(dir)
                if (files.isEmpty()) {
                    emptyDirs.add(dir)
                } else {
                    for (file in files) {
                        val path = "${dir}/${file.name}"
                        if (file.isDirectory) {
                            paths.add(path)
                        }
                    }
                }
            }

            // Remove reversed empty dirs.
            for (path in emptyDirs.reversed()) removeDirectory(path)
        }
    }

    private fun listFile(src: String): FTPFile {
        var srcFile: FTPFile? = null
        withClient { client ->
            srcFile = client.mlistFile(src)
            if (srcFile == null) {
                srcFile = client.listFiles(runCatching { PathUtil.getParentPath(src) }.getOrElse { "." })
                    .firstOrNull { it.name == PathUtil.getFileName(src) }
            }
        }
        if (srcFile != null) {
            return srcFile!!
        } else {
            throw IOException("$src not found.")
        }
    }

    /**
     * Actually this is not a recursive function,
     * just keep this name to make it easier to understand.
     */
    override fun deleteRecursively(src: String) = withClient { client ->
        val srcFile = listFile(src)
        if (srcFile.isDirectory.not()) {
            deleteFile(src)
        } else {
            val dirs = mutableListOf(src)
            val paths = mutableListOf(src)

            // Delete files and append all empty dirs.
            while (paths.isNotEmpty()) {
                val dir = paths.first()
                val files = client.listFiles(dir)
                for (file in files) {
                    val path = "${dir}/${file.name}"
                    if (file.isDirectory.not()) {
                        deleteFile(path)
                    } else {
                        paths.add(path)
                        dirs.add(path)
                    }
                }
                paths.removeFirstOrNull()
            }

            // Remove reversed empty dirs.
            for (path in dirs.reversed()) removeDirectory(path)
        }
    }

    override fun listFiles(src: String): DirChildrenParcelable {
        val files = mutableListOf<FileParcelable>()
        val directories = mutableListOf<FileParcelable>()
        withClient { client ->
            val clientFiles = client.listFiles(src)
            for (file in clientFiles) {
                val creationTime = file.timestamp.timeInMillis
                val fileParcelable = FileParcelable(file.name, creationTime)
                if (file.isSymbolicLink) {
                    fileParcelable.link = file.link
                }
                if (file.isDirectory) directories.add(fileParcelable)
                else files.add(fileParcelable)
            }
        }
        files.sortBy { it.name }
        directories.sortBy { it.name }
        return DirChildrenParcelable(files = files, directories = directories)
    }

    override fun walkFileTree(src: String): List<PathParcelable> {
        val pathParcelableList = mutableListOf<PathParcelable>()
        val srcFile = listFile(src)
        if (srcFile.isDirectory.not()) {
            pathParcelableList.add(PathParcelable(src))
        } else {
            val files = listFiles(src)
            for (i in files.files) {
                pathParcelableList.add(PathParcelable("${src}/${i.name}"))
            }
            for (i in files.directories) {
                pathParcelableList.addAll(walkFileTree("${src}/${i.name}"))
            }
        }
        return pathParcelableList
    }

    override fun exists(src: String): Boolean = runCatching { listFile(src) }.isSuccess

    override fun size(src: String): Long {
        var size = 0L
        withClient { client ->
            val srcFile = listFile(src)
            if (srcFile.isDirectory.not()) {
                size += client.getSize(src)?.toLongOrNull() ?: 0
            } else {
                val files = listFiles(src)
                for (i in files.files) {
                    size += client.getSize("${src}/${i.name}")?.toLongOrNull() ?: 0
                }
                for (i in files.directories) {
                    size("${src}/${i.name}")
                }
            }
        }
        log { "size: $size, $src" }
        return size
    }

    override suspend fun testConnection() {
        connect()
        disconnect()
    }

    private fun handleOriginalPath(path: String): String = run {
        val pathSplit = path.toPathList().toMutableList()
        // Remove “$Cloud:”
        pathSplit.removeFirstOrNull()
        pathSplit.toPathString()
    }

    override suspend fun setRemote(context: Context, onSet: suspend (remote: String, extra: String) -> Unit) {
        val extra = entity.getExtraEntity<FTPExtra>()!!
        connect()
        val prefix = "${context.getString(R.string.cloud)}:"
        val pickYou = PickYouLauncher(
            checkPermission = false,
            traverseBackend = { listFiles(it.replaceFirst(prefix, "")) },
            mkdirsBackend = { parent, child ->
                runCatching { mkdirRecursively(handleOriginalPath("$parent/$child")) }.isSuccess
            },
            title = context.getString(R.string.select_target_directory),
            pickerType = PickerType.DIRECTORY,
            rootPathList = listOf(prefix),
            defaultPathList = listOf(prefix),
        )
        withMainContext {
            val pathString = pickYou.awaitLaunch(context)
            onSet(handleOriginalPath(pathString), GsonUtil().toJson(extra))
        }
        disconnect()
    }
}
