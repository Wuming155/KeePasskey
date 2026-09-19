# §210 批次：Compose 长函数拆分两处批次（EntryEditCustomFieldsSection / SafeAttachmentPreviewDialog）

> 日期：2026-09-19　|　条目：`ISSUE-P3-188` 剩余清单第 1 项（渐进推进：待拆 6 → **3**）
> 口径：`python tools/doc/long_functions.py` 现跑（≥100 行函数 8 → **6**，
> 其中 2 个 PD-11 豁免 NavGraph + 1 个限界 §179 豁免 `CreateVaultWizardDialog`）

## 1. 本批做了什么（一律「只搬不改逻辑」、零行为变更）

### 1.1 `EntryEditCustomFieldsSection` 108 → **约 35 行**（出表）

- 宿主文件 `EntryEditListSections.kt` 279 行余量充足，段落**追加原文件**：
  `CustomFieldEditCard`（单条自定义字段的编辑卡片：key 行 + 删除按钮 / 值输入
  （受保护走 `SecurePasswordField`）/「受保护」开关行）。
- TASK-10 擦除通道注释随迁：受保护字段明文输入的 CharArray 直达 ViewModel 私有链路；
  `protectedVisible` 状态按 field.id 记忆在本卡片（与搬迁前同源）。
- 原文件 279 → **303 行**（不入第二档）。

### 1.2 `SafeAttachmentPreviewDialog` 107 → **约 78 行**（出表）

- 宿主文件 `EntryDetailPreviewDiffComponents.kt` 358 行贴 400 边，段落**追加原文件**（拆后 374 行，
  仍在 400 内）：
  - `SafeAttachmentIsolationNotice`（安全隔离提示横幅）；
  - `SafeAttachmentPreviewCanvas`（预览区画布：锁形图标 + MIME + 大小，真实内容不落预览）。
- **清单锁零风险设计**：本文件在 `SecureDialogFlagPolicyTest` 的 `SecureDialog {` 调用点计数清单内
  （expectedCalls=2）——只搬 `SecureDialog {` **包裹内部**的渲染段，调用点本体与计数不变
  （守卫随全量套件通过）。过程性缺陷（脚本重复执行导致函数重复定义）当场发现并修复。

## 2. 验证

- 全量 `test --rerun-tasks --max-workers=1`：`BUILD SUCCESSFUL`、114 任务全执行；
  聚合 `tests=2233 failures=0 errors=0 skipped=13`（同值，纯搬动零用例变动）。
- `long_functions.py` 现跑：8 → **6**；`lint`：**214** 持平。

## 3. 涉及文件

- **修改**：`EntryEditListSections.kt`、`EntryDetailPreviewDiffComponents.kt`
- `ACTIVE_ISSUES.md`：剩余清单第 1 项计数 6 → 3、头部名单更新。
