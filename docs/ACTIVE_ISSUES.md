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

## P2 中危缺陷与协议/测试缺口（5 项）

> **开放项 5 条**（2026-09-28 深度交互审查新增 `ISSUE-P2-353` ~ `ISSUE-P2-357`，均**未经对抗轮单独攻击**，
> 认领时按规则 6.1② 先复核前提）。2026-09-27 两条真机手测报出的凭据可用性缺陷（`ISSUE-P2-341` /
> `ISSUE-P2-343`）分别收口于 §343 与 §344；更早的 P2 闭环流水见 `RESOLVED_LOG.md` §315 ~ §325。

### ISSUE-P2-353：复制与「打开网址」的反馈不实（假成功 / 假动作 / 假错误样式）

- **核实时间点与方式（2026-09-28）**：四路并行深度审查（导航反馈 / 安全交互 / 流程细节 / 无障碍）+ 主线
  逐行抽查 `VaultListActionController.kt:74-88`、`EntryDetailComponents.kt:169`、detail 包 `Grep ACTION_VIEW`（0 命中）。
- **现状（四处「反馈与事实不符」）**：
  1. 列表复制密码：`chars == null` 或 `clipboardSecurityManager == null` 时跳过写剪贴板，但
     `vault_copy_password_done` 无条件发出（`VaultListActionController.kt:78-86`）；详情页同型
     （`EntryDetailCopyCoordinator.kt:41-47`）。
  2. 非受保护自定义字段「复制」只弹 `detail_field_copied`，**未写剪贴板**（`EntryDetailSections.kt:102-107`；
     受保护分支才走 `copyCustomField`，`EntryDetailCopyCoordinator.kt:76-91`）。
  3. 详情页「打开网址」是假动作：点击只 `onShowMessage(R.string.detail_opening_browser)`
     （`EntryDetailComponents.kt:169`，文案称「正在呼起浏览器…」），全 detail 包无 `ACTION_VIEW`/`Uri`；
     URL 文本亦无可点击性（`EntryDetailComponents.kt:115-119`）。
  4. 导出结果 isError 判定靠中文串 `contains("失败")`（`DatabaseSettingsScreen.kt:213`）——切英文语言后
     失败条目以成功样式呈现。
- **验收标准**：① 复制仅在剪贴板**实际写入成功**后报成功，失败/通道缺失如实报失败（含详情页同路径）；
  ② 非保护字段同样经剪贴板通道落值，取不到值如实报错；③ 「打开网址」校验 `http(s)` 后发
  `Intent.ACTION_VIEW`，失败回退为复制 URL 并如实提示——或按产品裁决移除该按钮（须登记 PD）；
  ④ 错误样式判定改类型化 `isError` 字段，不依赖展示文案；⑤ 新增/修改逻辑补 JVM 单测。

### ISSUE-P2-354：长任务缺防重复提交与进度（保存可产生重复条目 / 换主密钥关框静默跑 / 建库可连点）

- **核实时间点与方式（2026-09-28）**：同上审查 + 逐行核对 `EntryEditViewModel.kt:407-451`、
  `MasterKeyChangeDialog.kt:73-93`、`CreateVaultWizardDialog.kt:246`、`DatabasePickerUiState.kt:27`。
- **现状**：
  1. 条目保存无 `isSaving` 守卫：两个保存按钮仅 `enabled = !isReadOnly`（`EntryEditChrome.kt:63,82`），
     新条目 UUID 在协程内生成（`EntryEditViewModel.kt:415`，`:442` 才回写 state）——快速双击读到两次
     `entryId == null`，**产生两条重复条目**；含 KDBX 落盘的整个保存期间无任何进度。
  2. 更换主密钥：提交后**立即 `onDismiss()`**（`MasterKeyChangeDialog.kt:73-93`），全库 Argon2 重派生 +
     重加密跑在 UI 层 `rememberCoroutineScope`（`SettingsScreen.kt:118`）——无进度、可重复打开对话框重复提交、
     用户切 Tab 即随组合销毁取消，结果只剩可能已错过的 Snackbar（`SettingsScreen.kt:126`）。
  3. 建库向导仅 `enabled = state.isFormValid`、无 busy 守卫（`CreateVaultWizardDialog.kt:246`），秒级
     Argon2 派生期间可连点并发建库；`DatabasePickerUiState.isLoading` 是死字段（`DatabasePickerUiState.kt:27`，
     仅预览置值、无渲染点）。
  4. 导入解析中仅转圈、`onDismissRequest = {}` 不可取消（`ImportReportDialog.kt:54-68`），
     `VaultImportController.kt:67-80` 无取消通道与分阶段计数。
- **验收标准**：① 条目保存、建库、导入均置 busy 态并禁用全部入口，按钮内嵌进度；② 保存成功后一次性导航，
  双击不产生重复条目（补单测）；③ 换主密钥任务移出 UI scope（`viewModelScope`/WorkManager），对话框保留
  进度直至完成，期间不可重复提交；④ 导入给出可量化进度与取消（协程 cancellation）。

### ISSUE-P2-355：解锁会话与自动锁的静默反馈（快速解锁失败无提示 / 节流 UI 未接线 / 自动锁静默丢编辑）

- **核实时间点与方式（2026-09-28）**：同上审查 + 逐行核对 `BiometricUnlockCoordinator.kt:215-236,295-307`、
  `Grep throttleFailureCount`（仅写入 UiState、无 UI 读点）、`KeePasskeyApp.kt:305-313`。
- **现状**：
  1. 快速解锁（QUICK 模式）三条失败路径全部静默：比对失败（`BiometricUnlockCoordinator.kt:215-219`）、
     `authedCipher == null` 连消息都不设（`:234-236`）、解封成功但解库失败（`:295-307`）——均不回落
     STANDARD、无错误槽位（快速解锁卡无错误 UI），用户只见弹窗消失、按钮复原。
  2. 节流反馈缺位：`throttleFailureCount`/`throttleLockoutRemainingMs` 写入 `UnlockUiState.kt:51-53`
     但全仓无 UI 消费（`ThrottleGate` KDoc 明言「供 UI 呈现剩余尝试」）；锁定期文案为一次性快照
     （`MasterPasswordUnlockSession.kt:213-221`），无倒计时刷新，且用户一开始输入即被清掉（`:51-57`）。
  3. 自动锁静默丢弃未保存编辑：熄屏即锁（`AutoLockManager.kt:67-75`）→ 锁事件 `popUpTo(0)` 重建解锁页
     （`KeePasskeyApp.kt:305-313`），编辑页销毁、`isDirty` 仅存内存（`EntryEditUiState.kt:68`）；
     手动返回有丢弃确认（`EntryEditScreen.kt:194`），自动锁路径无提示、无草稿暂存、锁定后亦无告知。
  4. 生物识别错误全归一为同一句（`BiometricFailureMessagePolicy.kt:27-29` 仅 `else` 分支），
     用户无法区分「可重试」与「须改用主密码」。
- **验收标准**：① QUICK 三条失败路径统一回落 STANDARD 并渲染错误文案；② 失败提示附「剩余 N 次尝试」，
  锁定期加每秒 ticker 倒计时；③ 自动锁触发前对 `isDirty` 走确认或暂存草稿，锁定后给一次性告知；
  ④ 生物错误按 `errorCode` 分档（lockout / hardware / timeout / 策略）。

### ISSUE-P2-356：搜索输入防抖绑到显示态——快速打字字符回吞

- **核实时间点与方式（2026-09-28）**：逐行核对 `VaultListTopBars.kt:172-181`（受控 `BasicTextField`）、
  `VaultListProjection.kt:99`（`searchQuery = session.filterParams.query`）、
  `VaultListViewModel.kt:215-218`（`debounce(SEARCH_DEBOUNCE_MS = 300L)` 后进入 `filterParamsFlow`）。
- **现状**：搜索框显示值直接来自经 300ms 防抖后的过滤流；击键间隔 <300ms 时 `uiState.searchQuery` 不更新，
  受控输入框被同步回旧值——连续输入期间字符不显示或被回吞，停顿后才出现。防抖本意
  （`VaultListViewModel.kt:205-213` 注释：避免每字符全量重算）只该作用于**过滤流**，却让**显示态**也走了防抖。
- **验收标准**：搜索框持本地即时回显（`TextFieldValue` 或独立未防抖 state），防抖仅作用于 `filterParams`；
  快速连打无回吞（真机手测 + 投影时序单测）；回车/清空仍立即生效。

### ISSUE-P2-357：破坏性操作确认不一致，且全仓无撤销（undo）

- **核实时间点与方式（2026-09-28）**：同上审查 + 逐行核对 `VaultListActionController.kt:169-181`、
  全仓 `Grep SnackbarResult|undo`（0 命中）。
- **现状**：批量删除**无确认**直接执行（`VaultListTopBars.kt:89` → `VaultListScreen.kt:153` →
  `VaultListActionController.kt:169-181`）；回收站内永久删除（DeleteForever，20dp 小按钮）**无确认**
  （`VaultEntryRowLayouts.kt:265` → `VaultListScreen.kt:430` → `purgeEntry`）。对照组均有确认：分组删除
  （`VaultListDialogHost.kt:209`）、清空回收站（`:100`）、单条删除（`EntryDetailDialogHost.kt:118`）。
  全仓无任何 `SnackbarResult`/撤销动作——软删除后只能进回收站手工找回。
- **验收标准**：① 批量删除、永久删除补确认对话框（含数量与后果说明）；② 软删除的 Snackbar 提供
  「撤销」动作（`SnackbarResult.ActionPerformed`）；③ 确认口径在三类删除间对齐；④ 补行为单测。



## P3 低危问题、特性接线与体验优化（4 项）

> **开放项 4 条**（这里只列**各条还欠什么**；已完成的实施细节留在条目正文与 commit 里，不重复登记）：
> ① `ISSUE-P3-339` —— **用户指示暂时搁置**（浏览器半环需外部域名与信任链资源）。
> ② ~ ④ `ISSUE-P3-358` ~ `ISSUE-P3-360` 为 2026-09-28 深度交互审查新增（同样**未经对抗轮单独攻击**）。
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

### ISSUE-P3-358：触觉反馈开关空转，与无障碍语义缺口

- **核实时间点与方式（2026-09-28）**：无障碍专项审查 + 全仓 `Grep hapticFeedbackEnabled`（仅状态链与开关
  UI 共 5 处引用）、`Grep performHapticFeedback`（9 处调用点逐一核对）、`Grep contentDescription` 抽查。
- **现状**：
  1. **开关空转**：`hapticFeedbackEnabled`（`SettingsUiState.kt:178`）无任何 `performHapticFeedback`
     调用点读取——9 处全部裸调：`VaultEntryRowLayouts.kt:272`、`EntryDetailCardSections.kt:129,140`、
     `EntryDetailComponents.kt:181,195`、`GeneratorDisplayCard.kt:93`、`VaultListGroupDialogs.kt:197,229`、
     `ValueSlider.kt:91`。用户关闭「触觉反馈」后仍震动。
  2. **纯图标按钮缺描述**：条目详情自定义受保护字段的显隐切换与复制均为 `contentDescription = null`
     （`EntryDetailSections.kt:93-99,102-115`），TalkBack 播报「未加标签的按钮」；对照正确做法
     `EntryDetailCardSections.kt:134,145`。
  3. **硬编码英文描述 5 处**（未走 `stringResource`）：`ThemeSettingsComponents.kt:168` `"Selected"`
     （选中态唯一 TalkBack 线索）、`SecuritySettingsScreen.kt:359`、`HealthCheckComponents.kt:361`、
     `CloudSyncSections.kt:278`、`AboutSettingsScreenSections.kt:65`（后四者为装饰图标，应改 `null`）。
  4. **选择语义缺失**：单选卡片为裸 `.clickable`、无 `selectable`/`Role`/选中语义（`ThemeSettingsComponents.kt:117`
     三选一主题卡等，18 处 `.clickable` 仅 1 处带 `Role`）；对照正例 `VaultListDialogs.kt:85-94`。
  5. **热区过小**：TOTP「点按复制」可点区仅约 18–24dp 文本，且注释明言为保截图基线不加内边距
     （`VaultEntryRowLayouts.kt:332-352`），远低于 48dp。
  6. **大字体溢出风险**：倒计时数字 `10.sp` 嵌固定 `size(28.dp)` 环、验证码 `28.sp + letterSpacing 3.sp`
     无 `maxLines/overflow`（`AuthenticatorScreen.kt:361-366,385-393`）；fontScale ≥2.0 未核对。
- **验收标准**：① 提供 `HapticsEnabled` CompositionLocal（或 `maybeHaptic` 统一入口）并收口全部 9 处调用点；
  ② 显隐/复制按钮补 `stringResource` 描述；③ 硬编码英文清理（承载状态者改 stringResource + 语义 selected，
  装饰者改 `null`）；④ 单选卡片改 `selectable(role = Role.RadioButton)`、操作行补 `Role.Button`；
  ⑤ TOTP 热区经 `minimumInteractiveComponentSize()`（用外层 padding，不改图标几何以护截图基线）；
  ⑥ fontScale 2.0 下截图核对并修正溢出。

### ISSUE-P3-359：表单与导航反馈基础设施（IME 链 / 校验定位 / launchSingleTop / 全局 Snackbar / 加载态）

- **核实时间点与方式（2026-09-28）**：流程与导航两路审查 + 主线 `Grep imeAction|KeyboardOptions`（全 `ui/`
  仅 `SecurePasswordField.kt:128-132` 一处）、`Grep isLoading`（`EntryDetailUiState.isLoading` 无消费点）。
- **现状**：
  1. **IME 动作链缺失**：编辑页 title/url/username/tags 等 `OutlinedTextField`（`EntryEditFormSections.kt:137,175,259`、
     `EntryEditComponents.kt:247,256,269`）均无 `keyboardOptions`（默认无 Next 链）；密码框未传 `onDone`，
     默认 `{}` 覆盖框架收起键盘行为——按完成键无反应。
  2. **必填校验无字段级定位**：标题空白仅返回资源 id → 弹一次性 Snackbar（`EntryEditSaveProjection.kt:22-26` +
     `EntryEditViewModel.kt:409-411`），标题框无 `isError/supportingText`，无滚动/聚焦到首个错误字段
     （`EntryEditFormSections.kt:137-144`）。
  3. **下钻导航缺 `launchSingleTop`**：裸 `navigate(...)` 可重复入栈（`KeePasskeyNavGraphRoutes.kt:101,104,190,246-254`
     及设置子页）——双击列表行/设置项叠两条目的地，多按一次返回。
  4. **Snackbar 反馈随导航丢失/延迟**：十个 Screen 各持 `SnackbarHost`、无全局宿主（`ClipboardSecurityManager.kt:257`
     注释自认）——扫码导入发消息后立即导航，`showSnackbar` 被组合销毁取消、消息滞留到返回才弹出陈旧提示
     （`VaultListActionController.kt:424-425` + `VaultListScreen.kt:81-87`）；数据库选择器同型，代码注释自认
     「Snackbar 往往来不及渲染」（`DatabasePickerViewModel.kt:249-270`）。
  5. **加载态误判**：`EntryDetailUiState.isLoading` 全 UI 无消费点，详情页只判 `entry == null`
     （`EntryDetailScreen.kt:192-200`）——解密完成前显示「未找到凭据」；编辑页打开既有条目异步解密期间
     表单全空无指示，载入完成还会覆盖已键入内容（`EntryEditViewModel.kt:198-246`）。
- **验收标准**：① 编辑页 title→url→username 设 `ImeAction.Next`，密码/备注设 `Done` 并接收键盘或保存；
  ② 校验失败置字段级 `isError` 并聚焦/滚动到首个错误字段；③ 全部下钻路由加 `launchSingleTop = true`；
  ④ Snackbar 上提为 Scaffold 级全局宿主（或消息随导航队列化），导航后消息不丢不延迟；
  ⑤ 详情/编辑页 `isLoading` 分支渲染进度，加载完成不覆盖用户输入。

### ISSUE-P3-360：其余体验细节（冲突解决可读性 / 生成器历史污染 / TOTP 临期与清空提示 / 可发现性 / 骨架屏）

- **核实时间点与方式（2026-09-28）**：流程、安全、无障碍三路审查交叉取证，逐条附 file:line。
- **现状**：
  1. **冲突解决对非技术用户不可读**：`localModifiedTime`/`remoteModifiedTime` 已填充但 UI 无消费点
     （`ConflictResolutionUiState.kt:44-45` vs `ConflictResolutionViewModel.kt:68`），用户看不到哪边更新；
     字段默认 `FieldChoice.LOCAL`（`:29`）、批量「全选本地/云端」与「合并并推送」一点即生效、无确认与后果提示
     （`ConflictResolutionScreenSections.kt:75-91,141-158`）；敏感字段两侧掩码完全相同（`ConflictResolutionViewModel.kt:131-132`
     静态串），无法区分差异。
  2. **生成器滑杆污染历史**：`setRandomLength` 每变即生成并把旧值推进 history（`GeneratorViewModel.kt:51-54,203-219`）——
     拖动一次塞满 10 条中间态。
  3. **TOTP 复制无临期预警**：`copyTotpCode` 不看剩余秒数（`EntryDetailCopyCoordinator.kt:131-143`），剩 1–2 秒照样
     「已复制」；敏感复制文案仅密码带「Ns 后清空」（`EntryDetailStateAssembler.kt:237-244`），字段/HOTP/生成器复制不带；
     剪贴板清空前无任何提醒，清空 Toast（`ClipboardSecurityManager.kt:260-266`）在 Android 12+ 后台被系统抑制时彻底无反馈。
  4. **可发现性缺口**：批量模式只能长按进入且无任何提示（`VaultListScreen.kt:124-130`），溢出菜单无「选择」项
     （`VaultListTopBars.kt:218-276`）；搜索结果无命中高亮、无最近搜索、匹配档入口只在设置页
     （`SettingsExtendedPreferencesController.kt:127`）；填充确认首现调用方需勾选授权但禁用按钮无就地解释
     （`AutofillConfirmActivity.kt:213` + `strings.xml:1072`）。
  5. **加载无骨架屏**：`ui/` 下 shimmer 0 命中，14 处裸 `CircularProgressIndicator`，内容出现时硬切无占位过渡。
- **验收标准**：① 冲突屏两侧显示修改时间并标注「较新」，批量选择/合并前弹确认说明覆盖范围，敏感字段给出
  差异线索或显式揭示（按安全裁决取舍）；② 生成器改为拖动结束（`onDragEnded`）才生成，或滑杆调节不入历史；
  ③ TOTP 剩余 ≤5s 复制时提示「即将过期」，敏感复制文案统一附「Ns 后清空」；④ 溢出菜单补「选择」项 +
  首次长按 Snackbar 引导，搜索结果加命中高亮；⑤ 列表/详情首载引入骨架占位（取 MotionScheme 淡入）。


