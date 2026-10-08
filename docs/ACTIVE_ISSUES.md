# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。  
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。  
> **只放现存问题**：本文件**只保留尚未闭环的条目**——已闭环条目的正文、批次流水与索引段一律不留；历史实现与验收证据以 [RESOLVED\_LOG.md](RESOLVED_LOG.md) 与 `docs/resolved/batches/` 为单一真相源。  
> **实施前必读**：源自安全复核的条目，其 AC 已按 [`SECURITY_RECHECK_2026-09.md`](security/SECURITY_RECHECK_2026-09.md) §10 / §11 的**第四轮独立复核定版结论**逐条对齐，更正口径已内联到对应条目——照条目原文 AC 实施者可能**降低安全性**或**引入数据损坏**。  
> **例外（`ISSUE-P1-276` ～ `ISSUE-P3-299`）**：这批 2026-09-23 条目出自**两轮全仓审查**（8 个铺开代理 + 7 个对抗复核代理，反向口径＝先假设误报再取证），不属上述复核范围；其依据为各条「核实方式」所记的逐行反校与对抗推翻结论。凡条目标注「**未经对抗轮单独攻击**」或「**需外部证据（真机 / 真实 DAV 矩阵 / 官方对拍）**」者，认领时须按规则 6.1② 先自行复核前提，不得直接开工。  
> **闭环纪律**：任务完成后，将该条目从本文件**整条移入** [RESOLVED\_LOG.md](RESOLVED_LOG.md)（加一行索引 + 新增批次正文），并执行 `git commit & push`。
> **新增批次（2026-10-05）**：`ISSUE-P2-466~470` / `ISSUE-P3-474~487` 出自 [`records/软件工程质量审查记录_2026-10-05.md`](records/软件工程质量审查记录_2026-10-05.md)（五维度主源码静态审查 + 合并 / 同步 / 健康审计引擎深审）。**严重度映射**：记录标 medium（含 low/medium）→ **P2**，low → **P3**。这批条目为**纯静态审查**产出（未运行构建 / 测试 / 真机），认领时须按规则 6.1② 先复核前提（正文行号仅作核实时刻的快照）；其中涉及 `crypto/src/main/rust/**` 的原生面条目，入库前须按 AGENTS.md 测试资产纪律**四层 `connectedDebugAndroidTest` 真机实跑**（执行前按 §263 确认设备上无待保留数据或改用 AVD）。
> **新增批次（2026-10-06）**：`ISSUE-P1-495` / `ISSUE-P2-496~502` / `ISSUE-P3-503~516` 出自 [`records/三类隐蔽性故障排查报告_2026-10-06.md`](records/三类隐蔽性故障排查报告_2026-10-06.md)（假开关 / 接线断裂 / 读取源错误与静默降级三类，九路并行排查 + 逐条独立复核）。**严重度映射**：报告标 high → **P1**，medium → **P2**，low → **P3**。**三项登记裁决**（登记时新定，须随条目一并遵守）：
> ① **同族去重**：报告 F12 为「原生探活失败静默回落零观测」缺陷族的唯一母条目，其子集 F03（cipher 三引擎）/ F07（强度面）/ F11（Passkey 签名面）并入 `ISSUE-P2-499`，不独立立项（该合并条目已于 §454 闭环归档）；
> ② **severity 取族内最大值**：`ISSUE-P2-499` 判 medium（母条目自身 low，子集 F03 为 medium——ChaCha20 BC 回落 ≈44× 慢、Twofish 整库数据流走纯 Java），条目内分层保留两侧论据；
> ③ **F25 不登记**：复核判定其缺陷前提被证伪（与格式裁决者 KeePass 2.61.1 官方 C# release 行为逐行同型，批次 273 勘误②复审显式维持、写侧 D17 守卫在位），`holds=false` 依据是前提证伪而非登记表命中。
> **触发状态口径**（报告 v3 判据 E1/E2，与本文件「只放现存问题」不冲突——masked 指**错误效果已产生或曾实际发作**，dormant 指**零错误效果、缺陷以声明侧残留 / 守卫缺口形态存在**）：masked 7 条（`P1-495` 除外，另 `P2-496~499`）应优先认领；dormant 条目为前瞻性风险，可按批连续解决。全部条目为**只读静态排查**产出，认领时须按规则 6.1② 先复核前提。
> **真机走查（2026-10-06）**：`ISSUE-P1-495` 收工时的真机（MIUI / `M332BF` / Android 17）五层走查——`:crypto:` 37/37、`:database:` 18/18、`:sync:` 25/25 全绿；`:core:` 4 例中 1 例**确定性假红**（曾登记为 `ISSUE-P2-517`，**已于 §450 整条闭环**，本文件不再保留该条目）；`:app:` 51 例中 3 例失败属已登记的 `ISSUE-P2-492` 厂商冻结面（读数与未定性项见 [`architecture/实现约定与验证现状.md`](architecture/实现约定与验证现状.md)）。
> **新增批次（2026-10-07）**：`ISSUE-P2-518~521` / `ISSUE-P3-517~525` 出自 [`records/六专题综合研究与实测报告_2026-10-07.md`](records/六专题综合研究与实测报告_2026-10-07.md)（动态工作流六专题调研 + 五组实测的报告，§9.3 为 8 条 backlog 草稿；报告同日落册入库并登记文档地图）。**严重度映射**：草稿标 P2 → P2、标 P3 → P3；另 4 条（`P3-520` / `P3-521` / `P3-522` / `P3-525`）为报告正文 §1 / §3 / §6 已核实发现的补登。各条登记前均已逐条复核前提（核实时间点与方式见条目正文）；`P3-523` 涉及设备侧复跑，执行前按 §263 确认设备上无待保留数据。**该批 13 条现已全部闭环**：`P2-518/519/521` §463、`P2-520` §461、`P3-517/525` §462、`P3-518/520/521` §460、`P3-519/522/524` §461、`P3-523` §464（设备侧逐例定性）；`P3-526` 为 §463 残留（`.bak` 恢复入口真机走查）同批登记入清单，**已于 §465 闭环归档**，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。
> **新增批次（2026-10-08，第三方复核）**：`ISSUE-P1-537` / `ISSUE-P1-538` / `ISSUE-P2-539` / `ISSUE-P2-540` / `ISSUE-P3-541` 出自 [`records/第三方复核报告_2026-10-08_同步闪退面.md`](records/第三方复核报告_2026-10-08_同步闪退面.md)（**两份**报告：`§473`（`bb60d9bb`）复核 7 条 + `§474`（`4aa0474a`）复核 7 条，均为第三方静态复核 + 逐点核对，**未跑全量 `test`、未真机**）。**严重度映射**：报告标 CRITICAL/HIGH → **P1**，MEDIUM → **P2**，LOW → **P3**。**五项登记裁决**（登记时新定，须随条目一并遵守）：① **两份报告的相反动作不取折中**（详见 `ISSUE-P2-540`）；② 报告 B #1 判为**本批（§474）引入的回归**，代理认领；③ 报告 A 的 `AutofillPickerViewModel.kt:127` 一条经代理复核**判为审计误判**（该行位于 `runCatching { … }.getOrDefault("")` 之内）⇒ **不登记**、不改动；④ 报告 B #4 与 #5 **合并登记**（代理判定同根因：擦除是**逐实例身份集合**而非子树级）；⑤ **编号说明**：并行工作线已先行占用 `ISSUE-P2-536`（内容变更判据等值敏感性，提交 `01fc0b38`），本批从 **537** 起编号、**不重号**。逐条核实结论与「不采纳」理由见落册文档 §1～§4；认领时须按规则 6.1② 先复核前提（正文行号为 2026-10-08 核对时刻的快照）。

> **用户反馈批次（2026-10-08，§478）**：`ISSUE-P2-544` 出自**用户真机反馈**（「密码库搜索筛选功能异常，
> 点击按钮没有反应，另外这个按钮应该收敛到密码库右上角三个点的那个设置，和排序、扫码放在一起」）——
> 核实方式＝**AVD 真机实测复现 + `uiautomator` 层次树逐帧取证**（读数与步骤见
> [`resolved/batches/478-高级搜索入口收敛与对话框化批次.md`](resolved/batches/478-高级搜索入口收敛与对话框化批次.md) §1）；
> **同会话闭环**（登记即排查）。该批同时登记了未闭环项 `ISSUE-P2-545`（见 P2 区首条）。

---

## 条目维护规则（ISSUE-P3-28 确立）

1. **新增条目必须附「核实时间点」与「核实方式」**：任何声称「某文件需要删除 / 某处没有消费方 / 某硬编码为 0」一类**关于代码库现状的前提**，都必须写明**何时、以何种手段**核实过。
   > 立规缘由：条目与代码库演进之间存在时间差，曾出现正文前提在开工时**已不成立**。
2. **开工前复核前提**：认领条目时先复核其正文前提（路径是否存在、行号是否漂移、消费方是否已出现）。前提已不成立的，**就地修正或显式标注**后再动手。
3. **行号一律视为「核实时刻的快照」**：正文内引用的 `文件:行号` 仅作定位提示，实现前须以真实内容为准。

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

## P1 高危与核心功能问题（2 项）

> **新增（2026-10-08，第三方复核）**：以下两条出自报告 B（`§474` 复核），报告严重度标 HIGH ⇒ 按映射入 **P1**。

### `ISSUE-P1-537` `PasskeyData.fromCustomFields`：「先查后读」两步式 + 8 处无判据读取，且 §474 删掉了调用点兜底（**本批引入的回归**）

- **来源**：报告 B #1（[`records/第三方复核报告_2026-10-08_同步闪退面.md`](records/第三方复核报告_2026-10-08_同步闪退面.md) §2）。
- **核实时间点 / 核实方式**（2026-10-08，代理逐行实读 + 调用面普查）：`core/…/model/PasskeyData.kt:450-480`、`app/…/data/repository/VaultEntryMapper.kt:83-86`；全部调用点 20 处（含 `PasskeyEntryCoordinator.migrateLegacySchemaIfNeeded:352` 的迁移写路径）。
- **缺陷面（三条，须一并处置）**：
  1. **TOCTOU（必然存在，与字段顺序无关）**：`PasskeyData.kt:452` 的 `takeUnless { it.cleared }` 与 `:465` 的 `privateKeyVal.useUtf8 { … }`（内部 `checkNotCleared()`）之间不是原子的，并发 `clear()` 即抛 `IllegalStateException`；而 465 行是**外部管理器条目**（KeePassXC / KeePassDX 写入、无 `Passkey.Algorithm` 扩展键）的**常规路径**。
  2. **8 处无判据读取**：`456-460`（userHandle / userName / userDisplayName）、`464`（algorithm）、`467`（publicKey）、`470`（signCount）、`471-478`（两个 flags）、`479`（createdAt）全为裸 `readString()`。
  3. **§474 的回归**：`VaultEntryMapper.kt:83-86` 原本生效的 `catch (IllegalStateException) { null }` 被删除，替代品（`fromCustomFields` 内的 `cleared` 预扫）既不原子又不完整 ⇒ §473 闪退链可原路复发。
- **传播链（§473 原链）**：`mapKdbxEntryToUi` → `VaultEntryQueryCoordinator.entriesFlow()` 的 `flowOn(Dispatchers.Default)` → 收集方 `viewModelScope`（无 `CoroutineExceptionHandler`）⇒ 进程闪退。
- **为何「非抛化」不伤数据完整性（决定性事实，认领时须先核此条）**：写盘防线在 `database/…/xml/KdbxXmlEntrySerializer.kt:211/223`（另一模块、本批未触碰）；`fromCustomFields` 的调用方本就按 fail-safe 契约使用它（`?: return entry` / `?: continue` / `?: return false`），其迁移写路径 KDoc 明写「**失败即放弃**：…原样返回条目，只留痕不迁移」⇒ **它现在会抛 ISE 才是对契约的违背**。
- **整改方向（须先按 `ISSUE-P2-540` 的口径裁决执行）**：① `fromCustomFields` 全字段非抛化（必需键＝「已清零 ⇒ null」读口；可选 / 元数据键＝「已清零 ⇒ 默认」读口；嗅探路径改字节面非抛读口），**消灭 `takeUnless{cleared}` + 裸读的两步式**；② 展示调用点保留**带 `AppLog` 留痕的第二层**兜底；③ 新增机检：禁止两步式形态。
- **验收标准**：① 上述 8 处与 `:465` 在「字段已清零」下**均不抛**，且必需键清零 ⇒ 判为「本条目无通行密钥」；② `VaultEntryMapper` 的兜底为**有日志**形态（非空 catch）；③ 新增用例覆盖「字段顺序各异 + 必需键 / 可选键分别已清零」；④ 全量 `test` 绿 + `gate_readings` 全 PASS（读数块原样贴入批次文档）；⑤ `ISSUE-P2-540` 要求的 §474 结论回写同批完成。
- **边界**：本条 TOCTOU 属**必然而非概率性**（与字段顺序无关）；但触发仍要求「该条目正被会话层擦除」，故**不得**据此声称「必然复现用户场景」。

### `ISSUE-P1-538` 应用级 scope 收口漏掉「裸 `CoroutineScope(`」形态，且该形态无机检

- **来源**：报告 B #2（同上 §2）。
- **核实时间点 / 核实方式**（2026-10-08，代理现跑 grep）：`app/src/main/**` 全量搜 `CoroutineScope(` ⇒ 裸形态**仅 1 处**：`app/src/main/java/com/keepasskey/app/ui/AppShellLocalization.kt:137`（`.launchIn(CoroutineScope(Dispatchers.IO))`，`AppLocaleTracker` 为 `@Singleton` 进程级）；其余命中为 `rememberCoroutineScope()`（Compose 生命周期，不属此列）与 `GuardedScope.kt` 自身。
- **为何 §474 漏检**：本批普查口径写死为 `CoroutineScope(SupervisorJob(` ⇒ **形态性漏检**——与 `ISSUE-P3-305`「闸门存在 ≠ 闸门被执行」同型：收口靠人工普查、无机检。
- **缺陷**：该 scope 既无 `SupervisorJob` 也无 `CoroutineExceptionHandler`；`settings.getSettings()`（DataStore）抛 `IOException` 时异常直达线程默认处理器 ⇒ 与 §473 同型的进程闪退。
- **整改方向**：① 该处改走 `guardedScope(Dispatchers.IO, …)`；② **新增第 11 道机检**（含 `--selftest`）：`*/src/main/**` 内 `CoroutineScope(` 只允许出现在 `GuardedScope.kt`（`rememberCoroutineScope()` / `viewModelScope` 不在判据内），接入 `hygiene-gate`。
- **验收标准**：① 上述调用点接入工厂；② 机检上线并**先对整改前形态跑出红、再转绿**；③ `gate_readings` 读数块（11/11）原样贴入批次文档；④ 普查口径**写死进脚本 docstring**（避免再次「口径未定义即宣称全覆盖」）。
- **边界**：报告 A 称全仓 36 处零 handler、报告 B 称本批 15 处——**两口径均不完整**（依赖不同 grep 形态）。本条不追求「36」这个数字，只钉「**形态判据 + 机检**」。


## P2 中危缺陷与协议/测试缺口（**3 项**）

> **新增（2026-10-08，本批排查副产品；登记即闭环本批未闭环）**：`ISSUE-P2-545` 出自
> §478 的排查过程（`ISSUE-P2-544` 真机取证时核实树内 `§477` 的登记面 / 机检**不存在**）。
> 该条**只登记、本批未动**（不得据本条声称「`§477` 内容已丢失」——其**代码**改动以另一实现形态
> 存活于 `bc7f187a`；缺的是**批次正文 / 索引行 / `PD-79`·`PD-80` / 第 11 道机检**）。

### `ISSUE-P2-545` `§477` 的登记面与第 11 道机检在当前树内不存在（提交 `bc7f187a` 沿用陈旧文档树提交所致）

- **来源**：§478（`ISSUE-P2-544`）排查副产品。
- **核实时间点 / 核实方式**（2026-10-08，代理现跑 `git show` / `git log --format` / `ls` / `grep`）：
  `33a8b86b`（§477，13:26）与 `bc7f187a`（13:33）为**父子提交**（`bc7f187a` 的父提交即 `33a8b86b`），
  而后者把前者的文档面整批改回旧态。
- **当前树内缺失清单（逐项已核实）**：
  1. `docs/resolved/batches/477-*.md` 不存在（`33a8b86b` 曾新增 297 行正文）；
  2. `docs/RESOLVED_LOG.md` 无 `§477` 行，`docs/resolved/README.md` 仍写「当前最大为 **§476**」；
  3. `docs/architecture/产品裁决登记.md` 无 `PD-79`（读取面分层 / 禁两步式）与 `PD-80`（比较器钝化）；
  4. `tools/doc/check_raw_coroutine_scope.py` **已删除**，`build.yml` 的 `hygiene-gate` 随之
     11 道 → **10 道**（现清单见该 run 块，末行为 `check_projection_read_safety.py`）；
  5. `ACTIVE_ISSUES.md` 回到旧态：`ISSUE-P1-537` / `P1-538` / `P2-539` / `P2-540` / `P3-541` /
     `P3-542` **六条重新出现在待办区**，`ISSUE-P3-543`（`KdbxEntryMerger.isModified` 同族续扩）消失。
- **同批未丢的部分（不得夸大）**：读路径缺陷的**代码整改以另一实现形态在位**——例如
  `core/…/KdbxEntry.kt` 的 `displayTitle()` / `displayUserName()` / `displayUrl()` / `displayNotes()`
  成员级降级读口（内部走 `ProtectedString.readStringForDisplay()`）、`VaultEntryMapper` 的
  `PasskeyData.fromCustomFields` 容错注释、`check_projection_read_safety.py` 的 RULE0～3 均在位；
  `§477` 引入的 `ProtectedString` 级读口 `readStringForDisplayOrNull` / `contentEquals` 被
  `bc7f187a` 以「清理冗余读口 / 简化逻辑」的名义移除（**替代实现存在，故不得声称缺陷未修**）。
- **为何机检发现不了**：`python tools/doc/check_resolved_index_sync.py` 在当前树判
  `RESOLVED_INDEX_SYNC=OK（最大 §476）`——批次正文 / 分册 / 全量索引 / README 四者**自洽于旧态**，
  「整批登记被撤」不产生断链 ⇒ 无闸门可拦。
- **整改方向（待用户裁决，代理不单方定案）**：① 若维持 `bc7f187a` 的代码实现，则需以该实现为准
  **重新闭环**六条并补登 `ISSUE-P3-543`、把 `PD-79` / `PD-80` 的口径按现存实现重写后落库、
  决定第 11 道机检（`CoroutineScope(` 裸形态）是否重启；② 若判定 `bc7f187a` 的文档改回应属误操作，
  则以 `git show 33a8b86b:…` 取回正文并**逐条与现行代码对齐**后再入库（**不得**直接整文件回滚——
  正文描述的部分 API 已被删除，照搬即成新的文档漂移）。
- **验收标准**：① 处置方向经用户裁决并落册；② 六条待办与 `ISSUE-P3-543` 的**在办状态**与代码现状一致
  （闭环或明确重开，不得悬空）；③ `gate_readings` 条数与 `build.yml` 现清单一致（**条数不写死**）；
  ④ `check_resolved_index_sync` 仍绿。
- **边界**：本条**不**判 `bc7f187a` 的代码改动对错（那是另一议题）；**不得**据本条直接执行
  `git revert` / 改写已推送历史。

### `ISSUE-P2-539` 填充 / 交付链未收口：4 处裸读 + 交付面「半份交付」+ `hasClearedFields()` 前提失实（**三条合并，同一根因**）

- **来源**：报告 B #3（裸读 4 处）、#4（交付面 fail-open 读口令）、#5（`hasClearedFields` 前提）——**代理判定 #4 与 #5 同根因**：正因「擦除是**逐实例身份集合**而**非**子树级」，「预判通过、单字段已被擦」的半份交付才可能发生，故合并为一条。
- **核实时间点 / 核实方式**（2026-10-08，代理实读 + grep）：`app/…/autofill/AutofillUnlockedCandidates.kt:114`（`ranked.entry.password?.readString()?.isNotEmpty()`）、`app/…/autofill/StructuredFieldPolicy.kt:260/303/322`（`customFields[].value.readString()`）；`app/…/autofill/AutofillDatasetBuilders.kt:243`（`hasClearedFields()` 预判）与 `:257`（口令走 `readStringForDisplay()`）；`core/…/model/KdbxEntry.kt:76-83`（谓词与 KDoc）。
- **后果定性（如实）**：`KeePasskeyAutofillService.kt:116 / 420` 有 `catch (Throwable)` ⇒ **不闪退**，但整轮响应以 `callback.onFailure("自动填充失败")` 收场（**用户可见功能失败**），而非降级为「跳过该候选」；交付面则表现为「只有用户名」的**静默降级**。
- **整改方向**：① 纯评分 / 过滤类值改展示读口；口令**存在性探测**改 `cleared` 预判或凭据面口径；② 交付面按 `PASSWORD` 键**精确预判 + 读后复核**（`passwordId != null && password.isEmpty() && 该字段 cleared` ⇒ 整条跳过，与 `hasClearedFields()` 分支同一出口）；③ `hasClearedFields()` 纳入 `customFields`，并把 KDoc 前提改为「**逐实例**擦除 ⇒ 有字段被擦即本条正在下线（保守判据）」；④ 两个文件补入 `PROJECTION_FILES`；⑤ 机检加一条：**非交付面**文件禁止对 `password` 使用 fail-open 读口（交付面按登记豁免，理由写进脚本 docstring）。
- **验收标准**：① 4 处裸读清零且机检扩面后无命中；② 交付面在上述条件下**整条跳过**（不得交付半份）；③ `hasClearedFields` 的 `customFields` 覆盖与 KDoc 措辞落地；④ 新增用例锁定「必需 / 可选字段分别清零」与「交付面跳过」；⑤ 全量 `test` 绿 + `gate_readings` 全 PASS。
- **边界**：RULE3 为**文件级**白名单，**不能**表达「同文件内凭据面 vs 展示面」的边界（报告 B 亦指出该限制）——本条以「专项判据 + 登记豁免」逼近，**不宣称**边界已可完全机检。

### `ISSUE-P2-540` **两处矛盾**：`§474` 结论与代码现状不符 + 两份复核报告给出相反动作

- **来源**：报告 B #1 的「须回写」要求 + 报告 A #5 与报告 B #1 的动作冲突（落册文档 §3）。
- **矛盾一（文档 ↔ 现状）**：`§474` 批次文档 §2 表格写「#5 → 已按 `cleared` 预扫、调用点去掉 try/catch」，与代码现状（`VaultEntryMapper.kt:83-86` 确已无 catch；`fromCustomFields` 仍为两步式）**在「是否已妥善处置」这一结论上不符** ⇒ 属本仓最忌的**文档漂移**，且会诱导下一位读者按错误结论继续。
- **矛盾二（报告 A ↔ 报告 B）**：A5 要求**删除** catch（判据：用异常做流程控制 + 空 catch 无留痕）；B1 要求**恢复** catch（判据：替代品不原子且漏字段）。**两者同时成立**，指向同一段代码的相反动作。
- **过程教训（代理认领）**：上一批对「删掉 catch」**字面执行**；正确做法是「**先删因、再判果**」——先消除「为什么需要这个 catch」，再判断它是否还需要。这是本批回归的**流程根因**。
- **候选裁决口径（**待用户裁决**；采纳后登记 `docs/architecture/产品裁决登记.md` 并回写本节）**：① 按**消费面**分层——写盘 / 落库 / 凭据下发＝fail-fast（防线在 `KdbxXmlEntrySerializer`），展示 / 检索 / 报告 / 解析＝读到不可读即降级；② 判据必须落在**读取自身**（单一判据点），「先查 `cleared`、后裸读」的**两步式一律视为缺陷形态**；③ 兜底只允许作**第二层**且必须留痕，**不得**作为唯一机制；④ 两份报告动作相反时**不取折中**——追问各自反对的失效模式，设计一个**同时排除两者**的实现（通常是消除让两种坏结果都可能的前提）。
- **验收标准**：① 口径裁决落库（PD 或本节内的裁决记录）；② `§474` 的失实结论**在 §475 批次文档中显式更正**（不得静默改史）；③ 「两步式」形态加机检钉死；④ 本条闭环时同步复核落册文档 §4 的映射表。
- **边界**：口径冲突属**工程裁决**，**不得**由代理单方定案后当既成事实引用；裁决前 `ISSUE-P1-537` 的整改方向以本节候选口径为准，但须标注「候选」。

> **近期闭环（指针）**：`ISSUE-P2-544`（密码库高级搜索入口「点了没反应」+ 入口收敛进溢出菜单）已于 §478 整条闭环归档（同批登记未闭环项 `ISSUE-P2-545`，见本区首条）；`ISSUE-P2-536`（同步冷启动「远端侧更新误报本地修改」）已于 §476 整条闭环归档；`ISSUE-P2-518` / `519` / `521` 已于 §463 闭环归档；`ISSUE-P2-520` 已于 §461 闭环；`ISSUE-P2-528`（换密后云端副本不被替换）已于 §467 闭环；`ISSUE-P2-530`（CM 通行密钥候选筛选与归属合一）已于 §470 闭环归档；`ISSUE-P2-529`（库文件级「保留副本」出口）已于 §471 闭环归档，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。

## P3 低危问题、特性接线与体验优化（**2 项**）

### `ISSUE-P3-541` 卫生与观测补强：`guardedScope` 的 `tag` 形同虚设 + 13 处 `SupervisorJob` 死 import + 读路径 HMAC 削峰（可选）

- **来源**：报告 B #6 / #7 + 报告 A3 的削峰建议（报告残余节）。
- **核实时间点 / 核实方式**（2026-10-08，代理现跑 grep）：15 处 `guardedScope(…)` 调用**无一传 `tag`**；`import kotlinx.coroutines.SupervisorJob` 在 13 个文件已无使用（`GuardedScope.kt` 为合法使用；`ClipboardSecurityManager` 已于 §474 删除）。
- **三条（彼此独立、可分批判）**：① `tag` 形参无人传 ⇒ 拦截日志全落同一 TAG、归因失效（要么各调用点传自身名，要么删除该形参**不留假能力**）；② 死 import 清零；③ `ProtectedString.plainBytes()` 每次读取多一次 HMAC-SHA256 + 32B 分配 ⇒ 可改 `Mac.doFinal(out, offset)` 复用线程缓冲削峰，或如实登记到 [`architecture/已知工程限界.md`](architecture/已知工程限界.md)。
- **验收标准**：① `tag` 或删除或逐点传入（二者择一，须在批次文档写明理由）；② `grep` 反校死 import 为零；③ 削峰改法跑出读数（或限界表登记）；④ 全量 `test` 绿 + `gate_readings` 全 PASS。
- **边界**：本条为**可维护性 / 观测性**，与崩溃面无关；**不得**因本条延后 `ISSUE-P1-537` / `ISSUE-P1-538`。

### `ISSUE-P3-542` `KdbxContentComparator` 等值判据对 `ProtectedString.isProtected` 敏感——该标志对标准五字段不参与序列化往返，非默认 MemoryProtection 库会恒判「有内容变化」

- **背景（§476 排查副产品，2026-10-08 探针实跑取证）**：`KdbxContentComparator.entryChanged` 的
  `a.fields != b.fields` 走 `Map` → `ProtectedString.equals`，而后者**把 `isProtected` 计入等值**；但对
  **标准五字段**该标志**不参与序列化往返**——写侧 `KdbxXmlEntrySerializer.resolveProtectedFlag` 以
  **库级 MemoryProtection 无条件覆盖** per-value `IsProtected`（对齐官方 `KdbxFile.Write.cs:838-854`，
  KDoc 明写「请勿按直觉改回 `value.isProtected || config…`」），读侧 `KdbxXmlStringNode` 从
  `Protected="True"` 属性派生 ⇒ 内存构造实例与解析实例**内容相同也可能不相等**。
- **探针读数（2026-10-08，临时探针已删）**：同一份字节两次解析 `changed=false`（两侧皆解析产物时判据
  正确——§372 真机通过的原因）；**内存树 vs 自身序列化的解析 `changed=true`**（`Title`/`UserName`：
  内存 `prot=true,len=1` vs 解析 `prot=false,len=1`；`Password` 两侧 `prot=true` 相等）。
- **现形条件（边界，如实）**：生产写入面（`VaultEntryWriteCoordinator` / `VaultEntryMapper` 等 26 处
  `ProtectedString(` 写入点已审计）对标准五字段硬编码 `isProtected=false`、口令 `true`，**恰与库级默认
  `MemoryProtection` 一致** ⇒ 默认配置库不现形；①库级配置**非默认**（如导入自其它客户端且
  `ProtectTitle/UserName/Url/Notes=True`）⇒ 任何内存写入字段与解析值恒不等 ⇒ 「无编辑也误报有变化」；
  ②日后任何以 `ProtectedString(text)` 构造默认值造标准字段的路径。后果＝变更判定多报 ⇒ 无意义重传 /
  §372 类内容级兜底失效；`KdbxMerger` 侧的 equals 比较面同样敏感。
- **核实时间点 / 核实方式**：2026-10-08 临时探针实跑（读数如上）+ 全仓 `ProtectedString(` 写入点审计 +
  `KdbxXmlEntrySerializer.resolveProtectedFlag` / `KdbxXmlStringNode` 逐行读。
- **整改方向**：`KdbxContentComparator` 对**标准五字段**改按**内容**比较（不比 `isProtected`——对标准
  字段该标志由库级配置决定、不构成树内容），**自定义 / 非标准字段保留 per-value 标志比较**（对它们
  per-value 才是被序列化的真值）；比较仍不解密、不物化明文（可复用 HMAC 等值标签）。**勿顺手把
  `ProtectedString.equals` 一并改**——其等值语义另有 2026-09 加解密审查裁决依据与既有消费面，
  改前须单独取证（§122 立的「双向显式声明、勿任一侧顺手对齐」纪律照用）。
- **验收标准**：AC① 新增用例「内存构造（`isProtected=true`）与解析同一份文件得到的实例在内容相同时
  不判为变更」且**双向**；AC② 既有 `KdbxContentComparatorTest` 的 15+12 变异项与三条刻意排除项不回退；
  AC③ `已知工程限界.md` §10 口径声明同批更新；AC④ 全量 `test` 绿 + `gate_readings` 全 PASS。
- **边界**：本条为**判据稳健性**（误报方向），与 §476 已修的「占位态证据缺失」是两个独立面；修本条
  **不得**回退 §476 / §372 的任何行为。

> **近期闭环（指针）**：`ISSUE-P3-531`（常驻通知倒计时负数修复的设备侧对照走查）与 `ISSUE-P3-532`（QQ 登录页自动填充真机走查）两条纯验证条目经用户 **2026-10-08 真机走查回执通过**，已于 §472 整条闭环归档；`ISSUE-P1-533` / `ISSUE-P2-534` / `ISSUE-P3-535`（`§473` 复核整改）已于 §474 闭环归档，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。

