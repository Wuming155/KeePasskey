# §487 WebDAV 覆盖兜底 DELETE 前重探守卫批次（2026-10-11）

**条目**：`ISSUE-P2-572` **整条闭环**（P2 1 → **0**）
**来源**：外部评审意见（WebDAV 同步对比评审）第 1 条，经核实成立（登记于批次 `18f16912`，核实时间点 2026-10-11）。

## 487.1 原始条目（原样收录）

### `ISSUE-P2-572`：WebDAV 409/423 兜底「DELETE 后无预条件 MOVE 重试」存在并发覆盖窗口

**背景与影响**：`WebDavUploadAtomic.run`（`sync/src/main/java/com/keepasskey/sync/webdav/WebDavUploadAtomic.kt:115-127`）
在 attempt 0 的 MOVE（携带 `If` tagged-list 预条件 + `Overwrite: T`）返回 409/423 时走
`WebDavMoveOverwritePolicy.isOverwriteConflict` 兜底：先 `delete(remotePath)`（**无条件 DELETE，返回值不校验**），
再以 `withPrecondition = false`（无 `If` 头）单次重试 MOVE。兜底本身是 `ISSUE-P2-500` 吸收 keepass2android
`9c8ee243` 服务器兼容教训的**有意设计**，但它无法区分「服务器对 MOVE 覆盖的能力性拒绝」与「远端内容已被他端并发修改」：

1. **窗口 A（DELETE 本身）**：`delete(remotePath)` 删除的是「此刻远端」——若他端在本端上传临时文件期间
   （大库可达数十秒）写入了新版本，该版本被直接删除；
2. **窗口 B（DELETE → 重试 MOVE 之间）**：重试 MOVE 无任何预条件，窗口内他端新建的目标被静默覆盖。

边界现状：在正确评估 `If` 头的服务器上，远端变更会在 attempt 0 即返回 412 → 冲突路径，兜底不进入；
无 ETag 服务器的覆盖上传按既有产品口径本就是最后写入者胜（`WebDavSyncProvider.kt:334-341`），无锁可剥；
被覆盖方若同为三哈希模型客户端（本仓 / Kp2a / KeePassXC）会保留本地缓存、下轮同步检测到冲突可合并找回——
真正的丢失面是**朴素 WebDAV 客户端**。已知限界表 §28 的服务器矩阵只实测了 mod_dav（`If` 如实评估）与
nginx（MOVE `If` 整体忽略但 MOVE 覆盖成功，同样不进入兜底），**报 409/423 的服务器类未实测、本窗口未登记**。

**整改依据**：与 `ISSUE-P2-501` 立住的原则同源——「不得剥除乐观锁静默覆盖他端写入」；兜底路径恰在
服务器行为未知的类目上剥除了全部预条件。修复成本低：attempt 0 收到 409/423 后**先重探目标元数据**——
远端 ETag 非空且 ≠ `expectedEtag` → 直接抛 `ConflictError`（携当前远端 ETag），**不 DELETE**；
ETag 相同或缺失 → 才走既有 DELETE + 重试（维持 Kp2a 兼容收益）；探测失败沿用 `ISSUE-P2-501` fail-closed 口径如实上抛。
可选加固：DELETE 带 `If-Match`（RFC 7232 下 `If-Match` 作用于请求-URI，DELETE 直接指向目标，语义成立；
不支持条件 DELETE 的服务器按 412 保守转冲突）。

**验收标准**：
1. `WebDavUploadAtomic`：409/423 兜底进入 DELETE 前必须重探元数据；远端 ETag 非空且 ≠ `expectedEtag`
   时抛 `ConflictError` 不得 DELETE；探测失败如实上抛（fail-closed）；
2. 纯策略面（`WebDavMoveOverwritePolicy`）扩展「兜底前置条件」判定并配纯 JVM 单测
   （ETag 相同 / 漂移 / 缺失 / 探测失败四分支）；
3. 既有 409/423 兜底用例与 `WebDavUploadAtomic` 单测全绿，不放宽 `TransientHttpRetry` 的无条件写禁重试口径；
4. `已知工程限界.md` §28 视实测条件补记「409/423 服务器类」样本（无环境则维持「未执行」标注，不得写为已证）。

**核实时间点 + 核实方式**：2026-10-11，通读 `WebDavUploadAtomic.kt` / `WebDavMoveOverwritePolicy.kt` 全文逐行核对
attempt 循环与兜底分支；`grep` 确认 `docs/architecture/已知工程限界.md` §28 未覆盖「报 409/423 服务器类」、
`docs/ACTIVE_ISSUES.md` 无同主题开放条目。来源：外部评审意见（WebDAV 同步对比评审）第 1 条，经核实成立。

## 487.2 前提复核（2026-10-11 直读）

- **条目成立**：`WebDavUploadAtomic.kt` attempt 0 命中 `isOverwriteConflict`（409/423）时原实现为
  `logOverwriteConflict` → `delete(remotePath)`（无条件、返回值不校验）→ `withPrecondition = false` →
  `continue`，重试 MOVE 无 `If` 头——窗口 A / 窗口 B 与条目描述逐字吻合。
- **行号微漂移（不成立面）**：条目引 `WebDavSyncProvider.kt:334-341` 为「无 ETag 覆盖上传产品口径」KDoc，
  实际位于该文件 `uploadAtomic` KDoc 的 Overwrite 语义段（§486 批次后行号略移），语义原文未变，不影响判据。
- **既有用例面**：409 兜底仅两处用例——`WebDavSyncScenarioTest` 有状态 mock 例（**未传 expectedEtag**）
  与逐条 enqueue 例（`场景3 MOVE覆盖已有目标409兜底重试且带非空预期ETag时重试不带If预条件`，响应序
  PUT → 409 → DELETE → MOVE）；后者在重探插入后**必须补一条 PROPFIND 响应**，否则重探会把 DELETE 的
  204 当 PROPFIND 结果解析而 fail-closed（`androidTest` 无 409 兜底用例，不受影响）。

## 487.3 整改

### ① 策略面：`WebDavMoveOverwritePolicy` 扩展兜底前置守卫判定（AC②）

- 新增 `internal sealed interface OverwriteFallbackGuard`：`Proceed`（放行兜底）/
  `Drifted(currentRemoteEtag)`（漂移，禁 DELETE）/ `ProbeFailed`（重探失败，fail-closed）。
- 新增 `fallbackGuard(currentMeta: RemoteFileMetadata?, expectedEtag: String?)`：**四分支纯判定**——
  `currentMeta == null`（重探失败，含 404 / 5xx）→ `ProbeFailed`；远端 ETag 非空且 ≠ 非空预期 →
  `Drifted`（携当前远端 ETag）；相同 / 缺失 / 无预期基线 → `Proceed`。
- 新增 `isOverwriteDrift` 谓词：两侧均非空白且不相等才判漂移——预期基线空白（本就是无预条件覆盖上传）
  无「可失效的锁」，不判漂移（既有无 expectedEtag 兜底用例的语义因此逐字保留）；比较双方均为
  `cleanEtag` 规范形态（弱标记保留，与 ISSUE-P1-275 读写一致化口径对齐）。
- 新增 `overwriteDriftMessage(httpCode)`：与 412 文案同型但携实际 409/423 状态码（409/423 与 412 分开报错）。
- KDoc 语义段同步：409/423 → 「先重探做兜底守卫，放行时 DELETE + 无预条件重试」。

### ② 编排面：`WebDavUploadAtomic` 兜底 DELETE 前先重探（AC①）

attempt 0 的 409/423 分支在 `delete(remotePath)` 之前插入：`getMetadata(remotePath)` 重探 →
`fallbackGuard` 裁决——`Drifted` → 构造 `ConflictError(remoteEtag = 当前远端 ETag,
localExpectedEtag = expectedEtag.orEmpty(), message = overwriteDriftMessage(conflictCode))` 并 `break`
（**不 DELETE**，临时文件由既有 `!moveSuccess` 清理块删除）；`ProbeFailed` → `probeFailure =
metaResult.exceptionOrNull()`（fail-closed 如实上抛，ISSUE-P2-501 同口径）并 `break`；`Proceed` →
落回既有 `delete(remotePath)` + `withPrecondition = false` 重试路径（Kp2a 兼容收益逐字保留）。
重探是只读 PROPFIND，不触碰 `TransientHttpRetry` 的「无条件写禁重试」口径（AC③ 前半句依据：
`carriesPrecondition` 判定与 MOVE 的 `If` 头构造逐字未动）。

### ③ KDoc 同步

`WebDavSyncProvider.uploadAtomic` KDoc 的 ISSUE-P2-381 段补「先重探目标元数据做兜底守卫
（ISSUE-P2-572：远端 ETag 非空且 ≠ 预期 ⇒ ConflictError 不 DELETE；探测失败如实上抛 fail-closed；
相同 / 缺失才放行）」。

### ④ 测试资产

- **新增** `sync/src/test/java/com/keepasskey/sync/webdav/WebDavMoveOverwritePolicyTest.kt`（8 例，纯 JVM）：
  四分支各 1 例（相同放行 / 漂移禁 DELETE 携当前 ETag / 缺失放行 / 重探失败 fail-closed）+
  无预期基线不判漂移 + 弱 ETag 原形态比较（弱漂移判漂移、弱相同放行）+ 漂移谓词空白边界 +
  漂移文案携实际状态码且不冒用 412 字样。
- **新增** `sync/src/test/java/com/keepasskey/sync/scenario/WebDavOverwriteFallbackGuardTest.kt`（4 例，
  有状态 mock + 逐条 enqueue 双形态）：
  ① 重探发现他端并发写入 ⇒ ConflictError 携当前远端 ETag、他端内容与 ETag 原状、无重试 MOVE、临时文件清理；
  ② 重探持续失败（PROPFIND 恒 500）⇒ 非 ConflictError 的探测失败如实上抛、远端不被 DELETE、无重试 MOVE；
  ③ 重探确认未漂移 ⇒ 既有兜底保留（MOVE 409 + PROPFIND + DELETE + MOVE 重试成功）；
  ④ 无 ETag 服务器（重探成功且空 etag）⇒ 放行兜底，重试仍无 `If`、`Overwrite: T`，且判据锁定
  「重探请求先于兜底 DELETE」。
- **修改** `WebDavSyncScenarioTest` 既有 409 兜底 enqueue 例 1 条：响应序插入「PROPFIND 重探
  （getetag 与 expectedEtag 一致 ⇒ 放行）」并新增「重探先于 DELETE」判据；原判据（首试带 `If`、
  重试不带 `If`、`Overwrite: T`、DELETE 针对目标、结果码成功）逐字保留。删除 0 例。

## 487.4 验证

### 宿主

- **定向单测**（`--tests` 五套：新增两套 + 相邻三套）→ `BUILD SUCCESSFUL`；按 §201 立规**回读 XML 确认在册**：
  `WebDavMoveOverwritePolicyTest tests=8 / WebDavOverwriteFallbackGuardTest tests=4 /
  WebDavSyncScenarioTest tests=32 / WebDavSyncProviderTest tests=18 / WebDavConflictProbeFailureTest tests=3`，
  全部 `skipped=0 failures=0 errors=0`。
- **全量单测**：`.\gradlew.bat test --max-workers=1` → `BUILD SUCCESSFUL`（2m 26s / 114 tasks：9 executed、105 up-to-date）。
  `python tools/doc/preserve_test_failures.py` → `failures=0`（无首轮红，无需留痕）。
  `python tools/doc/count_test_results.py` → `xml=548 tests=3573 failures=0 errors=0 skipped=15`
  （§486 基线 `xml=546 tests=3561` ⇒ **+2 XML / +12 例**：策略面 8 例 + 场景面 4 例；场景既有例为**修改**不计增）。
- **AC③ 复核**：`TransientHttpRetry` 及其「无条件写禁重试」口径逐字未动（本批未触碰
  `TransientHttpRetry.kt` 与 `executeTransientRetryable` 的 retryable 判定）。
- **AC④**：`已知工程限界.md` §28 补记「409/423 服务器类（未实测）」样本——行为由仓内 mock + RFC 4918/7232
  推导锁定，**非真实服务器执行结果**；残余窗口（重探确认未漂移后 DELETE 与重试 MOVE 之间的微小竞态）
  如实声明并列入该条解除条件。
- **门禁读数**（`python tools/doc/gate_readings.py` 原样粘贴）：

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/15] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=35  budget=35
[2/15] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/15] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/15] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 484 份；分册登记 486 条；全量索引 486 条；最大 §487）
[5/15] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 606 个测试文件
[6/15] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/15] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[8/15] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  多发射助手函数（顶层发射 ≥2 节点的 @Composable）：62 个  检查过的调用点：BoxScope 组件=61  框架 supportingText 槽=8  box_slot_stacked_sites=0
[9/15] tools/audit/check_launch_language_seed.py    EXIT 0  | === 启动语言种子接线机检（五条 fail-closed）===  [E1] MainApplication.onCreate 灌启动种子	ok  [E2] Compose 首帧 initialValue 取种子	ok  [E3] AppLocaleTracker.init 取种子（StringsProvider 首帧通道）	ok  [E5] AppLocaleTracker 慢路径补种（SP 无历史镜像时的兜底）	ok  [E4] RealSettingsRepository.setAppLanguage 镜像种子	ok  --- launch_language_seed=OK（五条接线齐全）---
[10/15] tools/doc/check_projection_read_safety.py    EXIT 0  | [check_projection_read_safety] 登记面文件 13/13；展示读口文件 16 个（白名单 16）；命中 0 处  [check_projection_read_safety] PASS
[11/15] tools/doc/check_raw_coroutine_scope.py       EXIT 0  | [check_raw_coroutine_scope] 检查文件 738 个（*/src/main/**）；允许清单 1 个；命中 0 处  [check_raw_coroutine_scope] PASS
[12/15] tools/doc/check_archive_monotonicity.py      EXIT 0  | [check_archive_monotonicity] 基线 HEAD~1：索引行 485 个 / 批次正文 483 份；现树 工作区：486 / 484；缺失 0 处  [check_archive_monotonicity] PASS
[13/15] tools/doc/check_plaintext_carrier_to_string.py EXIT 0  | [check_plaintext_carrier_to_string] 检查文件 738 个（*/src/main/**）；允许清单 0 个；命中 0 处  [check_plaintext_carrier_to_string] PASS
[14/15] tools/doc/check_message_not_in_user_text.py  EXIT 0  | [check_message_not_in_user_text] 检查文件 738 个（*/src/main/**）；文案槽 10 类；命中 0 处  [check_message_not_in_user_text] PASS
[15/15] tools/doc/check_cancellation_semantics.py    EXIT 0  | [check_cancellation_semantics] 检查文件 738 个（*/src/main/**）；命中 0 处  [check_cancellation_semantics] PASS
=== 汇总：15/15 PASS ===
```

### 设备侧

- `sync/src/androidTest/**` **未改动** ⇒ 依测试资产纪律无需真机实跑；本批全部改动落在
  `sync/src/main/java/com/keepasskey/sync/webdav/**`（Kotlin 逻辑 + KDoc）与 `sync/src/test/**`（宿主单测）。

## 487.5 如实声明

- **可选加固未采纳**：条目「可选加固：DELETE 带 `If-Match`」未实现。理由：① `delete` 通道签名不支持
  附加预条件头，需扩 Provider API 面；② 重探守卫已把窗口 A 由「整个上传时长无检查」收窄为「重探确认
  未漂移后的一次 RTT」，而窗口 B（DELETE 与重试 MOVE 之间）在该类服务器上**本无协议手段可封**
  （不支持对 MOVE 评估 `If` 正是兜底存在的前提），`If-Match` DELETE 只能进一步压缩窗口 A 的残量；
  ③ 不支持条件 DELETE 的服务器按 412 转冲突会把 Kp2a 兼容收益打折。残量已如实登记
  `已知工程限界.md` §28（409/423 服务器类条目），取得真实样本时可复评。
- **重探失败的 404 形态**：他端并发**删除**目标时重探得 404 → 与 P2-501 / S3 场景既有口径一致，
  按「探测失败」fail-closed 如实上抛，不折成「目标不存在可安全重建」——保守方向，宁误报不覆盖。
- **未改面**：412 冲突路径、`overwriteFlag` 判定、`createMoveRequest` 形态族、
  `WebDavConflictProbeFailureTest` 三例及 S3 侧全部逐字未动；本批仅动 409/423 兜底分支与其策略面。
- **K2 智能转换限制（过程缺陷留痕）**：纯 JVM 单测首版在跨语句 `assertTrue(is Drifted)` 后直接取
  `verdict.currentRemoteEtag` 报 `UNRESOLVED_REFERENCE`（K2 对测试编译单元中 internal 类型不延续
  跨语句智能转换），改用 `when` 主体判别式（与生产代码同型）后编译通过——非生产代码缺陷。
