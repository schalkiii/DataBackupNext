package com.xayah.core.data.repository

import android.content.Context
import com.xayah.core.model.App
import com.xayah.core.model.File
import com.xayah.core.model.OpType
import com.xayah.core.model.SortType
import com.xayah.core.model.Target
import com.xayah.core.model.UserInfo
import com.xayah.core.model.database.LabelAppCrossRefEntity
import com.xayah.core.model.database.LabelFileCrossRefEntity
import com.xayah.core.model.database.VersionInfo
import com.xayah.core.util.module.combine
import dagger.hilt.android.qualifiers.ApplicationContext
import com.xayah.core.datastore.readFilterBackupHasBackups
import com.xayah.core.datastore.readFilterBackupHasNoBackups
import com.xayah.core.datastore.readFilterBackupLastBackupDays
import com.xayah.core.datastore.readFilterBackupUpdatedApps
import com.xayah.core.datastore.readFilterRestoreInstalledApps
import com.xayah.core.datastore.readFilterRestoreNotInstalledApps
import com.xayah.core.datastore.readFilterRestoreUpdatedApps
import com.xayah.core.datastore.saveFilterBackupHasBackups
import com.xayah.core.datastore.saveFilterBackupHasNoBackups
import com.xayah.core.datastore.saveFilterBackupLastBackupDays
import com.xayah.core.datastore.saveFilterBackupUpdatedApps
import com.xayah.core.datastore.saveFilterRestoreInstalledApps
import com.xayah.core.datastore.saveFilterRestoreNotInstalledApps
import com.xayah.core.datastore.saveFilterRestoreUpdatedApps
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ListDataRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usersRepo: UsersRepo,
    private val appsRepo: AppsRepo,
    private val filesRepo: FilesRepo,
    private val labelsRepo: LabelsRepo,
    private val workRepo: WorkRepo,
) {
    private lateinit var listData: Flow<ListData>

    // 统一页作用域状态：value 变化时，各列表 ViewModel 通过 flatMapLatest 流式重建（含备份↔恢复模式切换）
    private val scopeState = MutableStateFlow<ScopeState?>(null)
    val scope: StateFlow<ScopeState?> = scopeState.asStateFlow()

    private lateinit var selected: Flow<Long>
    private lateinit var total: Flow<Long>
    private lateinit var searchQuery: MutableStateFlow<String>
    private lateinit var showFilterSheet: MutableStateFlow<Boolean>
    private lateinit var sortIndex: MutableStateFlow<Int>
    private lateinit var sortType: MutableStateFlow<SortType>
    private lateinit var isUpdating: Flow<Boolean>
    private lateinit var labels: MutableStateFlow<Set<String>>

    // Apps
    private lateinit var appsOpType: OpType // 筛选持久化按操作类型分键
    private lateinit var showDataItemsSheet: MutableStateFlow<Boolean>
    private lateinit var filters: MutableStateFlow<Filters>
    private lateinit var userIndex: MutableStateFlow<Int>
    private lateinit var userList: Flow<List<UserInfo>>
    private lateinit var userMap: Flow<Map<Int, Long>>
    private lateinit var appList: Flow<List<App>>
    private lateinit var outdatedCount: Flow<Long> // 备份提醒角标：待更新应用数（仅备份页有值）
    private lateinit var pkgUserVersions: Flow<Map<String, VersionInfo>> // "${pkgName}-${userId}" -> 版本信息（备份页=备份版本，恢复页=本机版本）
    private lateinit var labelAppRefs: Flow<List<LabelAppCrossRefEntity>> // Labels filtered app refs

    // Files
    private lateinit var fileList: Flow<List<File>>
    private lateinit var labelFileRefs: Flow<List<LabelFileCrossRefEntity>> // Labels filtered file refs

    fun initialize(target: Target, opType: OpType, cloudName: String, backupDir: String) {
        when (target) {
            Target.Apps -> {
                appsOpType = opType
                selected = appsRepo.countSelectedApps(opType)
                total = appsRepo.countApps(opType)
                searchQuery = MutableStateFlow("")
                showFilterSheet = MutableStateFlow(false)
                sortIndex = MutableStateFlow(0)
                sortType = MutableStateFlow(SortType.ASCENDING)
                isUpdating = when (opType) {
                    OpType.BACKUP -> combine(workRepo.isFullInitRunning(), workRepo.isFullInitAndUpdateAppsRunning(), workRepo.isFastInitAndUpdateAppsRunning()) { fInit, full, fast -> fInit || full || fast }
                    OpType.RESTORE -> combine(workRepo.isFullInitRunning(), workRepo.isLoadAppBackupsRunning()) { fInit, lAppBackups -> fInit || lAppBackups }
                }
                labels = MutableStateFlow(setOf())
                labelAppRefs = labels.map {
                    labelsRepo.getAppRefs(it)
                }

                showDataItemsSheet = MutableStateFlow(false)
                // 筛选条件持久化：进入列表时恢复上次选择（按操作类型分键读取）
                filters = MutableStateFlow(
                    Filters(
                        cloud = cloudName,
                        backupDir = backupDir,
                        showSystemApps = runBlocking { appsRepo.getLoadSystemApps() },
                        hasBackups = runBlocking { context.readFilterBackupHasBackups().first() },
                        hasNoBackups = runBlocking { context.readFilterBackupHasNoBackups().first() },
                        installedApps = runBlocking { context.readFilterRestoreInstalledApps().first() },
                        notInstalledApps = runBlocking { context.readFilterRestoreNotInstalledApps().first() },
                        updatedApps = when (opType) {
                            OpType.BACKUP -> runBlocking { context.readFilterBackupUpdatedApps().first() }
                            OpType.RESTORE -> runBlocking { context.readFilterRestoreUpdatedApps().first() }
                        },
                        // 『上次备份超过 X 天』：0 = 关闭，默认 30，跨会话持久化
                        lastBackupDays = runBlocking { context.readFilterBackupLastBackupDays().first() },
                    )
                )
                userIndex = MutableStateFlow(0)
                userList = usersRepo.getUsers(opType)
                userMap = usersRepo.getUsersMap(opType, cloudName, backupDir)
                // 备份提醒角标仅统计备份页（本机版本高于备份版本）
                outdatedCount = when (opType) {
                    OpType.BACKUP -> appsRepo.countOutdatedBackupApps(cloudName, backupDir)
                    OpType.RESTORE -> flowOf(0L)
                }

                listData = getAppListData()
                pkgUserVersions = when (opType) {
                    OpType.BACKUP -> {
                        appsRepo.getBackups(filters)
                    }

                    OpType.RESTORE -> {
                        appsRepo.getInstalledVersions(userList)
                    }
                }
                appList = appsRepo.getApps(opType = opType, listData = listData, pkgUserVersions = pkgUserVersions, refs = labelAppRefs, labels = labels, cloudName = cloudName, backupDir = backupDir)
            }

            Target.Files -> {
                selected = filesRepo.countSelectedFiles(opType)
                total = filesRepo.countFiles(opType)
                searchQuery = MutableStateFlow("")
                showFilterSheet = MutableStateFlow(false)
                sortIndex = MutableStateFlow(0)
                sortType = MutableStateFlow(SortType.ASCENDING)
                isUpdating = when (opType) {
                    OpType.BACKUP -> combine(workRepo.isFullInitRunning(), workRepo.isFastInitAndUpdateFilesRunning()) { fInit, fast -> fInit || fast }
                    OpType.RESTORE -> combine(workRepo.isFullInitRunning(), workRepo.isLoadFileBackupsRunning()) { fInit, lFileBackups -> fInit || lFileBackups }
                }
                labels = MutableStateFlow(setOf())
                labelFileRefs = labels.map {
                    labelsRepo.getFileRefs(it)
                }

                listData = getFileListData()
                fileList = filesRepo.getFiles(opType = opType, listData = listData, refs = labelFileRefs, labels = labels, cloudName = cloudName, backupDir = backupDir)
            }
        }
        // 作用域就绪后发布，令所有消费方进入流式订阅并可感知后续模式切换
        scopeState.value = ScopeState(target, opType, cloudName, backupDir)
    }

    /**
     * 统一页模式切换：备份 ↔ 恢复。复用 initialize 重建当前作用域的列表数据，
     * 由于 scope 状态变化，所有消费方经 flatMapLatest 自动重建。
     */
    fun switchMode(target: Target, opType: OpType, cloudName: String, backupDir: String) {
        initialize(target, opType, cloudName, backupDir)
    }

    private fun getAppListData(): Flow<ListData.Apps> = combine(
        selected,
        total,
        searchQuery,
        showFilterSheet,
        sortIndex,
        sortType,
        isUpdating,
        labels,
        showDataItemsSheet,
        filters,
        userIndex,
        userList,
        userMap,
        outdatedCount,
    ) { s, t, sQuery, sFSheet, sIndex, sType, iUpdating, lIds, sDISheet, filters, uIndex, uList, uMap, oCount ->
        ListData.Apps(s, t, sQuery, sFSheet, sIndex, sType, iUpdating, lIds, sDISheet, filters, uIndex, uList, uMap, oCount)
    }

    private fun getFileListData(): Flow<ListData.Files> = combine(
        selected,
        total,
        searchQuery,
        showFilterSheet,
        sortIndex,
        sortType,
        isUpdating,
        labels,
    ) { s, t, sQuery, sFSheet, sIndex, sType, iUpdating, lIds ->
        ListData.Files(s, t, sQuery, sFSheet, sIndex, sType, iUpdating, lIds)
    }

    fun getListData(): Flow<ListData> = listData

    fun getAppList(): Flow<List<App>> = appList

    fun getFileList(): Flow<List<File>> = fileList

    suspend fun setFilters(block: (Filters) -> Filters) {
        filters.emit(block(filters.value))
        // 筛选条件持久化：按操作类型分键写入，下次进入列表时恢复
        val current = filters.value
        when (appsOpType) {
            OpType.BACKUP -> {
                context.saveFilterBackupHasBackups(current.hasBackups)
                context.saveFilterBackupHasNoBackups(current.hasNoBackups)
                context.saveFilterBackupUpdatedApps(current.updatedApps)
                context.saveFilterBackupLastBackupDays(current.lastBackupDays)
            }

            OpType.RESTORE -> {
                context.saveFilterRestoreInstalledApps(current.installedApps)
                context.saveFilterRestoreNotInstalledApps(current.notInstalledApps)
                context.saveFilterRestoreUpdatedApps(current.updatedApps)
            }
        }
    }

    suspend fun setSortIndex(block: (Int) -> Int) {
        sortIndex.emit(block(sortIndex.value))
    }

    suspend fun setSortType(block: (SortType) -> SortType) {
        sortType.emit(block(sortType.value))
    }

    suspend fun setSearchQuery(value: String) {
        searchQuery.emit(value)
    }

    suspend fun setUserIndex(value: Int) {
        userIndex.emit(value)
    }

    suspend fun setShowFilterSheet(value: Boolean) {
        showFilterSheet.emit(value)
    }

    suspend fun setShowDataItemsSheet(value: Boolean) {
        showDataItemsSheet.emit(value)
    }

    suspend fun addLabel(label: String) {
        val ids = labels.value.toMutableSet()
        ids.add(label)
        labels.emit(ids)
    }

    suspend fun removeLabel(label: String) {
        val ids = labels.value.toMutableSet()
        ids.remove(label)
        labels.emit(ids)
    }
}

data class Filters(
    val cloud: String,
    val backupDir: String,
    val showSystemApps: Boolean,
    val hasBackups: Boolean,
    val hasNoBackups: Boolean,
    val installedApps: Boolean,
    val notInstalledApps: Boolean,
    val updatedApps: Boolean,
    // 『上次备份超过 X 天』：0 = 关闭该筛选（默认 30）
    val lastBackupDays: Int = 0,
)

/**
 * 统一页作用域：目标、当前操作模式（备份/恢复）与云端作用域。
 * type 由 initialize 发布，消费方依赖其变化触发流式重建。
 */
data class ScopeState(
    val target: Target,
    val opType: OpType,
    val cloudName: String,
    val backupDir: String,
)

sealed class ListData(
    open val selected: Long,
    open val total: Long,
    open val searchQuery: String,
    open val showFilterSheet: Boolean,
    open val sortIndex: Int,
    open val sortType: SortType,
    open val isUpdating: Boolean,
    open val labels: Set<String>,
) {
    data class Apps(
        override val selected: Long,
        override val total: Long,
        override val searchQuery: String,
        override val showFilterSheet: Boolean,
        override val sortIndex: Int,
        override val sortType: SortType,
        override val isUpdating: Boolean,
        override val labels: Set<String>,
        val showDataItemsSheet: Boolean,
        val filters: Filters,
        val userIndex: Int,
        val userList: List<UserInfo>,
        val userMap: Map<Int, Long>,
        val outdatedCount: Long = 0L, // 备份提醒角标：待更新应用数（仅备份页有值）
    ) : ListData(selected, total, searchQuery, showFilterSheet, sortIndex, sortType, isUpdating, labels)

    data class Files(
        override val selected: Long,
        override val total: Long,
        override val searchQuery: String,
        override val showFilterSheet: Boolean,
        override val sortIndex: Int,
        override val sortType: SortType,
        override val isUpdating: Boolean,
        override val labels: Set<String>,
    ) : ListData(selected, total, searchQuery, showFilterSheet, sortIndex, sortType, isUpdating, labels)
}
