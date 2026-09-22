package com.xayah.core.data.repository

import android.content.Context
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.model.CompressionType
import com.xayah.core.model.OpType
import com.xayah.core.model.SortType
import com.xayah.core.model.database.BackupIndex
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.PackageExtraInfo
import com.xayah.core.model.database.PackageIndexInfo
import com.xayah.core.model.database.PackageInfo
import com.xayah.core.model.database.PackageStorageStats
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.PathUtil
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageRepositoryPredicatesTest {

    private val repository = PackageRepository(
        context = mockk<Context>(relaxed = true),
        rootService = mockk<RemoteRootService>(relaxed = true),
        cloudRepository = mockk<CloudRepository>(relaxed = true),
        packageDao = mockk<PackageDao>(relaxed = true),
        pathUtil = mockk<PathUtil>(relaxed = true),
    )

    private fun buildEntity(lastBackupTime: Long = 100L): PackageEntity = PackageEntity(
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
    fun `updated谓词value为true勾选时仅保留待更新集合`() {
        val predicate = repository.getUpdatedPredicate(value = true, outdatedSet = setOf("com.example.app-0"))
        val other = buildEntity().copy(indexInfo = buildEntity().indexInfo.copy(packageName = "com.other.app"))

        assertTrue(predicate(buildEntity()))
        assertFalse(predicate(other))
    }

    @Test
    fun `updated谓词value为false未勾选时全部通过`() {
        val predicate = repository.getUpdatedPredicate(value = false, outdatedSet = setOf("com.other.app-0"))

        assertTrue(predicate(buildEntity()))
    }

    @Test
    fun `hasBackups谓词value为false时仅保留无备份的`() {
        val predicate = repository.getHasBackupsPredicate(value = false, pkgUserSet = setOf("com.example.app-0"))

        assertFalse(predicate(buildEntity()))
    }

    @Test
    fun `hasNoBackups谓词value为false时仅保留有备份的`() {
        val predicate = repository.getHasNoBackupsPredicate(value = false, pkgUserSet = setOf("com.example.app-0"))

        assertTrue(predicate(buildEntity()))
    }

    @Test
    fun `installed谓词value为false时仅保留未安装的`() {
        val predicate = repository.getInstalledPredicate(value = false, pkgUserSet = setOf("com.example.app-0"))

        assertFalse(predicate(buildEntity()))
    }

    @Test
    fun `notInstalled谓词value为false时仅保留已安装的`() {
        val predicate = repository.getNotInstalledPredicate(value = false, pkgUserSet = setOf("com.example.app-0"))

        assertTrue(predicate(buildEntity()))
    }

    @Test
    fun `按上次备份时间升序排序`() {
        val early = buildEntity(lastBackupTime = 100L)
        val late = buildEntity(lastBackupTime = 200L)
        val comparator = repository.getSortComparatorNew(sortIndex = 3, sortType = SortType.ASCENDING)

        assertTrue(comparator.compare(early, late) < 0)
    }

    @Test
    fun `按上次备份时间降序排序`() {
        val early = buildEntity(lastBackupTime = 100L)
        val late = buildEntity(lastBackupTime = 200L)
        val comparator = repository.getSortComparatorNew(sortIndex = 3, sortType = SortType.DESCENDING)

        assertTrue(comparator.compare(early, late) > 0)
    }

    private fun backupIndex(lastBackupTime: Long) = BackupIndex(
        lastBackupTime = lastBackupTime,
        backedUpVersionCode = 10L,
        backedUpVersionName = "1.0",
        copyCount = 1,
    )

    @Test
    fun `lastBackupDays谓词值小于等于0时全部通过`() {
        val predicate = repository.getLastBackupOlderThanPredicate(value = 0, indexMap = mapOf("com.example.app-0" to backupIndex(100L)))

        assertTrue(predicate(buildEntity()))
    }

    @Test
    fun `lastBackupDays谓词未备份时视为超期以纳入管理`() {
        val predicate = repository.getLastBackupOlderThanPredicate(value = 30, indexMap = mapOf("com.example.app-0" to backupIndex(0L)))

        assertTrue(predicate(buildEntity()))
    }

    @Test
    fun `lastBackupDays谓词无索引条目时视为未备份纳入管理`() {
        val predicate = repository.getLastBackupOlderThanPredicate(value = 30, indexMap = mapOf())

        assertTrue(predicate(buildEntity()))
    }

    @Test
    fun `lastBackupDays谓词备份超过阈值天数时通过`() {
        val old = System.currentTimeMillis() - 31 * 24 * 60 * 60 * 1000L
        val predicate = repository.getLastBackupOlderThanPredicate(value = 30, indexMap = mapOf("com.example.app-0" to backupIndex(old)))

        assertTrue(predicate(buildEntity()))
    }

    @Test
    fun `lastBackupDays谓词备份未超过阈值天数时不通过`() {
        val recent = System.currentTimeMillis() - 1 * 24 * 60 * 60 * 1000L
        val predicate = repository.getLastBackupOlderThanPredicate(value = 30, indexMap = mapOf("com.example.app-0" to backupIndex(recent)))

        assertFalse(predicate(buildEntity()))
    }
}
