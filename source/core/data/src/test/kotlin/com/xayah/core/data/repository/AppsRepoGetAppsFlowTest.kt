package com.xayah.core.data.repository

import android.content.Context
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.model.CompressionType
import com.xayah.core.model.OpType
import com.xayah.core.model.SortType
import com.xayah.core.model.UserInfo
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.PackageExtraInfo
import com.xayah.core.model.database.PackageIndexInfo
import com.xayah.core.model.database.PackageInfo
import com.xayah.core.model.database.PackageStorageStats
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.PathUtil
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AppsRepoGetAppsFlowTest {

    private val appsDao = mockk<PackageDao>(relaxed = true)
    private val user0 = mockk<UserInfo>(relaxed = true).also { every { it.id } returns 0 }

    private val packageRepo = PackageRepository(
        context = mockk<Context>(relaxed = true),
        rootService = mockk<RemoteRootService>(relaxed = true),
        cloudRepository = mockk(relaxed = true),
        packageDao = mockk<PackageDao>(relaxed = true),
        pathUtil = mockk<PathUtil>(relaxed = true),
    )

    private val repository = AppsRepo(
        context = mockk<Context>(relaxed = true),
        defaultDispatcher = Dispatchers.Default,
        appsDao = appsDao,
        packageRepo = packageRepo,
        rootService = mockk<RemoteRootService>(relaxed = true),
        settingsDataRepo = mockk(relaxed = true),
        pathUtil = mockk<PathUtil>(relaxed = true),
        cloudRepo = mockk(relaxed = true),
    )

    private fun buildListData(searchQuery: String = "", cloud: String = "", backupDir: String = ""): ListData.Apps = ListData.Apps(
        selected = 0,
        total = 0,
        searchQuery = searchQuery,
        showFilterSheet = false,
        sortIndex = 0,
        sortType = SortType.ASCENDING,
        isUpdating = false,
        labels = setOf(),
        showDataItemsSheet = false,
        filters = Filters(
            cloud = cloud,
            backupDir = backupDir,
            showSystemApps = true,
            // 互补筛选对：与生产默认一致（双选 = 显示全部，取消一侧 = 只看另一侧）
            hasBackups = true,
            hasNoBackups = true,
            installedApps = true,
            notInstalledApps = true,
            updatedApps = false,
        ),
        userIndex = 0,
        userList = listOf(user0),
        userMap = mapOf(0 to 0L),
    )

    private fun buildEntity(id: Long, packageName: String, label: String, userId: Int = 0, activated: Boolean = false): PackageEntity = PackageEntity(
        id = id,
        indexInfo = PackageIndexInfo(
            opType = OpType.RESTORE,
            packageName = packageName,
            userId = userId,
            compressionType = CompressionType.TAR,
            preserveId = 0L,
            cloud = "",
            backupDir = "",
        ),
        packageInfo = PackageInfo(
            label = label,
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
            lastBackupTime = 100L,
            blocked = false,
            activated = activated,
            firstUpdated = true,
            enabled = true,
        ),
        dataStates = PackageDataStates(),
        storageStats = PackageStorageStats(),
        dataStats = PackageDataStats(),
        displayStats = PackageDataStats(),
    )

    private fun getAppsOf(listData: ListData.Apps) = repository.getApps(
        opType = OpType.RESTORE,
        listData = flowOf(listData),
        pkgUserVersions = flowOf(emptyMap()),
        refs = flowOf(emptyList()),
        labels = flowOf(emptySet()),
    )

    @Test
    fun `恢复页仅来源变化时重建查询_搜索变化不重建`() = runBlocking {
        val subscribeCount = AtomicInteger(0)
        every { appsDao.queryPackagesFlow(OpType.RESTORE, any(), any()) } answers {
            MutableStateFlow<List<PackageEntity>>(emptyList()).onStart { subscribeCount.incrementAndGet() }
        }
        val listData = MutableStateFlow(buildListData(cloud = "", backupDir = ""))
        val job = launch {
            repository.getApps(
                opType = OpType.RESTORE,
                listData = listData,
                pkgUserVersions = flowOf(emptyMap()),
                refs = flowOf(emptyList()),
                labels = flowOf(emptySet()),
            ).collect()
        }
        delay(500)
        assertEquals(1, subscribeCount.get())

        // 搜索/筛选变化不重建 Room 查询
        listData.value = buildListData(searchQuery = "keyword", cloud = "", backupDir = "")
        listData.value = buildListData(searchQuery = "keyword2", cloud = "", backupDir = "")
        delay(500)
        assertEquals(1, subscribeCount.get())

        // 来源（云端）切换重建查询
        listData.value = buildListData(searchQuery = "keyword2", cloud = "WebDAV", backupDir = "/backup")
        delay(500)
        assertEquals(2, subscribeCount.get())
        verify { appsDao.queryPackagesFlow(OpType.RESTORE, "WebDAV", "/backup") }
        job.cancel()
    }

    @Test
    fun `搜索关键词大小写不敏感匹配标签或包名`() = runBlocking {
        val example = buildEntity(id = 1, packageName = "com.example.app", label = "Example")
        val other = buildEntity(id = 2, packageName = "com.other.app", label = "Other")
        every { appsDao.queryPackagesFlow(OpType.RESTORE, any(), any()) } returns MutableStateFlow(listOf(example, other))

        val byLabel = getAppsOf(buildListData(searchQuery = "exa")).first()
        assertEquals(listOf("com.example.app"), byLabel.map { it.packageName })

        val byPackage = getAppsOf(buildListData(searchQuery = "OTHER.APP")).first()
        assertEquals(listOf("com.other.app"), byPackage.map { it.packageName })

        val none = getAppsOf(buildListData(searchQuery = "zzz")).first()
        assertEquals(emptyList<String>(), none.map { it.packageName })
    }

    @Test
    fun `选中条目始终置顶`() = runBlocking {
        val selected = buildEntity(id = 1, packageName = "z.app", label = "Zzz", activated = true)
        val plain = buildEntity(id = 2, packageName = "a.app", label = "Aaa", activated = false)
        every { appsDao.queryPackagesFlow(OpType.RESTORE, any(), any()) } returns MutableStateFlow(listOf(plain, selected))

        val result = getAppsOf(buildListData()).first()

        // 默认按字母升序后，选中条目仍稳定置顶
        assertEquals(listOf("z.app", "a.app"), result.map { it.packageName })
    }
}
