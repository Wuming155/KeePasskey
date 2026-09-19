# §207 批次：Compose 长函数拆分两处批次（themeListSection / VaultDatabaseCard）

> 日期：2026-09-19　|　条目：`ISSUE-P3-188` 剩余清单第 1 项（渐进推进：待拆 12 → **10**）
> 口径：`python tools/doc/long_functions.py` 现跑（≥100 行函数 14 → **12**）

## 1. 本批做了什么（一律「只搬不改逻辑」、零行为变更）

### 1.1 `themeListSection` 118 → **约 92 行**（出表）

- 宿主文件 `ThemeSettingsListSections.kt` 270 行余量充足，段落**追加原文件**：
  `ThemeDensitySelector`（列表密度标题 + 说明 + 三态 FilterChip 行，`private`）——密度块之外的
  7 个 `DisplayPrefRow` 序列本身已是最小声明式单元，不再动（**不得**为省行数把声明式行循环化，
  那是改逻辑不是搬逻辑）。
- 原文件 270 → **286 行**（`@Composable` import 补入，不入第二档）。

### 1.2 `VaultDatabaseCard` 117 → **约 55 行**（出表）

- 宿主文件 211 行余量充足，段落**追加原文件**：`VaultDatabaseCardHeader`（库类型图标 + 名称与
  元数据 + 激活徽章 / 删除按钮互斥，`private`）。
- **路径行未随迁（守卫锚点）**：`UiMd3AlignmentWiringTest` 的中段省略守卫锚定
  `middleEllipsize(database.path, DATABASE_PATH_MAX_CHARS)` 在宿主文件现场——路径行留在
  [VaultDatabaseCard]，头部行搬走后守卫不触碰（免并集化）。原文件 211 → **228 行**。

## 2. 验证

- 全量 `test --rerun-tasks --max-workers=1`：`BUILD SUCCESSFUL`、114 任务全执行；
  聚合 `tests=2233 failures=0 errors=0 skipped=13`（同值，纯搬动零用例变动）。
- `long_functions.py` 现跑：14 → **12**；`lint`：**214** 持平。

## 3. 涉及文件

- **修改**：`ThemeSettingsListSections.kt`、`VaultDatabaseCard.kt`
- `ACTIVE_ISSUES.md`：剩余清单第 1 项计数 12 → 10、头部名单更新。
