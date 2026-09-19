# §205 批次：Compose 长函数拆分两处批次（PackageBlocklistManageDialog / GeneratorContent）

> 日期：2026-09-19　|　条目：`ISSUE-P3-188` 剩余清单第 1 项（渐进推进：待拆 16 → **14**）
> 口径：`python tools/doc/long_functions.py` 现跑（≥100 行函数 18 → **16**，扣 2 个 PD-11 豁免 NavGraph）

## 1. 本批做了什么（一律「只搬不改逻辑」、零行为变更）

### 1.1 `PackageBlocklistManageDialog` 131 → **约 55 行**（出表）

- **段落去向判定**：原文件 `AutofillBlocklistDialogs.kt` 375 行，距 400 余量仅 25 行——按 §192 / §196
  两案相反结论所立的「余量逐案判」口径，段落**另起新文件**（若追加会把原文件推入第二档，
  与第 2 项「渐进消化」方向相悖）。
- 新文件 `PackageBlocklistDialogSections.kt`（220 行）：
  - `PackageBlocklistBody`（text 主体：说明 / 空态或名单 / 选择器入口 / 错误提示 / 手工输入切换）——
    **不自持状态**，输入值与开关全部由对话框持有、经参数回传（§156 / §159 窄参数先例）；
  - `PackageBlocklistConfirmButton` / `PackageBlocklistDismissButton`（confirm / dismiss 槽位）；
  - `BlockedPackageRow` / `rememberAppOption`（名单行渲染，随 text 主体同迁，`private` → `internal`）。
- **回调语义保真（人工确认点）**：名单模式（非手工输入）下 confirm 槽位文案与行为均为「关闭」
  （`onConfirm = { if (manualEntry) submit(pendingPackage) else onDismiss }`）——拆分时曾误写为
  无条件 `submit`，已即时纠偏。
- 逐字核对 `check_verbatim_move.py`：MISSING 8 处全部为回调参数化（`onPickApp` / `onToggleManual` /
  `onPendingPackageChange` 上提）/ `private`→`internal` / 注释，NEW_ONLY 全为签名与调用点。

### 1.2 `GeneratorContent` 121 → **约 75 行**（出表）

- **段落去向判定**：`GeneratorScreen.kt` 295 行（< 400 余量充足），段落**追加进原文件**（同判）。
- 下沉三个段落（`private`，留在原文件）：
  - `GeneratorTopBar()`（无参，标题固定）；
  - `LazyListScope.generatorModeTabsItem(mode, onSetMode)`（模式切换 Tab item）；
  - `LazyListScope.generatorHistoryItems(history, onSelect, onCopy)`（历史标题 + 逐条历史行，
    `if (history.isEmpty()) return` 保持「空历史整段不渲染」语义）。
- 逐字核对：MISSING 14 处全部为注释迁至 KDoc / 局部变量重命名（`mode` → `candidate` 防参数遮蔽）/
  回调 lambda → 方法引用 / `isNotEmpty()` 包裹 → `isEmpty() early return`，绘制代码逐字未动。

## 2. 验证

- `:app:compileDebugKotlin` 通过；`long_functions.py` 现跑：18 → **16**（`PackageBlocklistManageDialog`
  与 `GeneratorContent` 双双出表），`count_line_tiers.py` 现跑 tier2 = **28 维持不变、名单零变动**
  （改写的 `AutofillBlocklistDialogs.kt` 375→243、`GeneratorScreen.kt` 295→329、新增
  `PackageBlocklistDialogSections.kt` 220 行均不入第二档；§205 拆分与第二档档位成员无交集）。
- 全量 `test --rerun-tasks --max-workers=1`：`BUILD SUCCESSFUL`、114 任务全执行；
  聚合 `tests=2233 failures=0 errors=0 skipped=13`（与 §204 同值，纯搬动零用例变动）。
- `lint`：**214** 持平；`check_md_links.py`：`BROKEN_MD_LINKS=0`。

## 3. 涉及文件

- **新增**：`PackageBlocklistDialogSections.kt`
- **修改**：`AutofillBlocklistDialogs.kt`、`GeneratorScreen.kt`
- `ACTIVE_ISSUES.md`：剩余清单第 1 项计数 16 → 14、头部名单更新。
