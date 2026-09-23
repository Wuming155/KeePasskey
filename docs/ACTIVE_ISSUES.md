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

## P0 阻断级问题（0 项）

> **暂无开放项**。

---

## P1 高危与核心功能问题（0 项）

> **暂无开放项**（本区归零：§246 闭环 `ISSUE-P1-241`（「移除密码库关联」的确认文案承诺「不会删除物理文件」，而应用私有库的文件**会被真的删除**）——整改＝确认弹窗文案与动作按**存储类型**分列两套、判据落纯函数并单点化、数据层只在「应用私有库」分支删物理文件（产品口径落 `PD-17`）；真机逐字实证「界面声明与文件系统结果一致」（私有库删除后文件确已消失，外部库确认后文件原样在）。证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/246-移除密码库确认文案与真实行为一致批次.md`](resolved/batches/246-移除密码库确认文案与真实行为一致批次.md)。）

---

## P2 中危缺陷与协议/测试缺口（0 项）

> **暂无开放项**（本区归零：§271 闭环 `ISSUE-P2-271`（加密算法 / KDF 算法选择器假开关——对话框只改 UI 回显，库文件头未变）——整改＝两个选择器经 `CipherLabels` 词汇表反查算法 ID 后经 `updateDatabaseMeta` 写 `KdbxHeader.cipherUuid` / `kdfParameters` + `save()` 真实落库，换 KDF 变体补齐目标变体所需参数（Argon2 换型携带 I·M·P、AES-KDF 补官方缺省 rounds）、回显改单一真相源（init 头映射通道统一下发）；`app` 层 6 例 + `:database:` 层 5 例守卫锁定「选择 → 文件头真实变化 → 解锁成功」闭环；官方 keepassxc-cli 2.7.12 端到端对拍本仓产物报「Twofish 256 位 / Argon2d」实证。证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/271-算法选择器真实落库批次.md`](resolved/batches/271-算法选择器真实落库批次.md)。）

---

## P3 低危问题、特性接线与体验优化（3 项）

### ISSUE-P3-272：云端同步四项假开关（自动同步 / 事务化写入 / 预加载远程数据库 / 允许的 Wi-Fi SSID）

- **核实时间点**：2026-09-23 经全仓定向 grep 与消费方逐个核对核实。
- **核实方式**：① **自动同步** `autoSyncEnabled` 只活在 `SettingsSyncController` 的内存 `StateFlow`（`SettingsSyncController.kt:48/61/253-255`），`ExtendedSettingsStore` **无对应持久化键**；全仓 `autoSync` 命中仅设置页 / 投影 / 导航图，**无生产消费方**；缺省 `true`，重启即回 `true`。② **事务化写入** `useFileTransactions` **有**持久化键（`ExtendedSettingsStore.kt:82/171`，缺省 `true`），但全仓除 setter（`SettingsExtendedPreferencesController.kt:176`）/ 投影 / UI 外**无消费方**；本地写盘恒走 `AtomicFileWriter.writeAtomic`（`SessionFileWriter.kt:26-32`，条件仅为「是否生成 `.bak`」），上传恒走 `provider.uploadAtomic`（`SyncEngine.kt:192/259/287/347/415/448`，生产侧**无** `.upload(` 调用）——开关关不掉原子写。③ **预加载远程数据库** `preloadDatabaseEnabled` 有持久化键（`ExtendedSettingsStore.kt:85/174`），**零消费方**。④ **允许的 Wi-Fi SSID** `allowedWifiSsids` 有持久化键（同文件 `:72/167`），**零消费方**；同卡真正接线的只有 `wifiOnlySync`（`PeriodicSyncScheduler.kt:40/52` 映射 UNMETERED / CONNECTED）。
- **背景与根因**：三项「偏好只落盘不消费」+ 一项「偏好不落盘不消费」。前三项的界面文案已向用户承诺具体行为（`strings.xml`：`sync_auto_sync_sub`「数据变更后自动推送到云端」、`sync_file_tx_sub`「先写入临时文件再原子替换，意外中断不损坏数据库」、`sync_preload_sub`「Wi-Fi 下后台预取云端副本，加快解锁速度」）与「仅在指定 SSID Wi-Fi 下允许同步」（`SettingsUiState.kt:118`），实际行为与承诺无关；自动同步连回显都在重启后归位，属 `ISSUE-P2-228` 同型假开关。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsSyncController.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsExtendedPreferencesController.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/CloudSyncSections.kt`、`app/src/main/java/com/keepasskey/app/data/repository/ExtendedSettingsStore.kt`、`app/src/main/java/com/keepasskey/app/sync/PeriodicSyncScheduler.kt`、`app/src/main/java/com/keepasskey/app/sync/SyncProviderResolver.kt`、`database/src/main/java/com/keepasskey/database/session/SessionFileWriter.kt`、`sync/src/main/java/com/keepasskey/sync/engine/SyncEngine.kt`、`sync/src/main/java/com/keepasskey/sync/provider/SyncProvider.kt`。
- **验收标准**：AC① 四项逐一给出**明确结论**——「真实接线」或「如实移除 / 禁用并标注」（对齐 `ISSUE-P3-65`：不得长期保留可拨动的假开关）；AC② 若接线：Wi-Fi SSID 须接入网络约束判定并与 `wifiOnlySync` 合流为**同一判据**（避免两个入口语义重叠）、预加载与自动同步须接到具体触发点（冷启动 / 变更后 / 保存后）；**事务化写入**须先裁定其是否属可关闭项——原子写是本仓数据安全不变量（`AtomicFileWriter` / `uploadAtomic` 全路径无绕过），若裁定「不可关闭」，则**如实移除该开关**（或改只读展示并写明恒为开启）并登记 `PD-*`，不得保留「关了也没变化」的开关；AC③ 若移除：同步删除 setter / 状态字段 / 投影 / 字符串键（中英两侧），并核对字符串键成对与总数；AC④ 任一改动都须同步修订 `strings.xml` 与 `values-en/strings.xml` 文案，使其**仅**描述真实行为；AC⑤ 新增接线守卫用例，禁「UI 有开关、生产无消费方」复现（沿用 `AutofillChannelSwitchWiringTest` 的静态消费点计数口径）。

---

### ISSUE-P3-273：TOTP 字段映射与默认参数四项「假开关 + 不落盘」（种子字段名 / 设置字段名 / 默认步长 / 默认位数）

- **核实时间点**：2026-09-23 经全仓定向 grep 与解析侧逐点核对核实。
- **核实方式**：① **解析侧写死**：条目 TOTP 配置定位只认 `KdbxConstants.Fields.OTP`（`"otp"`）与自定义字段前缀 `VaultEntryMapper.TOTP_CUSTOM_FIELD_PREFIX`（`"TOTP"`，`VaultEntryTotpMapping.kt:19-32`；同型见 `VaultEntrySecretReader.kt:154/271`）；缺省参数写死 `DEFAULT_TOTP_PERIOD_SECONDS = 30` / `DEFAULT_TOTP_DIGITS = 6` / `DEFAULT_TOTP_ALGORITHM = "SHA1"`（同文件 `:98/106/124-126`）。② **零消费方**：全仓 `totpSeedFieldName` / `totpSettingsFieldName` / `defaultTotpStepSeconds` / `defaultTotpDigits` 命中仅 `ExtendedSettings` 字段、`SettingsExtendedPreferencesController` setter、`ExtendedSettingsStore` 读写、`SettingsUiStateProjection` 投影、`TotpSettingsScreen` 输入框——**无解析 / 取码侧消费方**。③ **不落盘**：唯一写入方 `updateTotpFieldMapping`（`SettingsExtendedPreferencesController.kt:191-200`）只调 `extendedSettingsStore.publish`（仅改内存权威快照，`ExtendedSettingsStore.kt:54-57` 自述「不改持久化」），**未调 `save`**；其 KDoc 亦自述「只更新内存 Flow、不落盘（ISSUE-P3-29 逐字保留）」。
- **背景与根因**：兼具两类缺陷——(a) **展示选择未应用**：用户在「TOTP 设置」页可自定义字段名与默认步长 / 位数，解析侧一律不理，故第三方库使用非 `TOTP` 前缀的自定义字段命名时行为与设置无关；(b) **回显漂移 + 丢失**：该映射仅驻内存快照，重启即回默认值（`TOTP Seed` / `TOTP Settings` / 30 / 6）；虽可能因其它偏好变更触发整表 `save` 被顺带写盘，但**不可依赖**（本项比「落盘但不消费」更弱）。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsExtendedPreferencesController.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/settings/ExtendedSettings.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiStateProjection.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/TotpSettingsScreen.kt`、`app/src/main/java/com/keepasskey/app/data/repository/ExtendedSettingsStore.kt`、`app/src/main/java/com/keepasskey/app/data/repository/VaultEntryTotpMapping.kt`、`app/src/main/java/com/keepasskey/app/data/repository/VaultEntrySecretReader.kt`、`core/src/main/java/com/keepasskey/core/model/KdbxConstants.kt`（`Fields.OTP`）。
- **验收标准**：AC① **不依赖裁决即可先行**：`updateTotpFieldMapping` 改走 `updateExtended`（内存 + 持久化原子完成），消除「改了不落盘」；AC② 四项须给出「接入解析侧」或「如实禁用 / 移除输入项并标注」的明确结论，不得长期保留可改却无效的输入项；AC③ 若接入解析侧：字段名以**设置值优先、官方键回退**的顺序参与 `parseTotpConfig` 定位——**不得移除** `otp` 标准字段与 `TOTP` 前缀回退（官方 / 第三方兼容性不放松），默认步长 / 位数传入取码路径，且不得绕过 `TotpKeyUriParser` 既有的 `period` / `digits` 钳制语义；AC④ 新增用例锁定「设置值真实参与解析 / 取码」闭环（含缺省回退路径），既有 TOTP / HOTP 用例不得回归；AC⑤ 修订中英文案，使其**仅**描述真实生效范围。

---

### ISSUE-P3-274：安全与外观两项假开关（记住最近打开的数据库 / 图标集风格）

- **核实时间点**：2026-09-23 经全仓定向 grep 核实。
- **核实方式**：① **记住最近打开的数据库** `rememberRecentFiles` 有持久化键（`ExtendedSettingsStore.kt:91/178/332`，缺省 `true`），全仓命中仅 setter（`SettingsExtendedPreferencesController.kt:66`）/ 投影（`SettingsUiStateProjection.kt:172`）/ 设置页开关（`SecuritySettingsScreen.kt:375-378`）——**零消费方**，全仓无「最近数据库」列表或记录存取实现；**对照**同卡相邻的 `rememberKeyFileLocation` 真实接线于解锁侧（`SafKeyFileAccess.kt:43` 实际读取该偏好），故本项不能以「同类均未接线」为由豁免。② **图标集风格** `iconSet` 有持久化键（`ExtendedSettingsStore.kt:139/199`），可三选一（Material / KeePass 经典 / 极简单色，`ThemeSettingsListSections.kt:241-245`），但全仓 `IconSetOption` 命中仅 settings 域（`ExtendedSettings.kt` / `SettingsExtendedPreferencesController.kt` / `SettingsUiState.kt` / `SettingsViewModel.kt` / `ThemeSettingsScreen.kt` / `ThemeSettingsListSections.kt`）+ store——**渲染侧无任何消费**。
- **背景与根因**：均为「偏好只落盘不消费」。`rememberRecentFiles` 的界面语义是**安全项**（是否保留打开历史），实际不记录任何东西，用户以为「关闭即不留痕」——属安全语义误导；`iconSet` 属展示类，改选后全站图标无变化。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsExtendedPreferencesController.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiStateProjection.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/SecuritySettingsScreen.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/ThemeSettingsListSections.kt`、`app/src/main/java/com/keepasskey/app/data/repository/ExtendedSettingsStore.kt`（消费方落点待定：最近库记录源 / 图标渲染层）。
- **验收标准**：AC① 两项逐一给出「真实接线」或「如实移除 / 禁用并标注」结论；AC② `rememberRecentFiles`：若接线，须有真实的「最近打开库」记录源与清理逻辑，**关闭即不写入且清理既有记录**（对齐 `createBackupBeforeSave` 关闭时顺带清理历史 `.bak` 的既有口径），并说明记录存储位置与清理时机；若不接线则**移除**该项（安全项不得停留在「看起来能控制痕迹」的假状态）；AC③ `iconSet`：若接线，须把选择真实接入图标渲染层并覆盖三种风格；若不接线则如实禁用并标注（避免「可三选一但全站无变化」）；AC④ 修订中英文案，使其**仅**描述真实行为；AC⑤ 新增接线守卫用例，禁「UI 有开关、生产无消费方」复现。

---
