# §373 同步状态 Snackbar 时长收短批次

- **触发**：2026-09-30 用户在 §372 真机 AC③ 确认「成功了」之后，反馈：
  「同步完成后弹出的 Toast 时间有点长，能不能缩短呢？」。
- **术语澄清（如实）**：同步完成提示并非 `android.widget.Toast`，而是外壳全局 Snackbar
  （`AppGlobalSnackbarHost` + `AppSnackbarChannel`，`ISSUE-P3-359` AC④）。Material3
  `SnackbarHostState.showSnackbar` 在无 `actionLabel` 时默认 `SnackbarDuration.Short` ≈ **4s**，
  用户感知为偏长。
- **开放项变动**：纯交互体验批次，**不**新开 ACTIVE_ISSUES 条目（用户直接指名要求的 UX 收短）；
  §372 的 AC③ 结论同批补录为「已真机验证」（见该批次 §6 与 `RESOLVED_LOG`）。

## 1. 整改

### 1.1 判据单点化

- `UiMessage` 增可选字段 `durationMillis: Long? = null` 与常量
  `SYNC_STATUS_DURATION_MS = 1_000L`（同步状态类反馈展示时长；**取值过程**：初版 2s，
  用户真机试装后仍嫌久，2026-09-30 同日再收至 **1s**）；
- 空值语义不变：`null` = 宿主默认档（无撤销动作 → Material Short ≈4s；有撤销动作 → Material Long ≈10s）。

### 1.2 宿主落地（`AppGlobalSnackbarHost`）

- 无撤销动作时才读取 `message.durationMillis`，以**延迟 dismiss** 落地
  （Material 枚举无「更短于 Short」档，故不改 `SnackbarDuration` 本身）；
- **有撤销动作恒走 `SnackbarDuration.Long`**，不得被收短——用户需要点「撤销」的时间窗
  （批量删除 / 详情软删除语义不变）。

### 1.3 生产者挂载（状态类才挂）

| 场景 | 文案 | 时长 |
|---|---|---|
| 列表页两端一致 | `vault_sync_completed` | `SYNC_STATUS_DURATION_MS` |
| 列表页本地已上传 / 合并已上传 | `vault_sync_uploaded` | `SYNC_STATUS_DURATION_MS` |
| 列表页远端已刷新 | `vault_sync_remote_updated` | `SYNC_STATUS_DURATION_MS` |
| 设置页 done / uploaded / merged | `sync_feedback_done/uploaded/merged` | `SYNC_STATUS_DURATION_MS` |
| 错误 / 冲突 / 离线 / 绑定不符 | `sync_feedback_error` 等 | **不挂**（默认档，便于阅读） |

说明：设置页同步完成反馈主路径是页内 `SyncFeedbackMessage` 状态条（非 Snackbar）；
在 `SettingsSyncController` 上挂时长是**前瞻一致性**（若后续改走全局通道则自动生效），
当前真机「完成 Toast 偏长」的主现场仍是**列表页解锁后自动同步**。

### 1.4 发布链路

`VaultListMessagePublish` 仍把整条 `UiMessage` 交给 `AppSnackbarEvent(message, onUndo?)`，
时长字段随消息下行，发布件**零改动**。

## 2. 验收

- AC① 接线守卫 `SyncSnackbarDurationWiringTest`（源码级）：`UiMessage` 字段/常量存在；
  宿主「无撤销才 override / 有撤销走 Long / 延迟 dismiss」三判据；列表页恰 3 处短档挂载；
  错误反馈构造不得携带 `SYNC_STATUS_DURATION_MS`；设置页状态类 ≥3 处短档且 error 不挂；
  发布件必须 `message = message` 整条下行。
- AC② 行为边界不变：撤销类消息仍走 Material Long（宿主源码断言）；错误 / 冲突不缩短。
- AC③ 全量 `test --rerun-tasks --max-workers=1` 绿 `xml=495 tests=3204 failures=0 errors=0
  skipped=13`（§372 基线 494/3199 ⇒ +1 xml/+5 例）+ 门禁 8/8（§5）。

## 3. 测试

新增 `SyncSnackbarDurationWiringTest` **5 例**（wiring；JVM 无法断言真实显示毫秒数，
故判据锚定源码结构，与仓内其余 snackbar 接线守卫同口径）。

## 4. 如实声明

- 未改 Material3 默认档，也未改 `Toast.LENGTH_*`（本提示本就不是 Toast）；
- 未改撤销 / 批量删除 / 详情软删除的显示时长；
- 真机「完成后约 2s 消失」的效果以用户下一次解锁同步走查为最终判据（本批交付包为
  `adb install -r` 原地升级后的 debug）；
- §372 AC③ 已同批补录为真机验证通过（用户确认「成功了」+ 坚果云端文件修改时间未变动）。

## 5. 验证读数（原样粘贴 `gate_readings.py` 输出，归档前采集）

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/8] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=37  budget=37
[2/8] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/8] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/8] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 371 份；分册登记 373 条；全量索引 373 条；最大 §373）
[5/8] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 553 个测试文件
[6/8] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/8] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[8/8] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  检查过的调用点：59  box_slot_stacked_sites=0
=== 汇总：8/8 PASS ===
```

> 计数：`count_test_results.py` = `xml=495 tests=3204 failures=0 errors=0 skipped=13`。
> 门禁 [4/8]「批次正文 371 份」＝§42/§43 已退役至 `security/` 的既有口径（373 − 2）。

## 6. 用户操作指引

设备已装含本批代码的 Debug 包后：解锁密码库 → 列表页自动同步 →
完成提示应在约 **1 秒**内消失（Material 默认约 4 秒；§373 初版 2s 后按用户反馈再收至 1s）。
删除类「撤销」提示仍会显示更久以便点按。
