# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。  
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。  
> **只放现存问题**：本文件**只保留尚未闭环的条目**——已闭环条目的正文、批次流水与索引段一律不留；历史实现与验收证据以 [RESOLVED\_LOG.md](RESOLVED_LOG.md) 与 `docs/resolved/batches/` 为单一真相源。  
> **实施前必读**：源自安全复核的条目，其 AC 已按 [`SECURITY_RECHECK_2026-09.md`](security/SECURITY_RECHECK_2026-09.md) §10 / §11 的**第四轮独立复核定版结论**逐条对齐，更正口径已内联到对应条目——照条目原文 AC 实施者可能**降低安全性**或**引入数据损坏**。  
> **例外（`ISSUE-P1-276` ～ `ISSUE-P3-299`）**：这批 2026-09-23 条目出自**两轮全仓审查**（8 个铺开代理 + 7 个对抗复核代理，反向口径＝先假设误报再取证），不属上述复核范围；其依据为各条「核实方式」所记的逐行反校与对抗推翻结论。凡条目标注「**未经对抗轮单独攻击**」或「**需外部证据（真机 / 真实 DAV 矩阵 / 官方对拍）**」者，认领时须按规则 6.1② 先自行复核前提，不得直接开工。  
> **闭环纪律**：任务完成后，将该条目从本文件**整条移入** [RESOLVED\_LOG.md](RESOLVED_LOG.md)（加一行索引 + 新增批次正文），并执行 `git commit & push`。
> **新增批次（2026-10-05）**：`ISSUE-P2-466~470` / `ISSUE-P3-474~487` 出自 [`records/软件工程质量审查记录_2026-10-05.md`](records/软件工程质量审查记录_2026-10-05.md)（五维度主源码静态审查 + 合并 / 同步 / 健康审计引擎深审）。**严重度映射**：记录标 medium（含 low/medium）→ **P2**，low → **P3**。这批条目为**纯静态审查**产出（未运行构建 / 测试 / 真机），认领时须按规则 6.1② 先复核前提（正文行号仅作核实时刻的快照）；其中涉及 `crypto/src/main/rust/**` 的原生面条目，入库前须按 AGENTS.md 测试资产纪律**四层 `connectedDebugAndroidTest` 真机实跑**（执行前按 §263 确认设备上无待保留数据或改用 AVD）。
> **新增批次（2026-10-06）**：`ISSUE-P1-495` / `ISSUE-P2-496~502` / `ISSUE-P3-503~516` 出自 [`records/三类隐蔽性故障排查报告_2026-10-06.md`](records/三类隐蔽性故障排查报告_2026-10-06.md)（假开关 / 接线断裂 / 读取源错误与静默降级三类，九路并行排查 + 逐条独立复核）。**严重度映射**：报告标 high → **P1**，medium → **P2**，low → **P3**。**三项登记裁决**（登记时新定，须随条目一并遵守）：
> ① **同族去重**：报告 F12 为「原生探活失败静默回落零观测」缺陷族的唯一母条目，其子集 F03（cipher 三引擎）/ F07（强度面）/ F11（Passkey 签名面）并入 [`ISSUE-P2-499`](#issue-p2-499)，不独立立项；
> ② **severity 取族内最大值**：`ISSUE-P2-499` 判 medium（母条目自身 low，子集 F03 为 medium——ChaCha20 BC 回落 ≈44× 慢、Twofish 整库数据流走纯 Java），条目内分层保留两侧论据；
> ③ **F25 不登记**：复核判定其缺陷前提被证伪（与格式裁决者 KeePass 2.61.1 官方 C# release 行为逐行同型，批次 273 勘误②复审显式维持、写侧 D17 守卫在位），`holds=false` 依据是前提证伪而非登记表命中。
> **触发状态口径**（报告 v3 判据 E1/E2，与本文件「只放现存问题」不冲突——masked 指**错误效果已产生或曾实际发作**，dormant 指**零错误效果、缺陷以声明侧残留 / 守卫缺口形态存在**）：masked 7 条（`P1-495` 除外，另 `P2-496~499`）应优先认领；dormant 条目为前瞻性风险，可按批连续解决。全部条目为**只读静态排查**产出，认领时须按规则 6.1② 先复核前提。
> **新增条目（2026-10-06 真机走查）**：`ISSUE-P2-517` 出自 `ISSUE-P1-495` 收工时的真机（MIUI / `M332BF` / Android 17）五层走查——`:crypto:` 37/37、`:database:` 18/18、`:sync:` 25/25 全绿；`:core:` 4 例中 1 例**确定性假红**（本条目）；`:app:` 51 例中 3 例失败属已登记的 `ISSUE-P2-492` 厂商冻结面（读数与未定性项见 [`architecture/实现约定与验证现状.md`](architecture/实现约定与验证现状.md)）。

---

## 条目维护规则（ISSUE-P3-28 确立）

1. **新增条目必须附「核实时间点」与「核实方式」**：任何声称「某文件需要删除 / 某处没有消费方 / 某硬编码为 0」一类**关于代码库现状的前提**，都必须写明**何时、以何种手段**核实过。
   > 立规缘由：条目与代码库演进之间存在时间差，曾出现正文前提在开工时**已不成立**。
2. **开工前复核前提**：认领条目时先复核其正文前提（路径是否存在、行号是否漂移、消费方是否已出现）。前提已不成立的，**就地修正或显式标注**后再动手。
3. **行号一律视为「核实时刻的快照」**：正文内引用的 `文件:行号` 仅作定位提示，实现前须以真实内容为准。

---

## 优先级定义

|   等级   | 严重度与类型                                 |     处理原则     |
| :----: | -------------------------------------- | :----------: |
| **P0** | **阻断级 / 致命安全漏洞**（数据损坏、篡改未拦截、明文泄露、严重崩溃） | 立即停止其他工作优先修复 |
| **P1** | **高危缺陷 / 核心功能阻断**（权限失效、关键契约异常、核心流程失败）  |   最高优先级排期修复  |
| **P2** | **中危缺陷 / 协议互操作 / 性能瓶颈 / 测试有效性缺口**      | 正常排期，按批次连续解决 |
| **P3** | **低危项 / 进阶特性接线 / 代码整洁度 / 评估与体验优化**     |   渐进优化与特性补齐  |

---

## P0 阻断级问题（0 项）

> **暂无开放项**。

---

## P1 高危与核心功能问题（0 项）

> **暂无开放项**（`ISSUE-P1-495` 已于 §449 闭环归档，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)）。

## P2 中危缺陷与协议/测试缺口（8 项）

> 本批 6 条出自 [`records/三类隐蔽性故障排查报告_2026-10-06.md`](records/三类隐蔽性故障排查报告_2026-10-06.md)；`ISSUE-P2-499` 为该报告 F12 母条目与其三张子集台账（F03/F07/F11）合并后的单条登记。
> 另有 `ISSUE-P2-517` **不属该报告**，出自 2026-10-06 `ISSUE-P1-495` 收工时的真机五层走查读数（见本文件末条）。
> **优先认领**：`P2-496` ~ `499` 四条属报告判定的**「已触发但被掩盖」**（masked）——错误效果已产生或曾实际发作、且被零观测机制屏蔽；其余三条为 dormant（零错误效果、前瞻性风险）。

### ISSUE-P2-496：回前台 / 网络恢复远端探测通道整条空转（自败节流 + 提示位零消费者）

- **核实时间点**：2026-10-06；**核实方式**：实读 `ResumeSyncProbeCoordinator.kt:71-86`（`:81` **先**写 `_lastProbeAtMillis` → `:82` 才调 `probeRemote`）、`:102-108` / `:115-121` / `:127-133`（三处 `classify(lastProbeAtMillis = _lastProbeAtMillis.value)` 传的都是刚写入值）、`ResumeSyncProbePolicy.kt:16`（`MIN_PROBE_INTERVAL_MILLIS = 30_000L`）与 `:66-68`（`classify` 内 `shouldProbe` 恒假 → `Skipped`）、`:139-147`（`Skipped -> ""`）；全仓 grep `ResumeSyncProbeCoordinator` 仅 4 处（`MainApplication.kt:81/:148` 仅 `runCatching { initialize() }`、`AutoLockManager.kt:75` KDoc、`SyncCoordinator.kt:221` 注释），`notice` / `lastOutcome` / `clearNotice` **零订阅者**；`strings.xml:799-801` 三键仅被协调器 `:141/:143/:145` 自引＝死资源；实读 `SettingsRepository.kt:55` 与 `RealSettingsRepository.kt:106` 确认开关**默认 true**、呈现于 `SecuritySettingsScreen.kt:239`。
- **背景**：`maybeProbe` 在通过 30s 节流检查后**立即把时间戳刷新为当前时刻**，随后的 `probeRemote` 才拿这个刚写入的值去过同一道节流 ⇒ `shouldProbe` 恒判假 ⇒ 生产接线下 `Unchanged` / `RemoteChanged` 构造性不可达，只能产出 `Skipped`（`describeOutcome` 返回空串）。叠加第二重吞没：`notice` / `lastOutcome` 全仓无任何读取者 ⇒ 连 `Failed` 文案也永不浮出。用户侧观感是**设置页开关打开、回前台无任何反应、无任何错误提示**，而真实同步与 `SyncCoordinator.lastOutcome → SyncFailureNotifier` 告警通道不受影响（探测按设计只提示不写路径），故无数据 / 安全后果。
  - **触发状态：masked**（判据 E1 现行错误产出）——`MainApplication:148` 无条件 `initialize()`、开关默认 true、回前台挂点齐全，凡已配置云同步且解锁的会话每次回前台 / 网络恢复必触发探测，结果被双重确定性吞没，**吞没机制现下正在工作**。旁证：§361 提交（`07fd019c`）自留「五条 UI 真机行为未核对」。
  - 报告已核实并修正候选的两处失真：候选所称「恒报 `strings.xml:800` 一致」**不成立**（该字符串永不渲染）；引擎无 ETag 回退实际位于 `sync/.../SyncEngineSupport.kt:36-38`，非候选所指的独立文件 `RemoteConsistencyProbe.kt`。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/sync/ResumeSyncProbeCoordinator.kt`、`ResumeSyncProbePolicy.kt`、`app/src/main/res/values/strings.xml:799-801`。
- **验收标准**：
  ① 节流判据改在**写入时间戳之前**求值，或改由 `probeRemote` 持入本次探测的 `startedAt` 快照，使 `classify` 不再消费被自己刷新的值；
  ② `notice` / `lastOutcome` **接入真实消费者**（设置页或同步页展示探测结论），或按 PD-39/PD-40 口径连同死字符串资源一并移除——**不得保留「开关可拨、结论永不呈现」的形态**；
  ③ 补协调器级测试（现仅 `HostPolicyBatchTest.kt:130-134` 以 `classify(true, false, null, ...)` 盖纯函数，`null` 基线使节流必过、恰绕开本缺陷）；宿主可注入时钟断言「两次间隔 < 30s 的回前台 → 第二次产出 `Unchanged` 而非 `Skipped`」；
  ④ 真机走查前按 §434 跑 `check_installed_build.py --expect-symbol`，门禁 9/9 PASS。

### ISSUE-P2-497：云同步页「离线缓存」开关为零行为消费方死开关

- **核实时间点**：2026-10-06；**核实方式**：全仓 grep `useOfflineCache|use_offline_cache`（`--type kotlin/xml`）确认 `sync` / `database` / `core` / `crypto` 四模块**零消费**，命中仅 store 读写（`ExtendedSettingsStore.kt:64/:163/:380`）、数据类字段（`ExtendedSettings.kt:14` 默认 `true`）、UI 回显（`SettingsUiState.kt:108` / `SettingsUiStateProjection.kt:154` / `CloudSyncSections.kt:80`）与 setter（`SettingsExtendedPreferencesController.kt:145`）；`setOfflineMode` 全仓无生产调用方（仅定义 `SyncCoordinator.kt:198-199` + 守卫测试 `OfflineCacheNotEngineOfflineTest.kt:14-33` 的源码文本反断言）⇒ `SyncSessionState.kt:29` `isOfflineMode` 恒 false ⇒ `SyncCycleSetup.kt:122` / `SyncCycleRunner.kt:251` 恒 false；实读 `CloudSyncComponents.kt:57-63` 确认 `SyncSwitchItem` 签名**无 `enabled` 形参**（开关未禁用）；实读 `SettingsExtendedPreferencesController.kt` 确认 `syncCoordinator` 依赖（`:21`）在本文件内**仅此一处命中**＝残留未用；实读 `SyncEngine.kt:93/:223/:294/:344/:359-361/:423-425` 确认离线缓存语义为引擎**无条件内置**。
- **背景**：commit `2a0dd5d1`（2026-09-30）为修「首传永不联网」拆掉 `useOfflineCache → setOfflineMode` 误接后，开关变成零消费方——但 KDoc（`:139-143`）与副标题文案（`strings.xml:961`「断网时继续读写本地缓存，联网后自动同步」）仍以可用开关口吻书写，UI 仍可拨动、持久化并回显。承诺的行为实为引擎无条件内置（缓存先行 + 失败保底重试），故**开关两向均为 no-op**：ON 无增量、OFF 关不掉。该修复及其死开关现状**无任何文档登记**（`git show --stat 2a0dd5d1` 仅 6 文件、无 `docs/`；`docs/` 全库 grep 零命中）。
  - **触发状态：masked**（判据 E1 现行错误产出，兼 E2）——开关位于常显设置页（默认 true），每次拨动必经「落盘 + 回显」闭环使其看起来完全正常，而行为侧零读取，**虚假可供性被持续产出**并被持久化 / 回显通路掩盖；E2 方面 `2a0dd5d1` 提交信息自录该开关默认开启曾真实触发首传必败并误报。
  - 登记表核查：`PD-39` / `PD-40` 逐项裁定表均不含 `useOfflineCache`，`产品裁决登记.md:1806` 仅以「云同步页不拆分」口径提及离线缓存区块名，`已知工程限界.md` 无对应条目 ⇒ 不属已接受项。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsExtendedPreferencesController.kt`（`:21` 残留依赖 / `:139-146` setter 与 KDoc）、`app/src/main/res/values/strings.xml:961`、`ExtendedSettings.kt` / `ExtendedSettingsStore.kt` / `SettingsUiState.kt` / `SettingsUiStateProjection.kt` / `CloudSyncSections.kt` / `CloudSyncComponents.kt`。
- **验收标准**：
  ① 按 PD-39/PD-40 全链移除模式处置（数据类字段、偏好键、字符串资源、UI 行、controller setter 同批清干净），或引入真实可关断语义并**重写文案**——按 PD-50 第 2 条，**不得保留可拨动假象**；
  ② 顺带清理 `SettingsExtendedPreferencesController.kt:21` 的 `syncCoordinator` 残留未用依赖；
  ③ 既有守卫 `OfflineCacheNotEngineOfflineTest.kt` 须随处置同步改写（按测试资产纪律不得直接删除用例）；
  ④ 门禁 9/9 PASS。

### ISSUE-P2-498：「关于」页与设置主页展示的版本号恒为写死虚构字面量

- **核实时间点**：2026-10-06；**核实方式**：实读 `SettingsUiState.kt:249-250`（`val appVersion: String = "v1.0.0-Preview (2026 Edition)"` / `val buildNumber: String = "Build 2026.09.04"`）；全仓 grep `appVersion|buildNumber`（含隐藏文件，排除参考项目 / build / .gradle）确认项目源码仅 4 文件命中且**全为默认值 / 传参 / 渲染**，零赋值点、零测试引用；`SettingsUiStateProjection.kt` 内 grep 零命中、`:89` `initialValue = SettingsUiState(appLanguage = ...)` 走默认值；实读 `SettingsGroups.kt:255` 确认 `if (BuildConfig.DEBUG)` 只包住调试行、「关于」行在 `:268-277` 无条件 `add`（**release 同样渲染**）、`:274` 为副标题渲染点；实读 `app/build.gradle.kts:162` 确认真实 `versionName = "0.1.0"`（`:161` versionCode = 1）；`git log -S "VERSION_NAME" -- app/` 为空、`git log -S "v1.0.0-Preview"` 仅命中初始化提交 `47eee328` ⇒ **真实来源从未接线**。
- **背景**：字段名与渲染位宣称展示应用版本，实际是自初始化提交起从未接线的静态虚构值——「看似读真实版本、实为静态假读数」。已排查「Preview 品牌属有意取舍」的反假设：`产品裁决登记.md` 无任何版本品牌口径裁决条目，同文件 `ISSUE-P3-126③` 先例（`kdbxFormat` 静态字面量已删，立规「不得再写为静态字面量」）与 §66 批次（2026-09-15，`docs/resolved/batches/66-…md:73-74` 已把它登记为「未核查疑似死字段」）均按「静态字面量失真」定性。
  - **触发状态：masked**（判据 E1 现行错误产出）——投影链零赋值 ⇒ 每次设置页 / 关于页渲染必显错误字面量，**错误输出被持续产出、用户可感**，且全仓零测试引用无任何拦截。需如实说明：错值本身对用户是明示的，**被掩盖的是「与真实来源脱节」这一事实**而非值本身被藏。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiState.kt:249-250`、`SettingsUiStateProjection.kt:89`、`SettingsGroups.kt:274`、`subscreens/AboutSettingsScreen.kt:56-57`、`subscreens/AboutSettingsScreenSections.kt:48-49/86/94`、`app/build.gradle.kts:161-162`。
- **验收标准**：
  ① 改由真实来源接线（`BuildConfig.VERSION_NAME` / `VERSION_CODE`，或 `PackageManager` 读 `versionName`），投影链与 `initialValue` 均有赋值点；
  ② 若产品裁决为「Preview 品牌即期望展示」，须**改字段名与文案**使其不再宣称是版本读数，并按 `ISSUE-P3-126③` 先例在两登记表中登记该取舍；
  ③ 补宿主接线守卫测试（断言 `appVersion` 来自 `BuildConfig.VERSION_NAME` 而非字面量），并加跑 `python tools/audit/check_tautological_assertions.py`（防重言断言）；
  ④ 门禁 9/9 PASS。

### ISSUE-P2-499：原生探活失败静默回落零观测（母条目 · 含 cipher / 强度 / Passkey 签名三面）

> **本条为缺陷族唯一登记条目**：报告 F12 为母条目，F03（三个对称 cipher 引擎）/ F07（口令强度面）/ F11（Passkey 签名面）为范围子集，按同族去重裁决并入本条，不独立立项。
> **severity 裁决**：判 **medium**（取族内最大值）。母条目自身论据为 low（回落实现功能正确、等价性由 `CipherFallbackParityTest` 等锁定）；子集 cipher 面为 medium（ChaCha20 BC 回落 ≈44× 慢、Twofish 整库数据流走纯 Java）。两条论据均保留如下。

- **核实时间点**：2026-10-06；**核实方式**：grep `val available: Boolean by lazy` 于 `crypto/src/main/java` 确认**恰 7 处**（`NativeArgon2.kt:46` / `NativeAesKdf.kt:34` / `NativeAes.kt:60` / `NativeTwofish.kt:34` / `NativeChaCha20.kt:42` / `NativePasswordStrength.kt:28` / `NativePasskeySign.kt:44`）；grep `noteFallbackOnce|AppLog\.` 于同目录确认**唯一日志点**是 `NativeKdfFallbackLog.kt:27-31`，调用方仅 `AesKdfEngine.kt:35` 与 `Argon2KdfEngine.kt:85`；实读 `NativeCryptoLibrary.kt:29-36`（`by lazy` + `catch (_: Throwable) { false }`）与 `NativeTwofish.kt:34-57`（同型探活 + `:54-56` catch 吞）；实读 `PasswordStrength.kt:114-119`（`available` 真则原生、否则 `PasswordStrengthFallback`，**分支无日志**）与 `:106-107`（`nativeAvailable` 诊断位）；全仓 grep `nativeAvailable` 于 `*.kt` **仅命中定义行一处**＝零生产读者；实读 `PasskeyAssertionSigner.kt:30-34`（ES256 同型回落）；实读 `ChaCha20CipherEngine.kt:23-24` 性能 KDoc 与 `TwofishCipherEngine.kt:19-21`；**发作在案**实读 `BcProviderDeviceTest.kt:16-20`（2026-09-17 Redmi 4X 实测：`NativeTwofish.available` 探活 BC 对照抛异常被吞 ⇒ 恒 `false` ⇒ 原生 Twofish 被静默弃用）+ `docs/RESOLVED_LOG.md:183` §143 / `ISSUE-P2-92`；**前例**实读 `docs/resolved/batches/359-…md` §ISSUE-P3-392（「未接受静默降级」，AC 涉及文件仅列两个 KDF 引擎）。
- **背景**：七个原生探活一律 `by lazy` 一次性进程缓存，`catch Throwable` 吞掉即永久 `false`、无刷新机制——首次触碰时一次瞬时失败（缺 ABI、KAT 不匹配、环境性异常）就把「原生 = 关」钉死到进程结束。其中**五个内核**（强度 / AES-CBC / Twofish / ChaCha20 / Passkey 签名）探活失败回落 JCE/BC/JVM 时**零日志零运行时观测**，而两个 KDF 引擎已在 `ISSUE-P3-392` 批次接了一次性 `AppLog`——该批次 AC 明确「**未接受静默降级**」，故其余五面构成**同政策下的真实缺口**，非设计取舍。唯一诊断位 `PasswordStrengthEvaluator.nativeAvailable` 全仓零读者，「原生已生效」在生产运行时无任何可核对表面。
  - **触发状态：masked**（判据 E2 历史实际发作）——§143 / `P2-92` 即本机制在真机的实际发作（当时零运行时观测，仅设备侧硬断言事后揭出）；须如实限定：**该次的具体触发源（BC 抢占）已修**（`NativeTwofish.kt:102` 探活对照改用持有实例），且标准构建在参考设备上探活当前通过（§221 记真机四层全绿），故**当下并非处于失败态**——masked 指机制曾在真机触发且被掩盖，而非当前正在失败。触发状态判据 E2 成立（历史发作在案 + 造成不可见的机制至今未修：`catch (_: Throwable) { false }` 与五面零日志均在位），与 `ISSUE-P2-503` ~ `508` 类「声明侧残留但历史从未发作」的 dormant 条目不属同一形态。
  - severity 分层：**low 面**（母条目）——回落实现功能正确（等价性由 `CipherFallbackParityTest` / `TwofishNativeParityTest` / `CbcStreamFramingTest` 及强度面跨语言契约用例锁定），影响面是性能与可诊断性；**medium 面**（cipher 子集）——ChaCha20 BC 回落 ≈44× 慢（`ChaCha20CipherEngine.kt:23-24` KDoc 自记真机 2.6~2.7 vs ≈118 MB/s）、Twofish 作用于整库数据流走纯 Java；**注**：AES 面须区分——`已知工程限界.md` §17 已登记现代机上原生 AES 慢于 JCE（+20~39ms/10MiB，取向为审计一致性），故 AES 回落近似无损，其问题仅在偏离 PD-06 唯一裁决路径且不可观测。
  - **登记表核查**：`已知工程限界.md`（§16 只登记 RS256 恒走 BC、§15/§17 为 ChaCha20 JNI 拷贝与 AES 性能代价）、`产品裁决登记.md`（PD-06 裁定对称密码与 KDF 全部由 Rust 提供、PD-07 仅登记两套流语义等价）**均无接受项**；`ACTIVE_ISSUES.md` grep「探活|回落|392」零命中 ⇒ 不归入已知限界。
  - **顺带核实（须在整改时一并处理）**：`PasswordStrength.kt:7` 与 `:147` 引用的守卫类 `PasswordStrengthNativeParityTest` **全仓不存在**（真实守卫是 `PasswordStrengthTest.kt:182` 的跨语言契约用例，换名存在）——即 `ISSUE-P3-506`。
- **涉及文件**：`crypto/src/main/java/com/keepasskey/crypto/NativeCryptoLibrary.kt:29-36`、七个 `Native*.kt` 的 `available`（`NativeArgon2.kt:46-72` / `NativeAesKdf.kt:34-55` / `NativeAes.kt:60-91` / `NativeTwofish.kt:34-57` / `NativeChaCha20.kt:42-66` / `NativePasswordStrength.kt:28-44` / `NativePasskeySign.kt:44-53`）、三个 `cipher/*CipherEngine.kt`（`:50` / `:45` / `:45`）、`strength/PasswordStrength.kt:114-119`、`passkey/PasskeyAssertionSigner.kt:30-34` 与 `:57-61`、对照物 `kdf/NativeKdfFallbackLog.kt:27-32`。
- **验收标准**：
  ① 五个未留痕内核（强度 / AES-CBC / Twofish / ChaCha20 / Passkey 签名）的探活失败回落分支接入**一次性 `AppLog`**（复用 `NativeKdfFallbackLog` 的 `AtomicBoolean` 闸门模式，口径与 `ISSUE-P3-392` 已定裁决一致），且**日志不含 KDF 参数 / 密钥材料**；
  ② 补 `NativeKdfFallbackLogTest` 同款一次性闸门反校（重复触发只记一次）；
  ③ 顺带处置 `PasswordStrength.kt:7/:147` 的失效守卫指针（与 `ISSUE-P3-506` 合并同批整改）；
  ④ **原生面改动须四层 `connectedDebugAndroidTest` 真机实跑**（`:crypto:` / `:database:` / `:sync:` / `:app:`），执行前按 §263 确认设备无待保留数据或改用 AVD，前置 §434 `check_installed_build.py --expect-symbol`，跑完 `python tools/device/check_connected_device_results.py` 断言各层 `tests > 0`（`compileDebugAndroidTestKotlin` 通过**不构成**验证证据）；
  ⑤ 门禁 9/9 PASS，`gate_readings.py` 读数原样贴入批次文档 §3。

### ISSUE-P2-500：`WebDavUploadAtomic` 409/423 兜底重试仍携带 `If` 预条件，与 KDoc / 批次 AC 承诺相反

- **核实时间点**：2026-10-06；**核实方式**：实读 `WebDavUploadAtomic.kt:47`（`fun createMoveRequest(withPrecondition: Boolean = true)`）、`:53-55`（`if (withPrecondition && !expectedEtag.isNullOrBlank())` 才发 `If` 头）、`:56-58`（else 分支仅发 `Overwrite`）、`:70`（**唯一调用点** `execute(...) { createMoveRequest() }`，用默认参 ⇒ `withPrecondition=true`）、`:91-94`（`attempt == 0` 时 `delete(remotePath)` 后 `continue`）、`:72-81`（412 → `ConflictError` → `:104-107` 抛出）；全仓 grep `withPrecondition` 仅 `:47` 形参与 `:53` 条件两处，**无人显式传 false**；实读矛盾 KDoc `WebDavSyncProvider.kt:322-323` 与 `WebDavMoveOverwritePolicy.kt:13`，对照批次 `359` AC（`docs/resolved/batches/359-…md:22/98`「DELETE 目标（404 容忍）→ **无预条件** + `Overwrite:T` 单次重试」）；实读仓内 mock 语义 `StatefulMockServers.kt:180-184`（`parseIfEtag` 不匹配即 412）与 `:200`（DELETE 移除 etag）；实读唯一走兜底的测试 `WebDavSyncScenarioTest.kt:288`（**未传 `expectedEtag`** ⇒ 无 `If` 头，恰好绕开缺陷组合）；`git show 844fe715:…` 确认缺陷自 `ISSUE-P2-381` 落地即存在，而该提交信息自称「DELETE 目标后无预条件重试」。
- **背景**：409 / 423 兜底删掉远端目标后的重试**仍带 `If` 预条件**——在按 RFC 4918 评估 `If` 的服务器上必 412 转 `ConflictError`，兜底退化为「先删远端目标 → 必败」。终态**比不兜底更差**（不兜底是 409 原样报错、远端完好），且报错文案「远端已被其他人修改」与事实（客户端自删）不符。常规提交路径传非空 etag（`SyncEngine.kt:312` / `:371` / `:486`），下轮可经 404 自愈（`:209-219`）。
  - **触发状态：dormant**——该缺陷路径（非空 expectedEtag + 409/423 + 重试）在仓内从未执行过：`已知工程限界.md` §28 实测矩阵 mod_dav / nginx 均不产生 MOVE 覆盖 409/423（nginx 对 MOVE 的 `If` 整体忽略，带 `If` 的重试在该类服务器反而照常成功），唯一走兜底的测试因未传 `expectedEtag` 绕开。
  - **未核实项（整改时须补）**：「严格评估 `If` 的真实服务器」无实证样本（§28 三类未测样本 Nextcloud / IIS / S3 同样未覆盖该组合）——「重试必 412」系读 mock 语义 + RFC 4918 推导，**非执行结果**。
- **涉及文件**：`sync/src/main/java/com/keepasskey/sync/webdav/WebDavUploadAtomic.kt:47-70`、`WebDavSyncProvider.kt:322-323`、`WebDavMoveOverwritePolicy.kt:13`、`sync/src/test/.../WebDavSyncScenarioTest.kt:288`。
- **验收标准**：
  ① 重 attempt 显式传 `createMoveRequest(withPrecondition = false)`，使实现兑现 KDoc / 批次 AC 承诺；
  ② 补负向样本（MockWebServer，仿 `StatefulMockServers.kt`）：`expectedEtag` 非空 + 首次 409 + 断言**重试请求头不含 `If`**（判据须同时看请求头与结果码——静默降级的结果码常是「成功」）；
  ③ 同步修正 `WebDavSyncProvider.kt:322-323` 与 `WebDavMoveOverwritePolicy.kt:13` 的 KDoc 口径（若二者已正确则只改实现）；
  ④ `sync/src/androidTest/**` 有改动则真机实跑 + `check_connected_device_results.py`；门禁 9/9 PASS。

### ISSUE-P2-501：WebDAV 合并链上 MOVE 412 后元数据重探瞬时失败会剥除乐观锁，静默覆盖他端写入

- **核实时间点**：2026-10-06；**核实方式**：实读 `WebDavUploadAtomic.kt:73-75`（`val currentMeta = getMetadata(remotePath).getOrNull()` / `remoteEtag = currentMeta?.etag.orEmpty()`，**探测失败 ⇒ 空串**）、`:40-45`（`overwriteFlag` 判定）与 `:53-58`（else 分支无 `If` 头）；实读 `SyncEngine.kt:403`（`ConflictNeedsMerge(remoteBytes, ex.remoteEtag)` **无回填**）对照 `:330/:340`（`uploadEx.remoteEtag.ifEmpty { remoteEtag }` **有回填**）——同文件两种口径；实读 `SyncConflictAutoMerge.kt:23-24`（`cleanEtag(conflictMomentEtag).ifEmpty { null }`）与 `:131`；实读 `SyncEngine.kt:486`（`expectedEtag?.takeIf { it.isNotBlank() }`）与 `:466-470` KDoc（「用当前值会通过校验并静默覆盖他端的更新……禁止在本方法内回退重探充当预条件」）；对照批次 `272` AC①（空白回退前提是「无 ETag 服务器」）；实读 `WebDavSyncProvider.kt:183-185`（无 ETag 服务器返回成功 + 空 etag，与探测失败**同折空**）。
- **背景**：MOVE 412 后的元数据重探一旦**瞬时失败**，`ConflictError.remoteEtag` 即被折算为空串，经 commitLocal 冲突分支（无回填）原样流入合并上传，被 `conflictUploadExpectedEtag` 折为 `null`，最终以**无 `If` 预条件**的 `MOVE(Overwrite:T)` 覆盖远端——在支持 ETag 的服务器上，仅因一次探测抖动就剥除合并窗口（含一次 KDF 级全库序列化）的乐观锁，他端窗口内写入被静默覆盖。空串把「无 ETag 服务器」与「探测失败」两种成因混为同一口径，而后者场景下**基线本可得**（被合并的远端内容刚下载成功）。
  - **触发状态：dormant**——非按构造必经：需 MOVE 412 后 `PROPFIND` 连同 `executeTransientRetryable` 内部重试一起失败（单次抖动被吸收），随后 commitLocal 下载与存在性探测又成功（后者失败则 `overwriteFlag="F"` 意外 fail-closed），且合并窗口内他端再写一次；全仓 grep 无 `ConflictError(remoteEtag = "")` 测试样本（`SyncCoordinatorTest.kt:472` 只锁定 ETag 可得时的透传）⇒ **该形态零测试覆盖、无触发留痕**。
  - **报告已修正候选的一处失真**：「仅 WebDAV 受累」不准确——`S3SyncProvider` 未覆写 `uploadAtomic`（走接口缺省），空期望合并上传会 HEAD 自探当前值并 `If-Match` 之，即 `SyncEngine.kt:466` 点名的「重探当前值 ⇒ 静默覆盖」形态，合并窗口锁同样被剥；S3 残余暴露更小（探测失败 fail-closed、绝不裸 PUT），但结论应改为「**两家同源受累，WebDAV 为最重形态**」。
- **涉及文件**：`sync/src/main/java/com/keepasskey/sync/webdav/WebDavUploadAtomic.kt:73-75`、`sync/src/main/java/com/keepasskey/sync/engine/SyncEngine.kt:403`（对照 `:330/:340`）与 `:466-470`、app 侧 `SyncCycleCommitPaths.kt:147` / `SyncConflictAutoMerge.kt:23-24/:131`。
- **验收标准**：
  ① 空串回退**只允许**在「无 ETag 服务器」成立时生效——探测失败（`getMetadata` 为 `Failure`）须与「成功但 etag 为空」区分，不得同折 null；
  ② commitLocal 冲突分支补齐与 openRemote 一致的 `ifEmpty` 回填口径（先按 `SyncEngine.kt:466-470` KDoc 判定该回填是否与 `ISSUE-P1-275` AC① 冲突——若冲突，改在 `WebDavUploadAtomic` 侧让探测失败**显式失败**而非折空）；
  ③ 补 MockWebServer 负向样本：412 后 `PROPFIND` 持续失败，断言合并上传**携带 `If` 头**；
  ④ 复核 S3 侧同源面并给出与 WebDAV 一致的处置；
  ⑤ 门禁 9/9 PASS。

### ISSUE-P2-502：`RUST_TOOLCHAIN` workflow env 零消费（改 env 静默无效的装饰性控制点）

- **核实时间点**：2026-10-06；**核实方式**：全仓 grep `RUST_TOOLCHAIN` 确认命中仅 `.github/workflows/build.yml:59`（定义）`:236`（注释）与一份文档提及，**零消费点**；实读 `build.yml:237/:474/:552` 三处 `toolchain: "1.97.1"` 字面量为真实钉版点；实读 `:50` 块注释「构建工具链固定版本（禁止漂移）」与 `:18` 头注释「Rust 全部在 env 中写死，禁止漂移」；实读 `:240-245` 打印步骤仅 `rustc --version` / `cargo --version` / `java -version`，**无比对断言**；`ls crypto/src/main/rust/` 确认**无** `rust-toolchain.toml`（故 workflow 字面量即 CI 唯一 Rust 固定点）；另两份 workflow（`codeql.yml` / `dependency-scan.yml`）不装 Rust。
- **背景**：同 env 块内 `ANDROID_NDK_VERSION` / `CARGO_NDK_VERSION` / `CARGO_DENY_VERSION` 均经 `run:` 内 shell 展开真实消费，唯 `RUST_TOOLCHAIN` 是装饰物；而 `:18` 与 `:236` 两处注释都在向维护者**承诺一个不存在的控制点**，`:236` 甚至把「人工保持一致」写成义务却不设机检。危害方向：未来升级 Rust 时三处注释都会引导改 env ⇒ CI 静默继续安装旧版本（「可审计」打印步骤只打印、无断言报红），形成**无声 no-op 升级**；且 3 处字面量互无机检，部分升级漏改会造成 job 间工具链分叉并全绿。`:236` 给出的理由「`with:` 不适于引用 workflow env」经 GitHub 官方 contexts 文档证伪（`jobs.<job_id>.steps.with` 可用上下文含 `env`）。
  - **触发状态：dormant**——当前 env 值与 3 处字面量同为 `1.97.1`、零漂移；git `-S` 全史显示 env 与字面量同笔引入、env 从未被消费亦从未被单独改动，「改 env 期望生效而被静默无视」的实机事件从未发生。
  - **未核实项**：`${{ env.RUST_TOOLCHAIN }}` 在 dtolnay action 下的可用性依据官方 contexts 文档**可用性表**（报告方 WebFetch 实读），**未实跑 GitHub Actions runner 验证解析**。
  - 登记表核查：两表 grep 零命中 ⇒ 不属已接受项。
- **涉及文件**：`.github/workflows/build.yml:18` / `:50` / `:59` / `:236-245` / `:474` / `:552`。
- **验收标准**：
  ① 二选一并使代码与注释同向——**接线**（`toolchain: ${{ env.RUST_TOOLCHAIN }}` 替换 3 处字面量）或**承认字面量为唯一事实源**（删 env 与 `:18` / `:236` 的失真注释）；本仓 `dependency-scan.yml:19-20` 已有「此处不复述条目数」的正确先例可仿；
  ② 若保留 env，则把 `:240-245` 的打印步骤升格为**断言**（`rustc --version | grep -q "$RUST_TOOLCHAIN"`），使漂移能变红；
  ③ 门禁 9/9 PASS。

### ISSUE-P2-517：`AppLogDeviceTest` 把「ROM 保留 v 级 logcat」当平台不变式——丢弃 v 级的真机上确定性假红

- **核实时间点**：2026-10-06；**核实方式**：真机 `a7eb933d`（M332BF / Android 17 / MIUI）跑 `:core:connectedDebugAndroidTest` **2/2 次**均得 `tests=4 failures=1`，肇事者恒为 `AppLogDeviceTest#debugEnabled 为 true 时 v 级别真实落 logcat`（`java.lang.AssertionError: debug 开启时 v 级别必须输出（标记行未出现）`，`AppLogDeviceTest.kt:93`）；**受控探针（与本应用无关）**逐级写入并回读：`adb shell "log -t P1495Probe -p <lvl> probe-<lvl>-level"` → `adb shell "logcat -d -s P1495Probe:v | grep -c probe-<lvl>-level"` ⇒ **v = 0，d / i / w / e 各 = 1**；`getprop persist.log.tag` 与 `log.tag.P1495Probe` 均**空**（无逐 tag 覆盖）。同批其余层读数：`:crypto:` 37/37、`:database:` 18/18、`:sync:` 25/25 全绿。
- **背景**：用例（`AppLogDeviceTest.kt:87-96`）断言「`debugEnabled = true` 时 `AppLog.v` 必须真实落 logcat」，其 KDoc 亦自述覆盖「v/d 的 `debugEnabled` 门控（**开 → 落**）」。但「v 级是否被保留」是 **ROM 的 logd 配置**（verbose 可被平台整体丢弃），**不是** Android 的契约不变式——受控探针已证本机在**无关本应用**的 shell 写入路径上同样丢弃 v 级 ⇒ 该断言把平台能力当产品行为，属**测试有效性缺口**。
  - **产品侧不成立（须一并写明，防误读）**：`AppLog.v` 实现为 `if (debugEnabled) safe { Log.v(tag, message) }`（`AppLog.kt:29-31`），是**正确调用平台 API**，本条**不得**读作「日志门控失效」或「AppLog 缺陷」。
  - **触发状态：已实际发作**（确定性 2/2，非偶发）——后果是 `:core:` 层在此类设备上**永远无法取得全绿读数**，而该层恰是「`AppLog` 平台行为只有真机可证」的唯一出口（AGENTS.md §5 命令表）；长期假红的直接代价是**训练维护者忽略该层的红**（与 `ISSUE-P3-305`「闸门存在 ≠ 闸门被执行」同族的信噪比问题）。
  - 同类先例：`ISSUE-P2-490`（设备侧判据把「峰值下界 `2D`」当「实际峰值」，跨堆界翻转）已按「设备侧判据前提缺陷」定 P2，本条目同理。
  - 登记表核查：`已知工程限界.md` / `产品裁决登记.md` 两表 grep `logcat|v 级|verbose` **零命中** ⇒ 不属已接受项；`docs/architecture/实现约定与验证现状.md` 的厂商冻结面段落（`ISSUE-P2-492` 收口）只覆盖 `:app:` 层**整层冻结**，不覆盖本条的**单条断言级**前提失配。
- **涉及文件**：`core/src/androidTest/java/com/keepasskey/core/log/AppLogDeviceTest.kt`（`:18-21` KDoc 覆盖声明 / `:43-46` `dumpLogcat` / `:87-96` 肇事用例）。
- **验收标准**：
  ① 用例**前提自探**：先判定「本机是否保留 v 级」（本批受控探针即可复用），不保留时**不得**把平台行为判作产品缺陷；优先按 `ISSUE-P2-494`「用例内自备」先例做到**在本类真机上真实通过**——可行落点示例：把**可观测通道由 `v` 改为 `d`**（本机实测 d 保留 = 1：`debugEnabled=true` ⇒ `AppLog.d` 落盘、`false` ⇒ 不落，门控在真实平台上仍可证），并把「v 级是否被平台保留」降为**读数**（打印）而非断言；
  ② `debugEnabled = false` 的负向判据须标注「在丢弃 v 级的平台上**恒真、无鉴别力**」，避免以噪声充当覆盖；
  ③ 真机复跑 `:core:connectedDebugAndroidTest` 得 `failures = 0`（须记录设备 / ROM，作为本条闭环证据）**且** `python tools/device/check_connected_device_results.py` 断言 `tests > 0`；
  ④ 门禁 9/9 PASS。

## P3 低危问题、特性接线与体验优化（14 项）

> 本批出自 [`records/三类隐蔽性故障排查报告_2026-10-06.md`](records/三类隐蔽性故障排查报告_2026-10-06.md)（触发状态均为 **dormant**——零错误效果被产出，缺陷以声明侧残留 / 守卫缺口 / 前瞻性风险形态存在）。按 AGENTS.md 规则 6，可按批连续解决。
> **共性说明**：本批多数条目属「**在案声明被违反但产物零影响**」或「**守卫缺口**」两类。它们与 `P2-496` ~ `499` 的 masked 条目形态不同——后者错误效果已产生或曾实际发作，前者仅误导未来维护者或在未来条件下才发作。

### ISSUE-P3-503：native-gate 导出符号断言的注释散文称「11 个」而实际清单为 13 个

- **核实时间点**：2026-10-06；**核实方式**：实读 `.github/workflows/build.yml:279`（「恰好 **10** 个」）、`:283-284`（「2026-09-19 ISSUE-P3-187 追加直扣探针后为 **11** 个……即下方 expected 清单」）、`:298-314`（逐项清点 **13** 项：1-10 基线 + `:310` ChaCha20 Direct + `:312-313` AES Direct ×2）、`:318`（`if actual != expected:` 逐名比对 fail-closed）、`:324`（成功输出动态打印 `len(expected)`，实会打印「各 13 个」）；`git blame` 确认 `:283` 末次修改为 `baaf8319`（P3-187 批，当时清单确为 11），`:312-313` 由 `947ccf07`（P3-198 §215 结案）加入且**未触碰 `:283`** ⇒ 失真由该提交按构造引入；`RESOLVED_LOG.md:263`（§212「符号契约 CI 10→11」）与 `:268`（§215「11→13」）与时间线一致。
- **背景**：散文声称「11 个 = 下方清单」但清单实为 13 项，**该失真已穿越后续多次 `build.yml` 改动未被察觉**（含 `56b1d3cb`「去除写死的门禁条数」——同一「不承载可变事实」政策在 `AGENTS.md` 落地却未扫到此处）。**断言本体按逐名集合相等核对、fail-closed、成功输出打印动态正确值**，故失真只造成误导 / 返工风险，**不会静默削弱门禁**——若有人按散文把清单「修正」回 11 反而会令真闸门变红。同型先例：`dependency-scan.yml:19-20` 头注释自供过一次「声明漂亡」并已改为只声明纪律。
  - **触发状态：dormant**——散文不参与判定，错误效果为零。
  - 机检覆盖情况：`gate_readings.py` 只解析 `hygiene-gate` 段命令清单，CI 注释散文不在任何闸门口径内。
- **涉及文件**：`.github/workflows/build.yml:279` / `:283-284`。
- **验收标准**：删除或改写 `:279` / `:283-284` 的写死计数（改动态表述「以下方 expected 清单为准」，仿 `dependency-scan.yml:19-20` 口径），使散文不承载可变事实。

### ISSUE-P3-504：`jni_bridge_ext.rs` 的 `applyKeystreamDirect` 契约注释称「非生产路径」而它已是生产热路径

- **核实时间点**：2026-10-06；**核实方式**：实读 `jni_bridge_ext.rs:413-425`（第 4 条 `:425`「**非生产路径**：仅供 AC② 的真机对比探针调用；生产 `ChaCha20CipherEngine` 不经此函数」，`:413` 亦称「零拷贝**探针**」）；实读同文件 `:27` 文件头索引「(§187 探针 / §198 生产化)」与 Kotlin 侧 `NativeChaCha20.kt:91` KDoc「`ISSUE-P3-198` 起为**生产路径**」——两处在案正确声明与 `:425` **自相矛盾**；`git log -S "非生产路径"` 仅命中 `baaf8319`（探针原型时期），§215 结案提交 `947ccf07` 改了同文件（新增 `:27` 索引与 AES 直扣段）但**未改 `:425`**；实读生产链 `CipherFactory.kt:14` → `KdbxFile.kt:207/455` → `ChaCha20CipherEngine.kt:332`（refill，64KB 块）→ `NativeChaCha20.kt:123`。
- **背景**：KDBX 库采用 ChaCha20 加密时，整库解密流**每 ≤64KB 块必经此 JNI 导出**——「生产不经此函数」这一命题在每次解密时被构造性证伪（须注意限定：**AES / Twofish 加密的库不经此函数**）。危害是前瞻性的：后续维护者按该注释把 direct 导出当「探针专用」改动 / 移除，或把「擦除责任上移」等契约差异当探针专属豁免，会静默破坏解密主路径。
  - **触发状态：dormant**——失真注释未产出任何错误效果（代码行为正确，他处在案正确声明在位、可交叉印证纠正）。
- **涉及文件**：`crypto/src/main/rust/src/jni_bridge_ext.rs:413-425`（尤以 `:425` 为准）。
- **验收标准**：`:413-425` 的探针定性改写为生产路径口径（并与 `:27` 文件头索引、`NativeChaCha20.kt:91` KDoc 三处一致）；**不改任何代码**。

### ISSUE-P3-505：material3 双声明的钉版效力寄生于 Gradle 最高版本胜出，`libs.versions.toml:46` 的约束无机检承载

- **核实时间点**：2026-10-06；**核实方式**：实读 `app/build.gradle.kts:273`（BOM 托管无版本）与 `:284`（显式 alpha）并存、`:274` 注释自述「显式覆盖 BOM 映射——受控保留 alpha」、`:281-283` 退出条件；实读 `gradle/libs.versions.toml:103` / `:105`（别名同 `group + name`）与 `:49`（`material3 = "1.5.0-alpha28"`）、`:46`（约束「仅允许 1.5.0-alphaN 内部上调」）；全仓 gradle 文件无 `resolutionStrategy.force/strictly`；实读 `DependencyResolutionDeterminismTest.kt:34`（用例仅拦动态 / 快照版本）与 `:22-29` KDoc 自陈覆盖边界，`tools/` 与五模块 `src/test` grep `compose-bom` **零命中**；触发向量：`.github/dependabot.yml:15` `interval: "weekly"` 覆盖版本目录，git 史 `d53fc93e` / `06341f92` 证明 Dependabot 实际在动这两项。
- **背景**：`toml:46` 的约束读作受控策略，**实际效力仅是版本排序出价**——未来 BOM 的 material3 映射一旦越过钉版即静默改写实际版本。且 **`:284` 并非死开关**：报告实跑 `dependencyInsight` 确认当前解析恰为受控目标 `1.5.0-alpha28`（BOM 映射 1.4.0），双声明与退出条件由 `退役依据承接-ISSUE-P3-09.md:32` 有意登记 ⇒ 属「护栏脆弱」而非「已失效」。
  - **触发状态：dormant**——当前配置（`composeBom=2026.09.00`，映射 1.4.0 < 钉版）下翻转不可达。
  - 报告如实修正：①「静默」仅相对钉版行与门禁——翻转必经 Dependabot PR 且 `libs.versions.toml` 的 `composeBom` 行必有可评审 diff；②最可能触发形态（BOM 映射 1.5.0 stable）恰是已登记退出条件、属良性结果。
- **涉及文件**：`app/build.gradle.kts:273-284`、`gradle/libs.versions.toml:46` / `:49` / `:103` / `:105`、`app/src/test/java/com/keepasskey/app/security/DependencyResolutionDeterminismTest.kt:33-58`。
- **验收标准**：在 `DependencyResolutionDeterminismTest` 补一条断言——「BOM 声明的 material3 版本**不得高于** `libs.versions.toml` 登记的钉版值」，使 `toml:46` 的约束有真实执行点（当前零翻转即绿）。

### ISSUE-P3-506：口令强度 KDoc 两处指向不存在的守卫类 `PasswordStrengthNativeParityTest`

- **核实时间点**：2026-10-06；**核实方式**：全仓 grep `PasswordStrengthNativeParityTest` 确认仅 3 处命中——`PasswordStrength.kt:7` 与 `:147` 两处 KDoc，加 `docs/security/退役审计承接-42-威胁建模与架构评估.md:74` 的表格引用（**第三处，候选原话遗漏**）；glob `*PasswordStrength*` 确认无同名测试文件；`git log --all -S` 仅 `9eece18c`（同时新建 `PasswordStrength.kt` 与 `PasswordStrengthTest.kt`）⇒ **指针自诞生即空、非重命名漂移**；实读真实守卫 `PasswordStrengthTest.kt:182`（跨语言契约用例）与 `:203` 逐标志断言；两侧契约本体已核对一致（`PasswordStrength.kt:14-38` ↔ `strength.rs:44-60` 逐位相同；两份 `COMMON_PASSWORDS` 均 33 条逐条一致）。
- **背景**：按名检索守卫必扑空、会误判「契约无守卫」。**且 `:147` 的失真比「指针失真」略重**：它声称锁定的「`COMMON_PASSWORDS` 集合成员一致」在全仓**没有任何等价守卫**（该降级词表是 `private`，测试无法直取；grep `COMMON_PASSWORDS` 于 `crypto/src/test` 与 `androidTest` 零命中），只有 `PasswordStrengthTest.kt:117-133/:140-151` 的**样本级**行为覆盖——当前两侧 33 条逐条一致故未成灾，但成员若在样本外漂移将无测试拦截。`:7` 的位值契约则是「真守卫换名存在」+ CI 零跳过断言（`build.yml:259`）兜底。
  - **触发状态：dormant**——两侧契约已逐位 / 逐条核对一致，当前无任何错误效果被产出；危害是前瞻性的（未来维护者按名检索扑空而误判契约无守卫）。
  - CI 兜底：该 job 内跳过数必须为 0，故 `Assume` 不会在 CI 上静默吞掉守卫。
- **涉及文件**：`crypto/src/main/java/com/keepasskey/crypto/strength/PasswordStrength.kt:7` / `:147`、`docs/security/退役审计承接-42-威胁建模与架构评估.md:74`。
- **验收标准**：三处指针统一改指真实守卫 `PasswordStrengthTest.kt:182`（或按 `ISSUE-P2-499` AC③ 顺带处置）；**并补一条集合成员一致性守卫**——两侧 `COMMON_PASSWORDS` 须可被测试读取（如提升可见性或加测试专用访问口），使 `:147` 的承诺有真实执行点。

### ISSUE-P3-507：`proguard-rules.pro` 的 OkHttp keep 规则指向不存在的方法，恒不匹配

- **核实时间点**：2026-10-06（报告方实测；本轮登记仅实读规则原文，javap 已由报告方留痕）；**核实方式**：实读 `app/proguard-rules.pro:49-51`（`-keepclassmembers class okhttp3.internal.publicsuffix.PublicSuffixDatabase { native byte[] findSuffix(java.lang.String[]); }`）与 `:22`（`-dontnote **`）、`:4-7` 文件头「据实最小保留」自立原则；实读 `app/build.gradle.kts:218`（`isMinifyEnabled = true`）与 `:222-225`（`proguardFiles(..., "proguard-rules.pro")`）确认 R8 必吃本文件；报告方已解包实际解析的 `okhttp-android 5.5.0` AAR 实测：`javap -p` 输出成员**无任何 native 方法、无 `findSuffix`**，官方 sources 对应文件 grep `native|external|findSuffix` 零命中；全仓 `rg "PublicSuffixDatabase|findSuffix"`（排除参考项目）**仅命中规则自身**；`git log -S "findSuffix"` 仅 `b0cdc898`（初版即无依据注释）。
- **背景**：本仓实际解析的 okhttp-android 5.5.0 中 `PublicSuffixDatabase` 无任何 native 方法，该规则**恒不匹配、静默 no-op**，违反文件头自立原则；同型缺陷 `ISSUE-P3-98`（规则 12 签名写错永不匹配）已闭环并由 `AppLogProguardRuleTest` 锁定守卫，而本规则**无任何守卫测试**。
  - **后果面**：无运行期影响——该类无反射消费方、AAR 自带 consumer 规则也不需要它，`-keepclassmembers` 成员模式不匹配时既不保类也不保成员，对产物零影响 ⇒ 属「误导性假配置」而非功能缺陷。
  - **触发状态：dormant**——规则恒不匹配但未产出任何错误效果。
  - **未核实项（如实声明）**：`:22` `-dontnote **` 是否吞掉 R8 未匹配规则提示**未做 R8 A/B 构建证实**（no-op 的核心结论由 javap + 源码 grep + 零反射消费方三面独立证实，不依赖该行）。
  - 登记表核查：两表 proguard / 混淆 / keep / okhttp 关键词仅命中「OkHttp IP 字面量不经 Dns」两条无关项。
- **涉及文件**：`app/proguard-rules.pro:49-51`、`app/src/test/java/com/keepasskey/app/log/AppLogProguardRuleTest.kt`（守卫缺口参照）。
- **验收标准**：删除该 no-op 规则（或按实际存在的成员改写），并**同批扩展 `AppLogProguardRuleTest` 锁定剩余 keep 规则的类名 / 签名在依赖实物中真实存在**（照 ISSUE-P3-98 的守卫模式）；release 规则改动须跑 `.\gradlew.bat assembleRelease` 验证 R8 真实接受。

### ISSUE-P3-508：`dependency-scan.yml` 仍传 `-Dorg.owasp.dependencycheck.nvd.api.key`，init 脚本明文不消费

- **核实时间点**：2026-10-06（报告方已做插件 jar 全量字符串扫描；本轮登记实读两侧文件）；**核实方式**：实读 `.github/workflows/dependency-scan.yml:91-93`（`if [[ -n "${NVD_API_KEY:-}" ]]; then args+=("-Dorg.owasp.dependencycheck.nvd.api.key=${NVD_API_KEY}"); fi`）；实读 `.github/dependency-check.init.gradle.kts:59-61`（「注意：不使用 workflow 的 `-D…nvd.api.key` 系统属性传参……统一以环境变量为唯一事实源」）与 `:62-69`（唯一消费点 `nvd { System.getenv("NVD_API_KEY") … }`）；全仓 grep 该属性名**仅命中上述两文件**；报告方对 `:19` 钉死的 `dependency-check-gradle-13.0.0.jar` 做全量 class 字符串扫描，该属性名及片段 `api.key` 均**零命中** ⇒ 「未验证」实为「确证不生效」；git 考古确认 `-D` 行源自 `TASK-20 c5307613`，`TASK-51 84b7b5e1` 提交信息明言「弃用未验证的 -D 系统属性传参」但**同提交未删该行**。
- **背景**：该 `-D` 传入通道是彻底的死接线（假开关），系 `TASK-51` 弃用声明时的残留。两点补充：① CI 行为正确**并非碰巧**——同一 env 变量（`:86-87`）同时喂 workflow 守卫与 init 脚本读取，`-D` 仅为惰性冗余（env 通道恒先兜住，`batches/23-…md:42-45` 实证 run 34477320673 走 `NvdApiDataSource.processApi`）；② 残留通道还附带把 secret 放上命令行（进程表 / 命令回显面，GitHub 日志掩码不覆盖本地复现场景），**删除优于修复**。
  - **触发状态：dormant**——判定链与产物均无任何错误效果被产出，「仅 `-D` 无 env」的误导场景在 CI 按构造不可达。
  - **未核实项**：报告方仅做字节码常量字符串扫描、未反编译，亦未做真实 Gradle 联跑（按名取系统属性必须以字符串常量传给 `getProperty`，且全串与片段均零命中，动态拼接同样被排除）。
- **涉及文件**：`.github/workflows/dependency-scan.yml:91-93`、`.github/dependency-check.init.gradle.kts:59-69`。
- **验收标准**：删除 workflow 侧该 `-D` 行（保留 env 通道为唯一事实源），并同步 init 脚本 `:59-61` 的注释（去掉「未验证」的悬置表述）；本地复现路径不得再把 `NVD_API_KEY` 放上命令行。

### ISSUE-P3-509：主屏预览导出白名单按前缀匹配，改名 / 删除后静默 0 命中且无逐屏断言

- **核实时间点**：2026-10-06；**核实方式**：实读 `app/build.gradle.kts:356`（`val mainScreenPreviewPrefixes = listOf(`）并**程序化清点得 20 项**、静态核对 20/20 前缀（去 `ScreenshotExport` 后缀）均命中 `app/src/main/java` 真实预览函数（`missing_count = 0`）；实读 `:396-397`（`include("**/${prefix}_*.png")`，**0 命中不报错**、Sync 仍成功）、`:403-408`（`doLast` 仅 `light` / `dark` 计数并 `logger.lifecycle`，**无逐屏命中断言**）、`:420-421`（次要任务用同一清单 `exclude`——改名的主屏会静默漏入 `secondary`，二次掩盖）；`tools/export_previews/generate_screenshot_test_wrappers.py:122` 包装名由预览函数名派生，与 Gradle 清单**仅靠字符串约定耦合、无同步校验**；实读 `.github/workflows/build.yml:77` hygiene-gate 名称清单确认**不含**预览白名单检查；`export-preview-main.bat:42` 仅 `if exist`；`git log -S "mainScreenPreviewPrefixes" --all` 仅 2 提交（`RESOLVED_LOG` 无漂移条目）。
- **背景**：白名单预览改名 / 删除后该屏静默 0 命中，导出任务与全仓任何脚本 / CI 门禁均不校验逐屏命中数 ⇒ 主屏截图导出管道可静默退化。**当前接线完好**（20/20 静态匹配、全历史无漂移事故）。
  - **触发状态：dormant**——当前 include 模式全部命中、无错误效果产出；触发条件为未来任一白名单预览被改名 / 删除（此时 `:app:compileDebugScreenshotTestKotlin` 仍绿、包装照常生成，只有 Gradle include 模式失配，全链路静默）。
  - **未跑项**：未实际执行 `:app:exportMainPreviewScreenshots` / `updateDebugScreenshotTest`（会写 `build/` 与 `preview-exports/`）——「当前完好」判据是静态函数名核对 + §98 / §438 批次文档产物命名旁证。
- **涉及文件**：`app/build.gradle.kts:356` / `:396-397` / `:403-408` / `:420-421`、`tools/export_previews/generate_screenshot_test_wrappers.py`。
- **验收标准**：`doLast` 补**逐屏命中数断言**（每个 prefix 的 light + dark 各须 ≥1，缺任一即 `throw GradleException`），使「改名即变红」；或按 §98 既有做法把 20 这个数变为**程序化派生**（从真实预览函数名生成清单）而非人工维护。

### ISSUE-P3-510：S3 目录列举在缺 `NextContinuationToken` 时破坏 `RemoteListPage` KDoc 不变式

- **核实时间点**：2026-10-06；**核实方式**：实读 `sync/src/main/java/com/keepasskey/sync/s3/S3DirectoryList.kt:86-87`（`nextCursor = if (parsed.isTruncated) parsed.nextToken else null, truncated = parsed.isTruncated`）与 `:155`（`nextToken?.takeIf { it.isNotBlank() }` 连空白 token 也归 null ⇒ **矛盾对确定性产出**）；实读 `RemoteListModels.kt:26`（「`truncated` 为 `true` 时 `nextCursor` 非空」）；实读 `RemoteBrowseDialog.kt:291`（`if (truncated)` **无条件**渲染「加载更多」，`RemoteBrowseEntriesList` 仅接收 truncated、无 `nextCursor` 守卫）；实读 `RemoteBrowseSection.kt:59` → `SettingsViewModel.kt:132-136` → `SettingsRemoteBrowseHost.kt:85-88` 原样透传 → `RemoteBrowseController.kt:169`（以 null cursor 重列）与 `:188`（`if (cursor == null) accumulated = …` **累积清回第 1 页**、`lastError = null` 无报错）；对照 `WebDavDirectoryList.kt:87`（`nextCursor = if (truncated) pageItems.lastOrNull()?.name else null` 末项名兜底——**唯独 S3 信任服务器合规**）；测试目录 grep `IsTruncated` / `S3DirectoryList` / `parseListObjects` **零命中**。
- **背景**：矛盾对按构造产出（服务器回 `IsTruncated=true` 缺 / 空 token 即触发），UI 把破坏放大成「加载更多」空转 + 累积回缩、**全程无错误提示**。仅影响远端浏览通道，WebDAV 侧按构造免疫，同步数据路径不走此面。
  - **触发状态：dormant**——AWS ListObjectsV2 规范规定 `IsTruncated=true` 必带 `NextContinuationToken`，合规服务器不可达；引入提交 `a7850648`（§362）至今 git 历史 / `RESOLVED_LOG` / `ACTIVE_ISSUES` / 测试目录均无触发痕迹。
  - **未核实项（如实声明）**：①端点由用户任填，**无法从仓内排除**某个非规范 S3 兼容实现触发此路径，且 §362 批次自述「WebDAV/S3 真实服务器浏览未端到端联调」；②仓内无任何测试锁定此退化对的行为。
- **涉及文件**：`sync/src/main/java/com/keepasskey/sync/s3/S3DirectoryList.kt:86-87` / `:155`、`sync/.../RemoteListModels.kt:26`、app 侧 `RemoteBrowseDialog.kt:291` / `RemoteBrowseController.kt:169/:188`。
- **验收标准**：`nextCursor` 与 `truncated` 的取值须满足 KDoc 不变式（`truncated = nextCursor != null`，或在 token 缺失时降级 `truncated = false` 并给一次告警）；补宿主用例锁定 `IsTruncated=true` + 缺 / 空 token 的组合行为，并断言 UI 不再渲染可点的空转「加载更多」。

### ISSUE-P3-511：CM 通道 `EXTRA_ORIGIN` 写入侧无任何读取点（整改后有意收口残留）

- **核实时间点**：2026-10-06；**核实方式**：实读 `CredentialCreateEntries.kt:60`（`putExtra(PasskeyCreateActivity.EXTRA_ORIGIN, callingOrigin)`）；实读 `PasskeyCreateActivity.kt:100-103`（只读 `EXTRA_RP_ID` / `EXTRA_USER_NAME` / `EXTRA_USER_DISPLAY_NAME` / `EXTRA_CHALLENGE` 四个缓存 extra）与 `:136-139`（`origin = CallingOriginResolver.resolveTrustedOrigin(injected.callingAppInfo, privilegedBrowserStore.allowlistJson())`）；实读 `PasskeyCreateActivity.kt:433-438` 常量 KDoc 明写「组装期 origin 副本。**已无任何读取点**（`ISSUE-P2-199` 起 origin 一律由本次系统背书的 `CallingAppInfo` 重新派生）；保留常量仅为兼容既有设备侧匹配键用例……与将来可能的展示需求」；`git show 4d17f591` diff 确认 `- origin = intent.getStringExtra(EXTRA_ORIGIN).orEmpty()` 的删除正是 `ISSUE-P2-199` 整改本体；全仓 grep 该常量仅定义处、本写入处、断言侧同值常量（`PasskeyAssertionActivity.kt:283`，其读取点 `PasskeyAssertionRequest.kt:45` 仅由 `PasskeyAssertionActivity.kt:77` 调用、服务断言流）与 `androidTest` 自建 Intent 的 `PendingIntentMatchKeyDeviceTest.kt:52/55`（不依赖生产写入）；`BaseCredentialActivity.kt` grep `intent|extras` **零命中**，无通用 extras 读取。
- **背景**：死通道属实（写入侧向已断线的一侧持续发射），但**不是被掩盖的故障**——读取点删除是 `ISSUE-P2-199` 整改本体，接收端 KDoc 明文声明无读取点及保留理由，历史事故（陈旧 origin 覆写致 DAL 门控整段跳过）已结案。严重度 low 恰当：零功能影响、无信息损失（写入值与接收端现场派生值同源，`KeePasskeyCredentialProviderService.kt:273-279` 亦走 `CallingOriginResolver`），仅剩「写者侧无注释、易误导后续维护者复活读取点」的卫生问题。
  - **触发状态：dormant**——`putExtra` 本身每次创建条目组装都会执行（代码路径是活的），不可达的是其效果：唯一消费者已按 fail-closed 设计删除、删除此事在接收端 KDoc 有在案声明。
  - 两份架构登记表均**未**登记此条 ⇒ 若团队裁定「KDoc 已声明的有意残留属正常设计」，可改判 `holds=false` 并补登限界表。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/passkey/CredentialCreateEntries.kt:60`、`PasskeyCreateActivity.kt:433-438`。
- **验收标准**：二选一——删除 `CredentialCreateEntries.kt:60`，或**在写者侧补指向性注释**（说明该 extra 已无读取点、保留原因为对齐断言侧匹配键），使两侧 KDoc 自洽。

### ISSUE-P3-512：`SettingsUiState.debugLogRecordsCount` 自引入即零消费的死状态字段

- **核实时间点**：2026-10-06；**核实方式**：实读 `SettingsUiState.kt:246`（`val debugLogRecordsCount: Int = 128,`）；全仓 grep（含隐藏文件、排除 `.git`）**唯一命中即声明行**；实读 `SettingsUiStateProjection.kt:263-266`（`buildSettingsUiState` 全部命名实参中**无该字段**，恒走默认 128）、`DebugSettingsScreen.kt:89`（渲染走 `debugLogLines`）与 `:141/:154`（用 `debugLogEnabled`）；`git log --all -S` 的 5 个搬移 / 重构提交逐个 `git grep -c` 出现次数**恒为 1**（该字段从未有过任何读写方，连生成物目录也无引用）。
- **背景**：严格说它不渲染任何用户可见开关，属「零消费死状态字段」而非可见假开关，但项目自身先例（同 data class 的 `ISSUE-P3-126③` 删 `kdbxFormat`、`RESOLVED_LOG` §292 删 `hardwareBackedSecurity`）均把该形态按缺陷清理 ⇒ 归类成立。
  - **触发状态：dormant**——任何执行路径都不可达其消费，当前无任何错误效果被产出；无历史发作。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiState.kt:246`。
- **验收标准**：删除该字段（照 `ISSUE-P3-126③` 先例），或在投影链接入真实消费方。

### ISSUE-P3-513：CM 通道 requestCode「避开带」KDoc 漏列次日新增的自动填充通道活常量 `REQUEST_CODE_SAVE=2300`

- **核实时间点**：2026-10-06；**核实方式**：实读 `CredentialPendingIntents.kt:73-74`（「基线 `1000` 刻意避开解锁 Action 旧常量区间与自动填充通道（`100`/`2001`/`2100`/`2200`）的取值段，便于日志与抓包中辨认」）、`:76`（`getAndIncrement()`）与 `:79/:82`（`AtomicInteger(REQUEST_CODE_BASE = 1000)`，进程级无界递增）、`:16-20`（本仓自述匹配键「`(requestCode, Intent.filterEquals)`（extras 不参与匹配）」）；实读 `KeePasskeyAutofillService.kt:471`（`internal const val REQUEST_CODE_SAVE = 2300`）与 `:429-439`（`Intent(this, PasswordSaveActivity::class.java)` 仅 putExtra + `FLAG_MUTABLE or FLAG_UPDATE_CURRENT`）；实读 `CredentialCreateEntries.kt:104-107`（passwordEntry 同为纯 extras Intent 指向 `PasswordSaveActivity`）与 `:151-157`（`entryPendingIntent` 取 `nextRequestCode()`）；确认 CM 通道其余分配点目标组件均不同（`CredentialResponseAssembler.kt:264` → `PasskeyAssertionActivity`、`:349-357` → `PasswordFillActivity`；`KeePasskeyCredentialProviderService.kt:151-155` → `CredentialUnlockActivity`）⇒ **`PasswordSaveActivity` 是唯一活的同组件重合点**；实读 `PasswordSaveActivity.kt:40-57` 确认同时消费 CM fillIn 与同名 `EXTRA_*` 键 ⇒ 覆写即跨通道串扰；实读守卫 `AutofillAuthResultWiringTest.kt:109-128`（只断言内部分配器与 3 处 `FLAG_MUTABLE`）与 `CredentialRequestCodeWiringTest.kt:30-44/76-107/109-131`（只断言 CM 通道内单调、基线 ≥1000、无残留常量）——**无一锁跨通道取值段**；`git log -S` 确认避开带 KDoc 引入于 `4d17f591`、`REQUEST_CODE_SAVE=2300` 引入于 `b4670f27`（**次日**），清单此后未扩充。
- **背景**：KDoc 声明的跨通道避开契约与实际活常量集漂移，且该缺口**无任何守卫覆盖**。**可灭火非不可达**：需单进程生命周期内 CM 通道恰好完成 ≥1301 次分配（`getAndIncrement` 首次返回 1000，2300 为第 1301 次）且该次恰为 passwordEntry，同时与存活的自动填充保存记录（system_server 持久保留至重启 / 卸载）重叠。
  - 报告如实修正两点：①避开带 KDoc 的自述目的是「便于日志与抓包中辨认」（**可读性口径**），其功能性价值（防同组件记录覆写）是合理引申而非原文声明——KDoc 所列常量（100/2001/2100/2200）的「避开」陈述本身**未被 2300 证伪**（缺口是漏列而非陈述为假）；②避开带还漏了 `REQUEST_CODE_INLINE_ATTRIBUTION=2002`（`AutofillInlinePresentationFactory.kt:91`），但组件不重合、无覆写风险。
  - **触发状态：dormant**——当前无任何覆写 / 串扰发生；无 git 历史 / `RESOLVED_LOG` 事故记录。
  - **未跑项**：纯静态审查，未做真机验证；「`filterEquals` 恒真」依据两处 Intent 构造逐字段比对（action / data / type / package / categories 全空、component 同类）与仓内 KDoc 自述语义。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/passkey/CredentialPendingIntents.kt:73-74`、`app/src/main/java/com/keepasskey/app/autofill/KeePasskeyAutofillService.kt:471`、app 侧 `CredentialCreateEntries.kt:104-110/151-157`、`app/src/test/.../CredentialRequestCodeWiringTest.kt`。
- **验收标准**：`CredentialPendingIntents.kt:73-74` 的避开带补列 `2300`（并复核是否补列 `2002`），或改写为**可机检的形式**——在 `CredentialRequestCodeWiringTest` 补一条跨通道取值段断言（扫描全部 `REQUEST_CODE_*` 常量，断言无一落在 CM 通道 `getAndIncrement` 的可达区间内），使「避开带」不再只是散文承诺。

### ISSUE-P3-514：`count_test_results.py` 在零 XML 输入下输出全零读数并 exit 0（空证据当绿）

- **核实时间点**：2026-10-06（报告方已实测复现；本轮登记实读判据行）；**核实方式**：实读 `tools/doc/count_test_results.py:59-66`（`totals` 仅对发现的 XML 累加，零文件 ⇒ 全零）、`:67-70`（打印全零行）、`:75`（`return 0 if totals['failures'] == 0 and totals['errors'] == 0 else 1`，**判据不含 `files` 计数**）、`:43-45`（`return 2` 只判「未找到任何模块目录」）；对照 `tools/doc/preserve_test_failures.py:27`（「`2` = 未找到任何 JVM 单测 XML（**不得**当绿：无法判别『没红』还是『没跑』）」）、`tools/device/check_connected_device_results.py:19,23`（缺结果 XML 退 2 / `tests==0` 退 1）与 `:28`（明称本脚本是「JVM 单测的唯一尺子」）；实读 `AGENTS.md` §5 称其「JVM 单测聚合计数的唯一尺子（`test` 后跑它）」；实读 `.github/workflows/build.yml:96-104` hygiene-gate **仅 9 条机检、不含本脚本**，`grep 整个 .github/` 无命中 ⇒ 仅人工调用。
- **背景**：该脚本不在 CI、仅人工调用，零证据绿只能靠人读输出行发现。按 AGENTS §5 处方流程（先跑 `test --rerun-tasks` 再跑尺子）JVM 单测 XML 必然存在，故**处方流程下不可达**；零 XML 仅在违背处方时（fresh clone / `gradlew clean` 后未跑 test / cwd 错误）出现。与同族脚本的 fail-closed 口径**双标并存**，与项目自身「无证据不得当绿」家规相悖。
  - **触发状态：dormant**——退出码口径自脚本诞生即如此，历史上从未有批次记录过零 XML 绿读数。
  - **未跑项**：报告方以临时目录拷贝运行复现（脚本按 cwd 取模块根），**未在仓内清 `build/` 实测**（破坏性，不做）。
- **涉及文件**：`tools/doc/count_test_results.py:59-75`。
- **验收标准**：`:75` 前补 `if totals['files'] == 0: return 2`（并同步 `--excluded` 分支），与 `preserve_test_failures.py:27` 口径对齐；`--selftest` 补零 XML 反样本。

### ISSUE-P3-515：`preserve_test_failures.py` 对解析失败的测试结果 XML 静默 `continue`（假绿后按工序即触发毁灭性重跑）

- **核实时间点**：2026-10-06（报告方已用合成截断样本实跑复现；本轮登记实读核心）；**核实方式**：实读 `tools/doc/preserve_test_failures.py:58-61`（`try: root = ET.parse(path).getroot()` / `except ET.ParseError: continue`——**解析失败静默跳过**）、`:25-27`（退出码仅三态，`:27` 的 exit-2 立据是「无法判别『没红』还是『没跑』」——**同型不可判态却被当绿**）、`:164-166`（`if not found: print(f"无失败用例（已扫描 {len(paths)} 份 XML，无需留痕）"); return 0`，`len(paths)` 含不可解析文件，消息反而暗示「已成功扫描」）；对照 `tools/doc/count_test_results.py:61` 与 `tools/device/check_connected_device_results.py:81` 均**裸调用** `ET.parse`（损坏即崩、fail-closed）；`grep -rn ParseError tools/` 全树**仅本脚本一处**吞并；报告方实跑 `--selftest` PASS（6 项），内嵌样本 `:94-110` 全为良构 XML ⇒ **损坏分支无机检覆盖**。
- **背景**：本脚本的核心职责正是「闸门红了 ≠ 红在哪**可查**」（`AGENTS.md` §6.2 首轮红纪律 + `ISSUE-P3-489` AC①：肇事用例清单必须落盘留痕后才允许重跑覆盖）。假绿后按工序即触发毁灭性重跑（`--rerun-tasks` 会全量覆盖 `test-results`，覆盖后首轮肇事者不可复原，§445.6 实测）。
  - 报告方已如实区分：**skip-and-continue 作为取证工具的 best-effort 收集策略本身是对的**（首文件崩会连累其余证据），缺陷**仅在「静默」**——无告警、无不可解析计数、退出码无对应态。
  - **触发状态：dormant**——脚本仅一个提交（`03e2f652`）且不挂 CI（批次文档明示「未进 hygiene-gate 的取舍」），仅人工在红退出后调用，暴露窗口 ≈1 天；触发前提是 `test` 进程异常中断半写 XML（Ctrl+C / daemon 被杀 / OOM），常规红跑不产生。旁证：唯一尺子 `count_test_results.py:61` 裸 `ET.parse` 历经多批实跑从未因仓产 XML 损坏而崩。
  - **未核实项**：报告方**未跑真实红色 `gradlew test`** 制造天然截断 XML，改以 importlib 载入脚本代码、对合成截断样本实跑复现。
- **涉及文件**：`tools/doc/preserve_test_failures.py:58-61` / `:25-27` / `:164-166`。
- **验收标准**：统计并打印**不可解析份数**（带 warning 标记）；全不可解析时按 exit-2 同型判「无法判定」退非 0；`--selftest` 补损坏 XML 反样本（照 `ISSUE-P3-489` 既有内嵌正反样本机制）。

### ISSUE-P3-516：`export-preview-*.bat` 的 py 启动器回退分支是结构性死代码

- **核实时间点**：2026-10-06（报告方已做 5 组 `cmd` 运行期探针；本轮登记实读脚本原文）；**核实方式**：实读 `export-preview-main.bat:13`（`where python >nul 2>nul`）、`:14`（`if %errorlevel%==0 (`）、`:15`（`python …`）、`:17`（`where py >nul 2>nul`）、`:18`（`if %errorlevel%==0 (`）、`:19`（`py -3 …`）、`:21`（`echo [WARN] python not found, skip wrapper regen`）、`:24`（`if errorlevel 1 (`）、`:32`（`call gradlew.bat :app:exportMainPreviewScreenshots`）；`export-preview-secondary.bat:13-23` 逐行同构（仅 `:32` 任务名不同）；**实测探针**（报告方实跑）证实 ①`%errorlevel%` 在同一逻辑行内取语句解析前旧值；②结构镜像探针输出 `BR-1` ⇒ 内层回退分支永不执行；③`if errorlevel 1` 特殊形式确为运行期取值；④`echo` 不复位 `errorlevel`；⑤多行括号块整体为单解析单元、块内 `%var%` 一次冻结 ⇒ `:18` 判据与 `:14` 同源；`where python` / `where py` 命中情况确认当前机器走 `:14` 正常分支；`.github/workflows/build.yml` 对 export-preview 与 `.bat` **零命中** ⇒ CI 不引用；`git log` 两文件仅 `e3463f5f` 一条历史；`git check-ignore` 证实 `.gitignore:55` `app/src/screenshotTest/` 被忽略。
- **背景**：`:19` 的 py 回退**在任何机器上都不可达**（else 分支只在解析期 `errorlevel ≠ 0` 时进入，而 `:18` 冻结同一值）。「仅装 py 启动器（PATH 无 python）」的机器上包装再生成被跳过，打印 WARN 后 `:24` 读到 `:17` `where py` 成功留下的 0 而**放行**，`:32` 继续对 gitignore 生成物目录以陈旧 / 缺失包装导出预览。
  - 报告如实修正候选四处：①内层 if 实为 `:18`（`:17` 是 `where py`）；②死代码范围比候选更强；③`:21` 文案在 py-only 机器上**字面为真**，失实处在于掩盖「py 回退本应运行而未运行」；且**无 python 无 py** 的机器反而会经 `where py` 失败在 `:24` 触发 `[FAIL]` 中止（候选未提此不对称）；④即便开了 delayed expansion，`%errorlevel%` 写法仍冻结，必须改写为 `!errorlevel!` 或 `:24` 已在用的 `if errorlevel N` 运行期形式。
  - **触发状态：dormant**——本机走正常分支（危害路径当前配置不可达）+ CI 不引用 + 死分支按构造在一切机器上不可达；危害仅在 py-launcher-only 安装（未勾选 Add to PATH 的常见配置）的机器显现。
- **涉及文件**：`export-preview-main.bat:13-24` / `:32`、`export-preview-secondary.bat:13-24` / `:32`。
- **验收标准**：改写 `:13-23` 为运行期判定形态（`if errorlevel 1` 特殊形式或 `setlocal enabledelayedexpansion` + `!errorlevel!`），使 py 回退**真实可达**；并确认 py-only 与全无两条路径都给出正确终态。

