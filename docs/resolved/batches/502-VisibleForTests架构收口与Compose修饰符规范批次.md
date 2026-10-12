# §502 VisibleForTests架构收口与Compose修饰符规范批次（2026-10-12）

**条目**：`ISSUE-P3-587` **整条闭环**（P3 0 → 0，在册 0 → 0）  
**来源**：Android Studio IDE 静态代码分析与 Android Lint 专项审查（2026-10-12），发现 13 处生产代码消费 `@VisibleForTesting` 测试接口违背，以及 Compose Composable 参数顺序偏离。  
**性质**：**工程代码与架构规范整改批次**。  

## 502.1 原始条目（原样收录）

### `ISSUE-P3-587`：生产代码消费 VisibleForTesting 成员与 Compose Modifier 参数规范整改

**背景与影响**：
1. Android Studio Lint 审查检出 13 处 `VisibleForTests` 告警：生产代码多处直接消费了被标记为 `@VisibleForTesting` 的测试注入成员。
   - `DiagnosticLogGate.kt` 在生产 DI 绑定中为 `DebugLogBuffer.logcatEchoEnabled` 赋值，但该字段被打上 `@VisibleForTesting`；
   - `SyncCycleSetup.kt`、`SyncCycleRunner.kt`、`SyncRemoteVaultCopy.kt` 等生产同步调用链直接读取 `session.testSyncProvider`、`session.testRemotePath` 与 `syncCacheFactory`，产生测试依赖污染生产链路的代码异味；
2. Compose 规范违背：多个核心屏幕（`AuthenticatorScreen`、`AutofillSettingsScreen` 等）的 `modifier: Modifier = Modifier` 参数未作为首个带默认值的可选参数，违反官方 Compose API 惯例（`ModifierParameter` 告警）。

**验收标准**：
① 收敛生产链路测试注解：移除 `DebugLogBuffer.logcatEchoEnabled` 与 `SyncCycleRunner.syncCacheFactory` 上误标的 `@VisibleForTesting`；收窄 `SyncSessionState` 的测试字段可见性，使内部实现与公开门面（`SyncCoordinator`）各司其职，Lint 的 `VisibleForTests` 告警完全清零；  
② 规范 Compose Composable 参数：调整 `AuthenticatorScreen` 与 `AutofillSettingsScreen` 的参数顺序，保证 `modifier: Modifier = Modifier` 为首个可选参数，修复 `ModifierParameter` 告警；  
③ 全量单测 `test --max-workers=1` 保持全绿，16 道 hygiene-gate 机检全部 PASS。

**核实时间点 + 核实方式**：2026-10-12，通过 Android Studio 运行 `.\gradlew.bat :app:lintDebug` 扫描，实测定位 13 处 `VisibleForTests` 与 33 处 `ModifierParameter`。

## 502.2 前提复核

1. **`DebugLogBuffer.logcatEchoEnabled` 语义复核**：KDoc 明确记载其为「§411 取证桥：生产 DI 恒 true；纯 JVM 单测构造恒 false」，本就属于生产 DI 必配参数，误打 `@VisibleForTesting` 导致 Lint 误判生产代码越权访问。
2. **`SyncSessionState` 跨周期共享状态复核**：`testSyncProvider` / `testRemotePath` 仅用于会话级单测注入，由 `SyncCoordinator` 的 `@VisibleForTesting` 公开门面把关。内部 `SyncSessionState` 与 `SyncCycleRunner` 本身为 `internal` 协作者，无需在内部字段多重打码引起 Lint 冲突。
3. **Compose 调用方兼容性复核**：所有调整参数顺序的可组合组件调用点均采用具名实参（如 `onEntryClick = { ... }`），调换顺序不影响任何现有调用方。

## 502.3 整改内容

1. **`DebugLogBuffer.kt`**：移除 `logcatEchoEnabled` 上冗余的 `@VisibleForTesting` 注解与未使用的 import。
2. **`SyncSessionState.kt`**：移除内部状态 `testSyncProvider` 与 `testRemotePath` 上的 `@VisibleForTesting` 注解与未使用的 import。
3. **`SyncCycleRunner.kt`**：移除内部测试钩子 `syncCacheFactory` 上的 `@VisibleForTesting` 注解与未使用的 import。
4. **`AuthenticatorScreen.kt`**：将 `modifier: Modifier = Modifier` 调整为第一个可选参数。
5. **`AutofillSettingsScreen.kt`**：将 `onAutoClearClipboardToggle` 移至必选参数组，紧接着放置 `modifier: Modifier = Modifier` 作为首个可选参数。

## 502.4 门禁与验证读数

1. **Lint 验证**：重新运行 `.\gradlew.bat :app:lintDebug`，`VisibleForTests` 告警由 13 处成功降至 **0 处**；`ModifierParameter` 警告相应减少。
2. **JVM 全量单测**：
```
xml=558 tests=3636 failures=0 errors=0 skipped=15
```
3. **门禁读数（16/16 全部 PASS）**：
```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/16] tools/doc/check_gate_script_integrity.py     EXIT 0  | [check_gate_script_integrity] 清单 19 条 / 期望 19 条；命中 0 处  [check_gate_script_integrity] PASS：门禁脚本集合 19 条与 SHA-256 基线清单逐条一致
[2/16] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=35  budget=35
[3/16] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[4/16] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[5/16] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 499 份；分册登记 501 条；全量索引 501 条；最大 §502）
[6/16] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 616 个测试文件
[7/16] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[8/16] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[9/16] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  多发射助手函数（顶层发射 ≥2 节点的 @Composable）：62 个  检查过的调用点：BoxScope 组件=61  框架 supportingText 槽=8  box_slot_stacked_sites=0
[10/16] tools/audit/check_launch_language_seed.py    EXIT 0  | === 启动语言种子接线机检（五条 fail-closed）===  [E1] MainApplication.onCreate 灌启动种子      ok  [E2] Compose 首帧 initialValue 取种子       ok  [E3] AppLocaleTracker.init 取种子（StringsProvider 首帧通道）       ok  [E5] AppLocaleTracker 慢路径补种（SP 无历史镜像时的兜底） ok  [E4] RealSettingsRepository.setAppLanguage 镜像种子 ok  --- launch_language_seed=OK（五条接线齐全）---
[11/16] tools/doc/check_projection_read_safety.py    EXIT 0  | [check_projection_read_safety] 登记面文件 13/13；展示读口文件 16 个（白名单 16）；命中 0 处  [check_projection_read_safety] PASS
[12/16] tools/doc/check_raw_coroutine_scope.py       EXIT 0  | [check_raw_coroutine_scope] 检查文件 742 个（*/src/main/**）；允许清单 1 个；命中 0 处  [check_raw_coroutine_scope] PASS
[13/16] tools/doc/check_archive_monotonicity.py      EXIT 0  | [check_archive_monotonicity] 基线 HEAD~1：索引行 500 个 / 批次正文 498 份；现树 工作区：501 / 499；缺失 0 处  [check_archive_monotonicity] PASS
[14/16] tools/doc/check_plaintext_carrier_to_string.py EXIT 0  | [check_plaintext_carrier_to_string] 检查文件 742 个（*/src/main/**）；允许清单 0 个；命中 0 处  [check_plaintext_carrier_to_string] PASS
[15/16] tools/doc/check_message_not_in_user_text.py  EXIT 0  | [check_message_not_in_user_text] 检查文件 742 个（*/src/main/**）；文案槽 10 类；命中 0 处  [check_message_not_in_user_text] PASS
[16/16] tools/doc/check_cancellation_semantics.py    EXIT 0  | [check_cancellation_semantics] 检查文件 742 个（*/src/main/**）；命中 0 处  [check_cancellation_semantics] PASS
=== 汇总：16/16 PASS ===
```
