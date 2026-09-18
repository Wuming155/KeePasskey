# §99 浅色二级组件样式割裂与 CJK 断行批次（2026-09）

**条目**：ISSUE-P3-133 / P3-134 / P3-135 / P3-136 / P3-137 / P3-138
**来源**：用户对 `preview-exports/secondary/light/`（53 张二级组件与细节导出图）的肉眼走查

## 99.0 原文收录（`ACTIVE_ISSUES.md` 条目正文，原样剪切）

> **2026-09-16 新增（浅色二级组件预览走查批次）**：来源为用户对
> `preview-exports/secondary/light/` 全量导出图的肉眼走查。
>
> **核实时间点与核实方式（2026-09-16，对 HEAD `65d90c0`）**：逐图读图 +
> **逐条回读源码定因**——本批次的**多数肉眼观感在源码侧不成立**，剔除如下：
> ① `SecurePasswordFieldPreview` 的「双眼睛」与 `ModernSettingsRowPreview` 的「双箭头」
>   是 `@Preview` 里的**占位图标**（`SecurePasswordField.kt:183` 传 `Icons.Default.Visibility`、
>   `SettingsComponents.kt:191` 传 `ArrowForwardIos`），生产调用点不传该实参，**非生产缺陷**；
> ② `ChildDatabaseDialog` 列表中看似「孤立逗号」的图形，实为 `ChildDatabaseStatus.Opening` 分支的
>   14dp `CircularProgressIndicator`（`ChildDatabaseDialogs.kt:254-257`）被**静态导出捕获到的动画相位**；
> ③ `PackageBlocklistManageDialog` 标题的「异常宽缩进」是**全角冒号 `：`** 的固有字宽
>   （`AutofillBlocklistDialogs.kt:302`），非双空格；
> ④ 中文在词中换行（如「通行密/钥」）**是中文排版的常态**，不构成缺陷；
> ⑤ 禁用态 `TextButton`（黑名单「添加」）取 MD3 默认 38% 前景属规范行为，
>   `disabledPrimaryButtonColors()` 的适用范围**仅限 Filled Button**；
> ⑥ `ImportOutcomeReport` 的「无卡片裸文本」是其作为**可复用区块**的既定语用
>   （真实容器由 `AlertDialog` 提供）、`VaultDatabaseCard` 的裸删除图标与
>   `DatabaseSettingsComponents` 的「回收站」开关属设计取舍，均不改。
>
> 下列 5 项经源码复核**成立**并已同批整改。
>
> | 编号 | 来源 | 问题与位置（核实于 2026-09-16，对 HEAD `65d90c0`） | 验收标准 |
> |---|---|---|---|
> | ISSUE-P3-133 | 浅色预览走查（输入框风格割裂） | **`SecurePasswordField` 外观偏离同表单的 `OutlinedTextField`**：未聚焦边框取 `outlineVariant`（`SecurePasswordField.kt:154`）——该令牌在 `Color.kt:25-32` 已被 `ISSUE-P3-132` 明确定位为**分隔线角色**（对 `surfaceContainerLow` 实测 1.24:1），而全站 `OutlinedTextField` 未聚焦边框按该裁决取不透明 `outline`（4.26:1）；容器另强制不透明 `surfaceContainerLowest`（浅色 `Color.White`），而同表单字段取 MD3 outlined 默认（透明）。二者叠加使密码框在对话框（`surfaceContainerHigh`）上呈「白底 + 近隐形描边」的另一套控件观感，与紧邻字段不可自洽 | ① 未聚焦边框改取 `outline`，与 `ISSUE-P3-132` 既有裁决同口径；② 容器回归 M3 outlined 默认（透明），与同表单字段一致；③ 不改动 `semantics { password() }`（`AccessibilityNoticeWiringTest` 守护）与任何 CharArray 桥接 / 擦除语义 |
> | ISSUE-P3-134 | 浅色预览走查（禁用态按钮） | **三处 Filled Button 未接入禁用态共用配色**：`CreateVaultWizardDialog`「创建」(`CreateVaultWizardDialog.kt:325-338`)、`OpenExistingVaultDialog`「打开并加载」(`OpenExistingVaultDialog.kt:249-259`)、`MasterKeyChangeDialog`「保存更改」(`MasterKeyChangeDialog.kt:103-128`) 均以裸 `Button(enabled = …)` 取 MD3 默认 `onSurface @12%` 填充——即 `ISSUE-P3-132` ③ 已判定「落在 background 画布上形同消失」(1.29:1) 并已为解锁/重扫/同步主按钮修好的同一形态，此三处为漏网 | 三处 `confirmButton` 接入 `disabledPrimaryButtonColors()` + `disabledPrimaryButtonBorder()`；`UiMd3AlignmentWiringTest` 的既有断言继续通过；不改动任何 `enabled` 判据 |
> | ISSUE-P3-135 | 浅色预览走查（预览可信度） | **预览产物自身失真，会误导复核**：① `SecurePasswordFieldPreview` 以 `Icons.Default.Visibility` 作 `leadingIcon`，导出图为「左右两个眼睛」（生产语义应为锁/钥匙）；② `ModernSettingsRowPreview` 以 `ArrowForwardIos` 顶替业务分类图标，导出图为「一行两个同向箭头」；③ `RevisionVisualDiffDialogPreview` 把 `RevisionVisualDiffDialog` 与 `SafeAttachmentPreviewDialog` **两个独立对话框**堆进同一张导出图（`EntryDetailPreviewDiffComponents.kt:332-346`），观感为「同一对话框底部两排按钮」 | ① 两处预览改用与生产一致的语义图标；② 差异对比对话框预览只渲染自身，附件预览器另立 `@Preview`；③ 仅改 `@Preview`，不动任何生产组合 |
> | ISSUE-P3-136 | 浅色预览走查（控件分组） | **Argon2 迭代轮数步进器的「− / 数值 / +」被说明文字拦断**：`DatabaseSettingsArgon2Dialog.kt:83-103` 把 `−` 图标按钮、说明文案（`weight(1f)` 撑开）、`+` 图标按钮排在同一 `Row`，数值 `N 轮` 另在上一行的右端——操作动线被大段小字阻隔；且触达上/下界时按钮仍可点而无反馈 | ① 步进器重组为「左侧 `迭代轮数` + 说明」、「右侧 `−` `N 轮` `+`」的同行分组；② 到达 1 / 50 边界时对应按钮置灰；③ 不改变 `onApplyParameters` 上行口径与 1..50 合法域 |
> | ISSUE-P3-137 | 浅色预览走查（对比度） | **面包屑分隔符取分隔线令牌致近乎不可见**：`VaultListComponents.kt:253` 路径分隔符 `/` 用 `outlineVariant`（对浅色底 1.24:1）。该令牌按 `ISSUE-P3-132` 的定性仅承载「分隔线」角色，而此处 `/` 是**承载层级信息的字形**，非纯装饰线 | 分隔符改取 `outline`（不透明，与本仓对「承载信息的描边 / 字形」的既有口径一致）；层级层级感可辨且不喧宾夺主 |
>
> **同日追加（用户裁决）**：中文断行的可整改部分——行末悬挂左括号属**避头尾（禁则）**违规，
> 与「词中换行符不符合中文排版」的误判分开处置，按用户裁决**在主题 Typography 全局应用**，
> 登记为 **ISSUE-P3-138**。

## 99.1 核实前提（2026-09-16，对 HEAD `65d90c0`）

本批次的**多数肉眼观感在源码侧不成立**，故核查次序刻意是「先读图定位，再回读源码定因」，
而非「照着观感改」。手段两路：

1. **逐图读图**：`preview-exports/secondary/light/` 全 53 张，逐张记录异常观感与像素位置。
2. **回读源码定因**：对每一条观感追到产生它的 Composable（含 `@Preview` 本体），
   区分「生产缺陷 / 预览产物失真 / 规范行为 / 设计取舍」四类。

**结论：15 条观感中 6 条不成立**（详见 99.3），**9 条成立**（其中 4 条属「预览产物自身失真」，
是导出图的可信度缺陷而非生产缺陷），另有 1 条由用户裁决追加。

**关键判据（`outlineVariant` 的角色归属）**：`ISSUE-P3-132`（§97）已把 `outline`
由 20% alpha 改为不透明 `#74777F`，并**显式声明** `outlineVariant` 维持原值、其角色是
「分隔线」（对浅色底 1.24:1）。因此凡把 `outlineVariant` 用在**承载信息的笔画**上
（输入框描边、路径分隔符），都是与该裁决相抵的既有偏离——本批两条（133 / 137）同根因。

## 99.2 整改内容

| 编号 | 核实（代码证据） | 改动 | 文件 |
|---|---|---|---|
| P3-133 | `unfocusedBorderColor = outlineVariant` 与全站 `OutlinedTextField` 的 `outline` 口径相抵；容器强制 `surfaceContainerLowest`（浅色即 `Color.White`），在 `surfaceContainerHigh` 的弹窗上落成一块白底 | 未聚焦边框改 `outline`；容器回归 M3 outlined 默认（`Color.Transparent`）。影响面为**全部**密码类输入（WebDAV / S3、主密钥更改、新建向导、子库对话框、条目编辑、TOTP 种子等） | `ui/components/SecurePasswordField.kt` |
| P3-134 | 三处 `Button(enabled = …)` 未取 `disabledPrimaryButtonColors()` | 三处 `confirmButton` 补 `colors` + `border`；`enabled` 判据一字未动 | `screens/database/CreateVaultWizardDialog.kt`、`screens/database/OpenExistingVaultDialog.kt`、`screens/settings/MasterKeyChangeDialog.kt` |
| P3-135 | 预览实参误用占位图标；差异对比预览把两个对话框堆进一图 | ① `SecurePasswordFieldPreview` 的 `leadingIcon` 改 `Icons.Default.Lock`；② `ModernSettingsRowPreview` 的 `icon` 改 `Icons.Default.Lock`；③ 拆为 `RevisionVisualDiffDialogPreview` 与 `SafeAttachmentPreviewDialogPreview` 两个预览 | `ui/components/SecurePasswordField.kt`、`screens/settings/SettingsComponents.kt`、`screens/detail/EntryDetailPreviewDiffComponents.kt` |
| P3-136 | 步进器 `−`/说明/`+` 同排且说明带 `weight(1f)`；边界值 1 / 50 写死在 `onClick` 内，按钮无 `enabled` | 重组为「左列：标题 + 说明」「右列：`−` `N 轮` `+`」；`enabled` 按 `ITERATIONS_MIN` / `ITERATIONS_MAX` 置灰；魔法数字 1 / 50 提为具名常量 | `screens/settings/subscreens/DatabaseSettingsArgon2Dialog.kt` |
| P3-137 | 分隔符 `/` 取 `outlineVariant` | 改取 `outline` | `screens/vault/VaultListComponents.kt` |
| P3-138 | 默认 `LineBreak.Simple` 在无空格语言逐字断行，中文界面出现行末悬挂左括号（导出图实测 `历史版本 (预` / 换行 `览历史快照)`） | 主题 `Typography` 全部自定义槽位 + `HeroTitleStyle` 套用 `CjkLineBreak = LineBreak(Strategy.HighQuality, Strictness.Strict, WordBreak.Phrase)`——三项取值全部来自 Android 官方 *Style text → CJK considerations* 的推荐组合；两条 CJK 参数**按 locale 生效**，拉丁语系不受影响，故可全局套用 | `ui/theme/Type.kt` |

**范围界定（如实声明）**：`MonospacePasswordStyle` / `MonospaceTotpStyle` **不套用**该断行策略——
它们是单行凭据 / 验证码样式，不参与跨行断行，且对其做无靶改动会牵连敏感展示面。

## 99.3 不属实项留痕（**不得**按观感「修复」已正确项）

| 观感 | 源码事实 | 判定 |
|---|---|---|
| 密码框「左右两个眼睛」 | `SecurePasswordFieldPreview` 传 `leadingIcon = Icons.Default.Visibility`；生产调用点（WebDAV / S3 / 新建向导等）均不传该实参或传 `Lock` / `VpnKey` | 预览产物失真（已按 P3-135 修），**生产无此缺陷** |
| 设置行「一行两个同向箭头」 | `ModernSettingsRowPreview` 以 `ArrowForwardIos` 顶替业务图标；行尾前进指示是组件固有 | 预览产物失真（已按 P3-135 修），**生产无此缺陷** |
| 差异对比对话框「底部两排按钮」 | 预览把 `RevisionVisualDiffDialog` 与 `SafeAttachmentPreviewDialog` 堆进同一 `Column`；单个对话框自身只有一排 confirm/dismiss | 预览产物失真（已按 P3-135 修），**生产无此缺陷** |
| 子库列表「孤立逗号」 | `ChildDatabaseStatus.Opening` 分支渲染 14dp `CircularProgressIndicator`，静态导出捕获到动画某一相位 | 静态导出伪影，**生产为真实转圈动画** |
| 黑名单标题「异常宽缩进」 | 文案为 `预览：应用填充黑名单`，用的是**全角冒号 `：`** | 全角标点固有字宽，**非缺陷** |
| 中文在词中换行（`通行密/钥`、`出于安/全考虑`） | 无空格语言按字断行是中文排版常态（官方 `LineBreak.WordBreak.Default` 文档亦如此描述） | **非缺陷**；仅有「行末悬挂左括号」这类**禁则**违规成立，按 P3-138 处置 |
| 禁用态「添加」按钮偏淡 | 该按钮是 `TextButton`，MD3 禁用态前景 38% 属规范；`disabledPrimaryButtonColors()` 的适用范围是 **Filled Button** | 规范行为，**不改** |
| `ImportOutcomeReport` 无卡片裸文本 | 它是可复用区块，真实容器由 `AlertDialog` 提供，独立预览无容器属正常 | 设计取舍，**不改** |
| `VaultDatabaseCard` 裸删除图标 / `DatabaseSettingsComponents` 混入「回收站」开关 | 前者是既有交互设计（配套确认流程），后者是「概览卡 + 全局设置」的排版选择 | 设计取舍，**不改** |

## 99.4 验证证据

- **全模块单测**：`.\gradlew.bat test --rerun-tasks --max-workers=1` ⇒ `BUILD SUCCESSFUL`，
  共 **1914 项**（app 1113 / core 68 / crypto 131 / database 392 / sync 210），
  **0 失败 / 0 错误 / 13 skip**（skip 全部落在 sync，属真实语料门控，符合 `AGENTS.md` §5 的有意降级口径）。
- **CJK 断行改动后复跑 `:app:testDebugUnitTest --rerun-tasks`** ⇒ app **1115 项**全绿。
- **既有守护未回归**：`UiMd3AlignmentWiringTest` 6 项全过，其中
  「弹窗容器形状统一走主题 extraLarge 而不各自覆盖」证明本批新增的 `colors` / `border`
  实参未误触弹窗顶层形状断言；「禁用态主按钮必须走共用组件」证明 P3-134 与既有约定同源。
- **新增守护**：`UiCjkLineBreakWiringTest`（2 例）——锁定 `Phrase` / `Strict` / `HighQuality`
  三项取值，并以「`fontFamily = FontFamily.Default` 槽位数 ≤ `lineBreak = CjkLineBreak` 处数」
  防「新增槽位漏带断行策略」的静默回归；含防空扫下限。
- **未执行项（如实声明）**：`tools/audit/check_recheck_consistency.sh` **未运行**——
  本质为 Windows 环境且 `bash` 不在 `PATH`；本批未改动任何审计 / 复核报告，该脚本的触发条件不成立。

## 99.5 闭环状态

六条全部闭环（P3-133 ~ P3-138）。**与安全语义无关**：全部改动限于外观、预览产物与排版策略，
未触碰任何 `CharArray` 桥接 / 擦除路径、`SecureDialog` / `FLAG_SECURE` 施加点、`enabled` 判据
与 KDBX 互操作面。

## 99.6 施工中撞出的既有残留（未登记为缺陷，留痕备查）

**主工作区存在并非本批次产生的未提交改动**（`AGENTS.md`、`app/src/main/res/values*/strings.xml`、
`EntryDetailSections.kt`、`CloudSyncConfigFields.kt`、`UnlockContentSections.kt`、
`AutofillConfirmActivity.kt`、`EntryIconContent.kt`、`EntryEditComponents.kt`、
`GeneratorModeOptions.kt`、`CloudSyncComponents.kt` 共 11 个文件），内容为**另一并行批次**
的同主题 UI 打磨（去掉按钮文案前缀 `+`、AutoType 改独立 placeholder、硬编码英文改资源、
预览 URL 调整等）。经**用户裁决**：本批次**仅选择性提交自己的 9 个文件**，
上述 11 个文件一律**不纳入**本次提交、不被修改、不被回退。

---

## 归档索引行原文（无损迁移承接）

> 迁移前本批次的正文同时存在于三处：总索引行、分册级索引行、本文件。以下按「只搬迁、不改写」
> 原文照录两处索引行正文（更正须另加小节，不得就地改），自此两处索引只留一行指针。
> 承接批次：§154。

### 总索引行原文（§99）

浅色二级组件样式割裂与 CJK 断行批次：**核实先行**（53 张二级导出图逐图读图 + **逐条回读源码定因**）——**15 条肉眼观感中 6 条在源码侧不成立**并逐条留痕「不得按观感修复已正确项」（预览占位图标致「双眼睛/双箭头」、「孤立逗号」实为静态导出的转圈动画相位、全角冒号字宽、中文按字断行属排版常态、`TextButton` 禁用态属 MD3 规范、裸文本区块属可复用区块语用）；**成立项**全部整改：`SecurePasswordField` 未聚焦边框由**分隔线令牌** `outlineVariant`（1.24:1）改回 `outline`（与 `P3-132` 裁决同口径）+ 容器回归 M3 outlined 默认（消除弹窗上的「白底 + 近隐形描边」第三套控件观感）、三处 Filled Button 补接 `disabledPrimaryButtonColors()`/`Border()`（`P3-132` ③ 的漏网调用点）、两处预览占位图标改语义图标 + 差异对比预览与附件预览拆图、Argon2 步进器由「被说明文字拦断」重组为同行分组并补边界置灰、面包屑 `/` 由 `outlineVariant` 改 `outline`；并按**用户裁决**新增 P3-138——主题 `Typography` 全局套用官方为 CJK 设计的 `LineBreak(HighQuality, Strict, Phrase)` 消除行末悬挂左括号（避头尾）类违规，新增 `UiCjkLineBreakWiringTest`（2 例，含防空扫）防「新增排版槽位漏带断行策略」的静默回归。**如实声明**：`tools/audit/check_recheck_consistency.sh` 未运行（Windows 无 `bash`，且本批未改审计报告，触发条件不成立）；工作区另有**并行批次**的 11 个未提交文件，经用户裁决仅选择性提交本批 9 个文件
