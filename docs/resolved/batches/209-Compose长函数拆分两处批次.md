# §209 批次：Compose 长函数拆分两处批次（S3ConfigFields / ChildVaultEntryRowView）

> 日期：2026-09-19　|　条目：`ISSUE-P3-188` 剩余清单第 1 项（渐进推进：待拆 8 → **6**）
> 口径：`python tools/doc/long_functions.py` 现跑（≥100 行函数 10 → **8**）

## 1. 本批做了什么（一律「只搬不改逻辑」、零行为变更）

### 1.1 `S3ConfigFields` 113 → **约 71 行**（出表）

- 宿主文件 `CloudSyncConfigFields.kt` 237 行余量充足，段落**追加原文件**：
  - `S3CredentialFields`（Access Key ID + Secret Access Key 双 `SecurePasswordField`，
    ISSUE-P2-01 / Wave 15 注释随迁——显示用 String 仅存活于组件内部，CharArray 直达本地状态）；
  - `S3PathStyleSwitch`（path-style 寻址开关行）。
- 原文件 237 → **约 295 行**（不入第二档）。

### 1.2 `ChildVaultEntryRowView` 113 → **约 76 行**（出表）

- 宿主文件 `VaultChildDatabaseSections.kt` 265 行余量充足，段落**追加原文件**：
  `ChildVaultSecondaryLine`（用户名 / 分隔点 / URL 的条件渲染行）——「是否存在可展示次级行」
  的判定仍由 `hasSecondaryLine` 在调用方先行承担，本段不渲染空行（语义零变化）。
- 原文件 265 → **约 305 行**（不入第二档）。

## 2. 验证

- 全量 `test --rerun-tasks --max-workers=1`：`BUILD SUCCESSFUL`、114 任务全执行；
  聚合 `tests=2233 failures=0 errors=0 skipped=13`（同值，纯搬动零用例变动）。
- `long_functions.py` 现跑：10 → **8**；`lint`：**214** 持平。

## 3. 涉及文件

- **修改**：`CloudSyncConfigFields.kt`、`VaultChildDatabaseSections.kt`
- `ACTIVE_ISSUES.md`：剩余清单第 1 项计数 8 → 6、头部名单更新。
