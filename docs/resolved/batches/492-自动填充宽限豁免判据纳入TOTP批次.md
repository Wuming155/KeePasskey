# §492 自动填充宽限豁免判据纳入 TOTP 批次（2026-10-11）

**条目**：`ISSUE-P3-577` **整条闭环**（P3 10 → **9**）
**来源**：2026-10-11 数据流安全审查工作流发现 5（verdict=confirmed），登记于 `ACTIVE_ISSUES.md`，基准提交 `cd223701`。

## 492.1 原始条目（原样收录）

### `ISSUE-P3-577`：自动填充会话授权宽限豁免判据不含 TOTP，宽限期内用户名+当前验证码免确认直填

**背景与影响**：会话授权宽限（30 秒 TTL，`AutofillSessionGrantStore.kt:82`）豁免二次确认的判据
`datasetCarriesPassword`（`AutofillUnlockedCandidates.kt:116-119`）只统计**口令字段**：交付不含口令但含
`calculateEntryTotp` 当前码的数据集时（`AutofillDatasetBuilders.kt:199 → 293-301`，排除 HOTP `:295`），
`:302-304` 因 skip 跳过 `attachConfirmationAuth`，框架把用户名+当前 TOTP 直填表单、无确认 UI；
「仅用户名+OTP 框、无口令框」表单可独立成立（`AutofillTargetFieldResolver.kt:20,79,109`）。
前置三重叠加：用户显式开启非默认宽限开关（`ExtendedSettingsStore.kt:259-260` 默认 false）+
30 秒内对该「包名+可归属域」确认过一次 + 库解锁态；调用方须已通过归属校验。

**验收标准**：① 豁免判据把 TOTP 直填值计入敏感值（含 TOTP 的数据集在宽限期内仍挂 `setAuthentication`）；
② `AutofillSessionGrantStoreTest` 补 TOTP 场景用例（既有 `:129-181` 无此场景，缺口未被测试锁定）。

**核实时间点 + 核实方式**：2026-10-11，数据流安全审查工作流（同 P2-573 口径），基准提交 `cd223701`；六要素详情见
[`security/数据流安全审查_2026-10-11.md`](../../security/数据流安全审查_2026-10-11.md) 发现 5（verdict=confirmed，
策略表达式实际在 `AutofillSessionGrantStore.kt:112-117`，原引 118-121 为 KDoc 偏移已修正）。
来源攻击面：自动填充与通行密钥目标验证。

## 492.2 前提复核（2026-10-11 直读）

- **条目成立**：`AutofillAuthenticationPolicy.skipRepeatConfirmation`（`AutofillSessionGrantStore.kt:112-117`）的
  敏感值形参原为 `datasetCarriesPassword`；`unlockedConfirmationPolicy`（`AutofillUnlockedCandidates.kt:98-121`）
  仅以 `passwordId != null && candidates.ranked.any { 口令非空/已清零 }` 喂入；`appendUnlockedDatasets`
  （`AutofillDatasetBuilders.kt:199`）调用时**未传 OTP 框 id** ⇒ `buildCandidateDataset` 的 TOTP 直填
  （`:293-301`）与豁免判据**互不知情**——交付「仅用户名 + OTP 框」的数据集时 `skipRepeatConfirmation == true`
  使 `:302-304` 不挂 `setAuthentication`，与条目描述逐字吻合。
- **行号核对**：条目引 `AutofillUnlockedCandidates.kt:116-119`（判据块）与 `AutofillDatasetBuilders.kt:295`
  （HOTP 排除）均与现树一致；`AutofillSessionGrantStore.kt:112-117` 为策略表达式本体（注记已修正 KDoc 偏移）。
- **既有测试面**：`AutofillSessionGrantStoreTest` 既有 11 例（`:129-181` 两例只以布尔 `false/true` 直接喂策略，
  **无 TOTP 场景**，且不覆盖接线的确证缺口）；`app/src/test` 全仓 `grep` 无 `datasetCarriesPassword` /
  `unlockedConfirmationPolicy` 的其它调用点（唯 `AutofillAuthResultWiringTest:210` 文案提及
  `skipRepeatConfirmation`，与本次改动面无关）。
- **未漂移面**：`AutofillTargetFieldResolver` 的 OTP 框识别、`ExtendedSettingsStore` 的宽限开关默认值、
  `AutofillSessionGrantStore` 的 TTL / 域可归属判定（`ISSUE-P2-81`）均逐字未动。

## 492.3 整改

### ① 策略面：`AutofillAuthenticationPolicy` 的敏感值判据扩义为「口令 **或** 当前 TOTP 直填值」（AC①）

- `skipRepeatConfirmation` 的形参 `datasetCarriesPassword` **改名并扩义**为 `datasetCarriesSensitiveValue`
  （判定式逐字不变：`… && !datasetCarriesSensitiveValue`）；KDoc 补 `ISSUE-P3-577` 段说明 TOTP 与口令同源
  （值经无 UI 自动途径下发即构成用户无感知泄露）。
- 新增**纯函数** `carriesSensitiveValue(hasDeliverablePassword, hasDeliverableTotp)` =
  `hasDeliverablePassword || hasDeliverableTotp`——把「敏感值 = 口令 ∨ 当前 TOTP」写成可单测的唯一判据。
- 新增**纯函数** `totpIsDeliverable(snapshot: EntryTotpSnapshot?)` = `snapshot != null && !isHotp && code.isNotEmpty()`：
  与交付面 TOTP 直填触发条件**逐字同口径**（`ISSUE-P3-298 ⑤` 排除 HOTP）。

### ② 判据编排面：`unlockedConfirmationPolicy` 同口径统计候选 TOTP（AC①）

- 签名新增 `otpId: AutofillId?`；敏感值改由 `AutofillAuthenticationPolicy.carriesSensitiveValue(...)` 装配：
  - `hasDeliverablePassword` = 既有口令判据**逐字保留**（`passwordId != null && any { 口令非空或已清零 }`，
    含 `ISSUE-P2-539` 的保守口径 —— 已清零一律判「携带」）；
  - `hasDeliverableTotp` = `otpId != null && candidates.ranked.any { totpIsDeliverable(calculateEntryTotp(id)) }`
    ——**以 `otpId != null` 为前提**（无 OTP 框的表单不得因该统计被误收紧，纯用户名通道的既有豁免意图不破坏）。
- 效果：宽限期内只要本批任一数据集将直填当前 TOTP 码，`skipRepeatConfirmation` 即返回 false ⇒
  `buildCandidateDataset` 的 `:302-304` 仍挂 `attachConfirmationAuth`（`setAuthentication`），不再免确认直填。

### ③ 交付面口径收敛（单一事实源）

`AutofillDatasetBuilders.buildCandidateDataset` 的 TOTP 直填分支由内联 `!otpSnapshot.isHotp && code.isNotEmpty()`
改为复用 `AutofillAuthenticationPolicy.totpIsDeliverable(otpSnapshot)`——**零行为变更**，使「豁免判据」与「交付面」
共用同一谓词，杜绝两处口径日后各自漂移。

### ④ 调用方接线

`AutofillDatasetBuilders.appendUnlockedDatasets` 调用 `unlockedConfirmationPolicy` 时改用**命名参数**传入
`callingPkg / candidates / passwordId / otpId`——`passwordId` 与 `otpId` 同为 `AutofillId?`，位置对调可静默编译，
命名参数从源头消除该风险。

### ⑤ 测试资产

- **修改** `AutofillSessionGrantStoreTest`：既有 6 处 `datasetCarriesPassword =` 形参名随策略改名同步
  （判据与断言值逐字保留）；**新增 2 例**——
  ① `ISSUE-P3-577 数据集仅携带当前 TOTP 直填值时授权宽限不生效`（AC②：无口令框 + OTP 框 + 直填当前码 ⇒
  不豁免；纯用户名通道 ⇒ 仍豁免，锁既有意图）；
  ② `ISSUE-P3-577 TOTP 直填口径为非 HOTP 且当前码非空`（四分支：非 HOTP / 快照 null / HOTP / 空码）。
- **新增** `AutofillSensitiveValueWiringTest`（2 例，静态接线守卫，与 `AutofillAuthResultRecheckWiringTest` 同范式）：
  ① `AutofillUnlockedCandidates` 必须 `calculateEntryTotp` + 共用 `totpIsDeliverable` + 结果并入
  `datasetCarriesSensitiveValue`（`hasDeliverableTotp` / `carriesSensitiveValue`）且以 `otpId != null` 守卫；
  ② `AutofillDatasetBuilders` 必须以 `otpId = otpId` 传参且直填共用 `totpIsDeliverable`。
  **立此守卫的缘由**：纯策略面用例只证「敏感值 ⇒ 不豁免」，**不证接线**——判据「算而不判」时它照样绿；
  这正是条目所指「缺口未被测试锁定」的实质。
- 删除 0 例。

## 492.4 验证

### 宿主

- **定向单测**（`--tests` 五套：新增两套 + 相邻三套）→ `BUILD SUCCESSFUL`；按 §201 立规**回读 XML 确认在册**：
  `AutofillSessionGrantStoreTest tests=13 / AutofillSensitiveValueWiringTest tests=2 /
  AlgoHotPathGuardsTest tests=19 / AutofillAuthResultWiringTest tests=7 /
  AutofillAuthResultRecheckWiringTest tests=4`，全部 `skipped=0 failures=0 errors=0`。
- **全量单测**：`.\gradlew.bat test --max-workers=1` → `BUILD SUCCESSFUL`（1m 18s / 114 tasks：2 executed、112 up-to-date）。
  `python tools/doc/preserve_test_failures.py` → `failures=0`（无首轮红，无需留痕，退出码 0）。
  `python tools/doc/count_test_results.py` → `xml=553 tests=3604 failures=0 errors=0 skipped=15`
  （§491 基线 `xml=552 tests=3600` ⇒ **+1 XML / +4 例**：策略面 +2、接线守卫 +2；既有 6 处形参改名为**修改**不计增）。
- **门禁读数**（`python tools/doc/gate_readings.py` 原样粘贴）：

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/16] tools/doc/check_gate_script_integrity.py     EXIT 0  | [check_gate_script_integrity] 清单 19 条 / 期望 19 条；命中 0 处  [check_gate_script_integrity] PASS：门禁脚本集合 19 条与 SHA-256 基线清单逐条一致
[2/16] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=35  budget=35
[3/16] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[4/16] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[5/16] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 489 份；分册登记 491 条；全量索引 491 条；最大 §492）
[6/16] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 611 个测试文件
[7/16] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[8/16] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[9/16] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  多发射助手函数（顶层发射 ≥2 节点的 @Composable）：62 个  检查过的调用点：BoxScope 组件=61  框架 supportingText 槽=8  box_slot_stacked_sites=0
[10/16] tools/audit/check_launch_language_seed.py    EXIT 0  | === 启动语言种子接线机检（五条 fail-closed）===  [E1] MainApplication.onCreate 灌启动种子	ok  [E2] Compose 首帧 initialValue 取种子	ok  [E3] AppLocaleTracker.init 取种子（StringsProvider 首帧通道）	ok  [E5] AppLocaleTracker 慢路径补种（SP 无历史镜像时的兜底）	ok  [E4] RealSettingsRepository.setAppLanguage 镜像种子	ok  --- launch_language_seed=OK（五条接线齐全）---
[11/16] tools/doc/check_projection_read_safety.py    EXIT 0  | [check_projection_read_safety] 登记面文件 13/13；展示读口文件 16 个（白名单 16）；命中 0 处  [check_projection_read_safety] PASS
[12/16] tools/doc/check_raw_coroutine_scope.py       EXIT 0  | [check_raw_coroutine_scope] 检查文件 739 个（*/src/main/**）；允许清单 1 个；命中 0 处  [check_raw_coroutine_scope] PASS
[13/16] tools/doc/check_archive_monotonicity.py      EXIT 0  | [check_archive_monotonicity] 基线 HEAD~1：索引行 489 个 / 批次正文 487 份；现树 工作区：491 / 489；缺失 0 处  [check_archive_monotonicity] PASS
[14/16] tools/doc/check_plaintext_carrier_to_string.py EXIT 0  | [check_plaintext_carrier_to_string] 检查文件 739 个（*/src/main/**）；允许清单 0 个；命中 0 处  [check_plaintext_carrier_to_string] PASS
[15/16] tools/doc/check_message_not_in_user_text.py  EXIT 0  | [check_message_not_in_user_text] 检查文件 739 个（*/src/main/**）；文案槽 10 类；命中 0 处  [check_message_not_in_user_text] PASS
[16/16] tools/doc/check_cancellation_semantics.py    EXIT 0  | [check_cancellation_semantics] 检查文件 739 个（*/src/main/**）；命中 0 处  [check_cancellation_semantics] PASS
=== 汇总：16/16 PASS ===
```

### 设备侧

- `app/src/androidTest/**` **未改动** ⇒ 依测试资产纪律无需真机实跑；本批全部改动落在
  `app/src/main/java/com/keepasskey/app/autofill/**`（Kotlin 逻辑 + KDoc）与 `app/src/test/**`（宿主单测）。
- 本批不触碰原生面（`crypto/src/main/rust/**`、`jni_bridge_ext.rs`、任一 `Native*` 绑定），故不触发
  《测试资产纪律》②的「四层 `connectedDebugAndroidTest`」义务。

## 492.5 如实声明

- **方案取舍**：采用报告建议的**首选方案**——敏感值判据扩为「口令 ∨ TOTP」，**未**采用「仅对纯用户名通道豁免」
  的备选。理由：备选会一并取消口令场景下「用户名可例外」的既有取舍（`ISSUE-P1-24 AC③`），改动面更大且
  会**收紧**用户可见行为；首选方案只把 TOTP 与口令并列，口令场景逐字不变。
- **未采纳项**：未在 `skipPickerRepeatConfirmation`（选择器路径）加 TOTP 闸门。理由：该路径每次填充均由用户
  在受保护窗口内**显式点选**条目，不存在「无 UI 自动途径下发」形态（`ISSUE-P3-185` 已论证），TOTP 直填仅发生在
  数据集路径；本次不改其口径。
- **未发现新缺陷**：复核过程中未发现条目未提及的额外缺陷；`hasDeliverableTotp` 的判定与交付面
  `setField(otpId, …)` 的触发条件**逐字一致**，不存在「判携带而实际不填」或反向的假阳性 / 假阴性。
- **残余口径（非缺陷）**：本批只治「宽限豁免判据」，宽限开关自身的 30 秒 TTL / 域可归属 / 库解锁三重前置
  （`ISSUE-P2-81` / `ISSUE-P3-42`）维持不变。另：口令判据的保守口径（已清零亦判携带 ⇒ 宁可多确认一次）沿用
  `ISSUE-P2-539`，本批未放宽。
- **性能**：`hasDeliverableTotp` 对候选逐条调 `calculateEntryTotp`（走既有 `TotpCodeCache` 缓存，`ISSUE-P2-90`
  同型）；与交付面同批计算，重复调用由缓存吸收，无额外 KDF / 网络开销。仅当 `otpId != null` 时才统计
  （无 OTP 框的表单零开销）。
