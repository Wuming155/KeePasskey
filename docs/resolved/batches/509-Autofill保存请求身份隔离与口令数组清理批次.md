# §509 Autofill 保存请求身份隔离与口令数组清理批次

> **状态：已实施并通过宿主层验收。** 本批次闭环 `ISSUE-P2-603`、`ISSUE-P2-606`。

<a id="s509"></a>

## 1. 原始条目

> 以下条目为闭环前从 `ACTIVE_ISSUES.md` 剪切的原始登记快照；其「仅审计」属历史状态，最终实施与验收结论以第 2 节为准。

### ISSUE-P2-603：锁定态 Autofill 保存复用同一个 PendingIntent

- **整改方案**：[ISSUE-P2-603 独立方案](../../architecture/问题整改方案/P2/ISSUE-P2-603/锁定态自动填充保存请求隔离方案.md)；方案已编制，尚未实施。

- **来源 / 严重度**：全面软件审计 `AUD-15`，Medium，可信度高（实际设备后果未实测）。
- **核实时间点 + 核实方式**：2026-10-13；现行调用链与相关测试直读。仅审计，未整改。
- **背景 / 位置**：`KeePasskeyAutofillService.kt:435-450` 固定 REQUEST_CODE_SAVE，Intent 仅 extras 不同并使用 FLAG_UPDATE_CURRENT；不同请求身份相同。
- **触发 / 后果**：两个锁定态保存请求并存，后请求替换前请求 extras；先前系统入口可打开后一凭据保存上下文，造成保存串单。
- **建议 / 验收**：每请求唯一身份及生命周期回收，验证双请求并存和进程重建不串凭据；设备端点击交错待验证。
- **详细证据**：[全面软件审计](../../security/全面软件审计_2026-10-13.md)。

### ISSUE-P2-606：Autofill 保存密码数组在等待解锁阶段缺少取消清理

- **整改方案**：[ISSUE-P2-606 独立方案](../../architecture/问题整改方案/P2/ISSUE-P2-606/保存口令解锁等待清理方案.md)；方案已编制，尚未实施。

- **来源 / 严重度**：全面软件审计 `AUD-18`，Medium，可信度高（实际设备后果未实测）。
- **核实时间点 + 核实方式**：2026-10-13；现行调用链与相关测试直读。仅审计，未整改。
- **背景 / 位置**：`PasswordSaveActivity.kt:70` 在 requireUnlocked 前构造口令数组，而清理在后续 startSave 的 finally（`:140`）；未解锁即退出不会进入该 finally。
- **触发 / 后果**：用户取消解锁或 Activity 关闭，捕获的口令数组未显式擦除，仅等待 GC。
- **建议 / 验收**：把数组所有权覆盖整个解锁等待阶段，取消/销毁/成功移交均闭环；验证取消解锁和 Activity 重建不会泄漏或双重清理。
- **详细证据**：[全面软件审计](../../security/全面软件审计_2026-10-13.md)。

## 2. 实施与验收

### ISSUE-P2-603 请求身份隔离

- **前提复核**：直读 `dispatchLockedSave` 确认前提成立——固定 `REQUEST_CODE_SAVE=2300`，`Intent` 仅
  extras 不同，配 `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT` ⇒ 同 (requestCode, filterEquals) 身份下，
  后一请求会改写已交付实例的 extras。
- **改动（`KeePasskeyAutofillService.dispatchLockedSave`）**：每请求生成不可预测令牌
  （`UUID.randomUUID()`，不含凭据），写入 `Intent.data = keepasskey://autofill-save-request/<token>`
  （参与 `PendingIntent.filterEquals`）并随 `EXTRA_REQUEST_TOKEN` 交付；requestCode 由**进程级单调分配器**
  逐请求分配（基址 3100，与认证段 100、CM 段 1000、常量段 2001~3006 不重叠；该基址已登记进
  `CredentialPendingIntents` 的 `AVOID-BAND` 清单）。保留非导出 Activity 与 `FLAG_IMMUTABLE`；
  身份唯一后 `UPDATE_CURRENT` 只可能作用于自身请求。
- **改动（`PasswordSaveActivity`）**：自动填充保存路径（`providerReq == null`）**必须**携带令牌，
  缺失即 fail-closed；令牌经新建 `SaveRequestLedger` 做**单次消费**判定（`isConsumed` / `markConsumed`），
  成功 / 明确失败 / 异常路径登记消费。台账只存令牌 SHA-256 **摘要**（不落令牌明文），持久化于应用私有
  `SharedPreferences`（跨进程重建仍可判），记录超 15 分钟 TTL 就地清理。
- **覆盖提升（`CredentialRequestCodeWiringTest` 7 例全绿）**：`REQUEST_CODE_SAVE` 由 2300 改 3100 后，
  避开带清单同步更新（该机检专防「常量改名 / 漏登」漂移——首红两例即由此揭出，已按清单补齐）。

### ISSUE-P2-606 口令数组所有权

- **前提复核**：直读 `PasswordSaveActivity.onCreate` 确认前提成立——`password.toCharArray()` 在
  `unlockPresenter.requireUnlocked` **之前**构造，清理只在后续 `startSave` 协程的 `finally`，
  未进入该协程即不清理。
- **改动**：数组自构造起登记为 Activity 在**等待解锁阶段独占**的载体 `pendingPasswordChars`；
  新增幂等 `releasePendingPassword()`（仅在仍持有时擦除），在 `onDestroy` 调用；解锁成功回调中
  **转移**所有权给保存任务并从本字段注销（避免双擦）；回调迟到（等待副本已释放）时不再推进。
  保存任务的 `finally` 仍负责擦除其持有的数组。不写 SavedState。

### 统一验证

- **定向回读 XML**：`SaveRequestLedgerTest` 4 / `AutofillSaveRequestIsolationTest` 4 /
  `AutofillSaveRobustnessTest` 5 / `AutofillAuthResultWiringTest` 7 / `CredentialPendingIntentsTest` 4 /
  `AutofillSaveTimeoutWiringTest` 4 / `CredentialRequestCodeWiringTest` 7 —— 全部 `failures=0 errors=0`。
- **全量单测**：`./gradlew.bat test --max-workers=1` EXIT 0；`count_test_results.py` 读数
  **`xml=570 tests=3730 failures=0 errors=0 skipped=15`**（§508 基线 3722 ⇒ +8，与新增 4+4 例一致）。
- **首红留痕**：本批首轮全量 2 红（`CredentialRequestCodeWiringTest` 的避开带漂移两项），
  先跑 `preserve_test_failures.py` 保全（`build/failure-evidence/unit-test-failures-20261014-020444.txt`），
  再定位到「`REQUEST_CODE_SAVE` 改名未同步清单」，修正后复跑全绿。

## 3. 门禁读数

=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/16] tools/doc/check_gate_script_integrity.py     EXIT 0  | [check_gate_script_integrity] 清单 19 条 / 期望 19 条；命中 0 处  [check_gate_script_integrity] PASS：门禁脚本集合 19 条与 SHA-256 基线清单逐条一致
[2/16] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=35  budget=35
[3/16] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[4/16] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[5/16] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 506 份；分册登记 508 条；全量索引 508 条；最大 §509）
[6/16] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 641 个测试文件
[7/16] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[8/16] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[9/16] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  多发射助手函数（顶层发射 ≥2 节点的 @Composable）：63 个  检查过的调用点：BoxScope 组件=61  框架 supportingText 槽=9  box_slot_stacked_sites=0
[10/16] tools/audit/check_launch_language_seed.py    EXIT 0  | === 启动语言种子接线机检（五条 fail-closed）===  [E1] MainApplication.onCreate 灌启动种子	ok  [E2] Compose 首帧 initialValue 取种子	ok  [E3] AppLocaleTracker.init 取种子（StringsProvider 首帧通道）	ok  [E5] AppLocaleTracker 慢路径补种（SP 无历史镜像时的兜底）	ok  [E4] RealSettingsRepository.setAppLanguage 镜像种子	ok  --- launch_language_seed=OK（五条接线齐全）---
[11/16] tools/doc/check_projection_read_safety.py    EXIT 0  | [check_projection_read_safety] 登记面文件 13/13；展示读口文件 16 个（白名单 16）；命中 0 处  [check_projection_read_safety] PASS
[12/16] tools/doc/check_raw_coroutine_scope.py       EXIT 0  | [check_raw_coroutine_scope] 检查文件 757 个（*/src/main/**）；允许清单 1 个；命中 0 处  [check_raw_coroutine_scope] PASS
[13/16] tools/doc/check_archive_monotonicity.py      EXIT 0  | [check_archive_monotonicity] 基线 HEAD~1：索引行 506 个 / 批次正文 504 份；现树 工作区：508 / 506；缺失 0 处  [check_archive_monotonicity] PASS
[14/16] tools/doc/check_plaintext_carrier_to_string.py EXIT 0  | [check_plaintext_carrier_to_string] 检查文件 757 个（*/src/main/**）；允许清单 0 个；命中 0 处  [check_plaintext_carrier_to_string] PASS
[15/16] tools/doc/check_message_not_in_user_text.py  EXIT 0  | [check_message_not_in_user_text] 检查文件 757 个（*/src/main/**）；文案槽 10 类；命中 0 处  [check_message_not_in_user_text] PASS
[16/16] tools/doc/check_cancellation_semantics.py    EXIT 0  | [check_cancellation_semantics] 检查文件 757 个（*/src/main/**）；命中 0 处  [check_cancellation_semantics] PASS
=== 汇总：16/16 PASS ===

## 4. 限制与后续边界

- **未执行设备端双请求点击交错**：603 的「A/B 两请求乱序点击、进程重建后点击 A」需真机两次保存请求
  并存后实点，本批**未执行**（仅以宿主层断言锁定 Intent 身份构造与消费台账语义）。该残余如实登记，
  不以 requestCode 数值不同代替真实点击。
- 单次消费在**生命周期取消**（Activity 被销毁/配置重建导致的协程取消）路径**不**登记消费，以允许
  用户重新触发；成功 / 明确失败 / 异常登记消费。此取舍如实声明。
- 台账 TTL 15 分钟为工程取值（非产品裁决），重开条件：若出现合法重试被误拒，按实际窗口调整。
- 未触达 Passkey schema / KPEX / 原生绑定，不触发互操作对拍与四层设备测试。
