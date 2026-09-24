<a id="s301"></a>

# §301 TOTP 字段映射与默认参数接线批次

> `ISSUE-P3-273` **整条闭环**（P3 12 → **11 项**）。
> 覆盖面：`ISSUE-P3-273`（本体，TOTP 字段映射与默认参数四项「假开关 + 不落盘」）。
> 生产改动＝四项全部**接入解析侧**（非移除）+ `updateTotpFieldMapping` 改走统一变更通道落盘；
> 新增闭环守卫 **12 例**；`TotpKeyUriParser` 增「缺省参数可注入」能力（钳制语义不动）。

---

## 1. 条目正文（原样收录）

### `ISSUE-P3-273`：TOTP 字段映射与默认参数四项「假开关 + 不落盘」（种子字段名 / 设置字段名 / 默认步长 / 默认位数）

- **核实时间点**：2026-09-23 经全仓定向 grep 与解析侧逐点核对核实。
- **核实方式**：① **解析侧写死**：条目 TOTP 配置定位只认 `KdbxConstants.Fields.OTP`（`"otp"`）与自定义字段前缀 `VaultEntryMapper.TOTP_CUSTOM_FIELD_PREFIX`（`"TOTP"`，`VaultEntryTotpMapping.kt:19-32`；同型见 `VaultEntrySecretReader.kt:154/271`）；缺省参数写死 `DEFAULT_TOTP_PERIOD_SECONDS = 30` / `DEFAULT_TOTP_DIGITS = 6` / `DEFAULT_TOTP_ALGORITHM = "SHA1"`（同文件 `:98/106/124-126`）。② **零消费方**：全仓 `totpSeedFieldName` / `totpSettingsFieldName` / `defaultTotpStepSeconds` / `defaultTotpDigits` 命中仅 `ExtendedSettings` 字段、`SettingsExtendedPreferencesController` setter、`ExtendedSettingsStore` 读写、`SettingsUiStateProjection` 投影、`TotpSettingsScreen` 输入框——**无解析 / 取码侧消费方**。③ **不落盘**：唯一写入方 `updateTotpFieldMapping`（`SettingsExtendedPreferencesController.kt:191-200`）只调 `extendedSettingsStore.publish`（仅改内存权威快照，`ExtendedSettingsStore.kt:54-57` 自述「不改持久化」），**未调 `save`**；其 KDoc 亦自述「只更新内存 Flow、不落盘（ISSUE-P3-29 逐字保留）」。
- **背景与根因**：兼具两类缺陷——(a) **展示选择未应用**：用户在「TOTP 设置」页可自定义字段名与默认步长 / 位数，解析侧一律不理，故第三方库使用非 `TOTP` 前缀的自定义字段命名时行为与设置无关；(b) **回显漂移 + 丢失**：该映射仅驻内存快照，重启即回默认值（`TOTP Seed` / `TOTP Settings` / 30 / 6）；虽可能因其它偏好变更触发整表 `save` 被顺带写盘，但**不可依赖**（本项比「落盘但不消费」更弱）。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsExtendedPreferencesController.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/settings/ExtendedSettings.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiStateProjection.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/TotpSettingsScreen.kt`、`app/src/main/java/com/keepasskey/app/data/repository/ExtendedSettingsStore.kt`、`app/src/main/java/com/keepasskey/app/data/repository/VaultEntryTotpMapping.kt`、`app/src/main/java/com/keepasskey/app/data/repository/VaultEntrySecretReader.kt`、`core/src/main/java/com/keepasskey/core/model/KdbxConstants.kt`（`Fields.OTP`）。
- **验收标准**：AC① **不依赖裁决即可先行**：`updateTotpFieldMapping` 改走 `updateExtended`（内存 + 持久化原子完成），消除「改了不落盘」；AC② 四项须给出「接入解析侧」或「如实禁用 / 移除输入项并标注」的明确结论，不得长期保留可改却无效的输入项；AC③ 若接入解析侧：字段名以**设置值优先、官方键回退**的顺序参与 `parseTotpConfig` 定位——**不得移除** `otp` 标准字段与 `TOTP` 前缀回退（官方 / 第三方兼容性不放松），默认步长 / 位数传入取码路径，且不得绕过 `TotpKeyUriParser` 既有的 `period` / `digits` 钳制语义；AC④ 新增用例锁定「设置值真实参与解析 / 取码」闭环（含缺省回退路径），既有 TOTP / HOTP 用例不得回归；AC⑤ 修订中英文案，使其**仅**描述真实生效范围。

**开工前前提复核（2026-09-24）**：成立，四项全部复现。① `VaultEntryTotpMapping.parseTotpConfig` 与 `VaultEntrySecretReader` 两处（`getEntryTotpSecretChars` / `getEntryRevisionSnapshot`）各自写了一份「`otp` 优先 + `TOTP` 前缀回退」的定位逻辑——**同一优先序三份拷贝**，设置值无任何入口；② 缺省 `30` / `6` 在 `projectTotpFields` 与 `TotpKeyUriParser` 内各写死一份；③ `updateTotpFieldMapping` 确只 `publish` 不 `save`。

---

## 2. 整改

### 2.1 四项逐项结论（AC①②）

| 项 | 结论 | 落点 |
|---|---|---|
| `totpSeedFieldName`（种子字段名） | **接入解析侧** | 单一真相源 `VaultEntryTotpMapping.locateConfigSource` 的**第一优先**候选 |
| `totpSettingsFieldName`（设置字段名） | **接入解析侧** | 同一函数的**第二优先**候选（未命中第一候选时） |
| `defaultTotpStepSeconds`（默认步长） | **接入取码路径** | 作为 `TotpKeyUriParser.parse` 的缺省参数注入，仅条目未声明 `period` 时生效 |
| `defaultTotpDigits`（默认位数） | **接入取码路径** | 同上（注入 `digits` 缺省值），**仍受 `6..8` 钳制** |

四项一律走**接入**分支，不涉及「如实禁用 / 移除」，故不再另立 `PD-*`。

### 2.2 字段定位收敛为单一真相源（AC③）

新增 `VaultEntryTotpMapping.locateConfigSource(entry, preferences)`，优先序为
**① 用户设置的两个字段名（种子 → 设置；`fields` 与 `customFields` 两处查找、忽略大小写）
→ ② 官方标准 `otp` 字段 → ③ `TOTP` 前缀自定义字段**。

- 「设置值优先、官方键回退」按条目 AC 原文实现；②③ 是官方 / 第三方兼容性底线，**未移除**。
- 原先的三份拷贝全部改走该函数：`parseTotpConfig`（解析）、`getEntryTotpSecretChars`（编辑页回填）、
  `getEntryRevisionSnapshot`（修订快照）。`VaultEntrySecretReader` 内的本地优先序**已删除**。
- 由此消除一类隐性缺陷：整改前若只改解析侧，编辑页回填仍按旧优先序取字段，
  「看到的种子」与「算出的码」可能来自不同字段。

### 2.3 缺省参数注入（AC③，不绕过钳制）

`TotpKeyUriParser.parse(uriOrSecret, defaultPeriodSeconds, defaultDigits)` 新增**带默认值的参数**
（原签名 `parse(bytes)` 行为完全不变，core 既有用例原样通过）。注入值在 `parse` 入口先过钳制：

- `defaultPeriodSeconds <= 0` ⇒ 回落内置 `30`；
- `defaultDigits !in 6..8` ⇒ 回落内置 `6`。

条目**自身声明**的 `period` / `digits` 恒优先于注入值；越界样本仍带 `warnings` 诊断回落
（禁静默改写，`ISSUE-P2-289` 口径不变）。

### 2.4 偏好载体与落盘（AC①）

- 新增 `TotpPreferences`（四字段快照）+ `TotpPreferencesSource`（`fun interface`）+
  `TotpPreferencesModule`（Hilt，读 `ExtendedSettingsStore.settings.value` 内存权威快照）。
  **刻意不用 `store.load()`**：本通道位于 TOTP 热路径（列表页逐条目投影 + 每秒取码），
  `load()` 每次重新反序列化全量偏好。
- **缺省值不另立常量**——`TotpPreferences.of(ExtendedSettings)` 单一来源，
  杜绝「UI 默认值 ⇄ 解析侧默认值」两处漂移（`ISSUE-P2-43` 同型教训）。
- `VaultEntryMapper` / `VaultEntrySecretReader` 各增一个默认值为 `{ TotpPreferences.DEFAULT }` 的
  通道参数（纯 JVM 单测不改即可编译）；`RealVaultRepository` 增注入参数并向下传导。
- `updateTotpFieldMapping` 改走 `updateExtended`（内存 + 落盘原子完成）。

### 2.5 缓存失效判据补全（整改中发现的连带面）

`VaultEntrySecretReader` 的验证码缓存原命中判据只有「同一周期号」。用户改动字段名或默认位数后，
按旧参数算出的码会在旧周期的剩余窗口内继续下发。`CachedTotp` 现增存 `preferences`，
命中时比对等值，参数变更即失效。

### 2.6 文案（AC⑤）

| 键 | 改前 | 改后 |
|---|---|---|
| `totp_presets_desc` | 不同桌面端 KeePass 插件使用不同的 TOTP 种子字段名，选择预设可提升兼容性 | 说明「配置的字段名优先用于查找；未命中时回退官方 `otp` 字段与 `TOTP` 开头的自定义字段」 |
| `totp_preset_traytotp` / `totp_preset_keeotp` | 两条均为 `TrayTotp ·otp` / `KeeOtp ·otp`（**且与真实预设值不符**：前者实际填 `TOTP Seed`+`TOTP Settings`，后者填 `otp`+`otp_settings`） | `TrayTotp` / `KeeOtp`（真实取值由下方两个输入框呈现，去掉误导性的 `·otp`） |
| `totp_section_defaults` | 默认参数 | 默认参数（仅当条目未声明时生效） |
| `totp_info_desc` | …完全兼容 RFC 6238 HMAC-SHA1 / SHA256…自定义字段名主要用于双向兼容第三方桌面 KeePass 插件生成的数据结构 | 写明**查找顺序**、**默认参数的生效范围**、**位数 6~8 钳制**；算法更正为 `HMAC-SHA1 / SHA256 / SHA512`（`computeTotpCode` 实际支持三档，原文漏了 SHA512） |

---

## 3. 验证

### 3.1 新增守卫用例（AC④）：`app/src/test/java/com/keepasskey/app/data/repository/TotpFieldMappingWiringTest.kt`（12 例）

**行为层（设置值真实参与）**
1. `设置字段名优先于官方 otp 字段`——同一条目同时含自定义字段与 `otp`，设置命中者胜出；
2. `设置值未命中时回退官方 otp 字段`；3. `设置值未命中时回退 TOTP 前缀自定义字段`（兼容性底线）；
4. `设置字段名本身参与定位且忽略大小写`（默认值 `TOTP Seed` 命中小写写法 `tOTP sEED`）；
5. `无任何 TOTP 来源时返回 null`。

**参数层（缺省值生效且不越权）**
6. `默认步长与位数在条目未声明时生效`（纯 Base32 + 60/8 ⇒ period=60、digits=8）；
7. `条目已声明的周期与位数不被设置值覆盖`（URI 内 `period=30&digits=6` + 注入 60/8 ⇒ 仍 30/6）；
8. `越界的设置值仍受内置钳制不得绕过`（注入 `0` / `9` ⇒ 回落 30 / 6）。

**缓存层**
9. `解析参数变更后验证码缓存立即失效并重算`（实例身份判据：参数未变 `assertSame`、变更后 `assertNotSame` 且位数 6 → 8）。

**静态层（防回退）**
10. `TOTP 映射写入走统一变更通道且偏好键读写两侧齐备`；
11. `字段定位在解析与回填三处共用同一实现`；
12. `生产通道按内存权威快照取解析参数`。

> **测试自身踩坑留痕**：首版第 8 例用 `defaultDigits = 7` 作「越界样本」——**7 在 `6..8` 内**，
> 钳制不该拦，断言自相矛盾而红（实现正确）。改用 `9` 后成立。
> 第 11 例首版断言 `locateConfigSource` 在 `VaultEntrySecretReader` 出现 **3** 次——
> 实际为 **2** 次（解析路径经 `VaultEntryMapper` 间接委派），断言与口径不符而红。
> 两处均为**用例缺陷**，非实现缺陷。

### 3.2 全量回归

- `.\gradlew.bat test --max-workers=1` → **BUILD SUCCESSFUL**；
- 聚合计数（`python tools/doc/count_test_results.py`）：**`xml=394 tests=2679 failures=0 errors=0 skipped=13`**
  （+12，即本批新增守卫；`skipped=13` 与基线持平，仍为无真实语料的 Assumption 例）；
- core 既有 `TotpKeyUriParser` / HOTP 用例**原样通过**（AC④「既有 TOTP / HOTP 用例不得回归」）。

### 3.3 机检

| 机检 | 结果 |
|---|---|
| `check_md_links.py` / `check_resolved_index_sync.py` / `check_bounded_type_names.py` | EXIT 0 |
| `check_tautological_assertions.py` / `check_recheck_consistency.py` | EXIT 0 |
| `count_line_tiers.py` | 仍为 EXIT 1（`tier1=4`，**既存**，见 `ISSUE-P3-305`） |
| `long_functions.py` | 仍为 EXIT 1（`functions_ge_100=2`，**既存**，同上） |

---

## 4. 如实声明

1. **未新增 `PD-*`**：四项一律取「接入解析侧」分支，AC② 的「如实禁用 / 移除并标注」分支未触发。
2. **删除了一处死代码**：`VaultEntrySecretReader.readErasableChars`（`ProtectedString? → CharArray?`）
   在本次整改后失去全部调用点（修订快照改走 `VaultEntryTotpMapping.locateConfigSource(...).readChars()`），
   按「删除死代码」口径移除，并在原位留一行说明；需要该能力处直接调 `ProtectedString.readChars()`
   （同为独占副本语义）。**未删除任何用例或测试资产**。
3. **`hygiene-gate` 两条仍为红**（`tier1=4` / `functions_ge_100=2`），属 `ISSUE-P3-305` 的范围，
   本批未触碰；不以「test 全绿」冒充「门禁全绿」。
4. **无设备侧必跑项**：未触 `*/src/androidTest/**`、未改 KDBX 序列化 / 合并 / 附件 / 原生内核；
   本批不涉 KPEX 互操作面，故未跑 `tools/passkey-interop/verify_interop.py`。
5. **未跑**：`lint` / `assembleRelease` / 截图包装编译门禁（未改 `@Preview` 与预览生成器）。
