package com.xayah.core.network.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CountingStreamsTest {

    @Test
    fun `输入流按实际读取字节数计数`() {
        val payload = ByteArray(10) { it.toByte() }

        CountingInputStreamImpl(ByteArrayInputStream(payload), fileSize = payload.size.toLong()) { _, _ -> }.use { stream ->
            val buffer = ByteArray(4)
            while (stream.read(buffer) != -1) {
                // 分块消费直至 EOF
            }
            assertEquals(payload.size.toLong(), stream.byteCount)
        }
    }

    @Test
    fun `输出流按实际写入字节数计数`() {
        CountingOutputStreamImpl(ByteArrayOutputStream(), fileSize = 8L) { _, _ -> }.use { stream ->
            stream.write(ByteArray(8))
            assertEquals(8L, stream.byteCount)
        }
    }

    @Test
    fun `进度回调携带累计值与总大小`() {
        val latch = CountDownLatch(1)
        var reportedRead = -1L
        var reportedTotal = -1L

        CountingInputStreamImpl(ByteArrayInputStream(ByteArray(5)), fileSize = 5L) { read, total ->
            reportedRead = read
            reportedTotal = total
            latch.countDown()
        }.use { stream ->
            stream.readBytes()
            // 首次回调由定时器零延迟触发
            assertTrue(latch.await(3, TimeUnit.SECONDS))
        }

        assertEquals(5L, reportedTotal)
        assertTrue(reportedRead in 0..5L)
    }
}
