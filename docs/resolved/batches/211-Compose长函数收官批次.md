# §211 批次：Compose 长函数收官批次（KeePasskeyTheme / PrivilegedBrowserSettingsScreen / EntryDetailScreen）

> 日期：2026-09-19　|　条目：`ISSUE-P3-188` 剩余清单第 1 项（**闭环**：待拆 3 → **0**）
> 口径：`python tools/doc/long_functions.py` 现跑（≥100 行函数 6 → **4**，
> 表内余项全部为已裁决豁免：2 个 PD-11 NavGraph + 1 个限界 §179 `CreateVaultWizardDialog`）

## 1. 本批做了什么（一律「只搬不改逻辑」、零行为变更；三处共 3 个函数出表）

### 1.1 `KeePasskeyTheme` 104 → **约 60 行**（出表）

- `Theme.kt` 段落**追加原文件**（纯函数形态）：
  - `resolveAppColorScheme`（动态取色 / 品牌调色盘 dark·light 两分支的 `copy` 链；
    动态取色路径内部读 `LocalContext.current` ⇒ 必须标 `@Composable`，与原内联位置语义一致）；
  - `resolveSecurityColors`（语义安全色明暗两套）。
- 过程缺陷（`ColorScheme` 未显式 import 解析歧义）当场修复。原文件 193 → **约 260 行**（不入档）。

### 1.2 `PrivilegedBrowserSettingsScreen` 100 → **约 40 行**（出表）

- **§199 守卫锚点零触碰**：`SettingsSubscreenScaffoldWiringTest` 三条判据锚定的
  `topBarContainerColor = background` / `topBarTitleContentColor = null` / `ISSUE-P3-195` 注释
  全部留在宿主文件的 Scaffold 装配处；只下沉 LazyColumn **内容段**：
  - `PrivilegedBrowserWarningCard`（风险提示卡）；
  - `LazyListScope.privilegedBrowserListItems`（空态 / 逐浏览器行）。
- 宿主文件 150 → **约 215 行**（不入档）；守卫随全量套件通过。

### 1.3 `EntryDetailScreen` 121 → **约 80 行**（出表）

- **§169 裁定全文遵守**：SAF 明文导出三态（`pendingExportAttachment` / `pendingPlaintextExportUri` /
  `showPlaintextExportConfirm`）、`exportLauncher` 与确认对话框渲染段**全部留在 Route 现场**
  （「同一状态不得有两处真相」与「出口顺序逐字保持」），只下沉两类**无状态编排段**：
  - `EntryDetailLifecycleEffects`（进入绑定 entryId / 销毁擦除 M1 / 删除回退 P3-48 / 偏好快照刷新 P3-17）；
  - `EntryDetailSnackbarEffect`（一次性用户消息 → Snackbar 消费）；
  - `EntryDetailContentHost`（Content 的纯参数装配段）——**过程纠偏一处真险情**：
    首版把 `onExportAttachment` 的**写路径**（`pendingExportAttachment = att`）搬进了 Host，
    对参数赋值不会回写 Route 状态 ⇒ 立即改为 Route 传导出回调、Host 只透传（写路径留在 §169 现场）。
- 宿主文件 383 行（+段落组件）。

## 2. 验证

- 全量 `test --rerun-tasks --max-workers=1`：`BUILD SUCCESSFUL`、114 任务全执行；
  聚合 `tests=2233 failures=0 errors=0 skipped=13`（同值，纯搬动零用例变动）。
- `long_functions.py` 现跑：6 → **4**（第 1 项以「无未登记的超限项」达标——表内余项全部为
  已登记豁免）。
- 设备回归：`Redmi 4X` 真机 `:app:connectedDebugAndroidTest` **64 例全绿**（详情页结构改动过
  设备侧回归）。
- `lint`：**214** 持平。

## 3. 涉及文件

- **修改**：`Theme.kt`、`PrivilegedBrowserSettingsScreen.kt`、`EntryDetailScreen.kt`
- `ACTIVE_ISSUES.md`：剩余清单第 1 项**闭环移出**（第 1 项标头的「待拆」与头部名单说明归档至此）。
