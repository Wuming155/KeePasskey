# UI 与操作体验的竞品对比报告（KeePasskey vs KeePassDX / keepass2android / Monica）

> **文档定位**：面向 UI 设计、操作体验、功能完整性三个维度的结构化竞品对比分析报告。
> **对比对象**：三个同类安卓应用＝参考项目中的 KeePassDX（🥇 核心参考）、keepass2android（🥈 云同步参考）、
> Monica（🥉 UI / 本地优先辅助）；KeePassXC / KeePass 官方为桌面端格式裁决者，不在本次对比范围。
> **核实时间点**：2026-10-02（首版据 `docs/references/` 分析文档转述；**同日补证**：经用户指令**定向直读三家竞品
> UI/视觉代码**（只读、未修改/复制任何文件），视觉面结论全部升级为 `文件:行号` 代码级证据；与既有架构分析
> 文档不符处，**以代码直读为准**并显式标注修正）。
> **核实方式**：① 竞品事实——三家竞品 UI 代码定向取证（主题/布局/动效/反馈/手势/无障碍等 12 节逐项）；
> 本仓事实——`app` 模块 UI 层逐文件走查（正文以 `文件:行号` 引用）。
> **证据边界声明**：本报告**未实机运行三款参考应用**，「对比截图」以「代码出处说明」承载；
> 取证为定向 UI 面阅读，非全仓扫描，非 UI 面结论仍以架构分析文档为准。
> **结论分流纪律**：改进点分三档——**已登记**（进 `ACTIVE_ISSUES.md`）/ **待裁决**（属产品取舍，
> 待用户拍板前不进待办）/ **不跟进**＝用户裁决排除（落 `产品裁决登记.md`）或与既有 PD/限界冲突
> （仅引用原条目，不重复登记）。**2026-10-02 裁决已落定**：原待裁决 12 项中 8 项登记为
> `ISSUE-P3-439`~`ISSUE-P3-446`、4 项排除为 PD-63~66（详见 §5）。

---

## §1 对比对象与技术底座

| | KeePasskey（本仓） | KeePassDX | keepass2android | Monica |
|---|---|---|---|---|
| UI 技术栈 | 单 Activity + Jetpack Compose + **Material 3 Expressive**（`app/build.gradle.kts:263-280`）+ Navigation Compose | View 体系（XML + Fragment + **Material 3** 主题基座，`styles.xml:28,124`） | View 体系 + **Material 3 DayNight**（`Resources/values/themes.xml:2,53`） | 100% 纯声明式 Compose + Navigation Compose |
| 主浏览界面 | 单 NavHost + 底部 Tab / 宽屏 NavigationRail | `GroupActivity`：**DrawerLayout + NavigationView**（`activity_group.xml:20,183-187`）+ 面包屑 + FAB | `GroupActivity`（ListView + 顶栏 ActionBar，**主列表无抽屉**） | `AdaptiveMainScaffold`：窄屏 NavigationBar / 宽屏 NavigationRail（`AdaptiveMainScaffold.kt:22-103`） |

---

## §2 UI 设计维度对比（视觉面均为代码直读证据）

### §2.1 视觉风格与主题系统

- **本仓**：`MaterialExpressiveTheme` + `MotionScheme.expressive()`（`ui/theme/Theme.kt:116-121`）；三态模式
  LIGHT/DARK/SYSTEM；Android 12+ 动态取色与 5 套品牌调色盘互斥单选（`ThemeMode.kt:27-44, 50-199`）；
  OLED 纯黑（`Theme.kt:69-73`）；语义安全色独立于主题（`Theme.kt:34-50`）。
- **KeePassDX**：Material3 Light/Night 双父主题（`styles.xml:28,124`）+ **9 组具名色系 × 明/暗 + Dynamic =
  17 个 StyleRes**（`Stylish.kt:129-150`；每色系一对 styles_*.xml）；动态取色实为 **Material DynamicColors**
  （`StylishActivity.kt:104-106`）——**修正**：架构分析文档曾记「chroma 做动态取色」，代码直读证实 chroma
  （AndroidClearChroma）仅用于**数据库自定义颜色选择器**（`ColorPickerDialogFragment.kt:10,19`），不承全局主题；
  主题切换经 `Stylish.getThemeId()` 做系统日夜等价换算（`Stylish.kt:50-102`）。
- **keepass2android**：`Theme.Material3.DayNight` 基座 + 完整 **md_theme Material You 调色板**
  （`colors_kp2a.xml:4-144`，primary #4B662C 绿系 / secondary #31628D 蓝系 + container/fixed/高对比变体），
  明暗经 values-night 覆盖（`values-night/colors.xml:4-19`）；对外仅绿/蓝两套强调色活动主题
  （`themes.xml:252-261`）。**未发现动态取色**（全仓 grep `DynamicColors` 零命中）。
- **Monica**：动态取色默认开（`AppSettings.kt:532`）+ **7 套具名方案 × 明/暗 + Catppuccin 四型 × 明/暗
  （168 个 `Cat*` 色值，`Color.kt`）+ 自定义五种子色生成器**（`CustomColorSchemeGenerator.kt`：种子色 → M3
  scheme 生成管线，`Theme.kt:878-942`）+ OLED 纯黑覆写 `withPureBlackSurfaces()`（`Theme.kt:1229-1284`）。

**判定**：动态取色 / 明暗 / 纯黑三要素本仓均已具备。「主题预设广度」的真实差距＝Monica 的 11 套静态方案 +
**用户自定义种子色生成**、KeePassDX 的 17 主题 vs 本仓 5 套调色盘——见 §5 G7（已登记 `ISSUE-P3-441`，中低优先，
Monica 的「自定义种子色」是可低成本借鉴的形态）。

### §2.2 图标体系

- **本仓**：`material-icons-extended` 全矢量；条目图标 = KDBX 内置 68 图标 + 库内自定义位图 + 选择器。
- **KeePassDX**：icon-pack 双包——material 包 **69 个 vector**（`material_00_32dp.xml` 数字索引命名）+ classic 包
  PNG 位图；`IconDrawableFactory` 装配、列表 32dp 渲染并 `setColorFilter` 着色（`NodesAdapter.kt:446-449`）；
  应用 UI 图标 92 个自命名 vector（`ic_<语义>_<色>_24dp` 约定）。
- **keepass2android**：标准条目图标为 **PNG 多分辨率位图**（ic00–ic68 + 文件夹变体，明暗两套）+ **可插换图标包**
  （`DrawableFactory.cs:69-92` 经 `Resources.GetIdentifier` 从外部图标包应用解析，失败回落 ic99_blank）+
  `IconSetPreference` 图标包选择 UI——**两家均具备图标包机制**，Kp2a 甚至支持外部应用形式图标包。
- **Monica**：文档级证据（emoji/上传图标/favicon/应用图标 40dp 图标链，`PasswordEntryCard.kt:108-207`）。

**判定**：核心面对齐；差距在「图标包扩展机制」（两家先例）——G8 经 2026-10-02 用户裁决**不做**（PD-65）。

### §2.3 排版与布局

- **本仓**：固定 Typography + 全局 CJK 断行 `LineBreak(Strict, Phrase)`（`Type.kt:17-33`）；密码/TOTP 等宽样式；
  列表密度偏好（紧凑/标准）。
- **KeePassDX**：完整 TextAppearance 阶梯（`styles.xml:384-478`）；**内嵌 Fira Mono 等宽字体用于密码字段且默认
  开启**（`assets/fonts/FiraMono-Regular.ttf` + `ViewUtil.kt:73-76` + `donottranslate.xml:230-231`
  `monospace_font_fields_enable` 默认 true）——本仓等宽密码为硬编码样式，无「等宽开关」偏好。
- **keepass2android**：25 个自定义 style（EntryItem 系文字样式 + BottomBarButton 等，`styles.xml:22-247`）。
- **Monica**：仅定制 bodyLarge（`Type.kt:10-17`），其余 M3 默认；等宽经 `FontFamily.Monospace` 内联散布 10+ 文件。

**判定**：本仓 CJK 断行与密度偏好领先；「等宽字体开关」为 KeePassDX 独有偏好项，并入 §5 G7 邻近的界面偏好
（与 G14 界面缩放同族，已随 `ISSUE-P3-444` 登记）。

### §2.4 控件使用一致性

- **本仓**：BentoCard + 全局唯一 Snackbar 宿主（跨导航存活 + undo）+ `SettingsSubscreenScaffold` + 触觉全站收口
  （`Haptics.kt:21-33`）；**残余 Toast 3 处**（`ClipboardSecurityManager.kt:282` 等）。
- **竞品对照（grep 计数）**：KeePassDX Snackbar 32 / Toast 48；**Kp2a Snackbar 26 / Toast 7 且有统一
  `ChainedSnackbarPresenter` + 各页 `SnackbarAnchorView`**（`LockingActivity.cs:97-109`）；
  **Monica Snackbar 23 / Toast 393——Toast 为主通道**，触觉反馈 6 类语义封装（`HapticFeedbackHelper.kt:11-24`，
  全仓 70 处引用）。
- **判定**：反馈通道一致性本仓与 Kp2a 同级、显著优于 KeePassDX/Monica；Toast 清扫项维持 G9（低优先）。

### §2.5 响应式适配

- **本仓**：≥600dp BottomBar↔NavigationRail（`KeePasskeyApp.kt:315, 346-353`）；预测式返回 + 90% 缩放转场；
  全局 imePadding。
- **Monica**：`AdaptiveMainScaffold` 同为窄屏 NavigationBar / 宽屏 NavigationRail（`AdaptiveMainScaffold.kt:33-100`）
  ——**形态与本仓一致**。
- **Kp2a**：无宽屏适配证据；解锁页有 CollapsingToolbar + predictive back（`PasswordActivity.cs:1060-1103`）。

**判定**：无差距（PD-43 双栏不做维持）；Monica 证实「宽屏换 Rail」是同类共识形态而非本仓特有偏航。

### §2.6 动效设计与过渡

- **本仓**：`AppNavigationMotion.kt` 全量定标四类转场 + 守卫测试（禁裸 `tween(`）；`animateItem` 列表动画。
- **KeePassDX**：res/anim 仅 8 个滑动插值；Activity 统一 fade 转场（`StylishActivity.kt:75-86`）；**列表变更动画
  三处显式关闭**（`FileDatabaseSelectActivity.kt:154` 等）；无 animator 资源。
- **keepass2android**：**全部 overridePendingTransition 均被注释（活跃 0 处）**、无 ItemAnimator——动效面基本
  关闭，仅靠 Material 组件自带 CollapsingToolbar 滚动视差。
- **Monica**：动效密度最高——`AnimatedVisibility` 约 160 处 / `AnimatedContent` 36 处 / `animate*AsState` 128 处；
  **`sharedBounds` 共享元素 11 处**（列表卡片 → 详情，`PasswordEntryCard.kt:71-76` key=`password_card_${id}`）；
  自定转场 spec（右滑入 1/8 屏宽 + 视差退出，`NavTransitions.kt:22-35`）；**带全局动效降级开关**
  `LocalReduceAnimations`（`ui/LocalSharedTransition.kt:10-18`，注释指明为规避 HyperOS 2 / Android 15 卡顿）。

**判定**：动效已按 PD-26/27/28/29/44 定标并被守卫锁定，Monica 的共享元素先例与 PD-29 冲突**不跟进**；
Monica 的「动效降级开关」（低端机 / ROM 兼容性）是本仓没有的**可感知性保险丝**，与本仓 MotionScheme 定标不冲突，
已随界面偏好组登记（`ISSUE-P3-444`，中低优先）。

---

## §3 操作体验维度对比

### §3.1 解锁 / 建库流程

- **本仓**：双模式（生物识别 ↔ 主密码）+ 自动唤起 + `SecurePasswordField`（等宽 + `semantics{password()}`）+
  SAF 密钥文件 + 只读开关 + 离场清零。**解锁全程零进度指示**（`UnlockScreen.kt` 无任何进度节点，
  `strings.xml` `unlock_*` 无过程文案——2026-10-02 grep 核实）。
- **KeePassDX**：`ProgressTaskDialogFragment` 进度对话框（标题 + 消息 + **警告行 + 取消按钮**，
  `fragment_progress.xml:70`）贯穿加解密；解锁页三层布局，生物识别以 Fragment 容器嵌入
  （`MainCredentialActivity.kt:138`）；新建凭据为分卡表单（密码/密钥文件/硬件密钥三 CardView）。
- **keepass2android**：解锁页 = DrawerLayout + CollapsingToolbar 大头部（背景图视差）+ 表单（主密钥类型
  Spinner → 密码框 + 显隐 + 指纹钮 → 密钥文件区 → OTP 区）；**QuickUnlock 独立布局**（专用背景 + 4 字符
  小输入框 + `QuickUnlockBlocked` 降级提示容器，`QuickUnlock.xml`）；进度三形态
  （`LoadingDialog` / `SimpleLoadingDialog` / 页内嵌 `BackgroundOperationContainer`）。
- **Monica**：**界面代码中未找到全屏解锁 Composable 与 PIN 数字盘**（grep `PinPad|Keypad|NumberPad` 零命中）
  ——**修正**：架构分析文档所记 `unlock_methods` 表（PIN/Password/Security Key 包装）在 UI 层无对应实现面；
  解锁实为分散的 BiometricPrompt 调用（`BiometricHelper.kt:26` + 7+ 消费方）。§5 G4（PIN 包装）的竞品 UI 先例
  **弱化为数据层声明**，经 2026-10-02 用户裁决**不做**（PD-64）。

### §3.2 条目浏览与搜索

- **已对齐（勿重复整改）**：`ISSUE-P3-352` 已整条结案——搜索空态专用文案 +「新建 / 清除搜索」双出口 +
  域名感知档 + 剪贴板实际清掉才提示。
- **竞品搜索空态现状（代码直读）**：**Kp2a 至今仍为单一 `no_results`（"No search results"）TextView，无出口**
  （`group_empty.xml:20-32`、`strings.xml:228`）；**Monica 两处搜索空态（Vault 与密码列表）均无出口按钮**
  （`VaultOverviewSearch.kt:90-93`、`PasswordListScrollableContent.kt:227`）；KeePassDX 无空态视图。
  ⇒ 本仓搜索空态（双出口 + 预填）**为四者唯一**，此前对照结论获代码级坐实。
- **即时过滤对照**：本仓 300ms 防抖 ≈ Monica 80ms 防抖 + Rust 后台索引（`VaultOverviewSearch.kt:63-66`）≫
  Kp2a 回车才搜；搜索框形态三者趋同（本仓与 Monica 均为顶栏胶囊，Monica 另支持横拖展开）。

### §3.3 条目详情与编辑

- **本仓已覆盖**：版本历史 diff + 回滚、TOTP 三通道、Passkey 绑定/解绑、自定义字段、附件、{REF} 展开、模板、
  图标选择器、丢弃确认。
- **竞品**：KeePassDX 列表项即含 **TOTP 徽标（OtpDisplayView 自定义 view）+ 标签横排 + 所在路径显示**
  （`item_list_nodes_entry.xml:127-169`）——本仓列表行内 TOTP 倒计时徽标已对齐；Kp2a 列表项含 TOTP 区块 +
  倒计时进度条（`entry_list_entry.xml:16-94`）；Monica 卡片内嵌平滑 TOTP 进度动画（`PasswordEntryCard.kt:317-369`）
  + **字段级显示配置**（`passwordCardDisplayFields`，用户可选列表项展示哪些字段）。

**判定**：详情-编辑面无结构性差距；Monica 的「列表项字段显示自定义」并入界面偏好组登记（`ISSUE-P3-444`）。

### §3.4 反馈机制（加载态 / 错误 / 空态 / 冲突）

- **本仓**：骨架屏（列表/详情）+ 编辑全幅遮罩 + 建库页顶进度 + Snackbar+undo + 外部修改三选 + 冲突可视化页 +
  SAF 重授卡片 + 空态三形态（含双出口搜索空态）。
- **竞品空态/加载现状（代码直读）**：**KeePassDX 全应用无专门空态视图**（layout 无 empty 文件，空列表即裸
  RecyclerView）；**Monica 无 shimmer/skeleton**，通用 `EmptyState` 无出口按钮参数（`EmptyState.kt:19-56`）；
  Kp2a 空态极简（见 §3.2）。加载态：KeePassDX 进度对话框 + 页内 loading 视图；Kp2a 三形态（§3.1）。
- **判定**：**空态/骨架面本仓为四者最完整**。缺口仍收敛于「长操作过程可感知性」（KeePassDX 的可取消进度对话框
  与 Kp2a 的页内进度容器均为先例）——`ISSUE-P3-437` 已登记，本节证据由文档转述升级为代码直读，条目前提不变。

### §3.5 手势操作

- **本仓**：长按批量（+一次性引导）、下拉刷新（带上次同步时间）、逐层 BackHandler 链、顶层返回可配锁库。
- **Monica（代码直读，修正此前「无先例」结论）**：**自研双向滑动手势 `SwipeActions`——左滑删除（红）/ 右滑选择
  （蓝），过卡宽 50% 触发、弹簧回弹**（`ui/gestures/SwipeActions.kt:22-42`，8 个列表页使用）；下拉体系七文件
  （`ui/common/pull/`，含五态指示器）。
- **Kp2a / KeePassDX**：无滑动手势证据（Kp2a 为多选 + 底部提示条；KeePassDX 文档与布局均未见）。

**判定**：Monica 提供了滑动操作的**真实同类先例**，推翻本报告首版「三家均未提及 ⇒ 无差距证据」的判定——
新增 §5 G12（经 2026-10-02 用户裁决**不做**，PD-63）。

### §3.6 功能入口可达性

- **本仓**：底部 4 Tab（验证器/生成器可隐藏）+ FAB + 溢出菜单 + 设置五分组 14 二级页。
- **Monica（代码直读）**：**底栏 10 个 Tab（8 数据 + 设置），顺序与可见性均用户可配**
  （`BottomNavModel.kt:25-34` + `SimpleMainScreen.kt:909-920` 的 `bottomNavOrder`/`bottomNavVisibility`）；
  单 Tab 时可自动隐藏底栏（`:922`）。**设置页带内置搜索**（`SettingsSearchSupport.kt:23`）与 iOS 式连续圆角
  分组（`SettingsScreen.kt:113-125`）。
- **Kp2a**：解锁页抽屉（仅 4 按钮：换库/设置/捐赠/关于，`password.xml:441-474`）+ 启动页存储类型 GridView +
  4 起始按钮；**主列表无抽屉**；设置 8 组 10+ PreferenceScreen。**5-FAB 展开簇**（新建组/条目/搜索/TOTP 总览，
  `group.xml:379-433`）。KeePassDX：主界面抽屉（NavigationView + tree/recycle_bin 菜单）+ 双 FAB（新建/锁定）。
- **判定**：**底栏 Tab 顺序/显隐自定义**为 Monica 独有且形态轻（本仓已有「隐藏 Tab」的半壁）——新增 §5 G13
  （已登记 `ISSUE-P3-443`，中低优先）；设置页搜索并入界面偏好组（`ISSUE-P3-444`）。

### §3.7 错误处理友好度

- **本仓**：凭据错误统一映射、KDF 超内存/OOM 专属提示、外部修改三选、冲突页、重授卡片、弱口令二次确认（PD-38）。
- **Kp2a**：只读原因细分（三型 `UiStringKey`）+ 自签证书用户裁决——本仓两项**仍未核实**（维持 §6 声明 3）。
- **判定**：不变。

### §3.8 引导与帮助

- **本仓**：仅批量模式一次性引导。
- **KeePassDX（代码直读）**：taptargetview 引导覆盖 **5 个 Activity 场景**（组页/条目/编辑/文件列表/解锁页，
  `education/` 6 文件 + `MainCredentialActivity.kt:559-568`），一次性 + 偏好持久化。
- **Kp2a（代码直读）**：**无 onboarding 向导**；引导形态 = ⓘ 内嵌短帮助（FontAwesome 渲染，5+ 处）
  **+ 主列表 6 套可关闭提示条**（autofill/指纹/只读库/子库/通知渠道/通知权限，`group.xml:39-347`，均带
  "don't show again"）——提示条形态比全屏引导轻，更贴近本仓 `VaultListBatchGuide` 既有模式。

**判定**：G11 已登记 `ISSUE-P3-445`（限轻形态，不做全屏 onboarding）；Kp2a 的「可关闭提示条」先例使实施成本评估更低。

---

## §4 功能完整性对比

### §4.1 核心功能矩阵

| 功能 | KeePasskey（本仓） | KeePassDX | keepass2android | Monica |
|---|---|---|---|---|
| KDBX v4 读写 | ✅（PD-53 不做 v4 以前） | ✅ 全版本含 .kdb | ✅ 含 .kdb | ✅（Kotpass） |
| 生物识别解锁 | ✅ 封印凭据 + per-op 验证 | ✅ Fragment 容器嵌入 | ✅ + QuickUnlock 独立页 | ✅ 分散 BiometricPrompt |
| 密钥文件 | ✅ SAF + 记忆位置 | ✅ | ✅ | 未见 UI 面 |
| 应用内搜索 | ✅ 即时 + 域名感知 + **双出口空态** | ✅（无空态视图） | ✅（空态无出口） | ✅ 80ms 防抖 + Rust 索引（空态无出口） |
| 标签 / 收藏 | ✅ FilterChip | ✅ 标签横排于列表项 | ✅ | ✅ |
| 回收站 | ✅ 横幅 + 恢复/彻底删 | ✅ 抽屉菜单含回收站 | ✅ | 未见 |
| 版本历史 | ✅ diff + 回滚 | ✅ | 未提及 | ✅ 快照 + 回滚 |
| TOTP | ✅ 详情卡 + **独立验证器页** + 扫码/图库 + Steam（PD-62） | ✅ 列表内徽标 | ✅ 列表内区块 | ✅ 平滑进度动画 |
| Passkey（CM） | ✅ 端到端 + 就地新建（口令维度，PD-51） | ✅ 四象限 | 未提及 CM | ✅ KPEX 互通 |
| 附件 | ✅ 预览 + SAF 导出 | ✅ 大文件落盘缓存 | ✅ | ✅ 分块 |
| 条目模板 | ✅ | ✅ 伪语言引擎 | ✅ | 未见 |
| 云同步 | ✅ WebDAV/S3 + 冲突页 | ✅ 三方合并 | ✅ 离线缓存决策树 | ✅ 多源 |
| 口令生成器 | ✅ 独立页 + 历史 + 锁库可用（PD-37） | ✅ + 熵估算 | ✅ profile | ✅ zxcvbn |
| 剪贴板保护 | ✅ 定时擦除 + 实际清掉才提示 | ✅ 倒计时通知 | ✅ + 通知快捷动作 | 未提及 |
| 截屏防护 | ✅ 动态 FLAG_SECURE + 遮挡过滤 + 反悬浮窗 | 未提及细节 | ✅ + 辅助屏检测 | ✅ |
| 多语言 | ✅ + 应用内热切换 | ✅ per-app | 未提及 | ✅（设置项） |
| 子库 / 多库 | ✅ 子库只读分区 | 未提及 | ✅ 多库同时打开 | ✅ 多源混排 |

### §4.2 特色差异化

- **本仓强点**（代码直读后仍成立，部分被坐实为四者唯一）：搜索空态双出口 + 搜索词预填（四者唯一）、
  独立验证器页、冲突可视化页、骨架屏（Monica/Kp2a 均无）、语义安全色、语言热切换、遮挡触摸过滤全站接线。
- **竞品强点但已被裁决不跟进**：硬件密钥（PD-54）、键盘（PD-55）、插件（PD-56）、双栏（PD-43）、
  共享元素过渡（PD-29，Monica 11 处先例）、老格式（PD-53）、零候选新建 passkey（PD-51）、AutoType 执行方（PD-42）。
- **竞品强点的处置**：可做的 8 项已登记 `ISSUE-P3-439`~`ISSUE-P3-446`；排除的 4 项见 PD-63~66（§5 表内逐行标注）。

### §4.3 辅助功能（无障碍）

- **本仓**：74 个 UI 文件含 contentDescription、装饰图标传 null、`semantics{password()}`、读屏加载标签、
  `Role` 声明、对比度专项整改。
- **竞品（代码直读计数）**：**Monica 最强——contentDescription 1588 处 / semantics 132 处**，装饰图标显式置空、
  操作图标全带本地化描述；KeePassDX 中等——63 处 + `importantForAccessibility="no"` 用于装饰控件；
  **Kp2a 最弱——contentDescription 仅 3 处**（密码框另有 11 处安全向 `importantForAccessibility="no"`，
  是防读屏而非增强）。
- **判定**：本仓处中上水位（密度低于 Monica、远高于 Kp2a）；无障碍专项审计建议另行立项，本报告不预登记。

---

### §4.4 密钥文件归属维度（2026-10-03 源码直读）

> 2026-10-03 用户报两条真机现象（① 生成密钥文件后保存「一闪而退」⇒ 第二因子丢失；② 切换密码库后密钥文件不变），
> 本轮**按源码定点直读**三家，不重新扫全树；结论同时登记为 `ISSUE-P2-460` 与
> [`../architecture/产品裁决登记.md`](../architecture/产品裁决登记.md) PD-67。

- **KeePassDX**：密钥文件 URI 落在 Room `file_database_history` 表的 **`keyfile_uri` 列**，
  该表主键即 `database_uri`（`FileDatabaseHistoryEntity.kt`）——**归属天然在库行上，结构上不可能串库**；
  `PreferencesUtil` 里**不存在任何 keyFile 偏好键**（无全局记忆层）；解锁时 `MainCredential.getKeyFileData`
  走 `getUriInputStream(keyFileUri)` **现读外部文档**，应用私有目录不落副本。
- **keepass2android**：SQLite `FileTable` 有独立 **`keyFile` 列**（`FileDbHelper.cs`，`KeyFileId` 自增主键 ⇒ 独立记录表）；
  **三态语义**——`keyFile == null` 表示「保留（未知/不改动）」，只有显式传 `""` 才是「故意清除该库这一行」
  （`CreateFile(ioc, keyFile, …)` 建库时传 `""`）。「生成了却没存下」有硬门：`CreateDatabaseActivity.CreateDatabase()`
  里 `KcpKeyFile` 取不到 ⇒ `ShowMessage(error_adding_keyfile)` 后**直接 return，库根本不会创建**。
- **Monica**：`local_keepass_databases` 四元组 `key_file_uri` + `key_file_internal_path` + `key_file_name` +
  `key_file_fingerprint`；内部副本走 `KeePassKeyFileStore`（应用私有目录，**按 SHA-256 指纹命名**，
  `cleanupUnreferencedInternalKeyFile` 做无引用回收）；建库/存库侧由 `KeePassKdbxService` 先落副本再登记指纹，
  UI 侧 `AddEditPasswordScreen:1151` 硬闸门——`keyFileInternalPath` 非空**或** `generatorVerification[id]` 已
  `Verified` 才放行，副本事后 `fingerprint(readInternal(p)) == fingerprint` 校验。
- **本仓现状（对照结论）**：副本层 `KeyFileVaultCopyStore`（§411）**已按 `dbId` 落盘**，但**记忆层**
  `RealSettingsRepository.setRememberedKeyFile` 只写 `last_key_file_uri` / `last_key_file_name` **两个全局键**
  （唯一写入者 `KeyFileSessionCoordinator.rememberKeyFileOnSuccess` 仅在「解锁成功」触发），
  建库链路既不登记也不收编、反在 `finally` 里 `fill(0)` 擦字节，恢复侧副本缺失时**裸落全局 Uri 且无归属校验**。
- **判定**：全局「记住上次的密钥文件」这一层**在四者中是孤例**——三家全部把归属收在「库表的一列/一行」上。
  本轮登记 `ISSUE-P2-460`，整改口径（用户裁决）＝**按库归属 + 建库即登记 + 取不到就显式告知**；
  「副本文件名 `.kfc` 为随机名」与 Monica 的**指纹命名**不同，**若将来要做副本完整性校验，命名需改为指纹**——
  该子项不单独立项，随 P2-460 一并评估。

---

## §5 改进点清单与优先级排序

> 处置栏：**已登记**＝已按 ACTIVE_ISSUES 维护规则补登；**不跟进（PD）**＝2026-10-02 用户裁决不做
> （G4/G8/G10/G12 → PD-64/65/66/63）或与既有 PD/限界冲突或无差距证据。
> **登记结果（2026-10-02 用户裁决「G3/G5/G6/G7/G9/G11/G13/G14 可以做，其余排除」）**：
> G3→`ISSUE-P3-439`、G6→`ISSUE-P3-440`（安全复核为硬门）、G7→`ISSUE-P3-441`、G5→`ISSUE-P3-442`、
> G13→`ISSUE-P3-443`、G14（含动效降级）→`ISSUE-P3-444`、G11→`ISSUE-P3-445`、G9→`ISSUE-P3-446`；
> 排除四项已落 [`../architecture/产品裁决登记.md`](../architecture/产品裁决登记.md) PD-63~66。
>
> **2026-10-03 更新（闭环回执）**：G6→`ISSUE-P3-440`、G5→`ISSUE-P3-442`、G14→`ISSUE-P3-444`
> 三条已由 [`../resolved/batches/425-竞品对照三项闭环批次.md`](../resolved/batches/425-竞品对照三项闭环批次.md)（§425）
> **整条闭环**——440 的前置安全复核落 `PD-69`（字段子集收窄为「用户名 + TOTP」，密码主动放弃）、
> 444 AC④ 的界面缩放口径落 `PD-70`（不建独立缩放偏好）。表内三行「处置」列的「已登记」为该日快照，
> 现状以本条为准。

| 编号 | 维度 | 问题描述 | 对比依据（代码直读） | 改进建议 | 预期效果 | 优先级 | 处置 |
|---|---|---|---|---|---|---|---|
| G1 | 操作体验·反馈 | 解锁（KDF 派生 / 密钥文件 / 封印解封）零进度指示；同步与导入导出无过程反馈 | KeePassDX `ProgressTaskDialogFragment`（含警告行 + 取消按钮，`fragment_progress.xml:70`）贯穿加解密；Kp2a `LoadingDialog`/`SimpleLoadingDialog`/页内 `BackgroundOperationContainer` 三形态 | ① 解锁页状态驱动阶段文案（「正在派生密钥…」起步）；② 同步/导入导出复用同一过程反馈通道 | 大库/弱机用户不再「猜卡没卡」 | **高** | **已登记 `ISSUE-P3-437`** |
| G2 | 操作体验·反馈 | 进程被系统杀死后无「上次会话未正常关闭」提示 | Kp2a `AppKilledInfo`（其架构分析 §8.4） | 解锁页一次性轻提示（仅异常退出时） | 消除「会话丢了」困惑 | 低 | **已登记 `ISSUE-P3-438`** |
| G3 | 操作体验·搜索 | 无高级搜索选项（字段勾选/正则/大小写/排除过期），仅两档 | Kp2a `SearchActivity.cs:93-115`（交互对照 §4C） | 字段范围勾选 + 排除过期先行；正则档单独评估 | 大库检索效率 | 中 | **已登记 `ISSUE-P3-439`** |
| G4 | 操作体验·解锁 | 无 PIN/口令包装解锁档 | Monica 架构分析记 `unlock_methods` 表，**但 UI 层未找到 PIN 盘实现**（`grep PinPad|Keypad` 零命中）——先例弱化为数据层声明 | 若引入按「可撤销派生凭证」设计；优先级下调 | 生物识别失效场景补充 | 低（下调） | **不跟进（PD-64，2026-10-02 用户裁决）** |
| G5 | 操作体验·搜索 | 手选结果后无「把搜索词写进条目」自愈 | Kp2a `AppTask.cs:494-534` | 评估回灌链路成本 | 匹配错误一次修正 | 中低 | **已登记 `ISSUE-P3-442`** |
| G6 | 操作体验·通知 | 前台通知无复制快捷动作（已有「立即锁定」） | Kp2a `CopyToClipboardService.cs:111-156` | 须先过安全复核（通知面敏感字段暴露口径） | 高频复制少一步 | 中 | **已登记 `ISSUE-P3-440`**（安全复核为硬门） |
| G7 | UI 设计·主题 | 主题预设 5 套 vs Monica 11 套静态方案 + **自定义种子色生成器** + KeePassDX 17 主题 | Monica `CustomColorSchemeGenerator.kt`（五种子色 → M3 scheme，`Theme.kt:878-942`）；KeePassDX `Stylish.kt:129-150` | ① 低成本扩充调色盘枚举；② 更有价值的是「自定义种子色」入口（复用现有三族覆写管线，取一色生成全方案） | 个性化面扩大 | 中低 | **已登记 `ISSUE-P3-441`** |
| G8 | UI 设计·图标 | 无图标包扩展机制 | KeePassDX icon-pack 双包（material 69 vector + classic PNG）；**Kp2a 支持外部应用形式可插换图标包**（`DrawableFactory.cs:69-92` GetIdentifier） | 待插件生态（PD-56）联动裁决 | — | 低 | **不跟进（PD-65，2026-10-02 用户裁决）** |
| G9 | UI 设计·一致性 | Toast 残留 3 处 | 本仓走查（对照：Kp2a 统一 Presenter 仅 7 处 Toast；Monica 393 处为反面参照） | 收敛进 `AppSnackbarChannel` | 通道单一化 | 低 | **已登记 `ISSUE-P3-446`** |
| G10 | 功能完整性 | 条目类型单一（凭据 + Passkey） | Monica 多态条目（其架构分析 §3.2） | 若扩展从「字段模板预设」轻形态起步 | 覆盖更多记忆场景 | 低 | **不跟进（PD-66，2026-10-02 用户裁决）** |
| G11 | 操作体验·引导 | 无 onboarding / 内嵌帮助 | KeePassDX taptargetview 覆盖 5 个 Activity 场景（`education/`）；Kp2a ⓘ 短帮助 + **6 套可关闭提示条**（`group.xml:39-347`）——提示条形态最轻 | 优先「设置项旁 ⓘ」与「可关闭提示条」轻形态 | 首用理解成本下降 | 低 | **已登记 `ISSUE-P3-445`**（限轻形态，不做全屏 onboarding） |
| G12 | 操作体验·手势 | 列表无滑动快捷操作 | **Monica 自研 `SwipeActions`（左滑删/右滑选，50% 阈值弹簧回弹，8 个列表页使用，`SwipeActions.kt:22-42`）**——修正首版「无先例」结论 | 若裁决引入：右滑快捷复制（用户名/密码）比左滑删除更贴密码库安全语义 | 高频操作少一步 | 中低 | **不跟进（PD-63，2026-10-02 用户裁决）** |
| G13 | UI 设计·导航 | 底栏 Tab 顺序不可调（仅验证器/生成器可隐藏） | Monica `bottomNavOrder` + `bottomNavVisibility` 用户可配（`SimpleMainScreen.kt:909-920`），单 Tab 自动隐藏底栏（`:922`） | 在既有「隐藏 Tab」设置上追加排序（数据结构相近） | 高频页可前置 | 中低 | **已登记 `ISSUE-P3-443`** |
| G14 | UI 设计·排版偏好 | 无界面缩放/字号偏好、无等宽字体开关、设置页无搜索 | Monica `InterfaceScaleSettingsItem` + BottomSheet（`SettingsScreen.kt:747-752`）与 `SettingsSearchField`（`SettingsSearchSupport.kt:23`）；KeePassDX 等宽开关默认开（`donottranslate.xml:230-231`） | 「界面偏好」一组：界面缩放（或跟随系统字体缩放自检）+ 等宽开关 + 设置页搜索 | 可达性与个性化 | 中低 | **已登记 `ISSUE-P3-444`** |
| — | UI 设计·动效 | 动效降级开关（低端机/ROM 兼容保险丝） | Monica `LocalReduceAnimations`（`ui/LocalSharedTransition.kt:10-18`，为 HyperOS 2 / Android 15 卡顿设） | 并入 G14「界面偏好」组一并裁决 | 低端机可用性 | 低 | **已登记（并入 `ISSUE-P3-444`）** |
| — | UI 设计·响应式 | 宽屏双栏 ListDetail | — | — | — | — | **不跟进**（PD-43；Monica 同为「宽屏换 Rail」，形态共识） |
| — | UI 设计·动效 | 列表→详情共享元素过渡 | Monica `sharedBounds` 11 处先例 | — | — | — | **不跟进**（PD-29） |
| — | 功能完整性 | 硬件密钥/键盘/插件/老格式/零候选新建 passkey/AutoType 执行方 | — | — | — | — | **不跟进**（PD-54/55/56/53/51/42） |
| — | 操作体验·搜索 | 即时过滤改回车制、系统 SearchProvider | — | — | — | — | **不跟进**（本仓即时过滤 + Monica 80ms 先例证实现口径更优；交互对照 B 档） |

---

## §6 未命中与未核实声明

1. **取证方式升级**：首版「竞品视觉细节大量缺证」已于 2026-10-02 经用户指令**定向直读三家竞品 UI 代码**补证
   （只读；每条结论附 `文件:行号`）；非 UI 面结论仍以 `docs/references/` 架构分析文档为准。两处以代码为准的
   **修正**：① KeePassDX 动态取色实为 Material DynamicColors，chroma 仅用于库内颜色选择器；② Monica UI 层
   **未见** PIN 解锁盘（文档所记 `unlock_methods` 为数据层声明）。
2. **未实机运行参考应用**：交互结论为代码 + 文档推定；涉及真机手感（动效流畅度、震动强度等）不在断言范围。
3. **本仓两项未核实**：① 只读会话提示是否细分原因文案（Kp2a 三型先例，§3.7）；② WebDAV 自签名证书场景
   交互形态（涉安全口径）。两项目前均未登记。
4. **已知限界交叉**：相机实拍扫码可扫性（§35「摄像机问题，不解决」）、系统设置直达可用性（§27）、SAF 写回
   非原子文案（§24）、弱 KDF 导入告警（§8）均由限界表承接，本报告不重复。
5. **编号与登记纪律（2026-10-02 裁决后更新）**：登记条目从 `ISSUE-P3-437` 起（§406 已用至 436）。
   2026-10-02 用户裁决：G3/G5/G6/G7/G9/G11/G13/G14（含动效降级）**可以做**，已登记为
   `ISSUE-P3-439` ~ `ISSUE-P3-446`；G4/G8/G10/G12 **排除**，已落
   [`../architecture/产品裁决登记.md`](../architecture/产品裁决登记.md) PD-63~66，此后竞品对照**不得**
   再将其登记为缺陷或待办。

---

## §7 总体结论

代码直读补证后，首版判断**增强并局部修正**：本仓 UI/体验面相对三家**无结构性落后**，且多项面被坐实为
四者最完整（搜索空态双出口、骨架屏、冲突可视化页、语义安全色）；视觉面四家均为 Material 3 基座，差异在
预设广度与个性化入口（Monica 领先：11 方案 + 自定义种子色 + 界面缩放）。

**真实残余差距仍收敛为一类**：**长操作的过程可感知性**（`ISSUE-P3-437`，高优先；KeePassDX 可取消进度对话框
与 Kp2a 页内进度容器均为代码级先例）。2026-10-02 用户裁决后，八项有代码先例的轻量增量已登记待办
（`ISSUE-P3-439`~`ISSUE-P3-446`：高级搜索、通知复制动作〔须安全复核〕、自定义种子色、搜索词自愈、
Tab 排序、界面偏好组、轻引导、Toast 清扫）；四项经裁决不做（滑动操作 / PIN 包装 / 图标包 / 多态条目，
PD-63~66）；其余维持既有 PD 条目引用。
