package com.xayah.core.model

import com.xayah.core.model.util.formatSize
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class UnitTest {
    private val originalLocale = Locale.getDefault()

    // DecimalFormat 取默认 locale 的小数点符号，固定为 US 保证断言确定性
    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `formatSize 按量级分档并保留两位小数`() {
        assertEquals("0.00 Bytes", 0.0.formatSize())
        // 恰好 1KB 不满足 >kb 边界，仍按 Bytes 显示
        assertEquals("1024.00 Bytes", 1024.0.formatSize())
        assertEquals("100.00 KB", 102400.0.formatSize())
        assertEquals("2.00 MB", (2.0 * 1024 * 1024).formatSize())
        assertEquals("1.50 GB", (1.5 * 1024 * 1024 * 1024).formatSize())
    }
}
