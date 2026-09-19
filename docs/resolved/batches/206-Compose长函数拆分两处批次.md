# §206 批次：Compose 长函数拆分两处批次（BasicCredentialsCard / SyncStatusCard）

> 日期：2026-09-19　|　条目：`ISSUE-P3-188` 剩余清单第 1 项（渐进推进：待拆 14 → **12**）
> 口径：`python tools/doc/long_functions.py` 现跑（≥100 行函数 16 → **14**）

## 1. 本批做了什么（一律「只搬不改逻辑」、零行为变更）

### 1.1 `BasicCredentialsCard` 118 → **约 40 行**（出表）

- **段落去向判定**：宿主文件 `EntryDetailCards.kt` 397 行，距 400 余量仅 3 行——按 §192 / §196
  「余量逐案判」口径**另起新文件**（追加必入第二档）。
- 新文件 `EntryDetailCardSections.kt`（160 行）：
  - `BasicCredentialsUsernameRow`（用户名行：标签 + 用户名 + 复制按钮）；
  - `BasicCredentialsPasswordArea`（密码区：标签 + 明文/掩码交叉淡入 + 揭示/复制按钮 + 强度条）。
  两段**不自持状态**（haptic 经 LocalHapticFeedback 各自获取，与搬迁前同源），显隐态与回调全部
  由 `BasicCredentialsCard` 持有并经参数回传。原文件 397 → **315 行**（远离 400）。
- 该函数的绘制面（AnimatedContent 过渡参数 / 触感类型 / TASK-32 与 ISSUE-P2-16 注释）逐字未动。

### 1.2 `SyncStatusCard` 119 → **约 45 行**（出表）

- **段落去向判定**：宿主文件 `CloudSyncComponents.kt` 325 行，追加段落将贴近 400 行档沿——
  同判**另起新文件**。
- 新文件 `CloudSyncStatusSections.kt`（约 155 行）：
  - `SyncStatusHeaderRow`（标题 + 连接状态徽章，状态色三态逐字保持）；
  - `SyncFeedbackMessage`（反馈消息条，`null` 不渲染——原 `if != null` 改为 early return 等价形态）;
  - `SyncActionButtonsRow`（手动同步 / 测试连接按钮行，含 ISSUE-P3-132 ③ 禁用态共用配色与描边）。
  原文件 325 → **约 235 行**。
- **守卫并集（第 3 项规则的活例）**：`UiMd3AlignmentWiringTest` 的「禁用态共用配色 / 描边」判据
  锚在 `CloudSyncComponents.kt`，按钮行搬迁后首跑即红（守卫有效的直接证据）——同批把该判据
  扫描改为「门面 + 分支文件」**并集**（`CloudSyncComponents.kt` + `CloudSyncStatusSections.kt`），
  断言强度零变化（`contains` 两条逐字不动）。

## 2. 验证

- 全量 `test --rerun-tasks --max-workers=1`：`BUILD SUCCESSFUL`、114 任务全执行；
  聚合 `tests=2233 failures=0 errors=0 skipped=13`（与 §205 同值，纯搬动零用例变动）。
- `long_functions.py` 现跑：16 → **14**；`count_line_tiers.py` 现跑 tier2 = **28 维持不变**
  （`EntryDetailCards.kt` 397→315、`CloudSyncComponents.kt` 325→约 235、两个新文件均不入档）。
- `lint`：**214** 持平。

## 3. 涉及文件

- **新增**：`EntryDetailCardSections.kt`、`CloudSyncStatusSections.kt`
- **修改**：`EntryDetailCards.kt`、`CloudSyncComponents.kt`、`UiMd3AlignmentWiringTest.kt`（守卫并集）
- `ACTIVE_ISSUES.md`：剩余清单第 1 项计数 14 → 12、头部名单更新。
