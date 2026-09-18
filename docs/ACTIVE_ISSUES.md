# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。
> **只放现存问题**：本文件**只保留尚未闭环的条目**——已闭环条目的正文、批次流水与索引段一律不留；历史实现与验收证据以 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 `docs/resolved/batches/` 为单一真相源。
> **实施前必读**：各条目的 AC 已按 [`SECURITY_RECHECK_2026-09.md`](security/SECURITY_RECHECK_2026-09.md) §10 / §11 的**第四轮独立复核定版结论**逐条对齐，更正口径已内联到对应条目——照条目原文 AC 实施者可能**降低安全性**或**引入数据损坏**。
> **闭环纪律**：任务完成后，将该条目从本文件**整条移入** [RESOLVED_LOG.md](RESOLVED_LOG.md)（加一行索引 + 新增批次正文），并执行 `git commit & push`。

---

## 条目维护规则（ISSUE-P3-28 确立）

1. **新增条目必须附「核实时间点」与「核实方式」**：任何声称「某文件需要删除 / 某处没有消费方 / 某硬编码为 0」一类**关于代码库现状的前提**，都必须写明**何时、以何种手段**核实过，例如「2026-09-10 经 `Test-Path` 核实」「2026-09-10 经全仓 `grep maskPasswordsDefault` 核实（仅设置页回显）」。
   > 立规缘由：条目与代码库演进之间存在时间差，曾出现正文前提在开工时**已不成立**（文件早已删除、引用早已清理），致执行者去做已经完成的工作。
2. **开工前复核前提**：认领条目时先复核其正文前提（路径是否存在、行号是否漂移、消费方是否已出现）。前提已不成立的，**就地修正或显式标注**后再动手；完全无对象可改的条目应直接归档并注明原因。
3. **行号一律视为「核实时刻的快照」**：正文内引用的 `文件:行号` 仅作定位提示，实现前须以真实内容为准。

---

## 优先级定义

| 等级 | 严重度与类型 | 处理原则 |
|:---:|---|:---:|
| **P0** | **阻断级 / 致命安全漏洞**（数据损坏、篡改未拦截、明文泄露、严重崩溃） | 立即停止其他工作优先修复 |
| **P1** | **高危缺陷 / 核心功能阻断**（权限失效、关键契约异常、核心流程失败） | 最高优先级排期修复 |
| **P2** | **中危缺陷 / 协议互操作 / 性能瓶颈 / 测试有效性缺口** | 正常排期，按批次连续解决 |
| **P3** | **低危项 / 进阶特性接线 / 代码整洁度 / 评估与体验优化** | 渐进优化与特性补齐 |

---

## P1 高危与核心功能问题（0 项）

> **暂无开放项**（历史 P1 条目的实现与验收证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md)）。

---

## P2 中危缺陷与协议/测试缺口（1 项）

### ISSUE-P2-192 设备侧测试层覆盖面余量清单（§151 扩容后仍未被真机证明的面）

- **核实时间点与方式**：2026-09-18，全量清点 `*/src/androidTest/**`（逐文件读取并核对其实际调用的
  平台 API，非按文件名推定）；宿主 / 设备比例经 `find <模块>/src/{test,androidTest} -name '*.kt' | wc -l`
  计数——扩容前为**宿主 307 : 设备 26**；§151 新增 5 个设备类后为 **307 : 31**。
  下列每条的「零覆盖」结论均以 `grep -rl <类名> */src/androidTest` 核实（命中 0）。
- **归类**：属优先级定义中的「**测试有效性缺口**」。§151 已补齐 5 处（原生 AES-KDF / 口令强度、
  平台 DOM 的 PROPFIND、AndroidKeyStore 三类消费方、Credential Manager provider 请求契约），
  并当场撞出并修复两项真机缺陷（`ISSUE-P1-190` / `ISSUE-P1-191`）——**这正说明下列余量的性价比**。
- **余量清单（按性价比排序；均为「真机才可证」）**：
  1. **`sync` 传输层在设备侧仍零覆盖**，且是**结构性不可达**：`liveSyncTest` 联调凭据只经
     `systemProperty` 下发到宿主测试 JVM（`sync/build.gradle.kts` 的 `tasks.withType<Test>`），
     而全仓**无任何** `instrumentationRunnerArguments` ⇒ 设备侧永远读不到该参数，
     真实 HTTPS 联调从未在真机跑过。修法：加 `testInstrumentationRunnerArgument`，
     或在设备侧起 `MockWebServer`（`okhttp3.mockwebserver` 目前仅 `testImplementation`，需补
     `androidTestImplementation`）覆盖 `WebDavSyncProvider` / `S3SyncProvider` / `S3RequestSigner`
     在平台 HTTP 栈（Conscrypt + 真实 socket）上的请求-响应链路。
  2. **平台网络策略从未被证明生效**：`app/src/main/res/xml/network_security_config.xml` 的
     「全局禁明文 + 信任锚仅系统 CA」是 **Android-only** 的 NetworkSecurityConfig，
     宿主 JVM 根本不解析它；`SyncHttpClientFactory` 的 TLS-only `ConnectionSpec` 与它是双层防御的
     **上层**，而该上层目前只有代码注释作证据。
  3. **Hilt 图在真机上从未实例化**：无 `hilt-android-testing`、无 `@HiltAndroidTest`
     ⇒「DI 图能否构建」这一类启动崩溃只靠手工冒烟（改依赖后尤其危险）。
  4. **`WorkManager` 周期同步零设备用例**（`PeriodicSyncScheduler` / `PeriodicSyncWorker`，
     含 `Constraints`/`NetworkType` 与锁屏态执行），`androidx.work:work-testing` 未引入。
  5. **系统 UI / 权限依赖面**：`BiometricPrompt` 实际弹窗与 `CryptoObject` 绑定、通知渠道渲染、
     剪贴板（Android 10+ 后台读取限制、`EXTRA_IS_SENSITIVE`）、`FLAG_SECURE` 与截屏检测、
     SAF / `ContentResolver` / `FileProvider` 的附件与库导出、扫码相机流。
  6. **`core` 无 `androidTest` 源集**（连 `testInstrumentationRunner` 都未配置），
     而 `AppLog` 等平台侧行为只有在此源集才能证。
  7. **CI 从不跑 connected 层**：`.github/workflows/build.yml` 无 `connectedAndroidTest`
     ⇒ 本节这类缺陷（含 §151 撞出的两项）**只在本地真机可复现**，CI 上永久绿灯。
  8. **设备用例把环境前提写成硬断言**且无 orchestrator / `clearPackageData`：
     `QuickUnlockSealDowngradeDeviceTest`（要求**未**录入生物识别）、`TracedProcessProbeDeviceTest`
     （要求 `TracerPid=0`，挂调试器即红）、`PasskeyCreationDeviceTest`（`SDK_INT>=34` 硬断言）、
     `AutofillAuthChainDeviceTest`（要求解锁屏 + 独占 `UiAutomation`，且**自行改写全局
     `secure autofill_service`** 并依赖自身 `@After` 还原）⇒ 换机 / 换环境即红，
     红了又易被读作「生产有 bug」。应改为「前提不满足即 `Assume` 跳过 + 如实登记跳过数」，
     或引入 orchestrator 与 `clearPackageData`。
- **整改纪律**：① 逐项要么补用例、要么显式登记到
  [`architecture/已知工程限界.md`](architecture/已知工程限界.md) §4.1，**不得**留成无人认领的空白；
  ② 新增/修改的设备用例**必须在真机实跑**（`AGENTS.md` §5，§150 立规）——
  `compileDebugAndroidTestKotlin` 通过不构成任何验证证据；
  ③ **不得**以「宿主单测已覆盖同一逻辑」推定设备可用（§147 全零密钥解密、§143 平台剥离版 BC、
  §151 的两项均系「宿主全绿、仅真机失败」）。
- **验收标准**：1 / 2 / 7 三项必须闭环（传输层设备侧有真实请求-响应用例、网络策略被证明、CI 有
  connected 门禁或明确登记为不做）；3 ~ 6 逐条给出「补用例」或「入限界表」的处置；
  8 的全局副作用项须做到**可重复执行**（同一台设备连跑两轮无相互污染）。
- **边界**：本条是**测试基础设施**清单，不指认任何生产缺陷；§151 的结论仅代表
  Redmi 4X（API 37 / A53）一台，不得外推为「其余机型亦成立或不成立」。

---

## P3 低危问题、特性接线与体验优化（2 项）

### ISSUE-P3-188 巨型类与魔法数字专项整改（工程规则 §单一职责 / §禁止魔法数字 违例收敛）

- **核实时间点与方式**：2026-09-18，主控以 `wc -l` 全量扫描五模块（`app`/`core`/`crypto`/`database`/`sync`）
  `src/main` 下全部 `.kt`；超长函数以**括号配平逐点实测**复核（启发式初筛 + 人工核实，已剔除把类体 / KDoc 误计为函数的假阳性）；
  魔法数字面以 `grep -E '0x[0-9A-Fa-f]{2,}'` 排除常量声明行后按文件计数。
- **违例清单（核实时刻快照，行号以实现为准）**：
  1. **超 500 行文件（8 个，第一档）**：`EntryDetailViewModel`(564)、`EntryEditFormSections`(557)、
     `SettingsViewModel`(544)、`DatabaseSession`(535)、`DatabaseSettingsScreen`(530)、`EntryDetailScreen`(529)、
     `UnlockViewModel`(517)、`SyncCache`(507)；
  2. **400~500 行文件（约 36 个，第二档）**：见扫描快照，头部为 `VaultListDialogs`(490)、
     `RealVaultRepository`(489)、`VaultRepository`(478)、`AutofillConfirmActivity`(475)、`EntryEditScreen`(472)、
     `RuntimeIntegrityDetector`(470)、`PasskeyAssertionActivity`(466) 等；
  3. **超 50 行函数（第一档 ≥100 行）**：`SyncEngine.openRemote`(≈173)、`PasskeyAssertionActivity.onCreate`(≈153)、
     `SyncCycleRunner.runSyncCycle`(≈130)、`KdbxEntryMerger.mergeConflictedEntry`(≈129)、
     `VaultEntryWriteCoordinator.saveEntryInternal`(≈121)、`KeePasskeyAutofillService.processFillRequest`(≈120)、
     `SyncConflictController.handleConflictMerge`(≈104)、`ExtendedSettingsStore.load`（≈89 行签名起，含 KDoc 记 131）、
     `PasswordFillActivity.onCreate`(≈108)、`PasskeyCreateActivity.startCreation`(≈100)、
     `KeePasskeyCredentialProviderService.buildBeginCreateResponse`(≈97)、`CredentialResponseAssembler.buildPasskeyEntries`(≈93)、
     `InnerHeader.deserialize`、`KdbxKeyDerivation.deriveKeys`、`KdbxXmlMetaSerializer.serialize`、`VaultEntryMapper.mapKdbxEntryToUi`(≈112)、
     `HealthCheckEngine.analyzeEntries`(≈106)、`BiometricEnrollmentCoordinator.requestBiometricEnrollment`(≈134) 等；
  4. **内联十六进制字面量（排除合法形态后）**：`PasskeyCryptoEngine`(10)、`CborEncoder`(10)、
     `UnlockPasskeyManager`(8)、`SyncEndpointGuard`(7)、`OtpEngine`(7)、`TotpKeyUriParser`(6)、`PasskeyKeyText`(6)、
     `VariantDictionary`(5)、`KdbxCipherKeyResolver`(4)、`PasskeyKeyCodec`(4)、`KdfBenchmark`(4) 等；
     **合法形态不整改**（登记于此以防重复排查）：`ThemeMode`/`Color.kt` 的 UI 色板即常量定义、
     `LittleEndianUtil` 的位运算掩码、`DicewareWordList` 的数据表。
- **整改纪律**：
  1. 拆分**不得改变公开 API 与行为**（对齐 `DatabaseSession` 批次 D 先例：门面收敛、职责下沉同包协作类）；
  2. 涉及 `crypto` / `database` 解析面的改动须过既有对拍回归（`.kdbx` 语料 / Parity 套件）；
  3. 常量收敛**只挪定义不改值**，改值即属协议变更，须另行立项；
  4. 每档闭环后 `.\gradlew.bat test --rerun-tasks --max-workers=1` 全绿方准入库；
  5. **不得为凑行数把注释移出文件充当「瘦身」**——以职责拆分为准。
- **验收标准**：第一档 8 文件全部 ≤400 行（或如实登记限界理由）；
  > **§160 实测口径（`wc -l`）**：达标 6（`SyncCache` 382 / `DatabaseSettingsScreen` 369 /
  > `UnlockViewModel` 368 / **`EntryEditFormSections` 351**（§160）… ）、按理由登记 2
  > （`SettingsViewModel` 544 / `DatabaseSession` 535，限界 **§18**）、
  > **仍超 2**（`EntryDetailViewModel` 434 / `EntryDetailScreen` 426）
  > ⇒ 本条**不得**被读作「第一档已闭环」。`EntryDetailViewModel` 虽有 44 / 58 个成员是一行委托，
  > 但仍有 5 个含实现体的成员（`uiState` 装配 29 行、`exportAttachment` 14 行、协作者构造等），
  > **不满足**限界 §18 的成立前提（「成员全部为一行委托」）⇒ 不得登记为门面理由，只能继续拆。≥100 行函数全部拆分至 ≤50 行；
  第 4 目清单中的协议 / 格式语义字面量收敛为命名常量；剩余第二档渐进消化，未消化部分在批次文档留清单。
- **进度（2026-09-18 复核批次，`.\gradlew.bat test --rerun-tasks --max-workers=1` 全绿）**：
  - **第一档 8 文件**：`EntryDetailViewModel` 564 → 435、`EntryEditFormSections` 557 → 438、
    `UnlockViewModel` 517 → 368、`SyncCache` 507 → 382（文件层原语下沉 `SyncCacheFiles`）、
    `EntryDetailScreen` 529 → 426（对话框接线下沉 `EntryDetailDialogHost`）；
    `SettingsViewModel`(544) 与 `DatabaseSession`(535) 经复核为**纯门面**（成员全为一行委托），
    按验收标准的「或如实登记限界理由」分支处理，理由 / 边界 / 解除条件已登记
    [`architecture/已知工程限界.md`](architecture/已知工程限界.md) **§18**；
    `DatabaseSettingsScreen`(530) **未消化**，列入下方剩余清单。
  - **≥100 行函数（第 3 目）**：`SyncEngine.openRemote`、`SyncCycleRunner.runSyncCycle`、
    `KdbxEntryMerger.mergeConflictedEntry`、`VaultEntryMapper.mapKdbxEntryToUi`、
    `HealthCheckEngine.analyzeEntries`、`BiometricEnrollmentCoordinator.requestBiometricEnrollment`、
    `VaultEntryWriteCoordinator.saveEntryInternal`、`KeePasskeyAutofillService.processFillRequest`、
    `SyncConflictController.handleConflictMerge`、`AutofillDatasetBuilders.appendUnlockedDatasets`、
    `KdbxXmlMetaSerializer.serialize`、`ExtendedSettingsStore.load`、`KdbxHeader.deserialize`、
    `KdbxXmlParser.parse` 以及本批新完成的 `PasskeyAssertionActivity.onCreate`(153 → 编排 23 行)、
    `PasswordFillActivity.onCreate`(108 → 33)、`PasskeyCreateActivity.startCreation`(101 → 12)、
    `KeePasskeyCredentialProviderService.buildBeginCreateResponse`(97 → 26)、
    `CredentialResponseAssembler.buildPasskeyEntries`(93 → 31)、`InnerHeader.deserialize`(130 → 编排 8 行)、
    `KdbxKeyDerivation.deriveKeys`(90 → 10)、`VaultListProjection.buildVaultListUiState`(152 → 39)
    全部降到 ≤50 行（**非 Compose 逻辑函数已清零**）。
  - **核实方式修正（防重复排查）**：初筛的「括号配平」启发式会把**注释 / 字符串内含花括号**的短函数误计为超长函数。
    本批逐一实读排除四处假阳性：`FieldReferenceEngine.containsReference`（`{REF:` 文案）、
    `PasskeyData.usePrivateKeyBytes`（单行委托）、`SimpleJson.objectAt`（单行）、`PuxArchiveReader.parse`
    （`ImportJson.parse`，实为短函数）；`PasskeyCreateActivity.buildRegistrationJson` 实测 65 行（非 113）。
  - **第 4 目字面量**：清单内 11 文件的协议 / 格式语义字面量已全部收敛为命名常量；本批另新增
    `WebAuthnJson`（`app/passkey/WebAuthnJsonKeys.kt`，收敛 passkey 面 5 文件共 **60+ 处** JSON 协议键名与
    规范取值）、`INNER_RANDOM_STREAM_KEY_SIZE`(64)、`COMPOSITE_SEED_BYTES`(65) / `KEY_COMPONENT_BYTES`(32) /
    `HMAC_KEY_SELECTOR`(0x01)、`END_OF_HEADER_MARKER`、写侧 `XML_TRUE` / `XML_FALSE` / `boolElement`、
    `PasskeyKeyText` 的 PEM 空白码位。**全部只挪定义、未改任何取值**。
- **剩余清单（第二档渐进消化，未消化部分如实留此）**：
  1. **Compose 面的超长文件 / 超长函数**（结构性可拆）：`DatabaseSettingsScreen` ——
     **§156 已消化第一段**（三处同形「明文导出二次确认」弹窗收敛为共享组件
     `DatabaseSettingsExportConfirmDialog.kt`，530 → **431 行**；配套把 `ExportTicketSinkGuardTest`
     的接线判据由 3 条扩到 8 条并做变异验证，见
     [`resolved/batches/156-明文导出二次确认收敛批次.md`](resolved/batches/156-明文导出二次确认收敛批次.md)）；
     **§159 消化第二段**（子库段 + 导入段下沉为 `DatabaseSettingsSections.kt` 的两个同包段落组件，
     整页 **431 → 369 行，本文件降到 400 阈值以下**；并为搬家新增 `DatabaseSettingsSectionWiringTest` 4 例，
     钉住「选择器先于门控块 / 段落必须无条件组合 / 子库关闭三口径齐备 / 导入两步式顺序」）。
     **仍开放**：导出侧四个 `CreateDocument` launcher 与三处 `pending…Uri` 状态刻意留在整页
     （`ExportTicketSinkGuardTest` 的定位串锚在「SAF 回调把目标落到待确认态」这一现场，再搬走会让
     「弹了确认框却导出别的对象」失去可断言落点）；清单内其余 Compose 文件（`HealthCheckScreen`(326)、
     `ConflictResolutionScreen`(172) 等）仍按同一路径逐档消化。
     `HealthCheckScreen`(326)、`ConflictResolutionScreen`(172)、`ChildDatabaseDialog`(170)、
     `Argon2ParametersDialog`(168)、`UnlockStandardUnlockContent`(158)、`AboutSettingsScreen`(153)、
     `EntryDetailTopBar`(151)、`EntryDetailScreen`(137)、`PackageBlocklistManageDialog`(131)、
     `VaultListDialogHost`(130)、`GeneratorContent`(121)、`EntryDetailDialogHost`(119，本批新文件的接线体)、
     `SyncStatusCard`(119)、`themeListSection`(118)、`BasicCredentialsCard`(118)、`VaultDatabaseCard`(117)、
     `MasterKeyChangeDialog`(116)、`PrivilegedBrowserSettingsScreen`(115)、`ChildVaultEntryRowView`(113)、
     `EntryEditCustomFieldsSection`(108)、`SafeAttachmentPreviewDialog`(107)、`KeePasskeyTheme`(104)、
     以及两个 NavGraph（`keepasskeySettingsNavGraph` 252 / `keepasskeyNavGraph` 164）与 `KeePasskeyApp`(202)；
  2. ~~**§151 新增的第二档待消化项**：`SyncCycleRunner` 431 → 515~~ —— **§155 已闭环**
     （三段裁决与 `RemoteSyncContext` 移为同包 `internal` 扩展函数文件 `SyncCycleRemoteOutcomes.kt`，
     门面回到 **429 行**；五成员放宽为 `internal`；`AlgoHotPathGuardsTest` 改按「门面 + 分支文件」并集扫描，
     计数判据仍为 2。见 [`resolved/batches/155-同步周期远端分支下沉批次.md`](resolved/batches/155-同步周期远端分支下沉批次.md)）；
  3. **其余 400~500 行文件**（**§161 重测口径**：五模块 `src/main` 全部 `.kt` 逐文件 `wc -l`，
     **§165 后为 36 个**（§161 40 → §162 39 → §163 38 → §164 37 → §165 36）；原记「约 36 个」系 §188 首次清点时刻之数）——头部：
     `RealVaultRepository` 489、`VaultRepository` 478、`AutofillConfirmActivity` 475、`EntryEditScreen` 472、
     `RuntimeIntegrityDetector` 470、`SyncConflictController` 461、`KdbxHeader` 461、`KdbxXmlParser` 454；第一档余量 `EntryDetailViewModel` 434 / `EntryDetailScreen` 426 亦含在内。
     > **§165 再消化一个**：`EntryEditScreen` 472 → **319**（四件窄参数无状态展示件抽为
     > `EntryEditChrome.kt` 135 行 + 预览夹具归位到本包既有预览夹；`showIconPicker` /
     > `showDiscardDialog` 刻意留在母文件，避免「关对话框」出现两处真相）。
     > **§164 再消化一个**：`AutofillConfirmActivity` 475 → **366**（无状态的「调用方归属块」+ 其预览夹具
     > 移入 `AutofillCallerAttributionBlock.kt` 126 行，`private` → `internal`；三处源码接线守卫的判据
     > 都锚在 Activity 本体的认证结果与交付锁路径上，**其行为用户刻意排除**——该段的行为级证据只有
     > 设备侧 `AutofillAuthChainDeviceTest`，本机无设备时不搬，以免进入「只有静态判据、无法真机复跑」的区域）。
     > **§163 再消化一个**：`VaultEntryMapper` 490 → **384**（条目 TOTP 面「来源定位 → 配置解析 → 按时刻取码」
     > 三者是无状态纯函数，整族移入 `VaultEntryTotpMapping.kt` 127 行；TASK-46 的字节态与 `finally` 擦除
     > 语义随代码同迁未放宽；对外 `parseTotpConfig` / `computeTotpCode` 由母文件留一行委托，调用点零改动）。
     > **§162 再消化一个头部文件**：`PasskeyCreateActivity` 460 → **359**（注册响应材料是纯函数产物，
     > 整族移入 `PasskeyRegistrationPayload.kt` 133 行；`rpId` / `origin` 由实例字段改为显式入参，
     > 三处安全语义随代码同迁未放宽），该档实测降至 **39 个**。
     > **§161 已消化头部之一**：`VaultListDialogs` 490 → **280**（五个群组生命周期对话框移入
     > `VaultListGroupDialogs.kt` 242 行，组件体逐字未改、无可见性放宽）；
     > `.kdbx` 格式面（`KdbxHeader` / `KdbxXmlParser`）**刻意排在最后**——拆它们必须过对拍回归，属独立一段。
     > `EntryEditFormSections`（原 438）已由 §160 降到 **351**（分组选择分节 + 绑定应用解析下沉为同包
     > `EntryEditGroupAndAppSections.kt`）⇒ 该文件退出本档；其下沉块含 §131 的 `items(…, key = …)` 修复，
     > `AlgoHotPathGuardsTest` 的扫描目标已按**两文件并集**同步（判据强度不变）。
  4. **接线守卫与结构耦合的长期代价**：本批有**三个测试类**的静态源码比对断言因函数搬家而失配
     （`AutofillAuthResultWiringTest` 两条：确认入口基 Intent 定位串、候选 `setField` 续行缩进；
     `AlgoHotPathGuardsTest` 两条：内存树快照实参经 `RemoteSyncContext` 取值；
     `OneTapInteractionWiringTest` 一处：复制职责已下沉协作者，改为「门面须委托 + 协作者须真写剪贴板」双查），
     已按「**只放宽定位串、不降低断言强度**」修正并入库。凡再做结构性搬家，**必须**同批改这些守卫，
     且**不得**以删除守卫凑绿（`AGENTS.md` §3 测试资产纪律）。
- **依据**：`.codebuddy/rules/engineering-rules.md` §高内聚低耦合 / §禁止魔法数字；本条目为 2026-09-18 用户命题「消除巨型类和魔法数字」。

### ISSUE-P3-187 JNI 零拷贝评估（原生加密内核的边界拷贝成本）

> **条目性质**：**评估项**（先出结论与量化证据，再决定是否实施），非既定整改。

- **核实时间点与方式**：2026-09-18，双机（Redmi 4X / A53 级 与 M332BF / 现代）**连续 10 轮**
  instrumented 探针 `DeviceThroughputProbeTest.probeJni边界_10MiB成本分解_连续10轮`
  （logcat 前缀 `PERF-PROBE|`）＋ 独立 aarch64 二进制内核实测；原始数字见
  `docs/records/真机吞吐实测记录_2026-09-17.md` §8.2。
- **背景（实测得出，非推断）**：现有 JNI 桥是**逐字节拷贝**形态——入参 `convert_byte_array`
  （含一次零化 `Vec` 分配）+ 出参 `SetByteArrayRegion`。同一份 **10 MiB** 载荷的分解：
  - ChaCha20 单次 JNI **24.7 ms**（现代）/ 151.6 ms（A53），其**内核本体**仅 **16.3 / 85 ms**
    ⇒ 边界 + 拷贝 ≈ **8.4 / 66.6 ms**；
  - AES 单次 JNI **34.5 ms**，内核本体 13.3 ms ⇒ 边界 + 拷贝 ≈ **21.2 ms**；
  - 该成本**与算法无关**（两个内核同形对照），且**随调用粒度线性增长**（AES 生产 48.1 ms 中
    28% 为 PKCS#7 整缓冲垫片与明文擦除，同属此类）。
- **影响面**：所有已下沉内核（Twofish / ChaCha20 / AES / Passkey）都交这道税；它同时是
  `已知工程限界.md` **§15**（ChaCha20 刻意不做零拷贝）与 **§17**（AES 统一后的性能代价）的
  共同成因。零拷贝若成立，**收益面覆盖全族**，而非单个算法。
- **JNI 面的现状覆盖清单（本条的"未闭合项"定义；2026-09-18 核实）**：

  | 面 | 状态 |
  |---|---|
  | 符号与定长契约 | ✅ CI `native-gate` 逐名集合核对（4 ABI） |
  | 密钥所有权 | ✅ `ownedSecrets` 契约 + `StreamKeyOwnershipContractTest` |
  | 错误语义 | ✅ 与 JCE / BC 逐例对拍（`AesNativeParityTest` 等） |
  | 兜底分支等价 | ✅ `CipherFallbackParityTest`（强制兜底 ↔ 原生逐字节一致，含流式） |
  | 探活（库能否正确工作） | ✅ 官方向量 KAT 自测；AES 已由 1 分组扩至 **NIST 4 分组 + `iv` 出口契约** |
  | 边界代价 | ✅ 已量化并登记（限界 §15 / §17） |
  | **零拷贝（效率）** | ❌ **未做——本条要回答的问题** |
  | **有状态形态（结构性消除密钥所有权风险）** | ❌ **未评估——并入本条 AC ⑤** |

  ⇒ 也就是说：**本条目是 JNI 面上唯一未闭合的一项，且它只影响效率，不影响正确性**。
- **评估目标与验收标准**：
  1. **先定义契约**（评估产出物，须落文档）：**三条候选路线**各自的契约——
     ① `GetPrimitiveArrayCritical` / `ReleasePrimitiveArrayCritical`（`mode` 选择）；
     ② `DirectByteBuffer`；
     ③ **`CipherSpi`（有状态 Provider 形态，参考 KeePassDX 的 `NativeAESCipherSpi`）**。
     三者的**临界区内禁止分配、禁止 panic**、异常 / 早退路径的 release 语义、
     与现有桥范式（`catch_unwind` + `Zeroizing` 全路径擦除）的取舍须逐条写明。
     > 路线 ③ 的特别之处（2026-09-18 补充）：JCA 的
     > `update(byte[] in, int inOff, int inLen, byte[] out, int outOff)` **由调用方提供输出缓冲**，
     > 结构上允许零拷贝；且 `CipherSpi` 天然**有状态**（`engineInit` 时把密钥交给原生自持），
     > 顺带消除「Java 侧提前擦除密钥 ⇒ 全零密钥」这一类风险（§147 实测踩过）。
  2. **真机前后对比**（同机 / 同语料 / 10 轮中位）：以 §8.2 的四个锚点为基线，
     目标把「边界 + 拷贝」压到 **≤ 内核本体 + 20%**；达不到即判**收益不足**。
  3. **语义零漂移**：`AesNativeParityTest` / `ChaCha20NativeEngineTest` / `TwofishNativeParityTest` /
     `StreamKeyOwnershipContractTest` / `CbcStreamFramingTest` / `CipherFallbackParityTest` 与三层
     设备侧套件**保持全绿**；错误语义（失败返回、长度异常、擦除时点）逐例与现状对齐。
  4. **结论必须入档**：无论可行与否，结论与依据登记到 `已知工程限界.md`（§15 / §17 的解除条件），
     **不得只留聊天记录**。
  5. **有状态 vs 无状态对比（AC ⑤，2026-09-18 追加）**：必须给出两种形态在
     **密钥所有权 / 擦除时点 / 生命周期泄漏防护 / 可测试性** 四个维度上的对照结论；
     若采用有状态形态，须证明它**不削弱**现有秘密治理纪律（`Zeroizing` 全路径擦除 / 显式清零），
     并说明 `ownedSecrets` 契约在有状态形态下的等价物（应为「原生侧自持 + `close()` 时由原生擦除」）。
- **失败回退**：
  1. 临界区方案若与**秘密擦除纪律**冲突且无法自证（`Zeroizing` 全路径归零 / 禁止分配），
     回退到「`DirectByteBuffer` + 显式清零」形态重评；
  2. 上述两者均不可行时，回退到 **`CipherSpi`（有状态 Provider）路线**——它把密钥自持与输出缓冲
     一并解决，但引入跨 JNI 的对象生命周期管理，须按 AC ⑤ 逐维对照后再定；
  3. 三条路线均不可行 ⇒ **维持现状并收口**（当前代价已落在无感区：现代设备 <5 MB 库 +10~20 ms），
     把「不做」的理由与解除条件登记到限界表。
- **涉及文件**：`crypto/src/main/rust/src/jni_bridge_ext.rs`、`aes_cbc.rs`、`chacha20_stream.rs`、
  `twofish_cbc.rs`；`crypto/src/main/java/com/keepasskey/crypto/cipher/NativeAes.kt` /
  `NativeChaCha20.kt` / `NativeTwofish.kt` / `CbcStreams.kt`。
- **依据**：实测记录 §8.2 / §8.3；`已知工程限界.md` §15 / §17；批次 `147-AES内核下沉批次.md`。
