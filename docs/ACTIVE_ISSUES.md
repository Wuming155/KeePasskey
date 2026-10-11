# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。按 **P0 → P1 → P2 → P3** 降序排列，
> 每项含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需另立计划文件**。
> **只放现存问题**：本文件**只保留尚未闭环的条目**——已闭环条目的正文、批次流水与索引段一律不留；
> 历史实现与验收证据以 [RESOLVED\_LOG.md](RESOLVED_LOG.md) 与 `docs/resolved/batches/` 为单一真相源
> （批次的来源、严重度映射与登记裁决一并归位到**各批次正文**，产品口径落
> [`产品裁决登记.md`](architecture/产品裁决登记.md)，已接受取舍落
> [`已知工程限界.md`](architecture/已知工程限界.md)）。
> **闭环纪律**：条目整改通过后**整条移入** [RESOLVED\_LOG.md](RESOLVED_LOG.md)（加一行索引 + 新增批次正文），
> 并执行 `git commit & push`。
> **认领前复核前提**：条目正文的 `文件:行号` 一律是**核实时刻的快照**，认领时须先复核前提再开工（规则 6.1②）。

> **在册批次**：**1 条**。新条目附「核实时间点 + 核实方式」。
> 上一批 `ISSUE-P3-570`（`runCatching` 的协程取消语义与 `catch` 面同型、未逐一收口）已于 **§486** 整条闭环归档——
> 机检并入 `check_cancellation_semantics.py`（runCatching 面 + `LaunchedEffect` 构造补入），红 23 → 绿 0；
> 正文见 [`resolved/batches/486-协程runCatching取消语义并入机检批次.md`](resolved/batches/486-协程runCatching取消语义并入机检批次.md)，
> 索引见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。
> 再上一批 `ISSUE-P3-571`（自动填充「关联记忆」无感知 / 不随库迁移）已于 **§485** 整条闭环归档——方案 A
> （Kp2a 式询问写回条目 URL）落地，交互口径裁决登记 `PD-83`；正文见
> [`resolved/batches/485-自动填充记住应用关联询问写回批次.md`](resolved/batches/485-自动填充记住应用关联询问写回批次.md)，
> 索引见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。
> 上一批 `ISSUE-P3-569`（`catch (Throwable)` 的协程取消语义逐处收口）已于 **§484** 整条闭环归档——正文见
> [`resolved/batches/484-协程catch取消语义逐处收口批次.md`](resolved/batches/484-协程catch取消语义逐处收口批次.md)，
> 索引见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)；该批的逐处判定表（36 处补重抛 / 21 处判不适用并就地标注）
> 与机检读数留痕见该批次正文。
> 再上一批 2026-10-09 第二轮多维深度审查登记的**十三条**（`ISSUE-P2-556` / `ISSUE-P2-557` /
> `ISSUE-P3-558` ~ `ISSUE-P3-568`）已于 **§483** 整批闭环归档——正文见
> [`resolved/batches/483-存量十三条整改批次.md`](resolved/batches/483-存量十三条整改批次.md)，
> 索引见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)；该批的来源证据与「被推翻 / 降档的代理结论」留痕见
> [`records/多维深度审查报告_2026-10-09.md`](records/多维深度审查报告_2026-10-09.md)。

---

## 条目维护规则（ISSUE-P3-28 确立）

1. **新增条目必须附「核实时间点」与「核实方式」**：任何声称「某文件需要删除 / 某处没有消费方 / 某硬编码为 0」一类**关于代码库现状的前提**，都必须写明**何时、以何种手段**核实过。
   > 立规缘由：条目与代码库演进之间存在时间差，曾出现正文前提在开工时**已不成立**。
2. **开工前复核前提**：认领条目时先复核其正文前提（路径是否存在、行号是否漂移、消费方是否已出现）。前提已不成立的，**就地修正或显式标注**后再动手。（行号一律视为「核实时刻的快照」，见文首。）

---

## 优先级定义

|   等级   | 严重度与类型                                 |     处理原则     |
| :----: | -------------------------------------- | :----------: |
| **P0** | **阻断级 / 致命安全漏洞**（数据损坏、篡改未拦截、明文泄露、严重崩溃） | 立即停止其他工作优先修复 |
| **P1** | **高危缺陷 / 核心功能阻断**（权限失效、关键契约异常、核心流程失败）  |   最高优先级排期修复  |
| **P2** | **中危缺陷 / 协议互操作 / 性能瓶颈 / 测试有效性缺口**      | 正常排期，按批次连续解决 |
| **P3** | **低危项 / 进阶特性接线 / 代码整洁度 / 评估与体验优化**     |   渐进优化与特性补齐  |

---

## P0 阻断级问题（0 项）

> **暂无开放项**（`ISSUE-P0-531` 已于 §473 闭环归档，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)）。

## P1 高危与核心功能问题（0 项）

> **暂无开放项**（`ISSUE-P1-537` / `ISSUE-P1-538` 已于 §477 闭环归档，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)）。

## P2 中危缺陷与协议/测试缺口（1 项）

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

## P3 低危问题、特性接线与体验优化（0 项）

> **暂无开放项**（`ISSUE-P3-570` 已于 §486 闭环归档，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)）。

