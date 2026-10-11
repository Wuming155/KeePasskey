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

> **在册批次**：**2 条**（`ISSUE-P3-570` ＋ `ISSUE-P3-571`）。新条目附「核实时间点 + 核实方式」。
> 新增 `ISSUE-P3-571`（2026-10-11 用户真机反馈）：自动填充「关联记忆」交互无感知、且仅存本地
> SharedPreferences 不随库迁移——三家参考项目（Kp2a / KeePassDX / Monica）的关联均以**条目内载体**随 `.kdbx`
> 走；KeePassDX 定向取证补录见
> [`references/自动填充关联记忆与字段识别的参考项目对照.md`](references/自动填充关联记忆与字段识别的参考项目对照.md) §6。
> 在册 `ISSUE-P3-570`（来源＝`ISSUE-P3-569`（§484）闭环时「`runCatching` 面不属本判据」
> 的边界声明）。
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

## P2 中危缺陷与协议/测试缺口（0 项）

> **暂无开放项**（`ISSUE-P2-556` / `ISSUE-P2-557` 已于 §483 闭环归档，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)）。

## P3 低危问题、特性接线与体验优化（**2 项**）

> ### ISSUE-P3-570：`runCatching` 的协程取消语义与 `catch` 面同型、**未逐一收口**（`ISSUE-P3-569` 闭环时的判据边界）
>
> - **现象**：`kotlinx.coroutines.CancellationException` 在 JVM 上是
>   `java.util.concurrent.CancellationException` 的别名、继承 `IllegalStateException` ⇒ 裸 `runCatching { }`
>   与裸 `catch (Throwable)` **同型**地吞掉取消。`ISSUE-P3-569`（§484）的机检判据只钉 `catch (…)` 子句，
>   已在脚本文档串显式声明 **`runCatching` 不属本判据面**；`sync` 模块侧的 `runCatchingCancellable`
>   （`ISSUE-P3-555`，§482）只覆盖了 provider / 上传重试与 `markResolvedAndUpload` 数处。
> - **核实时间点 / 方式**：2026-10-09，**静态启发式普查**（临时脚本，一次性分析、未入库）：五模块 `src/main`
>   的 `runCatching` 内联调用共 **80 处**，其中 **30 处**落在协程上下文；按「保护段内出现 `suspend fun`
>   名（排除同名非挂起者）或 `.first(` / `.collect` 等已知挂起调用」进一步筛出 **16 处**——**该数字含已知误报**
>   （例：`sync/.../S3SyncProvider.kt` / `WebDavSyncProvider.kt` 的命中实为已收口的
>   `runCatchingCancellable`，被脚本的子串匹配误计；`SafKeyFileAccess.kt:79` 的保护段只是
>   `Uri.parse`），**故不得当缺陷清单**。
> - **影响**：与 `ISSUE-P3-569` 同——取消被归一为业务失败 / 默认值后，上层按失败处置。
> - **涉及文件**：`app/src/main/**`、`database/src/main/**`、`sync/src/main/**`、`core/src/main/**`（候选以现跑为准）
> - **验收标准**：① 逐处判定并为协程上下文中「保护段含挂起点」的 `runCatching` 换用**等价的不吞取消**写法
>   （可复用 `runCatchingCancellable` 的口径，或就地补 `is CancellationException` 判定）；
>   ② 对判为不适用者登记理由（不得静默跳过）；③ 评估把该面并入 `check_cancellation_semantics.py`
>   （或另立机检），**判据须与 `catch` 面同款避开「前置取消分支」误报**，并以 `--selftest` 正反样本反校。

> ### ISSUE-P3-571：自动填充「关联记忆」交互无感知、且仅存本地 SharedPreferences——关联不随库迁移（2026-10-11 用户再次反馈「没有询问是否记忆」）
>
> - **核实时间点 / 方式**：2026-10-11；用户口头反馈 ＋ 代码接线核对（写入点 `AutofillPickerActivity.kt:334` /
>   `AutofillConfirmActivity.kt:230`，召回点 `AutofillUnlockedCandidates.kt:58`，存储
>   `AutofillCallerEntryMemory.kt`）＋ 复读批次文档 §468 / §472 ＋ KeePassDX 定向取证（**取证已落册**
>   [`references/自动填充关联记忆与字段识别的参考项目对照.md`](references/自动填充关联记忆与字段识别的参考项目对照.md) §6，
>   关键证据行号经抽查复核）。
> - **背景**：`ISSUE-P3-528`（§468）已落地「关联记忆」：键＝归一化包名＋签名摘要 → 条目 UUID，
>   手选/确认交付成功即**静默**写入 `SharedPreferences`，下次该调用方请求时召回、不经匹配入选并置首
>   （仍走既有二次确认），§472 经用户走查回执闭环。**用户 2026-10-11 再次反馈**：「匹配不到时可以自己搜索并
>   输入密码，却没有（在那时）询问是否记忆这个 APP，下次还得再搜索」。对照三家参考项目，存在两条真实缺口：
>   1. **交互无感知**：当前写入全程静默——无「已记住」反馈、无询问、无设置开关。Kp2a 在自动填充流内弹
>      「Remember search text?」询问（对照文档 §2）；KeePassDX 虽同为静默写回但有设置开关（默认开）且写回
>      **条目本体**（对照文档 §6.2）；Monica 有显式「填充并保存 URI」双动作（对照文档 §3.1）。本仓静默记忆对
>      用户完全不可见，这正是用户「以为功能不存在」的直接原因。
>   2. **关联不随库迁移**：记忆仅存本应用 SharedPreferences（`keepasskey_autofill_caller_entry`，容量 16），
>      换设备 / 重装应用 / 换库即全部失效；三家参考项目均把关联写进**条目数据**随 `.kdbx` 走（Kp2a＝URL 伪
>      schema `androidapp://`；KeePassDX＝条目自定义字段 `AndroidApp`/`AndroidApp Signature`；Monica＝条目 app
>      绑定）。§468 如实声明第 3 条已明示「填充并保存 URI……如需另立条目」，至今未立——本条收此缺口。
> - **认领时先做（装机定性）**：用户设备上的包**未必含 §468 实现**（§434：设备上跑的不一定是刚打的包）——
>   先跑 `python tools/device/check_installed_build.py --expect-symbol <§468 新增符号>` 复核装机版本，
>   区分「感知缺口」与「版本落后」。
> - **交互口径待裁决（两案并列，不预设）**：
>   - **A（Kp2a 式询问）**：手选交付成功后弹「是否记住该应用与此条目的关联？」，显式同意才把
>     `android://<调用方包名>` 写进条目 URL。用户 2026-10-11 原话倾向此案；但它修订 2026-10-07
>     「按照 Monica 的执行」的既有裁决档，采纳时须同步
>     [`architecture/产品裁决登记.md`](architecture/产品裁决登记.md)。
>   - **B（KeePassDX 式）**：保持静默写回条目 ＋ 设置开关（默认开）＋ 首次交付时轻量提示「已记住」。
>   - 两案共同面：关联随库迁移；既有本地记忆（`AutofillCallerEntryMemory`）保留为快速通道或按裁决收敛。
> - **明确不在本条范围**：放宽准入口径（应用名 / 标题准入档，§352 已锁定且 §468 复申）；记忆档键改裸包名或
>   绕过 `ISSUE-P2-46` 绑定门（§472「不得外推」条款原样继承）。
> - **涉及文件（预估）**：`app/.../autofill/AutofillPickerActivity.kt`（询问 / 提示入口）、
>   新增 `android://` 形态写回判定内核（与 `SearchWriteBackPolicy` 分离——`isUrlTerm` 只认域名形态，
>   `android://<包名>` 恒为 false，§468 已预估此档）、`data/repository/VaultRepository`
>   （复用既有 `updateEntryUrl` 通道）、`res/values/strings.xml` ＋ `values-en/strings.xml`（成对文案）、
>   设置页开关（方案 B）。
> - **验收标准**：① 关联持久化到条目且与本仓既有 `android://<包名>` 绑定形态一致
>   （`DomainMatcher.isAndroidPackageMatch` 消费侧零改动命中；不新造 KeePassDX 式自定义字段）；
>   换设备 / 重装后关联随库恢复，匹配仍受既有绑定门（P2-46）约束；② fail-closed 同形四否决
>   （库只读 / 回收站 / `{REF:…}` / 已覆盖）＋ 包名非法或签名摘要不可读时**不产生**降级写入；写回只改 `URL`
>   一个字段，`Override URL` 与自定义字段零触碰；③ 交互形态按裁决落（询问式 / 开关＋提示），
>   zh/en 文案成对；④ 判定内核配 JVM 正反两态用例；`.\gradlew.bat test` 全绿 **且**
>   `python tools/doc/gate_readings.py` 全 PASS，读数块原样入批次文档；装机走查按 §434 前置读数。
