package com.xayah.core.service.packages.backup

import com.xayah.core.model.CompressionType
import com.xayah.core.model.DefaultPreserveId
import com.xayah.core.model.OpType
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.PackageExtraInfo
import com.xayah.core.model.database.PackageIndexInfo
import com.xayah.core.model.database.PackageInfo
import com.xayah.core.model.database.PackageStorageStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectCopiesToPruneTest {

    // 构造实体：preserveId=0 为主备份，其余为时间戳副本
    private fun buildEntity(id: Long, preserveId: Long, lastBackupTime: Long): PackageEntity = PackageEntity(
        id = id,
        indexInfo = PackageIndexInfo(
            opType = OpType.RESTORE,
            packageName = "com.example.app",
            userId = 0,
            compressionType = CompressionType.TAR,
            preserveId = preserveId,
            cloud = "",
            backupDir = "",
        ),
        packageInfo = PackageInfo(
            label = "Example",
            versionName = "1.0",
            versionCode = 10L,
            flags = 0,
            firstInstallTime = 0L,
            lastUpdateTime = 0L,
        ),
        extraInfo = PackageExtraInfo(
            uid = 10000,
            hasKeystore = false,
            permissions = listOf(),
            ssaid = "",
            lastBackupTime = lastBackupTime,
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
    fun `保留副本数不超过1时禁用多副本不做裁剪`() {
        val copies = listOf(
            buildEntity(id = 1, preserveId = 0L, lastBackupTime = 100L),
            buildEntity(id = 2, preserveId = 200L, lastBackupTime = 200L),
        )

        assertTrue(selectCopiesToPrune(copies = copies, retainCopies = 1).isEmpty())
        assertTrue(selectCopiesToPrune(copies = copies, retainCopies = 0).isEmpty())
    }

    @Test
    fun `副本数量未超保留数时不裁剪`() {
        val copies = listOf(
            buildEntity(id = 1, preserveId = 100L, lastBackupTime = 100L),
            buildEntity(id = 2, preserveId = 200L, lastBackupTime = 200L),
        )

        // 保留 2 份副本（retainCopies=3 → 副本预算 2）而实际只有 2 份
        assertTrue(selectCopiesToPrune(copies = copies, retainCopies = 3).isEmpty())
    }

    @Test
    fun `超出保留数时删除最旧副本保留最新`() {
        val copies = listOf(
            buildEntity(id = 1, preserveId = 100L, lastBackupTime = 100L),
            buildEntity(id = 2, preserveId = 200L, lastBackupTime = 200L),
            buildEntity(id = 3, preserveId = 300L, lastBackupTime = 300L),
        )

        // 保留 2 份副本（retainCopies=3 → 副本预算 2），最旧的 100L 出局
        val pruned = selectCopiesToPrune(copies = copies, retainCopies = 3)

        assertEquals(listOf(1L), pruned.map { it.id })
    }

    @Test
    fun `主备份不参与裁剪即使时间最旧`() {
        val main = buildEntity(id = 1, preserveId = DefaultPreserveId, lastBackupTime = 50L)
        val copies = listOf(
            main,
            buildEntity(id = 2, preserveId = 200L, lastBackupTime = 100L),
            buildEntity(id = 3, preserveId = 300L, lastBackupTime = 300L),
        )

        // 保留 1 份副本（retainCopies=2 → 副本预算 1），最旧副本 100L 出局，主备份保留
        val pruned = selectCopiesToPrune(copies = copies, retainCopies = 2)

        assertEquals(listOf(2L), pruned.map { it.id })
    }
}
