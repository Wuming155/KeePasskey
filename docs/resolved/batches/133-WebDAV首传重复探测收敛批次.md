# §133 WebDAV 首传重复探测收敛批次（`ISSUE-P3-180`）

> **条目范围**：`ISSUE-P3-180`（WebDAV 单次上传最多 4 个往返：重复 PROPFIND）
> **完成时间**：2026-09-17
> **验证**：`.\gradlew.bat test --rerun-tasks --max-workers=1` → `BUILD SUCCESSFUL in 1m 51s`
> （114 actionable tasks executed），聚合 **`tests=2083 skipped=13 failures=0 errors=0`**
> （§132 为 2080，**+3** = 首传往返计数用例 3 例）。
> **审计闸门**：`bash tools/audit/check_recheck_consistency.sh` → `PASS`（1308 行 / 11 条禁用短语）。

---

## 1. 原条目（自 `ACTIVE_ISSUES.md` 原样收录）

- **背景**：`sync/.../webdav/WebDavSyncProvider.kt:272` 在 `expectedEtag == null` 时额外插一次 PROPFIND
  探测 `Overwrite`，而上游 `app/.../sync/SyncCycleRunner.kt:204` 的 `establishRemoteBaselineIfMissing`
  已经探过一次；MOVE 成功但响应无 ETag 时再 `getMetadata` 一次（`:333`）；MOVE 失败重试 `for (attempt in 0..1)`
  （`:296`）又各带一次 412 分支的 `getMetadata`（`:300`）⇒ 首传一次最多 4 个往返，弱网下每往返被 RTT 放大。
- **整改方向**：把上游已探测到的远端存在性 / ETag 经参数下传（或让 `uploadAtomic` 接受
  `remoteAbsent: Boolean?`），单次上传内的探测结果在一次调用内复用。
- **验收标准**：首传路径的 HTTP 往返次数下降（请求计数断言，可用 MockWebServer 计次）；
  条件写失败 / 412 / 无 ETag 四条分支的既有行为与错误语义回归全绿。
- **核实时间点与方式**：2026-09-17 读 `WebDavSyncProvider.kt:215-340` 与 `SyncCycleRunner.kt:195-215` 核实。
- **风险提示**：条件写是并发正确性的正确性来源（`已知工程限界.md` §1.3），
  减少往返**不得**削弱「用 ETag 预检 + `If-Match` 条件写」的判定，只删重复探测。

---

## 2. 本批实现（存在性结论参数化下传）

1. **`SyncProvider.uploadAtomic` 增第 4 个形参 `remoteExists: Boolean? = null`**（默认 null ⇒
   **既有调用点零改动**）；KDoc 写明语义：`null` = 未知（实现须自行探测），非 null = 调用方已探明、
   实现**不得**重复探测。
2. **`WebDavSyncProvider.uploadAtomic`**：`Overwrite` 判定改为
   `val exists = remoteExists ?: getMetadata(remotePath).isSuccess` —— 仅未知时才现探 PROPFIND。
   `If` tagged-list 条件写、MOVE 重试、回滚清理、412 → `ConflictError` **逐字未动**。
3. **`SyncEngine.commitLocal` 增可选形参 `remoteExists` 并透传**给 `uploadAtomic`。
4. **`SyncCycleRunner.establishRemoteBaselineIfMissing`**：在 `getMetadata` 返回 `FileNotFound`
   的分支上 `commitLocal(remotePath, localBytes, remoteExists = false)` —— 复用**那一次**探测的结论。
5. `SyncEngineTest` 的 provider 测试替身同步接受该参数并**记录**（`lastRemoteExists`），
   供上层断言「参数真实下传」（与既有 `lastExpectedEtag` 同一手法）。

**收益与范围（精确界定）**：首传路径从 `PUT + PROPFIND + MOVE`（+ 可能的 ETag 回退探测）
降为 `PUT + MOVE`——**少一次 PROPFIND，即少一个 RTT**。其余 5 处 `uploadAtomic` 调用点
（`commitLocalForce` / `openRemote` 各分支 / `markResolvedAndUpload`）**仍传 null** ⇒
行为与改动前完全一致（该结论对它们本就未知），**不得**读作「全链路上传往返均下降」。

---

## 3. 本批新增用例（3 例，**请求计数实测**）

`sync/src/test/.../scenario/WebDavSyncScenarioTest.kt`（复用既有 `StatefulDavDispatcher`
有状态 MockWebServer，其 PUT/MOVE 均返回 `ETag` ⇒ 不触发 `:333` 的 ETag 回退探测）：

| 用例 | 断言 |
|---|---|
| `场景11 已知远端不存在时首传不得再探 PROPFIND` | `uploadAtomic(…, remoteExists = false)` 的 `server.requestCount` 增量 **恰为 2**（PUT + MOVE） |
| `场景11 存在性未知时仍按原逻辑探测一次` | `remoteExists = null` 的增量 **恰为 3**（PUT + PROPFIND + MOVE）⇒ **对侧保守语义未被改动** |
| `场景11 已知远端已存在时覆盖上传同样不探测` | `remoteExists = true` 的增量为 2，且远端内容确实被覆盖为 `v1`（`Overwrite: T` 生效） |

⇒ 这是本批**最强的一类证据**：不是结构断言，而是**真实 HTTP 请求计数**的差值
（2 vs 3），且覆盖了「未知 ⇒ 仍探测」的负向对照。

**回归面**：`WebDavSyncScenarioTest` 其余 30+ 例（含并发竞态恰好一胜、MOVE 失败回滚幂等、
412 转 `ConflictError`、编码 / 特殊字符 / 空文件 / 5xx 与 401）与 `SyncEngineTest` 全绿。

---

## 4. 边界如实声明（不得外推）

1. **「少一个往返」是 MockWebServer 侧的实测请求计数，不是真机吞吐**：
   本批**未测真实网络 RTT**，也未量化弱网收益；**不得**据此宣称「同步更快 N%」。
2. **只有首传路径受益**（5 处调用点仍传 null，见 §2 末）；`commitLocalForce` /
   `markResolvedAndUpload` 等路径的 `Overwrite` 判定**仍各探一次 PROPFIND**（对它们该结论确实未知，
   属**未消化的既有成本**，未被本批登记为缺陷）。
3. **`remoteExists` 是可选且不校验的入参**：调用方若传错，WebDAV 会据此选 `Overwrite: F/T`——
   传错 `false` 而目标实际存在 ⇒ MOVE 得 412 ⇒ 转 `ConflictError`（fail-closed，不会静默覆盖）；
   传错 `true` 而目标不存在 ⇒ MOVE 得 404 ⇒ 抛错回滚（同样 fail-closed）。
   ⇒ 误传不会导致数据损坏，但会退化为一次失败重试。本批唯一生产调用方传的是**同一次 `getMetadata`
   的结论**，无漂移风险。
4. **`:333`（MOVE 成功但响应无 ETag 时回退一次 `getMetadata`）与 `:300`（412 分支取当前 ETag）
   未动**：前者依赖服务端是否返回 `ETag`（本仓 MockWebServer 恒返回，故用例覆盖不到该分支的往返），
   后者只在错误路径发生。**不得**读作「已消除全部重复探测」。
5. **未跑 `-DliveSyncTest` 真实联调**（需先起 `tools/local-sync`）、未跑设备侧与 `assembleRelease`。
6. **`SyncProvider` 接口签名变更的影响面**：全仓仅 `WebDavSyncProvider` 覆写该方法
   （S3 走接口默认实现 ⇒ 条件写 PUT，本就无存在性探测），测试侧仅 `SyncEngineTest` 的替身需同步
   ——三处均已改，其余调用点因**默认参数**零改动。

---

## 5. 过程缺陷与留痕

1. **本批生产代码一次通过编译**，未出现前几批那类 `old_string` 不匹配。
2. **全量单测撞上一处新的宿主侧偶发红（本会话第 6 次，且与既有两类不同）**：
   `sync` 模块 `SyncRollbackGuardTest > 摘要重载与字节重载的裁决逐项一致`（§125 引入的用例）失败，
   异常为 **`java.nio.file.AccessDeniedException`**，发生于 `SyncRollbackGuard.persist` 的
   **原子 rename**（`<hash>.rollback.<uuid>.tmp → <hash>.rollback`）——**Windows 上「覆盖已存在目标」
   的 rename 在句柄未完全释放 / 索引器与杀软扫描下会偶发拒绝**（POSIX/Android 无此语义）。
   **处置**：**定向复跑 8/8 绿**（`--rerun-tasks`），随后**全量复跑绿**（1m51s）。
   **未改任何断言、未改产品代码**：该现象属**宿主侧测试环境**（Android 侧 rename 语义不同），
   但按 `§7` / `§107` 对宿主侧残留的登记纪律，已登记为
   [`已知工程限界.md`](../architecture/已知工程限界.md) **§12**（事实 / 边界 / 依据 / 解除条件）。
   **触发条件观测**：隔离运行 8/8 绿、全量运行偶发 —— 与 `§7` 记的「文件系统 churn 放大」一致。
