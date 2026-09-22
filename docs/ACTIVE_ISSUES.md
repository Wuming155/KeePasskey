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

## P2 中危缺陷与协议/测试缺口（1 项）

### `ISSUE-P2-254`：通行密钥交互链路三项端到端验证缺口（github.com 手工冒烟未执行 / PRF 未对真实 RP 对拍 / CM 选择器 UI 未覆盖）

- **核实时间点**：2026-09-22（文档审核批次 §260）。
- **核实方式**：直读 `records/通行密钥互操作对拍记录_2026-09-19.md` §6（标题自陈「本次未执行」）、`architecture/实现约定与验证现状.md` §4.3 边界②③（「2026-09-19 尚未执行」「PRF 仍未与真实 RP 对拍」）、`docs/resolved/batches/220-通行密钥互操作对拍与Ed25519-PKCS8形态整改批次.md` 结案「如实声明」段——三处一致，且 `RESOLVED_LOG.md` 自 §220 起无任何批次执行过该清单；`ACTIVE_ISSUES` 检索无对应条目 ⇒ 缺口自 2026-09-19 起**无跟踪**。
- **背景与影响**：`ISSUE-P2-210` 以**文件级 / 离库**证据结案并如实留下三项未覆盖面，但「留下边界声明」≠「有人跟踪执行」——checklist 沉在 records 里无限期搁置。缺口不补，「通行密钥互操作」的对外表述只能停在限界 §4.3 / 实现约定 §4.3 的收窄口径。
- **验收标准**：
  - AC① 按 `records/通行密钥互操作对拍记录_2026-09-19.md` §6 清单完成 github.com 端到端真机贯通（8 步逐项「通过 / 失败 + 截图或日志」留痕，回写该记录或新增批次正文）；
  - AC② 完成至少一次 **PRF 与真实 RP** 的对拍；若确客观无可用 RP，须以限界 / 产品裁决登记「不可达」边界并经确认——**不得静默留白**；
  - AC③ 系统 CM 选择器 UI 全链路 + 通知渲染核对留痕（可与 AC① 同轮）；
  - AC④ 三项完成后同步收口 `architecture/实现约定与验证现状.md` §4.3 与限界 §4.3 存根的对应边界句（原句保留作留痕）并归档本条。任一项若裁决「不做」，必须以限界 / 产品裁决登记承接。
- **依据**：`docs/records/通行密钥互操作对拍记录_2026-09-19.md` §6；`docs/architecture/实现约定与验证现状.md` §4.3；`docs/resolved/batches/220-通行密钥互操作对拍与Ed25519-PKCS8形态整改批次.md`（结案如实声明）；同类「逐条收口」先例 `docs/resolved/batches/242-凭据链路未验证项逐条收口批次.md`。

---

> **本区最近一次归零记录**（2026-09-22 §260 补登 `ISSUE-P2-254` 前）：§254 闭环 `ISSUE-P2-246`——敏感对话框遮罩改由调用点
> `SecureFlagPolicy.SecureOn` 强制（7 处调用点），真机对照实证；§259 闭环 `ISSUE-P2-253`——
> 关闭生物识别开关只落偏好、不删封印凭据与断言登记，现关闭即撤销全部生物识别数据（关闭 = 删除）。
> 证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/259-关闭生物识别开关撤销封印数据批次.md`](resolved/batches/259-关闭生物识别开关撤销封印数据批次.md)。

---

## P3 低危问题、特性接线与体验优化（3 项）

### `ISSUE-P3-263`：动态取色开启后「主题调色盘」5 项仍可点选、仍显示选中态，但全局配色零变化

- **核实时间点**：2026-09-22（外观与主题页交互走查）。
- **核实方式**：直读 HEAD 四处——① `app/src/main/java/com/keepasskey/app/ui/theme/Theme.kt:132-136`：`resolveAppColorScheme` 的 `when` **首分支** `useDynamicColor ->` 直接返回系统壁纸 ColorScheme，**`themePalette` 入参在该分支内零读取**（`useDynamicColor` 于 `:77` 由 `dynamicColorEnabled && SDK >= S` 推出）；② `app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/ThemeSettingsSections.kt:170-200`：`themePaletteSection` **通篇未读 `uiState.dynamicColorEnabled`**，5 项无条件渲染并绑定 `onClick`；③ `.../subscreens/ThemeSettingsComponents.kt:178-200`：`ThemePaletteItemCard` **无 `enabled` 参数**，`clickable` 恒可点，选中态（勾选角标 + 加粗 + `primaryContainer` 高亮，`:184-185, 240-246`）只由 `uiState.themePalette == palette` 决定；④ `app/src/main/java/com/keepasskey/app/data/repository/RealSettingsRepository.kt:130-140`：`setThemePalette` 与 `setDynamicColorEnabled` 各写各的 key（`theme_palette` / `dynamic_color_enabled`），**互不感知**。另以全仓 `grep`「动态取色 / dynamicColor / Material You」于 `docs/` 复核：仅命中 `references/KeePassDX-架构分析.md`、`resolved/batches/211`（纯函数下沉）、`resolved/batches/262`（页面渲染走查）三处，`architecture/产品裁决登记.md` 与 `ACTIVE_ISSUES.md` **均无对应条目** ⇒ 该交互缺陷自 §211 起无跟踪、口径亦未裁决。
- **背景与影响**：「动态取色压过品牌调色盘」**本身是有意设计**（`Theme.kt:75-76` 注释与 `strings.xml:757-758` 文案均自陈「开启后覆盖品牌调色盘」），**不是**本条的指控对象。**真正的缺陷在设置页缺少互斥与生效反馈**，由此产生三重错位：① 卡片照常可点——用户以为切换成功；② 卡片照常呈现「已选中」（勾选角标 + 高亮边框）——屏幕上**两根互相矛盾的"选中"同时成立**（动态取色开关 + 某套调色盘）；③ `theme_palette` 偏好照常落盘——状态、存储、UI 三者一致地"以为"生效了，唯独渲染层不认。⇒ 用户感知为「5 种主题全都失效、不能用了」（2026-09-22 用户真机反馈）。
  另有两处加深错觉的次生现象：`Theme.kt:104-108` 的 `CompositionLocalProvider` **仍把原始 `themePalette` 原样下发** `LocalThemePalette`，而卡片里的四色圆点预览**直接读枚举**而非读 `MaterialTheme.colorScheme`（`ThemeSettingsComponents.kt:186-189, 214-217`）⇒ 动态取色下卡片色块依旧鲜艳、界面纹丝不动；`:193` 的 `isDarkTheme = uiState.themeMode == AppThemeMode.DARK` 使「跟随系统 + 系统深色」时卡片预览按**浅色**渲染，预览色与生效色本就不同源。
- **正确的方式（整改方向）**：把「配色来源」收敛为**单维度、单一判据、单一选中态**——
  1. **判据单点化**：`useDynamicColor` 的推导下沉为**唯一纯函数**（建议置于 `app/src/main/java/com/keepasskey/app/ui/theme/ThemeMode.kt`，形如 `resolveColorSource(dynamicColorEnabled, sdkInt): ColorSource`），**设置页与 `KeePasskeyTheme` 共用同一判据**；禁止 UI 与 Theme 层各写一份（现状即「UI 不判、Theme 独判」的双写漂移）。`sdkInt < 31` 时须**显式回落**品牌调色盘且可单测（`dynamic_color_enabled` 可能经备份恢复为 `true`，而设备不支持）。
  2. **消除「选中态 ≠ 生效态」**：动态取色生效期间，调色盘 5 项**不得再呈现"已选中"**（勾选角标 / 加粗 / `primaryContainer` 高亮一并撤销），`ThemePaletteItemCard` 新增 `enabled` 参数；`isSelected` 的语义收紧为「**当前实际生效**」而非「偏好值等于该项」。
  3. **给出原因与出路**：置灰必须附行内说明（如「动态取色已开启，主题调色盘暂不生效」）并提供**一步动作**（关闭动态取色 / 改用品牌调色盘），不得让用户靠试错猜。
  4. **存储口径三选一（须先裁决）**：
     - **候选 A（推荐）互斥单选**：调色盘与动态取色归入同一「配色来源」维度；点选任一调色盘即**幂等地**关闭 `dynamic_color_enabled`。理由：与「同一时刻只有一个配色生效」的用户心智一致，且 `theme_palette` 保持「永远是当前生效值」的干净语义。
     - **候选 B 主从显式化**：维持两个独立偏好，但动态取色开启时调色盘整节**置灰只读**（偏好保留、切回即恢复），由 UI 明示主从关系；改动面最小。
     - **候选 C 混合叠加**：让 `Theme.kt:133` 分支**不再丢** `themePalette`，改为「壁纸 ColorScheme 打底 + 品牌三族语义色覆写」。⚠️ 选此项须先证「壁纸衍生容器色 × 品牌主色」的对比度不劣化，并同步改写 `theme_dynamic_sub` 的「覆盖」表述。
  5. **文案同步**：按裁决口径改写 `theme_palette_pick_desc` / `theme_dynamic_sub`（zh + en 各一），把「覆盖」这类模糊动词换成**可验证描述**。
- **验收标准**：
  - AC⓪ **先决**：由用户就候选 A / B / C 作出裁决；**未裁决不得动代码**。裁决若为「取色优先、调色盘让位」，须登记 `architecture/产品裁决登记.md`（续 `PD-30`）并写明重开条件；裁决若判为缺陷，则直接进入 AC①~⑤。
  - AC① 判据单点化落地：新增纯函数并令设置页与 `KeePasskeyTheme` 共用，宿主用例锁定真值表——**必须含** `sdkInt < 31` 且偏好为 `true` 时回落品牌调色盘的分支。
  - AC② 动态取色生效期间，调色盘分区**不存在任何"已选中"呈现**，且 5 项均不可点（`enabled = false`，`ThemePaletteItemCard` 增参）。
  - AC③ 置灰分区内附原因说明 + 一步切换动作，两种状态真机（Redmi 4X / API 37）逐字留痕。
  - AC④ 按裁决落实存储口径：候选 A 下须证明「点选调色盘 ⇒ `dynamic_color_enabled` 关闭」幂等且原子；候选 B / C 下须证明读出口**不存在「两个偏好同时看似生效」**的状态。
  - AC⑤ 文案（zh + en）按口径改写，并按同族判据复核「**界面声明 == 实际行为**」（判定落纯函数、呈现与真值同源）。
  - AC⑥ 任一项若裁决「不做」，须经 `产品裁决登记.md` 或 `architecture/已知工程限界.md` 承接并写明重开条件，**不得静默留白**。
- **依据**：`app/src/main/java/com/keepasskey/app/ui/theme/Theme.kt:75-77, 104-108, 126-161`；`app/src/main/java/com/keepasskey/app/ui/theme/ThemeMode.kt:20-164`；`app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/ThemeSettingsSections.kt:108-119, 170-200`；`app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/ThemeSettingsComponents.kt:178-251`；`app/src/main/java/com/keepasskey/app/data/repository/RealSettingsRepository.kt:130-140, 230-233`；`app/src/main/res/values/strings.xml:757-760`。同类「界面声明与真实行为一致」先例：[`resolved/batches/246-移除密码库确认文案与真实行为一致批次.md`](resolved/batches/246-移除密码库确认文案与真实行为一致批次.md)。

### `ISSUE-P3-264`：「不再提示保存的应用」弹窗的「关闭」被焊进**状态相关**的 confirm 槽（手工输入模式下右下角变为可禁用的「新增」、真正的关闭被挤到左侧），且该弹窗零接线守卫

- **核实时间点**：2026-09-22（用户报告「设置 → 自动填充 → 不再提示保存的应用：点开后右下角『关闭』点击无响应/关不掉」后的静态走查）。**用户所报「接线缺失」未获证实**——实证到的是下述结构缺陷与两态观感；若真机在**非手工模式**复现「点了不关」，按 AC⑤ 升 P2 处理。
- **核实方式**：直读 HEAD 六处并逐跳跟线——① `app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/AutofillSettingsComponents.kt:272-282`（该行接线 `onClick = onOpenSaveBlacklist`）；② `.../AutofillSettingsScreen.kt:79`（`showSaveBlacklistDialog` 本屏自持）与 `:186-198`（`onDismiss = { showSaveBlacklistDialog = false }`；与填充黑名单各占一个 `if` 组，`remember` 槽位互不共用）；③ `.../AutofillBlocklistDialogs.kt:126-161`（`onDismissRequest = onDismiss`；confirm 槽给 `PackageBlocklistConfirmButton`、dismiss 槽给 `PackageBlocklistDismissButton`；`:155` 即 `onConfirm = { if (manualEntry) submit(pendingPackage) else onDismiss }`）；④ `.../PackageBlocklistDialogSections.kt:128-146`（非手工分支渲染「关闭」并**绑 `onConfirm`**；手工分支渲染「新增」且 `enabled = pendingPackage.isNotBlank()`）与 `:148-158`（`PackageBlocklistDismissButton` **仅**手工分支才渲染「关闭」，否则整段不产子项）。⇒ **非手工模式下全弹窗只有一颗按钮**（「关闭」，落在 confirm 槽），且逐跳接线完整：**未发现**监听缺失、空 lambda、`onDismiss` 错挂、`enabled` 误用（该参数不作用于非手工分支）或状态错挂。
  布局面以项目实际所用 M3 版本源码核实：`~/.gradle/caches/modules-2/files-2.1/androidx.compose.material3/material3-android/1.5.0-alpha27/…-sources.jar!commonMain/androidx/compose/material3/AlertDialog.kt` 的 `:259-273`（槽位调用序 `confirmButton()` → `dismissButton?.invoke()`）、`:382-406`（`AlertDialogFlowRow` 以 `LayoutDirection.flip()` 承载 FlowRow ⇒ confirm 落在最右）、`:293-375`（`text` 槽为 `Modifier.weight(1f, fill = false)`，按钮属非 weight 子项**先测量**）⇒ 正文再长也不会把按钮顶出可视区、亦不被文本覆盖——**排除布局遮挡**。
  接线形态清点：`grep -rn "R\.string\.btn_close" app/src/main --include=*.kt` 共 15 处，14 处为真实「关闭」按钮（另 1 处在 `ui/model/UiMessage.kt:33` 的预览里，非按钮）；14 处中 **13 处直接绑 `onDismiss`**（12 处同形 `TextButton(onClick = onDismiss)` ＋ `EntryDetailPreviewDiffComponents` 的 `IconButton(onClick = onDismiss)` 图标关闭），**唯一例外**即本弹窗 confirm 槽经 `onConfirm` 间接转发 ⇒ **非通用组件问题**。
  测试守卫：`grep -rln "PackageBlocklistManageDialog\|autofill_save_blacklist" app/src/test app/src/androidTest` **零命中**（唯一命中落在 `app/src/screenshotTest/kotlin/…/GeneratedPreviewWrappers.kt:23`，而该目录由 `.gitignore:53` 排除、未入 git，属**生成物**且只调用预览函数、不构成行为断言）⇒ **零接线守卫**，与 §196 / §198 两次记下的 `VaultListDialogHost` / `PackageBlocklistManageDialog`「零测试引用」同源。
  历史同形故障：`git --no-pager show 52bf966` 复核并见 [`resolved/batches/205-Compose长函数拆分两处批次.md`](resolved/batches/205-Compose长函数拆分两处批次.md) §1.1——该 confirm 槽在 §205 拆分时**曾误写为无条件 `submit(pendingPackage)`** ⇒ 点「关闭」走 `onAdd("")` 返 `false` ⇒ `showAddError = true` ⇒ **弹窗不关、还多一条「包名无效」**，正是「右下角关闭点了没反应」的完整原型；同提交内已纠偏为现形态。
- **背景与影响**：本条的确认缺陷不是「接线断了」，而是**「关闭语义与一个单向状态耦合」＋「零守卫」**，落到用户侧有两种可复现观感：
  1. **非手工模式（默认态）**：全弹窗只有一颗「关闭」且接线正确 ⇒ 在此态若真机复现「点了不关」，只可能是构建落在 §205 纠偏之前，或存在本条目范围外的干扰项（须真机取证，见 AC⑤）。
  2. **手工输入模式**：`manualEntry` 是**单向闩锁**（`AutofillBlocklistDialogs.kt:143-146` 的 `onToggleManual` 只置 `true`，无回退到名单模式的路径），一旦点过「手工输入」，右下角即由「关闭」**变为**「新增」；空输入时 `enabled = pendingPackage.isNotBlank()` 为假 ⇒ **灰掉且点之不动**，真正的「关闭」被挤到其左侧。用户按「右下角那颗按钮」的既有印象点击，观感即「右下角按钮无响应 / 关不掉」（`submit` 成功还会清空 `pendingPackage` ⇒「新增」随即再次变灰，极易踩中）。
  影响面：设置页 → 自动填充 →「智能识别、凭证保存与兼容策略」→「不再提示保存的应用」（`ISSUE-P3-43 ③` 的保存侧黑名单）。**不阻断使用**（返回键 / 点弹窗外部 / 左侧「关闭」三条路仍可关），无数据风险 ⇒ **定级 P3**；**升档条件**：若在**非手工模式**下真机复现「点关闭不关」，即为功能性阻断，须升 **P2** 并先定位真因。
- **正确的方式（整改方向）**：
  1. **关闭语义去状态化**：把「关闭」**无条件**放回 `dismissButton` 槽（与全仓 13 处同形，`PackageBlocklistDismissButton` 删掉 `manualEntry` 分支），confirm 槽只在 `manualEntry == true` 时承载「新增」（`enabled = pendingPackage.isNotBlank()` 保持不变）⇒ 顺带消灭 `onConfirm` 里 `if (manualEntry) … else …` 这一**全仓唯一**的状态相关关闭语义。
  2. **补回退路径**：手工输入模式提供回到名单模式的方式（或新增成功后自动回落），使界面不存在「右下角长期停在禁用按钮上」的态。
  3. **补接线守卫**：比照 `SettingsSubscreenScaffoldWiringTest` / `OneTapInteractionWiringTest` 的静态源码比对形态新增用例——断言该弹窗 `dismissButton` 槽存在 `TextButton(onClick = onDismiss)`，且 confirm 槽**不得再出现** `btn_close`；须含**防空扫**断言（§77 口径：扫不到目标即失败）。
- **验收标准**：
  - AC① 非手工模式下「关闭」由 `dismissButton` 槽承载，且其**存在与可点性不依赖任何状态**（`manualEntry` 取何值都不影响）；
  - AC② 手工输入模式补回退路径，界面**不存在**「右下角恒为禁用按钮」的态；切换/回落后按钮文案与动作一致（界面声明 == 实际行为）；
  - AC③ 新增接线守卫用例（含防空扫），并以下述方式**证明其区分力**：把 §205 的历史坏形态（confirm 槽无条件 `submit`）代入谓词须报红（`git show <回归态>` 代入法，§199 先例），不得只以「现态通过」自证；
  - AC④ 若裁决「保留现形态」（不重构），须在 [`architecture/产品裁决登记.md`](architecture/产品裁决登记.md) 登记取舍与重开条件，**不得静默留白**；
  - AC⑤ 若真机在**非手工模式**下复现「点关闭不关」，本条目升 P2，并先取真机留痕（设备 / 构建号 / 复现步骤 / 录屏或日志）再定因。
- **依据**：`app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/AutofillSettingsComponents.kt:272-282`；`.../AutofillSettingsScreen.kt:79, 186-198`；`.../AutofillBlocklistDialogs.kt:143-146, 150-161`；`.../PackageBlocklistDialogSections.kt:128-158`；`app/src/main/res/values/strings.xml:947-952`（`autofill_save_blacklist_*` 文案与 `ISSUE-P3-43 ③` 出处）；[`resolved/batches/205-Compose长函数拆分两处批次.md`](resolved/batches/205-Compose长函数拆分两处批次.md) §1.1（同形故障与纠偏留痕）；同族「零测试引用」先例 [`resolved/batches/196-库列表对话框宿主分段批次.md`](resolved/batches/196-库列表对话框宿主分段批次.md)、[`resolved/batches/198-详情页确认对话框下沉批次.md`](resolved/batches/198-详情页确认对话框下沉批次.md)。

### `ISSUE-P3-265`：CI 工作流硬编码固定临时签名口令 `keepasskey-ci-ephemeral`

- **核实时间点**：2026-09-22（Git 跟踪内容与历史敏感信息审计）。
- **核实方式**：① 全树检索 `keepasskey-ci-ephemeral` / `CI_KEYSTORE_PASSWORD` 命中**仅** `.github/workflows/build.yml`（约 `:60` 及同 job 的 `keytool` / `GITHUB_ENV` 导出段）；② 直读 `build.yml` 该段——`env.CI_KEYSTORE_PASSWORD: "keepasskey-ci-ephemeral"` 为 workflow 级固定字符串，同 job 内 `keytool -genkeypair … -storepass "${CI_KEYSTORE_PASSWORD}" -keypass "${CI_KEYSTORE_PASSWORD}"` 生成 `${RUNNER_TEMP}/ci-release.jks`，再写入 `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` 供 `assembleRelease` 读取；③ 直读 `app/build.gradle.kts` 签名口令闸门——启用签名时校验长度 ≥ 16、不命中 `FORBIDDEN_RELEASE_PASSWORDS`（含 `keepasskey123`）、不包含 `__REPLACE_WITH` 占位符标记；④ `git log -S 'keepasskey-ci-ephemeral'` 与产品/测试源码交叉检索：无其他消费方、无测试断言依赖该字面量。
- **背景与影响**：该口令仅用于 **CI runner 临时生成的一次性签名密钥**（工作流注释已写明「非机密、不可用于发布」），不保护发布身份，**不构成生产凭据泄露**。但值被写死在公开仓库的 workflow 中，任何克隆者皆可读；一旦有人误将同口令用于真实密钥库，或后续把该 job 改成消费真实 secret 却沿用固定值，会静默放大风险。属**供应链 / 密钥卫生**缺口，非功能缺陷。定级 **P3**。
- **正确的方式（整改方向）**：将口令改为 **job 运行时随机生成**（如 `openssl rand -base64 24`，约 32 字符，可过构建闸门长度 ≥ 16），在 `keytool` 之前生成**一次**并同时用于 `storepass`/`keypass` 与 `GITHUB_ENV` 导出——**不得**分两次 `openssl rand`（否则 keystore 口令与 Gradle 读到的口令不一致，签名失败）。固定字面量与 `CI_KEYSTORE_PASSWORD` 顶层 env 一并移除或改为占位说明。随机值须避开 `FORBIDDEN_RELEASE_PASSWORDS` 与 `__REPLACE_WITH`（`openssl rand -base64` 实际不会命中，但闸门本身会兜底）。
- **验收标准**：
  - AC① `build.yml` 中**不再出现**任何固定口令字面量；`keytool` 与 `KEYSTORE_PASSWORD`/`KEY_PASSWORD` 使用**同一次**运行时随机值；
  - AC② 随机口令长度 ≥ 16 且能通过 `app/build.gradle.kts` 发布签名口令闸门（以真实 `assembleRelease` 配置阶段不 `error` 为证）；
  - AC③ 全仓检索 `keepasskey-ci-ephemeral` 零命中（历史提交除外）；工作流注释同步改为「口令运行时生成、非机密、不可用于发布」口径；
  - AC④ 若裁决「保留固定值」（例如为可复现构建），须在 [`architecture/产品裁决登记.md`](architecture/产品裁决登记.md) 或 [`architecture/已知工程限界.md`](architecture/已知工程限界.md) 登记取舍与重开条件，**不得静默留白**。
- **依据**：`.github/workflows/build.yml`（`CI_KEYSTORE_PASSWORD` env 与同 job `keytool` / `GITHUB_ENV` 段）；`app/build.gradle.kts`（`minReleasePasswordLength = 16` / `forbiddenReleasePasswords` / `releasePasswordPlaceholderMarker` 闸门）；`keystore.properties.example`（口令卫生与 re-key 说明）。相关历史：`ISSUE-P2-55`（发布签名弱口令 `keepasskey123`，已 re-key 闭环，见 [`resolved/batches/49-存量安全整改批次-密钥文件纯字节解析.md`](resolved/batches/49-存量安全整改批次-密钥文件纯字节解析.md)）。

---

> **本区最近一次归零记录**（2026-09-22 §263 补登 `ISSUE-P3-263`、其后补登 `ISSUE-P3-264` 前）：§262 闭环 `ISSUE-P3-261`，条目正文原样收录批次 §1——全局转场动效
> 由「自研 tween 一套 + 主题 Expressive 一套」收敛为**双栏定标**——空间段（位移 / 缩放）走
> `MaterialTheme.motionScheme` 的 spring（与底栏指示器同族）、效果段（透明度）走官方 shared axis 的
> **顺序淡化时间轴**（出向 90ms / 入向 210ms 延迟 90ms，35% 阈值以两条不变式锁定）；
> 同级 Tab 补齐真正顺序的 Fade Through 并把 `Screen.Settings` 纳入同族、层级下钻的前进与返回
> 改为逐项镜像（视差比例落具名 token）、预测性返回启用两个专属槽位并按全屏表面规格补齐
> 缩放与插值器 `(.1,.1,0,1)`、内容层 6 处改读 MotionScheme 且列表项加 `Modifier.animateItem`。
> 四条「有意偏离」逐条登记 `PD-26` ~ `PD-29`（含**不做**共享元素过渡的评估结论）。
> 同批闭环 `ISSUE-P2-260`——底栏顶层 Tab 切换的 `popUpTo` 目标改取**栈上当前路由**，
> 使 `saveState` / `restoreState` 真正生效、返回栈不再无界增长。
>
> **前次归零记录**（2026-09-22 §261 四条同批闭环，条目正文原样收录批次 §1）：`ISSUE-P3-255`——`ISSUE-P3-221` 自陈的三项
> 真机核验（拒绝页按钮状态流 / 授权后回浏览器放行 / `STATUS_FAILED` 文案）已执行：①② 真机核验通过（Firefox 149 +
> passkeys.io，逐字证据见批次），③ 判定稳态真机不可达（资格守卫与签名读取互斥的 TOCTOU 论证）并按限界 §22 以
> 「发版前人工核对承担」显式承接；`ISSUE-P3-256`——`PD-05` 待裁决归位并按用户裁定**候选 C** 落地（唯一强匹配 + 已绑定
> 改链确认页，路由判据落纯函数 `AutofillUnlockRouter`，未绑定 fail-closed 回选择器，解锁页不自建 Dataset）；`ISSUE-P3-257`——
> `SettingsViewModel` 4 处薄编排全部下沉控制器、ViewModel 收单语句委托，`PD-23` 待消化清单清空；`ISSUE-P3-258`——
> 池内擦除准入①穷举 9 类持有者全部满足，Step 4 按身份集合判定实施（新原语 `clearBinaryPool` + 四处收口点 + 反校 4 组），
> 限界 §1.6 标记已解除、契约 Step 0~4 完结。证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §261 与
> [`resolved/batches/261-P3低危四项闭环批次.md`](resolved/batches/261-P3低危四项闭环批次.md)。
>
> **前次归零记录**（2026-09-22 §260 补登四条前）：§258 同批闭环 `ISSUE-P3-251` 与 `ISSUE-P3-252`——前者＝限界表 §13 缺
> 「事实」「边界」两字段、违反该表自己的「事实 → 边界 → 依据 → 解除条件」登记纪律，整改＝事实段自汇录
> **逐字搬移**回补 ＋ 边界段按条目两候选**裁决新写并留痕**（批次正文即留痕）；后者＝限界表等文件正文
> `[附二](#附二更正留痕按日期) §X` 形编号留痕的锚点在自身文件内**无落点**（正典实居汇录附二 21 条，
> 而 `check_md_links.py` 对含锚链接不匹配、判不出），整改＝按条目**处置方向 ①**「附二正典归汇录」——
> 汇录附二标题修 `### ##` 畸形、收纳判据与 `ISSUE-P2-243` / `P2-244` 两留痕块**逐字迁入**、
> **5 文件 14 处**引用（条目所称 3 文件 10 处 ＋ 现场扩展 `实现约定与验证现状.md` 3 处 / `宿主开发环境现象.md` 1 处）
> 改指汇录锚点、限界表附二降为「一行指针 + 3 个块去向」。证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/258-限界表13字段补齐与附二正典归汇录批次.md`](resolved/batches/258-限界表13字段补齐与附二正典归汇录批次.md)。
