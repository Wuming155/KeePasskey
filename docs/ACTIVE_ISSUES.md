# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。  
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。  
> **只放现存问题**：本文件**只保留尚未闭环的条目**——已闭环条目的正文、批次流水与索引段一律不留；历史实现与验收证据以 [RESOLVED\_LOG.md](RESOLVED_LOG.md) 与 `docs/resolved/batches/` 为单一真相源。  
> **实施前必读**：源自安全复核的条目，其 AC 已按 [`SECURITY_RECHECK_2026-09.md`](security/SECURITY_RECHECK_2026-09.md) §10 / §11 的**第四轮独立复核定版结论**逐条对齐，更正口径已内联到对应条目——照条目原文 AC 实施者可能**降低安全性**或**引入数据损坏**。  
> **例外（`ISSUE-P1-276` ～ `ISSUE-P3-299`）**：这批 2026-09-23 条目出自**两轮全仓审查**（8 个铺开代理 + 7 个对抗复核代理，反向口径＝先假设误报再取证），不属上述复核范围；其依据为各条「核实方式」所记的逐行反校与对抗推翻结论。凡条目标注「**未经对抗轮单独攻击**」或「**需外部证据（真机 / 真实 DAV 矩阵 / 官方对拍）**」者，认领时须按规则 6.1② 先自行复核前提，不得直接开工。  
> **闭环纪律**：任务完成后，将该条目从本文件**整条移入** [RESOLVED\_LOG.md](RESOLVED_LOG.md)（加一行索引 + 新增批次正文），并执行 `git commit & push`。
> **新增批次（2026-10-05）**：`ISSUE-P2-466~470` / `ISSUE-P3-474~487` 出自 [`records/软件工程质量审查记录_2026-10-05.md`](records/软件工程质量审查记录_2026-10-05.md)（五维度主源码静态审查 + 合并 / 同步 / 健康审计引擎深审）。**严重度映射**：记录标 medium（含 low/medium）→ **P2**，low → **P3**。这批条目为**纯静态审查**产出（未运行构建 / 测试 / 真机），认领时须按规则 6.1② 先复核前提（正文行号仅作核实时刻的快照）；其中涉及 `crypto/src/main/rust/**` 的原生面条目，入库前须按 AGENTS.md 测试资产纪律**四层 `connectedDebugAndroidTest` 真机实跑**（执行前按 §263 确认设备上无待保留数据或改用 AVD）。
> **新增批次（2026-10-06）**：`ISSUE-P1-495` / `ISSUE-P2-496~502` / `ISSUE-P3-503~516` 出自 [`records/三类隐蔽性故障排查报告_2026-10-06.md`](records/三类隐蔽性故障排查报告_2026-10-06.md)（假开关 / 接线断裂 / 读取源错误与静默降级三类，九路并行排查 + 逐条独立复核）。**严重度映射**：报告标 high → **P1**，medium → **P2**，low → **P3**。**三项登记裁决**（登记时新定，须随条目一并遵守）：
> ① **同族去重**：报告 F12 为「原生探活失败静默回落零观测」缺陷族的唯一母条目，其子集 F03（cipher 三引擎）/ F07（强度面）/ F11（Passkey 签名面）并入 `ISSUE-P2-499`，不独立立项（该合并条目已于 §454 闭环归档）；
> ② **severity 取族内最大值**：`ISSUE-P2-499` 判 medium（母条目自身 low，子集 F03 为 medium——ChaCha20 BC 回落 ≈44× 慢、Twofish 整库数据流走纯 Java），条目内分层保留两侧论据；
> ③ **F25 不登记**：复核判定其缺陷前提被证伪（与格式裁决者 KeePass 2.61.1 官方 C# release 行为逐行同型，批次 273 勘误②复审显式维持、写侧 D17 守卫在位），`holds=false` 依据是前提证伪而非登记表命中。
> **触发状态口径**（报告 v3 判据 E1/E2，与本文件「只放现存问题」不冲突——masked 指**错误效果已产生或曾实际发作**，dormant 指**零错误效果、缺陷以声明侧残留 / 守卫缺口形态存在**）：masked 7 条（`P1-495` 除外，另 `P2-496~499`）应优先认领；dormant 条目为前瞻性风险，可按批连续解决。全部条目为**只读静态排查**产出，认领时须按规则 6.1② 先复核前提。
> **真机走查（2026-10-06）**：`ISSUE-P1-495` 收工时的真机（MIUI / `M332BF` / Android 17）五层走查——`:crypto:` 37/37、`:database:` 18/18、`:sync:` 25/25 全绿；`:core:` 4 例中 1 例**确定性假红**（曾登记为 `ISSUE-P2-517`，**已于 §450 整条闭环**，本文件不再保留该条目）；`:app:` 51 例中 3 例失败属已登记的 `ISSUE-P2-492` 厂商冻结面（读数与未定性项见 [`architecture/实现约定与验证现状.md`](architecture/实现约定与验证现状.md)）。
> **新增批次（2026-10-07）**：`ISSUE-P2-518~521` / `ISSUE-P3-517~525` 出自 [`records/六专题综合研究与实测报告_2026-10-07.md`](records/六专题综合研究与实测报告_2026-10-07.md)（动态工作流六专题调研 + 五组实测的报告，§9.3 为 8 条 backlog 草稿；报告同日落册入库并登记文档地图）。**严重度映射**：草稿标 P2 → P2、标 P3 → P3；另 4 条（`P3-520` / `P3-521` / `P3-522` / `P3-525`）为报告正文 §1 / §3 / §6 已核实发现的补登。各条登记前均已逐条复核前提（核实时间点与方式见条目正文）；`P3-523` 涉及设备侧复跑，执行前按 §263 确认设备上无待保留数据。**该批 13 条现已全部闭环**：`P2-518/519/521` §463、`P2-520` §461、`P3-517/525` §462、`P3-518/520/521` §460、`P3-519/522/524` §461、`P3-523` §464（设备侧逐例定性）；`P3-526` 为 §463 残留（`.bak` 恢复入口真机走查）同批登记入清单，**已于 §465 闭环归档**，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。
> **新增批次（2026-10-08，第三方复核）**：`ISSUE-P1-537` / `ISSUE-P1-538` / `ISSUE-P2-539` / `ISSUE-P2-540` / `ISSUE-P3-541` 出自 [`records/第三方复核报告_2026-10-08_同步闪退面.md`](records/第三方复核报告_2026-10-08_同步闪退面.md)（**两份**报告：`§473`（`bb60d9bb`）复核 7 条 + `§474`（`4aa0474a`）复核 7 条，均为第三方静态复核 + 逐点核对，**未跑全量 `test`、未真机**）。**严重度映射**：报告标 CRITICAL/HIGH → **P1**，MEDIUM → **P2**，LOW → **P3**。**五项登记裁决**（登记时新定，须随条目一并遵守）：① **两份报告的相反动作不取折中**（详见 `ISSUE-P2-540`）；② 报告 B #1 判为**本批（§474）引入的回归**，代理认领；③ 报告 A 的 `AutofillPickerViewModel.kt:127` 一条经代理复核**判为审计误判**（该行位于 `runCatching { … }.getOrDefault("")` 之内）⇒ **不登记**、不改动；④ 报告 B #4 与 #5 **合并登记**（代理判定同根因：擦除是**逐实例身份集合**而非子树级）；⑤ **编号说明**：并行工作线已先行占用 `ISSUE-P2-536`（内容变更判据等值敏感性，提交 `01fc0b38`），本批从 **537** 起编号、**不重号**。逐条核实结论与「不采纳」理由见落册文档 §1～§4；认领时须按规则 6.1② 先复核前提（正文行号为 2026-10-08 核对时刻的快照）。
> **本批闭环（2026-10-08，§477）**：上条批次中的 `ISSUE-P1-537` / `ISSUE-P1-538` / `ISSUE-P2-539` / `ISSUE-P2-540` / `ISSUE-P3-541` 五条 + `ISSUE-P3-542`（§476 副产品登记）共**六条整条闭环归档**（批次正文见 `docs/resolved/batches/477-*.md`）。**口径裁决**：`ISSUE-P2-540` 的两处矛盾按候选口径落库为 **`PD-79`**（**代理裁决，用户授权** 2026-10-08）——「写盘 / 落库 / 凭据下发＝fail-fast；展示 / 检索 / 报告 / 解析＝降级」分层 + **禁止「先查 `cleared` 再裸读」的两步式** + 兜底只允许作**带留痕的第二层** + 两份报告动作相反时**不取折中**（先删因、再判果）；`ISSUE-P3-542` 的判据钝化为 **`PD-80`**。**`§474` 批次文档的失实结论已在 §477 批次文档显式更正**（原 AC 所指「§475 批次文档」编号从未创建，§474 与 §476 之间该编号空缺 ⇒ 更正落在本批，理由见批次文档 §4）。**同族续扩**：整改中发现的 `KdbxEntryMerger.isModified` 同源面已登记为 **`ISSUE-P3-543`**（取证前不动代码）。**新增 / 扩面机检**：第 11 道 `tools/doc/check_raw_coroutine_scope.py`；`tools/doc/check_projection_read_safety.py` 扩至**五条判据**（新增「两步式」与「非交付面口令 fail-open」）。

> **用户反馈批次（2026-10-08，§478）**：`ISSUE-P2-544` 出自**用户真机反馈**（「密码库搜索筛选功能异常，
> 点击按钮没有反应，另外这个按钮应该收敛到密码库右上角三个点的那个设置，和排序、扫码放在一起」）——
> 核实方式＝**AVD 真机实测复现 + `uiautomator` 层次树逐帧取证**（读数与步骤见
> [`resolved/batches/478-高级搜索入口收敛与对话框化批次.md`](resolved/batches/478-高级搜索入口收敛与对话框化批次.md) §1）；
> **同会话闭环**（登记即排查）。该批同时登记了 `ISSUE-P2-545`（排查中发现 §477 的登记面与第 11 道机检
> 不在当前树内），**已于 §479 整条闭环归档**。

> **本批复原批次（2026-10-08，§479）**：`ISSUE-P2-545` 整条闭环——复核确认提交 `bc7f187a` 是 `33a8b86b`（§477）的
> **整树精确回退**（源码面与 `d8111b4a` **逐字节相同**：含六条整改、5 份新增用例、第 11 道机检、`PD-79` / `PD-80`
> 与批次正文）。故按「**先核事实、再复原**」处置：§477 的代码 / 机检 / 登记面**一并复位**，并以本批新采集的读数
> 作证（正文见 [`resolved/batches/479-读路径登记面与机检整批复原批次.md`](resolved/batches/479-读路径登记面与机检整批复原批次.md)）。
> **口径不变**：六条的结论仍以 §477 为闭环批次，本批只复原其登记面，**不重开、不改写**既有结论。

> **五轴质量审查批次（2026-10-09）**：`ISSUE-P2-548` / `ISSUE-P2-549` / `ISSUE-P3-550` / `ISSUE-P3-551` /
> `ISSUE-P3-552` / `ISSUE-P3-553` / `ISSUE-P3-554` / `ISSUE-P3-555`（8 条）出自
> [`records/五轴质量审查报告_2026-10-09.md`](records/五轴质量审查报告_2026-10-09.md)（按「正确性 / 可读性 /
> 架构 / 安全 / 性能」五轴，四路并行取证 + 主审逐条复核原始代码行）。
> **严重度映射**：无 P0 / P1；确证缺陷按影响定 P2（用户可感知的功能或告警缺失）/ P3（纪律残留与重复实现）。
> **两项登记裁决**（登记时新定，须随条目一并遵守）：
> ① **代理结论先复核再登记**：四路代理的原始结论中有 **8 条被推翻或降档**（明细见报告 §3），
> 例如「同步层静默失败」的机理被改写为「**先 `post()` 后 `cancel()`**」而非「`Offline` 不通知」——
> **照原结论改会误修**；认领 `ISSUE-P2-548` 前必读报告 §3；
> ② **已登记限界与格式固有限制不重复立项**：`SafVaultCreation.kt:144` 的 `"rwt"` 截断写（限界 §24）、
> `SizeBoundedInputStream` 未覆写 `skip()`（生产链路不调 `skip`）、`VariantDictionary` 缺条目数上限
> （生产输入已被 1 MiB 上界约束）、合并历史同秒去重（KDBX 官方即秒精度）**一律不登记**。
> **全部为静态取证**：未运行构建 / 未跑测试 / 未真机，认领时须按规则 6.1② 先复核前提（行号为 2026-10-09 快照）。

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

> **暂无开放项**（`ISSUE-P0-531` 已于 §473 闭环归档，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)）。

## P1 高危与核心功能问题（0 项）

> **暂无开放项**（`ISSUE-P1-537` / `ISSUE-P1-538` 已于 §477 闭环归档，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)）。

## P2 中危缺陷与协议/测试缺口（**2 项**）

### ISSUE-P2-548：同步失败通知「先发后撤」——鉴权 / 协议失败被伪装成离线且无告警残留

- **现象**：WebDAV 密码填错（401/403）或服务端 5xx 时，UI 显示「离线，已保留本地副本」，
  且**通知栏不留任何失败痕迹**——库可长期未同步成功而用户无感知。
- **核实时间点 / 方式**：2026-10-09，静态逐行取证（**未跑构建 / 未跑测试 / 未真机**）：
  ① `sync/.../webdav/WebDavSyncProvider.kt:163-164` 401/403 → `throw SyncException.AuthenticationError`；
  ② `:150-151` `getMetadata` 走 `runCatching` ⇒ 以 `Result.failure` **返回**而非抛出，故 `SyncCycleRunner.kt:365`
  的 `NetworkError -> Offline` catch **不覆盖此路径**；
  ③ `sync/.../engine/SyncEngine.kt:220-224` `recoverFromMetaFailure` 的 `else` 分支把**所有非 `FileNotFound`**
  失败（含 `AuthenticationError` / `ProtocolError`）归一为 `RemoteUnreachableUsingCache`，
  同时 emit `CouldntOpenFromRemote`；
  ④ `app/.../sync/SyncCycleRunner.kt:354` → `SyncOutcome.Offline`；
  ⑤ `app/.../sync/SyncFailureNotifier.kt:33-42` `isEngineFailure(CouldntOpenFromRemote)=true` ⇒ `post()`；
  ⑥ `:89-103` `lastOutcome.collect` 对**非 `Error`** 一律 `cancel()`。
- **根因（与初版审查结论的差异，勿误修）**：**不是**「`Offline` 不发通知」——事件通道**确实**会 `post()`。
  真缺陷是**同周期内定序**：事件先 `post()`、周期末 `lastOutcome = Offline` 再 `cancel()`，`posted` 被清零，
  净效果＝通知从未出现。**照「`Offline` 也纳入通知」去改会误修**（真实断网时通知刷屏）。
- **影响**：部分抵消 `ISSUE-P3-298`（其立项目标正是修复「同步失败零感知」）。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/sync/SyncFailureNotifier.kt`、
  `app/src/main/java/com/keepasskey/app/sync/SyncCycleRunner.kt`、
  `sync/src/main/java/com/keepasskey/sync/engine/SyncEngine.kt`
- **验收标准**：
  ① 鉴权 / 协议错误所在的同步周期结束后，失败通知**仍在通知栏**（新增单测覆盖「事件 + 结论同周期」定序）；
  ② 真实网络不可达（`SyncException.NetworkError` **抛出**路径，`SyncCycleRunner.kt:363-365`）不得因本次整改变成常驻通知；
  ③ UI 侧对鉴权失败不得再显示「离线」文案，须与真实离线区分（错误分类上收，不改降级语义）。

### ISSUE-P2-549：`ExtractedSaveCredentials` 持明文口令却无 `toString()` 覆写 + 无谓 `FLAG_MUTABLE`

- **现象**：自动填充保存链路承载**明文口令**的 data class 未覆写 `toString()`，且口令经
  `FLAG_MUTABLE` 的 `PendingIntent` extras 跨 Binder 下发。
- **核实时间点 / 方式**：2026-10-09，静态逐行取证：
  ① `app/.../autofill/AutofillSaveExtractor.kt:8-12` `internal data class ExtractedSaveCredentials(
  val username: String, val password: String, val webDomain: String?)` —— 全文件**无** `toString()` 覆写；
  ② `app/.../autofill/KeePasskeyAutofillService.kt:439` `putExtra(EXTRA_PASSWORD, extracted.password)`、
  `:445` `PendingIntent.FLAG_MUTABLE or FLAG_UPDATE_CURRENT`；
  ③ **同仓反证**：`AutofillPickerViewModel.kt:162-170` 的 `Credentials` data class **已**就同一形态覆写
  `toString()`（KDoc：「一次日志/异常插值即泄漏」）⇒ 本处属**遗漏而非设计**；
  ④ 现有 3 个消费点（`:8` / `:27` / `:66` 与 `KeePasskeyAutofillService.kt:431`）**均无**日志插值。
- **降档理由（诚实定级，勿按 P1 处置）**：`PasswordSaveActivity` 于 `AndroidManifest.xml:190` 为
  `exported="false"`，且当前无日志消费点 ⇒ **未构成实际泄露**。但 `FLAG_MUTABLE` 在此处**无任何 `fillIn` 需求**
  （属无谓放宽），且一旦新增一行 `AppLog.d(TAG, "$extracted")` 即明文出桶、编译期无护栏。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/autofill/AutofillSaveExtractor.kt`、
  `app/src/main/java/com/keepasskey/app/autofill/KeePasskeyAutofillService.kt`
- **验收标准**：
  ① 补 `toString()` 覆写，与 `Credentials` 同口径（仅呈现长度，内容不物化）；
  ② `PendingIntent` 改 `FLAG_IMMUTABLE`（整改前先确认 `PasswordSaveActivity` 确无 `fillIn` 需求）；
  ③ 增补机检：字段名命中 `password` 且承载 `String` 的 data class 必须覆写 `toString()`，并挂入 `hygiene-gate`。

## P3 低危问题、特性接线与体验优化（**5 项**）

### ISSUE-P3-550：10 处异常 message 直出 UI（自订 ZT-10 / `ISSUE-P3-453` 纪律的残留）

- **现象**：`t.message` / `e.message` 被直接拼进用户可见文案，违反本仓自订纪律。
- **纪律出处**：`core/.../result/KdbxResult.kt:19-40` —— `Failure.message` 已标
  `@Deprecated(level = ERROR)`，KDoc 明示「`${t.message}` 把异常细节一并透出 UI……**禁止再用于 UI**」。
- **核实时间点 / 方式**：2026-10-09，`grep -rn "\.message ?:" app/src/main` 全量 + 主审逐条 Read：
  `VaultExportCoordinator.kt:47`、`:67`；`VaultFileDriftResolve.kt:63`、`:65`；
  `VaultLifecycleCoordinator.kt:272`、`:312`；`ResumeSyncProbeCoordinator.kt:157`；
  `SettingsHealthController.kt:161`、`:319`；`SettingsKdfBenchmarkController.kt:51`（共 **10 处**）。
  **渲染路径实锤**：`HealthCheckScreenSections.kt:176` `Text(text = healthMessage)`。
  （`ConflictResolutionViewModel.kt:323` 读的是 `SyncOutcome.Error.message`，属 app 层自产文案，**不计入**。）
- **定级说明**：泄露内容为**文件路径 / 端点 / 主机 / 协议细节**，**非主密码明文** ⇒ P3。
- **验收标准**：10 处 `message` 一律改为错误码映射（`KdbxErrorTexts.uiTextArg` 口径），
  细节只进日志；增补机检禁止 `\.message` 进 `userText` / 文案槽，并挂入 `hygiene-gate`。

### ISSUE-P3-551：序列化缓冲（整库密文）在写盘异常路径不清零

- **核实时间点 / 方式**：2026-10-09，静态取证：
  ① `database/.../session/SessionPersistence.kt:91-94` `serialized = serializeToBytes(...)`
  → `writer(serialized)` → `serialized.fill(0)`，`fill(0)` 在 **`try` 体内而非 `finally`**，
  `writer` 抛异常时整库密文副本滞留堆等 GC；
  ② `:187-189` 同型第二处（`changeCredentials` 路径）；
  ③ **同文件反证**：`:180-200` 的凭据快照处理正确（`oldPwd` 克隆 + 失败路径所有权移交 `restoreCredentials`）
  ⇒ 本处属遗漏而非取舍。
- **验收标准**：两处 `serialized.fill(0)` 移入 `finally`（或 `use`-式收口），并补单测断言异常路径下缓冲已清零。

### ISSUE-P3-552：「今天/昨天/M月d日 HH:mm」相对时间格式化三处实质重复

- **核实时间点 / 方式**：2026-10-09，静态取证：
  `VaultListSyncController.kt:205-215`、`SettingsSyncController.kt:382-392`、
  `ConflictResolutionViewModel.kt:93-101` —— 三处同一套 `when(今天/昨天/else)` 判据 + 同一套
  `R.string.time_today` / `time_yesterday` / `date_pattern_month_day`，差异仅在入参与「0 → never」分支。
  三处**每次调用都重编译** `DateTimeFormatter.ofPattern("HH:mm")`；
  同类：`EntryDetailExpiryCard.kt:25` 与 `EntryEditExpiryEditor.kt:37` 各自硬编码 `"yyyy-MM-dd"`。
  （`VaultEntryMapper.kt:339-355` 已用 `ConcurrentHashMap<Locale, DateTimeFormatter>` 缓存并注释此坑 ⇒ 已知未收敛。）
- **验收标准**：抽 `app/.../ui/model/RelativeTimeFormatter.kt` 统一出口（可空入参统一处理「从未」），
  pattern 常量化 + 缓存；三处调用点收敛，行为与文案不变。

### ISSUE-P3-553：剪贴板写入成败裁决助手逐字重复

- **核实时间点 / 方式**：2026-10-09，静态取证：`EntryDetailCopyCoordinator.kt:60-68` 与
  `VaultListClipboardCopy.kt:35-43` 的 `private fun writeToClipboard(write: ...): Boolean` **实现逐字相同**
  （通道 null → false；`try { channel.write(); true } catch { false }`）。
  另 `notification/UnlockedNotificationCopyAction.kt:81`、`:107`、`:120` 三发重复
  `UiMessage(R.string.clipboard_copy_failed)`。
- **验收标准**：提 `ClipboardSecurityChannel?.tryWrite(...): Boolean` 单点扩展，两处调用点收敛；
  失败提示文案一并收口。

### ISSUE-P3-554：`app` 模块依赖声明与实际使用不符（隐式透传脆点）

- **核实时间点 / 方式**：2026-10-09，静态取证：
  ① `app/build.gradle.kts:257-259` 只声明 `:core` / `:database` / `:sync`，**未声明 `:crypto`**；
  ② `grep -rl "com.keepasskey.crypto" app/src/main` = **12 个文件**（如
  `SettingsKdfBenchmarkController.kt:7`、`PasswordEntropyEstimator.kt:4`、`SettingsUiStateProjection.kt:8`）；
  ③ 能编译全靠 `database/build.gradle.kts:32` 的 `api(project(":crypto"))` 透传；
  ④ **单向性未被破坏**（已核实 core 无跨模块 import、crypto 只依赖 core、sync 只依赖 core、
  database 只依赖 core+crypto）。
- **验收标准**：`app` 显式 `implementation(project(":crypto"))`，使声明反映真实依赖；
  改动后 `.\gradlew.bat assembleDebug` 仍绿。

### ISSUE-P3-555：`SyncEngine.markResolvedAndUpload` 吞掉协程取消

- **核实时间点 / 方式**：2026-10-09，静态取证：
  ① `sync/.../engine/SyncEngine.kt:495-497` `catch (t: Throwable) { SyncResolveUploadResult.Failed(t) }`
  未重抛 `CancellationException`，与同文件 `openRemote`（抛出）及 `SyncCycleRunner.kt:363-365`（显式重抛）口径不一致；
  ② 同源：`WebDavSyncProvider.kt:151/227/262`、`S3SyncProvider.kt:162/272` 的 `runCatching`、
  `WebDavUploadAtomic.kt:127` 的 `catch (e: Exception)`（`CancellationException` 是 `IllegalStateException` 子类）；
  ③ 全模块 grep `CancellationException` **仅** `TransientHttpRetry.kt:62` 正确重抛 ⇒ 该处是例外而非通例。
- **验收标准**：上述 `catch (t: Throwable)` / `runCatching` 收敛点显式重抛 `CancellationException`；
  增补单测断言「周期被取消时不上报 `Failed`」。

> **近期闭环（指针）**：`ISSUE-P3-547`（密码库列表标签 / 收藏筛选芯片行整体移除，用户裁决）已于 §481 整条闭环归档；`ISSUE-P3-543`（`KdbxEntryMerger.isModified` 同源内容比较判据上收 `core` 单点）已于 §480 整条闭环归档；`ISSUE-P1-537` / `ISSUE-P1-538` / `ISSUE-P2-539` / `ISSUE-P2-540` / `ISSUE-P3-541` / `ISSUE-P3-542` 六条已于 §477 整条闭环归档（读路径判据收口与同族续扩）；`ISSUE-P2-545`（§477 登记面 / 第 11 道机检被整树回退）已于 §479 整条闭环归档；`ISSUE-P2-544`（密码库高级搜索入口「点了没反应」）已于 §478 闭环；`ISSUE-P2-536`（同步冷启动「远端侧更新误报本地修改」）已于 §476 闭环；`ISSUE-P2-518` / `519` / `521` 已于 §463、`ISSUE-P2-520` 已于 §461、`ISSUE-P2-528` 已于 §467、`ISSUE-P2-530` 已于 §470、`ISSUE-P2-529` 已于 §471 闭环归档；`ISSUE-P3-531` / `ISSUE-P3-532` 两条纯验证条目经用户 **2026-10-08 真机走查回执通过**，已于 §472 整条闭环归档，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。

