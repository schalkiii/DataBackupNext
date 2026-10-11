package com.xayah.core.service.packages.backup

import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.model.CloudIndexManifest
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.model.util.get
import com.xayah.core.network.client.CloudClient
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.service.util.PackagesBackupUtil
import com.xayah.core.service.util.TransferProgress
import com.xayah.core.service.util.withProgressTicker
import com.xayah.core.util.CloudAppsIndexName
import com.xayah.core.util.DateUtil
import com.xayah.core.util.PathUtil
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
internal class BackupServiceCloudImpl @Inject constructor() : AbstractBackupService() {
    override val mTAG: String = "BackupServiceCloudImpl"

    @Inject
    override lateinit var mRootService: RemoteRootService

    @Inject
    override lateinit var mPathUtil: PathUtil

    @Inject
    override lateinit var mCommonBackupUtil: CommonBackupUtil

    @Inject
    override lateinit var mTaskDao: TaskDao

    @Inject
    override lateinit var mTaskRepo: TaskRepository

    override val mTaskEntity by lazy {
        TaskEntity(
            id = 0,
            opType = OpType.BACKUP,
            taskType = TaskType.PACKAGE,
            startTimestamp = mStartTimestamp,
            endTimestamp = mEndTimestamp,
            backupDir = mRootDir,
            isProcessing = true,
        )
    }

    override suspend fun onTargetDirsCreated() {
        mCloudRepo.getClient().also { (c, e) ->
            mCloudEntity = e
            mClient = c
        }

        mRemotePath = mCloudEntity.remote
        mRemoteAppsDir = mPathUtil.getCloudRemoteAppsDir(mRemotePath)
        mRemoteConfigsDir = mPathUtil.getCloudRemoteConfigsDir(mRemotePath)
        mTaskEntity.update(cloud = mCloudEntity.name, backupDir = mRemotePath)

        log { "Trying to create: $mRemoteAppsDir." }
        log { "Trying to create: $mRemoteConfigsDir." }
        mClient.mkdirRecursively(mRemoteAppsDir)
        mClient.mkdirRecursively(mRemoteConfigsDir)
    }

    private fun getRemoteAppDir(archivesRelativeDir: String) = "${mRemoteAppsDir}/${archivesRelativeDir}"

    override suspend fun onAppDirCreated(archivesRelativeDir: String): Boolean = runCatchingOnService {
        mClient.mkdirRecursively(getRemoteAppDir(archivesRelativeDir))
    }

    override suspend fun backup(type: DataType, p: PackageEntity, r: PackageEntity?, t: TaskDetailPackageEntity, dstDir: String) {
        val remoteAppDir = getRemoteAppDir(p.archivesRelativeDir)
        // 云端备份压缩后还需上传：压缩占前半程进度，上传续走后半程
        val result = if (type == DataType.PACKAGE_APK) {
            mPackagesBackupUtil.backupApk(p = p, t = t, r = r, dstDir = dstDir, compressProgressCap = 0.5f)
        } else {
            mPackagesBackupUtil.backupData(p = p, t = t, r = r, dataType = type, dstDir = dstDir, compressProgressCap = 0.5f)
        }
        if (result.isSuccess && t.get(type).state != OperationState.SKIP) {
            mPackagesBackupUtil.upload(client = mClient, p = p, t = t, dataType = type, srcDir = dstDir, dstDir = remoteAppDir)
        }
        t.update(dataType = type, progress = 1f)
        t.update(processingIndex = t.processingIndex + 1)
    }

    override suspend fun onConfigSaved(path: String, archivesRelativeDir: String) {
        mCloudRepo.upload(client = mClient, src = path, dstDir = getRemoteAppDir(archivesRelativeDir))
    }

    override suspend fun onItselfSaved(path: String, entity: ProcessingInfoEntity) {
        entity.update(state = OperationState.UPLOADING)
        val reporter = TransferProgress()
        val result = withProgressTicker(onTick = {
            entity.update(content = "${(reporter.current * 100).toInt()}%", progress = reporter.current)
        }) {
            mCloudRepo.upload(client = mClient, src = path, dstDir = mRemotePath, onUploading = { read, total -> reporter.report(read, total) })
        }
        entity.update(state = if (result.isSuccess) OperationState.DONE else OperationState.ERROR, log = if (result.isSuccess) null else result.outString, content = "100%")
    }

    override suspend fun onIconsSaved(path: String, entity: ProcessingInfoEntity) {
        entity.update(state = OperationState.UPLOADING)
        val reporter = TransferProgress()
        val result = withProgressTicker(onTick = {
            entity.update(content = "${(reporter.current * 100).toInt()}%", progress = reporter.current)
        }) {
            mCloudRepo.upload(client = mClient, src = path, dstDir = mRemoteConfigsDir, onUploading = { read, total -> reporter.report(read, total) })
        }
        entity.update(state = if (result.isSuccess) OperationState.DONE else OperationState.ERROR, log = if (result.isSuccess) null else result.outString, content = "100%")
    }

    override suspend fun onConfigsSaved(path: String, entity: ProcessingInfoEntity) {
        entity.update(state = OperationState.UPLOADING)
        val reporter = TransferProgress()
        val result = withProgressTicker(onTick = {
            entity.update(content = "${(reporter.current * 100).toInt()}%", progress = reporter.current)
        }) {
            mCloudRepo.upload(client = mClient, src = path, dstDir = mRemoteConfigsDir, onUploading = { read, total -> reporter.report(read, total) })
        }
        entity.update(state = if (result.isSuccess) OperationState.DONE else OperationState.ERROR, log = if (result.isSuccess) null else result.outString, content = "100%")
    }

    override suspend fun onRotateCopy(src: PackageEntity, dst: PackageEntity): Boolean = runCatching {
        val remoteAppDir = "${mRemoteAppsDir}/${src.archivesRelativeDir}"
        val dstDir = "${mRemoteAppsDir}/${dst.archivesRelativeDir}"
        // 先上传轮转后的 config 再整体改名，与 protectCloudApp 同模式。
        // 本方法在每个应用打包前触发，共享暂存目录里还有待上传的归档/图标，
        // 配置必须写入独立临时子目录且只清理该子目录，不能删整个暂存目录
        val tmpRotateDir = "${mPathUtil.getCloudTmpDir()}/rotate"
        val tmpJsonPath = PathUtil.getPackageRestoreConfigDst(tmpRotateDir)
        mRootService.mkdirs(tmpRotateDir)
        mRootService.writeJson(data = dst, dst = tmpJsonPath)
        mCloudRepo.upload(client = mClient, src = tmpJsonPath, dstDir = remoteAppDir)
        mRootService.deleteRecursively(tmpRotateDir)
        mClient.renameTo(remoteAppDir, dstDir)
    }.isSuccess

    override suspend fun onDeleteCopy(copy: PackageEntity): Boolean = runCatching {
        val remoteAppDir = "${mRemoteAppsDir}/${copy.archivesRelativeDir}"
        if (mClient.exists(remoteAppDir)) mClient.deleteRecursively(remoteAppDir)
    }.isSuccess

    override suspend fun onIndexManifestSaved() {
        // 上传统计当前作用域全部 RESTORE 实体的索引清单，供增量同步使用。
        // 本方法每备份完一个应用就会调用，清单写入独立临时子目录并只清理该子目录，
        // 不能动整个暂存目录——归档文件还在里面等待上传
        val packages = mPackageDao.queryPackages(OpType.RESTORE, mTaskEntity.cloud, mTaskEntity.backupDir)
        val manifest = CloudIndexManifest(generatedAt = DateUtil.getTimestamp(), packages = packages)
        val tmpIndexDir = "${mPathUtil.getCloudTmpDir()}/index"
        val tmpJsonPath = "${tmpIndexDir}/$CloudAppsIndexName"
        mRootService.mkdirs(tmpIndexDir)
        mRootService.writeJson(data = manifest, dst = tmpJsonPath)
        mCloudRepo.upload(client = mClient, src = tmpJsonPath, dstDir = mRemoteAppsDir)
        mRootService.deleteRecursively(tmpIndexDir)
    }

    override suspend fun clear() {
        mRootService.deleteRecursively(mRootDir)
        mClient.disconnect()
    }

    @Inject
    override lateinit var mPackageDao: PackageDao

    @Inject
    override lateinit var mPackageRepo: PackageRepository

    @Inject
    override lateinit var mPackagesBackupUtil: PackagesBackupUtil

    override val mRootDir by lazy { mPathUtil.getCloudTmpDir() }
    override val mAppsDir by lazy { mPathUtil.getCloudTmpAppsDir() }
    override val mConfigsDir by lazy { mPathUtil.getCloudTmpConfigsDir() }

    @Inject
    lateinit var mCloudRepo: CloudRepository

    private lateinit var mCloudEntity: CloudEntity
    private lateinit var mClient: CloudClient
    private lateinit var mRemotePath: String
    private lateinit var mRemoteAppsDir: String
    private lateinit var mRemoteConfigsDir: String
}
