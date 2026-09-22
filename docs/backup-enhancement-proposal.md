# DataBackup 备份能力增强方案

> 版本：v2（调研 + 设计稿 + 实现/验证规划）
> 范围：`source/`（2.x 稳定版，含云端）、`source-next/`（Next 重写版）
> 目标：解决 4 个实际使用痛点，并对标 Swift Backup / Neo Backup / Titanium Backup 补齐能力短板。
>
> **阅读指引**：想看结论看 §0；想看为什么要分两条轨道看 **§1.4**；想知道 rustic 是什么 / 云端能不能从 2.x 搬过来看 **§1.5**；想看每个阶段"怎么做 + 怎么验"看 **§7**；想看测试基建怎么搭看 **§8**。

---

## 0. 结论速览（TL;DR）

| 痛点 | 根因（一句话） | 建议方案 | 优先级 |
|---|---|---|---|
| 1. 不能筛选"版本已更新"的 App | 数据库只存**当前**版本号，从未记录**备份时**的版本号，无法比对 | 新增 **备份台账**（记录每次备份的 versionCode/时间），派生 `Outdated` 状态 + 筛选器 + "一键备份已更新" | **P0** |
| 2. 不能设置 App 多副本 | 2.x 只有手工 `preserveId` 保护副本，**没有保留策略（N 份）**；Next 的 Archive 后端每次覆盖 | 新增**保留策略**（全局默认可配 + 每 App 覆盖），备份后按 LRU 轮转，`protected` 不轮转 | **P0** |
| 3. 云端已备份列表刷新很慢 | 每次刷新都 `walkFileTree` **全量递归**远端目录树 + **逐个下载** `configs_package_restore.json` + 逐条 `exists()` 校验，零缓存、零增量 | 远端 **清单文件（index.json）** + 本地 DB 缓存 + 按 `mtime/etag` 的**增量合并** | **P0** |
| 4. 筛选/排序条件太少 | 2.x 排序只有 3 项（字母/安装时间/数据大小），`sortIndex: Int` 扩展性差；Next 4 项 | 排序维度扩到 8+（含**上次备份时间**），筛选维度补 6+（含**已更新/备份过期/已保护**），`SortKey`/`FilterKey` 枚举化并可保存为视图 | **P1** |

竞品对标结论：**Titanium Backup 的 `Max backup history` + "Min days" 批量条件**、**Swift Backup 的"保存旧版本 APK"** 正好是痛点 1/2 的成熟答案；而痛点 3 属于本项目的实现缺陷，业界普遍用"远端清单 + 本地索引库"解决。

**执行策略（一句话）**：

- `source/`（2.x）走 **M0→M6 全套** —— 因为**只有它有云端**，痛点 3 只能在这里闭环；理由见 §1.4.1。
- `source-next/` 只先做 **M0 + M1 + M3** —— 台账是通用底座、痛点 1/4 在 Next 上改造成本极低、M3 是零外部依赖的本地优化；M2 在 Next 上被并入 M1（排序本来就是枚举），M4/M5 被云端缺失与文件布局未冻结**硬阻塞**，理由见 §1.4.2，合流条件见 §1.4.3。
- 每个里程碑的**实现步骤与验证清单成对定义**，见 §7；测试基建与五类验证的执行方式见 §8。

---

## 1. 项目现状梳理

### 1.1 仓库结构

```
DataBackupNext/
├── source/         # 2.x 稳定版（多模块 + Hilt + Compose）
│   ├── app/            # 壳工程（productFlavors: foss/premium/alpha × abi）
│   ├── core/
│   │   ├── model/      # 数据模型、枚举
│   │   ├── database/   # Room（AppDatabase / PackageDao / MediaDao / CloudDao ...）
│   │   ├── data/       # Repository（AppsRepo / PackageRepository / CloudRepository ...）
│   │   ├── network/    # 云客户端（FTP / WebDAV / SMB / SFTP）
│   │   ├── service/    # 备份/恢复后台服务
│   │   ├── work/       # WorkManager Worker（DB 刷新、备份列表加载）
│   │   ├── datastore/  # DataStore 设置项
│   │   └── ui/         # 通用 Compose 组件与路由
│   └── feature/main/   # 各页面（list / details / cloud / restore / configurations ...）
├── source-next/    # Next 重写版（单模块 app + hiddenapi + native）
│   └── app/src/main/java/com/xayah/databackup/
│       ├── data/       # AppRepository / BackupConfigRepository / rustic/*
│       ├── database/   # Room（仅 apps/networks/contacts/call_logs/sms/mms）
│       ├── feature/    # backup / restore / dashboard / settings ...
│       ├── rootservice/# RemoteRootService（libsu AIDL）
│       └── service/    # BackupService
└── dex/            # hiddenapi 辅助 dex 模块
```

**关键差异**：`source/` 是当前发布形态（**有云端**）；`source-next/` 是重写中（**无云端**、改用 Rustic/tar.zst 两种后端）。本方案对两条轨道分别给出落地路径。

### 1.2 数据模型对照

| 维度 | 2.x（`source/`） | Next（`source-next/`） |
|---|---|---|
| 主键 | `PackageEntity.id`（自增） | `App(packageName, userId)` 复合主键 |
| 版本信息 | `PackageInfo.versionName/versionCode/lastUpdateTime` | `Info.versionName/versionCode/lastUpdateTime` |
| 备份时间 | `PackageExtraInfo.lastBackupTime` | ❌ 无 |
| 多副本 | `PackageIndexInfo.preserveId`（0=主备份，否则为时间戳副本） | ❌ 仅 Rustic 快照天然多版本；Archive 后端覆盖 |
| 备份作用域 | `indexInfo.cloud + backupDir` | `BackupConfig.uuid`（→ `configs/<uuid>`） |
| 备份元数据 | 每个 App 目录内 `configs_package_restore.json` | Rustic 快照内 `.databackup/manifest.json`；Archive 用固定文件名 |
| 云端 | FTP / WebDAV / SMB / SFTP | ❌ 未实现（仅遗留 `Source.CLOUD` 与字符串资源） |

**2.x 关键代码**

- `source/core/model/src/main/kotlin/com/xayah/core/model/database/PackageEntity.kt:71-95` — `PackageInfo` / `PackageExtraInfo`
- `PackageEntity.kt:160-172` — `PackageIndexInfo`（含 `preserveId`/`cloud`/`backupDir`）
- `PackageEntity.kt:264-265` — `archivesRelativeDir` = `pkg/user_<uid>[@<preserveId>]`
- `source/core/model/src/main/kotlin/com/xayah/core/model/App.kt:3-11` — **暴露给 UI 的 App 模型只含 7 个字段，缺 versionCode / lastBackupTime**（这是痛点 4 的直接阻碍）

**Next 关键代码**

- `source-next/app/src/main/java/com/xayah/databackup/database/entity/App.kt:15-55` — `App` + `Info` + `Option` + `Storage`
- `source-next/app/src/main/java/com/xayah/databackup/data/rustic/RusticBackupManifest.kt:7-43` — 快照内 manifest（**已含 versionCode/versionName/各部件字节数**，是很好的历史数据源）

### 1.3 关键调用链

**2.x — 云端已备份 App 列表加载（痛点 3 的主链路）**

```
ListScreen(RESTORE)
  └─ ListViewModel.uiState
       └─ ListDataRepo.getListData()           # source/core/data/.../ListDataRepo.kt:53-98
            └─ AppsRepo.getApps(opType=RESTORE, cloudName, backupDir)
                 └─ PackageDao.queryPackages(opType, cloud, backupDir)   # 只读本地 DB → 快
刷新按钮 / 首次进入（云端 DB 为空时）：
  ListActionsViewModel.refresh()               # source/feature/main/list/.../ListActionsViewModel.kt:76-101
    └─ WorkManagerInitializer.loadAppBackups(cloudName, backupDir)
         └─ AppsLoadWorker.doWork()            # source/core/work/.../workers/AppsLoadWorker.kt:45-61
              └─ AppsRepo.load(cloudName)
                   └─ loadCloudApps()          # source/core/data/.../AppsRepo.kt:507-548   ← 慢在这里
```

`AppsRepo.loadCloudApps()` 的实际开销（`AppsRepo.kt:507-548`）：

1. `client.exists(remoteAppsDir)` — 1 次远端往返
2. `client.walkFileTree(remoteAppsDir)` — **递归列举整棵目录树**，每个子目录 ≥1 次网络往返（WebDAV/SMB/SFTP/FTP 实现见 `source/core/network/src/main/kotlin/com/xayah/core/network/client/*ClientImpl.kt`）
3. 遍历所有路径，对每个 `configs_package_restore.json` **逐个下载**到本地 tmp 再解析（`cloudRepo.download(...)`）
4. `parsePreserveAndUserId()` + `appsDao.query(...)` 逐条查重后 upsert
5. 最后对 DB 中每个条目 `client.exists("$path/${archivesRelativeDir}")` **逐条远端校验并删除**（N 次往返）

> 即：一次刷新 = `O(目录数)` 次列举 + `O(App数)` 次下载 + `O(App数)` 次 exists。100 个 App / 千级目录时，云端往返次数可达数千次，慢是必然的。

**Next — 备份库列表加载（同源问题）**

```
BackupLibraryScreen (LaunchedEffect)
  └─ BackupLibraryViewModel.initialize()      # .../feature/backup/BackupLibraryViewModel.kt:76-81
       └─ BackupConfigRepository.loadBackupConfigsFromLocal()   # .../data/BackupConfigRepository.kt:147-173
            ├─ RemoteRootService.listFilePaths(..., listDirs=true)   # 1 次 IPC 列目录
            ├─ RemoteRootService.readText("<dir>/.config")           # ← 每个目录 1 次 IPC
            ├─ deduplicateConfigs()  # 命中重复 uuid 会逐个回写 .config（额外写盘）
            └─ sortByDescending(updatedAt)
```
每次进入页面都会全量重扫（无 `isLoaded` 短路），`BackupLibraryViewModel.kt:52` 只把"内存是否为空"当加载态。

**Next — 快照清单 / 恢复清单**

```
RestoreViewModel.load()                       # .../feature/restore/RestoreViewModel.kt:18-48
  └─ RestoreRepository.loadSnapshot()         # .../data/restore/RestoreRepository.kt:17-36
       ├─ gateway.listSnapshots()             # 已有 .snapshots 文件级缓存（RusticBackupGateway.kt:98-127）
       └─ gateway.readSnapshotTextFiles(manifest.json + 各结构化 json)
            └─ RusticRestoreInventoryReader.inventory()   # .../RusticRestoreInventory.kt:74-149
```
`manifest.json` 需要从 Rustic 仓库 dump 出来，**结果是纯内存的、不落库**，每次进入快照都要重算。

### 1.4 双轨策略：为什么必须拆成两条轨道

`source/` 与 `source-next/` 是**两个独立的 Gradle 根工程**（各有 `settings.gradle.kts` / `gradlew` / 依赖版本目录），**没有任何共享源码模块**。同名类（`AppRepository`、`App`、`PathHelper`）只是同名，实现完全不同。唯一的公约数是：同一个 applicationId（`com.xayah.databackup`）与"备份产物落在同一套目录约定下"这一事实。

> 因此**任何一项改动都要在两条轨道上各写一遍**，且"改哪里成本高"两端并不一致 —— 这就是必须分轨的第一层原因。

第二层原因是：**能力与难点的分布是不对称的**。

| 痛点 | 2.x（`source/`） | Next（`source-next/`） |
|---|---|---|
| 1 已更新筛选 | 需补台账（DB 有 `versionCode`，缺"备份时版本"） | 需补台账（**连 `lastBackupTime` 都没有**） |
| 2 多副本 | 有 `preserveId` 底座，**只缺策略** | Archive 后端物理单副本、Rustic 无 retention，**缺底座** |
| 3 云端列表慢 | **有云端**，慢在实现（可修） | **没有云端**，得先造云端 |
| 4 筛选排序少 | `sortIndex: Int` 硬编码，**要重构** | 已是枚举，**扩枚举即可** |

#### 1.4.1 为什么痛点 3 只能在 2.x 闭环

1. **云端代码只在 2.x 存在。** 四个云客户端（`source/core/network/src/main/kotlin/com/xayah/core/network/client/{FTP,WebDAV,SMB,SFTP}ClientImpl.kt`）、连接管理（`CloudRepository.kt:107-128` 的 `getClient` / `withClient`）、云账号表（`CloudDao` / `CloudEntity`）、云选择 UI（`source/feature/main/cloud/`）全部在 2.x。
2. **Next 的云端不是"缺 UI"，而是底层被关掉了。** `source-next/native/src/main/jni/rustic/Cargo.toml` 对 `rustic_backend` 声明了 `default-features = false`，仓库路径以**本地路径**传入（`native/src/main/jni/rustic/src/repository.rs` 的 `BackendOptions::default().repository(repository_path)`），即**未编译任何远端后端**；`Source.CLOUD` 枚举值全仓库零引用，只剩死字符串。
3. **在 Next 造云端是架构级工作**：协议实现 + 认证/凭据存储 + 流式上传 + 断点续传 + 增量清单，工期约为"修 2.x 的慢"的 3~5 倍。而痛点 3 是**当下就在发生的体验问题**，必须先在能跑的地方修掉。

> **结论：M3 + M4 全部落在 `source/`。** Next 侧的等价问题（"备份库 / 快照清单每次重扫"）用 M3 的本地缓存思路即可解决；等 Next 真正接入云端时，把 2.x 沉淀的 `index.json` 清单格式与增量协议**直接搬过去** —— 这份格式定义是跨轨道唯一可复用的资产，所以它在 §5 F3 里被单独定义、单独冻结。

#### 1.4.2 为什么 Next 只先做 M0 + M1 + M3

1. **M0 是"无云端也成立"的通用底座。** 台账只依赖本地事实（本机版本号 + 备份产物元数据）。Next 的 Rustic manifest（`RusticBackupManifest.kt:7-27`）**已经带了 `versionCode`/`versionName`/各部件字节数**，回填成本甚至低于 2.x。
2. **M1 在 Next 是纯增量，不动数据库结构。** `Info.versionCode` 已存在（`database/entity/App.kt:130`），只需在 `SortsAndFiltersHelper.kt` 加两个扩展函数、在 `AppFilterSheetContent.kt` 加两个按钮。
3. **Next 的"痛点 4"被并入 M1，不单独成阶段。** 2.x 的 M2 之所以独立，是因为要把 `sortIndex: Int` 重构成 `SortKey` 枚举 + 表驱动 Comparator；而 Next 的排序**本来就是枚举**（`util/DataStoreHelper.kt:14-19` 的 `SortsType`），"按上次备份时间排序"只是**枚举加一项 + `when` 加一个分支**（< 0.5 天）。故 Next 轨道上 `M2 ≡ M1 的子任务`。
4. **M3 是纯本地优化，零外部依赖。** 只改 `RemoteRootService` 的 IPC 次数与 `BackupLibraryViewModel` 的缓存短路，不碰云端、不碰数据格式，风险最低、体感提升最直接（每次进备份库/恢复页都要吃这个开销）。
5. **M4 / M5 在 Next 上做不了、或不该现在做：**
   - **M4** 依赖云端，Next 没有云端 → 只能等（见 §1.4.1）。
   - **M5** 在 Rustic 后端语义特殊：快照是**整库粒度**，不是"按 App 分副本"，必须先定清"保留 N 个快照 / 保留 N 天"的产品语义；Archive 后端还要改目录结构（`PathHelper.kt:36-41` 的固定文件名 → 带 `copyId` 的层级），属**破坏性变更**，应与 Next 的整体文件布局冻结合并做，不宜现在单独上线。

#### 1.4.3 两条轨道的合流条件

当且仅当以下条件满足，Next 轨道才推进对应的 M4' / M5'：

| 条件 | 内容 | 解锁 |
|---|---|---|
| A | Next 具备至少一种可用云端后端（自研，或启用 Rustic 远端 feature）— 两条可选路线见 **§1.5.4 A/B** | M4'（**仅路线 B** 可复用 2.x 的 `index.json` 契约，见 §1.5.5） |
| B | Next 文件布局冻结（Archive 新结构定稿 + 旧结构兼容层就绪） | M5'（保留策略 + 副本目录） |
| C | M0 台账在两个轨道上语义一致（字段名、`copyId` 语义、派生规则完全对齐） | 所有后续阶段；未满足则先做 M0 对齐，避免后续合并两套语义 |

### 1.5 补充：rustic 是什么，以及"能否把 2.x 云端移植到 Next"

§1.4.1 断言"Next 的云端要另起炉灶"，这里给出证据与替代路线的完整对比。

#### 1.5.1 rustic 简介与本项目的封装

**rustic**（rustic-rs）是用 Rust 实现的、**与 restic 仓库格式兼容**的备份工具/库。其核心模型是"**内容寻址的仓库**"：

| 概念 | 含义 |
|---|---|
| **repo（仓库）** | 一个目录，内含 `config`、`keys/`、`index/`、`data/`（pack 文件）、`snapshots/` |
| **snapshot（快照）** | 一次备份 = 一个不可变快照。**多次备份 = 多个快照** → 这就是 Next 天然具备多版本的原因 |
| **去重** | 按内容分块（CDC）+ 哈希寻址，相同数据只存一次 → 后续快照体积增量很小 |
| **加密** | 仓库由密码派生密钥加密（`Credentials::password`），与 2.x 的明文 tar 完全不同 |
| **parent 链** | 快照有 `parent`/`parents`，记录增量血缘 |

本项目 Next 的封装链路：

```
Kotlin:   Rustic.kt (native/src/main/kotlin/com/xayah/libnative/Rustic.kt:4-78)
   ↓ JNI
C++:      native/src/main/jni/rustic/rustic.cpp  →  librustic.so（CMakeLists 用 Corrosion 构建 Rust）
   ↓
Rust:     native/src/main/jni/rustic/src/repository.rs
   ↓
crates:   rustic_core 0.12.0 + rustic_backend 0.6.2
          （rustic_core 是 git submodule → github.com/rustic-rs/rustic_core，当前未初始化）
```

暴露给 Kotlin 的能力共 8 个（`Rustic.kt`）：`initRepository` / `repositoryExists` / `validateRepository` / `createSnapshot` / `restoreSnapshot` / `deleteSnapshot` / `listSnapshots` / `checkRepository` / `readSnapshotTextFiles`。

**Rustic vs 2.x Archive 两种后端的本质差异：**

| 维度 | 2.x（tar.zst 文件） | Next Rustic（仓库） |
|---|---|---|
| 备份产物形态 | **一堆独立文件**（`apk.tar.zst` / `user.tar.zst`…） | **一个持续演进的仓库目录** |
| 可否手工拆分/单独拷贝 | ✅ 可以 | ❌ 不行（pack + index 互相依赖） |
| 多次备份 | 覆盖写同一个文件名（默认只有 1 份） | 每次新增一个 snapshot（天然多版本） |
| 增量 | ❌ | ✅ 内容去重 |
| 加密 | ❌ | ✅ |
| 随机读取 | 顺序读整包 | 需要按 index 定位 pack 偏移 |

#### 1.5.2 证据：Next 当前的 Rustic 是"仅本地"的

| 证据 | 位置 | 结论 |
|---|---|---|
| `rustic_backend` 声明了 `default-features = false` | `native/src/main/jni/rustic/Cargo.toml:15` | 关掉了 `opendal` / `rest` / `rclone` 三类远端后端 |
| `Cargo.lock` 中**完全没有 `opendal`** | `native/src/main/jni/rustic/Cargo.lock` | 远端后端确实**未被编译进二进制** |
| `rustic_backend 0.6.2` 的依赖只有 `aho-corasick / bytes / bytesize / derive_setters / displaydoc / hex / jiff / log / rustic_core / serde / strum / thiserror / url / walkdir` | `Cargo.lock` | 全是本地后端所需，无 HTTP/SSH/S3 相关依赖 |
| 仓库路径被当作**本地路径**直接传入 | `rustic/src/repository.rs:192-196` 的 `backends()` → `BackendOptions::default().repository(repository_path)` | 无 URL 解析、无远端抽象 |
| 所有文件访问走 `RemoteRootService`（libsu AIDL，`java.io.File`） | `app/src/main/java/com/xayah/databackup/rootservice/RemoteRootService.kt` | Next 全栈假设"备份仓库在本地磁盘" |

参考：`rustic_backend 0.6.2` 的 features 为 `default = ["opendal", "rest", "rclone"]`。其中 `opendal` 会**一次性拉起** Apache OpenDAL 的约 30 个服务（s3 / webdav / sftp / ftp / gdrive / onedrive / dropbox / azblob / b2 / gcs / cos / obs / oss / swift / webhdfs …）外加 `tokio` + `rayon` + `typed-path`，**无法按服务粒度裁剪**；并且 **opendal 不支持 SMB**（2.x 的 SMB 用的是 `smbj`）。

> 打开这个开关的代价必须量化评估：native 体积、CMake/NDK 编译时间、以及引入 `tokio` 运行时后与现有 JNI 线程模型的配合。

#### 1.5.3 2.x 的云能力拆成两块，可移植性截然不同

2.x 的云端实现（§1.3、`BackupServiceCloudImpl.kt`）实际是 **"本地临时目录 staging → 逐文件上传"**：

```kotlin
// source/core/service/.../backup/BackupServiceCloudImpl.kt
override val mRootDir by lazy { mPathUtil.getCloudTmpDir() }        // 本地临时目录
override suspend fun backup(...) {
    val result = mPackagesBackupUtil.backupApk(...)                  // ① 先写本地
    if (result.isSuccess && ...) {
        mPackagesBackupUtil.upload(client = mClient, srcDir = dstDir, dstDir = remoteAppDir)  // ② 再上传
    }
}
override suspend fun clear() { mRootService.deleteRecursively(mRootDir); mClient.disconnect() }
```

而且**云客户端跑在 app 进程**（`@AndroidEntryPoint` 的 Android `Service` + Hilt 注入），**不走 AIDL**。

| 组成 | 具体内容 | 可移植性 |
|---|---|---|
| **(a) 协议客户端** | `{FTP,WebDAV,SMB,SFTP}ClientImpl.kt`，依赖 `apache-commons-net` / `smbj` / `sshj` / `sardine-next` / `okhttp` —— **全是纯 JVM 库，无 native** | ✅ **高**：加入 Next 的 `libs.versions.toml` + `app/build.gradle.kts` 即可编译。<br>⚠️ 但需改接口签名：`CloudClient`（`CloudClient.kt:14-32`）返回 `List<PathParcelable>` / `DirChildrenParcelable`，这两个类分别来自 `core:rootservice` 与第三方 `libpickyou`，Next 里应替换为普通 `data class`，否则会拖入无用依赖 |
| **(b) 集成层** | `CloudRepository` / `CloudDao` / `CloudEntity`+各协议 `*Extra` / 云设置 UI / `reloadAppsFromCloud12x` 等 | ❌ **低**：强耦合 2.x 的 Hilt 模块划分（`core:network`/`core:data`/`core:database`）、多模块 Room、`PackageEntity.indexInfo.cloud/backupDir` 双字段路径模型；Next 是 Koin + 单模块 + `BackupConfig.uuid` 单字段作用域，**必须重写** |
| **(c) 远端索引** | `index.json` 清单 + `mtime/etag` 增量 diff（本方案 F3/M4 的设计） | ⚠️ **只对 Archive 后端有意义**：Rustic 自带 index，不需要外层清单；见下 |

#### 1.5.4 三条候选路线

| 路线 | 做法 | 复用 2.x | 优点 | 代价 |
|---|---|---|---|---|
| **A. 让 Rustic 直连远端**（**长期主线，推荐**） | `Cargo.toml` 改为开启 `features = ["opendal", "rest"]`；`backends()` 把 `repository_path` 从本地路径换成 URL（`opendal:webdav:...` / `sftp:...` / `rest:...`） | 协议层**零复用**（opendal 自己实现）；2.x 的 `index.json` 清单**也用不上** | 增量、去重、加密全部原生保留；一套代码覆盖 30+ 云服务；**不需要"上传/下载/清单同步"流程** | native 体积与编译时间大增；需凭据管理（OAuth/token 存储）；**不支持 SMB**；网络抖动直接影响 backup/restore 主流程 |
| **B. 仅在 Archive 后端移植 2.x 云栈**（**短期见效，可并行**） | 把 `CloudClient` 接口 + 4 个协议实现搬到 Next（换成普通 data class），配 `CloudDao`-like 表 + `index.json` 清单 + 增量 diff | **高**（协议客户端直接复用；M4 的清单/增量设计与 2.x 完全同构） | 不碰 native；渐进式；**2.x 的 M4 成果可直接搬过来**（这正是 §1.4.3 条件 A 的复用点） | 只有 Archive 后端支持云、Rustic 不支持 → 两套后端能力不一致，产品语义割裂 |
| **C. 本地中转（stage → 整包同步）** | 备份仍写本地 repo，完成后把整个 repo 同步到云端 | 低 | 改动最小 | **对 Rustic 基本不成立**：仓库随去重索引持续演进，restore/list 需随机读 pack 与 index，整包同步的量远超新增数据，且恢复前必须整包拉回。仅对 Archive 勉强可行，但每次全量同步、体验差 |

> 注意路线 C 正是 **2.x 云端现在的做法**（`getCloudTmpDir()` staging → 逐文件 upload）。它对 2.x 之所以可行，纯粹因为 2.x 的产物是**一堆独立文件**；换成 Rustic 的仓库模型就失效了。

#### 1.5.5 结论与对 §1.4 的修正

- **不能整块照搬**：可移植的只有"协议客户端 + 清单/增量设计"；Repository/DI/Room/路径模型必须重写；Rustic 后端的云端**必须另起**。
- **推荐组合**：**A + B 并行** —— Archive 后端走 B（复用 2.x，快速出成果、验证 `index.json` 契约），Rustic 后端走 A（用 feature 开关，长期主线）。
- **对 §1.4.3 条件 A 的细化**：Next 云端有两条可选实现，任一条落地即满足条件 A；但**若选 A，则 2.x 的 `index.json` 清单格式在 Next 上无复用价值**（Rustic 自带索引），条件 A 的"复用点"仅对路线 B 成立。

---

## 2. 痛点诊断（逐条）

### 痛点 1：不能筛选"更新了版本"的 App

**现象**：App 升级后，用户仍要在一长串列表里手动找哪些需要重新备份。

**根因**：
- 2.x DB 中有 `PackageInfo.versionCode`（`PackageEntity.kt:74-75`），但那是**当前已安装版本**；**没有任何字段记录"上次备份时的版本"**。`PackageExtraInfo`（`PackageEntity.kt:85-95`）只有 `lastBackupTime`，没有 `backedUpVersionCode`。
- 备份流程 `AbstractBackupService.kt:160-181` 在写完数据后只做 `p.extraInfo.lastBackupTime = DateUtil.getTimestamp()`，**不回写版本号**。
- 因此"是否已更新"这个布尔量在系统里**无法计算**，UI 自然也无从筛选。
- 与之对照，2.x 现成的 `Filters`（`ListDataRepo.kt:200-208`）只有 `showSystemApps / hasBackups / hasNoBackups / installedApps / notInstalledApps`，`PackageRepository.kt:79-93` 也只有对应的 4 个 predicate。

**Next 侧同样缺失**：`App` 实体（`database/entity/App.kt:15-55`）完全没有备份态字段；`SortsAndFiltersHelper.kt:44-47` 只有用户/系统过滤。

**竞品做法**：
- **Titanium Backup**：批量操作里有 `Min days`（"备份早于 N 天的应用"）与"Backup new/updated apps"类批量项（见官方 User's Guide 的 Batch 章节）。
- **Swift Backup**：应用列表直接以"Last backup"列展示，并有"未备份/已更新"的视觉标记。

### 痛点 2：不能设置 App 备份多个副本

**现象**：想为重要 App 保留 2~3 份历史备份（尤其升级前），现在做不到（或只能每次手工"保护"）。

**根因（2.x）**：
- "多副本"机制**已存在但不可配置**：`preserveId`（`PackageIndexInfo.preserveId`，见 `PackageEntity.kt:160-172`）为非 0 时，备份目录变成 `pkg/user_<uid>@<preserveId>`（`PackageEntity.kt:264-265`），即一份独立副本；UI 侧在 `source/feature/main/details/src/main/kotlin/com/xayah/feature/main/details/AppDetails.kt:133` 提供 `onProtect`。
- 但**没有任何"保留 N 份/自动轮转"的配置项**：DataStore 中查无 `preserve`/`protect` 相关 key（`source/core/datastore/` 中检索为空）。副本只能靠用户在每个 App 上手工制造，且不会自动清理旧副本 → 空间会无限膨胀。
- 去重优化也缺失：同一 `versionCode` 的 APK 会被重复存储（Titanium Backup 的对应做法是"每个 apk 版本只存一次"）。

**根因（Next）**：
- Rustic 后端每次备份新建 snapshot，天然多版本，但**没有保留上限**（仓库会无限增长），也**没有"只保留最近 N 个快照"的 retention 策略**（`.../feature/backup/BackupConfigViewModel.kt:63-86` 只能手工删除单个快照）。
- Archive 后端（`.../util/PathHelper.kt:36-41`）写死文件名 `apk.tar.zst`/`user.tar.zst`…，**重新备份即覆盖，物理上只有 1 份**。

### 痛点 3：云端已备份的应用列表无缓存，刷新很慢

**根因**：见 §1.3 的 `AppsRepo.loadCloudApps()` 五点开销。核心缺陷是：

1. **无远端清单**：远端没有"索引文件"，只能靠递归遍历目录树反推内容。
2. **无增量**：`walkFileTree` 返回全部路径，代码里没有 `mtime`/`etag`/`size` 的任何比对逻辑。
3. **逐条网络往返**：每个 config 独立下载；结算阶段每个条目独立 `exists()`。
4. **本地 DB 只当结果表，不当缓存**：`appsDao.upsert` 只做去重插入（`AppsRepo.kt:491-493`），没有"缓存有效性/上次同步时间"概念，因此每次刷新都必须重新走远端。
5. **刷新入口不可控**：`ExistingWorkPolicy.KEEP`（`WorkManagerInitializer.kt:58-60`）意味着"上一轮没跑完就丢弃本次刷新"，用户感觉"点了没反应/很久"。

**底层能力缺失**：`CloudClient` 接口（`source/core/network/src/main/kotlin/com/xayah/core/network/client/CloudClient.kt:26-32`）只有 `listFiles / walkFileTree / exists / size / testConnection / setRemote`，**没有 `stat`、没有批量列举、没有 etag**。`WebDAVClientImpl.listFiles` 丢弃了 `getetag`/`getlastmodified`（`WebDAVClientImpl.kt:159-175` 只取了 `creation.time`）。

**Next 侧的同类问题**：`BackupConfigRepository.loadBackupConfigsFromLocal()`（`.../BackupConfigRepository.kt:147-173`）每个备份目录一次 IPC；`BackupLibraryViewModel.kt:76-81` 无 `isLoaded` 短路；`DashboardViewModel` / `BackupSetupViewModel` 还会调用 `calculateTreeSize` 递归统计整棵备份树。

### 痛点 4：筛选/排序条件太少

**2.x 现状**：
- 排序：仅 3 项，硬编码在字符串数组 `source/app/src/main/res/values/arrays.xml:2-6`（`alphabet` / `installation_time` / `data_size`），并以 `sortIndex: Int` 传递（`ListDataRepo.kt:60-61,215-216`）——**加一项要同时改数组、改 `sortByXxxNew` 分支、改索引常量，极易错位**。
- 排序字段只有 `firstInstallTime` / `storageStatsBytes` / `label`（`PackageRepository.kt:99-130`）。
- 筛选：见痛点 1 的 `Filters`。
- **`lastBackupTime` 已经在 DB 里且已写入**（`PackageEntity.kt:90`、`AbstractBackupService.kt:175`），详情页也在展示（`AppDetails.kt:508-512`），但**没有开放为排序/筛选维度**。这是"投入产出比最高"的一项。
- `App` UI 模型缺字段（`core/model/App.kt:3-11`），导致即使 DAO 能排序，UI 层也拿不到 `lastBackupTime` 去展示。

**Next 现状**：
- 排序 4 项（`util/DataStoreHelper.kt:14-19`：`A2Z/DATA_SIZE/INSTALL_TIME/UPDATE_TIME`）+ `selectedFirst`，实现见 `util/SortsAndFiltersHelper.kt:49-74`，UI 见 `ui/component/selection/AppFilterSheetContent.kt:88-186`。
- 筛选仅"用户应用/系统应用"两项。
- 变量名在 DataStore 里是 `KeySortsTypeBackup` 全局单例（`DataStoreHelper.kt:63-81`），**备份页与恢复页共用同一份排序设置**，恢复页无法独立记忆。

---

## 3. 竞品对标

### 3.1 能力矩阵

| 能力 | DataBackup 2.x | DataBackup Next | Titanium Backup | Swift Backup | Neo Backup |
|---|---|---|---|---|---|
| Root 需求 | 需要 | 需要 | 需要 | 可选（Shizuku 批量恢复） | 需要 |
| 增量/去重存储 | ❌ | ✅ Rustic 内容寻址去重 | ✅ 同版本 APK 只存一份 | ✅ | 部分 |
| **多版本/副本保留策略** | ⚠️ 手工 `preserveId`，无策略 | ⚠️ Rustic 天然多版本，无上限 | ✅ **`Max backup history`（N 份，PRO）** | ✅ **"保存应用备份的旧版本"** | ❌ |
| **"已更新/久未备份"批量筛选** | ❌ | ❌ | ✅ **`Min days`** + 批量"备份已更新" | ✅ Last backup 标记 | ❌ |
| 上次备份时间排序 | ❌（有数据，未开放） | ❌（无数据） | ✅ | ✅ | ✅ |
| 标签 Labels | ✅ `LabelEntity` | ❌ | ✅ Filters/Labels | ✅（付费） | ✅ 自定义列表 |
| 定时/计划备份 | ❌ | ❌ | ✅ 多计划 | ✅（付费） | ✅ 无数量上限 |
| 云端存储 | ✅ FTP/WebDAV/SMB/SFTP | ❌ | ⚠️ Dropbox/Box/GDrive（PRO） | ✅ **14 种** | ❌（推荐 Syncthing/Nextcloud） |
| 备份加密 | ❌ | ✅ Rustic AES | ❌ | ✅ | ✅ AES-256 |
| 备份完整性校验 | ⚠️ | ✅ Rustic | ✅ Verify（PRO） | ⚠️ | ⚠️ |
| 应用批量备份/恢复 | ✅ | ✅ | ✅ | ✅ | ✅ |

### 3.2 可直接借鉴的三点

1. **`Max backup history`（Titanium Backup）** → 抽象成"每 App 保留 N 份"，配合"同版本 APK 只存一份"的硬链接去重。→ 直接解决**痛点 2**。
2. **`Min days` / "备份已更新的应用"（Titanium Backup + Swift Backup）** → 抽象成"备份态筛选器"，条件由 `lastBackupTime` + `versionCode` 派生。→ 直接解决**痛点 1 & 4**。
3. **离线索引/清单**（Swift Backup 的 `SwiftBackup/accounts/` 结构 + 各云盘的 `list` 增量能力）→ 抽象成"远端 `index.json` + 本地 Room 缓存 + `mtime/etag` 增量 diff"。→ 直接解决**痛点 3**。

### 3.3 上游 / 社区已有动议

建议在动手前先检索上游仓库 issue（`XayahSuSuSu/Android-DataBackup`）中与 `preserve`、`backup history`、`cloud reload`、`sort` 相关的议题，避免重复实现或与维护者路线冲突；若已有 PR，优先复用其数据结构约定。

---

## 4. 总体设计：引入"备份台账"（Backup Ledger）

四个痛点中，3 个（1、2、4）都卡在同一件事上：**系统只有"当前设备状态"和"备份文件"，缺少一份统一的"备份台账"把两者关联起来**。因此核心设计是一张新表。

### 4.1 数据模型（两端通用语义）

```kotlin
/** 一次成功备份的记录：一个 App 的一个副本。 */
@Entity(
    tableName = "backup_ledger",
    primaryKeys = ["scopeKey", "packageName", "userId", "copyId"],
    indices = [Index("packageName", "userId"), Index("createdAt")],
)
data class BackupRecord(
    /** 作用域：2.x = "${cloud}|${backupDir}"；Next = BackupConfig.uuidString */
    val scopeKey: String,
    val packageName: String,
    val userId: Int,
    /** 副本标识：2.x = preserveId（主备份为 0）；Next = 0 或快照短 id */
    val copyId: Long,
    /** 备份时的版本 */
    val versionCode: Long,
    val versionName: String,
    /** 备份完成时间 */
    val createdAt: Long,
    /** 被备份的部件与体积 */
    val apkBytes: Long,
    val userBytes: Long,
    val userDeBytes: Long,
    val dataBytes: Long,
    val obbBytes: Long,
    val mediaBytes: Long,
    /** 相对归档目录（2.x: pkg/user_uid@copyId；Next: 快照内路径前缀） */
    val archivePath: String,
    /** 内容指纹，用于跨副本去重与损坏检测 */
    val checksum: String? = null,
    /** 受保护：不参与保留策略轮转 */
    val protected: Boolean = false,
    /** 标签，便于按标签批量备份 */
    val label: String? = null,
)
```

派生视图（**每个 App 一行**，供列表页直接消费）：

```kotlin
data class AppBackupStatus(
    val packageName: String,
    val userId: Int,
    val lastBackupAt: Long,          // max(createdAt)
    val copyCount: Int,
    val backedUpVersionCode: Long,   // 最新副本的 versionCode
    val backedUpVersionName: String,
    val totalBytes: Long,
    val hasProtectedCopy: Boolean,
    val scopeHasAnyRecord: Boolean,  // 该作用域下是否有备份
)
```

**`isOutdated` 不落库、纯计算**（避免版本回滚场景下状态失真）：

```
isOutdated = installed.versionCode > status.backedUpVersionCode
isStale    = now - status.lastBackupAt > staleThresholdDays
```

### 4.2 落地到 2.x（`source/`）

| 变更点 | 文件 | 说明 |
|---|---|---|
| 新表 | `core/model/src/main/kotlin/com/xayah/core/model/database/BackupLedgerEntity.kt`（新增） | 如上 |
| 注册 | `core/database/.../AppDatabase.kt` | 加 `@Entity` + `version++` + `AutoMigration` |
| DAO | `core/database/src/main/kotlin/com/xayah/core/database/dao/BackupLedgerDao.kt`（新增） | `upsert()`、`statusFlow(scopeKey)`、`copyCount()`、`oldestUnprotected(scopeKey, pkg, uid, n)` |
| 写入 | `core/service/src/main/kotlin/com/xayah/core/service/packages/backup/AbstractBackupService.kt:160-181` | 备份成功后 `ledgerDao.upsert(...)`（复用已有的 `lastBackupTime` 写入点） |
| 回填 | `PackageRepository.reloadAppsFromLocal12x()` / `reloadAppsFromCloud12x()` | 解析 `configs_package_restore.json` 时同步 upsert 台账（老备份自动建档） |
| 模型暴露 | `core/model/src/main/kotlin/com/xayah/core/model/App.kt:3-11` | `App` 增 `lastBackupTime`、`backedUpVersionCode`、`copyCount`、`hasProtectedCopy`、`isOutdated` |
| 映射 | `PackageEntity.kt:272-280` 的 `asExternalModel()` | 在 Repo 层 join 台账后构造 `App`（保持 `asExternalModel()` 纯函数，另开 `toAppWithStatus()`） |

### 4.3 落地到 Next（`source-next/`）

| 变更点 | 文件 | 说明 |
|---|---|---|
| 新表 | `app/src/main/java/com/xayah/databackup/database/entity/BackupRecord.kt`（新增） | 同上 |
| 注册 | `.../database/AppDataBase.kt:17-34` | 加实体 + DAO |
| 写入 | `.../data/rustic/RusticBackupCoordinator.kt:19-57` | `createSnapshot` 成功后落台账（**manifest 里已有 versionCode/各部件字节数，直接可用**，见 `RusticBackupManifest.kt:7-27`） |
| 回填 | `.../data/rustic/RusticRestoreInventory.kt:74-149` | 读取快照 manifest 时顺带 upsert，使台账成为"备份库索引" |
| 选择器 | `.../data/AppRepository.kt:24-33` | `appsFiltered` 扩展为 `appsWithBackupStatus`（join 台账） |
| UI 状态 | `.../feature/backup/apps/AppsViewModel.kt:50-67` | 增加筛选/排序分支 |

---

## 5. 功能增强提案

### F1（P0）版本变更检测与"待更新"筛选 — 对应痛点 1

**能力**
- 列表为每个 App 实时标注：`未备份` / `已备份` / **`有新版本`** / `备份已过期`。
- 新增筛选器：`仅显示有新版本`、`仅显示未备份`、`仅显示备份过期（>N 天）`。
- 新增批量动作：**"备份所有已更新的应用"**（一键把 `isOutdated` 的 App 全部选中并进入备份流程）。

**2.x 实现**
1. 台账落库（M0）。
2. `AppsRepo.getApps()` 侧：用 `listData` 中新增的 `onlyOutdated` / `onlyNotBackedUp` / `staleDays` 参与过滤。
3. 在 `PackageRepository.kt:71-97` 的 predicate 家族中新增两个（与既有风格一致）：
   ```kotlin
   fun getOutdatedPredicate(enabled: Boolean, outdatedKeys: Set<String>): (PackageEntity) -> Boolean
   fun getStalePredicate(enabled: Boolean, staleDays: Int, lastBackupMap: Map<String, Long>): (PackageEntity) -> Boolean
   ```
4. 排序/筛选 UI 在 `ListBottomSheet.kt:239-293`（`AppsFilterSheet`）增加按钮 + 命中数量徽标；字符串加到 `source/feature/main/list/src/main/res/values/ids.xml:30-33` 同区域。
5. 批量动作挂在 `ListActionsViewModel`（与 `refresh()` 同级），复用已有批量备份入口。

**Next 实现**
1. 台账落库（M0）；`Info.versionCode`（`database/entity/App.kt:130`）已有。
2. `SortsAndFiltersHelper.kt` 新增 `Iterable<App>.filterOutdated(...)` / `.filterStale(...)`，与 `filterApp(userId, ...)` 平行。
3. `DataStoreHelper.kt:57-81` 新增 `KeyFilterOnlyOutdatedBackup` / `KeyFilterStaleDaysBackup`（默认关闭 / 7 天）。
4. `AppFilterSheetContent.kt:157-186` 的 `showAppTypeFilters` 区块追加两个 `FilterButton`。

**验收**：升级某 App → 列表出现"有新版本"标记 → 筛选后只剩它 → 一键备份 → 标记消失、`lastBackupTime` 更新。

---

### F2（P0）多副本与保留策略 — 对应痛点 2

**能力**
- 全局默认保留份数 `N`（选项：1 / 2 / 3 / 5 / 无限），支持每 App 覆盖。
- 备份成功后自动轮转：当副本数 > N 且存在 `copyId != 0 && !protected && copyId == min` 的副本时，删除最旧的未保护副本。
- `protected` 副本永不参与轮转，需要时手工解除。
- App 详情页展示**副本列表**（时间 / 版本号 / 体积 / 保护状态），支持单副本删除与恢复。
- 同 `versionCode` 的 APK 副本复用（硬链接），避免重复占用。

**2.x 实现**
1. DataStore 新增：
   ```kotlin
   val KeyMaxBackupCopies = intPreferencesKey("max_backup_copies")      // 默认 2，0 表示无限
   val KeyBackupCopiesEnabled = booleanPreferencesKey("backup_copies_enabled")
   ```
   写入 `core/datastore/`（参考 `Boolean.kt` / `Int.kt` 既有扩展）。
2. 轮转逻辑放在备份收尾处：`AbstractBackupService.kt:173-181`（`pkg.isSuccess` 分支）之后，调用
   ```kotlin
   BackupRetention.enforce(scopeKey, packageName, userId, maxCopies)
   ```
   内部通过 `BackupLedgerDao.oldestUnprotected(...)` 找候选，再走既有删除路径（本地用 `rootService.deleteRecursively` / 云端用 `client.delete`）。
3. **与现有 `preserveId` 机制的兼容**：保留 `preserveId` 作为副本标识载体，`copyId = preserveId`；新增"新建副本"动作时自动分配 `System.currentTimeMillis()`（沿用 `AppDetails.onProtect` 的既有语义），只是**从手工变成按策略自动执行**。
4. 副本列表 UI 放在 `source/feature/main/details/src/main/kotlin/com/xayah/feature/main/details/AppDetails.kt`，数据源改为 `ledgerDao.recordsFlow(scopeKey, pkg, uid)`。
5. 全局设置入口在 `source/feature/main/configurations/`（已有设置页骨架）。

**Next 实现**
1. Archive 后端（`PathHelper.kt:36-41` 固定文件名）：**改目录结构**为 `<config>/apps/<pkg>/<userId>/<copyId>/{apk,user,user_de,data,obb,media}.tar.zst`，`copyId = 备份时间戳`。需在 `BackupAppsHelper` 中改路径生成，并保留旧结构的兼容读取（见 §6）。
2. Rustic 后端：新增 retention —— 备份完成后按策略 `deleteRusticSnapshot` 多余的快照（`RusticBackupGateway.deleteSnapshot` 已具备能力，`.../RusticBackupGateway.kt:88-96`）。**注意**：Rustic 快照是"整库一次备份"，不是按 App 分副本，所以 Rustic 的 retention 应表述为"保留最近 N 个快照 / 保留最近 N 天"，并与 `protected`（标记快照 tag）配合。
3. 设置项加在 `.../feature/settings/`，副本数写入 DataStore。

**验收**：设 N=2 → 连续备份同一 App 3 次 → 只剩最近 2 份；对第 2 份打"保护" → 再备份 → 受保护副本仍在。

---

### F3（P0）云端清单缓存 + 增量同步 — 对应痛点 3

**设计：三级加速**

**L1 — 远端清单文件**

远端作用域根目录新增：

```
<remote>/configs/index.json          # 人类可读，便于排查
<remote>/configs/index.json.zst      # 实际读取的压缩版本
```

```jsonc
{
  "schemaVersion": 1,
  "generatedAt": 1737000000000,
  "entries": [
    {
      "path": "apps/com.foo/user_0@1736900000000",
      "packageName": "com.foo", "userId": 0, "copyId": 1736900000000,
      "versionCode": 1234, "versionName": "1.2.3",
      "mtime": 1736900000000, "bytes": 1048576,
      "components": { "apk": 1048576, "user": 204800, "userDe": 0, "data": 0, "obb": 0, "media": 0 },
      "protected": false
    }
  ]
}
```

- **刷新时只需 `exists` + 下载 1 个文件**（甚至可用 HTTP `If-Modified-Since`/`If-None-Match` 直接 304）。
- 清单由备份/删除动作**自增维护**（备份成功后 append 条目并回写），不必每次全量重算。
- 若清单缺失（老数据/被手工删除），退化为一次全量扫描并**同时补建清单**。

**L2 — 变更探测与增量合并**

- 以 `(scopeKey, path)` 为键，`(mtime, bytes, versionCode)` 为版本向量，与本地台账/缓存比对：
  - 键不存在 → 新增（需解析）
  - 版本向量不同 → 变更（需重新解析）
  - 键在缓存但不在清单 → 删除（**直接按 diff 批量删，不再逐条 `exists()`**）
  - 其他 → 跳过
- 若远端缺少清单，则用目录级 `mtime` 做**逐层懒加载**替代一次性 `walkFileTree`：先列一级、仅对 mtime 变化的子目录下钻。
- `CloudClient` 接口扩展（`source/core/network/src/main/kotlin/com/xayah/core/network/client/CloudClient.kt:26-32`）：
  ```kotlin
  fun stat(src: String): FileStat?            // mtime, size, etag, isDirectory
  fun listFilesDetailed(src: String): List<FileStat>   // 带 etag/mtime
  ```
  - WebDAV：`PROPFIND Depth:1`，取 `getlastmodified` + `getetag`（`WebDAVClientImpl.kt:159-175` 目前把它们丢了，需补回）
  - SMB：`diskShare.fileExists` + `FileAttributes`
  - SFTP：`it.stat(src).attributes`（`SFTPClientImpl.kt:191-206` 已含 `atime`，可扩为 `mtime`）
  - FTP：`FTPFile.timestamp`

**L3 — 本地缓存即数据源**

- `PackageDao.queryPackages(opType, cloud, backupDir)`（`PackageDao.kt:203-207`）继续作为**首屏唯一数据源** → 秒开。
- 新增同步元数据（DataStore 或独立表）：
  ```kotlin
  data class SyncMeta(val scopeKey: String, val lastSyncedAt: Long, val remoteIndexMtime: Long?, val remoteIndexEtag: String?)
  ```
- 进入列表页：**先渲染缓存** + 顶部显示"上次同步：X 分钟前" + 后台触发增量同步；仅当缓存为空（首次）才显示全屏 Loading。
- 把 `WorkManagerInitializer.loadAppBackups` 的 `ExistingWorkPolicy.KEEP`（`WorkManagerInitializer.kt:58-60`）改为 `REPLACE` 或 `APPEND_OR_REPLACE`，并提供"强制全量重建索引"的独立入口。

**预期收益**：100 个 App / 千级目录场景下，刷新网络往返从 **数千次 → 1~2 次**（清单命中时），首次冷启动也需要一次全量但会建索引。

**Next 侧的等价优化（低成本、高收益，建议同批做）**
1. `BackupConfigRepository.loadBackupConfigsFromLocal()`（`BackupConfigRepository.kt:147-173`）：
   - 用 `RemoteRootService.listFilePaths` 一次拿到所有 `.config`（**当前是"先列目录、再逐目录 readText"，应改为"一次列出 `*/​.config` 或一次批量读取"**），或新增 AIDL `readTextBatch(paths): Map<String,String>`。
   - `deduplicateConfigs()` 命中重复时的**回写放到后台**，不要阻塞首屏。
   - 加 `isLoaded` 短路：`BackupLibraryViewModel.initialize()`（`BackupLibraryViewModel.kt:76-81`）在 `repo.isLoaded == true` 时跳过重扫，仅做后台静默刷新。
2. `RusticRestoreInventory` 结果落库（复用 M0 的 `BackupRecord`），恢复页改为"读台账秒开 + 后台校验"。
3. 避免在列表页调用 `calculateTreeSize`（`BackupSetupViewModel.kt:68-80`、`DashboardViewModel`），改为**读台账累加**或异步更新。

---

### F4（P1）筛选/排序体系重构 — 对应痛点 4

**排序维度（新增加粗）**

| 维度 | 2.x | Next |
|---|---|---|
| 名称 A-Z | ✅ | ✅ |
| 数据大小 | ✅ | ✅ |
| 安装时间 | ✅ | ✅ |
| 更新时间 | ❌ | ✅ |
| **上次备份时间** | ➕ | ➕ |
| **备份占用大小** | ➕ | ➕ |
| **副本数** | ➕ | ➕ |
| **版本号** | ➕ | ➕ |
| 已选优先 | ❌ | ✅ |

**筛选维度（新增加粗）**

`用户 / 系统` ✅、`有备份 / 无备份` ✅(2.x)、`已安装 / 未安装` ✅(2.x)、**`有新版本`** ➕、**`备份已过期(>N天)`** ➕、**`已保护副本`** ➕、**`副本数 ≥ N`** ➕、**`标签`** ➕(2.x 已有标签体系，需接入列表筛选)。

**工程改造（避免"加一个维度改五处"）**

- 2.x：把 `sortIndex: Int` 改为枚举（新增 `SortKey`），替换 `arrays.xml:2-6` 的字符串数组为**代码内枚举 + `@StringRes`**，`PackageRepository.kt:99-130` 的 `sortByXxxNew` 改为 `Comparator<PackageEntity>` 表驱动：
  ```kotlin
  enum class SortKey(val resId: Int, val appSort: Boolean, val fileSort: Boolean) {
      ALPHABET(...), INSTALL_TIME(...), UPDATE_TIME(...), DATA_SIZE(...),
      LAST_BACKUP_TIME(...), BACKUP_SIZE(...), COPY_COUNT(...), VERSION_CODE(...)
  }
  ```
  排序做成 `Map<SortKey, (SortType) -> Comparator<T>>`，加维度只改一处。
- 2.x：`Filters`（`ListDataRepo.kt:200-208`）扩展字段并加默认值，避免破坏调用方；筛选条件抽成 `List<Predicate>` 而不是无限 `if`。
- Next：`SortsType`（`DataStoreHelper.kt:14-19`）扩展即可，同时把 `KeySortsTypeBackup` / `KeySortsSequenceBackup` 拆成**备份页与恢复页两套 key**，避免状态串台。
- 两端统一：新增"保存为视图"（把当前 `SortKey + Filters` 存成一个命名视图），为后续计划备份复用（F5）。

**UI 建议**：筛选面板改"分区 + 命中计数"，排序改为可点击列头/单选列表；列表项右侧显示 `上次备份：3 天前 · 2 份` 的次级信息（2.x 已有展示能力，见 `AppDetails.kt:508-512`）。

---

### F5（P1）对齐竞品的延伸项

| 项 | 说明 | 依赖 |
|---|---|---|
| **计划备份** | WorkManager `PeriodicWorkRequest`，scope 可选"全部/标签/当前筛选视图/仅已更新"；解锁后运行（`setRequiresDeviceIdle` 可选） | F1 + F4 的视图 |
| **备份验证** | 对台账中每条记录做 checksum 校验（2.x 可校验归档 tar 可读；Next 用 Rustic `check`） | M0 台账的 `checksum` |
| **标签体系补到 Next** | 2.x 已有 `LabelEntity`/`LabelAppCrossRefEntity`，Next 需补表 + UI | — |
| **云端协议扩展** | 现有 FTP/WebDAV/SMB/SFTP；后续可加 S3 / Rclone 后端（Next 的 Rustic 若开启 `rustic_backend` 远端 feature 可低成本获得 S3/WebDAV 能力） | F3 |
| **备份报告/通知** | 完成后通知"成功 N 失败 M，共 X GB"，失败项可重试 | — |

---

## 6. 数据库迁移与兼容

**2.x（Room）**
- 新增 `BackupLedgerEntity` 与 `BackupLedgerDao`；`AppDatabase` version++。
- 迁移策略：`AutoMigration` + 首次启动**后台回填任务**（扫本地备份目录的 `configs_package_restore.json` 建台账；云端在首次同步时按 F3 建台账）。回填期间列表正常工作，仅缺少"已更新/副本数"标记。

**Next（Room + 文件布局）**
- 新增 `BackupRecord`；`AppDataBase` version++。
- Archive 目录结构变更（`<pkg>/<uid>/<copyId>/...`）必须**兼容旧结构**：读取时先探测新结构，不存在则回退旧固定文件名并视为 `copyId = 0`；新备份一律写新结构。
- Rustic 侧无需迁移（仓库自描述）。

**向后兼容红线**
- 备份产物必须能被**旧版本 App 读取**（2.x 的 `reloadAppsFromXxx12x` 依赖 `pkg/user_uid[@preserveId]` 路径约定；Next 依赖 `PathHelper` 常量）。因此：
  - 2.x 的多副本沿用 `preserveId` 命名，**不要**引入新的目录层级。
  - Next 的新目录结构需要同步更新 `RusticAppSourcePlanner` / `RusticRestoreInventoryReader` 的路径解析，并保留旧结构分支。

---

## 7. 里程碑：实现与验证同步规划

> 估算按单人全职投入，含自测与联调，不含翻译与发布。
> **每个里程碑都把"实现步骤"与"验证方式"成对定义**：实现交付后立即按该里程碑的验证清单执行，全部通过（Exit Criteria 打勾）才允许进入下一阶段。验证失败的**默认动作是回退该里程碑**，不允许"先往下做、回头再补"。

### 7.0 轨道分配总览

| 里程碑 | 内容 | 2.x（`source/`） | Next（`source-next/`） | 估时 |
|---|---|---|---|---|
| **M0** | 台账表 + DAO + 迁移 + 老备份回填 + 测试基建 | ✅ 必做 | ✅ 必做 | 2~3 天 |
| **M1** | 版本变更检测 + "待更新"筛选 + 一键备份已更新 | ✅ 必做 | ✅ 必做（**含 M2 子任务**） | 2~3 天 |
| **M2** | 排序/筛选体系重构（`SortKey` 枚举化 + 上次备份时间排序） | ✅ 必做（真重构） | ⚠️ **并入 M1**（扩枚举即可） | 2~3 天 / Next 0.5 天 |
| **M3** | 本地缓存优先渲染 + `isLoaded` 短路 + 批量 IPC/读取 | ✅ 必做 | ✅ 必做 | 2 天 |
| **M4** | 远端清单 + 增量 diff + `CloudClient.stat/listFilesDetailed` | ✅ 必做 | ⏸ **阻塞**（无云端，§1.4.1） | 4~6 天 |
| **M5** | 保留策略 + 副本列表 UI + 同版本 APK 去重 | ✅ 必做 | ⏸ **阻塞**（需先定后端语义 + 冻结文件布局，§1.4.2） | 4~5 天 |
| **M6** | 计划备份 / 备份验证 / Next 标签体系 | ⚠️ 可选 | ⏸ 阻塞 | 5~8 天 |

**推荐顺序**
- **2.x**：`M0 → M1 → M2 → M3 → M4 → M5 → M6`
- **Next**：`M0 → M1(含 M2 子任务) → M3`，之后按 §1.4.3 的合流条件跟进

**为什么是这个顺序**：M0 是共用底座，也是**唯一两条轨道必须语义一致的产物**；M1/M2 投入产出比最高（用户感知最强）；M3 是 M4 的前置（**先有缓存，增量才有落点**）；M5 涉及删除用户数据，必须在台账稳定、M4 的清单可信之后才做。

---

### M0 — 备份台账底座（2~3 天）

**目标**：把"当前设备状态"与"备份产物"关联起来，产出可查询的 `AppBackupStatus`。

| # | 端 | 实现动作 |
|---|---|---|
| 0.1 | 共用 | 冻结 `BackupRecord` 字段契约（§4.1），**两端字段名逐字一致** |
| 0.2 | 共用 | 冻结派生规则：`copyId=0` 为主副本；`isOutdated = installed.versionCode > backedUpVersionCode`；`isStale = now - lastBackupAt > staleDays` |
| 0.3 | 2.x | 新增 `BackupLedgerEntity` + `BackupLedgerDao`；`AppDatabase` version++ + `AutoMigration` |
| 0.4 | 2.x | `AbstractBackupService.kt:173-181` 成功分支写台账（与既有 `lastBackupTime` 同一处，保证不漏写） |
| 0.5 | 2.x | `PackageRepository.reloadAppsFromLocal12x()` / `...FromCloud12x()` 解析 config 时回填台账 |
| 0.6 | Next | 新增 `BackupRecord` + `BackupRecordDao`；`AppDataBase` version++ |
| 0.7 | Next | `RusticBackupCoordinator.kt:19-57` 快照成功后写台账（数据源：snapshot id + manifest） |
| 0.8 | Next | `RusticRestoreInventory.kt:74-149` 读取 manifest 时回填台账 |
| 0.9 | 共用 | **测试基建**：`kotlinx-coroutines-test`、`Room.inMemoryDatabaseBuilder`、Fake 云客户端（2.x 走 `androidTest`；Next 可上 Robolectric 跑 JVM） |

| 验证层级 | 验证内容 |
|---|---|
| 单元（JVM） | ①`isOutdated` 真值表：安装版本 = / > / < 备份版本；②`isStale` 边界（恰好等于 `staleDays`）；③多副本时"最新副本"按 `createdAt` 选取（**不是按 `copyId` 大小**） |
| 单元（Room） | `upsert` 幂等（同主键重复写不产生两行）；`oldestUnprotected` 在 `protected` 混合场景返回正确候选；`statusFlow` 在插入/删除后 emit 新值 |
| 迁移 | ①空库升级 → 新表建成；②已有数据升级 → 旧数据不丢（`PackageDao.queryPackages` 结果条数不变） |
| 回填 | 构造本地备份目录（2 个 App × 2 副本）→ 首启后台账条数 = 4；删掉一个目录再回填 → 条数 = 2 |
| 回归 | 不做任何新操作，仅升级安装 → 备份 / 恢复 / 云列表三条核心流程仍可用 |

**出口标准（Exit Criteria）**
- [ ] 台账能回答"App X 最后一次备份是什么时候、什么版本、几份"
- [ ] 老备份 100% 回填成功，备份目录为空的场景不崩
- [ ] 迁移测试通过，零数据丢失
- [ ] 两端 `BackupRecord` 字段名 / 语义交叉评审确认一致

---

### M1 — 版本变更检测与"待更新"筛选（2~3 天）

**目标**：一眼看出哪些 App 需要重新备份，并能一键批量备份。

| # | 端 | 实现动作 |
|---|---|---|
| 1.1 | 2.x | `App` 模型（`core/model/.../App.kt:3-11`）增 `lastBackupAt` / `backedUpVersionCode` / `copyCount` / `hasProtectedCopy` / `isOutdated`；**新增 `toAppWithStatus()`，保持 `asExternalModel()` 纯函数语义不变** |
| 1.2 | 2.x | `AppsRepo.getApps()` 内 join 台账；`PackageRepository.kt:71-97` 新增 `getOutdatedPredicate` / `getStalePredicate` |
| 1.3 | 2.x | `Filters`（`ListDataRepo.kt:200-208`）增 `onlyOutdated` / `staleDays`（**带默认值**，不破坏既有调用方） |
| 1.4 | 2.x | `ListBottomSheet.kt:239-293` 加筛选按钮 + 命中数量徽标；文案加到 `feature/main/list/src/main/res/values/ids.xml:30-33` 同区 |
| 1.5 | 2.x | `ListActionsViewModel` 加 `backupOutdated()`：查 `isOutdated` 的 key 集合 → 批量置 `activated=true` → 跳备份处理页 |
| 1.6 | Next | `SortsAndFiltersHelper.kt` 加 `Iterable<App>.filterOutdated(...)` / `.filterStale(...)` |
| 1.7 | Next | `DataStoreHelper.kt:57-81` 加 `KeyFilterOnlyOutdatedBackup`（默认 false）/ `KeyFilterStaleDaysBackup`（默认 7） |
| 1.8 | Next | `AppFilterSheetContent.kt:157-186` 追加两个 `FilterButton`；`feature/backup/apps/AppsViewModel.kt:50-67` 接上筛选 |
| 1.9 | Next | **（= M2 子任务）** `SortsType` 加 `LAST_BACKUP_TIME`；`SortsAndFiltersHelper.kt:49-71` 加 `sortByLastBackupTime`；`AppFilterSheetContent.kt:115-156` 加对应项 |

| 验证层级 | 验证内容 |
|---|---|
| 单元（JVM） | `filterOutdated` 真值表：无备份 / 版本相同 / 版本更高 / 版本更低 / 多用户；`sortByLastBackupTime` 升降序**稳定性**（同值元素顺序不抖动） |
| 单元（JVM） | **无回归**：`Filters` 新字段为默认值时，`getApps()` 输出与改造前逐项相等 |
| 手工 A1 | 装旧版 APK → 备份 → `adb install -r` 更高 versionCode → 列表出现"有新版本"；勾"仅显示已更新"后只剩它 |
| 手工 A2 | 点"备份所有已更新" → 选中数 = 命中数 → 完成后标记全消失、`lastBackupAt` 更新为当前 |
| 边界 | ①App 已卸载但有备份 → 不因 `versionCode` 缺失误判 outdated；②用户降级安装（versionCode 变小）→ 不标记 outdated；③从未备份 → 标"未备份"，不标"已更新" |
| 性能 | 500 个 App 时筛选 + 排序在 `Dispatchers.Default` 上完成，主线程无阻塞（< 16ms/帧） |

**出口标准**
- [ ] A1 / A2 手工场景通过
- [ ] `filterOutdated` 真值表单测全绿
- [ ] 默认筛选参数下列表输出与改造前一致（无回归）
- [ ] 降级安装 / 卸载残留两类边界不误报

---

### M2 — 排序与筛选体系重构（2~3 天；Next 见 M1 的 1.9）

**目标**：消除 `sortIndex: Int` 硬编码，把"加一个维度"的成本收敛到一处。

| # | 端 | 实现动作 |
|---|---|---|
| 2.1 | 2.x | 新增 `SortKey` 枚举：`ALPHABET / INSTALL_TIME / UPDATE_TIME / DATA_SIZE / LAST_BACKUP_TIME / BACKUP_SIZE / COPY_COUNT / VERSION_CODE`，每项带 `@StringRes` + 支持的 target 集合 |
| 2.2 | 2.x | `PackageRepository.kt:99-130` 的 `sortByXxxNew` 改为 `Map<SortKey, (SortType) -> Comparator<PackageEntity>>` 表驱动；`MediaEntity` 同样处理 |
| 2.3 | 2.x | `ListDataRepo.kt:60-61,215-216` 的 `sortIndex: Int` → `sortKey: SortKey`；**DataStore 持久化格式由 index 改为 name，需迁移**（旧值按固定映射表转换） |
| 2.4 | 2.x | 删除 `arrays.xml:2-6` 的字符串数组；`ListBottomSheet.kt:290-291` 改为遍历枚举 |
| 2.5 | 2.x | `Filters` 从"一堆 boolean"抽成 `List<Predicate<PackageEntity>>`，新维度只加一个工厂函数 |
| 2.6 | Next | 把 `KeySortsTypeBackup` / `KeySortsSequenceBackup`（`DataStoreHelper.kt:63-81`）拆成**备份页与恢复页两套 key**，消除状态串台 |

| 验证层级 | 验证内容 |
|---|---|
| 单元（JVM） | **排序等价性测试（重构安全网）**：同一份随机数据集，枚举化前后排序结果**逐项相等**（3 个原有维度 × 升降序 = 6 组断言） |
| 单元（JVM） | **完整性断言**：`SortKey.entries` 每项 `@StringRes` 非 0；每个 key 在 Comparator 表里都有实现（反射遍历，防漏配） |
| 迁移 | DataStore 旧值 `sortIndex=0/1/2` → `ALPHABET/INSTALL_TIME/DATA_SIZE` 映射正确；未知旧值 fallback 到 `ALPHABET` |
| 手工 A5 | 按"上次备份时间"降序 → 顺序与每个 App 详情页显示的时间一致（**两个数据源交叉校验**） |
| 手工 | 排序 / 筛选切换后重启 App，设置被正确记忆 |
| 回归 | `Target.Files` 文件列表排序行为不变（复用同一套 Repo 方法） |

**出口标准**
- [ ] 排序等价性测试全绿
- [ ] `SortKey` / Comparator 完整性断言全绿（无漏配维度）
- [ ] DataStore 迁移正确
- [ ] `arrays.xml` 中旧数组引用已清零（`grep` 确认）

---

### M3 — 本地缓存优先渲染与批量 I/O（2 天）

**目标**：列表首屏不再等待 I/O；重复进入不重扫。

| # | 端 | 实现动作 |
|---|---|---|
| 3.1 | 2.x | 新增 `SyncMeta` 持久化（`scopeKey / lastSyncedAt / remoteIndexMtime / remoteIndexEtag`） |
| 3.2 | 2.x | 列表页改为"**先读 DB 缓存渲染** + 顶部显示'上次同步：X 分钟前' + 后台触发同步"；仅缓存为空时全屏 Loading |
| 3.3 | 2.x | `AppsLoadWorker` 挂载策略由 `KEEP` 改为 `REPLACE`（`WorkManagerInitializer.kt:58-60`），并区分"增量刷新"与"强制重建索引"两个入口 |
| 3.4 | 2.x | 结算阶段（`AppsRepo.kt:499-504 / 540-545`）的逐条 `exists()` 改为**先批量列举一次再本地比对** |
| 3.5 | 2.x | 给 `CloudClient` 加调用计数器（仅 debug），作为 M4 的性能基线测量钩子 |
| 3.6 | Next | `BackupConfigRepository.kt:147-173` 改为"**一次列举 + 批量读取**"，避免"每个备份目录一次 `readText` IPC"；或新增 AIDL `readTextBatch(paths): Map<String,String>` |
| 3.7 | Next | `deduplicateConfigs()`（`BackupConfigRepository.kt:77-87`）的重复 uuid 回写**移出首屏路径**（后台静默） |
| 3.8 | Next | `BackupLibraryViewModel.kt:76-81` 加 `isLoaded` 短路：已加载则跳过重扫，仅后台静默刷新 |
| 3.9 | Next | 列表页不再调用 `calculateTreeSize`（`BackupSetupViewModel.kt:68-80`），改为读台账累加 / 异步更新 |

| 验证层级 | 验证内容 |
|---|---|
| 单元（JVM） | `readTextBatch` 的 Fake `RemoteRootService`：100 个目录时**断言 IPC 次数 = 1**（而非 100） |
| 单元（JVM） | 缓存优先状态机：`isLoaded=false && cache 非空` → 立刻 emit Content 且 `isRefreshing=true`；后台完成后 emit 新 Content |
| 性能基线 | **可复现合成数据集**：脚本生成 200 个 App × 3 副本目录结构，测量 (a) 首屏到首帧 (b) 单次刷新耗时 (c) IPC / 远端调用次数。M3 前后各跑一次并写入 PR 描述 |
| 手工 A6 | 断网进入云端列表 → 展示缓存 + "上次同步：X 分钟前"，不白屏、不 crash |
| 手工 | 连续快速进出列表页 10 次 → 不产生 10 次全量扫描（日志计数证明）；`WorkManager` 无任务堆积 |
| 回归 | 首次安装（缓存为空）仍能正常全量加载并建索引 |

**出口标准**
- [ ] 有缓存时首屏不等待任何 I/O
- [ ] 重复进入不重扫（日志证明扫描次数 = 1）
- [ ] A6 通过
- [ ] 性能基线数据已记录（作为 M4 的对照基准）

---

### M4 — 远端清单与增量同步（4~6 天，**仅 2.x**）

**目标**：刷新时的远端往返从 `O(目录数 + App 数)` 降到 `O(1)`。

| # | 端 | 实现动作 |
|---|---|---|
| 4.1 | 2.x | 定义并**冻结** `index.json` 契约（§5 F3 L1：`schemaVersion / generatedAt / entries[]`），放在 `core/model` 便于未来跨轨道复用 |
| 4.2 | 2.x | 备份成功 / 删除副本后**自增维护清单**（append / remove + 回写）；回写失败则删除清单，下次全量重建 |
| 4.3 | 2.x | `CloudClient`（`CloudClient.kt:26-32`）扩展 `stat()` 与 `listFilesDetailed()`；四实现分别补：WebDAV 取 `getlastmodified` + `getetag`（`WebDAVClientImpl.kt:159-175` **当前丢弃了它们**）、SMB 用 `FileAttributes`、SFTP 用 `stat().attributes`（`SFTPClientImpl.kt:191-206` 已取 `atime`）、FTP 用 `FTPFile.timestamp` |
| 4.4 | 2.x | 实现 `CloudIndexSync`：拉清单 → 与本地缓存按 `(scopeKey, path)` + `(mtime, bytes, versionCode)` 版本向量做 diff → **仅对增 / 改项**下载解析；删除项按 diff 批量删 |
| 4.5 | 2.x | 清单缺失时的降级路径：一次全量 `walkFileTree` 并**同时补建清单**（幂等） |
| 4.6 | 2.x | 清单走 `If-Modified-Since` / `If-None-Match`，命中 304 直接跳过解析 |
| 4.7 | 2.x | 新增"**强制重建索引**"入口（设置页 / 列表溢出菜单） |

| 验证层级 | 验证内容 |
|---|---|
| 单元（JVM） | **diff 算法真值表（核心）**：给定 (缓存集合, 远端清单) 的 6 种组合 —— 新增 / 删除 / 修改 / 未变 / 清单 entries 为空 / 清单整文件缺失 —— 断言产出的 `(toParse, toDelete, toSkip)` 三集合精确正确 |
| 单元（JVM） | 清单**向后兼容**：`schemaVersion` 更大时拒绝解析并降级全量；字段缺失取默认值不抛异常 |
| 单元（JVM） | 四个 `CloudClient` 实现的 `stat` / `listFilesDetailed` 用 Mock 服务端响应验证字段映射（不连真机） |
| 契约（Fake） | `CloudIndexSync` 全程跑在 Fake `CloudClient` 上，**断言调用次数**：①清单命中且无变更 → 调用 ≤ 2（`stat` + `get`）；②有 1 个新增 App → 只下载 1 个 config |
| 手工 A4 | 云端 100 个 App：改造前记录 (请求次数, 耗时) → 改造后重测，**网络往返 ↓ ≥ 95%，同步耗时 ↓ ≥ 80%** |
| 手工 A7 | 远端手工删掉一个备份目录 → 刷新后条目正确消失，且日志中**没有** N 次 `exists()` |
| 手工 | 远端手工新增一个备份目录（不通过 App）→ 刷新后能发现（走清单 diff 或降级全量） |
| 容错 | ①清单写入半截损坏 → 降级全量且不 crash；②同步中途断网 → 已解析部分仍生效，`lastSyncedAt` **不更新**；③两台设备同时写清单 → `generatedAt` 较旧的一方被丢弃并降级全量 |
| 一致性 | **强制重建索引的结果 == 增量同步的结果**（逐项相等）—— 这是最重要的自愈性断言 |

**出口标准**
- [ ] diff 真值表单测全绿
- [ ] A4 性能指标达成（往返 ↓ ≥ 95%，耗时 ↓ ≥ 80%）
- [ ] A7 通过，且全流程无逐条 `exists()`
- [ ] 容错三项（损坏 / 断网 / 并发）均正确降级
- [ ] "强制重建 == 增量"一致性断言通过

---

### M5 — 多副本与保留策略（4~5 天）

**目标**：可配置保留 N 份、自动轮转，且**绝不误删受保护副本**。

| # | 端 | 实现动作 |
|---|---|---|
| 5.1 | 2.x | DataStore 加 `KeyMaxBackupCopies`（默认 2，0 = 无限）/ `KeyBackupCopiesEnabled`；参考 `core/datastore/` 既有 `Int.kt` / `Boolean.kt` |
| 5.2 | 2.x | 新增 `BackupRetention.enforce(scopeKey, pkg, uid, maxCopies)`；候选来自 `BackupLedgerDao.oldestUnprotected(...)` |
| 5.3 | 2.x | 挂载点：`AbstractBackupService.kt:173-181` 成功分支之后，顺序必须是 **先写台账、再轮转**（否则会把刚生成的副本当候选） |
| 5.4 | 2.x | 副本标识沿用 `preserveId`，**保持 `0 = 主备份` 语义不变**；"新建副本"改为按策略自动分配 `System.currentTimeMillis()` |
| 5.5 | 2.x | 删除采用**软删**：移入 `<backupDir>/.trash/<ts>/`，由后台任务延迟清理（阶段一先做软删，阶段二再自动清理） |
| 5.6 | 2.x | `AppDetails.kt` 展示副本列表（时间 / 版本 / 体积 / 保护态），支持单副本删除与"恢复此副本" |
| 5.7 | 2.x | 设置页（`feature/main/configurations/`）加保留份数选项 + 每 App 覆盖入口 |
| 5.8 | 2.x | 同 `versionCode` 的 APK 副本复用：本地用硬链接（`rootService` 加 `linkTo`）；云端跳过（远端无稳定硬链接语义） |

| 验证层级 | 验证内容 |
|---|---|
| 单元（JVM） | **决策真值表（核心）**：N=2/3、候选含 `protected`、候选含 `copyId=0` 主副本、候选数 ≤ N、N=0（无限）→ 断言"待删集合"精确正确；**断言主副本 `copyId=0` 永不进入待删集合** |
| 单元（JVM） | 顺序性：备份第 N+1 份后，台账条数 **= N**（既不是 N+1 也不是 N-1） |
| 单元（JVM） | 软删幂等：对同一候选重复 `enforce` 不重复移动、不报错 |
| 手工 A3 | 设 N=2 → 连续备份同一 App 3 次 → 只剩最近 2 份；对其中一份打"保护" → 再备份 2 次 → 受保护副本仍在，未保护的被轮转 |
| 手工 | 每 App 覆盖：全局 N=1、某 App 覆盖为 5 → 该 App 保留 5 份，其他仍 1 份 |
| 手工 | 硬链接去重：不升级版本连续备份两次 → 磁盘占用不翻倍（`du` 对比）；删其中一份不影响另一份可读 |
| 手工 | 副本列表显示的副本数 / 体积与磁盘实际一致；"恢复此副本"能正确恢复 |
| 危险场景 | ①N 从 5 调到 1 → 确认框明确提示将删除 4 份 → 确认后正确执行；②轮转中断网 / 断电 → 台账与实际文件仍一致（下次启动自愈） |
| 回归 | 关闭多副本（`KeyBackupCopiesEnabled=false`）时行为与改造前**完全一致**（含手工创建"保护"副本仍可用） |

**出口标准**
- [ ] 决策真值表全绿，主副本 / 受保护副本零误删
- [ ] A3 通过
- [ ] 磁盘占用去重生效
- [ ] 危险场景有二次确认，软删内容可恢复
- [ ] 关闭开关时行为与改造前一致（无回归）

---

### M6 — 竞品对齐延伸项（5~8 天，优先级最低）

| 子项 | 实现要点 | 验证方式 |
|---|---|---|
| **计划备份** | WorkManager `PeriodicWorkRequest`；scope 复选"全部 / 标签 / 当前筛选视图 / 仅已更新"；解锁后执行 | 单元：scope → 选中集合的映射真值表；手工：设 1 分钟周期（测试用）验证触发且不重复入队；断电重启后计划仍在 |
| **备份验证** | 对台账每条记录做 checksum 校验（2.x 校验归档 tar 可读；Next 用 Rustic `check`） | 单元：篡改一个字节后校验必须失败；手工：删掉一个归档内的文件 → 验证报告标红并可定位到 App |
| **Next 标签体系** | 补 `LabelEntity` / `LabelAppCrossRefEntity` + UI（2.x 已有可参考） | 单元：标签增删改查 + 与台账联查；手工：按标签筛选 + 仅备份该标签 |
| **备份报告 / 通知** | 完成后通知"成功 N / 失败 M，共 X GB"，失败项可重试 | 手工：造 1 个必失败项（如目录不可写）→ 报告准确、重试可收敛 |

**出口标准**：每个子项独立验收，不强绑定发布；优先做"计划备份"（竞品差距最大）。

---

## 8. 验证方法论与测试基建

§7 里反复引用"单元（JVM）/ 单元（Room）/ 契约（Fake）/ 手工 / 性能基线"五类验证，这里统一定义它们怎么落地。

### 8.1 现状：测试基建几乎是空的

| 位置 | 现状 |
|---|---|
| `source/core/model/src/test/.../UnitTest.kt` | 占位文件 |
| `source/core/data/src/androidTest/.../UnitTest.kt` | 一个依赖真实 root shell 的"手工式"测试 |
| `source/build-logic/convention/src/main/kotlin/LibraryTestConventionPlugin.kt:7-17` | 只给 library 模块加了 `junit` |
| `source-next/app/src/test/.../RusticSnapshotTest.kt` | 唯一有意义的单测（Moshi 序列化往返） |

**结论**：M0 必须先补基建，否则后续所有里程碑的验证都只能靠手工，成本不可控。

### 8.2 需要新增的依赖

| 用途 | 依赖 | 加在哪 |
|---|---|---|
| 协程测试 | `kotlinx-coroutines-test` | 两端 `commonTest` / `testImplementation` |
| Room 内存库 | `androidx.room:room-testing` | 2.x `core/database`；Next `app` |
| 免设备跑 Android API | `robolectric` | 优先给 Next（单模块，易接）；2.x 走 `androidTest` 亦可 |
| 断言 / 桩 | `mockk` 或手写 Fake（**优先手写 Fake**，见 8.3） | 两端 |
| 覆盖率 | `kover` 或 `jacoco` | 两端，仅对 `core/data`、`core/database`、`util` 三个包设阈值 |

### 8.3 五类验证的执行方式

| 类型 | 适用对象 | 做法 | 运行命令 |
|---|---|---|---|
| **单元（JVM）** | 纯函数：predicate、Comparator、`filterOutdated`、diff 算法、retention 决策 | 直接 JUnit4，无需 Android 环境。**算法类必须先写测试再实现** | 2.x：`./gradlew :core:data:test`<br>Next：`./gradlew :app:testDebugUnitTest` |
| **单元（Room）** | DAO 行为（幂等 upsert、`oldestUnprotected`、Flow 重发） | `Room.inMemoryDatabaseBuilder(...).allowMainThreadQueries().build()`；每例独立建库 | 同上 |
| **契约（Fake）** | `CloudClient`、`RemoteRootService`、`CloudIndexSync` | **手写 Fake 实现接口**（不用 MockK 打桩，避免"测实现而非测契约"）；Fake 内记录调用次数与顺序，用于**次数断言**（M4 的核心指标） | 同上 |
| **手工场景** | A1~A8 | 每项写成可复现步骤（含 `adb` 命令），记入 PR 描述 | — |
| **性能基线** | M3 / M4 的加速比 | 脚本生成合成数据集（200 App × 3 副本），采集 (首帧耗时, 刷新耗时, 远端/IPC 调用次数) | 2.x：`./gradlew :app:assembleDebug` + `adb install` + 采集日志 |

### 8.4 合成数据集脚本（M3/M4 的前置）

需要一个可复现的"假云端"：本地目录树按 `<cloudDir>/apps/<pkg>/user_<uid>[@<copyId>]/configs_package_restore.json` 规则生成 200 个 App × 3 副本，用 `CloudClient` 的 **LocalFileClient**（Fake）指向它。好处是：

- 不依赖真实云盘 / 网络，CI 可跑；
- 可以精确控制 `mtime`，从而测试"只有 1 个条目变化"的场景；
- 调用次数可断言，性能对比可比。

### 8.5 CI 接入

- 每个里程碑 PR 必须附：**单测全绿 + A 系列手工场景截图/日志 + 性能基线对比表**。
- 命令（沿用各工程既有约定）：
  - 2.x：`./gradlew test`、`./gradlew lint`、`./gradlew :core:data:test`
  - Next：`./gradlew test`、`./gradlew lint`、`:app:testDebugUnitTest`（见 `source-next/AGENTS.md:23-29`）
- 覆盖率红线：`core/data`、`core/database`、`util` 三类包新增/修改代码行覆盖率 ≥ 70%，其中**删除/轮转相关代码为 100%**（涉及数据安全）。

### 8.6 验证与里程碑的配对矩阵

| 里程碑 | 单元JVM | 单元Room | 契约Fake | 手工 | 性能基线 | 迁移 | 不可跳过项 |
|---|---|---|---|---|---|---|---|
| M0 | ✅ | ✅ | — | — | — | ✅ | 迁移、回填 |
| M1 | ✅ | — | — | A1/A2 | — | — | 无回归断言 |
| M2 | ✅ | — | — | A5 | — | ✅ | **排序等价性** |
| M3 | ✅ | — | ✅ | A6 | ✅ | — | 扫描次数 = 1 |
| M4 | ✅ | — | ✅ | A4/A7 | ✅ | — | **diff 真值表 + 重建==增量** |
| M5 | ✅ | ✅ | — | A3 | — | — | **主副本零误删** |
| M6 | ✅ | ✅ | — | 各自 | — | — | 篡改后校验必须失败 |

> 加粗项为**阻塞性验证**：不通过则该里程碑不算完成，禁止进入下一阶段。

---

## 9. 验收标准（A 系列手工场景）

> 每一项都在 §7 的对应里程碑里被引用为 Exit Criteria；仅 2.x 闭环的项标注"2.x only"。

| 编号 | 归属里程碑 | 场景 | 期望 |
|---|---|---|---|
| A1 | M1 | 升级 App 后进入备份列表 | 该 App 显示"有新版本"标记；勾选"仅显示已更新"后列表只剩它 |
| A2 | M1 | 点击"备份所有已更新" | 自动勾选全部 `isOutdated` 项并进入备份流程，完成后标记全部消失 |
| A3 | M5 | 设置保留副本数 = 2，连续备份 3 次 | 只保留最近 2 份；第 2 份标记"保护"后再备份，受保护副本仍在 |
| A4 | M4（2.x only） | 云端备份 100 个 App 后重进列表 | 首屏 < 1s 出缓存；后台同步完成时间较改造前下降 ≥ 80%；远端网络请求次数下降 ≥ 95% |
| A5 | M2 | 按"上次备份时间"降序排序 | 最近备份的 App 在最前，时间显示与详情页一致 |
| A6 | M3 | 断网进入云端列表 | 正常展示缓存并提示"上次同步：X 分钟前"，不报错、不白屏 |
| A7 | M4（2.x only） | 手工在远端删除一个备份目录后刷新 | 该条目被正确移除（走清单 diff，不做 N 次 `exists`） |
| A8 | M0 | 从旧版本升级 App | 老备份被回填进台账；旧结构产物仍可恢复 |

---

## 10. 风险与取舍

| 风险 | 影响 | 缓解 |
|---|---|---|
| 台账与真实文件不一致（用户手工删改远端文件） | 显示"有备份"但实际不存在 | 提供"强制重建索引"入口；恢复/删除操作做实时存在性校验并自愈台账 |
| 远端清单写入失败 / 并发写冲突 | 清单过期导致显示旧数据 | 清单 appending 时带 `generatedAt`；写入失败降级为全量扫描；读取时校验 `schemaVersion` |
| 保留策略误删用户数据 | 不可逆数据丢失 | 默认 `N=2` 且**仅轮转未保护副本**；删除前二次确认；先软删（移动到 `.trash/<timestamp>`）再异步清理 |
| Archive 后端目录结构变更破坏兼容 | 老备份无法恢复 | 读路径双分支 + 单测覆盖新老结构 |
| `preserveId` 语义被复用导致混淆 | 老代码误判"主备份" | 保持 `preserveId == 0` 表示主备份的语义不变，新副本一律用时间戳 |
| Next 的 Rustic 快照是整库粒度 | "每 App 保留 N 份"无法直接映射 | Rustic 侧改为"保留最近 N 个快照/N 天"策略，并在文档与 UI 中明确区分两种后端语义 |
| 上游已有同类改动 | 合并冲突 / 重复劳动 | 动工前检索上游 issue/PR，优先复用其字段命名 |

---

## 附录 A：涉及文件清单（改动索引）

**2.x（`source/`）**
- 新增：`core/model/.../database/BackupLedgerEntity.kt`、`core/database/.../dao/BackupLedgerDao.kt`、`core/data/.../repository/BackupLedgerRepository.kt`、`core/data/.../BackupRetention.kt`
- 修改：`core/database/.../AppDatabase.kt`、`core/model/.../App.kt:3-11`、`core/model/.../database/PackageEntity.kt:272-280`、`core/data/.../repository/ListDataRepo.kt:53-98,200-216`、`core/data/.../repository/AppsRepo.kt:471-548`、`core/data/.../repository/PackageRepository.kt:71-130,511-,847-`、`core/service/.../packages/backup/AbstractBackupService.kt:160-181`、`core/work/.../workers/AppsLoadWorker.kt`、`core/work/.../WorkManagerInitializer.kt:58-64`、`core/network/.../client/CloudClient.kt:26-32` + 4 个 `*ClientImpl.kt`、`feature/main/list/.../ListBottomSheet.kt:239-293`、`feature/main/list/.../ListActionsViewModel.kt:76-101`、`feature/main/details/.../AppDetails.kt`、`feature/main/configurations/.../IndexViewModel.kt`、`app/src/main/res/values/arrays.xml:2-6`

**Next（`source-next/`）**
- 新增：`database/entity/BackupRecord.kt`、`database/dao/BackupRecordDao.kt`、`data/BackupLedgerRepository.kt`
- 修改：`database/AppDataBase.kt:17-34`、`data/AppRepository.kt:24-33`、`data/BackupConfigRepository.kt:147-173`、`data/restore/RestoreRepository.kt:17-36`、`data/rustic/RusticBackupCoordinator.kt:19-57`、`data/rustic/RusticRestoreInventory.kt:74-149`、`data/rustic/RusticBackupGateway.kt:88-127`、`feature/backup/BackupLibraryViewModel.kt:52-81`、`feature/backup/apps/AppsViewModel.kt:50-67`、`feature/restore/apps/AppsViewModel.kt:48-62`、`util/SortsAndFiltersHelper.kt:44-74`、`util/DataStoreHelper.kt:14-24,57-81`、`util/PathHelper.kt:36-41`、`ui/component/selection/AppFilterSheetContent.kt:88-186`、`service/util/BackupAppsHelper.kt`

## 附录 B：竞品参考

- Swift Backup — 官网功能列表（14 种云存储 / App Labels / Scheduled backups / Custom backup configuration）：<https://www.swiftapps.org/>；"保存应用备份的旧版本"说明：<https://www.appinn.com/swift-backup-for-android/>
- Neo Backup（OAndBackupX 重写）— AES-256 加密、批量备份/恢复、不限数量计划任务、自定义应用列表、推荐 Syncthing/Nextcloud 同步：<https://github.com/NeoApplications/Neo-Backup>
- Titanium Backup — `Max backup history`（每 App 保留 N 个版本，PRO）、"同版本 APK 只存一份"、`Min days` 批量条件、Filters/Labels、Scheduled backups、Backup Verification：<https://www.titaniumtrack.com/kb/titanium-backup-kb/titanium-backup-user-guide.html>
- 上游项目 — `XayahSuSuSu/Android-DataBackup`（2.x 与 `[Next]` 分支）

---

## 附录 C：实施记录（2025-09，2.x 轨道）

本轮实施聚焦 `source/`（2.x）轨道，F1/F3/F4 全量落地、F2 以简化方案落地，全部通过单元测试（20 用例）与增量编译验证，并产出 foss/arm64-v8a APK。与原规划的差异与转向如下。

### C.1 设计转向：派生式台账（替代 M0 独立台账表）

原规划 M0 新增 `BackupLedgerEntity` 独立表。实施中确认 **RESTORE 实体本身就是天然台账**：每个副本（含 preserveId=0 的主备份）一条记录，`packageInfo` 是备份时刻的版本快照、`extraInfo.lastBackupTime` 是备份时间、preserveId 即副本标识。因此：

- 不新增表、不发生数据库迁移、无双写一致性问题；
- 新增 `BackupIndex(lastBackupTime, backedUpVersionCode, copyCount)` 数据类，由 `AppsRepo.getBackupIndexes()` 在 Repo 层对同作用域（cloud + backupDir）RESTORE 实体**聚合派生**；
- `PackageEntity.toAppWithStatus(index)` 完成状态合并映射（保持 `asExternalModel()` 纯函数不变）。

该转向同样适用于 Next 轨道（`RusticRestoreInventory` 的 manifest 已含 versionCode，可直接聚合）。

### C.2 F1 版本变更检测（痛点 1）落地

- `App` 模型增 `lastBackupTime / backedUpVersionCode / copyCount / isOutdated`；
- `AppsRepo.getApps()` 计算 outdatedSet（当前 versionCode > 台账记录），`PackageRepository.getUpdatedPredicate(value, outdatedSet)` 谓词与既有风格一致；
- `ListBottomSheet` 增"仅显示有新版本"筛选入口（value=false 时生效，与既有谓词约定一致）。
- 未实施（后续项）：按天过期（staleDays）、"备份所有已更新的应用"批量动作。

### C.3 F4 排序/筛选（痛点 4）落地

- `getSortComparatorNew` 增 `sortIndex=3` → `sortByLastBackupTimeNew`（升/降序），未知键兜底字母序；
- 排序 UI（arrays.xml）增"上次备份时间"选项。
- 未实施（后续项）：安装时间以外的更多维度（大小排序已有）。

### C.4 F3 云端清单 + 增量同步（痛点 3）落地 — 设计简化

原规划三层（远端清单 + mtime/etag 版本向量 + CloudClient.stat 扩展）。实施简化为**完整实体清单**：

- 清单位置：`<remote>/apps_index.json`（原规划 `configs/index.json.zst`；未压缩，与 config.json 同为 Gson 格式，人类可读）；
- `CloudIndexManifest(schemaVersion, generatedAt, packages)`，**packages 直接嵌入 RESTORE 形态的完整 `PackageEntity`**——一次下载即可完整重建本地列表，免去逐 app 下载 config 的 O(n) 次调用，也无需扩展 CloudClient 接口（stat/listFilesDetailed 未实施）；
- 增量路径 `loadCloudAppsByIndex`：已存在条目按清单更新（保留本机选中状态，跨设备刷新可见最新备份）；**批量结算改为本地 diff**（DB 条目 vs 清单条目按 `(packageName, userId, preserveId, compressionType)` 对比），免去逐条远程 `exists()`；
- 降级自愈：清单缺失/损坏/`schemaVersion` 高于本机时回退全量 `walkFileTree`，结束后回补清单；
- 清单维护点：云备份收尾（`onIndexManifestSaved` 挂钩）、全量同步自愈、删除（`deleteCloudApp` / `deleteSelected` 按账号合并一次）、保护（`protectCloudApp`）；
- 首屏渲染仍以 `PackageDao` 本地缓存为数据源（原 L3 设计），`WorkManager` KEEP 策略未改动。

**行为测试发现**：全默认参数的 data class 会生成 Kotlin 无参构造器，Gson 对缺失字段回退默认值而非 null；显式 `"packages": null` 才产生 null（调用方已判空降级）。

### C.5 F2 多副本保留（痛点 2）落地 — 日志式轮转

采用 log rotation 模式（对标 Titanium `Max backup history` / Neo Backup `backups to keep`）：

- DataStore 新增 `backup_retain_copies`（默认 1 = 现状覆盖式备份，完全向后兼容）；
- 备份设置页新增滑条（1~10）；
- `AbstractBackupService.rotateCopies()`：备份前先清理超出保留数的最旧副本（按 lastBackupTime LRU），再将现有主备份轮转为时间戳副本（preserveId=timestamp），随后本次备份产出新主备份；
- 本地/云端差异经 `onRotateCopy` / `onDeleteCopy` 挂钩实现（云端先上传新 config 再 rename，与 `protectCloudApp` 同模式）；
- DB 侧「删除旧行 + 插入新行」避免与稍后新主备份 upsert 的主键冲突；
- 轮转失败静默降级为覆盖式备份（不阻断任务）。

未实施（后续项）：按天过期清理、`hasProtectedCopy` 徽标、每 App 独立保留数。

### C.6 验证结果

| 验证项 | 方式 | 结果 |
|---|---|---|
| 版本判定语义 | `BackupStatusTest`（core:model，6 用例） | 通过 |
| 清单序列化/降级前提 | `CloudIndexManifestTest`（core:model，6 用例） | 通过 |
| 筛选谓词与排序 | `PackageRepositoryPredicatesTest`（core:data，mockk，8 用例） | 通过 |
| 轮转/清单维护点 | 挂钩结构 + 编译验证 + 手工场景（A 系列）待真机 | 编译通过 |
| APK | `assembleArm64-v8aFossDebug` | 产出 |

测试基建：core:data 启用 `library.test` 插件并引入 mockk 1.13.13（`testImplementation`）。

### C.7 实际改动文件（2.x）

- 新增：`core/model/.../CloudIndex.kt`、`core/model/src/test/.../database/BackupStatusTest.kt`、`core/model/src/test/.../CloudIndexManifestTest.kt`、`core/data/src/test/.../repository/PackageRepositoryPredicatesTest.kt`
- 修改：`core/model/.../App.kt`、`core/model/.../database/PackageEntity.kt`（BackupIndex + toAppWithStatus）、`core/data/.../repository/AppsRepo.kt`（台账聚合/清单增量/维护点）、`core/data/.../repository/PackageRepository.kt`（谓词 + 排序）、`core/datastore/.../Int.kt`、`core/service/.../backup/AbstractBackupService.kt`（轮转 + 清单挂钩）、`core/service/.../backup/BackupServiceLocalImpl.kt`、`core/service/.../backup/BackupServiceCloudImpl.kt`、`core/util/.../PathUtil.kt`、`feature/main/list/.../ListBottomSheet.kt`、`feature/main/list/.../ListItems.kt`、`feature/main/settings/.../backup/Index.kt`、`feature/main/settings/src/main/res/values/ids.xml`、`app/src/main/res/values/strings.xml`、`app/src/main/res/values-zh-rCN/strings.xml`、`core/data/build.gradle.kts`、`gradle/libs.versions.toml`

### C.8 构建环境备忘（内存受限沙箱）

- 4GB cgroup 限额下，Gradle 守护进程（-Xmx2560m）与 Kotlin 编译守护进程共存会在 dex 阶段触发 OOM 被杀（"daemon disappeared"）。规避：`--stop` 后单守护进程重启，全部编译任务命中缓存，仅重跑 dex/打包；
- 验证通过的约束构建命令：`./gradlew assembleArm64-v8aFossDebug -Dorg.gradle.jvmargs="-Xmx2560m -XX:MaxMetaspaceSize=512m -XX:+UseParallelGC" -Dorg.gradle.workers.max=2 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.jvmargs=-Xmx800m`；
- 产物：`app/build/outputs/apk/arm64-v8aFoss/debug/DataBackup-2.0.12-arm64-v8a-foss-debug.apk`（35MB，md5 830c4a9e63e8dfb63491289462bec9a5）；
- 沙箱内依赖下载所需的 `gradle.properties` 代理配置已回退，未纳入提交；`.kotlin/` 会话目录已加入 `source/.gitignore`。

## 附录 D：第一轮实测反馈修复（2025-09）

用户真机测试 APK 后反馈四项问题，本轮逐一闭环。

### D.1 Fix1 「有更新」筛选语义反转

**现象**：勾选"有更新"反而显示无新版的应用，不勾选才显示有新版的。

**根因**：谓词写成了 `value || p.pkgUserKey in outdatedSet`（勾选=全通过、未勾选=仅待更新），与直觉相反。

**修复**：`PackageRepository.getUpdatedPredicate` 改为 `value.not() || p.pkgUserKey in outdatedSet`，勾选时仅保留待更新集合；默认值由勾选改为未勾选（`Filters.updatedApps = false`），避免首次进入即过滤。文案按操作拆分：备份页=「本机有新版本的应用」（本机 versionCode > 备份版本），恢复页=「云端有新版本的应用」。

### D.2 Fix2 恢复侧「云端有更新」筛选

**设计**：版本基线从 `Set<pkgUserKey>` 升级为 `Map<pkgUserKey, versionCode>`（`ListDataRepo.pkgUserVersions`）：

- 备份页基线 = 台账最近备份版本（`getBackups()`）；
- 恢复页基线 = 本机已安装版本（新增 `getInstalledVersions()`，未安装为 0）；
- `AppsRepo.getBackupIndexes(copies, baselineVersions)` 聚合台账时按场景取基线，恢复页传入本机版本 Map，云端 versionCode > 本机即"云端有更新"。

**UI**：恢复侧筛选面板新增 CheckBox，与备份侧共用 `Filters.updatedApps` 状态位。

### D.3 Fix3 详情页对侧版本展示

新增 `AppsRepo.getAppCounterpart(id)`：备份页返回最近一次备份实体（RESTORE 台账按 lastBackupTime 取最新），恢复页返回本机已安装版本（复制实体改写 versionName/versionCode，未安装为 null）。`DetailsViewModel` 通过 `combine` 并入 `Success.App.counterpart`，详情页 Info 区在版本行下追加「备份版本」（备份页）/「本机版本」（恢复页）。

### D.4 Fix4 云端详情大小为 0 + 列表加载优化

**现象**：恢复页详情各子项大小 0 Bytes；备份页正常但需现场计算。

**根因**：云端归档不在本地，`calculateLocalAppArchiveSize` 现场计算本地路径恒为 0。

**修复**（两层）：

- 备份时 `displayStats` 已随实体写入云端 config 与 `apps_index.json` 清单（Fix4 前置已落盘）；
- `calculateLocalAppArchiveSize` / `calculateLocalFileArchiveSize` 对云端实体（`indexInfo.cloud.isNotEmpty()`）直接跳过现场计算，保留清单预置统计——获取云端列表时一次下载即全量就绪，详情页不再现场刷新。

### D.5 验证

| 验证项 | 方式 | 结果 |
|---|---|---|
| 谓词语义反转 | `PackageRepositoryPredicatesTest` 用例改写（勾选=仅待更新/未勾选=全通过） | 通过 |
| 基线聚合（备份/恢复双场景） | 新增 `AppsRepoBackupIndexTest`（4 用例：备份基线、恢复基线、未安装 0、空集） | 通过 |
| 清单序列化/状态聚合 | `CloudIndexManifestTest` + `BackupStatusTest`（回归） | 通过 |
| APK | `assembleArm64-v8aFossDebug` | 产出 |

### D.6 本轮改动文件

- 修改：`core/data/.../repository/AppsRepo.kt`（counterpart 可空修复）、`core/data/.../repository/PackageRepository.kt`、`core/data/.../repository/ListDataRepo.kt`、`core/data/.../repository/FilesRepo.kt`、`feature/main/details/.../DetailsViewModel.kt`、`feature/main/details/.../AppDetails.kt`、`feature/main/details/src/main/res/values/ids.xml`、`feature/main/list/.../ListBottomSheet.kt`、`feature/main/list/src/main/res/values/ids.xml`、`app/src/main/res/values/strings.xml`、`app/src/main/res/values-zh-rCN/strings.xml`
- 新增测试：`core/data/src/test/.../repository/AppsRepoBackupIndexTest.kt`

### D.7 交付与构建复盘（2025-09-16 收尾）

**构建环境经验**（对 C.8 的修订）：

- 沙箱重置后默认 Java 11，需先 `apt-get install -y openjdk-17-jdk-headless` 并 `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`；
- C.8 的 `-Xmx2560m` 配置在本轮两次触发 daemon OOM（"daemon disappeared"）；实测更稳的组合：`-Xmx2048m -XX:MaxMetaspaceSize=512m -XX:+UseParallelGC -Dorg.gradle.workers.max=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.jvmargs=-Xmx800m`（1m17s 完成打包，命中缓存）；
- OOM 失败后必有约 1.5GB 残留 Java 进程，重试前先 `pkill -9 -f gradle` 释放，否则必然连续失败。

**交付物清单**（/workspace）：

| 交付物 | 说明 |
|---|---|
| `apk/DataBackup-2.0.12-arm64-v8a-foss-debug.apk` | 含全部四项修复的测试 APK（md5 e192b1e67a0e314e39d7e60105df32f3，36MB） |
| `DataBackupNext-repo/` | 完整 git 仓库（浅克隆，含最新提交 c696678，remote 已指向 GitHub；submodule 引用在册，实体需 `git submodule update --init`） |
| `backup-enhancement-proposal.md` | 本提案（含附录 A-D 实施记录） |

**遗留事项**：

- 本地提交已完成（c696678），沙箱内无 GitHub 凭证无法 push——repo 交付物即为待推送的完整仓库，用户侧执行 `git push origin main` 即可（或将 repo 目录替换远端后推送）；
- Kotlin `getAppCounterpart` 首版遗漏 Room 可空处理（编译期暴露），已修复——Room `queryPackageFlow` 返回 `Flow<PackageEntity?>`，map 链中必须显式判空，后续新增同类 Flow 注意；
- 测试基线：core:data 12 用例（谓词 8 + 基线聚合 4）+ core:model 12 用例（状态 6 + 清单 5 + 1），共 24 例全绿。

## 附录 E：未来开发计划（Roadmap）

以下为基于现有架构梳理的实用性与易用性增强方向，按"落地成本-收益"排序，均为独立可实施项，依托本轮已落地的台账（`BackupIndex`）、版本基线（`pkgUserVersions`）与云端清单（`apps_index.json`）能力。

### E.1 备份提醒角标（P0，推荐首批）✅ 已实施（附录 F.1，2025-09-16）

**价值**：形成"检测 → 提醒 → 备份"闭环，放大本轮"有更新"检测的核心价值。

**方案**：首页（或列表页标题栏）展示"N 个应用有新版本待备份"角标。数据源直接复用 `AppsRepo.getApps` 流中的 `outdatedSet` 计数，无新增查询；`ListViewModel` 侧新增一个 `countOutdated` StateFlow 即可。可进一步接入通知栏提醒（每日一次，WorkManager 周期任务）。

**依托**：`outdatedSet` 现成；筛选机制（`Filters.updatedApps`）现成。

### E.2 一键全选有更新（P0，推荐首批）❌ 经用户决策不实施

**价值**：与 E.1 组合即为"增量备份"快捷入口——筛选出待更新应用后一键全选执行备份，减少逐个勾选。

**决策记录**（2025-09-16）：现有"筛选有更新 + 全选"已覆盖该场景，单独入口属重复功能，经用户确认不做。

**方案**：列表页多选模式（长按进入）增加快捷 Chip"选中所有有更新的应用"，实现上对当前过滤后的 `appList` 中 `isOutdated == true` 的条目批量 `setDataItems(activated = true)`。

**依托**：`App.isOutdated` 派生字段已随列表下发；批量激活机制（`appsDao` upsert）现成。

### E.3 副本浏览与定点恢复（P1）✅ 已实施（附录 F.2，2025-09-16）

**价值**：多副本保留（F2）已落地但 UI 不可见——数据在库里，用户却无法选择"从哪个时间点恢复"。

**方案**：恢复页详情（或列表项长按菜单）增加"历史副本"入口，列出该应用同 `pkgUserKey` 下所有副本（`preserveId != 0` 的时间戳副本 + 主备份），展示备份时间与版本，选择后以该副本实体发起恢复。

**依托**：轮转副本实体已在库（`rotateCopies` 产出）；恢复服务按实体执行，无需改造。

### E.4 筛选条件持久化（P1）✅ 已实施（附录 F.3，2025-09-16）

**价值**：当前筛选退出即重置（`Filters` 每次初始化为默认值），高频用户每次都要重新勾选。

**方案**：`Filters` 关键字段（`updatedApps`、`hasBackups`、`installedApps` 等）写入 DataStore，`ListDataRepo` 初始化时读取。项目已有 `saveLoadSystemApps` 同类模式可复用。

**依托**：DataStore 读写模式现成。

### E.5 云端空间统计（P2）❌ 经用户决策不实施

**价值**：回答"云端被什么占了"——方便用户清理低价值备份。

**决策记录**（2025-09-16）：经用户确认本轮不实施（"云端空间统计"指云端配置维度，暂无诉求）。

**方案**：云备份管理页增加占用视图：按应用聚合 `displayStats` 总量排行（清单已预置），展示 TopN 与总占用；可选按时间清理建议（如"超过 90 天未更新的副本"）。

**依托**：`apps_index.json` 清单携带 `displayStats`；聚合纯本地计算。

### E.6 行内版本差徽标（P2）✅ 已实施（附录 F.4，2025-09-16）

**价值**：比单独的 Update 图标更直观，一眼看出"从哪版到哪版"。

**方案**：列表项 `AppItem` 在 `isOutdated` 图标旁展示 `v2.0 → v1.0`（本机版本 → 备份版本，恢复页反向）微缩文本；`App` 模型补充 `backedUpVersionName` 字段（台账聚合时一并取出）。

**依托**：`BackupIndex.backedUpVersionCode` 已有，需补 versionName 冗余。

### E.7 实施批次建议

| 批次 | 内容 | 理由 | 状态 |
|---|---|---|---|
| 第一批 | E.1 + E.2 | 直接放大"有更新"检测价值，形成增量备份闭环 | E.1 已实施（F.1）；E.2 经用户决策不做 |
| 第二批 | E.3 + E.4 | 补齐多副本可见性与操作记忆，中等工作量 | 均已实施（F.2/F.3） |
| 第三批 | E.5 + E.6 | 体验打磨项，独立无依赖 | E.6 已实施（F.4）；E.5 经用户决策不做 |

## 附录 F：第二轮增强实施（2025-09-16）

第二轮按用户指令实施 E.1 / E.3 / E.4 / E.6 四项（E.2、E.5 经用户决策不实施），全部依托既有台账与版本基线能力，无新增数据库表。

### F.1 备份提醒角标（E.1 落地）

- **数据链路**：`AppsRepo.countOutdatedBackupApps(cloudName, backupDir)` 聚合 RESTORE 实体派生台账后统计"本机版本高于备份版本"的应用数；`ListDataRepo.outdatedCount` 仅在备份页接流，恢复页置 `flowOf(0L)` 避免无效计算；
- **状态下发**：`ListData.Apps` 新增 `outdatedCount` 字段随 `combine` 下发；`ListTopBar` 副标题追加"N 个应用有更新"文案（`format_x_apps_have_updates`）；
- **前置改造**：`getAppListData` 的 combine 流从 13 个增至 14 个，为此在 `core/util/.../FlowUtil.kt` 按既有 Triple 分组模式追加 T14 重载（见 F.7 踩坑记录）。

### F.2 副本浏览与定点恢复（E.3 落地）

- **副本查询**：`AppsRepo.getAppCopies(id)` 取同 `pkgUserKey`、同云端作用域（cloud + backupDir）的全部 RESTORE 实体，经 `selectCopiesOf` 扩展函数过滤并按 `lastBackupTime` 倒序；
- **定点激活**：`AppsRepo.restoreFromCopy(app, copyId)` 先批量反选同组副本再激活目标副本，保证恢复队列中同组仅一个副本生效；
- **UI**：`AppDetails` 新增 HistoryCopies 区块，仅恢复页且副本数 > 1 时展示；每行显示备份时间与版本名，已激活副本以勾选图标标识，点击即切换定点恢复目标（当前实体行标注"当前副本"）。

### F.3 筛选条件持久化（E.4 落地）

- **存储**：DataStore 新增 6 个布尔键，按操作类型分键（`filter_backup_*` / `filter_restore_*`），默认值与既有 UI 初值一致（`updatedApps` 默认 false）；
- **读写**：`ListDataRepo.initialize` 经 `runBlocking { ...first() }` 读取恢复（对齐 `getLoadSystemApps` 模式）；`setFilters` 内按 `appsOpType` 分键写回；
- **边界**：`showSystemApps` 沿用既有全局键不参与分键；云端/目录作用域参数不持久化（跟随导航上下文）。

### F.4 行内版本差徽标（E.6 落地）

- **模型**：`App` 新增 `versionName`（实体侧）与 `backedUpVersionName`（台账对侧），`BackupIndex` 同步冗余 `backedUpVersionName`；
- **文案**：`App.buildVersionTransition(opType, app)` 统一生成"本机 → 备份"（备份页）/"云端 → 本机"（恢复页）文本，非待更新或任一侧版本名缺失时返回 null 降级为仅图标；
- **UI**：`ListItems` 的 AppItem 在 Update 图标旁以 `LabelSmallText` 展示微缩版本差文本。

### F.5 验证

- 单元测试：全模块 29 用例全绿（新增 5 例：`VersionTransitionTest` 4 例覆盖双向文本与非降级路径、`AppsRepoBackupIndexTest` 新增同键副本过滤排序 1 例）；
- Lint：`core:model` / `core:data` / `core:datastore` / `core:util` / `feature:main:details` / `feature:main:list` / `app:lintArm64-v8aFossDebug` 全部通过；
- 编译：`assembleArm64-v8aFossDebug` BUILD SUCCESSFUL，36MB。

### F.6 本轮改动文件

- 修改：`core/model/.../App.kt`、`core/model/database/PackageEntity.kt`、`core/data/.../AppsRepo.kt`、`core/data/.../ListDataRepo.kt`、`core/datastore/.../Boolean.kt`、`core/util/.../FlowUtil.kt`、`feature/main/details/.../AppDetails.kt`、`DetailsScreen.kt`、`DetailsViewModel.kt`、`feature/main/details/src/main/res/values/ids.xml`、`feature/main/list/.../ListItems.kt`、`ListTopBar.kt`、`ListTopBarViewModel.kt`、`feature/main/list/src/main/res/values/ids.xml`、`app/src/main/res/values/strings.xml`、`app/src/main/res/values-zh-rCN/strings.xml`
- 新增测试：`core/model/src/test/.../VersionTransitionTest.kt`；扩充：`AppsRepoBackupIndexTest.kt`

### F.7 复盘与踩坑记录（2025-09-16 第二轮）

- **combine 参数上限**：`FlowUtil.kt` 自定义 combine 最高 13 参数，新增流超出后 Kotlin 静默解析到 kotlinx 的 vararg 重载并报"Cannot infer type"——应在 FlowUtil 按既有 Triple 分组模式扩展对应参数数的重载，而非改用嵌套 combine；
- **DataStore 单值读取**：`readStoreBoolean` 返回 `Flow<Boolean>`，`runBlocking` 内直接调用得到的是 Flow 对象而非值，必须 `.first()`（对齐 `getLoadSystemApps` 既有模式）；
- **Kotlin 编译守护进程残留**：lint/编译多次 "daemon disappeared" 的直接诱因是两个残留 `KotlinCompileDaemon` 进程合计占用约 2GB——对 D.7 经验的补充：除了 `pkill -9 -f gradle`，还需 `pkill -9 -f KotlinCompileDaemon`，释放后可用内存从 2.6G 恢复至 4.6G，lint 即可稳定通过；
- **Lint 依赖需联网**：`--offline` 模式无 `lint-gradle` 缓存，需临时在 `gradle.properties` 写入 `systemProp.http(s).proxyHost/Port` 走沙箱代理拉取，完成后必须回退（勿提交代理配置）；
- **内存受限下的 Lint 策略**：`jvmargs` 临时降至 1536m、`org.gradle.parallel` 临时关闭、`-Dorg.gradle.workers.max=1`、按模块分批执行，可在 5.8G 无 Swap 沙箱内完成全模块 lint。

### F.8 交付与遗留事项（2025-09-16 第二轮收尾）

**交付物清单**（/workspace）：

| 交付物 | 说明 |
|---|---|
| `apk/DataBackup-2.0.12-arm64-v8a-foss-debug.apk` | 含第二轮四项增强的测试 APK（md5 833a7c513fa9eb363a0d71d8f3379ebc，36MB） |
| `DataBackupNext-repo/` | 完整 git 仓库（最新提交 5a3af27b，remote 已指向 GitHub） |
| `backup-enhancement-proposal.md` | 本提案（含附录 A-F 实施记录） |

**遗留事项**：

- ~~沙箱内无 GitHub 凭证，push 无法执行~~ 已解决（2026-09-16）：用户提供细粒度 PAT 后完成推送，远端 `main` 已更新至 9a300e71（token 仅在推送命令中内联使用，未持久化到任何配置或文件）；
- 测试基线更新：core:data 13 用例（谓词 8 + 基线聚合 4 + 副本筛选 1）+ core:model 16 用例（状态 6 + 清单 5 + 版本差 4 + 1），共 29 例全绿。

## 附录 G：第三轮增强实施 — 备份/恢复统一页（2026-09-22）

### G.1 需求

- 备份应用详情页也要显示应用的上次备份时间；
- 将备份、恢复两个应用页面整合为统一页面，筛选后再选择「备份 / 恢复 / 删除备份 / 卸载应用」等操作；
- 筛选增加「上次备份超过 X 天」条件（默认 30，可调，持久化）。

### G.2 设计决策

| 议题 | 决策 | 说明 |
|---|---|---|
| 统一页形态 | 单列表 + 顶部「备份/恢复」分段切换 | 复用同一列表与选择逻辑，作用域（Scope）驱动重建 |
| 卸载实现 | 根服务 `pm uninstall --user` | `uninstallPackageAsUser`，仅非系统应用，批量卸载以退出码判定成败 |
| 批量操作 | 在既有选择机制上扩充 | 删除备份按当前模式的实体类型自动映射（BACKUP 用包名↔备份台账，RESTORE 直接删实体） |
| 默认天数 | 30 天（0 = 关闭该筛选，可持久化） | 未备份应用视为超期以纳入管理 |

### G.3 关键落地

- `ListDataRepo` 引入作用域 `ScopeState`（target/opType/cloud/backupDir），`switchMode()` 重发布作用域，各列表 VM 经 `flatMapLatest` 自动重建；
- `ListTopBar` 新增 `SingleChoiceSegmentedButtonRow` 分段切换「备份 ↔ 恢复」；
- 批量操作整合：`ListActions` 新增「删除备份」「卸载应用」（二次确认，卸载时跳过系统应用）；
- 筛选：`Filters.lastBackupDays` + `PackageRepository.getLastBackupOlderThanPredicate`，经 DataStore 持久化（默认 30）；
- 详情页：备份视图沿用对侧（counterpart 台账）的 `lastBackupTime` 展示「上次备份时间」，恢复视图直接用归档实体时间；
- 字符串：新文案经 `feature/main/list/src/main/res/values/ids.xml` 以 `<item type="string"/>` 声明 ID，实际文案由 app 模块提供。

### G.4 测试

- `PackageRepositoryPredicatesTest` 新增 5 项 `getLastBackupOlderThanPredicate` 用例（关闭、未备份、无索引、超阈值、未超阈值），全绿（该文件共 13 例）；
- `AppsRepoBackupIndexTest` 5 例全绿。

### G.5 本轮改动文件

根服务（AIDL/Impl/Wrapper 新增卸载）、`AppsRepo`/`ListDataRepo`/`PackageRepository`（统一页 + 过滤器）、`datastore/Int.kt`（天数持久化）、list 模块（TopBar 分段、Actions 批量操作、BottomSheet 筛选、各 VM flatMapLatest）、details 模块（上次备份时间）、`ids.xml`（字符串 ID 声明）、app `strings.xml`（文案）、单测。

### G.6 构建环境备忘（关键踩坑，已固化）

- **出站代理**：沙箱内部直连 `dl.google.com`/Maven 超时，需经本地代理 `127.0.0.1:18080`。构建时在 `gradle.properties` 临时注入 `systemProp.*proxy*`（提交前回退，避免代理信息入库）。
- **内存上限**：cgroup 内存上限 4GB，`-Xmx4096m` 的 daemon + kotlin daemon + lint worker 并行会触发 OOM 被杀。已将 `org.gradle.jvmargs` 调至 `-Xmx2048m`、`kotlin.daemon.jvmargs=-Xmx1024m`、`workers.max=2`（构建后回退）。
- 单测通过（core:data 13 + 5 新增谓词用例）、受影响模块 `compileDebugKotlin` 通过（仅弃用/opt-in 警告）。

### G.7 交付与提示

交付物见 `/workspace` 与 `apk/`；本附录记录了统一页的形态与裁决，便于后续直接复用方法论。
