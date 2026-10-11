package com.xayah.core.data.repository

import android.content.Context
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.model.CloudIndexManifest
import com.xayah.core.model.CloudIndexSchemaVersion
import com.xayah.core.model.CompressionType
import com.xayah.core.model.OpType
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.PackageExtraInfo
import com.xayah.core.model.database.PackageIndexInfo
import com.xayah.core.model.database.PackageInfo
import com.xayah.core.model.database.PackageStorageStats
import com.xayah.core.network.client.CloudClient
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.GsonUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.model.ShellResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppsRepoCloudIndexSyncTest {

    private val appsDao = mockk<PackageDao>(relaxed = true)
    // mockk 无法插桩 RemoteRootService 的 suspend 方法，清单 JSON 从测试侧直接构造；
    // 回退编排用例中远端读取不会触发，此处仅作占位
    private val rootService = mockk<RemoteRootService>(relaxed = true)
    private val cloudRepo = mockk<CloudRepository>(relaxed = true)
    private val pathUtil = mockk<PathUtil>(relaxed = true)
    private val client = mockk<CloudClient>(relaxed = true)
    private val entity = mockk<CloudEntity>(relaxed = true).also {
        every { it.name } returns "WebDAV"
        every { it.remote } returns "/backup"
    }

    private val repository = AppsRepo(
        context = mockk<Context>(relaxed = true),
        defaultDispatcher = Dispatchers.Default,
        appsDao = appsDao,
        packageRepo = mockk(relaxed = true),
        rootService = rootService,
        settingsDataRepo = mockk(relaxed = true),
        pathUtil = pathUtil,
        cloudRepo = cloudRepo,
    )

    // 构造清单/本地实体：cloudIndexKey 由包名-用户-副本-压缩类型组成
    private fun buildEntity(
        id: Long,
        packageName: String,
        activated: Boolean = false,
        userId: Int = 0,
        preserveId: Long = 0L,
    ): PackageEntity = PackageEntity(
        id = id,
        indexInfo = PackageIndexInfo(
            opType = OpType.RESTORE,
            packageName = packageName,
            userId = userId,
            compressionType = CompressionType.TAR,
            preserveId = preserveId,
            cloud = "",
            backupDir = "",
        ),
        packageInfo = PackageInfo(
            label = packageName,
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

    @Test
    fun `清单为准_清单外条目删除_命中条目复用行id保留选中`() = runBlocking {
        val existingA = buildEntity(id = 5, packageName = "com.a.app", activated = true)
        val existingB = buildEntity(id = 6, packageName = "com.b.app")
        val manifestA = buildEntity(id = 777, packageName = "com.a.app")
        val manifestC = buildEntity(id = 888, packageName = "com.c.app")
        coEvery { appsDao.queryPackages(OpType.RESTORE, "WebDAV", "/backup") } returns listOf(existingA, existingB)

        val result = repository.syncCloudIndex(
            loaded = CloudIndexManifest(generatedAt = 1L, packages = listOf(manifestA, manifestC)),
            entity = entity,
        ) { _, _, _ -> }

        assertTrue(result)
        val upserts = slot<List<PackageEntity>>()
        coVerify { appsDao.upsert(capture(upserts)) }
        // 命中条目复用行 id 并保留本机选中状态
        assertEquals(5L, upserts.captured[0].id)
        assertTrue(upserts.captured[0].extraInfo.activated)
        // 新条目走默认插入：id=0、未选中、填充云端作用域
        assertEquals(0L, upserts.captured[1].id)
        assertFalse(upserts.captured[1].extraInfo.activated)
        assertEquals("WebDAV", upserts.captured[1].indexInfo.cloud)
        assertEquals("/backup", upserts.captured[1].indexInfo.backupDir)
        // 清单外条目（com.b.app）删除
        coVerify(exactly = 1) { appsDao.delete(6L) }
    }

    @Test
    fun `不同用户与副本的同包名条目不复用行id`() = runBlocking {
        val existing = buildEntity(id = 5, packageName = "com.a.app", userId = 0)
        val manifestOtherUser = buildEntity(id = 777, packageName = "com.a.app", userId = 10)
        coEvery { appsDao.queryPackages(OpType.RESTORE, "WebDAV", "/backup") } returns listOf(existing)

        repository.syncCloudIndex(
            loaded = CloudIndexManifest(generatedAt = 1L, packages = listOf(manifestOtherUser)),
            entity = entity,
        ) { _, _, _ -> }

        val upserts = slot<List<PackageEntity>>()
        coVerify { appsDao.upsert(capture(upserts)) }
        // cloudIndexKey 不同 → 新条目，未命中复用
        assertEquals(0L, upserts.captured[0].id)
        // 旧条目因 key 不在清单内被删除
        coVerify(exactly = 1) { appsDao.delete(5L) }
    }

    @Test
    fun `空清单按远端清空语义删除全部本地条目`() = runBlocking {
        coEvery { appsDao.queryPackages(OpType.RESTORE, "WebDAV", "/backup") } returns listOf(
            buildEntity(id = 5, packageName = "com.a.app"),
            buildEntity(id = 6, packageName = "com.b.app"),
        )

        val result = repository.syncCloudIndex(
            loaded = CloudIndexManifest(generatedAt = 1L, packages = emptyList()),
            entity = entity,
        ) { _, _, _ -> }

        assertTrue(result)
        coVerify(exactly = 0) { appsDao.upsert(any<List<PackageEntity>>()) }
        coVerify(exactly = 1) { appsDao.delete(5L) }
        coVerify(exactly = 1) { appsDao.delete(6L) }
    }

    @Test
    fun `清单不存在时回退全量扫描`() = runBlocking {
        every { pathUtil.getCloudRemoteAppsIndexPath(any()) } returns "/remote/apps_index.json"
        every { client.exists(any()) } returns false

        val result = repository.loadCloudAppsByIndex(client, entity) { _, _, _ -> }

        assertFalse(result)
        coVerify(exactly = 0) { appsDao.upsert(any<List<PackageEntity>>()) }
        coVerify(exactly = 0) { appsDao.delete(any<Long>()) }
    }

    @Test
    fun `下载失败时回退全量扫描`() = runBlocking {
        every { pathUtil.getCloudRemoteAppsIndexPath(any()) } returns "/remote/apps_index.json"
        every { pathUtil.getCloudTmpDir() } returns "/tmp"
        every { client.exists(any()) } returns true
        // 下载失败不回调 onDownloaded，清单不会被读取
        coEvery {
            cloudRepo.download(any(), any(), any(), any(), any(), any())
        } returns ShellResult(code = -2, input = listOf(), out = listOf())

        val result = repository.loadCloudAppsByIndex(client, entity) { _, _, _ -> }

        assertFalse(result)
        coVerify(exactly = 0) { appsDao.upsert(any<List<PackageEntity>>()) }
        coVerify(exactly = 0) { appsDao.delete(any<Long>()) }
    }

    @Test
    fun `清单schemaVersion超本机版本时不采用`() = runBlocking {
        val result = repository.syncCloudIndex(
            loaded = CloudIndexManifest(schemaVersion = CloudIndexSchemaVersion + 1, generatedAt = 1L, packages = emptyList()),
            entity = entity,
        ) { _, _, _ -> }

        assertFalse(result)
        coVerify(exactly = 0) { appsDao.upsert(any<List<PackageEntity>>()) }
        coVerify(exactly = 0) { appsDao.delete(any<Long>()) }
    }

    @Test
    fun `清单packages为null时不采用`() = runBlocking {
        // Gson 反射赋值可绕过 Kotlin 非空声明，落库前必须拦截
        val manifest = GsonUtil().fromJson<CloudIndexManifest>(
            """{"schemaVersion":$CloudIndexSchemaVersion,"generatedAt":1,"packages":null}""",
            CloudIndexManifest::class.java,
        )

        val result = repository.syncCloudIndex(loaded = manifest, entity = entity) { _, _, _ -> }

        assertFalse(result)
        coVerify(exactly = 0) { appsDao.upsert(any<List<PackageEntity>>()) }
        coVerify(exactly = 0) { appsDao.delete(any<Long>()) }
    }
}
