# §208 批次：Compose 长函数拆分两处批次（HealthCheckScoreCard / MasterKeyChangeDialog）

> 日期：2026-09-19　|　条目：`ISSUE-P3-188` 剩余清单第 1 项（渐进推进：待拆 10 → **8**）
> 口径：`python tools/doc/long_functions.py` 现跑（≥100 行函数 12 → **10**）

## 1. 本批做了什么（一律「只搬不改逻辑」、零行为变更）

### 1.1 `HealthCheckScoreCard` 116 → **约 50 行**（出表）

- 宿主文件 `HealthCheckScreenSections.kt` 159 行余量充足，段落**追加原文件**：
  - `HealthCheckScoreGauge`（96dp 环形进度 + 分数圆，含色阶映射与「只放分数」的历史注释）；
  - `HealthCheckScoreTexts`（总分数 / 评级 / 消息 / 上次扫描文本序列）。
- 重扫按钮（含 ISSUE-P3-132 ③ 禁用态共用件）留在主函数；MD3 守卫已是
  「HealthCheckScreen.kt + HealthCheckScreenSections.kt」并集，无感知。原文件 159 → **185 行**。

### 1.2 `MasterKeyChangeDialog` 116 → **约 95 行**（出表，三步收敛）

- **敏感面约束生效**：`SecureDialogFlagPolicyTest` 以 `SecureDialog {` 调用点计数锚定该文件
  （§193 清单锁）⇒ `SecureDialog {` 包裹与 `AlertDialog` 本体**留在宿主文件**，只搬非保密段：
  1. `MasterKeyChangePasswordFields`（新 / 确认两行输入字段 + 说明文字）：**只搬渲染面**——
     `onPasswordChanged` 回调原样上行（调用方的 `fill(0)` + `copyOf` 擦除链仍在现场），
     密码字节不经过本段落驻留；
  2. `MasterKeyChangeConfirmButton`（「保存更改」按钮渲染面）：**只搬渲染**——enabled 判定与
     onClick 提交逻辑（`pwdChars` 的 copyOf → wipe → 协程 → finally 擦除链）全部留在对话框现场
     （擦除链「单一现场」口径，类比 §179；ISSUE-P3-134 禁用态共用配色 / 描边随渲染面逐字同迁）。
- 原文件 168 → **209 行**（新增两个 `private` 段落组件与 KDoc，不入第二档）。

## 2. 验证

- 全量 `test --rerun-tasks --max-workers=1`：`BUILD SUCCESSFUL`、114 任务全执行；
  聚合 `tests=2233 failures=0 errors=0 skipped=13`（同值，纯搬动零用例变动）。
- `long_functions.py` 现跑：12 → **10**（`SecureDialogFlagPolicyTest` 随全量套件通过
  ⇒ `SecureDialog {` 调用点计数不受影响）；`lint`：**214** 持平。

## 3. 涉及文件

- **修改**：`HealthCheckScreenSections.kt`、`MasterKeyChangeDialog.kt`
- `ACTIVE_ISSUES.md`：剩余清单第 1 项计数 10 → 8、头部名单更新。
