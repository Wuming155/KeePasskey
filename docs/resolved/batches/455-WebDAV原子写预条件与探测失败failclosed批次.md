# §455 WebDAV 原子写兜底重试预条件与 412 探测失败 fail-closed 批次（2026-10-06）

**条目**：`ISSUE-P2-500` + `ISSUE-P2-501` **两条整条闭环**（P2 2 → **0**）
**来源**：`2026-10-06` 三类隐蔽性故障排查报告（[`../../records/三类隐蔽性故障排查报告_2026-10-06.md`](../../records/三类隐蔽性故障排查报告_2026-10-06.md)）；两条均为 **dormant**（零错误效果、前瞻性风险，无历史触发留痕）。

## 455.1 原始条目（原样收录）

### ISSUE-P2-500：`WebDavUploadAtomic` 409/423 兜底重试仍携带 `If` 预条件，与 KDoc / 批次 AC 承诺相反

- **核实时间点**：2026-10-06；**核实方式**：实读 `WebDavUploadAtomic.kt:47`（`fun createMoveRequest(withPrecondition: Boolean = true)`）、`:53-55`（`if (withPrecondition && !expectedEtag.isNullOrBlank())` 才发 `If` 头）、`:56-58`（else 分支仅发 `Overwrite`）、`:70`（**唯一调用点** `execute(...) { createMoveRequest() }`，用默认参 ⇒ `withPrecondition=true`）、`:91-94`（`attempt == 0` 时 `delete(remotePath)` 后 `continue`）、`:72-81`（412 → `ConflictError` → `:104-107` 抛出）；全仓 grep `withPrecondition` 仅 `:47` 形参与 `:53` 条件两处，**无人显式传 false**；实读矛盾 KDoc `WebDavSyncProvider.kt:322-323` 与 `WebDavMoveOverwritePolicy.kt:13`，对照批次 `359` AC（`docs/resolved/batches/359-…md:22/98`「DELETE 目标（404 容忍）→ **无预条件** + `Overwrite:T` 单次重试」）；实读仓内 mock 语义 `StatefulMockServers.kt:180-184`（`parseIfEtag` 不匹配即 412）与 `:200`（DELETE 移除 etag）；实读唯一走兜底的测试 `WebDavSyncScenarioTest.kt:288`（**未传 `expectedEtag`** ⇒ 无 `If` 头，恰好绕开缺陷组合）；`git show 844fe715:…` 确认缺陷自 `ISSUE-P2-381` 落地即存在，而该提交信息自称「DELETE 目标后无预条件重试」。
- **背景**：409 / 423 兜底删掉远端目标后的重试**仍带 `If` 预条件**——在按 RFC 4918 评估 `If` 的服务器上必 412 转 `ConflictError`，兜底退化为「先删远端目标 → 必败」。终态**比不兜底更差**（不兜底是 409 原样报错、远端完好），且报错文案「远端已被其他人修改」与事实（客户端自删）不符。常规提交路径传非空 etag（`SyncEngine.kt:312` / `:371` / `:486`），下轮可经 404 自愈（`:209-219`）。
  - **触发状态：dormant**——该缺陷路径（非空 expectedEtag + 409/423 + 重试）在仓内从未执行过：`已知工程限界.md` §28 实测矩阵 mod_dav / nginx 均不产生 MOVE 覆盖 409/423（nginx 对 MOVE 的 `If` 整体忽略，带 `If` 的重试在该类服务器反而照常成功），唯一走兜底的测试因未传 `expectedEtag` 绕开。
  - **未核实项（整改时须补）**：「严格评估 `If` 的真实服务器」无实证样本（§28 三类未测样本 Nextcloud / IIS / S3 同样未覆盖该组合）——「重试必 412」系读 mock 语义 + RFC 4918 推导，**非执行结果**。
- **涉及文件**：`sync/src/main/java/com/keepasskey/sync/webdav/WebDavUploadAtomic.kt:47-70`、`WebDavSyncProvider.kt:322-323`、`WebDavMoveOverwritePolicy.kt:13`、`sync/src/test/.../WebDavSyncScenarioTest.kt:288`。
- **验收标准**：
  ① 重 attempt 显式传 `createMoveRequest(withPrecondition = false)`，使实现兑现 KDoc / 批次 AC 承诺；
  ② 补负向样本（MockWebServer，仿 `StatefulMockServers.kt`）：`expectedEtag` 非空 + 首次 409 + 断言**重试请求头不含 `If`**（判据须同时看请求头与结果码——静默降级的结果码常是「成功」）；
  ③ 同步修正 `WebDavSyncProvider.kt:322-323` 与 `WebDavMoveOverwritePolicy.kt:13` 的 KDoc 口径（若二者已正确则只改实现）；
  ④ `sync/src/androidTest/**` 有改动则真机实跑 + `check_connected_device_results.py`；门禁 9/9 PASS。

### ISSUE-P2-501：WebDAV 合并链上 MOVE 412 后元数据重探瞬时失败会剥除乐观锁，静默覆盖他端写入

- **核实时间点**：2026-10-06；**核实方式**：实读 `WebDavUploadAtomic.kt:73-75`（`val currentMeta = getMetadata(remotePath).getOrNull()` / `remoteEtag = currentMeta?.etag.orEmpty()`，**探测失败 ⇒ 空串**）、`:40-45`（`overwriteFlag` 判定）与 `:53-58`（else 分支无 `If` 头）；实读 `SyncEngine.kt:403`（`ConflictNeedsMerge(remoteBytes, ex.remoteEtag)` **无回填**）对照 `:330/:340`（`uploadEx.remoteEtag.ifEmpty { remoteEtag }` **有回填**）——同文件两种口径；实读 `SyncConflictAutoMerge.kt:23-24`（`cleanEtag(conflictMomentEtag).ifEmpty { null }`）与 `:131`；实读 `SyncEngine.kt:486`（`expectedEtag?.takeIf { it.isNotBlank() }`）与 `:466-470` KDoc（「用当前值会通过校验并静默覆盖他端的更新……禁止在本方法内回退重探充当预条件」）；对照批次 `272` AC①（空白回退前提是「无 ETag 服务器」）；实读 `WebDavSyncProvider.kt:183-185`（无 ETag 服务器返回成功 + 空 etag，与探测失败**同折空**）。
- **背景**：MOVE 412 后的元数据重探一旦**瞬时失败**，`ConflictError.remoteEtag` 即被折算为空串，经 commitLocal 冲突分支（无回填）原样流入合并上传，被 `conflictUploadExpectedEtag` 折为 `null`，最终以**无 `If` 预条件**的 `MOVE(Overwrite:T)` 覆盖远端——在支持 ETag 的服务器上，仅因一次探测抖动就剥除合并窗口（含一次 KDF 级全库序列化）的乐观锁，他端窗口内写入被静默覆盖。空串把「无 ETag 服务器」与「探测失败」两种成因混为同一口径，而后者场景下**基线本可得**（被合并的远端内容刚下载成功）。
  - **触发状态：dormant**——非按构造必经：需 MOVE 412 后 `PROPFIND` 连同 `executeTransientRetryable` 内部重试一起失败（单次抖动被吸收），随后 commitLocal 下载与存在性探测又成功（后者失败则 `overwriteFlag="F"` 意外 fail-closed），且合并窗口内他端再写一次；全仓 grep 无 `ConflictError(remoteEtag = "")` 测试样本（`SyncCoordinatorTest.kt:472` 只锁定 ETag 可得时的透传）⇒ **该形态零测试覆盖、无触发留痕**。
  - **报告已修正候选的一处失真**：「仅 WebDAV 受累」不准确——`S3SyncProvider` 未覆写 `uploadAtomic`（走接口缺省），空期望合并上传会 HEAD 自探当前值并 `If-Match` 之，即 `SyncEngine.kt:466` 点名的「重探当前值 ⇒ 静默覆盖」形态，合并窗口锁同样被剥；S3 残余暴露更小（探测失败 fail-closed、绝不裸 PUT），但结论应改为「**两家同源受累，WebDAV 为最重形态**」。
- **涉及文件**：`sync/src/main/java/com/keepasskey/sync/webdav/WebDavUploadAtomic.kt:73-75`、`sync/src/main/java/com/keepasskey/sync/engine/SyncEngine.kt:403`（对照 `:330/:340`）与 `:466-470`、app 侧 `SyncCycleCommitPaths.kt:147` / `SyncConflictAutoMerge.kt:23-24/:131`。
- **验收标准**：
  ① 空串回退**只允许**在「无 ETag 服务器」成立时生效——探测失败（`getMetadata` 为 `Failure`）须与「成功但 etag 为空」区分，不得同折 null；
  ② commitLocal 冲突分支补齐与 openRemote 一致的 `ifEmpty` 回填口径（先按 `SyncEngine.kt:466-470` KDoc 判定该回填是否与 `ISSUE-P1-275` AC① 冲突——若冲突，改在 `WebDavUploadAtomic` 侧让探测失败**显式失败**而非折空）；
  ③ 补 MockWebServer 负向样本：412 后 `PROPFIND` 持续失败，断言合并上传**携带 `If` 头**；
  ④ 复核 S3 侧同源面并给出与 WebDAV 一致的处置；
  ⑤ 门禁 9/9 PASS。

## 455.2 前提复核（2026-10-06 直读）

- **P2-500 前提成立**：`createMoveRequest(withPrecondition: Boolean = true)` 形参存在；唯一调用点用默认参（`withPrecondition=true`）；409/423 兜底在 `attempt == 0` 做 `delete(remotePath)` 后 `continue`；矛盾的两处 KDoc（`WebDavSyncProvider.kt` 事务写 KDoc §3、`WebDavMoveOverwritePolicy.kt` 语义段）实读确为「无 If 预条件、`Overwrite:T`」——**KDoc 正确、实现相反**。
- **P2-501 前提成立**：三处 412 处理点均以 `getMetadata(...).getOrNull()` + `?.etag.orEmpty()` 折空（`WebDavUploadAtomic` / `WebDavSyncProvider.upload` / `S3SyncProvider.upload`）；`WebDavSyncProvider.getMetadata` 对无 ETag 服务器返回 **Success + 空 etag**（`:183-185` 的 `ifBlank` 回退）⇒「探测失败」与「无 ETag 服务器」当前同折空、不可分。

## 455.3 整改

### ① P2-500：兜底重试改走无 `If` 形态（并关停其传输级重试）

`WebDavUploadAtomic.run` 引入局部 `withPrecondition`（初值 `true`）：在 409/423 分支 `delete(remotePath)` 之后置 `false`，重试请求经 `createMoveRequest(withPrecondition = withPrecondition)` 构造 ⇒ **重试不再发送 `If`**（仅 `Overwrite:T`），兑现 KDoc / 批次 `359` AC 承诺。

- `execute(...)` 的 `retryable` 位与「请求**实际**携带服务器预条件」逐字对齐：`carriesPrecondition = requestWithPrecondition && !expectedEtag.isNullOrBlank()`（ISSUE-P3-298 ③：无条件写禁重试）。409/423 兜底重试（`withPrecondition=false`）因此 `retryable=false`；首试（携带 `If`）仍 `retryable=true`。
  > **与任务口径的偏差与理由**：任务提示的近似式 `attempt == 0 && !expectedEtag.isNullOrBlank()` 会把「首试**抛异常后**的外层 attempt 1」也判为不可重试——但该路径未 `DELETE` 任何目标、请求**仍携带 `If`**（预条件由服务端逐次重验），属条件写，按 ISSUE-P3-298 ③ 应允许传输级重试。故取**按请求实际头**判定的精确式，而非按 attempt 序号。
- `AC③`：两处 KDoc 已**正确**（`WebDavSyncProvider.kt:320-324`「再单次重试 MOVE（无 If 预条件、`Overwrite:T`）」、`WebDavMoveOverwritePolicy.kt:13`「DELETE 目标（404 容忍）后单次无预条件重试」）⇒ **未改**，只改实现（AC③「若二者已正确则只改实现」）。

### ② P2-501：412 后元数据重探**失败**与「无 ETag 服务器」分型，失败即 fail-closed

三处 412 处理点统一改为：取 `metaResult = getMetadata(remotePath)`，`metaResult.getOrNull() == null`（**探测失败**）即上抛 `metaResult.exceptionOrNull()`（兜底虚构 `NetworkError`），**不再**折成 `remoteEtag=""` 的 `ConflictError`；`getOrNull()` 非空（含 **Success + 空 etag** 的无 ETag 服务器）时行为**逐字不变**（`ConflictError(remoteEtag = <可能为空的 etag>)`）。

- `WebDavUploadAtomic`：新增 `probeFailure` 位；命中即 `break`，循环后 `if (!moveSuccess) { delete(tmpPath); probeFailure?.let { throw it } ... }`——**临时文件在既有 `!moveSuccess` 清理块内一并删除**，探测失败不留远端残骸。
- `WebDavSyncProvider.upload`（非原子 `upload` 的 412 分支）与 `S3SyncProvider.upload`（412 分支）：就地 `?: throw (...)`，如实上抛探测失败。
- 上层收敛：`SyncEngine.commitLocal` 见非 `ConflictError` ⇒ 走既有 `else` 分支 ⇒ `RemoteUnreachable(keptLocal = true)`（本地缓存已保留，下轮 `openRemote` 重探得真实基线后重入完整冲突流程）。

**AC②（回填口径）判定**：**不采「commitLocal 冲突分支补 `ifEmpty` 回填」**。依据 `SyncEngine.kt:461-470` KDoc——`markResolvedAndUpload` 的 `expectedEtag` 必须用**冲突时刻**记录的 ETag，**禁止**在本方法内回退重探当前值充当预条件（`ISSUE-P1-275` AC①）。`commitLocal` 冲突分支处**手上没有当前远端 etag**（作用域内只有 `state?.etag`＝陈旧**基线**，用它做预条件会恒 412），且重探被 KDoc 明令禁止 ⇒ 回填不可行。故正确处置落在 **`WebDavUploadAtomic` 侧让探测失败显式失败**（AC② 的分支二），从源头杜绝空串流入。

### ③ 测试资产

- **新增** `sync/src/test/java/com/keepasskey/sync/scenario/WebDavConflictProbeFailureTest.kt`（3 例）：
  - 端到端（`SyncEngine.commitLocal` + 真实 `WebDavSyncProvider` + `StatefulDavDispatcher`）：MOVE 412 后 PROPFIND **持续失败** ⇒ `RemoteUnreachable`；他端内容与 ETag 不被覆盖；唯一一次 MOVE 仍带 `If`（**无无锁 MOVE**）；临时文件清理。
  - Provider 层：412 + 探测失败 ⇒ 上抛**非** `ConflictError` 的探测失败（`ProtocolError`），他端内容不被覆盖、临时文件清理。
  - 正确路径保留：412 + **无 ETag 服务器**（Success + 空 etag）⇒ 仍归 `ConflictError(remoteEtag="")`。
- **新增** `WebDavSyncScenarioTest` 1 例（P2-500 负向）：`expectedEtag` 非空 + 首试 MOVE 409 ⇒ 断言**首试 MOVE 带 `If`、兜底重试 MOVE 不带 `If`**、重试 `Overwrite:T`、DELETE 针对目标、**结果码成功**（判据同时看请求头与结果码）。
- **修改** `S3SyncScenarioTest` 1 例（P2-501 AC④ 的 S3 同源面）：原 `场景1 一端编辑一端删除 条件写412转ConflictError`（412 后第二次 HEAD **404**）随口径改为 `…412后元数据探测404不折空为冲突`——断言如实失败（`FileNotFound`）而非伪造带空 etag 的 `ConflictError`；请求序（HEAD/PUT/HEAD）与「不静默覆盖」不变量不变。删除 0 例。
- **测试支撑** `StatefulMockServers.kt`：`StatefulDavDispatcher` 新增 `failAllPropfind` 故障注入开关（PROPFIND 恒 500）。

## 455.4 验证

### 宿主

- **全量单测**：`.\gradlew.bat test --max-workers=1` → `BUILD SUCCESSFUL`（2m 13s / 114 tasks：6 executed、108 up-to-date）。
  `python tools/doc/count_test_results.py` → `xml=521 tests=3393 failures=0 errors=0 skipped=13`（§454 基线 `xml=520 tests=3389` ⇒ **+1 XML / +4 例**：P2-501 新类 3 例 + P2-500 场景 1 例；S3 场景 1 例为**修改**不计增）。**无 `Assume` 跳过**。
- **重言断言机检**：`python tools/audit/check_tautological_assertions.py` → `汇总：命中 0 处 / 扫描 579 个测试文件`，退出码 0。
- **门禁读数**（`python tools/doc/gate_readings.py` 原样粘贴）：

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/9] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=35  budget=35
[2/9] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/9] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/9] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 453 份；分册登记 455 条；全量索引 455 条；最大 §455）
[5/9] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 579 个测试文件
[6/9] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/9] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[8/9] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  多发射助手函数（顶层发射 ≥2 节点的 @Composable）：62 个  检查过的调用点：BoxScope 组件=61  框架 supportingText 槽=8  box_slot_stacked_sites=0
[9/9] tools/audit/check_launch_language_seed.py    EXIT 0  | === 启动语言种子接线机检（五条 fail-closed）===  [E1] MainApplication.onCreate 灌启动种子	ok  [E2] Compose 首帧 initialValue 取种子	ok  [E3] AppLocaleTracker.init 取种子（StringsProvider 首帧通道）	ok  [E5] AppLocaleTracker 慢路径补种（SP 无历史镜像时的兜底）	ok  [E4] RealSettingsRepository.setAppLanguage 镜像种子	ok  --- launch_language_seed=OK（五条接线齐全）---
=== 汇总：9/9 PASS ===
```

- **`check_md_links.py`**：`BROKEN_MD_LINKS=0`（见上表 [3/9]）。
- **索引一致性**：`python tools/doc/check_resolved_index_sync.py` → `RESOLVED_INDEX_SYNC=OK`（见上表 [4/9]）。

### 设备侧（AC④）

- `sync/src/androidTest/**` **未改动** ⇒ 依 AC 无需真机实跑；本批全部改动落在 `sync/src/main/**`（Kotlin 逻辑）与 `sync/src/test/**`（宿主单测）。

## 455.5 如实声明

- **未核实项（沿用条目）**：「严格评估 `If` 的真实服务器」仍无实证样本——P2-500 的「重试必 412」由**仓内 mock 语义 + RFC 4918 推导**在负向样本中锁定（首试带 `If`、重试不带 `If`），**非真实服务器执行结果**。
- **修正 S3 既有用例语义（如实登记）**：`场景1 一端编辑一端删除 条件写412转ConflictError` 的 412 后元数据探测由「折空为 `ConflictError`」改为「如实上抛 `FileNotFound`」，系 P2-501 一致处置的**直接后果**；该用例覆盖的「绝不静默覆盖」不变量由新断言（失败 + 请求序）继续锁定，并有 WebDAV 侧同类 fail-closed 用例互为等价覆盖。
- **未改面**：`SyncEngine.commitLocal` / `markResolvedAndUpload` / `SyncConflictAutoMerge` / app 侧提交路径**逐字未动**（仅由 Provider 侧返回值类型变化驱动既有分支）。
- **KDoc 未改（AC③ 判定）**：`WebDavSyncProvider.kt` 事务写 KDoc 与 `WebDavMoveOverwritePolicy.kt` 语义段本就与整改后实现一致，按 AC③「若已正确则只改实现」保留原文。
