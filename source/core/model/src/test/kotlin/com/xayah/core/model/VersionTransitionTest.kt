package com.xayah.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VersionTransitionTest {

    private fun buildApp(
        isOutdated: Boolean = true,
        versionName: String = "2.1.0",
        backedUpVersionName: String = "2.0.5",
    ) = App(
        id = 1L,
        packageName = "com.example.app",
        label = "Example",
        preserveId = 0L,
        isSystemApp = false,
        selectionFlag = 0,
        selected = false,
        isOutdated = isOutdated,
        versionName = versionName,
        backedUpVersionName = backedUpVersionName,
    )

    @Test
    fun `备份页展示本机版本到备份版本`() {
        assertEquals("2.1.0 → 2.0.5", buildVersionTransition(OpType.BACKUP, buildApp()))
    }

    @Test
    fun `恢复页展示本机版本到云端版本`() {
        assertEquals("2.0.5 → 2.1.0", buildVersionTransition(OpType.RESTORE, buildApp()))
    }

    @Test
    fun `非待更新时不展示版本差`() {
        assertNull(buildVersionTransition(OpType.BACKUP, buildApp(isOutdated = false)))
    }

    @Test
    fun `任一侧版本名缺失时不展示版本差`() {
        assertNull(buildVersionTransition(OpType.BACKUP, buildApp(versionName = "")))
        assertNull(buildVersionTransition(OpType.RESTORE, buildApp(backedUpVersionName = "")))
    }
}
