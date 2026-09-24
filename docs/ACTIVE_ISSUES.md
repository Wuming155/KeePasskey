# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。
> **只放现存问题**：本文件**只保留尚未闭环的条目**——已闭环条目的正文、批次流水与索引段一律不留；历史实现与验收证据以 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 `docs/resolved/batches/` 为单一真相源。
> **实施前必读**：源自安全复核的条目，其 AC 已按 [`SECURITY_RECHECK_2026-09.md`](security/SECURITY_RECHECK_2026-09.md) §10 / §11 的**第四轮独立复核定版结论**逐条对齐，更正口径已内联到对应条目——照条目原文 AC 实施者可能**降低安全性**或**引入数据损坏**。
> **例外（`ISSUE-P1-276` ～ `ISSUE-P3-299`）**：这批 2026-09-23 条目出自**两轮全仓审查**（8 个铺开代理 + 7 个对抗复核代理，反向口径＝先假设误报再取证），不属上述复核范围；其依据为各条「核实方式」所记的逐行反校与对抗推翻结论。凡条目标注「**未经对抗轮单独攻击**」或「**需外部证据（真机 / 真实 DAV 矩阵 / 官方对拍）**」者，认领时须按规则 6.1② 先自行复核前提，不得直接开工。
> **闭环纪律**：任务完成后，将该条目从本文件**整条移入** [RESOLVED_LOG.md](RESOLVED_LOG.md)（加一行索引 + 新增批次正文），并执行 `git commit & push`。

---

## 条目维护规则（ISSUE-P3-28 确立）

1. **新增条目必须附「核实时间点」与「核实方式」**：任何声称「某文件需要删除 / 某处没有消费方 / 某硬编码为 0」一类**关于代码库现状的前提**，都必须写明**何时、以何种手段**核实过，例如「2026-09-10 经 `Test-Path` 核实」「2026-09-10 经全仓 `grep maskPasswordsDefault` 核实（仅设置页回显）」。
   > 立规缘由：条目与代码库演进之间存在时间差，曾出现正文前提在开工时**已不成立**（文件早已删除、引用早已清理），致执行者去做已经完成的工作。
2. **开工前复核前提**：认领条目时先复核其正文前提（路径是否存在、行号是否漂移、消费方是否已出现）。前提已不成立的，**就地修正或显式标注**后再动手；完全无对象可改的条目应直接归档并注明原因。
3. **行号一律视为「核实时刻的快照」**：正文内引用的 `文件:行号` 仅作定位提示，实现前须以真实内容为准。

---

## 优先级定义

| 等级 | 严重度与类型 | 处理原则 |
|:---:|---|:---:|
| **P0** | **阻断级 / 致命安全漏洞**（数据损坏、篡改未拦截、明文泄露、严重崩溃） | 立即停止其他工作优先修复 |
| **P1** | **高危缺陷 / 核心功能阻断**（权限失效、关键契约异常、核心流程失败） | 最高优先级排期修复 |
| **P2** | **中危缺陷 / 协议互操作 / 性能瓶颈 / 测试有效性缺口** | 正常排期，按批次连续解决 |
| **P3** | **低危项 / 进阶特性接线 / 代码整洁度 / 评估与体验优化** | 渐进优化与特性补齐 |

---

## P0 阻断级问题（0 项）

> **暂无开放项**。

---

## P1 高危与核心功能问题（0 项）

> **暂无开放项**。

---

## P2 中危缺陷与协议/测试缺口（5 项）

> 本批 5 项出自 2026-09-24 同步/加密/passkey 安全审计（逐行反校 + 4 并行核查代理），均为数据完整性 / 密钥残留类，按「库永不丢、永不自己搞坏」定位列为 P2（Tier 0 优先整改）。

### ISSUE-P2-308：同步采纳失败后引擎状态不回滚 → 整库回滚跨端传播（★）

- **核实时间点**：2026-09-24（本轮审计，逐行反校）。
- **核实方式**：并行核查代理直读 `SyncEngine.kt` / `SyncCycleRemoteOutcomes.kt` / `SyncCycleCommitPaths.kt` 并 quote 实际代码；行号漂移已校正（原引 233-240 实为 `openCachedWithoutLocalChanges` 分支 commit 在 234、updateBase 在 236；83-90 实为 `return SyncOutcome.Error` 而非「仅日志」）。
- **背景与根因**：引擎在把 `RemoteSynced` 交还 app 层**之前**即已执行 `receipt.commit → updateBase → writeBaseContent → recordAccepted`（`SyncEngine.kt:233-240` 区间，`openCachedWithoutLocalChanges` 分支；同模式亦见于 `openUncached:173-175`、`openCachedWithLocalChanges:290-291`），即 base 已在「app 采纳」之前前移并写盘。采纳 / 应用远端失败（PARSE_FAILED / SAVE_FAILED / SESSION_DIVERGED）在 `SyncCycleRemoteOutcomes.kt:73-83` 仅 `return SyncOutcome.Error`，全仓无回滚引擎状态的函数。下一周期 `SyncCycleRemoteOutcomes.kt:38-40` 的 `contentEquals` 短路把陈旧内存树记为 `lastSyncedDb`。`SyncCycleCommitPaths.kt:83-93` 上传成功后本地 `save()` 失败仅 `return Error`，base 已前移不回退。后果：此后本地编辑以 `V_prev+edit` 整库上传，他端改动被覆盖；每次保存重生成 masterSeed/IV/KDF salt ⇒ 字节摘要全新，`SyncRollbackGuard` 仅比对字节级 sha256（`SyncRollbackGuard.kt:134-142` 比对 `sha256Hex(content)`）⇒ 各端判 Accept。**无需攻击者、无需服务器配合**即可跨端灭数据。残余（如实登记）：干净锁库经 `SyncCacheEvictor`（SessionLockObserver）清缓存自愈 ⇒ 窗口＝不锁库继续用 / 进程被杀（无冷启动 cacheDir 清理）。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/sync/SyncEngine.kt`、`SyncCycleRemoteOutcomes.kt`、`SyncCycleCommitPaths.kt`、`SyncRollbackGuard.kt`。
- **验收标准**：AC① 把引擎侧三步落地（updateBase / writeBaseContent / recordAccepted）延后到 app 层采纳确认之后；采纳失败须回滚或保持 base 不动，不得前移。AC② 沿用 `SyncMidCycleEditGuardTest`，追加第三轮 `runSyncCycle` 断言 provider 侧对象字节仍含远端新增条目（即接受失败后重试不丢失对端数据）。AC③ `test` 全绿 + `gate_readings.py` 7/7 PASS。

### ISSUE-P2-309：三方合并 base 取工作副本兜底，违反同函数注释明令禁止（○）

- **核实时间点**：2026-09-24（本轮审计直读复核）。
- **核实方式**：主代理直读 `SyncCycleSetup.kt:154-157` 与 `SyncConflictController.kt:333` / `SyncConflictResolution.kt:209`，确认注释与代码矛盾及另两处无兜底；「零测试锁定」未专门核实测试面。
- **背景与根因**：`SyncCycleSetup.kt:154-156` 注释明令「本地缓存会被工作副本反复覆盖，绝不能再兼任 base 内容来源——否则冲突会话中断后 base 会被本地修改版污染，后续合并退化为远端全胜」；但紧接 `:157` `val baseSnapshotBytes = syncCache.readBaseContent(remotePath) ?: cachedSnapshotBytes` 正是用工作副本（`cachedSnapshotBytes = readCache`）兜底 base。`SyncConflictController.kt:333` 与 `SyncConflictResolution.kt:209` 调 `readBaseContent` **无兜底**，三处不一致。后果：`.basecache` 单文件被 cacheDir 回收且本地确有编辑时，共同祖先被上一版工作副本顶替 ⇒ 远端字段被「local==base」判走远端，本地编辑静默丢弃。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/sync/SyncCycleSetup.kt`、`SyncConflictController.kt`、`SyncConflictResolution.kt`。
- **验收标准**：AC① 删 `SyncCycleSetup.kt:157` 的 `?: cachedSnapshotBytes`，`readBaseContent` 返回 null 即走 F2 空库并集（宁多冲突不丢数据）。AC② `SyncConflictController.kt:333` / `SyncConflictResolution.kt:209` 维持无兜底（与修复后 setup 一致）。AC③ 补一条覆盖「.basecache 缺失 + 本地有编辑」的合并用例，断言本地编辑不被静默丢弃。`test` + `gate_readings.py` 7/7 PASS。

### ISSUE-P2-310：附件被系统回收后 fail-open 返空字节 → 保存产出矛盾内层头 → 整库打不开（○）

- **核实时间点**：2026-09-24（本轮审计，逐行反校）。
- **核实方式**：并行核查代理直读 `FileBinaryStore.kt:52` / `InnerHeader.kt:58-59,119-125,175` / `SessionOpener.kt:181-182`；并 Grep 确认 `sizeOf(` / `cacheSize` 生产侧零调用方（仅测试 fake 调用）。
- **背景与根因**：`FileBinaryStore.kt:52` `cache.readCache(key) ?: ByteArray(0)` 与 `:54-55` 的 `openStream() ?: ByteArrayInputStream(ByteArray(0))` 均为 fail-open。`InnerHeader.kt:58-59` 落盘 `size` 恒取解析期固定的 `spilledSize`；`:175` 写字段长 `bin.size + FLAGS_FIELD_BYTES`；`:119-125` `writeTo` 对 spill 分支直接 `openStream().copyTo`，无长度校验。被系统回收后 store 返空流 ⇒ 写出 0 字节但头部仍声明 `spilledSize+1` ⇒ 内层头长度自相矛盾；下次打开 `InnerHeader.kt:376-397` 按声明长度消费后续字节 ⇒ 整段内层头错位 ⇒ `KdbxCorruptFileException`。不是丢一个附件，是整库打不开。默认 `.bak` 仅一代、关备份即不可恢复。根因：新增 save 前 `sizeOf(key)==spilledSize` 校验所需的 `sizeOf` / `cacheSize` 原语生产侧零调用。
- **涉及文件**：`database/.../inner/InnerHeader.kt`、`FileBinaryStore.kt`、`SessionOpener.kt`。
- **验收标准**：AC① `serialize` 前校验 `sizeOf(key) == spilledSize`，不等抛类型化异常（保存 fail-closed，**不动读侧**）。AC② 需真机取证（AVD Pixel_10，禁实体机）：含 5 MiB 附件库 → 设置→清空缓存（不 force-stop）→ 编辑保存，断言保存被类型化异常拦截而非产出矛盾头。AC③ `test` + `gate_readings.py` 7/7 PASS。

### ISSUE-P2-311：自产 64 MiB 附件被自家解析器判损坏（写读两侧对同一常量口径互斥）（★）

- **核实时间点**：2026-09-24（本轮审计，逐行反校）。
- **核实方式**：并行核查代理直读 `AttachmentSizeLimits.kt:21` / `EntryEditPickers.kt:123,135` / `InnerHeader.kt:175,212,231-232,357,447` / `KdbxFile.kt:86`，并确认绿测 `InnerHeaderSecurityTest` / `KdbxInnerResourceLimitsInvariantTest` 断言全在读侧。
- **背景与根因**：`AttachmentSizeLimits.kt:21` `MAX_ATTACHMENT_BYTES = 64 MiB`。`EntryEditPickers.kt:123` 与 `:135` 两道关卡均用 `>` ⇒ 恰好 64 MiB 放行。写侧 `InnerHeader.kt:175` 写字段长 `bin.size + FLAGS_FIELD_BYTES = 64 MiB + 1`；`MAX_INNER_FIELD_BYTES = KdbxFile.MAX_DECOMPRESSED_PAYLOAD_BYTES(128 MiB) / FIELD_CAP_SHARE_DIVISOR(2) = 64 MiB`（`:212 / :231-232`）；读侧 `:357` 用 `>` 判 ⇒ 下次打开整库判损坏。**写侧零累计闸**：`MAX_BINARY_POOL_TOTAL_BYTES`（`:280-281` = 128 MiB）仅活在读侧 `:447` 与 init 断言 ⇒ 3×50 MiB 附件同样写得出、读不进。绿测 `InnerHeaderSecurityTest` / `KdbxInnerResourceLimitsInvariantTest` 断言全在读侧/常量层，把矛盾固化。
- **涉及文件**：`database/.../inner/InnerHeader.kt`、`AttachmentSizeLimits.kt`、`app/.../EntryEditPickers.kt`、`KdbxFile.kt`。
- **验收标准**：AC① UI 上界取 `MAX_INNER_FIELD_BYTES − 1` 并同源派生（消除写读互斥）。AC② `serialize` 前加池累计判据（单值同读侧 `MAX_BINARY_POOL_TOTAL_BYTES`）。AC③ 参数化 save→load 往返测试：UI 上界值（含 64 MiB−1、多附件累计临界）必通。`test` + `gate_readings.py` 7/7 PASS。

### ISSUE-P2-312：useCredentials 借出主密码/密钥文件克隆从不归零（漏擦，对齐 P2-290 标尺）（★ 待定级→P2）

- **核实时间点**：2026-09-24（本轮审计；定级由「待定级」改判 P2）。
- **核实方式**：并行核查代理直读 `SessionCredentialCache.kt:28-32` / `SyncDatabaseCodec.kt:30-31,42-43` / `SessionExternalParser.kt:24-25,40-41` / `KdbxFile.kt:401-404`；并核实先例 `RESOLVED_LOG.md:371`（ISSUE-P2-290，compositeKey/口令明文漏擦，P2）与 `resolved/batches/238-待决冲突解析树身份判定擦除批次.md:35`（ISSUE-P3-119 判「不可擦而非漏擦」）。
- **背景与根因**：`SessionCredentialCache.kt:28-32` 的 `useCredentials` 只 clone 并传给 block，**自身无 finally 归零借出克隆**，契约靠 KDoc「调用方用毕必须显式清零」。两生产调用点（`SyncDatabaseCodec.kt:30-31/42-43`、`SessionExternalParser.kt:24-25/40-41`）仅擦内层再克隆；`KdbxFile.kt:401-404` 的 finally 只擦派生 `cipherKey/hmacKey64`，**不擦入参 `passwordChars/keyFileData`** ⇒ 借出的外层克隆全程未归零，主密码/密钥文件明文克隆滞留内存至 GC。此为**漏擦**（可清零而未清），形状与 `ISSUE-P2-290`（口令明文字节漏擦）同型 ⇒ 定 P2；与 `ISSUE-P3-119`（判「不可擦」）性质不同，上一轮引其作证属误引。
- **涉及文件**：`database/.../session/SessionCredentialCache.kt`、`SyncDatabaseCodec.kt`、`SessionExternalParser.kt`、`KdbxFile.kt`。
- **验收标准**：AC① 在 `useCredentials` 的 `block` 调用后加 `finally` 清零借出的 `pwdClone/keyClone`（2 行级修复）。AC② 补用例断言借出克隆在 block 返回后内存被清零（单元断言 clone 内容在 `useCredentials` 返回前被 `fill`）。AC③ `test` + `gate_readings.py` 7/7 PASS。

---

## P3 低危问题、特性接线与体验优化（5 项）

### ISSUE-P3-300：弱 ETag 乐观锁的**真实 DAV 服务器矩阵未实测**——弱 ETag 服务器上的同步收敛行为待证（§272 AC⑤ 显式残余）

> 本条**不属**上文「`ISSUE-P1-276` ～ `ISSUE-P3-299`」审查批例外范围：它由 §272（`ISSUE-P1-275` 闭环批）
> 的 AC⑤ 未执行残余升级为待办承接，与 [`已知工程限界.md`](architecture/已知工程限界.md) §28 互为指针。

- **核实时间点**：2026-09-23（§272 整改当日，随批如实登记）。
- **核实方式**：① §272 已完成的证据面＝RFC 全文核对（RFC 7232 §2.3 / §3.1、RFC 4918 §8.6 / §10.4.2 / §10.4.4 / §10.4.9，rfc-editor.org 原文）+ 有状态 mock 的头形态与弱比较裁决守卫（`StatefulDavDispatcher.weakEtagPaths`）——**协议层与形态层已证**。② 条目 AC⑤ 要求的**真实服务器矩阵**（Apache/mod_dav、nginx-dav、Nextcloud、IIS）当日无环境可用；`tools/local-sync` 联调链路代理侧不可运行（既有口径）且 `run_webdav.py` 不产 ETag，本就不构成弱面取证 ⇒ **未执行**，登记于批次文档 §3.3 与限界表 §28。
- **背景与根因**：§272 弱 ETag 收口选定「回传服务器签发原形态」取向（MOVE `If` 头携 `W/"…"`；`If-Match` 弱期望不发送；S3 弱期望 fail-closed）。理论边界已经 RFC 划清：**弱比较**服务器上原形态必匹配；**强比较 × 弱存储标签**的组合下任何客户端的实体标签预条件均不可满足（RFC 7232 §2.3 强比较要求两侧均非弱），属服务器自绝于条件写，本仓退化为 412 → 冲突重检（不静默覆盖、不丢数据，但同步可能反复提示冲突）。**未证的是**：真实服务器对 RFC 4918 §10.4.4「弱或强比较二选一」的实际取向、以及 MOVE 事务写在弱 ETag 服务器上的兼容性——这决定弱 ETag 服务器（Apache/mod_dav 部分文件系统配置为高发面）上同步是正常收敛还是反复冲突提示。
- **涉及文件**：无生产代码改动面（纯外部验证条目）；实测结果回填 `docs/resolved/batches/272-冲突时刻ETag透传与弱校验收口批次.md` §3.3 与 `docs/architecture/已知工程限界.md` §28。
- **验收标准**：AC① 四类服务器各实测三项读数并按规则 8 留证（命令 + 原始响应）：签发 ETag 的强/弱形态；MOVE `If` 头对弱形态（`[W/"…"]`）与强形态（`["…"]`）预条件的接受性；PUT+MOVE 事务写兼容性。AC② 实测**证实**「回传原形态」取向 ⇒ 回填两处登记并闭环本条；实测**推翻** ⇒ 不得就地放宽 §272 守卫用例（限界表 §28 边界条款），须另行立条裁决新取向。AC③ 无法取得的环境（如 IIS）逐项如实标注未执行，禁以 mock 绿推定闭环。AC④ 环境不可得期间，本条与限界表 §28 维持开放，不得归档。

---

### ISSUE-P3-302：大库下拉刷新的**主线程阻塞量级未实测**（§274 AC④ 显式残余）

- **核实时间点**：2026-09-23（§274 整改当日，随批如实登记；与 `ISSUE-P3-300` 同型）。
- **核实方式**：① §274 已完成的证据面＝**调度边界层**：`SyncAssemblyOffMainThreadTest` 以五个探针（栈归因 + 非空性断言 + 线程身份断言）锁定装配段四类重活不在主线程执行，判别力实验（撤销下沉 ⇒ 五类探针全落主线程基准即红、恢复即绿）证明守卫紧扣缺陷本身；② 条目 AC④ 要求的**真机量级读数**（大库下拉刷新的帧耗时 / 主线程阻塞时长）当日本会话**无设备**，未执行任何真机或 `connected` 用例 ⇒ **未执行**，登记于批次文档 §2.4 与 §3.4。
- **背景与根因**：§274 的整改依据「大库下为**数百毫秒至秒级**」是**估算**（同一主线程上做 Keystore 解密 + 整库密文读 + 全库逐字段比较 + 整库写 + `fd.sync()` 的量级推理，非本机实测）。按定义整改后主线程应只剩一次 stat，但**未证的是**：真实大库（万级条目 / 含附件）下装配段下沉后的实际主线程阻塞与帧耗时，以及把该段整体挪到 `Dispatchers.IO` 后对**同步总时长**的影响（阻塞被移走，该段本身仍须执行完）。
- **涉及文件**：无生产代码改动面（纯外部验证条目）；实测结果回填 [`resolved/batches/274-同步周期装配段下沉IO批次.md`](resolved/batches/274-同步周期装配段下沉IO批次.md) §2.4 / §3.4，并按需登记 [`architecture/已知工程限界.md`](architecture/已知工程限界.md)。
- **验收标准**：AC① 接入设备后构造**大库**（万级条目，含 ≥1 MiB 落盘附件与历史快照）实测下拉刷新：记录装配段耗时、主线程最长连续阻塞、掉帧数与同步总时长，并按「量级须实测」留证（工具 + 原始读数）；AC② 实测**证实**「主线程无同步重活」⇒ 回填批次 §2.4 / §3.4 并闭环本条；实测**推翻**（仍见秒级主线程阻塞）⇒ 另行立条定位残余阻塞点（含 `ISSUE-P3-301` 的 stat 面与 `SyncCache` 之外的调用），**不得**就地改判 §274 的守卫口径；AC③ 无设备期间本条维持开放，**不得**以 §274 的 JVM 守卫绿推定量级已证；AC④ 若同批接入设备，与 `ISSUE-P3-300`（真实 DAV 矩阵）一并执行以减少设备占用。AC⑤ **并入** `ISSUE-P2` 审计项「404 恢复分支未下传 remoteExists」的同目标并发实验，按本仓目标后端分两类：**(a) 标准 WebDAV（RFC 4918 合规实现，即 mod_dav 类参考实现口径）**——对「本地已 PUT、他端并发创建」的目标，第二客户端以 `MOVE(Overwrite:F)` 期望 412，验证 `SyncEngine.kt:190-198` 404 恢复分支缺省 `remoteExists` 导致的 TOCTOU 在真实服务器上的实际效力（即强制 `Overwrite:F` 是否真被尊重、412 是否返回；若服务器忽略 `Overwrite:F` 静默覆盖，则该 TOCTOU 为真实残余，需换用 `If` 预条件而非 Overwrite 头）；**(b) 兼容 S3 协议存储**——S3 路径走原子条件 PUT（`If-None-Match:*` 首传 / `If-Match` 覆盖，`S3SyncProvider.kt:32` KDoc 已声明「无 TOCTOU 竞争窗口」），本就不存在 MOVE+Overwrite 竞争，故**无需 Overwrite:F 实验**；实验只需验证目标 S3 兼容端**是否真正尊重条件写头**（少数非合规实现会忽略 `If-Match`/`If-None-Match:*`，见 `S3SyncProvider.kt:239`），以「对**已存在**对象 PUT `If-None-Match:*` 期望 412 而非 200」一轮即可。注：Nextcloud 等具体产品端点不在本仓目标范围，不纳入实验矩阵。
---

### ISSUE-P3-310：同步层卫生批量登记（3 项）（「建议并表，不逐条占位」）

> 本条目合并 3 个低危同步卫生发现，逐项附核实结论与验收要点；其中「404 恢复分支 TOCTOU」已并入 `ISSUE-P3-300` AC⑤ 实验面，不在本条目单列。

- **核实时间点**：2026-09-24（本轮审计，逐行反校）。
- **核实方式**：并行核查代理直读 `SessionExternalParser.kt:35-43` / `SyncCache.kt:137-144` / `SyncEngine.kt:194,262,290` / `SyncCycleRunner.kt:203` 等，并比对 `SyncOutcome.kt:35` 与 `SyncException.ProtocolError` 站点。
- **项 1 — 解析失败会话的附件残留不回滚（成立，核心）**：`SessionExternalParser.kt:37-42` 的 `finally` 只擦 `pwdClone/keyClone`，未对 `binaryStore` 本次 spilled 的附件做回滚；`catch` 仅包装为 `KdbxResult.Failure`。真实口径＝不锁库时 ≈ 周期数 × ≤4 × 池体积，锁定即归零（非「单次 128 MiB 上界」亦非「无上界」）。AC：失败解析会话在 `onSessionLocked()`/`clear()` 路径补附件回滚，或文档登记该残留量级与窗口。
- **项 2 — N1：.version 与 .basecache 两步写盘可失配（部分成立）**：`SyncCache.kt:137-144` 写序 `updateBase(.baseversion) → writeBaseContent(.basecache)`；`SyncEngine.kt:194/262/290` 的 `advanceBaseAndPersist` 用 `state.localVersion`（＝ `.version` 内容）充当 baseVersion。正常路径下 `.version == sha256(.cache)` 一致，`hasLocalChanges` 返 FALSE 是正确态（非「恒真」亦非缺陷）；**残余仅为崩溃窗内不一致**（代码自承于 `SyncEngineSupport.kt:70-72`）。AC：崩溃窗内的 `.version`/`.cache` 失配须自愈或显式登记为已接受限界；修复建议 `advanceBaseAndPersist` 传 `sha256Hex(bytes)`。
- **项 3 — 同步失败文案透传服务器可控字符串（部分成立）**：`SyncOutcome.Error(message)` 承载文本，`SyncCycleRunner.kt:203/268/336`、`SyncCycleSetup.kt:65`、`SyncConflictResolution.kt:108` 等少量站点以 `e.message`（服务器可控）直达 UI（`ConflictResolutionViewModel.kt:245`、`SettingsSyncController.kt:313`）；多数站点已用 `strings.get(R.string.fixed_xxx)` 合规。真实缺口＝与 `ISSUE-P1-10`「禁止透传 t.message」口径**部分冲突**而非整体违反。AC：上述透传站点改走固定通用文案（保留 type 供日志），消除服务器可控串外显面。
- **涉及文件**：`SessionExternalParser.kt`、`SyncCache.kt`、`SyncEngine.kt`、`SyncCycleRunner.kt`、`SyncOutcome.kt`、`SyncConflictResolution.kt`、`ConflictResolutionViewModel.kt`、`SettingsSyncController.kt`。
- **验收标准**：三项各自 AC 达成（项 1 回滚或登记；项 2 崩溃窗自愈/登记；项 3 透传站点改固定文案）；`test` + `gate_readings.py` 7/7 PASS。

### ISSUE-P3-311：加密/passkey 卫生批量登记（4 项）（「建议并表，不逐条占位」）

- **核实时间点**：2026-09-24（本轮审计，逐行反校）。
- **核实方式**：并行核查代理直读 `InnerHeader.kt:336,417-424` / `PasskeyKeyCodec.kt:100,107-111,114,143-149` / `PasskeyAssertionPayload.kt:184` / `PasskeyCryptoEngine.kt:241` / `AtomicFileWriter.kt:148-167,170-183,201` / `InnerRandomStreamCipher.kt:69-79`。
- **项 1 — 内层随机流密钥字段缺失兜底全零、无类型化诊断（成立）**：`InnerHeader.kt:336` 默认 `ByteArray(64)`；`:417-424` 的 `acceptStreamKey` 只查上界 1024、不查字段是否出现 ⇒ 字段缺失静默全零。修复只能做存在性断言（ID∈{2,3} 时字段必须出现）；钉 `==64` 会误拒本仓可解的 32 字节 Salsa20（`InnerRandomStreamCipher.kt:69-79` 对 key 长度无约束，SHA-256 吸收任意输入）。AC：补字段存在性断言 + 跨实现等价对拍。
- **项 2 — v1 legacy 私钥 DER 分支不钉曲线（成立）**：`PasskeyKeyCodec.kt:107-111` 返回 DER 自带 `ECPrivateKeyParameters`；`:143-149` 仅用 P-256 的 `n` 判界。可达链 `PasskeyAssertionPayload.kt:184 → PasskeyCryptoEngine.kt:241 → Base64 分支`。后果止于单凭据 self-DoS，无伪造面。AC：解析时钉死曲线（拒绝 DER 自带非 P-256 域）或显式 fail-closed。
- **项 3 — 私钥物化为不可擦 String（成立）**：`PasskeyKeyCodec.kt:100` 与 `:114` 均先 `String(bytes, UTF_8)` 再 `BigInteger(...)`；`:114` 在 DER 异常回退路径更易触发（非 32 字节非法 EC 私钥文本）。AC：避免 `String` 物化（以 `BigInteger(1, bytes)` 直接构造或复用字节），修复后无不可擦 String 副本。
- **项 4 — .kdbx.bak 无数据块 fsync，但 KDoc 承诺可恢复（成立）**：`AtomicFileWriter.kt:150-156` 仅 `Files.copy` + 父目录 `syncDirectory`，tmp 才有 `fos.fd.sync()`（`:124`）；`:178-179` KDoc 承诺「可自备份恢复」。PD-17 管删除语义、§1.4 管旧口令可解，均不覆盖 durability。AC：`.bak` 数据块补 `fd.sync()`（或等效）；fsync/delayed-allocation 语义需 AVD 实验确认。
- **涉及文件**：`InnerHeader.kt`、`PasskeyKeyCodec.kt`、`PasskeyAssertionPayload.kt`、`PasskeyCryptoEngine.kt`、`AtomicFileWriter.kt`、`InnerRandomStreamCipher.kt`。
- **验收标准**：四项各自 AC 达成；`test` + `gate_readings.py` 7/7 PASS。

### ISSUE-P3-312：导出/文档卫生批量登记（2 项）（「建议并表，不逐条占位」）

- **核实时间点**：2026-09-24（本轮审计，逐行反校）。
- **核实方式**：并行核查代理直读 `KdbxCsvExporter.kt:105-144` / `ImportWarnings.kt`（全枚举） / `BrowserCsvImporter.kt:149,228`；并直读 `docs/architecture/产品裁决登记.md:686-689` 与 `SyncEngine.kt:111,128,190-198`。
- **项 1 — CSV 公式注入零中和（成立，P3）**：`KdbxCsvExporter.kt:105-144` 仅 RFC 4180 引号化（判 `FIELD_SEPARATOR/QUOTE/\n/\r`），无前导 `= + - @` / 制表符中和；导入侧 `ImportWarnings.kt` 零公式注入编码（`BrowserCsvImporter.kt:149` 按表头原样转交 password）。触发需 3 次用户动作 + 绕过现代 Office 默认 DDE 阻断 ⇒ P3 加固。修复只能导入侧告警 + 导出确认文案提示（password 列不能撇号中和，会改坏口令本身）。AC：导入侧新增公式注入警告项 + 导出确认文案提示风险。
- **项 2 — PD-21 括注失实（文档诚信，成立）**：`产品裁决登记.md:688-689` 记 `recoverFromMetaFailure` 的 404 恢复分支「本周期无本地内容变更」；但 `SyncEngine.kt:111` 在 `metaResult.isFailure` 时即 `return recoverFromMetaFailure(...)`，**早于 `:128` 的 `hasLocalChanges` 判定**——该括注作行为背书缺代码支撑，且曾被上一轮审计当免检理由引用。AC：修订 PD-21 括注，删除「本周期无本地内容变更」或改述为「该分支在 hasLocalChanges 判定之前返回」，并登记为文档诚信更正。
- **涉及文件**：`KdbxCsvExporter.kt`、`ImportWarnings.kt`、`BrowserCsvImporter.kt`、`docs/architecture/产品裁决登记.md`、`SyncEngine.kt`。
- **验收标准**：两项各自 AC 达成；如涉及文档改动，须 `check_md_links.py` / `check_resolved_index_sync.py` PASS。
---
