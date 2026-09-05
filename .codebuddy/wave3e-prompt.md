# WAVE3-E 启动提示词（数据层接线与去假数据）

> 本文件为代理提示词存档。主会话在 Wave 2 验收通过后，将 Wave 2-C/D 实际交付的 API 签名补入「Wave 2 已交付 API」一节，再复制给子代理执行。目标：P0-6、P1-10、P1-11、P1-13、P2-18、P2-19、P2-21、P2-22、P0-5(app 侧接线)。

你是资深 Android 应用工程师，为密码管理器 KeePasskey 完成「数据层完整性修复与真实接线」（REMEDIATION_PLAN.md 的 Wave 3-E）。

## 项目背景
- 项目根目录：D:\GithubWorkplace\KeePasskey（Windows），Gradle 9.3.1（`.\gradlew.bat`），minSdk 36。
- 开工前必读（相对项目根，按顺序）：
  1. `AGENTS.md`、`.codebuddy/rules/engineering-rules.md`
  2. `REMEDIATION_PLAN.md`（问题 P0-6/P0-5接线/P1-10/P1-11/P1-13/P2-18/P2-19/P2-21/P2-22；第四节契约；第六节风险决策）
  3. `.codebuddy/skills/references/KeePassDX-架构分析.md` 第 5.2 节（回收站：`Database.isRecycleBinEnabled`/recycle/undoRecycle 模式）
  4. `.codebuddy/skills/references/keepass2android-架构分析.md` 第 5.4/5.5 节（同步状态机与 SyncUtil 消费方式）
- 【铁律】严禁读取/扫描 `参考项目/` 源码树；严禁复制参考代码；严禁 git 命令。

## Wave 1 已交付 API（git ed601da，先读源文件确认）
- `KdbxDatabase`：`binaries`、`recycleBinUuid: KdbxUuid?`、`recycleBinEnabled: Boolean`、`deletedObjects: List<DeletedObject>`、`customIcons`、`memoryProtection`、`customData`、`historyMaxItems/Size`、`generator` 等字段
- `core/model/DeletedObject(id: KdbxUuid, deletionTime: Instant)`
- `KdbxAttachment(name, refIndex, isProtected, data)` + `resolveData(binaryPool)` + `clear()`
- `DatabaseSession.useCredentials { (pwd, keyFile) -> ... }`（返回克隆，调用方清零）
- `HistoryManager.recordHistorySnapshot(currentEntry, newEntry, maxHistoryItems)` / `rollbackToSnapshot`
- `HealthCheckEngine.analyzeEntries(entries): List<EntryHealthIssue>`（database 模块）
- `OtpEngine.calculateTotp(secretKeyBase32, ...)` / `getRemainingSeconds`（core 模块）；TOTP secret 存 KDBX 标准字段 `otp`（KeyUriFormat: otpauth://totp/...）——解析函数若 core 无则 E 自行实现 KeyUri 解析（放 core/otp 可新建文件——Wave 3 无并行冲突，core 允许你修改）
- 类型化异常 `KdbxInvalidCredentialsException` 等

## Wave 2 已交付 API（git 4927189，实际签名，已核实）
- Wave 2-C（sync 模块）：
  - `sync.engine.SyncCache(cacheDir: File)`：`isCached(remotePath)/readCache(remotePath): ByteArray?/hasLocalChanges(remotePath)/writeCache(remotePath, data, updateVersion=true): String/updateBase(remotePath, baseVersion, etag?)/getState(remotePath): SyncCacheState?/clear(remotePath)`；`SyncCacheState`（含 localVersion/baseVersion/etag/lastSyncMillis）；伴生 `SyncCache.sha256Hex(bytes)`
  - `sync.engine.SyncEngine(provider: SyncProvider, cache: SyncCache)`：`var isOffline: Boolean`；`val events: MutableSharedFlow<SyncCacheEvent>`；`suspend openRemote(remotePath): SyncOpenResult`（sealed：RemoteSynced(remoteBytes, etag)/ConflictDetected(localBytes, remoteBytes, remoteEtag)/LocalWinAutoUploaded(etag)/RemoteLostRestored(etag)/CacheHitOffline(localBytes)/RemoteUnreachableUsingCache(localBytes)）；`suspend commitLocal(remotePath, localBytes): SyncCommitResult`（sealed：Uploaded(newEtag)/ConflictNeedsMerge(remoteBytes, remoteEtag)/RemoteUnreachable(keptLocal)）；`suspend markResolvedAndUpload(remotePath, mergedBytes): Result<String>`
  - `sync.merge.KdbxMerger`：`data class KdbxDatabaseLite(rootGroup: KdbxGroup, deletedObjects: List<DeletedObject> = emptyList())`；`data class MergeResult(mergedRoot, mergedDeletedObjects, conflicts: List<ConflictedEntryPair>)`；`fun mergeDatabases(base, local, remote): MergeResult`；既有 `ConflictedEntryPair(entryId: String, localEntry, remoteEntry, modifiedFields)` 与 `ConflictResolutionChoice{KEEP_LOCAL,KEEP_REMOTE,DUPLICATE_BOTH}`、`resolveConflict(pair, choice)` 保持兼容
  - `webdav.WebDavSyncProvider(serverUrl, username, passwordChars, client?)`：`suspend uploadAtomic(remotePath, data, expectedEtag? = null): Result<String>` 新增；既有 testConnection/getMetadata/download/upload/delete 不变
  - `s3.S3SyncProvider(endpoint, bucketName, region, accessKeyId, secretAccessKey, client?)`：不变（首传已带 If-None-Match:*）
  - `sync.model.cleanEtag()` 扩展函数
- Wave 2-D（app 仓库层，VaultRepository/Real/Fake 三处一致）：
  - `suspend getKdbxEntries(): List<KdbxEntry>`；`suspend findEntriesForRpId(rpId): List<KdbxEntry>`；`suspend findPasskeyByCredentialId(credentialId): KdbxEntry?`；`suspend saveNewPasskeyEntry(data: PasskeyData): KdbxEntry`；`suspend patchPasskeySignCount(entryId: String, newCount: Int)`；`suspend saveAutofillCredential(packageName, webDomain?, username, passwordChars: CharArray)`
  - `app.passkey.DomainMatcher`：`extractDomain(url)/isDomainMatch(rpIdOrDomain, originHost)/isPackageMatch(entryPackageHint, callingPackage)`
  - CredentialProviderService/AutofillService/4 个 Activity 已真实化——**不得推翻，仅编译性最小修补**

## 文件所有权（Wave 3 无并行代理，但仍需守边界）
- 允许修改：`app/**`（不得推翻 Wave 2-D 的 passkey/autofill 服务实现，仅可做编译性最小修补并报告）、`core/**`（仅允许新增 otp KeyUri 解析等小文件，不得改动 Wave 1 已交付语义）。
- 禁止修改：database/、crypto/、sync/ 下 main 代码（若发现 sync 需要小适配，报告说明，由主会话决策）；禁止 git 命令。

## 任务清单（全部必做，除标注可选）

### E1【P0-6】编辑保存完整保留（RealVaultRepository.mapUiEntryToKdbx + saveEntry 路径重构）
现状：`mapUiEntryToKdbx` 每次保存都重建 KdbxEntry，丢失 history/times/tags/图标/颜色/自定义字段（含 Passkey 字段）/attachments。
- 重构 `saveEntry(entry: UiVaultEntry)`：先从 databaseSession 取既有条目（按 UUID），存在则做**合并更新**：保留 history/times(仅更新 lastModificationTime)/tags/iconId/颜色/attachments/overrideUrl/qualityCheck/previousParentGroup/customData；字段以 UiVaultEntry 提交值为准（null/空串视为用户清空——与既有编辑页语义一致）；不存在则新建（现状逻辑）。
- 保存路径接入 `HistoryManager.recordHistorySnapshot`（旧→新，maxHistoryItems 取库配置 historyMaxItems）。
- `mapKdbxEntryToUi` 补齐：tags→（UiVaultEntry 无 tags 字段则不加，避免 UI 层扩散）、iconId→iconName 映射（48=folder/key 简单映射表常量）、history→revisions 已有、attachments→UiAttachment（用 KdbxAttachment.name + resolveData 大小格式化）。
- 单元测试（app/src/test 新文件）：编辑后 history 保留且新增快照；tags/icon/颜色保留；自定义字段（含 Passkey 字段）保留；密码字段更新生效。

### E2【P1-10】KDBX 标准回收站（RealVaultRepository + DatabaseSession 增量）
- `DatabaseSession` 新增（若 Wave 1-A 未提供）：
  - `suspend fun moveToRecycleBin(entryId: KdbxUuid)`：取 db.recycleBinUuid 组；不存在则创建名为"回收站"的组（iconId=48 之外的常量，如 43），更新 db.recycleBinUuid/recycleBinEnabled/recycleBinChanged 并置 DIRTY；
  - `suspend fun restoreFromRecycleBin(entryId: KdbxUuid, targetGroupId: KdbxUuid?)`；
  - `suspend fun emptyRecycleBin()`（物理删除组内条目 + 记录 DeletedObject 墓碑入 db.deletedObjects + 修剪：墓碑上限策略——保留全部，官方默认）；
  - `suspend fun isRecycleBinGroup(groupId: KdbxUuid): Boolean`。
  - 真正删除条目（emptyRecycleBin/彻底删除）时向 db.deletedObjects 追加 DeletedObject（同步合并的墓碑基础）。
- `RealVaultRepository`：删除既有内存 recycledEntryIds StateFlow 方案，全部改走库内回收站组：`deleteEntry`（在回收站→物理删除+墓碑；否则→moveToRecycleBin）、`restoreEntry`（从回收站还原到 parentGroupId 或根组）、`emptyRecycleBin`、`batchDeleteEntries`/`batchMoveEntries` 相应适配。getEntries 的 effectiveGroupId 逻辑改为按 parentGroupId==recycleBinUuid 判定回收站归属；getGroups 过滤或标记回收站组（UiVaultEntry/VaultGroup 既有 isRecycleBin 字段）。RECYCLE_BIN_GROUP_ID 假 ID 机制废除，UI 层若硬编码该常量需同步适配（VaultListViewModel 等处 grep 修复）。
- 单元测试：删除→回收站→还原 往返；emptyRecycleBin 后条目消失且墓碑入 deletedObjects；重启会话（重新 load）后回收站状态仍在（持久化验证）。

### E3【P1-11】TOTP 真实化（AuthenticatorViewModel + core/otp KeyUri 解析）
- core/otp 新增 `TotpKeyUriParser`（若 Wave 1 无）：解析 `otpauth://totp/Issuer:Account?secret=XXX&issuer=...&period=30&digits=6&algorithm=SHA1` → `ParsedTotpConfig(secretBase32, issuer, account, periodSeconds, digits, algorithm)`；非法 URI 返回 null（宽容：缺失 period/digits 用默认值）。
- `RealVaultRepository.getEntries`/`mapKdbxEntryToUi`：条目含 `otp` 字段（KdbxConstants.Fields 无则用 "otp" 常量，对齐 KeePass 标准字段名）→ 尝试解析为 totpCode 派生标记（UiVaultEntry.totpSecret 新字段或复用 totpCode 存 secret——**推荐**：UiVaultEntry 增加 `totpSecret: String?` 与 `totpPeriod: Int = 30`/`totpDigits: Int = 6`/`totpAlgorithm: String = "SHA1"` 字段，totpCode 留给运行时计算值）。
- `AuthenticatorViewModel`：删除 `generateMockCode` 与 GitHub/Google/AWS 标题硬编码过滤；改为过滤 `totpSecret != null` 条目；每秒 tick 用 `OtpEngine.calculateTotp(secret, now, period, digits, algorithm)` 计算真实码；`calculateCurrentRemainingSeconds` 改用 `OtpEngine.getRemainingSeconds`。
- FakeVaultRepository 测试数据补 1-2 条带 otp secret 的条目（用 RFC 6238 测试向量 secret）。
- 单元测试：TotpKeyUriParser 解析（标准/缺参/非法）；ViewModel 层用固定时间注入验证 TOTP 码（AuthenticatorViewModel 若难注入时钟，抽 `TotpCalculator` 纯函数类测试）。

### E4【P1-13】健康检查真实化（SettingsViewModel/HealthCheckScreen 数据源）
- SettingsViewModel 注入 VaultRepository；`rescanHealth()` 改为：`vaultRepository.getKdbxEntries()` → `HealthCheckEngine.analyzeEntries(entries)` → 聚合（weak/reused/expired 计数、按问题数计算 healthScore 公式常量化：100 - 5*weak - 10*reused - 15*expired，下限 0，KDoc 注明启发式）→ 更新 healthStateFlow（healthStatus/healthMessage 按 score 分档：≥90 优秀 / ≥70 良好 / ≥50 一般 / else 需改进，字符串资源化中英双语）；lastHealthScanTime = 当前时间格式化。
- 初始化 healthStateFlow 默认值改为"未扫描"状态（score=0, status=待扫描），冷启动不自动扫描（用户点击触发）。
- HealthCheckScreen 若直接绑假数据则改绑 uiState 既有字段（已是 SettingsUiState 通道，一般只需 ViewModel 改造）。
- 单元测试：注入 FakeVaultRepository（弱密码/重复密码/过期条目）→ rescan 后计数与 score 断言。

### E5【P0-5 app 侧】同步接线（SettingsViewModel.triggerSync + 新建 app/sync/SyncCoordinator.kt）
- `SyncCoordinator`（@Singleton，注入 DatabaseSession + SyncProvider 工厂 + KeystoreManager）：
  - `suspend fun syncNow(): SyncOutcome`（sealed：UpToDate / UploadedLocal / MergedAndUploaded / ConflictNeedsUser(conflicts) / Offline / Error(message)）：
    1. 从 DatabaseSession 取当前库（无活动库→Error）；
    2. 读加密持久化的同步配置（E6）构造 WebDavSyncProvider 或 S3SyncProvider；
    3. `SyncEngine.openRemote`：RemoteSynced→若 remoteBytes 与本地不同则 KdbxFile.load 远端→替换会话树→UpToDate；LocalWinAutoUploaded→UploadedLocal；ConflictDetected→取 base（缓存的历史基线字节，SyncCache 提供）/local/remote 三方 KdbxFile.load→`KdbxMerger.mergeDatabases`→conflicts 空则写回会话+markResolvedAndUpload→MergedAndUploaded；非空→ConflictNeedsUser(conflicts)（存入共享 StateFlow 供 ConflictResolutionScreen 消费）；RemoteLostRestored→UploadedLocal；CacheHitOffline/RemoteUnreachableUsingCache→Offline；
    4. 本地有未推送修改（DatabaseSession.state==DIRTY）→ `commitLocal`（序列化当前库字节）。
  - `val conflictFlow: StateFlow<List<ConflictedEntryPair>>` + `suspend fun resolveConflicts(choices: Map<String, ConflictResolutionChoice>)`。
  - 凭据经 `DatabaseSession.useCredentials` 借出（用毕清零）；序列化在 Dispatchers.Default、IO 在 Dispatchers.IO。
- `SettingsViewModel.triggerSync()`：删除模拟 delay，改调 SyncCoordinator.syncNow()，把 outcome 映射到 syncFeedbackMessage（中英字符串资源新增：上传完成/已是最新/已自动合并/发现冲突待处理/离线模式/失败原因）；isSyncing 状态真实翻转。
- ConflictResolutionViewModel 改造：数据源接 SyncCoordinator.conflictFlow；用户决策调用 resolveConflicts（KEEP_LOCAL/KEEP_REMOTE/DUPLICATE_BOTH）。
- WebDavSyncScreen 的"测试连接"按钮接 provider.testConnection()（SettingsViewModel 新增 testSyncConnection()，结果反馈字符串）。
- 单元测试：Fake SyncProvider（内存）+ 内存 DatabaseSession（setDatabaseForTesting）→ syncNow 各 outcome 至少 1 用例（UpToDate/UploadedLocal/MergedAndUploaded；ConflictNeedsUser 至少触发）。

### E6【P2-19】同步凭据加密持久化（KeystoreManager 扩展 + SyncCredentialsStore 新建）
- 新建 `app/sync/SyncCredentialsStore`（@Singleton，SharedPreferences + KeystoreManager AES-GCM 加密）：save/load/clear；存储字段：provider 类型/webdav url+username+password（加密）/s3 endpoint+bucket+region+accessKey+secretKey（加密）/remotePath。密文与 IV Base64 存储（对齐 BiometricCredentialStorage 模式）。
- SettingsViewModel：WebDavSyncScreen 提交的配置（updateWebDavConfig/updateS3Config）→ 持久化到 SyncCredentialsStore（不再只存内存 StateFlow）；SettingsUiState 展示已有掩码逻辑保持。
- 单元测试：加解密 round-trip（Robolectric 不可用则仅测非加密逻辑分层：store 接口以注入 encryptor/decryptor lambda 实现，测试注入恒等加密）。

### E7【P2-18】UnlockViewModel 主密码 CharArray 边界
- `persistBiometricCredentialIfEnabled(password: String)` → 改为接收 CharArray（调用方 UnlockScreen 传递处相应改；UiState 若有 password String 字段，评估改造为 TextFieldValue/CharArray 需波及范围，**最小方案**：保持 UI String 现状但进入 ViewModel 立即 toCharArray() 并清零源 String 无法做到（String 不可变）——此时以 KDoc 注明边界妥协并尽早转 CharArray；若 UnlockUiState 已是 String 则记录为已知限界）。报告中说明最终取舍。

### E8【P2-22】Manifest 加固
- `allowBackup="false"` + 新增 `android:dataExtractionRules="@xml/data_extraction_rules"`（fullBackupContent 废弃；xml/data_extraction_rules.xml：cloud-backup 与 device-transfer 均 exclude 所有 sharedpref 与 files，仅允许 exclude 指令的最小文件集）。
- `android:enableOnBackInvokedCallback="true"`（可选，跟随现代规范）。

### E9【P2-21 可选】附件接线（尽力而为）
- EntryEditScreen/EntryDetailScreen 若结构允许可加附件区（展示 UiAttachment 列表 + 导出按钮→AttachmentManager.exportToCache + FileProvider 分享）；若 UI 改造超范围，仅完成 Repository 层 getAttachments(entryId) 数据通道并在报告注明 UI 遗留。

### E10 全量验证
`.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest :core:testDebugUnitTest --console=plain`，然后 `.\gradlew.bat assembleDebug --console=plain`（Wave 3 无并行冲突，允许 assemble）。

## 工程纪律
简体中文 KDoc/注释；魔法数字常量化；敏感数据 CharArray/ByteArray + 显式清零；Result/sealed 错误模型；UiState 不可变 data class + StateFlow 单向数据流。

## 完成报告格式
改动文件清单（±行数）、各问题编号（P0-5/P0-6/P1-10/P1-11/P1-13/P2-18/P2-19/P2-21/P2-22）落实情况逐条说明、新增测试与结果、gradle 命令与结果、偏差与原因（特别是 E7 密码边界与 E9 附件的取舍）。
