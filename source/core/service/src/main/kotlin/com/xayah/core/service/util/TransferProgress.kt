package com.xayah.core.service.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * 数据项进度跟踪器：进度只增不减。
 * 断线重传/重试会让底层字节计数从零重来，直接上报会造成界面百分比回跳，
 * 这里以历史最大值兜底；[base] 为当前已达成的进度（如压缩阶段的进度），
 * 本阶段进度按 [base, cap] 区间换算，保证压缩→上传全程连续递增。
 */
internal class TransferProgress(private val base: Float = 0f, private val cap: Float = 1f) {
    @Volatile
    private var value: Float = base.coerceIn(0f, 1f)

    /**
     * 按本阶段完成比例（0..1）推进进度。
     */
    fun report(ratio: Float) {
        val fraction = base + (cap - base) * ratio.coerceIn(0f, 1f)
        if (fraction > value) value = fraction
    }

    fun report(read: Long, total: Long) {
        if (total > 0) report(read.toFloat() / total)
    }

    val current: Float
        get() = value
}

/**
 * 归档增长率估算（归档大小/源大小）：优先取上次备份的实际值，
 * 首次备份没有参考值时按 0.5 估，仅用于驱动进度条，不影响备份结果。
 */
internal fun estimateArchiveRatio(displayBytes: Long, dataBytes: Long): Float =
    if (dataBytes > 0) (displayBytes.toFloat() / dataBytes).coerceIn(0.05f, 1f) else 0.5f

/**
 * 轮询上报进度直至 [block] 返回。
 * 结束后先停掉轮询协程再返回，避免过期的轮询写入覆盖收尾状态
 * （如上传收尾的 100% 被旧百分比回写覆盖，界面百分比倒退）。
 * 进度上报失败不影响备份本身，仅终止本轮上报。
 */
internal suspend fun <T> withProgressTicker(
    intervalMillis: Long = 500,
    onTick: suspend () -> Unit,
    block: suspend () -> T,
): T {
    val ticker = CoroutineScope(coroutineContext).launch {
        while (true) {
            try {
                onTick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 进度上报失败不影响备份本身
            }
            delay(intervalMillis)
        }
    }
    try {
        return block()
    } finally {
        ticker.cancel()
        ticker.join()
    }
}
