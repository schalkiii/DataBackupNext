package com.xayah.core.data.repository

import android.content.Context
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.model.CompressionType
import com.xayah.core.model.OpType
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.PackageExtraInfo
import com.xayah.core.model.database.PackageIndexInfo
import com.xayah.core.model.database.PackageInfo
import com.xayah.core.model.database.PackageStorageStats
import com.xayah.core.model.database.VersionInfo
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.PathUtil
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

class AppsRepoBackupIndexTest {

    private val repository = AppsRepo(
        context = mockk<Context>(relaxed = true),
        defaultDispatcher = Dispatchers.Default,
        appsDao = mockk<PackageDao>(relaxed = true),
        packageRepo = mockk<PackageRepository>(relaxed = true),
        rootService = mockk<RemoteRootService>(relaxed = true),
        settingsDataRepo = mockk<SettingsDataRepo>(relaxed = true),
        pathUtil = mockk<PathUtil>(relaxed = true),
        cloudRepo = mockk<CloudRepository>(relaxed = true),
    )

    // 构造 RESTORE 备份实体：同 pkgUserKey 多实体代表多副本
    private fun buildRestoreEntity(lastBackupTime: Long, versionCode: Long, versionName: String = "1.0", packageName: String = "com.example.app", userId: Int = 0): PackageEntity = PackageEntity(
        id = 1L,
        indexInfo = PackageIndexInfo(
            opType = OpType.RESTORE,
            packageName = packageName,
            userId = userId,
            compressionType = CompressionType.TAR,
            preserveId = 0L,
            cloud = "WebDAV",
            backupDir = "/backup",
        ),
        packageInfo = PackageInfo(
            label = "Example",
            versionName = versionName,
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
    fun `备份页基线取台账最近备份版本`() {
        val index = repository.getBackupIndexes(
            copies = listOf(
                buildRestoreEntity(lastBackupTime = 100L, versionCode = 10L, versionName = "1.0"),
                buildRestoreEntity(lastBackupTime = 200L, versionCode = 12L, versionName = "1.2"),
            ),
        )["com.example.app-0"]!!

        assertEquals(12L, index.backedUpVersionCode)
        assertEquals("1.2", index.backedUpVersionName)
        assertEquals(200L, index.lastBackupTime)
        assertEquals(2, index.copyCount)
    }

    @Test
    fun `恢复页基线改用本机已安装版本`() {
        val index = repository.getBackupIndexes(
            copies = listOf(buildRestoreEntity(lastBackupTime = 100L, versionCode = 12L)),
            baselineVersions = mapOf("com.example.app-0" to VersionInfo(versionCode = 10L, versionName = "1.0")),
        )["com.example.app-0"]!!

        // 云端版本 12 高于本机 10，勾选"云端有更新"时应命中
        assertEquals(10L, index.backedUpVersionCode)
        assertEquals("1.0", index.backedUpVersionName)
        assertEquals(100L, index.lastBackupTime)
        assertEquals(1, index.copyCount)
    }

    @Test
    fun `恢复页本机未安装时基线为0`() {
        val index = repository.getBackupIndexes(
            copies = listOf(buildRestoreEntity(lastBackupTime = 100L, versionCode = 12L)),
            baselineVersions = emptyMap(),
        )["com.example.app-0"]!!

        assertEquals(0L, index.backedUpVersionCode)
        assertEquals("", index.backedUpVersionName)
    }

    @Test
    fun `空副本集返回空台账`() {
        val indexes = repository.getBackupIndexes(copies = listOf())

        assertEquals(0, indexes.size)
    }

    @Test
    fun `副本筛选只保留同包名同用户并按备份时间倒序`() {
        val app = buildRestoreEntity(lastBackupTime = 100L, versionCode = 10L)
        val copies = listOf(
            buildRestoreEntity(lastBackupTime = 300L, versionCode = 13L),
            buildRestoreEntity(lastBackupTime = 100L, versionCode = 10L),
            buildRestoreEntity(lastBackupTime = 200L, versionCode = 11L),
            // 其他应用与其他用户的实体不应入选
            buildRestoreEntity(lastBackupTime = 400L, versionCode = 99L, packageName = "com.example.other"),
            buildRestoreEntity(lastBackupTime = 400L, versionCode = 99L, userId = 10),
        )

        val selected = copies.selectCopiesOf(app)

        assertEquals(listOf(300L, 200L, 100L), selected.map { it.extraInfo.lastBackupTime })
    }
}
