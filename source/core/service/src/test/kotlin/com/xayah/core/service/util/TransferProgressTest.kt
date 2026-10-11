package com.xayah.core.service.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TransferProgressTest {

    @Test
    fun `进度按区间映射且只增不减`() {
        // 上传阶段从压缩存量 0.5 起步，上限 1
        val reporter = TransferProgress(base = 0.5f, cap = 1f)
        assertEquals(0.5f, reporter.current, 1e-6f)

        reporter.report(0.5f) // 0.5 + (1 - 0.5) * 0.5 = 0.75
        assertEquals(0.75f, reporter.current, 1e-6f)

        // 断线重传让底层计数归零，上报 0 不应使进度回退
        reporter.report(0f)
        assertEquals(0.75f, reporter.current, 1e-6f)

        reporter.report(1f)
        assertEquals(1f, reporter.current, 1e-6f)
    }

    @Test
    fun `按字节上报时读取字节超过总字节也不越界`() {
        val reporter = TransferProgress()
        reporter.report(read = 120L, total = 100L)
        assertTrue(reporter.current <= 1f)
    }

    @Test
    fun `总字节非正数时不推进进度`() {
        val reporter = TransferProgress()
        reporter.report(read = 50L, total = 0L)
        assertEquals(0f, reporter.current, 1e-6f)
    }

    @Test
    fun `压缩存量值作为上传起点`() {
        val reporter = TransferProgress(base = 0.5f, cap = 1f)
        reporter.report(0f)
        // 即使上传从 0 重传，起点仍保持压缩结束时的存量
        assertEquals(0.5f, reporter.current, 1e-6f)
    }

    @Test
    fun `估算归档比优先取参考值并限制在合理区间`() {
        // 上次归档 50 / 源 100 = 0.5
        assertEquals(0.5f, estimateArchiveRatio(displayBytes = 50L, dataBytes = 100L), 1e-6f)
        // 无参考值兜底 0.5
        assertEquals(0.5f, estimateArchiveRatio(displayBytes = 0L, dataBytes = 0L), 1e-6f)
        // 极小压缩比下限钳制到 0.05
        assertEquals(0.05f, estimateArchiveRatio(displayBytes = 1L, dataBytes = 1000L), 1e-6f)
    }

    @Test
    fun `轮询返回业务结果并容忍上报异常`() = runBlocking {
        val ticks = AtomicInteger(0)
        val result = withProgressTicker(intervalMillis = 10L, onTick = {
            ticks.incrementAndGet()
            error("进度上报失败不应影响备份")
        }) {
            delay(50L)
            "done"
        }
        assertEquals("done", result)
        assertTrue(ticks.get() > 0)
    }

    @Test
    fun `业务结束后轮询协程立即停止不再上报`() = runBlocking {
        val ticks = AtomicInteger(0)
        withProgressTicker(intervalMillis = 10L, onTick = { ticks.incrementAndGet() }) {
            delay(30L)
        }
        // 收尾后读取计数，再等待超过轮询间隔，计数不应继续增长
        val settled = ticks.get()
        delay(60L)
        assertEquals(settled, ticks.get())
    }
}
