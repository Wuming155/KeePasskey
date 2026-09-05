# WAVE2-C 启动提示词（同步引擎三哈希状态机与墓碑合并）

> 本文件为代理提示词存档，由主会话在 Wave 1 验收通过后复制给子代理执行。目标：P0-5（引擎部分）、P1-9、P2-16。

你是资深同步引擎工程师，为密码管理器 KeePasskey 实现「云同步引擎：三哈希缓存状态机 + 墓碑感知三方合并 + WebDAV/S3 协议加固」（REMEDIATION_PLAN.md 的 Wave 2-C）。

## 项目背景
- 项目根目录：D:\GithubWorkplace\KeePasskey（Windows），Gradle 9.3.1（`.\gradlew.bat`）。
- 开工前必读（相对项目根）：
  1. `AGENTS.md`、`.codebuddy/rules/engineering-rules.md`
  2. `REMEDIATION_PLAN.md`（问题 P0-5/P1-9/P2-16；第四节契约你的部分与「sync 禁止依赖 database」硬约束；第六节 S3/WebDAV 风险决策）
  3. `.codebuddy/skills/references/keepass2android-架构分析.md` 第 5.4 节（CachingFileStorage 三哈希状态机——磁盘布局、打开决策树、写入语义、离线开关、ICacheSupervisor 六事件）与 9.2 节（对 KeePasskey 的九条建议）
  4. `.codebuddy/skills/references/KeePass-2.61.1-架构分析.md` 第 3.2 节（PwDeletedObject 墓碑）与 keepass2android 分析第 5.5 节（同步决策表）
  5. 现有代码：`sync/src/main/java/com/keepasskey/sync/**`（provider/model/webdav/s3/merge 五包）、`sync/src/test/**`、core 的 `KdbxEntry`/`KdbxTimes`（Wave1-A 已交付 `core/model/DeletedObject.kt`，先读实际签名）
- 【铁律】严禁读取/扫描 `参考项目/` 源码树；严禁复制参考代码；严禁 git 命令。

## 文件所有权（并行约束：另一代理正在改 app/ 模块）
- 允许修改：`sync/src/**`（main+test）、`sync/build.gradle.kts`（仅追加依赖）。
- 禁止修改：app/、database/、crypto/、core/ 下任何文件（core 只读消费）。
- 【硬约束】sync 不依赖 database；若需 kdbx 语义（如合并），只用 core 模型。

## 任务清单（全部必做）

### C1 SyncEngine：纯字节级三哈希状态机（新包 sync.engine）
参考 Kp2a CachingFileStorage 的决策树，实现本地缓存优先的同步状态机（纯 Kotlin，可注入 fake provider 单测）：
- `class SyncCache(private val cacheDir: File)`：磁盘布局以 SHA-256(iocPath) 为键：`<hash>.cache`（内容）、`<hash>.version`（本地版本=内容 SHA-256）、`<hash>.baseversion`（基准版本）、`<hash>.meta`（JSON：remotePath/etag/lastSyncMillis）。方法：readCache/isCached/hasLocalChanges/writeCache(clears base 比对)/updateBase/getState。
- `class SyncEngine(private val provider: SyncProvider, private val cache: SyncCache)`：
  - `suspend fun openRemote(): SyncOpenResult`（sealed：RemoteSynced / ConflictDetected(localBytes, remoteBytes, remoteEtag) / LocalWinAutoUploaded / RemoteLostRestored / CacheHitOffline / RemoteUnreachableUsingCache）
    决策树：未缓存→下载+写缓存+基线=远端 ETag；已缓存且本地==base（无修改）→重新下载刷新缓存（RemoteSynced）；本地有修改且 base==远端 ETag→自动上传（LocalWinAutoUploaded，基线前移）；本地有修改且 base!=远端→ConflictDetected；远端 404 且有缓存→RemoteLostRestored（上传恢复）；网络错误且有缓存→RemoteUnreachableUsingCache。
  - `suspend fun commitLocal(localBytes: ByteArray): SyncCommitResult`（sealed：Uploaded(newEtag) / ConflictNeedsMerge(remoteBytes, remoteEtag) / RemoteUnreachable(keptLocal)）——先写缓存（本地安全第一），再尽力上传；上传 412/ETag 不匹配→ConflictNeedsMerge；失败保留缓存。
  - `suspend fun markResolvedAndUpload(mergedBytes: ByteArray)`（合并后提交，基线前移）。
  - `val events: SharedFlow<SyncCacheEvent>`（对齐 Kp2a ICacheSupervisor 六事件语义：UpdatedCachedFileOnLoad/UpdatedRemoteFileOnLoad/OpenedFromLocalDueToConflict/LoadedFromRemoteInSync/CouldntSaveToRemote/CouldntOpenFromRemote）。
  - 远端 ETag 判等注意 WebDAV/S3 ETag 引号差异（复用 cleanEtag 语义，可把该工具提为 sync 内共享函数）。
- 全部 suspend + withContext(Dispatchers.IO)；无 Android 依赖。

### C2 KdbxMerger v2：墓碑感知三方合并（重写 sync/merge/KdbxMerger.kt，保留旧公开 API 或提供迁移封装以兼容既有测试语义）
- 新 API：`fun mergeDatabases(base: KdbxDatabaseLite, local: KdbxDatabaseLite, remote: KdbxDatabaseLite): MergeResult`，其中 `KdbxDatabaseLite(rootGroup: KdbxGroup, deletedObjects: List<DeletedObject>)`（sync 内定义的轻量聚合类型，字段来自 core 模型；因 sync 不能依赖 database，由 app 层负责从 KdbxDatabase 抽取填充）。
- 算法（KeePass 三方合并语义，参考官方 PwDatabase.MergeIn 的行为描述）：
  1. 以 UUID 索引 base/local/remote 三方条目与分组；
  2. 一方删除（在 deletedObjects 且对端不存在该对象）→ 结果删除并记录墓碑；**删除 vs 修改冲突 → 修改方胜**（KeePass 默认 KEEP_MODIFIED）并从墓碑移除；**删除后对端重建（同 UUID 重新出现且修改时间晚于墓碑时间）→ 保留新版**；
  3. 仅一方修改（与 base 字段级比对）→ 采纳修改方；
  4. 双方修改不同字段 → 字段级合并；同字段不同值 → 冲突清单（保留 ConflictedEntryPair/modifiedFields 既有语义，供 UI 决策）；同值 → 任取；
  5. 分组结构变更（移动/改名）同条目逻辑（按 LastModificationTime/LocationChanged 裁决）；
  6. 墓碑列表合并去重（同 UUID 取更晚删除时间）。
- `MergeResult(mergedRoot: KdbxGroup, mergedDeletedObjects: List<DeletedObject>, conflicts: List<ConflictedEntryPair>)`。
- 保留/迁移旧 `detectConflictsAndMergeAuto` 与 `resolveConflict`（既有 app 无调用方、测试有引用——按新实现提供等价语义或更新测试）。

### C3 WebDAV 加固（P2-16）
- PROPFIND 解析改用 `XmlPullParser`（Android library 模块可用）替换正则（保留既有公开 API 不变）。
- 事务写：upload 增加可选「temp+MOVE」路径——`upload(..., useTransaction: Boolean = false)` 或新方法 `uploadAtomic`：PUT 到 `<path>.kpktmp` → MOVE（Destination 头，Overwrite: T）→ 失败按状态码重试一次 → 仍失败清理临时对象。常量化临时后缀。
- 对象键 URL 编码：buildUrl 对 remotePath 做 RFC 3986 未保留字符检查，含特殊字符（空格/中文/# 等）时逐段 URLEncoder.encode(UTF-8)（保留 '/'）。

### C4 S3 加固（P2-16）
- upload 首传（expectedEtag==null 且远端不存在）→ 请求头 `If-None-Match: *` 实现原子创建；已存在对象更新保留 HEAD+ETag 预检（TOCTOU 限界写 KDoc）。
- 对象键 URL 编码同 C3（S3 键允许任意 UTF-8，路径段编码）。
- SigV4 canonicalUri 与实际请求 URL 必须使用同一编码结果（编码后再签名）。

### C5 测试（sync/src/test，MockWebServer + 内存 fake）
- SyncEngine：状态机全路径（首次下载/无修改刷新/本地赢自动上传/双方修改冲突/远端丢失恢复/离线回退）+ 六事件触发断言 + commitLocal 的 412→ConflictNeedsMerge；
- KdbxMerger v2：删除传播/删除vs修改/删除后重建/字段级合并/同字段冲突/墓碑合并去重 各至少 1 用例；
- WebDAV：XmlPullParser 解析多命名空间 PROPFIND（d:/D: 前缀）、temp+MOVE 事务成功/失败回滚、URL 编码；
- S3：If-None-Match 首传、编码对象键签名一致性（对签名 canonicalUri 断言）。
- 既有测试保持全绿（可按新语义更新旧断言，报告中列出）。

## 工程纪律
简体中文 KDoc/注释；魔法数字常量化（块大小/重试次数/超时）；单一职责（SyncCache/SyncEngine/协议实现分离）；IO 全部 Dispatchers.IO；不引入 Android UI 依赖。

## 验证（允许且必须）
`.\gradlew.bat :sync:compileDebugKotlin :sync:testDebugUnitTest --console=plain`（锁等待重试规则同前）。禁止 assembleDebug。

## 完成报告格式
改动文件清单（±行数）、公共 API 变更（SyncEngine/SyncCache/KdbxMerger v2 最终签名，及与第四节契约的差异）、新增测试与结果、gradle 命令与结果、偏差与原因。
