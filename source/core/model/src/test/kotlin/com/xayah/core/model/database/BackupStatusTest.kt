package com.xayah.core.model.database

import com.xayah.core.model.CompressionType
import com.xayah.core.model.OpType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupStatusTest {

    private fun buildEntity(versionCode: Long = 10L): PackageEntity = PackageEntity(
        id = 1L,
        indexInfo = PackageIndexInfo(
            opType = OpType.BACKUP,
            packageName = "com.example.app",
            userId = 0,
            compressionType = CompressionType.TAR,
            preserveId = 0L,
            cloud = "",
            backupDir = "",
        ),
        packageInfo = PackageInfo(
            label = "Example",
            versionName = "1.0",
            versionCode = versionCode,
            flags = 0,
            firstInstallTime = 0L,
            lastUpdateTime = 0L,
        ),
        extraInfo = PackageExtraInfo(
            uid = 10000,
            hasKeystore = false,
            permissions = listOf(),
            ssaid = "",
            lastBackupTime = 100L,
            blocked = false,
            activated = false,
            firstUpdated = true,
            enabled = true,
        ),
        dataStates = PackageDataStates(),
        storageStats = PackageStorageStats(),
        dataStats = PackageDataStats(),
        displayStats = PackageDataStats(),
    )

    @Test
    fun `当前版本高于备份版本时判定为待更新`() {
        val index = BackupIndex(lastBackupTime = 100L, backedUpVersionCode = 10L, backedUpVersionName = "1.0", copyCount = 3)
        val app = buildEntity(versionCode = 11L).toAppWithStatus(index = index)

        assertTrue(app.isOutdated)
        assertEquals(100L, app.lastBackupTime)
        assertEquals(10L, app.backedUpVersionCode)
        assertEquals(3, app.copyCount)
        assertEquals("1.0", app.versionName)
        assertEquals("1.0", app.backedUpVersionName)
    }

    @Test
    fun `当前版本等于备份版本时判定为非待更新`() {
        val index = BackupIndex(lastBackupTime = 100L, backedUpVersionCode = 11L, backedUpVersionName = "1.1", copyCount = 1)
        val app = buildEntity(versionCode = 11L).toAppWithStatus(index = index)

        assertFalse(app.isOutdated)
    }

    @Test
    fun `当前版本低于备份版本时判定为非待更新`() {
        val index = BackupIndex(lastBackupTime = 100L, backedUpVersionCode = 12L, backedUpVersionName = "1.2", copyCount = 1)
        val app = buildEntity(versionCode = 11L).toAppWithStatus(index = index)

        assertFalse(app.isOutdated)
    }

    @Test
    fun `无台账记录时字段为默认值`() {
        val app = buildEntity(versionCode = 11L).toAppWithStatus(index = null)

        assertFalse(app.isOutdated)
        assertEquals(0L, app.lastBackupTime)
        assertEquals(0L, app.backedUpVersionCode)
        assertEquals(0, app.copyCount)
    }

    @Test
    fun `asExternalModel 保持纯函数默认值`() {
        val app = buildEntity(versionCode = 11L).asExternalModel()

        assertFalse(app.isOutdated)
        assertEquals(0L, app.lastBackupTime)
        assertEquals(0L, app.backedUpVersionCode)
        assertEquals(0, app.copyCount)
    }

    @Test
    fun `cloudIndexKey 由包名用户副本与压缩类型组成`() {
        val entity = buildEntity()

        assertEquals("com.example.app-0-0-tar", entity.cloudIndexKey)
    }
}
