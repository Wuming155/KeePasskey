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

## P2 中危缺陷与协议/测试缺口（2 项）

> 本批出自 2026-09-24 同步/加密/passkey 安全审计（逐行反校 + 4 并行核查代理），均为数据完整性 / 密钥残留类，
> 按「库永不丢、永不自己搞坏」定位列为 P2（Tier 0 优先整改）。
> `ISSUE-P2-309`（合并 base 取工作副本兜底）与 `ISSUE-P2-312`（凭据克隆漏擦）已于 §315 闭环；
> `ISSUE-P2-308`（同步采纳失败后引擎状态不回滚）已于 §317 闭环（其整改中发现的合并主路径同型残余补登为 `ISSUE-P2-313`）；
> `ISSUE-P2-310`（附件被系统回收 fail-open）已于 §318 闭环。

### ISSUE-P2-313：合并主路径（markResolvedAndUpload）基线前移仍先于本地采纳——采纳失败后同型「陈旧树本地赢覆盖」损失链（§317 整改中发现的同型残余）

- **核实时间点**：2026-09-25（`ISSUE-P2-308` 整改批逐行反校；412 重入侧已随该批接入采纳结算句柄，主路径未接入）。
- **核实方式**：直读 `SyncEngine.markResolvedAndUpload`（上传 → `writeCache` → `advanceBaseAndPersist` → `recordAccepted` 全部在引擎内即时落地，返回 `Result<String>` 不携带结算句柄）与两处采纳点：`autoMergeAndUpload`（`SyncConflictAutoMerge.kt`：上传成功 → `adoptMergedIfSessionUnchanged` 失败走 `abortDiverged`）与 `resolveConflicts` → `adoptMergedDatabase`（`SyncConflictResolution.kt`：采纳失败 / `save()` 失败仅返回 `Error`）。
- **背景与根因**：与 `ISSUE-P2-308` 同型——合并产物上传成功时引擎已把基线前移到 merged 内容（cache=merged、baseversion=sha(merged)、ETag 前移），而本地采纳在**上传之后**；采纳失败（校验-采用发现窗口内会话被 UI 编辑替换，或落盘失败）只返回 Error、基线不回退。下一周期：lastSyncedDb 滞留旧树 ⇒ `hasLocalContentChanged` 判真 ⇒ 以「旧树+窗口编辑」重写缓存 ⇒ `hasLocalChanges` 为真而 baseEtag==remoteEtag ⇒ **本地赢整库上传**，把云端刚接收的 merged 内容（含他端改动）静默覆盖。触发前提＝合并上传网络窗口内发生 UI 编辑并保存（`adoptDatabaseIfUnchanged` 守卫命中）或本地落盘失败，比 P2-308 的「解析/保存失败」窗口更窄，但损失形态相同（跨端灭他端数据）。`ISSUE-P2-308` 批已修：`commitLocal` / `commitLocalForce` / `RemoteSynced` 三类结果的采纳结算（含 autoMerge 412 重入与用户裁决 412 重入侧）；**主路径 `markResolvedAndUpload` 因返回 `Result<String>` 无法携带句柄而未动**。
- **涉及文件**：`sync/.../engine/SyncEngine.kt`（`markResolvedAndUpload` 返回类型）、`app/.../sync/SyncConflictAutoMerge.kt`、`SyncConflictResolution.kt`。
- **验收标准**：AC① `markResolvedAndUpload` 改为携带 [RemoteAdoptionSettlement] 的类型化终态（上传成功 ⇒ Uploaded(etag, settlement)，基线三步延后），两处调用方在采纳确认成功后 `accept`、采纳失败 / 落盘失败 `reject`；AC② 参数化用例锁定「采纳失败 ⇒ 基线保持旧值、下轮按冲突收敛且云端 merged 内容不被陈旧树覆盖」；AC③ `test` 全绿 + `gate_readings.py` 7/7 PASS。

### ISSUE-P2-311：自产 64 MiB 附件被自家解析器判损坏（写读两侧对同一常量口径互斥）（★）

- **核实时间点**：2026-09-24（本轮审计，逐行反校）。
- **核实方式**：并行核查代理直读 `AttachmentSizeLimits.kt:21` / `EntryEditPickers.kt:123,135` / `InnerHeader.kt:175,212,231-232,357,447` / `KdbxFile.kt:86`，并确认绿测 `InnerHeaderSecurityTest` / `KdbxInnerResourceLimitsInvariantTest` 断言全在读侧。
- **背景与根因**：`AttachmentSizeLimits.kt:21` `MAX_ATTACHMENT_BYTES = 64 MiB`。`EntryEditPickers.kt:123` 与 `:135` 两道关卡均用 `>` ⇒ 恰好 64 MiB 放行。写侧 `InnerHeader.kt:175` 写字段长 `bin.size + FLAGS_FIELD_BYTES = 64 MiB + 1`；`MAX_INNER_FIELD_BYTES = KdbxFile.MAX_DECOMPRESSED_PAYLOAD_BYTES(128 MiB) / FIELD_CAP_SHARE_DIVISOR(2) = 64 MiB`（`:212 / :231-232`）；读侧 `:357` 用 `>` 判 ⇒ 下次打开整库判损坏。**写侧零累计闸**：`MAX_BINARY_POOL_TOTAL_BYTES`（`:280-281` = 128 MiB）仅活在读侧 `:447` 与 init 断言 ⇒ 3×50 MiB 附件同样写得出、读不进。绿测 `InnerHeaderSecurityTest` / `KdbxInnerResourceLimitsInvariantTest` 断言全在读侧/常量层，把矛盾固化。
- **涉及文件**：`database/.../inner/InnerHeader.kt`、`AttachmentSizeLimits.kt`、`app/.../EntryEditPickers.kt`、`KdbxFile.kt`。
- **验收标准**：AC① UI 上界取 `MAX_INNER_FIELD_BYTES − 1` 并同源派生（消除写读互斥）。AC② `serialize` 前加池累计判据（单值同读侧 `MAX_BINARY_POOL_TOTAL_BYTES`）。AC③ 参数化 save→load 往返测试：UI 上界值（含 64 MiB−1、多附件累计临界）必通。`test` + `gate_readings.py` 7/7 PASS。

---

## P3 低危问题、特性接线与体验优化（4 项）

### ISSUE-P3-300：弱 ETag 乐观锁的**真实 DAV 服务器矩阵未实测**——弱 ETag 服务器上的同步收敛行为待证（§272 AC⑤ 显式残余）

> 本条**不属**上文「`ISSUE-P1-276` ～ `ISSUE-P3-299`」审查批例外范围：它由 §272（`ISSUE-P1-275` 闭环批）
> 的 AC⑤ 未执行残余升级为待办承接，与 [`已知工程限界.md`](architecture/已知工程限界.md) §28 互为指针。

- **核实时间点**：2026-09-23（§272 整改当日，随批如实登记）。
- **核实方式**：① §272 已完成的证据面＝RFC 全文核对（RFC 7232 §2.3 / §3.1、RFC 4918 §8.6 / §10.4.2 / §10.4.4 / §10.4.9，rfc-editor.org 原文）+ 有状态 mock 的头形态与弱比较裁决守卫（`StatefulDavDispatcher.weakEtagPaths`）——**协议层与形态层已证**。② 条目 AC⑤ 要求的**真实服务器矩阵**（Apache/mod_dav、nginx-dav、Nextcloud、IIS）当日无环境可用；`tools/local-sync` 联调链路代理侧不可运行（既有口径）且 `run_webdav.py` 不产 ETag，本就不构成弱面取证 ⇒ **未执行**，登记于批次文档 §3.3 与限界表 §28。
- **背景与根因**：§272 弱 ETag 收口选定「回传服务器签发原形态」取向（MOVE `If` 头携 `W/"…"`；`If-Match` 弱期望不发送；S3 弱期望 fail-closed）。理论边界已经 RFC 划清：**弱比较**服务器上原形态必匹配；**强比较 × 弱存储标签**的组合下任何客户端的实体标签预条件均不可满足（RFC 7232 §2.3 强比较要求两侧均非弱），属服务器自绝于条件写，本仓退化为 412 → 冲突重检（不静默覆盖、不丢数据，但同步可能反复提示冲突）。**未证的是**：真实服务器对 RFC 4918 §10.4.4「弱或强比较二选一」的实际取向、以及 MOVE 事务写在弱 ETag 服务器上的兼容性——这决定弱 ETag 服务器（Apache/mod_dav 部分文件系统配置为高发面）上同步是正常收敛还是反复冲突提示。
- **涉及文件**：无生产代码改动面（纯外部验证条目）；实测结果回填 `docs/resolved/batches/272-冲突时刻ETag透传与弱校验收口批次.md` §3.3 与 `docs/architecture/已知工程限界.md` §28。
- **验收标准**：AC① 四类服务器各实测三项读数并按规则 8 留证（命令 + 原始响应）：签发 ETag 的强/弱形态；MOVE `If` 头对弱形态（`[W/"…"]`）与强形态（`["…"]`）预条件的接受性；PUT+MOVE 事务写兼容性。AC② 实测**证实**「回传原形态」取向 ⇒ 回填两处登记并闭环本条；实测**推翻** ⇒ 不得就地放宽 §272 守卫用例（限界表 §28 边界条款），须另行立条裁决新取向。AC③ 无法取得的环境（如 IIS）逐项如实标注未执行，禁以 mock 绿推定闭环。AC④ 环境不可得期间，本条与限界表 §28 维持开放，不得归档。**AC⑤（§316 自 `ISSUE-P3-302` AC⑤ 迁入，随本条一并执行）**「404 恢复分支未下传 `remoteExists`」的同目标并发实验（原属 `ISSUE-P2` 审计项），按本仓目标后端分两类：**(a) 标准 WebDAV（RFC 4918 合规实现，即 mod_dav 类参考实现口径）**——对「本地已 PUT、他端并发创建」的目标，第二客户端以 `MOVE(Overwrite:F)` 期望 412，验证 `SyncEngine.kt:190-198` 404 恢复分支缺省 `remoteExists` 导致的 TOCTOU 在真实服务器上的实际效力（即强制 `Overwrite:F` 是否真被尊重、412 是否返回；若服务器忽略 `Overwrite:F` 静默覆盖，则该 TOCTOU 为真实残余，需换用 `If` 预条件而非 Overwrite 头）；**(b) 兼容 S3 协议存储**——S3 路径走原子条件 PUT（`If-None-Match:*` 首传 / `If-Match` 覆盖，`S3SyncProvider.kt:32` KDoc 已声明「无 TOCTOU 竞争窗口」），本就不存在 MOVE+Overwrite 竞争，故**无需 Overwrite:F 实验**；实验只需验证目标 S3 兼容端**是否真正尊重条件写头**（少数非合规实现会忽略 `If-Match`/`If-None-Match:*`，见 `S3SyncProvider.kt:239`），以「对**已存在**对象 PUT `If-None-Match:*` 期望 412 而非 200」一轮即可。注：Nextcloud 等具体产品端点不在本仓目标范围，不纳入实验矩阵。

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


### ISSUE-P3-313：同步周期「内容一变即整库重序列化」在低端真机上是 4.8 秒 CPU 成本（量级已由 §316 实测）

- **核实时间点**：2026-09-24（§316 真机实测随批补登）。
- **核实方式**：`app/src/androidTest/.../SyncAssemblyScaleDeviceTest` 在实体机（Redmi 4X / LineageOS 24 / SDK 37 / 1.8 GB RAM / arm64-v8a）上直调 `SyncDatabaseCodec.serializeLocalDatabase` 并以记录型 `SyncCache` 子类取装配段各步耗时；夹具为 10,000 条目（含 200 份历史快照与 1 个 2 MiB 落盘附件）真库。三次实测读数：整库序列化 + 加密 **4,812 / 4,829 / 4,832 / 4,844 ms**，同批「全库逐字段深比较」**1 ms**、缓存读 **2-3 ms**、原子写 **22-63 ms**（原始读数见 §316 §2.2）。
- **背景与根因**：`SyncCycleSetup.buildCycleContext` 在 `!isCached || hasLocalContentChanged` 分支对**整棵树**重跑「XML 写出 → 压缩 → 内层加密 → 外层加密 → MAC」并用 `writeCache` 覆盖工作副本——KDBX4 无增量写，故**任一字节的内容变化都触发整库重加密**。实测该步占装配段观察窗（5,024-5,073 ms）的 **95% 以上**，⇒ §274 把四类重活并列为「数百毫秒至秒级」的**归因需更正**：成本几乎全部集中在整库序列化+加密一步，深比较实际可忽略。**不是交互卡顿**：§316 实测整改后主线程最长连续阻塞 ≤33 ms（四轮最差）、vsync 掉帧 0（该步已在 `Dispatchers.IO`）。残余面＝**同步完成时延与低端机 CPU/电量成本**：万级条目库「改一条即同步」每轮都要重加密整库，且这属把阻塞移走而非减少总工作量（§316 AC① 明列为待证项）。
- **涉及文件**：`app/.../sync/SyncCycleSetup.kt`、`SyncDatabaseCodec.kt`、`database/.../file/KdbxFile.kt`（序列化管线本体）。
- **验收标准**：AC① 按段给出 4.8 s 的内部归因（XML 写出 / 压缩 / 内层流加密 / 外层块加密 / MAC / 缓存落盘各占多少），同设备同夹具留原始读数；AC② 依归因评估可行取向并登记取舍（例如收紧「需要重序列化」的判据、把序列化与缓存覆盖解耦），**本条不预设结论**。**红线**：KDBX4 每次保存重生成 masterSeed / IV / KDF salt（`ISSUE-P2-309` 已证），任何「复用旧字节 / 跳过重序列化」的取向都必须重证 `version` / ETag 前移语义与 `SyncRollbackGuard` 的字节摘要判据，不得以省时为名削弱防回滚。

---
