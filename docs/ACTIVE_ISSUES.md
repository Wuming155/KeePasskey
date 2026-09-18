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

> **历史开放项**：`ISSUE-P2-92`（平台剥离版 BC 抢占 `"BC"` 注册名致真机 ChaCha20 / Twofish
> 全路径不可用）已于 **§143** 闭环——`bouncyCastleProvider()` 改为持有完整 BC 实例与注册表解耦，
> 宿主回归 1 例 + 真机 3 例全绿，见
> [`resolved/batches/143-真机BCProvider抢占解耦批次.md`](resolved/batches/143-真机BCProvider抢占解耦批次.md)。
>
> **暂无其余开放项**。`ISSUE-P2-91`（同步内容变化检测漏比 `times` 与历史内容）已于 §122 批次
> **经前提复核撤销**——「只改 `times` 的本地编辑被静默丢弃」在本应用可达面上**不成立**
> （生产代码无 `expires` / `expiryTime` 写入者；`times` 的改写必然伴随 `fields` 或 `customFields` 变化）；
> 其真实残余（两处判定**口径刻意不同** + 历史只比条数）作为**口径而非缺陷**登记
> [`architecture/已知工程限界.md`](architecture/已知工程限界.md) **§10**，见
> [`resolved/batches/122-同步变化判定口径声明与语义锁定批次.md`](resolved/batches/122-同步变化判定口径声明与语义锁定批次.md)。

> **本批历史**：2026-09-17 登记的两条 CPU 占用瓶颈（`ISSUE-P2-89` 列表页秒级整页重建、
> `ISSUE-P2-90` TOTP 重算 O(T×N)）已于同日在 §114 批次闭环，实现与验证证据见
> [`resolved/batches/114-列表页秒级重建与TOTP重算收敛批次.md`](resolved/batches/114-列表页秒级重建与TOTP重算收敛批次.md)。
> 本批的**残留风险**（整库投影仍在收集上下文执行）已由 §117 闭环（`ISSUE-P3-154`），见
> [`resolved/batches/117-仓库投影流补flowOn批次.md`](resolved/batches/117-仓库投影流补flowOn批次.md)。

---

## P3 低危问题、特性接线与体验优化（3 项）

### ISSUE-P3-189 测试调度器跨用例污染防护未全覆盖（§18 归档批次口径的余量，本地偶发红已复现）

- **核实时间点与方式**：2026-09-18 14:05，本地 `.\gradlew.bat test --rerun-tasks --max-workers=1` 连跑两轮——
  第 1 轮 `:app:testDebugUnitTest` **1 红**（`BreachCheckHealthTest > 查询失败时状态为 FAILED 且原因如实上浮，绝不回落为未泄露 FAILED`，
  `IllegalStateException → MainDispatchers.kt:111 → Looper.java`）；第 2 轮同命令 **114 task 全绿**；单独
  `--tests` 重跑该类亦绿 ⇒ 判定为**套件内时序 / 顺序相关偶发**。抛点经
  `kotlinx-coroutines-test-jvm-1.11.0-sources.jar` 定位为 `TestMainDispatcherJvm.kt:45`
  `reportMissingMainCoroutineDispatcher`，原文即「`Dispatchers.Main` 在平台调度器缺失且测试调度器已 unset
  （含 `resetMain()` 之后）时被访问」。
- **归类**：属归档批次 **§18**（[`resolved/batches/18-偶发红根因修复-测试调度器跨用例污染.md`](resolved/batches/18-偶发红根因修复-测试调度器跨用例污染.md)）
  已记载的**同一类根因**，非 §188 的结构性拆分引入；§115 曾把本用例的同一条报错如实判为「§18 类偶发红、与本批无因果」。
  机理：`runTest` 结束**不取消** `viewModelScope`，ViewModel 内在真实线程（`Dispatchers.Default` / `IO`）上执行的
  在途工作于 `@After` 的 `resetMain()` **之后**回跳 Main，异常被协程测试记到「用例开始前已有未捕获异常」，
  **污染同一 JVM 中后续用例**（表现位置随执行顺序漂移）。
- **本批已修（§150）**：`ui/screens/settings` 下直接构造 `SettingsViewModel` 的三个用例
  （`BreachCheckHealthTest` / `HealthCheckViewModelTest` / `ChildDatabaseSettingsWiringTest`）补齐
  「先 `viewModelScope.cancel()`、再 `resetMain()`」口径——`SettingsViewModel` **无调度器注入点**，
  故采用 §18 对 `AuthenticatorViewModelTest` 的那一种修法。
  修复后 `test --rerun-tasks --max-workers=1 --continue` **连续三轮全绿**（`tests=2182 skipped=13`）；
  因修复前命中率仅 1/2，该三轮属**弱证据**，本条**不因三轮绿而闭环**。
- **剩余清单（18 个类仍无该防护；核实方式：脚本扫描 `app/src/test` 中同时含 `Dispatchers.setMain` +
  `resetMain` + `*ViewModel(` 构造、且不含 `viewModelScope.cancel` 的文件，2026-09-18）**：
  1. **风险最高**（生产侧用真实调度器且**无**注入点）：`DatabasePickerViewModelTest`、
     `DatabasePickerKeyFileCreateTest`（`DatabasePickerViewModel` 内 `withContext(Dispatchers.IO)`）；
  2. **风险较低**（已按 §18 注入 `displayDispatcher`，真实 Default 线程面已收敛，但生产侧另有 `Dispatchers.IO`）：
     `CustomIconDeleteTest`、`EntryDetailDisplayPreferencesTest`、`EntryDetailTotpCopyTest`、
     `EntryDetailViewModelTest`、`VaultDisplayPreferencesTest`、`VaultListChildDatabaseTest`、
     `VaultListDecorationsTest`；
  3. **待判定**（所构造 ViewModel 本体未见真实调度器，是否可泄漏取决于依赖链）：
     `ConflictResolutionViewModelTest`、`EntryEditViewModelTest`，以及 `unlock` 目录 7 个
     （`QuickUnlockSealDowngradeTest`、`UnlockFailureLogSanitizationTest`、`UnlockImportKdfStrengthNoticeTest`、
     `UnlockKeyFileFlowTest`、`UnlockViewModelBiometricAutoPromptTest`、`UnlockViewModelClearOnLeaveTest`、
     `UnlockViewModelTest`）。
- **整改纪律**：按 §18 两种修法择一——有调度器注入点的**注入测试调度器**（首选，从源头消除真实线程竞速），
  无注入点的在 `@After` **先 cancel 各 ViewModel 作用域、再 `resetMain()`**。
  **不得**以「给 `resetMain()` 包 try/catch 吞异常」或「调大 `awaitOffMainComputation` 超时」处置——
  那是掩盖污染而非修复。
- **验收标准**：上述 18 类全部具备防护，或逐条如实登记「该 ViewModel 无可在真实线程上回跳 Main 的在途工作」并给出依据；
  `.\gradlew.bat test --rerun-tasks --max-workers=1` **连续三轮**全绿；CI Fast gate 同期不再出现
  `UncaughtExceptionsBeforeTest`。
- **边界**：本条是**测试基础设施**缺陷，不涉及生产行为；复现窗口与时序 / 核数相关（§18 为 CI-only，
  本条本地 1/2 命中），故**不得**以「跑一次绿了」判定已修复。

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
- **验收标准**：第一档 8 文件全部 ≤400 行（或如实登记限界理由）；≥100 行函数全部拆分至 ≤50 行；
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
  1. **Compose 面的超长文件 / 超长函数**（结构性可拆，本批未做）：`DatabaseSettingsScreen`(530，
     单个 `@Composable` 约 459 行，含 18 个局部状态 + SAF launcher + 对话框接线，可对齐
     `EntryDetailDialogHost` / `VaultListDialogHost` 的「控制器 + 对话框宿主」先例下沉)、
     `HealthCheckScreen`(326)、`ConflictResolutionScreen`(172)、`ChildDatabaseDialog`(170)、
     `Argon2ParametersDialog`(168)、`UnlockStandardUnlockContent`(158)、`AboutSettingsScreen`(153)、
     `EntryDetailTopBar`(151)、`EntryDetailScreen`(137)、`PackageBlocklistManageDialog`(131)、
     `VaultListDialogHost`(130)、`GeneratorContent`(121)、`EntryDetailDialogHost`(119，本批新文件的接线体)、
     `SyncStatusCard`(119)、`themeListSection`(118)、`BasicCredentialsCard`(118)、`VaultDatabaseCard`(117)、
     `MasterKeyChangeDialog`(116)、`PrivilegedBrowserSettingsScreen`(115)、`ChildVaultEntryRowView`(113)、
     `EntryEditCustomFieldsSection`(108)、`SafeAttachmentPreviewDialog`(107)、`KeePasskeyTheme`(104)、
     以及两个 NavGraph（`keepasskeySettingsNavGraph` 252 / `keepasskeyNavGraph` 164）与 `KeePasskeyApp`(202)；
  2. **本批新增的第二档待消化项**：`SyncCycleRunner` 431 → **515**（`runSyncCycle` 由 ≈173 行降到编排形态、
     三段裁决下沉为同文件私有函数 + `RemoteSyncContext` 聚合体，接缝文档使文件变长）。
     下一步：把 `handleRemoteSynced` / `handleConflictDetected` / `RemoteSyncContext` 移为同包
     `internal` 扩展函数文件（`AutofillDatasetBuilders.kt` 先例），需把 `session` / `databaseSession` /
     `strings` / `conflicts` 四个成员由 `private` 放宽为 `internal`；改动须同步更新
     `AlgoHotPathGuardsTest` 的「两处合并入口传内存树」扫描文件清单；
  3. **其余 400~500 行文件约 36 个**（原第 2 目清单，头部：`VaultListDialogs`、`RealVaultRepository`、
     `VaultRepository`、`AutofillConfirmActivity`、`EntryEditScreen`、`RuntimeIntegrityDetector`）；
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

> **暂无其余开放项**。`ISSUE-P3-153`（Rust 下沉候选评估 → 立项落地）已于 **§145（ChaCha20 内核）
> + §146（Passkey ES256/Ed25519 签名内核）** 分两批闭环——真机生产路径：ChaCha20 整库流
> 2.7 → 54~66 MB/s（≈20~24×），ES256 sign 17.7ms → ≈1ms、Ed25519 3.8ms → ≈0.2ms；
> `cargo deny check` 全绿；RS256 经实测裁定不下沉。见
> [`resolved/batches/145-ChaCha20Rust内核下沉批次.md`](resolved/batches/145-ChaCha20Rust内核下沉批次.md) 与
> [`resolved/batches/146-Passkey签名内核下沉批次.md`](resolved/batches/146-Passkey签名内核下沉批次.md)。
> 同日连做闭环：`P2-92`（§143）、`P3-155`（§144）、`P3-153`（§145/§146）——**清单归零**。

> 本批为 2026-09-17「降低 CPU / 内存占用」排查的**其余开放结论**。
> 条目 153 为 **Rust 下沉候选的评估结论**（评估项）；条目 155 为同轮后续批次（§115）开工复核转登。
> 同轮排查的其余条目均已闭环：150 / 151 见 §115、152 见 §116、154 见 §117、156 见 §118、157 见 §119，
> 原 `ISSUE-P3-149`（投影热路径）的 ①②④ 见 §114、**③ 转登的 `ISSUE-P3-154` 见 §117**。
>
> **条目 160 ~ 181 的由来**：2026-09-17「算法与数据结构专项」排查（用户提出「看看是不是坏算法、有没有坏数据结构」），
> 方式为**五路并行静态审计 + 主控逐条复核关键点位**，覆盖 `app` / `core` / `crypto` / `database` / `sync`
> 五模块全部 `.kt` 源文件；已排除本清单与 `RESOLVED_LOG.md` 中已闭环项、以及
> [`已知工程限界.md`](architecture/已知工程限界.md) / [`产品裁决登记.md`](architecture/产品裁决登记.md) 中已裁决项。
> **口径声明（须先读）**：该批全部结论均为**静态代码结构推导**（复杂度与调用频率），**无任何性能实测数据**
> ⇒ **不得**把下列条目读作「已测得百分比收益」；整改验收标准一律按「不再产生某类工作」的结构性判据给出。
> 批内按「正确性已单列 `P2-91` → 高杠杆 → 低影响」顺序编号，编号续用不复用。
> **已闭环（§121 第一档：零风险局部项）**：`ISSUE-P3-162`（分组索引平方级投影）、
> `ISSUE-P3-172`（①②③ 循环内新建重对象；**④ 已裁决不实施**，理由登记 [`已知工程限界.md`](architecture/已知工程限界.md) §9）、
> `ISSUE-P3-173`（OTP Base32 装箱与线性查表）、`ISSUE-P3-181`（密钥文件 / CSV / 标签解析常数因子）——见
> [`resolved/batches/121-零风险局部项与守卫用例批次.md`](resolved/batches/121-零风险局部项与守卫用例批次.md)。
> **已闭环（§123 第二档：循环结构改造 ‣ 批量树操作单趟化）**：`ISSUE-P3-160`（批量删除 / 移动按 id
> 逐次重走整树）、`ISSUE-P3-161`（冲突决策逐条重建整棵树）——见
> [`resolved/batches/123-批量树操作单趟化批次.md`](resolved/batches/123-批量树操作单趟化批次.md)。
> **已闭环（§124 第三档：同一份数据重复计算 ‣ 前两条）**：`ISSUE-P3-166`（健康检查同一条口令
> 解密 3 次、哈希 2 次）、`ISSUE-P3-169`（内存驻留加密每次访问新建 `Cipher` / `Mac`；
> **其 AC ② 经复核判定原理不可实施**，依据见批次文档 §1.2）——见
> [`resolved/batches/124-同一份数据重复计算收敛批次.md`](resolved/batches/124-同一份数据重复计算收敛批次.md)。
> **已闭环（§125 第三档：同一份数据重复计算 ‣ 第三条）**：`ISSUE-P3-167`（同步接受路径对同一份字节
> 算 3 遍 SHA-256）——**① 摘要一次化已做**；**② 基线前移的「重复写盘跳过」判定不做**，理由与
> 解除条件登记 [`architecture/已知工程限界.md`](architecture/已知工程限界.md) **§11**，见
> [`resolved/batches/125-同步接受路径摘要一次化批次.md`](resolved/batches/125-同步接受路径摘要一次化批次.md)。
> **已闭环（§126 第三档：同一份数据重复计算 ‣ 第四条）**：`ISSUE-P3-171`（自动填充评分对全库条目
> 逐条字符串物化）——**① 形状短路与 ② `url` 单读已做**；**AC ② 的原文路线（为 `KdbxEntry` 加不解密
> 判定入口）判定不采用**（评分路径必须要 URL 文本，只读一次已吃掉同一条收益且不新增 API 面），见
> [`resolved/batches/126-自动填充评分逐条目物化收敛批次.md`](resolved/batches/126-自动填充评分逐条目物化收敛批次.md)。
> **已闭环（§127 第三档：同一份数据重复计算 ‣ 收口）**：`ISSUE-P3-170`（自动填充请求内的重复工作：
> 证书摘要 ×2 / Keystore IPC / hex 格式化）——①②③ 已做；「同请求内 `Mac` 复用」与「按包名跨请求
> 记忆化」两项**判定不做**并留痕，见
> [`resolved/batches/127-自动填充请求内重复读取收敛批次.md`](resolved/batches/127-自动填充请求内重复读取收敛批次.md)。
> **第三档（同一份数据被算两遍以上）已全部闭环**（166 / 167① / 169① / 170 / 171）。
> **已闭环（§128 第二档剩余项之一：循环结构改造）**：`ISSUE-P3-163`（字段引用引擎按每个引用重建整库
> 扁平列表）——改为入口建一次**按字段惰性**的引用目标索引并沿递归共享；AC 建议的「一次性建全部字段
> 索引」经复核**刻意收窄**（那会把全库口令解密一遍，属反向优化），见
> [`resolved/batches/128-字段引用解析索引一次化批次.md`](resolved/batches/128-字段引用解析索引一次化批次.md)。
> **已闭环（§129 第四档：线程落点 ‣ 首条）**：`ISSUE-P3-174`（列表页整库投影缺 `flowOn`）——`uiState`
> 在 `stateIn` 前补 `.flowOn(displayDispatcher)`；验收取**结构断言**（AC 允许二选一）并如实声明其
> 不构成运行期派发证据，见
> [`resolved/batches/129-列表页整库投影离开收集上下文批次.md`](resolved/batches/129-列表页整库投影离开收集上下文批次.md)。
> **已闭环（§130 第四档：分配面 / 线程落点 ‣ 第二条）**：`ISSUE-P3-178`（完整性探测未做字节级化）
> ——`TracerPid` 改字节级解析 + 缓冲按线程复用；maps 改流式字节匹配 + 块间重叠（并新增 16 MiB
> 有界上限，封住「hook `read` 喂无限流」的挂死面）；**AC ③（`Debug` 探针去重）判定不做**并留痕，见
> [`resolved/batches/130-完整性探测字节级化批次.md`](resolved/batches/130-完整性探测字节级化批次.md)。
> **已闭环（§131 第四档：渲染与组合期 ‣ 第三条）**：`ISSUE-P3-179`（非惰性大集合展开与组合期就地派生）
> ——对话框分组选择改**惰性 + 280 dp 高度上限 + `key`**（原实现超出屏幕的分组**无法触达**，是本批
> 唯一的可用性缺陷修复）、编辑页组合期过滤下沉 + `key`、面包屑补 `key`、日志等级色改 `remember`
> 预计算；**AC ① 的日志惰性化刻意不做**（嵌套纵向滚动属 UX 回归；日志有 500 行硬上限），见
> [`resolved/batches/131-非惰性大集合渲染与组合期就地派生收敛批次.md`](resolved/batches/131-非惰性大集合渲染与组合期就地派生收敛批次.md)。
> **已闭环（§132 第四档：节拍与线程落点 ‣ 第四条）**：`ISSUE-P3-175`（秒级节拍常驻与整页重建）
> ——① 详情页节拍改挂 `uiState` 的订阅期（`onStart`/`onCompletion`，`run` 循环体逐字未改）、
> ② 前半 验证器页接入 `calculateEntryTotps` 批量通道（每拍 T 次挂起调用 → 1 次）、
> ③ 列表页周期集合按快照实例缓存；**② 后半（验证器页列表与倒计时解耦）转登 `ISSUE-P3-182`**，见
> [`resolved/batches/132-节拍启停与逐条通道收敛批次.md`](resolved/batches/132-节拍启停与逐条通道收敛批次.md)。
> **已闭环（§133 第四档：同步往返 ‣ 第五条）**：`ISSUE-P3-180`（WebDAV 首传重复 PROPFIND）——
> `uploadAtomic` 增可选形参 `remoteExists` 并自首传路径下传，`Overwrite` 判定仅在未知时才现探；
> **验证为 MockWebServer 的实测请求计数**（`remoteExists=false` ⇒ 2 次；`null` ⇒ 3 次，含负向对照），
> 另将宿主侧 Windows 原子 rename 偶发 `AccessDeniedException`（平台现象）登记
> [`architecture/已知工程限界.md`](architecture/已知工程限界.md) **§12**，见
> [`resolved/batches/133-WebDAV首传重复探测收敛批次.md`](resolved/batches/133-WebDAV首传重复探测收敛批次.md)。
> **已闭环（§134 第四档：流订阅 ‣ 第六条 ①）**：`ISSUE-P3-176` 的 **① 冷流重复订阅**——列表页
> 两条整库投影流改 `shareIn` 后共享给 `uiState` 与装饰装配（2 份 → 1 份）；详情页 `getEntry(id)` ×3、
> `getGroups()` ×2 各收敛为一处（装配器新增 `scope` 形参以承载 `shareIn`）；**② 导航图重建转登
> `ISSUE-P3-183`**，见
> [`resolved/batches/134-冷流重复订阅收敛批次.md`](resolved/batches/134-冷流重复订阅收敛批次.md)。
> **已闭环（§135 第二档剩余项：保存路径分配面）**：`ISSUE-P3-164`（每次保存无条件全树历史保留期维护）
> ——递归改为**惰性分配**（首次发现变化才复制该层列表）；**AC 第一半的 O(1) 闸门判定不做**（维护点须覆盖
> 全部整树替换路径，漏一处即让修剪静默失效，风险不对称），见
> [`resolved/batches/135-保存路径历史修剪惰性分配批次.md`](resolved/batches/135-保存路径历史修剪惰性分配批次.md)。
> **已闭环（§136 第四档：Compose 状态宽度 ‣ 第七条＝`ISSUE-P3-176` ② 的转登项）**：`ISSUE-P3-183`
> （应用根状态过宽导致导航图整图重建）——`keepasskeyNavGraph` 形参由整个 `SettingsUiState` 收窄为
> `AppThemeMode`（该图实际只用 `themeMode` 两处）；根组合其余字段读取未动。**「不再整图重建」为结构性
> 推理**（依赖编译器 lambda 记忆化），见
> [`resolved/batches/136-导航图参数收窄批次.md`](resolved/batches/136-导航图参数收窄批次.md)。
> **已闭环（§137 第二档剩余项：合并健壮性）**：`ISSUE-P3-165`（分组父链自愈 `O(G × 深度)` 退化）
> ——改为**一次函数图染色**（`O(G)`，判定与逐组上溯逐项等价，已由随机图对拍锁定）；**装配递归深度
> 上限经复核判定不可达**（上游 `KdbxXmlParser.MAX_XML_DEPTH = 64` 已封顶输入深度），故**不加**并登记
> 依赖，见 [`resolved/batches/137-分组父链自愈单趟染色批次.md`](resolved/batches/137-分组父链自愈单趟染色批次.md)。
> **已闭环（§138 第四档：数据面分配 ‣ 第八条）**：`ISSUE-P3-177`（CBC 流式分块缓冲反复分配）——
> 解密侧稳态 **3 处 64 KiB 分配 → 0**（实例级 `buffer` + `bodyScratch`、`pending` 改「偏移 + 长度」），
> 并把「变换必须返回独立数组」由隐式前提升级为**显式校验**；**AC 的 `(offset, len)` 契约部分判定不做**
> （会触及 `NativeTwofish` 的 JNI 定长布局契约、收益为每块 1 次分配），见
> [`resolved/batches/138-CBC解密流缓冲复用批次.md`](resolved/batches/138-CBC解密流缓冲复用批次.md)。
> **已闭环（§139 第四档：节拍与整页状态宽度 ‣ 验证器页）**：`ISSUE-P3-182`（验证器页倒计时与列表内容未解耦）
> ——删除占位 tick 流、内容独立成流、复用并**迁移更名**列表页通道为 `ui/model/TotpCountdownTracker`、两条窄通道
> （刻度 + 实时码）只由卡片读取；`TotpCardItem` 去掉 `remainingSeconds` 与 `codeFormatted`（格式化提为单一实现），
> **HOTP 卡片按 `isHotp` 分流取投影之码**（AC 未写、照抄列表页写法会显示刚被消费掉的码）；见
> [`resolved/batches/139-验证器页倒计时解耦批次.md`](resolved/batches/139-验证器页倒计时解耦批次.md)。
> **已闭环（§140 第四档：同步冲突周期解密面）**：`ISSUE-P3-168`（冲突周期实际为 4 load + 4 save = **8 次 KDF**，
> 条目原记 6 次且行号已漂移 +10）——三方合并的本地侧改**直取会话内存树快照**（`handleConflictMerge` 的
> `localDbOverride` + `localDbOwned` 擦除守卫），省去一次「把刚序列化出的字节解析回树」；
> **AC ②（会话级「摘要 → 已解析树」缓存）判定不实施**（须跨会话持有整棵解密树，破坏 `已知工程限界.md` §1.6
> 的树外可达性穷举）并登记同文件 **§14**（含解除条件）；见
> [`resolved/batches/140-冲突合并本地侧直取内存树批次.md`](resolved/batches/140-冲突合并本地侧直取内存树批次.md)。
> **本清单剩余 2 项**（`P3-153` / `P3-155`）的 AC 均要求**真机吞吐实测**（BC 现行 vs Rust 候选、AES-CBC 块粒度
> 前后对比）——**实测已于 2026-09-17 完成**（真机 Redmi 4X，见
> [`records/真机吞吐实测记录_2026-09-17.md`](records/真机吞吐实测记录_2026-09-17.md)）：
> `P3-155` 已于 **§144** 闭环（解密侧分块流，真机 15.3 → 55.5 MB/s）；`P3-153` 已于 **§145/§146**
> 闭环（ChaCha20 内核 + Passkey 签名内核；RS256 否定）。
> 实测过程另发现并登记 `ISSUE-P2-92`（平台 BC 抢占致真机 ChaCha7539 不可用，**整改中发现 Twofish
> 路径同样踩中且被静默吞错**）——已于 **§143** 同日闭环。
> **2026-09-17 增补与同日闭环**：交互成本核查顺带发现 `ISSUE-P3-184`（详情页 TOTP 复制按钮谎报成功），
> 该条连同 5 项**同源但无编号**的交互整改（甲b 列表行徽标一次点击复制 / 乙 三处单值设置就地化 /
> 丙 泄露检测开关开启即扫描 / 丁 「保存并同步」合并主按钮 / 戊 系统设置一次点击直达）已于 **§141** 同批闭环，
> 见 [`resolved/batches/141-一次点击交互整改批次.md`](resolved/batches/141-一次点击交互整改批次.md)。
> **已闭环（§142 增补条目同日闭环）**：`ISSUE-P3-185`（选择器路径不复用会话授权宽限）、
> `ISSUE-P3-186`（选择器路径不兑现 TOTP 复制/通知偏好）——选择器 `confirmAndFill` 接入
> `AutofillAuthenticationPolicy.skipPickerRepeatConfirmation` 查询与 `deliver` 成功路径同口径写入授权
> （域口径与数据集路径同源，均走 `resolveUsableWebDomain`）；TOTP 二次动作收敛为共用实现
> `AutofillPostFillTotpActions`（确认页同批改委托），见
> [`resolved/batches/142-选择器路径宽限与TOTP偏好兑现批次.md`](resolved/batches/142-选择器路径宽限与TOTP偏好兑现批次.md)。
> ⇒ 「无需设备即可闭环」的开放项**再次清零**；本清单仍余 `P3-153` / `P3-155` 两项（均需真机吞吐实测）。
>
> **2026-09-17 再增补（同日第二排）**：用户命题「查找坏交互——本该一次点击就完成却要点很多次（尤其密码填充）」。
> 经**填充全链路逐环节通读**（AutofillService → 解锁页 → 选择器 / 确认页 → 回传；CM 通道 PasswordFillActivity），
> 登记 `ISSUE-P3-185`（选择器路径不复用会话授权宽限）与 `ISSUE-P3-186`（选择器路径不兑现 TOTP 复制/通知偏好）；
> 「唯一强匹配候选不直达」经判定属**安全取舍而非缺陷**，登记 [`architecture/产品裁决登记.md`](architecture/产品裁决登记.md) **PD-05**。
> 排查中同时确认既有一次点击整改均已就位（列表行一键复制密码 / TOTP 徽标 / 解锁页 IME Done 接线 / 系统设置直达），无回归。

> **已闭环（§145/§146）**：原 `ISSUE-P3-153`（Rust 下沉候选评估 → 立项落地）——ChaCha20 内核
> （§145）与 Passkey ES256/Ed25519 签名内核（§146）分两批落地，RS256 裁定不下沉，见上。
>
> **已闭环（§144）**：原 `ISSUE-P3-155`（AES-CBC 解密侧受 `CipherInputStream` 内部 512 B 缓冲限制）——
> 采纳结论经真机验证后落地：解密侧切换 `CbcDecryptingInputStream` 分块骨架（范围收窄依据：加密侧实测
> 1.0× 无收益），真机吞吐 15.3 → 55.5 MB/s（3.6×），宿主官方夹具 + 真机 KeePassXC 语料端到端 +
> pykeepass 外验三重互操作铁证，见
> [`resolved/batches/144-AES解密侧分块流批次.md`](resolved/batches/144-AES解密侧分块流批次.md)。

