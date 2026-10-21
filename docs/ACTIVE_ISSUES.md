# KeePasskey 当前未解决问题

本文件是当前缺陷、功能缺口和待办的唯一状态源，只保留尚未闭环事项。新条目须记录核实时间与方式；开始处理前重新核对涉及的路径、行为和前提。关闭事项后从本表移除，不在此追加进度或历史流水；完成任务不自动新建永久文档。

> **本轮进度口径**：下文“已实现”只表示代码/文案已有对应改动，不表示问题已闭环；“尚未完成”列出必须继续实现或验收的项目。只要仍有未完成项，该问题就保留在本表。

## 优先级

| 等级 | 范围 | 处理原则 |
|---|---|---|
| P0 | 数据损坏、明文泄露等阻塞级问题 | 立即优先处理 |
| P1 | 高风险缺陷或核心功能阻断 | 最高优先级 |
| P2 | 协议互操作、性能或测试有效性缺口 | 正常排期 |
| P3 | 低危问题、体验和渐进改进 | 渐进处理 |

## P0 阻塞级问题

暂无。

## P1 高危与核心功能问题

暂无。

## P2 中危缺陷与协议/测试缺口

### ISSUE-P2-636 同步缓存与版本标记分离提交可在重启后误判无修改

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认，崩溃矩阵待实跑；置信度：高（false-clean分支）；后续覆盖后果待验证。
- 问题：writeCache先替换.cache，再独立更新.version。两步之间中断会留下新字节与旧/缺失version；hasLocalChanges仅比较version/baseversion，缺失version直接false。冷启内容比较以当前库对新cache，两者相同仍判UNCHANGED，无法修复旧标记。
- 影响与触发：已保存本地内容可能不上传，远端长期落后；远端后续变化时有覆盖本地新内容的风险。不能表述为崩溃立即损坏正式KDBX。 触发序列及反证详见方案。
- 位置：`sync/src/main/java/com/keepasskey/sync/engine/SyncCache.kt:126`；`sync/src/main/java/com/keepasskey/sync/engine/SyncCacheMaintenance.kt:29`。
- 已实现（2026-10-21 复核）：`hasLocalChanges` 以 `.cache` 实际字节摘要对照 `.baseversion`；本轮另修「基线前移仍取旧 `.version`」的真实缺陷（`SyncEngineOpenBranches.openCachedBranch` 与 `SyncCacheMaintenance.updateBase` 改为同源摘要，三份标记背靠背原子提交）。运行证据：`SyncCacheCrashRecoveryTest` 10 例（cache 已替换/version 落后、version 缺失、baseversion 缺失、rename 前失败、并发 6 路、崩溃后本地赢上传与远端并发 C），0 失败。
- 已实现（2026-10-21 追加修复）：并发写同一路径时 `.cache` 与 `.version` 分属不同写入者，会出现「标记与字节交叉污染」——`SyncCache.writeCache` 与 `SyncCacheMaintenance.updateBase` 现按**规范键进程内互斥**（`SyncCacheFiles.pathLock`），字节与标记落在同一临界区。`SyncCacheCrashRecoveryTest` 连跑 5 次 0 失败（修复前 3 次中 1 次偶发红，证明该竞争真实存在）。
- **进程级真实死亡验收**（2026-10-21）：`SyncCacheCrashRecoveryDeviceTest` + `tools/device/run_p2_636_crash.py`（隔离 AVD，两阶段：prepare 段制造崩溃窗口 → 宿主 `am force-stop` 核实旧 PID 消失 → verify 段在**新进程**核对）。两轮稳定通过，`tests=1 failures=0`：
  - 崩溃窗口（`.cache` 已换新字节、`.version` 仍为旧值）后新进程 `hasLocalChanges` 为 **true**；
  - `.cache` 可整份读回且**逐字节等于**崩溃前写入内容，目录内**无 `.tmp` 残留**；
  - 删除 `.version` 后仍判为 **true**（原缺陷正是此分支直接 false）；
  - 基线前移后负对照为 **false**（不把所有情况都判脏）；
  - **ETag 标记一致性**：崩溃窗口后 `.meta` 的 ETag 仍等于**崩溃前**基线值（新字节属「未确认上传」的本地修改，远端仍是旧版本，标记不得被顺带改写）；基线前移后 ETag 同步更新为新值。
  证据：`.ci-artifacts/remediation-20261021/p2-636/p2636-*-{prepare,verify}.{xml,instrumentation.txt}`。
- **尚未完成**：真实远端（MinIO / 商业 WebDAV）上的端到端 ETag 往返未取设备读数——本轮核对的是崩溃窗口后**本地标记**的一致性（ETag 来源由服务端决定，本地只保证「不与自己错配」）；跨进程并发写仍依赖各文件的原子替换与 SHA-256 校验（已登记为工程限界）。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-636/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-638 恢复副本差异比较忽略密码和实际附件内容

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：恢复预览的fingerprint只比title/userName/url/notes/tags及附件/历史数量，未比较密码、OTP、受保护自定义字段、Passkey材料或附件字节。内容改变但数量相同可显示“修改0”。
- 影响与触发：恢复/覆盖/丢弃前的决策依据失真，尤其密码单独变化、历史已达容量上限或禁用历史的库；不意味着系统自动删除副本。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/security/VaultRecoveryCoordinator.kt:211`。
- 已实现（2026-10-21 复核）：内容比较抽到 `RecoveryEntryContentComparator`（字段内容等值，不解密成字符串；自定义字段、附件**逐字节**流式比较、历史、条目元数据）。附件比较不再依赖 32 位 `contentHash`（不同字节可能同哈希），并对超预算与读取失败 fail-closed。运行证据：`RecoveryEntryContentComparatorTest` 6 例 + `RecoveryPreviewContentTest` 4 例（生产 `VaultRecoveryCoordinator.preview` 路径：密码 / OTP / Passkey / 受保护自定义字段单独变化、同数量异哈希附件、等长历史异内容、无变化负对照、正式库不可读、错误凭据），全部 0 失败。
- 设备侧产品 UI 验收（2026-10-21）：`RecoveryPreviewFailureUiDeviceTest` **4/4** 通过（隔离 AVD，真实 `MainActivity` + 真实 `RecoveryViewModel` + 真实 `VaultRecoveryCoordinator`，不替换 `previewOperation`）：
  - **正式库不可读**（把正式库写成垃圾字节）：预览返回 `officialEntryCount = null`，UI 呈现 `recovery_preview_no_official`——**绝不**给出「修改 0」这种误导读数；
  - **附件字节不同、长度相同**（仅最后一字节差 1）：必须判为「修改 1 条」，且 UI 摘要逐字等于 `副本 1 项；正式库 1 项；新增 0 / 删除 0 / 修改 1`——这正是原缺陷的盲区（只比数量/名称/长度/32 位哈希都看不出）；
  - **同一份字节的负对照**：必须成功预览且新增/删除/修改全为 0（证明不会因附件参与比较就一律报脏）；
  - **附件超预算**：把协调器的 `attachmentBudgetBytes` 调到 1 KiB（生产恒为 256 MiB，与真实大附件走同一分支），预览必须失败、**不得**给出差异读数，且 UI 呈现 `err_recovery_preview_budget_exceeded`。
- 本轮修掉的真实缺陷：超预算与「凭据不匹配」此前**共用同一文案**「恢复副本解锁失败（凭据不匹配或缺密钥文件）」——用户按提示去换凭据只会徒劳。现由 `RecoveryEntryContentComparator.AttachmentComparisonUnavailable` 分型，协调器上报新增的 `RECOVERY_PREVIEW_BUDGET_EXCEEDED`，文案改为「附件过大，无法逐字节比较差异；请先另存副本再人工核对」。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-638/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-641 CM Passkey与创建保存入口未消费已登记的库代次上下文

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；确认静态缺口，旧入口设备影响待验证；置信度：高（遗漏）；中（系统旧入口后果）。
- 问题：构造入口时已登记databaseId/sessionTicket，但只有PasswordFill消费并最终复核。Passkey断言/创建及密码保存/草稿读取当前活动库；BaseCredentialActivity仅做窗口保护，没有补偿票据检查。
- 影响与触发：旧A入口在活动库变B后可能对B同UUID条目计数/签名或把创建数据写入B，造成账户/库上下文混淆。需用户选择与验证；未证明任意应用、静默或跨RP泄漏。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/passkey/CredentialResponseAssembler.kt:370`；`app/src/main/java/com/keepasskey/app/passkey/PasskeyAssertionActivity.kt:102`。
- 已实现（2026-10-20）：四类 CM 入口消费已登记的随机请求上下文，绑定库路径和会话票据；实际改树、落盘及成功回传均复核原票据。锁定时无票据的创建入口仅允许解锁登记的原路径后绑定一次；传统 Autofill 保留独立令牌语义。运行证据（2026-10-21 复核）：JVM 会话门控 2 例、`CredentialSessionGateDeviceTest` 1/1、`CredentialProviderRequestContractDeviceTest` 5/5（真实签名 CallingAppInfo 与 Provider request）。
- 真实 framework CM 矩阵（2026-10-21）：`CmSessionMatrixDeviceTest` **4/4** 通过（隔离 AVD，API 36），经**真实系统 Credential Manager** 选择器与**生产** `PasskeyAssertionActivity` 完成签发：
  - A 库断言：系统呈现 `cm-matrix-A` 候选 → 生产确认页 → 结果回传，**A 计数 17→18、B 计数保持 71**；
  - 切到 B 库后断言：**B 计数 71→72、A 计数保持 17**（两库同 UUID 但互不串账）；
  - A/B 使用**不同 credentialId 与不同公钥**（同 UUID 也可判别签的是哪本库）；
  - A 库活动时，仅给 B 凭据 id 的 `allowCredentials` 请求**不出候选**。
- 独立 RP 对拍（`tools/passkey-interop/verify_assertion.py`，不导入本仓签发/解析代码）：A1 / B1 断言双双 `verified=true`（`signCount` 18 / 72、UP=true、UV=false、rpIdHash、origin、challenge、credentialId、userHandle 逐项通过），且 `forbiddenPublicKeyBase64`（另一库公钥）**验签必须失败**——即另一本库的私钥无法伪造该断言。证据：`.ci-artifacts/remediation-20261021/p2-641/{A1,B1}-{expected,response}.json`。
- 验收期间修正的装置缺陷（非产品缺陷）：
  - fixture 造条目时把 `userHandle` 塞成明文标签，与 WebAuthn「base64url 字节串」契约不符（独立验签器直接报 `Only base64 data is allowed`）；改为按契约存 base64url。生产创建路径本就取 RP 下发的 `user.id`（base64url 文本），无此问题。
  - 系统选择器呈现的是 **userName** 而非条目标题，且候选行文字分散在多个子节点、子树匹配会同时命中多层祖先（`awaitActiveNode` 要求恰好一个匹配，永远等不到）；改为「子树文本 + 取最深节点」。
  - 跨用例复用同一系统 CM 时，上一用例遗留的选择器 sheet 会让新请求排队（连跑必红、单跑必绿）；`prepare()` 先退场并等待 sheet 消失。
- **旧 token 跨进程重放**（2026-10-21）：`CredentialSessionGateDeviceTest` 两阶段 + `tools/device/run_p2_641_token_replay.py`（真实 `am force-stop` → 新进程核对），两轮稳定通过：
  - prepare 段：建库、登记票据，并断言**同进程内旧 token 可用**（否则证明的不是「跨进程失效」）；
  - verify 段（新进程，断言 PID 已变）：requestId 仍是合法 URI 形态（排除「解析失败」这一假原因）、登记表查不到旧 requestId、旧 token `bind` 返回 **false**；反向对照——同进程内新登记的 token **可用**，证明拒绝来自登记表随进程消失而非其他原因。
  证据：`.ci-artifacts/remediation-20261021/p2-641-token-replay/p2641-*-verify.{xml,instrumentation.txt}`。
- **尚未完成**：验收矩阵中的「认证过程中人为换库」（用户在系统验证窗口内切库）未在真实 CM 链路驱动——该时序需要精确控制系统窗口内的库切换，本环境无可控入口；其判据由 `CredentialSessionGateDeviceTest` 的换库拒绝用例与 JVM `CredentialSessionGateTest` 覆盖，但**不是**同一形态。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-641/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-651 同步配置保存失败后输入显示与已擦除凭据副本不一致

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：表单把本地password/accessKey/secretKey数组直接传给controller；后者在HTTPS拒绝及保存收尾均清零。UI只在成功时替换/清空本地状态，失败时输入框仍显示已输入而提交数组已全零。修正端点再次提交会被isAllZero持续拒绝。
- 影响与触发：WebDAV/S3配置错误后难以重试，输入状态误导用户，须重新键入秘密才能恢复；不应通过停止所有清理来修复。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/CloudSyncScreen.kt:160`；`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsSyncController.kt:244`。
- 已实现（2026-10-21 复核）：表单提交走 `SyncFormPersistence`（取**独立副本**交给控制器，所有权转移）；失败时工作副本清零、表单草稿保留供只改端点重试；成功才清空草稿并提示。另修「保存成功后控制器仍残留上次失败提示」的缺陷（WebDAV / S3 成功分支清 `syncFeedbackMessage`），并把成功提示收敛到页面组件一处。
- 设备验收期间另修**三个真实表单缺陷**（`SyncFormDrafts`）：
  1. 晚到的水合覆盖草稿——`hydrate()` 在页面组合后才从 IO 线程回灌已保存配置，原 `remember(uiState.xxx)` 以字段值为 key，回灌一落地就把用户正在输入的内容整段重建（真实表现为「刚敲完端点就变回默认地址，保存提交旧值」）。改为页面级草稿，仅在字段未被用户编辑时接受播种，保存成功后放权；
  2. 秘密草稿被列表回收——密码 / AccessKey / SecretKey 原以组合内 `remember` 持有，表单 item 滚出视口即被 `LazyColumn` 回收、`SecurePasswordField` 的 `onDispose` 随即擦除显示态，用户滚回来看密码框已空、提交必然失败。改由页面级草稿持有并在回收重建时回灌，只有离页才擦除；
  3. 保存成功后草稿未让位——成功路径解除「已编辑」标记并清空秘密草稿，归一化后的落盘值重新接管显示。
- 运行证据（设备侧，真实 UI 点击 + 真实 HTTPS 接收方）：`CloudSyncFormUiDeviceTest` 4/4（`tools/device/run_p2_651_652_ui.py` 全场景通过，两轮稳定）：
  - `httpEndpointRejectedSendsNoRequest`：点「保存并同步」提交 http 端点 → 上浮 HTTPS 要求、接收方**零请求**、未置位已验证、未停在同步中、失败后 URL 与密码框仍显示用户输入；
  - `correctedHttpsRetriesWithSameDraft`：只改 scheme、**不重输秘密**再次保存即落盘成功，且落盘口令与首次输入**逐字相等**、保存配置入口零联网。
- JVM 证据：`CloudSyncSaveRetryContractTest` 5/5、`SyncSavedConfigFailureAndCancelTest` 5/5、`CloudSyncSaveFlowTest` 5/5；本轮定向回归 27/27（`app:testDebugUnitTest`，0 失败 0 跳过）。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-651/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-652 测试连接忽略配置保存失败，仍验证并同步旧目标

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：SyncStatusCard.onTestConnection先persistConfig()但不检查返回值，无条件onSyncAfterSave。与同页保存并同步按钮的if守卫不同，保存失败时后续流程使用仍在存储中的旧配置。
- 影响与触发：测试的并非用户当前输入目标，且回调还会同步旧目标；用户可能把旧连接成功当作新表单验证，产生不期望的同步副作用。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/CloudSyncScreen.kt:402`；`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsSyncController.kt:244`。
- 已实现（2026-10-21 复核）：测试连接仅在 `persistConfig()` 返回成功后才继续测连/同步；同一成功保存回执（`SyncProviderConfigSnapshot`）贯穿「测连」与「同步」两个网络阶段，中途改存新配置只影响后续轮次，且迟到结论不会把新配置标成已验证（`SettingsSyncSavedFlow`）。
- 运行证据（设备侧，真实 UI 点击 + 真实 HTTPS 接收方）：`CloudSyncFormUiDeviceTest` 4/4（`tools/device/run_p2_651_652_ui.py`）：
  - `inFlightTestKeepsAAndDoesNotVerifyB`：A 的测连 PROPFIND 被接收方挂住 → 在途改存 B → 全程**只触达 A 的端点一次**（method/path/用户名逐项核对），A 的迟到结论不把 B 标成已验证，徽章仍为「未验证」；
  - `leavingPageWipesDraftAndResetsBusy`：未提交草稿离页后不残留（重进只回显已保存值），在途离页后忙态复位。
- JVM 证据：`SavedSyncConfigSnapshotFlowTest` 1/1、`SyncSavedConfigFailureAndCancelTest` 5/5（persist=false 零请求、A 测试在途改存 B 仍只触达 A、迟到 A 不误标 B、取消复位忙态）。
- 验收装置修正：接收方 Basic 凭据原由脚本固定为 `p2-651-user`、用例却写死 `ui-user`，导致 PROPFIND 走 401 分支提前返回、门闩永不置位（in-flight 编排失去判别力）。现由脚本经 `p2_651_user` / `p2_651_password` 参数下发，缺参即失败。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-652/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-655 S3目录参数先拼原文再拆分，含&前缀导致签名不一致

- 核实：2026-10-16（Asia/Shanghai），当前源码与协议消费链；静态确认；置信度：高。
- 问题：目录列举将原始queryPairs拼成signQuery，值内&被signer当成参数分隔；wire URL则先编码值。prefix=foo&bar/因此在线上是一个参数，签名里却被拆为两项，canonical request不一致。
- 影响与触发：含&的合法S3目录前缀无法浏览；若服务返回此类续页token，翻页亦失败。普通参数不受影响，不是凭据泄漏。 复现与反证详见方案。
- 位置：`sync/src/main/java/com/keepasskey/sync/s3/S3DirectoryList.kt:48`；`sync/src/main/java/com/keepasskey/sync/s3/S3RequestSigner.kt:110`。
- 已实现（2026-10-17）：`S3DirectoryList` 与 `S3RequestSigner` 改为**结构化键值表**——`S3RequestSigner.canonicalQueryString` 对键与值分别按 SigV4 编码、按键（同键按值）排序，wire URL 与签名 canonical query 消费**同一份输出**；不再存在「先拼串再拆」这一步。JVM：`S3QueryCanonicalizeTest` 6 例 + `S3DirectoryListMetadataTest` 7 例（`&` / `=` / `%` / `+` / 空格 / Unicode / 重复键；含「含 `&` 前缀不得改变参数个数」与「wire query 与 canonical 逐字一致」断言），0 失败。
- 已实现（2026-10-21 补充）：① **独立 AWS 官方向量** `S3OfficialSigV4VectorTest` 4 例（botocore `get-vanilla-query-order-key-case` 的 canonical request / hash / string-to-sign / 签名 `b97d918c…df2500` 逐字节一致；差异项由向量原文显式给出）+ 官方 SDK 生成的 6 条整条 Authorization 对拍；② **真实 MinIO 端到端**（`LiveS3DirectoryListTest` 4 例）：含 `&` 前缀首分页被服务端验签接受、服务端签发的真实续页 token 取到第二页、空目录、坏密钥映射 `AuthenticationError`。隔离服务矩阵见 `tools/local-sync/run_s3_matrix.py` 11/11。
- **已闭环（2026-10-21 复核）**：方案 §4 的两条关闭条件均已满足，并按 §5「原触发 + 正常/失败对照满足后才关闭」执行：
  - **独立 AWS 签名向量一致**：`S3OfficialSigV4VectorTest` 4 例（botocore 官方向量 canonical request / hash / string-to-sign / 签名逐字节一致）+ 官方 SDK 6 条整条 Authorization 对拍；
  - **隔离 S3 服务的目录 / 分页回执**：`tools/local-sync/run_s3_matrix.py` 本轮重跑 **11/11 通过**（含 `[PASS] SigV4 真实服务端到端（含 & 前缀 / 真实续页 / 坏密钥） gradle rc=0`），服务端真实验签接受含 `&` 前缀的首分页、真实续页 token 取到第二页、坏密钥不被伪装成空目录。
  - 本仓 signer 固定 `s3` 且附加 `x-amz-content-sha256`，与部分官方向量头部集合不同——已按向量原文适配比较，**未硬凑整签名**；该差异不影响上述两条判据。
- 保留边界（不构成未闭环项）：**未**对 AWS 真实 S3 服务做端到端（本环境无 AWS 凭据）；该条不在方案 §4 的关闭条件内，属额外声明。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-655/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P3-642 原生Passkey候选在请求RP不匹配时仍走包名回退

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认协议错配；不认定新增跨RP权限；置信度：高。
- 问题：PD-77规定rpId缺失/空白才退回包名维度，但native候选始终domainMatch||packageMatch。最终上下文未保留请求rpId，已绑定app即使请求RP-B也能选到RP-A并生成A的响应。
- 影响与触发：错误候选、被正常RP拒绝的断言和不必要计数写入；同一已绑定app本来可直接请求A，因此不把它定成新跨RP登录漏洞。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/passkey/CredentialCandidateMatcher.kt:57`；`app/src/main/java/com/keepasskey/app/passkey/PasskeyAssertionRequest.kt:104`。
- 已实现（2026-10-21 复核）：`PasskeyRequestRpBinding` 保留请求 `rpId`，候选与最终签发要求归一化后**精确相等**；仅 RP 缺失时才允许按已绑定包名回退。依据 WebAuthn §7.2 对 `rpIdHash` 的校验；`PD-77` 已补协议澄清。
- 运行证据：`CredentialCandidateMatcherTest`（含父域/子域负对照）、`PasskeyRequestRpBindingTest`。
- 设备侧真实入口验收（2026-10-21）：`PasskeyRpBindingDeviceTest` **6/6** 通过（隔离 AVD，API 36）——经**真实 framework Credential Manager** 的 `prepareGetCredential` 发起 **public-key** 请求，由**生产** `KeePasskeyCredentialProviderService` 应答，读 `hasCredentialResults(TYPE_PUBLIC_KEY_CREDENTIAL)` 作为「系统是否发布公钥候选」的直接读数：
  - 请求 RP 与凭据 RP 精确相等 → 发布（`prepared:passkey=true`）；
  - 请求 RP 不匹配（`other.example`）→ **不发布**；
  - 请求子域 `sub.example.com` → **不发布**（精确相等口径，不做子域放宽）；
  - `allowCredentials` 不含该凭据 → **不发布**；命中 → 发布；
  - 请求缺 RP → 按已绑定包名回退并发布（PD-77 的唯一回退口）。
- **尚未完成**：选择器点选与最终签发后的 RP 回执未在本设备用例内驱动（候选层已证「不发布」，签发前 `PasskeyAssertionActivity` 的 origin ⇄ rpId 复校由 `PasskeyRequestRpBindingTest` 覆盖判据）；本机 `cmd credential` 无实现，请求一律由真实客户端 Activity 发起。
- [独立整改方案](architecture/问题整改方案/P3/ISSUE-P3-642/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P3-643 重复选择自定义图标会清掉其它未保存编辑的脏标记

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：onCustomIconSelected直接设置isDirty=(旧customIconId!=新id)，没有保留旧isDirty。用户已编辑标题/密码后选择当前相同图标，或连续选择同一新图标，脏位变false。
- 影响与触发：离页不弹丢弃确认，未保存修改容易丢失；未保存编辑登记也会错误认为没有草稿。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditViewModel.kt:307`；`app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditScreen.kt:204`。
- 已实现（2026-10-21 复核）：重复选择图标保留既有表单脏标记；只有「此前未脏且图标未变」才保持 clean。运行证据：`EntryEditViewModelTest`（重复选图标保留标题/密码脏态、连续同图标、未改动负对照）与 `EntryEditDirtyConfirmationConsistencyTest`。
- 设备侧联动验收（2026-10-21）：`EntryEditDirtyConfirmationDeviceTest` 2/2 通过（隔离 AVD，真实生产 `EntryEditScreen` + 生产 `EntryEditViewModel`），补齐 `UnsavedEditRegistry` 与返回确认的联动：
  - 载入未改动时全局登记表**不报**「有未保存编辑」；改标题后**报**「有未保存编辑」（登记与脏态上报生效）；
  - 连续两次选当前图标后返回仍弹丢弃确认、「继续编辑」不离页且未保存标题**不落盘**；
  - 编辑页销毁后全局登记表**不残留**陈旧脏态（`onCleared` 注销配对生效）。
  为此在 `AutofillDeviceGraph` 增加 `unsavedEditRegistry()` 入口（只暴露既有 `@Singleton`，无测试专用行为）。
- 同时修正该设备用例的既有装置缺陷：`setTitleText` 的 `ACTION_SET_TEXT` 会返回 true 但文本未落入编辑态（Compose 语义节点被外部状态重播种），原实现一次性写入即断言，导致用例此前**一直为红**。改为「写入后回读校验、不一致即重试」，超时导出页面树。
- [独立整改方案](architecture/问题整改方案/P3/ISSUE-P3-643/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。
