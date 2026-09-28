# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。
> **只放现存问题**：本文件**只保留尚未闭环的条目**——已闭环条目的正文、批次流水与索引段一律不留；历史实现与验收证据以 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 `docs/resolved/batches/` 为单一真相源。
> **实施前必读**：源自安全复核的条目，其 AC 已按 [`SECURITY_RECHECK_2026-09.md`](security/SECURITY_RECHECK_2026-09.md) §10 / §11 的**第四轮独立复核定版结论**逐条对齐，更正口径已内联到对应条目——照条目原文 AC 实施者可能**降低安全性**或**引入数据损坏**。
> **例外（`ISSUE-P1-276` ～ `ISSUE-P3-299`）**：这批 2026-09-23 条目出自**两轮全仓审查**（8 个铺开代理 + 7 个对抗复核代理，反向口径＝先假设误报再取证），不属上述复核范围；其依据为各条「核实方式」所记的逐行反校与对抗推翻结论。凡条目标注「**未经对抗轮单独攻击**」或「**需外部证据（真机 / 真实 DAV 矩阵 / 官方对拍）**」者，认领时须按规则 6.1② 先自行复核前提，不得直接开工。
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

> **暂无开放项（全量待办归零）**。2026-09-25 解锁节流完整性层 fail-closed 缺陷与冗余性裁决
> （`ISSUE-P1-277`，完整性层整体移除 / 基础节流保留，`PD-46`）闭环见 §334。

---

## P2 中危缺陷与协议/测试缺口（0 项）

> **暂无开放项**。2026-09-28 深度交互审查登记的 5 条（`ISSUE-P2-353` ~ `ISSUE-P2-357`）当日整批整改闭环，
> 归档见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md) §349 与批次正文
> [`349-交互体验深度审查整改批次.md`](resolved/batches/349-交互体验深度审查整改批次.md)；
> 2026-09-27 两条真机手测缺陷（`ISSUE-P2-341` / `ISSUE-P2-343`）分别收口于 §343 与 §344；
> 更早的 P2 闭环流水见 `RESOLVED_LOG.md` §315 ~ §325。

## P3 低危问题、特性接线与体验优化（11 项）

> **开放项 11 条**（这里只列**各条还欠什么**；已完成的实施细节留在条目正文与 commit 里，不重复登记）：
> ① `ISSUE-P3-339` —— **用户指示暂时搁置**（浏览器半环需外部域名与信任链资源）。
> ②~⑥ `ISSUE-P3-361` ~ `ISSUE-P3-365` —— 2026-09-28 用户命题「全面排查存量假开关」的清点产物：
> 严格假开关在两个设置模型（`UserSettings` 26 字段 / `ExtendedSettings` 41 字段）与其余 UI 开关中**仅 1 处**
> （`autoReturnFromQuery`，已按批次 37 D3 禁用+标注，不再可拨动，不另立条）；以下五条均为排查中**新证实**的
> 「消费面小于宣称面 / 回显分叉 / 覆盖面不足」类近似缺陷，逐条独立核实时间点与方式见各条目。
> ⑦~⑪ `ISSUE-P3-366` ~ `ISSUE-P3-370` —— 2026-09-28 参考项目对照评估补登的五条 P3 小项
> （自动锁后台时间戳持久化 / 写侧恒写 4.1 / 解析进度回调 / 通用占位符引擎 / getCheckKey 同型通道）；
> 同次评估发现的三项能力差距已按用户裁决归位产品裁决表 `PD-53` ~ `PD-55`，属取舍不属缺陷。
> 2026-09-28 深度交互审查新增的 `ISSUE-P3-358` ~ `ISSUE-P3-360` 三条已同批闭环于
> [`RESOLVED_LOG.md`](RESOLVED_LOG.md) §349。
> `ISSUE-P3-345`（无匹配「就地新建并用于本次填充」）收口于 `RESOLVED_LOG.md` §347：主体整改落
> commit `37fdf145`，Autofill 通道真机日志坐实（§347.1），CM 浏览器面按用户裁决（`PD-52`：目标生态
> **非 Chrome / 非 GMS**）收口——呈现面登记入限界表 §36。
> `ISSUE-P3-340`（`@Preview` 状态覆盖普查 / 规则条文）按用户裁决收口于 `RESOLVED_LOG.md` §346：
> 普查**未**升级为 `hygiene-gate` 第九条，维持「只出读数、不进 CI」（闸门化前置——带理由的显式豁免清单
> ——指针留在该批正文 §346.2）；2026-09-28 UI 现代化批次（`ISSUE-P3-346` ~ `ISSUE-P3-351` 六条，含两条
> 登记即复核否决的 350 / 351 中 350 归档注明）收口于 `RESOLVED_LOG.md` §345；
> 更早的 P3 闭环流水见 `RESOLVED_LOG.md` §326 ~ §344（`ISSUE-P3-337` / `ISSUE-P3-342` 收口于 §343，
> `ISSUE-P2-343` 收口于 §344）。

### ISSUE-P3-339：仿冒域能否唤醒通行密钥（本地 RP 实验室 + 四类仿冒 origin 的「该醒 / 不该醒」）——**代码层已闭环，浏览器半环搁置**

- **状态（2026-09-27 用户指示）**：**暂时搁置**。剩余部分需要外部资源（自有域名 + ACME DNS-01，
  或企业策略放行自建 CA，或换自带信任库的浏览器并相应重述判据），用户裁「先精简描述、暂不做」。
- **威胁模型（本条的立规前提，勿在后续复用时被悄悄替换）**：**真实攻击者只有公开 DNS 与公网可信证书，
  没有任何设备篡改能力**。⇒ 早期"靠 `adb root` 注系统 CA + 改 hosts"的做法**已判为不成立并撤销**
  （用户质疑「真实场景中你怎么可能安装别的 CA 证书，还弄到系统目录去？」）；
  一切结论必须出自**攻击者可达的**配置。
- **已闭环的半环（代码层，2026-09-27）**：表驱动用例首跑即红两条，都是实测越界、随批修掉——
  ① `KeePasskeyCredentialProviderService.findMatchingEntries` 的 URL 兜底不区分条目类型
  ⇒ 「rpId 属 A 域、条目 URL 写了 B 域」的 passkey 会被列进 B 域候选（**凭据存在性跨域泄露**，
  签名侧另有 rpId 复核故断言交不出去，但用户一点就在签名处撞上拒绝）；收紧为
  「URL 兜底只服务口令/密码条目，passkey 在域维度只认 `passkeyRpId` 一个真相源」；
  ② `PublicSuffixList.normalizeHost` 去 DNS 根点而 `DomainMatcher.extractDomain` 不去
  ⇒ 同一主机名在两个归一器手里答案不同（尾点 origin 的合法凭据不出候选，公共后缀查找却判同域）；
  现由 `extractDomain` 统一剔除末点。新增 `CredentialProviderLookalikeMatchTest` 5 例
  （真域/子域正向 ＋ 后缀堆叠/异域/punycode 同形/前缀粘连负向 ＋ IP origin  尾点大小写），
  **每条负向都配同源正向对照**。⇒ 细节见 commit `0199bc69`。
- **一条设计更正（登记以免复发）**：`rp.testlab.xyz` 与 `rp.testlab.xyz.phish.testlab.xyz` 的 eTLD+1
  同为 `testlab.xyz` ⇒ 对 WebAuthn 是**同站**，原设计的仿冒矩阵在该形态下无效
  （代码层不受影响，因 `isDomainMatch` 是标签后缀判定，三条堆叠负向实测均正确拒绝）。
- **未闭环的半环（浏览器 → 系统 CM → 本应用 provider）**：**未做仿冒域矩阵**，且已判死三条候选路线——
  ⚠️ **但「能不能到达 provider」这一项已由真机读数排除**（2026-09-27 19:55，`mark.via` →
  系统 CM → 本应用 provider → 断言，RP 侧 AAGUID 等于本应用 `DEFAULT_AAGUID`；
  见 `ISSUE-P3-337` 的「19:55 真实 RP 的 PRF 端到端读数」）⇒ 剩余阻塞**只剩**域名与信任链。
  AVD 上 GMS 自带的 FIDO 栈会截走请求（provider 日志命中 0 次）、实验机上 Firefox 的 `get()` 永不返回、
  `adb shell cmd credential` 无 shell 实现 ⇒ 无入口直接下发 `GetCredentialRequest`。
  实验室载体已落地可复用：`tools/passkey-phish-lab/`（自建 CA + 多 SAN 证书、HTTPS RP 自跑页、
  只读 CBOR 子集、6 用例矩阵驱动；`rp_server` 默认只绑 127.0.0.1，局域网零暴露）。
  ⚠️ 该矩阵的判据本身被实测纠过一次：原版把「六例全无浏览器读数」判成 PASS——**环境假绿**，
  已改为「无浏览器读数 ⇒ 无效，不得计为通过」。
- **红线（不变，优先级高于任何进度）**：**在破不通真实信任锚之前，本条目下不得出现任何
  「仿冒域不会唤醒」的结论**。今天能声称的只有「本应用对给定 rpId 的接受/拒绝判定」，
  **不是**「Chrome 不唤醒仿冒站」（那是上游 origin 校验，未证）。
- **载体顺带承接**：`ISSUE-P3-337` 的 AC⑧(b)（对真实 RP 完成 GetAssertion）与未决 6 计数器跳变实测出口。
- **关联**：`ISSUE-P3-337` / `PD-32`、`PD-33`（DAL 与 caller origin 归因）/ commits
  `db332b78`、`8e2a7700`、`fa283ea2`、`0199bc69`、`dc741803`（完整核实与读数）。

---

### ISSUE-P3-361：「仅 Wi-Fi 同步」开关消费面小于其文案承诺（只约束周期后台同步）

- **核实时间点与方式（2026-09-28）**：全仓 `wifiOnly|UNMETERED|wifi_only` grep 亲证——唯一行为消费点为
  `app/src/main/java/com/keepasskey/app/sync/PeriodicSyncScheduler.kt:52`（WorkManager 周期任务网络约束
  UNMETERED / CONNECTED）；手动「立即同步」`SettingsSyncController.triggerSync`、冷启动
  `SettingsColdStartSyncGate`、解锁后自动同步 `VaultListViewModel.kt:198-201` **均不读该值**。
  文案 `values/strings.xml:796-797`（`sync_wifi_only_title` / `sync_wifi_only_sub`
  「移动网络下暂停大文件同步以节省流量」）。守卫 `CloudSyncSwitchWiringTest.kt:151-158` 只断言存在性，
  未断言覆盖面。三路并行清点（`UserSettings` / `ExtendedSettings` / 其余 UI 开关）交叉得出。
- **背景与根因**：开关持久化齐备（`ExtendedSettingsStore.kt:316/383` 独立键）且真实接线（`PD-39` 明确
  「`wifiOnlySync` 真实接线不动」），但**消费面只有周期后台一路**——移动数据下用户手动同步 / 冷启动 /
  解锁自动同步照常执行，文案承诺的「移动网络下暂停」不成立；且约束粒度是「整周期任务」而非「大文件」。
  属 `ISSUE-P3-65` 口径的「消费面小于宣称面」，非严格假开关。
- **验收标准**：AC① 二选一并留痕——(a) **接线**：手动 / 冷启动 / 解锁自动同步入口合流同一 Wi-Fi 判据，
  或 (b) **改文案**：如实限定为「仅约束定时后台同步」，中英两侧成对；AC② 若接线，必须与 `wifiOnlySync`
  单一判据合流，**不得**新增第二开关或复活 SSID 名单（`PD-39` 边界）；AC③ 守卫从「存在性」升级为
  「覆盖面」断言（消费点计数或入口合取断言，参考 `AutofillChannelSwitchWiringTest` 口径）。

### ISSUE-P3-362：安全设置「自动锁定超时」冷启动回显恒为「立即」，与真实生效值分叉

- **核实时间点与方式（2026-09-28）**：亲证 `SettingsPreferencesController.kt:69-73`
  `securityTimeoutStateFlow` 初值硬编码 `autoLockTimeoutSeconds = 0`（= 立即）；全仓
  `securityTimeoutStateFlow|SecurityTimeoutUiState(|setAutoLockTimeout` grep 确认**唯一写入者**是
  `setAutoLockTimeout`（`:326-333`，用户点选才写），**无任何从 `settingsRepository` 播种的代码**；
  投影 `SettingsUiStateProjection.kt:177` 只读 `secState`；行为侧 `AutoLockManager.kt:145` /
  `AutoLockSessionGuard.kt:70-76` 读仓库真值（持久化默认 60 秒）。显示点
  `SecuritySettingsScreen.kt:210` `selectedValue = uiState.autoLockTimeoutSeconds`。
  两路独立代理交叉证实 + 本人逐行复核。
- **背景与根因**：回显流与行为流不同源——冷启动后进安全设置页，选中 chip 恒显示「立即」（0），
  而实际生效的是持久化的 60 秒（或用户上次选择），直到用户再次点选才对齐。用户会误判已设「立即锁定」。
  写侧 / 持久化 / 行为消费全部真实，属**回显漂移**而非假开关。
- **验收标准**：AC① 回显单一真相源——init 时从 `settingsRepository` 播种 `securityTimeoutStateFlow`，
  或投影改读 `userSettings.autoLockTimeoutSeconds`（二选一，禁双源并行）；AC② 拨动即写仓库且回显同步，
  冷启动回显 == 仓库值；AC③ 新增用例锁定「冷启动回显 == 持久化值」（含默认 60 与自定义档、-1 永不档）；
  AC④ **不得**改动行为侧默认 60 与 `AutoLockTimeoutPolicy` 语义。

### ISSUE-P3-363：`lockWhenScreenOff` 双存储（DataStore 镜像 + ExtendedSettings 键）无对账，迁移用户回显可能≠行为

- **核实时间点与方式（2026-09-28）**：亲证 `SettingsExtendedPreferencesController.kt:50-53`
  `setLockWhenScreenOff` 双写（`updateExtended` → SharedPreferences `keepasskey_extended_settings` +
  `persistLockWhenScreenOff` → DataStore）；行为消费只读 DataStore 侧（`AutoLockSessionGuard.kt:47`
  读 `UserSettings.lockWhenScreenOff`，键 `RealSettingsRepository.kt:240`）；UI 回显只读 ExtendedSettings 侧
  （`SettingsUiStateProjection.kt:179` `extState.lockWhenScreenOff`）。DataStore 侧有 legacy
  `keepasskey_settings` 一次性迁移（`RealSettingsRepository.kt:63-85`），ExtendedSettings 是独立文件
  （`ExtendedSettingsStore.kt:324`）——两键**无任何对账机制**。两路独立代理交叉证实。
- **背景与根因**：正常拨动路径双写一致；但迁移用户的 DataStore 可能是旧值（如 false），而 ExtendedSettings
  键缺失回落默认 true ⇒ **设置页显示值 ≠ 实际熄屏行为**，且在用户首次拨动前持续分叉。属同名偏好双持久化的
  工程债（`lockWhenScreenOff` 镜像双写形态，`ExtendedSettings` 副本本身无行为消费方）。
- **验收标准**：AC① 选定单一真相源并对账——冷启动/迁移时以一方播种另一方，或删除冗余键改单源
  （回显与行为读同一处）；AC② 新增用例锁定「两键同值」（含 legacy 迁移路径与键缺失缺省路径，
  参考 `CHANNEL_SWITCH_DEFAULT` 数据类默认 ⇄ 单键缺省同值用例口径）；AC③ **不得**放松熄屏锁定语义
  （`AutoLockSessionGuard` 判定条件一行不改）。

### ISSUE-P3-364：触觉反馈开关真实但覆盖面仅 11 触点，高频动作永无触感（用户报「开了没用」）

- **核实时间点与方式（2026-09-28，用户命题本体）**：接线链亲证**完整非假开关**——
  `KeePasskeyApp.kt:93` `HapticsEnabled provides appSettings.hapticFeedbackEnabled` 注入 →
  `ui/components/Haptics.kt:17,27-32` `rememberMaybeHaptic` 门控 → 全仓 `.performHapticFeedback(` 仅
  `Haptics.kt:32` 一处、`LocalHapticFeedback.current` 仅 `:28` 一处（**无裸调**，§349 `ISSUE-P3-358` 收口
  无回归）；开关行 `ThemeSettingsListSections.kt:101-106` → `SettingsUiStateProjection.kt:201` 真实回读。
  **覆盖面**：现有 11 处触点（复制密码 ×3、复制用户名 ×2、密码明文切换、重新生成、滑杆、删除确认 ×4）；
  零命中包亲证——`ui/screens/settings/`、`unlock/`、`edit/`、`authenticator/` 整包 grep
  `rememberMaybeHaptic|performHapticFeedback` = 0；高频复制动作**无触感**：TOTP 徽标复制
  `VaultEntryRowLayouts.kt:343`、详情页复制 TOTP `EntryDetailCards.kt:175`、认证器复制
  `AuthenticatorScreen.kt:198`、生成器**复制**按钮 `GeneratorDisplayCard.kt:113`（同组件「重新生成」有）。
  另证：`EntryDetailCards.kt:35-36` 残留两个未使用 haptic import（死导入非裸调）。
  权限口径：2026-09-28 经 Google 开发者文档核实 `performHapticFeedback` **不需要** `VIBRATE` 权限
  （Manifest 无该权限不构成失效根因）；系统侧触感强度 / 触感反馈设置需真机复核。
- **背景与根因**：用户开「触觉反馈」后期望复制等操作有震动，但覆盖面只到 7 个文件 11 个触点；
  TOTP / 验证码复制是最高频动作却无触感 ⇒ 「开了没用」的体感来源。开关本体不是假开关，属
  **部分生效 / 覆盖面不足**。
- **验收标准**：AC① 至少补齐用户可感知的高频动作：TOTP / 验证码复制（列表徽标 + 详情页 + 认证器页）、
  生成器复制、详情页「打开网址」chip 与 TotpCard 三按钮（与同排已有点对齐）；AC② 所有新增触点一律经
  `rememberMaybeHaptic` 收口，**禁止**裸调 `LocalHapticFeedback`；AC③ 清除 `EntryDetailCards.kt:35-36`
  死 import；AC④ 不得给纯导航 / 装饰性点击滥加触感（沿用 §349 的克制原则：确认类 Reject、复制类 Confirm、
  轻揭示 SegmentTick）；AC⑤ 若真机复测开关开合仍无任何震动，另立条排查系统触感设置 / 设备能力，
  不得在本条内以「接线完成」冒充「用户可感知」。

### ISSUE-P3-365：`appLanguage` 强制语言不覆盖 AutofillUnlockActivity / CredentialUnlockActivity 两条独立入口

- **核实时间点与方式（2026-09-28）**：对照主外壳应用链 `KeePasskeyApp.kt:76-93` +
  `AppShellLocalization.kt:15-36`（`localeFor` → `createConfigurationContext`）；对两文件全文 grep
  `Locale|appLanguage|createConfigurationContext|configurationWithLocale` **0 命中**（`rg -c` 无输出 =
  零匹配）。行为消费本身真实（`themeMode` 等其余设置在两 Activity 均读），仅语言作用域缺口。
- **背景与根因**：用户强制选择「简体中文 / English」后，从自动填充解锁或 Passkey 独立解锁 Activity
  进入时仍走系统语言，与主界面语言不一致。属作用域缺口，是否算缺陷可由产品口径确认
  （若裁定「这两屏跟随系统可接受」则转如实标注并登记 `PD-*`）。
- **验收标准**：AC① 二选一——(a) 两 Activity 应用与主外壳**同源** Locale 覆盖（复用
  `AppShellLocalization` 不得复制粘贴第二份实现），或 (b) 裁定维持系统语言并如实标注 + 登记 `PD-*`；
  AC② 若 (a)，新增用例锁定「两 Activity 的配置上下文 == 主外壳同语言配置」；AC③ 不得顺带改动
  两 Activity 的其余设置读取链。

### ISSUE-P3-366：自动锁后台时间戳不持久化（进程重建后超时判定丢失）；且无长任务挂锁机制

- **核实时间点与方式（2026-09-28）**：直读 `app/src/main/java/com/keepasskey/app/security/AutoLockManager.kt:60,118`——
  `backgroundTimestamp` 为内存变量，无 DataStore / Preferences 持久化键（全仓检索零命中）；
  「长任务挂锁」经检索 `temporarilyDisable` 类符号零命中，现仅 `UnsavedEditRegistry` 脏表单拦截部分覆盖
  （§349 闭环面）。
- **背景**：KeePassDX 两个细节（`docs/references/KeePassDX-架构分析.md` §8.2-7）：① 超时时间戳持久化到
  Preferences，进程被杀后 Alarm 恢复仍能判定超时；② 长任务期间 `temporarilyDisableTimeout` 挂起锁。
  本仓会话驻进程内存、进程死亡即会话消失、冷启动必回锁屏，故**实害面有限，属健壮性差距而非安全洞**
  （如实声明，避免拔高）；非表单类长任务（如同步合并）中途弹锁面由 `AutoLockSessionGuard` 会话熔断兜底。
- **验收标准**：AC① 后台化时间戳持久化（DataStore / Preferences 任一，单源），会话恢复路径据此判定
  是否已超自动锁时限；AC② 评估「长任务挂锁」并实施，至少覆盖保存（KDF 派生 + 整库重序列化）与
  同步合并两类长任务；AC③ 单测覆盖持久化恢复判定与挂锁 / 恢复对称性；AC④ 与 `ISSUE-P3-362`
  （超时回显漂移）互不越界——本条不动 `securityTimeoutStateFlow` 显示链。

### ISSUE-P3-367：KDBX 写侧恒写 4.1 版本号，而非按特性动态计算最小版本

- **核实时间点与方式（2026-09-28）**：直读 `database/src/main/java/com/keepasskey/database/file/KdbxHeader.kt:48-54`——
  新建库 XML 恒含 4.1 元素、头部恒写 `VERSION_4_1`（`core/model/KdbxConstants.kt`，`ISSUE-P2-266`），
  读取仅校验 major。
- **背景**：KeePassDX 按「是否用到 4.1 专有特性」动态计算最小版本（`docs/references/KeePassDX-架构分析.md`
  §8.2-8）。恒写 4.1 把互操作下限抬到「必须支持 4.1 的客户端」；现实影响小（主流客户端均已支持 4.1），故 P3。
- **验收标准**：AC① 二选一——(a) 实施动态最小版本（按实际写出的 4.1 专有特性回退 4.0），
  或 (b) 复核确认全库恒用 4.1 特性面 / 代价不值，登记入限界表并归档本条；AC② 若 (a)，
  版本判定单测 + 官方客户端互操作对拍不回归（`AGENTS.md` 规则 8）。

### ISSUE-P3-368：流式解析已就位，但读写全程无进度回调

- **核实时间点与方式（2026-09-28）**：database / crypto / sync 三模块 `progress`（-i）检索零命中；
  直读 `database/src/main/java/com/keepasskey/database/xml/KdbxXmlParser.kt` 确认为流式 SAX
  （官方 `ReadXmlStreamed` 同族，非 DOM）。
- **背景**：大库打开 / 保存 / KDF 派生期间无可感知进度；§349 仅给部分屏加了 loading 骨架（不确定态）。
  KeePassDX `ProgressTaskUpdater` 贯穿读写（`docs/references/KeePassDX-架构分析.md` §8.2-10）。
- **验收标准**：AC① 读写链路挂 0..1 进度回调（Flow 形态），至少覆盖打开（解密→解压→XML）与保存两段；
  AC② 大库场景 UI 呈现进度（确定或分段不确定均可）；AC③ 不回退流式解析与内存擦除纪律
  （敏感缓冲不因进度层新增驻留点）。

### ISSUE-P3-369：通用占位符引擎缺失（仅 `{REF:}` 字段引用引擎）

- **核实时间点与方式（2026-09-28）**：检索 `resolvePlaceholder|expandPlaceholder|\{TITLE|\{URL|\{USERNAME`——
  仅命中 AutoType 序列字面量（`app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditComponents.kt:415`）
  与 UI 输入框 placeholder 文案；`{REF:}` 引擎在
  `database/src/main/java/com/keepasskey/database/fieldref/FieldReferenceEngine.kt`（消费点展开）。
- **背景**：kp2a 有 SPR 占位符引擎（`docs/references/keepass2android-架构分析.md` §6.6）。
  **红线**：`PD-42`——AutoType 只保存不执行，占位符只服务**复制 / 填充消费点**，不得成为 AutoType 执行面；
  展开纪律与 `{REF}` 一致：仅在取值消费点解析，投影层不物化。
- **验收标准**：AC① 先评估真实受益面（URL 模板 / 备注模板类条目在复制与填充时的展开需求）；
  AC② 有则实施常用占位符子集 + 转义规则，消费点纪律同 `{REF}`；无则复核否决归档并注明。

### ISSUE-P3-370：「不解库校验已记住凭据」通道（getCheckKey 同型）缺失

- **核实时间点与方式（2026-09-28）**：检索 `getCheckKey|checkKey|prefixHash|knownKey` 全仓零命中；
  最近似机制为生物识别封印凭据（`app/src/main/java/com/keepasskey/app/security/BiometricCredentialStorage.kt`）
  与 KDBX4 头部 HMAC 快速凭据校验（解锁链路直读确认已存在）。
- **背景**：KeePassDX `MasterCredential.getCheckKey` 以密码前缀哈希在**不解锁全库**前提下校验
  「已记住凭据」（`docs/references/KeePassDX-架构分析.md` §8.2-1）。本仓解锁路径已有头部 HMAC 快速判错、
  「记住凭据」主场景已由生物封印覆盖，实益面可能很小——如实登记，防后续对照评估再误报为缺口。
- **验收标准**：AC① 列出真实受益场景；AC② 有则实施（派生 `getCheckKey` 形态：凭据全程 `CharArray`
  即用即清、不解锁全库、不落地 `String`）；无则复核否决归档并注明。


