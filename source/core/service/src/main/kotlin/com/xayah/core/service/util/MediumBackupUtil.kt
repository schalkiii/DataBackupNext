package com.xayah.core.service.util

import android.content.Context
import com.xayah.core.common.util.toLineString
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.datastore.readCompressionLevel
import com.xayah.core.datastore.readFollowSymlinks
import com.xayah.core.model.DataType
import com.xayah.core.model.OperationState
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.TaskDetailMediaEntity
import com.xayah.core.model.util.getCompressPara
import com.xayah.core.network.client.CloudClient
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.command.Tar
import com.xayah.core.util.model.ShellResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class MediumBackupUtil @Inject constructor(
    @ApplicationContext val context: Context,
    private val rootService: RemoteRootService,
    private val taskDao: TaskDao,
    private val mediaRepository: MediaRepository,
    private val commonBackupUtil: CommonBackupUtil,
    private val cloudRepository: CloudRepository,
) {
    companion object {
        private const val TAG = "MediumBackupUtil"
    }

    private fun log(onMsg: () -> String): String = run {
        val msg = onMsg()
        LogUtil.log { TAG to msg }
        msg
    }

    private fun MediaEntity.getDataBytes() = mediaInfo.dataBytes

    private fun MediaEntity.setDataBytes(sizeBytes: Long) = run { mediaInfo.dataBytes = sizeBytes }
    private fun MediaEntity.setDisplayBytes(sizeBytes: Long) = run { mediaInfo.displayBytes = sizeBytes }
    private fun MediaEntity.getDisplayBytes() = mediaInfo.displayBytes

    private fun TaskDetailMediaEntity.getLog() = mediaInfo.log

    private suspend fun TaskDetailMediaEntity.updateInfo(
        state: OperationState? = null,
        bytes: Long? = null,
        log: String? = null,
        content: String? = null,
        progress: Float? = null,
    ) = run {
        mediaInfo.also {
            if (state != null) it.state = state
            if (bytes != null) it.bytes = bytes
            if (log != null) it.log = log
            if (content != null) it.content = content
            if (progress != null) it.progress = progress
        }
        taskDao.upsert(this)
    }

    /**
     * [compressProgressCap] 压缩阶段在该数据项进度中的上限：
     * 压缩完成后还需上传时应小于 1，为上传阶段留出区间。
     */
    suspend fun backupMedia(m: MediaEntity, t: TaskDetailMediaEntity, r: MediaEntity?, dstDir: String, compressProgressCap: Float = 0.5f): ShellResult = run {
        log { "Backing up ${DataType.MEDIA_MEDIA.type}..." }

        val name = m.name
        val ct = m.indexInfo.compressionType
        val dst = mediaRepository.getArchiveDst(dstDir = dstDir, ct = ct)
        var isSuccess = true
        val out = mutableListOf<String>()
        val src = m.path
        val srcDir = PathUtil.getParentPath(src)

        // Check the existence of origin path.
        rootService.exists(src).also {
            if (it.not()) {
                isSuccess = false
                out.add(log { "Not exist: $src" })
                t.updateInfo(state = OperationState.ERROR, log = out.toLineString())
                return@run ShellResult(code = -1, input = listOf(), out = out)
            }
        }

        val sizeBytes = rootService.calculateSize(src)
        t.updateInfo(state = OperationState.PROCESSING, bytes = sizeBytes)
        if (rootService.exists(dst) && sizeBytes == r?.getDataBytes()) {
            t.updateInfo(state = OperationState.SKIP)
            out.add(log { "Data has not changed." })
        } else {
            // 压缩期间按归档增长估算进度并持续上报，长压缩不再长时间无反馈
            val reporter = TransferProgress(cap = compressProgressCap)
            val ratio = estimateArchiveRatio(displayBytes = r?.getDisplayBytes() ?: 0, dataBytes = r?.getDataBytes() ?: 0)
            withProgressTicker(onTick = {
                val archiveBytes = rootService.calculateSize(dst)
                reporter.report(if (sizeBytes > 0) archiveBytes.toFloat() / (sizeBytes * ratio) else 0f)
                t.updateInfo(content = "${(reporter.current * 100).toInt()}%", progress = reporter.current)
            }) {
                Tar.compress(
                    exclusionList = listOf(),
                    h = if (context.readFollowSymlinks().first()) "-h" else "",
                    srcDir = srcDir,
                    src = PathUtil.getFileName(src),// the name is not always the actual file name of the source,but the src does contain
                    dst = dst,
                    extra = ct.getCompressPara(context.readCompressionLevel().first())
                )
            }.also { result ->
                isSuccess = result.isSuccess
                out.addAll(result.out)
            }
            commonBackupUtil.testArchive(src = dst, ct = ct).also { result ->
                isSuccess = isSuccess && result.isSuccess
                out.addAll(result.out)
                if (result.isSuccess) {
                    m.setDataBytes(sizeBytes)
                    m.setDisplayBytes(rootService.calculateSize(dst))
                }
            }
        }

        t.updateInfo(state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = out.toLineString())

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }

    suspend fun upload(client: CloudClient, m: MediaEntity, t: TaskDetailMediaEntity, srcDir: String, dstDir: String) = run {
        val ct = m.indexInfo.compressionType
        val src = mediaRepository.getArchiveDst(dstDir = srcDir, ct = ct)
        t.updateInfo(state = OperationState.UPLOADING)

        // 上传进度从压缩阶段的存量值续走，断线重传计数归零时取最大值兜底，百分比不回跳；
        // 收尾写入在轮询停止后进行，避免过期轮询把 100% 覆盖回旧值
        val reporter = TransferProgress(base = t.mediaInfo.progress.coerceIn(0f, 1f))
        val result = withProgressTicker(onTick = {
            t.updateInfo(content = "${(reporter.current * 100).toInt()}%", progress = reporter.current)
        }) {
            cloudRepository.upload(client = client, src = src, dstDir = dstDir, onUploading = { read, total -> reporter.report(read, total) })
        }
        t.updateInfo(state = if (result.isSuccess) OperationState.DONE else OperationState.ERROR, log = t.getLog() + "\n${result.outString}", content = "100%")
    }
}
