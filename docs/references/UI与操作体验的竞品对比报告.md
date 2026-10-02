# UI 与操作体验的竞品对比报告（KeePasskey vs KeePassDX / keepass2android / Monica）

> **文档定位**：面向 UI 设计、操作体验、功能完整性三个维度的结构化竞品对比分析报告。
> **对比对象**：三个同类安卓应用＝参考项目中的 KeePassDX（🥇 核心参考）、keepass2android（🥈 云同步参考）、
> Monica（🥉 UI / 本地优先辅助）；KeePassXC / KeePass 官方为桌面端格式裁决者，不在本次对比范围。
> **核实时间点**：2026-10-02。
> **核实方式**：① 竞品事实全部取自 `docs/references/` 既有分析文档（`KeePassDX-架构分析.md`、
> `keepass2android-架构分析.md`、`Monica-架构分析.md`、`交互体验的参考项目对照.md`），**未扫描** `参考项目/` 源码
> （AGENTS.md 规则 3）；② 本仓事实取自 `app` 模块 UI 层逐文件走查（正文以 `文件:行号` 引用）。
> **证据边界声明**：本报告**未实机运行三款参考应用**，故用户要求的「对比截图」以「说明 + 出处」承载；
> 竞品侧 UI 事实均为**据架构分析文档转述**（各文档原文即声明其为架构分析而非 UI 专项分析），
> 凡文档未覆盖的维度一律显式标注「文档未提及」，**不得**据其绿推定「竞品无此能力」。
> **结论分流纪律**：改进点分三档——**已登记**（进 `ACTIVE_ISSUES.md`）/ **待裁决**（属产品取舍，
> 待用户拍板前不进待办）/ **已裁决不跟进**（与 [`../architecture/产品裁决登记.md`](../architecture/产品裁决登记.md)
> 既有 PD 条目冲突，仅引用不重复登记）。

---

## §1 对比对象与技术底座

| | KeePasskey（本仓） | KeePassDX | keepass2android | Monica |
|---|---|---|---|---|
| UI 技术栈 | 单 Activity + Jetpack Compose + **Material 3 Expressive**（`app/build.gradle.kts:263-280`）+ Navigation Compose | View 体系（XML + Fragment + Material Components），Activity 跳转式 | 传统 Activity/XML（MAUI 单项目形态但**未用** MAUI UI） | 100% 纯声明式 Compose + Navigation Compose |
| 主浏览界面 | 单 NavHost + 底部 Tab / 宽屏 NavigationRail | `GroupActivity`（1536 行）内 GroupFragment/SearchFragment 切换 + 面包屑 | `PasswordActivity → GroupActivity → EntryActivity` 跳转链 | NavHost + `Screen` 密封类路由 |
| 文档对 UI 描述的完备度 | 本报告直接走查代码 | 集中于其架构分析 §6.2/§6.5/§6.7/§7 | §7 交互链较全，**视觉面未涉及** | §2.2/§2.3/§2.6 较全 |

> 本仓 UI 层文件结构、主题、导航、反馈、手势、动效、无障碍、响应式的逐项事实清单见本次走查结论
> （关键锚点：`app/src/main/java/com/keepasskey/app/ui/` 各 screen 包、`ui/theme/Theme.kt`、
> `ui/navigation/AppNavigationMotion.kt`、`ui/components/AppBottomBar.kt`）。

---

## §2 UI 设计维度对比

### §2.1 视觉风格与主题系统

- **本仓**：`MaterialExpressiveTheme` + `MotionScheme.expressive()`（`ui/theme/Theme.kt:116-121`）；三态模式
  LIGHT/DARK/SYSTEM；Android 12+ 动态取色（Material You）与 5 套品牌调色盘（SAPPHIRE/EMERALD/AMETHYST/
  AMBER_SUNSET/OBSIDIAN）互斥单选（`ThemeMode.kt:27-44, 50-199`）；OLED 纯黑深色模式（`Theme.kt:69-73`）；
  **语义安全色独立于主题**——passkey/success/warning/danger 四族经 `LocalSecurityColors` 提供、不随壁纸漂移
  （`Theme.kt:34-50, 174-190`）。
- **KeePassDX**：`Stylish` 主题引擎 + chroma（AndroidClearChroma）动态取色；跟随系统日夜换算；free flavor 禁用
  8 个主题（出处：其架构分析 §6.7）。
- **Monica**：Monet 动态取色 + **Catppuccin 四型**（Latte/Frappe/Macchiato/Mocha）+ 浅/深/**纯黑**三模式
  （出处：其架构分析 §2.2）。
- **keepass2android**：**文档未提及**视觉设计。

**判定**：主题四要素（动态取色 / 暗色 / 纯黑 / 多预设）本仓已具备前三者与 5 套预设，处于第一梯队。
残余差距仅「主题预设广度」（Monica 的 Catppuccin 系社区审美预设、KeePassDX 8+ 主题）——见 §5 编号 G7（待裁决，低优先）。

### §2.2 图标体系

- **本仓**：`material-icons-extended` 全矢量；条目图标 = KDBX 内置 68 图标 + 库内自定义位图（`ui/model/EntryIcon.kt`、
  `IconBitmapCache.kt`、`IconPickerDialog.kt`、`EntryEditIconCodec.kt`）。
- **KeePassDX**：独立 `icon-pack` Gradle 模块（classic/material 两个子包）**插件式图标包** + KDBX 自定义图标池 +
  图标编辑 UI（其架构分析 §2.1/§2.4/§9.4）。
- **Monica / keepass2android**：文档未提及。

**判定**：核心面（KDBX 内置 + 自定义图标 + 选择器）已对齐；差距仅在「图标包扩展生态」——
KeePassDX 的 icon-pack 是**独立模块加载外部包**形态，本仓单库产品定位下收益有限，列 §5 G8（待裁决，低优先）。

### §2.3 排版与布局

- **本仓**：固定 Typography + 全局 CJK 断行策略 `LineBreak(Strict, Phrase)`（`ui/theme/Type.kt:17-33`，中文排版
  专项）；密码/TOTP 等宽样式；列表密度偏好（紧凑/标准，`vault/ListDensitySpec.kt`）。
- **三家竞品**：排版/字体/密度均**文档未提及**。

**判定**：无差距证据；本仓 CJK 断行与密度偏好反系可引用的领先点。

### §2.4 控件使用一致性

- **本仓**：BentoCard 卡片体系 + 全局唯一 Snackbar 宿主（跨导航存活、支持 undo，`AppGlobalSnackbarHost.kt:19-50`）+
  设置二级页统一 `SettingsSubscreenScaffold` + 触觉反馈全站收口（`Haptics.kt:21-33`）。
- **残余不一致**：Toast 仍有 3 处残留（`ClipboardSecurityManager.kt:282`、`EntryDetailUrlActions.kt:108`、
  `OpenVaultEntryActivity.kt:108`），与全局 Snackbar 通道并存。
- **竞品**：KeePassDX 23 个 DialogFragment 各自为政、`PreferencesUtil` 静态读写（其文档自评为应规避形态）；
  Kp2a 事件总线广播驱动刷新（其文档 §9.3 自评应换 Flow/diff）。

**判定**：本仓控件一致性显著优于三家的自评状态；残余为 Toast 清扫项，列 §5 G9（低优先，可随手批处理）。

### §2.5 响应式适配

- **本仓**：≥600dp 切换 BottomBar↔NavigationRail（`KeePasskeyApp.kt:315, 346-353`）；预测式返回已启用且配专属
  90% 缩放转场（manifest `android:enableOnBackInvokedCallback="true"` + `KeePasskeyApp.kt:364-366`）；
  全局 `imePadding` + 解锁页 IME 避让修正。
- **未使用** WindowSizeClass API、无双栏布局。
- **竞品**：Kp2a 有辅助屏检测跳转 `NoSecureDisplayActivity`（防投屏泄露，其架构分析 §8.3）；其余文档未提及。

**判定**：宽屏双栏（ListDetail）**已被 PD-43 裁决不做**（用户明示「不考虑大屏幕」）——Kp2a/Monica 即便有类似能力
也不构成差距，本报告**不登记**。WindowSizeClass 化属实现细节替换（现口径 `screenWidthDp>=600` 语义等价），
无用户可感知收益，不单列。

### §2.6 动效设计与过渡

- **本仓**：`AppNavigationMotion.kt` 全量定标四类转场（下钻共享轴 X + 视差、顶层 Tab 双向交叉淡化、预测返回缩放、
  内容层 MotionScheme spec），并有源码守卫锁定「全仓不得再出现裸 `tween(`」；列表增删 `Modifier.animateItem()`；
  主题胶囊 `AnimatedContent`、骨架 `Animatable` 淡入。
- **PD 已定标**：PD-26（双栏定标）→ PD-44（降档复定标）、PD-27（Tab 用 Fade Through、下钻共享轴 X，有意偏离
  Android Auto 口径）、PD-28（预测返回保留 90% 缩放）、PD-29（**不做**列表→详情共享元素过渡）。
- **竞品**：Monica 用 `SharedTransitionCompat` 横滑 + 淡入淡出（其架构分析 §2.2）；KeePassDX 文档未提及动效。

**判定**：动效面**已裁决定标且经测试锁定**，Monica 的 SharedTransition 先例与 PD-29 冲突，**不登记**。
本维度无开放改进点。

---

## §3 操作体验维度对比

### §3.1 解锁 / 建库流程

- **本仓现状**：双模式（生物识别 QuickUnlock ↔ 主密码完整解锁，`UnlockScreen.kt:316-338`）；生物识别自动唤起
  一次性意图；`SecurePasswordField` 等宽 + `semantics { password() }`；密钥文件走 SAF；只读开关；无库空态就地
  弹导入/新建框；离开页面清空密码缓冲（`UnlockScreen.kt:80-93`）。
- **竞品**：KeePassDX 解锁页三项凭据 + 进度对话框贯穿加解密全流程（`ProgressTaskUpdater`，其文档 §6.3/§8.2 第 10 条
  并点名为「大库在低端机上的可感知性」）+ 记住凭据快速校验；Kp2a QuickUnlock 尾部 N 字素 + 通知栏快捷解锁 +
  `AppKilledInfo`「上次被系统杀死」提示（其架构分析 §6.3/§8.4）；Monica 生物识别释放会话密钥 + 三档自动锁定策略。
- **差距与处置**：
  - **G1 解锁全过程零进度反馈**（本仓 `UnlockScreen.kt` 无任何 CircularProgressIndicator/LinearProgress/过程文案，
    `strings.xml` `unlock_*` 无「正在派生/正在解锁」类条目——2026-10-02 grep 核实）：KDF 派生秒级乃至弱机数十秒
    （`ISSUE-P1-431` 实测 StrongBox 分块 700 KB 达数十秒）期间界面无任何变化，用户只能猜。**已登记
    `ISSUE-P3-437`**。
  - **G2 封印载荷性能**：已登记 `ISSUE-P1-431`（方案已裁决），本报告不重复。
  - **G3 进程被杀后无「上次会话未正常关闭」提示**：Kp2a `AppKilledInfo` 先例；本仓仅有编辑面「上次未保存的改动
    已丢弃」（`strings.xml:399`），锁库/会话面无对应说明。**已登记 `ISSUE-P3-438`**（低优先）。
  - **G4 PIN/口令包装解锁**（Monica `unlock_methods` 表：PIN/Password/Security Key 包装 Vault Key）：现口径
    PD-46＝快速解锁只留「封印凭据 + per-operation 生物识别」，增加 PIN 通道属产品取舍，列 §5 待裁决。

### §3.2 条目浏览与搜索

- **已对齐（勿重复整改）**：`ISSUE-P3-352` 已整条结案（批次 §348）——① 搜索空态专用文案 +「新建凭据条目 / 清除
  搜索」双出口 + 搜索词一次性预填（走 `CreateEntryPrefillHost` 内存单例）；② 应用内搜索域名感知档（加性 OR：
  父子域 + `www.` 剥离，`DomainMatcher` 零改动）；③ 剪贴板定时擦除实际清掉才 Toast。
- **本仓仍领先处**：常驻胶囊搜索框 + 300ms 防抖即时过滤（Kp2a 为回车才搜，出处 `交互体验的参考项目对照.md` §2）。
- **残余差距（均属此前对照文档 C 档「待裁决」，本报告维持该分流）**：
  - **G5 高级搜索选项**（Kp2a `SearchActivity.cs:93-115`：字段勾选 / 正则 / 大小写 / 排除过期）；本仓仅
    CONTAINS/ALL_TERMS 两档。正则引入注入面与性能预算，需单独评估。
  - **G6「记住搜索词」自愈**（Kp2a `AppTask.cs:494-534`：用户手选条目后询问把搜索词写进条目 URL，一次操作修正
    匹配错误）；涉及选择结果回灌链路成本。

### §3.3 条目详情与编辑

- **本仓已覆盖**：版本历史 diff + 回滚（`EntryDetailRevisionController`）、TOTP 三通道（手输/扫码/图库）、
  Passkey 绑定/解绑、自定义字段逐字段显隐复制、附件预览 + SAF 导出二次确认、{REF} 引用展开、过期卡、
  图标选择器、内嵌口令生成器、条目模板（`entry_edit` 路由带 `templateId`）、丢弃未保存确认。
- **竞品**：KeePassDX 模板引擎 + 字段引用引擎 + 附件查看 Activity + 剪贴板倒计时清除通知；Kp2a 模板编辑 + SPR
  占位符 + 插件菜单项；Monica 字段级历史 + 单条/全库回滚 + 多态条目类型（card/identity/ssh-key 等）。

**判定**：本仓对 KeePassDX/Kp2a 的详情-编辑面已基本对齐。Monica 的**多态条目类型**（银行卡/身份/WiFi/SSH 等
卡片式条目）是真实差异，但属产品定位取舍（本仓定位＝凭据 + Passkey 密码库），列 §5 G10（待裁决）。
AutoType 键入序列本仓「兼容保存、不实现执行方」＝PD-42 已裁决，不登记。

### §3.4 反馈机制（加载态 / 错误 / 空态 / 冲突）

- **本仓**：骨架屏（列表/详情，静态块 + MotionScheme 淡入）、全幅遮罩进度圈（编辑载入）、建库进行中页顶进度条
  （`DatabasePickerScreen.kt:219-235`）、全局 Snackbar + undo、外部修改三选对话框（`ExternalModificationDialogHost`）、
  独立冲突可视化页（`ConflictResolutionScreen`：对比 + FilterChip 三选 + 批量合并前确认）、SAF 授权失效重授卡片、
  弱口令显式二次确认（PD-38）、空态三形态（列表/库选择/详情未找到）。
- **竞品**：KeePassDX 进度对话框 + 外部修改对话框 + 四个通知族；Kp2a 缓存监督六回调外抛 Snackbar/通知 + 两级
  进度消息（可取消）+ 前台同步服务进度通知；Monica 冲突管理面板（Local/Incoming 字段对比）+ 数据库健康诊断
  修复计划 UI（先演练后替换）。

**判定**：冲突面本仓已是三者中最完整的显式 UI；**缺口收敛到「长操作过程可感知性」**——本仓解锁/云同步/导入导出
均为「结果型反馈」（起点转圈或终态 Snackbar），无分阶段/进度型反馈；Kp2a 的两级进度消息 + 可取消 + 前台服务
进度通知是明确先例。与 §3.1 G1 合并为 `ISSUE-P3-437` 一条整改面（解锁 KDF 派生、同步上传下载、导入导出三类
长操作）。

### §3.5 手势操作

- **本仓**：长按进批量模式（含首次一次性引导 `VaultListBatchGuide`）、下拉刷新（带「上次同步时间」自定义指示器）、
  逐层 BackHandler 优先级链（批量→清搜索→返上级→交还系统）、顶层返回键可配锁库。
- **竞品**：Kp2a 多选 + MoveElementsTask；Monica 批量选择模式（仅顺带提及）；**滑动操作三家文档均未提及**。

**判定**：无差距证据（无竞品先例支撑引入 SwipeToDismiss）；不登记。

### §3.6 功能入口可达性

- **本仓**：底部 4 Tab（验证器/生成器可隐藏）+ FAB 新建类型选择 + 溢出菜单（排序/锁定/扫码/选择/退出）+ 设置页
  五卡分组 14 个二级页 + 条目级「禁用自动填充」开关 + 系统设置页直达（§27 限界内如实降级）。
- **竞品**：KeePassDX 设置散落多个 PreferenceFragment + 独立设置 Activity；Kp2a 协议前缀即云入口 + 插件管理页；
  Monica 按数据源分页面。

**判定**：无结构性差距。Kp2a 的「插件可注入条目菜单」依赖插件宿主（PD-56 不评估不接入），不登记。

### §3.7 错误处理友好度

- **本仓**：凭据错误统一映射、KDF 超内存/OOM 专属提示（对齐 KeePassDX 的 `KDFMemoryDatabaseException` 口径）、
  远端失败不丢数据只上报、外部修改三选、冲突页、重授卡片。
- **Kp2a 独有先例**：只读原因**细分到用户可读**（`OptionalOut<UiStringKey> reason`：只读标志 / KitKat 限制 /
  本地备份文件三型）；自签名证书用户裁决（信任记忆）。
- **判定**：本仓只读态已有「只读会话」分区提示，但**未核实**是否细分原因文案；自签证书 WebDAV 场景的交互
  **未核实**（本仓全站 HTTPS-only、零证书固定，是否允许用户裁决自签证书属安全口径而非纯 UX）——两者均列入
  §6 未核实声明，不登记。

### §3.8 引导与帮助

- **本仓**：仅批量模式一次性引导；无 onboarding / 内嵌帮助。
- **竞品**：KeePassDX `education/` 包 taptargetview 首用引导；Kp2a `Kp2aShortHelpView` 内嵌短帮助（其文档自评弱）。
- **判定**：此前对照文档已列 C 档待裁决（价值取决于目标用户群），本报告维持——列 §5 G11。

---

## §4 功能完整性对比

### §4.1 核心功能矩阵

| 功能 | KeePasskey（本仓） | KeePassDX | keepass2android | Monica |
|---|---|---|---|---|
| KDBX v4 读写 | ✅（v4，PD-53 明确不做 v4 以前格式） | ✅ 全版本含 .kdb v3 | ✅ 含 .kdb | ✅（Kotpass） |
| 生物识别解锁 | ✅ 封印凭据 + per-op 验证 | ✅ StrongBox + 按库加密凭据 | ✅ 指纹 + QuickUnlock | ✅ 会话密钥释放 |
| 密钥文件 | ✅ SAF + 记忆位置 | ✅ | ✅ | 文档未提及 |
| 应用内搜索 | ✅ 即时过滤 + 域名感知 + 空态双出口 | ✅ SearchFragment | ✅ 跨已开库 + 系统 SearchProvider | ✅ Rust 模糊搜索 |
| 标签 / 收藏 | ✅ FilterChip | ✅ 标签池 | ✅（能力位） | ✅ 分类 + 收藏 |
| 回收站 | ✅ 警示横幅 + 恢复/彻底删 | ✅ 可配置 | ✅（CanRecycle） | 文档未提及 |
| 版本历史 | ✅ diff + 回滚 | ✅ EntryHistoryFragment | 文档未提及 | ✅ 快照 + 回滚 |
| TOTP | ✅ 详情卡 + **独立验证器页** + 扫码/图库 + Steam Guard（PD-62） | ✅ | ✅ 定时刷新 | ✅ 含 Steam 映射 |
| Passkey（CM） | ✅ 生成/存储/验证端到端 + 就地新建（口令维度，PD-51） | ✅ 四象限 | 文档未提及 CM | ✅ + KPEX 互通 |
| 附件 | ✅ 预览 + SAF 导出 | ✅ 大文件缓存落盘 | ✅ | ✅ 一等公民分块 |
| 条目模板 | ✅（templateId） | ✅ 伪语言引擎 | ✅ | 文档未提及 |
| 云同步 | ✅ WebDAV/S3 + 冲突可视化页 | ✅ 三方合并 | ✅ 离线缓存决策树 + 后台秒开 | ✅ 多源聚合 |
| 口令生成器 | ✅ 独立页 + 历史 + 锁库可用（PD-37） | ✅ 密码+口令短语+熵估算 | ✅ profile + 用户熵混入 | ✅ zxcvbn 实时评估 |
| 剪贴板保护 | ✅ 定时擦除 + 实际清掉才提示 | ✅ 倒计时清除通知 | ✅ + 通知快捷动作 | 文档未提及 |
| 截屏防护 | ✅ FLAG_SECURE 动态守卫 + 遮挡触摸过滤 + 反悬浮窗 | 文档未提及细节 | ✅ + 辅助屏检测 | ✅ 可配置 |
| 多语言 | ✅ 中文/英文 + **应用内热切换** | ✅ per-app language | 文档未提及 | 文档未提及 |
| 子库 / 多库 | ✅ 子库挂载（只读分区） | 文档未提及 | ✅ 多库同时打开 | ✅ 多源混排 |

### §4.2 特色差异化

- **本仓强点**（对三家形成差异，应保持并对外传达）：独立验证器页（集中 TOTP 管理，三家皆无对应形态）、
  生成器历史记录、健康检查页（弱口令体检）、冲突可视化三选页、语言热切换、外部修改三选对话框、
  语义安全色不随壁纸漂移、遮挡触摸过滤全站接线。
- **竞品强点但已被裁决不跟进**（引用原 PD，**不得**登记为缺陷）：硬件密钥 Yubikey（KeePassDX/Kp2a，
  PD-54）、自定义键盘填充（Magikeyboard/KP2A Keyboard，PD-55）、插件宿主与插件注入菜单（PD-56）、
  多库同时打开（本仓以子库 + 切库覆盖大半，产品定位单库，无需 PD 亦有先例分流）、老格式兼容（PD-53）、
  宽屏双栏（PD-43）、列表→详情共享元素（PD-29）、零候选新建通行密钥（PD-51，安全红线）。
- **待裁决的竞品强点**：多态条目类型（G10）、PIN 包装解锁（G4）、高级搜索（G5）等见 §5。

### §4.3 辅助功能（无障碍）

- **本仓**：74 个 UI 文件含 contentDescription、装饰性图标传 null；`semantics { password() }`、
  `clearAndSetSemantics`（验证器卡）、骨架读屏加载标签、`Role` 声明、对比度专项整改留痕（`Color.kt:15-36` 注释）。
- **三家竞品**：无障碍维度**文档均未提及**。

**判定**：无差距证据；本仓现状可作内部基线保持（后续若做无障碍专项审计应另行立项，本报告不预登记）。

---

## §5 改进点清单与优先级排序

> 处置栏：**已登记**＝已按 ACTIVE_ISSUES 维护规则补登；**待裁决**＝属产品取舍，待用户拍板，裁决前不实施；
> **不跟进**＝与既有 PD/限界冲突或无差距证据。

| 编号 | 维度 | 问题描述 | 对比依据 | 改进建议 | 预期效果 | 优先级 | 处置 |
|---|---|---|---|---|---|---|---|
| G1 | 操作体验·反馈 | 解锁（KDF 派生 + 密钥文件读取 + 封印解封）全过程零进度指示，弱机数十秒界面无变化；云同步、导入导出同为「结果型反馈」无过程反馈 | KeePassDX `ProgressTaskUpdater` 进度对话框贯穿加解密（其架构分析 §6.3/§8.2-10）；Kp2a 两级进度消息 + 可取消 + 前台同步进度通知（§6.2/§7.6） | ① 解锁页接入状态驱动的过程指示（「正在派生密钥…」「正在读取密钥文件…」阶段文案起步，Argon2 原生侧回调进度为进阶）；② 同步/导入导出复用同一过程反馈通道 | 大库/弱机用户不再「猜卡没卡」；与 P1-431 修复叠加后快速解锁等待可解释 | **高** | **已登记 `ISSUE-P3-437`** |
| G2 | 操作体验·反馈 | 进程被系统杀死后再次打开无「上次会话未正常关闭」说明，用户不理解为何要重输主密码 | Kp2a `AppKilledInfo`（其架构分析 §8.4） | 解锁页在检测到上次异常退出（非用户主动锁库）时展示一次性轻提示 | 降低「应用丢了我的会话」困惑，强化自动锁定的可感知安全性 | 低 | **已登记 `ISSUE-P3-438`** |
| G3 | 操作体验·搜索 | 无高级搜索选项（字段勾选 / 正则 / 大小写 / 排除过期），仅 CONTAINS/ALL_TERMS 两档 | Kp2a `SearchActivity.cs:93-115`（`交互体验的参考项目对照.md` §4C-35） | 设置内或搜索面板加字段范围勾选与「排除过期」开关；正则档须单独评估注入面与性能预算后再裁决 | 高级用户跨大库检索效率提升 | 中 | 待裁决 |
| G4 | 操作体验·解锁 | 解锁通道无 PIN/口令包装档位（仅生物识别 ↔ 全长主密码二选） | Monica `unlock_methods` 表 PIN/Password/Security Key 包装（其架构分析 §3.4）；本仓 PD-46 现口径为「封印凭据 + 生物识别」 | 若裁决引入：PIN 仅作生物识别不可用时的降级档，按「可撤销派生凭证而非密码本体」设计（Kp2a QuickUnlock 主密码驻留教训，其文档 §9.3） | 生物识别失效场景的可用性补充 | 中 | 待裁决（涉 PD-46 边界） |
| G5 | 操作体验·搜索 | 手选搜索结果后无「把搜索词写进条目」自愈询问 | Kp2a `AppTask.cs:494-534`「Remember search text?」（交互对照 §4C-34） | 评估「选择结果回灌」链路成本后裁决 | 匹配错误一次操作永久修正 | 中低 | 待裁决 |
| G6 | 操作体验·通知 | 前台通知无「复制用户名/密码/TOTP」快捷动作（本仓常驻通知已有「立即锁定」） | Kp2a `CopyToClipboardService.cs:111-156`（交互对照 §4C-36） | 若裁决引入：须先过安全复核——敏感字段经通知动作复制的暴露口径 | 高频复制少一次进 App | 中 | 待裁决（须安全复核） |
| G7 | UI 设计·主题 | 主题预设广度：5 套品牌调色盘 vs Monica Catppuccin 四型、KeePassDX 8+ 主题 | Monica 架构分析 §2.2；KeePassDX §6.7 | 低成本扩充 `ThemeMode` 调色盘枚举（纯数据追加，复用现有三族覆写管线） | 个性化选择面扩大 | 低 | 待裁决 |
| G8 | UI 设计·图标 | 无外部图标包扩展（KDBX 内置 68 + 自定义位图已覆盖核心面） | KeePassDX `icon-pack` 模块化图标包（其架构分析 §2.1/§9.4） | 单库产品定位下收益有限；如引入宜等插件生态（PD-56）裁决联动 | — | 低 | 待裁决 |
| G9 | UI 设计·一致性 | Toast 残留 3 处与全局 Snackbar 通道并存 | 本仓走查（`ClipboardSecurityManager.kt:282` 等 3 处） | 收敛进 `AppSnackbarChannel`，行为语义不变 | 反馈通道单一化 | 低 | 待裁决（可随手批处理） |
| G10 | 功能完整性 | 条目类型单一（凭据 + Passkey），无银行卡/身份/WiFi/SSH 等多态卡片条目 | Monica 多态 `SecureItem` / Entry 类型（其架构分析 §3.2） | 属产品定位取舍：若扩展建议从「自定义字段模板预设」轻量形态起步，勿直接引入多态 schema | 覆盖更多记忆场景 | 低 | 待裁决 |
| G11 | 操作体验·引导 | 无 onboarding / 内嵌短帮助（仅批量模式一次性引导） | KeePassDX `education/` + taptargetview；Kp2a `Kp2aShortHelpView`（交互对照 §4C-37） | 待裁决；若做，优先「设置项旁问号说明」轻形态而非全屏引导 | 首用理解成本下降 | 低 | 待裁决 |
| — | UI 设计·响应式 | 宽屏双栏 ListDetail | Kp2a/Monica 多源混排形态 | — | — | — | **不跟进**（PD-43） |
| — | UI 设计·动效 | 列表→详情共享元素过渡 | Monica `SharedTransitionCompat` | — | — | — | **不跟进**（PD-29；动效已按 PD-26/27/28/44 定标并被守卫测试锁定） |
| — | 功能完整性 | 硬件密钥 / 键盘填充 / 插件 / 老格式 / 零候选新建 passkey / AutoType 执行方 | KeePassDX、Kp2a 相应能力 | — | — | — | **不跟进**（PD-54/55/56/53/51/42） |
| — | 各维度 | 滑动手势（SwipeToDismiss）、抽屉导航、底部导航形态、无障碍专项 | 三家文档均未提及对应先例 | — | — | — | **不跟进**（无差距证据） |

---

## §6 未命中与未核实声明

1. **竞品视觉细节大量缺证**：Kp2a 的视觉设计/图标/主题/抽屉导航在其架构分析中通篇未涉及；三家的空态、
   骨架屏、Snackbar/Toast 用法、无障碍、排版均「文档未提及」。本报告在这些维度不作「本仓领先」的强断言，
   仅在有正向证据处（如 Kp2a 事件总线自评、KeePassDX 巨型类自评）作对照。
2. **未实机运行参考应用**：所有竞品交互均为据架构分析文档转述；「对比截图」以出处说明替代。
3. **本仓两项未核实**：① 只读会话提示是否细分原因文案（Kp2a 三型先例，§3.7）；② WebDAV 自签名证书场景的
   交互形态（涉安全口径，非纯 UX）。两项目前均未登记。
4. **已知限界交叉**：本报告不重复限界表已承接的面——相机实拍扫码可扫性（§35「摄像机问题，不解决」）、
   系统设置直达可用性（§27）、SAF 写回非原子文案如实呈现（§24）、弱 KDF 导入告警（§8，设置页 KDF 标注为
   已登记「更优」解除方向）。
5. **编号与登记纪律**：本报告新增登记条目从 `ISSUE-P3-437` 起（§406 已用至 436）；待裁决项**不进**
   `ACTIVE_ISSUES.md`，裁决后由用户指认再补登。

---

## §7 总体结论

本仓 UI/体验面相对三家参考应用**无结构性落后**：视觉（M3 Expressive + 动态取色 + OLED + 语义安全色）、
反馈（骨架屏/空态/Snackbar undo/冲突可视化页）、动效（全量定标 + 守卫测试）、无障碍（semantics 体系 +
对比度专项）均处于第一梯队或领先；搜索空态、域名感知、剪贴板反馈等历史差距已经 `ISSUE-P3-352` 闭环。

**真实残余差距收敛为一类**：**长操作的过程可感知性**（解锁 KDF 派生 / 同步 / 导入导出缺进度与阶段反馈），
已登记 `ISSUE-P3-437`（高优先）；其余为若干低优先增量项与产品取舍（§5 待裁决档），其中「进程被杀提示」
已登记 `ISSUE-P3-438`（低优先）。竞品确有而本仓裁决不做的能力（硬件密钥、键盘、插件、双栏、共享元素等）
全部有 PD 条目承接，本报告仅引用、不重复登记。
