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

> **暂无开放项**。

---

## P1 高危与核心功能问题（0 项）

> **暂无开放项**（`ISSUE-P1-495` 已于 §449 闭环归档，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)）。

## P2 中危缺陷与协议/测试缺口（**1 项**）

> **历史**：`ISSUE-P2-518` / `519` / `521` 已于 §463 闭环归档；`ISSUE-P2-520` 已于 §461 闭环；`ISSUE-P2-528`（换密后云端副本不被替换）已于 §467 同批闭环；`ISSUE-P2-530`（CM 通行密钥候选筛选与归属合一）已于 §470 闭环归档，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。

### `ISSUE-P2-529` 库文件级缺「保留副本」出口（四处破坏性取舍处无法「两份都留」）

- **背景（用户报告 2026-10-07）**：「云端冲突之后没有保留副本的选项，要么取消，要么删除」。核实结论＝**属实且有界**：条目级**已有**「双方保留（云端存副本）」（`KdbxMerger.resolveConflict` 的 `DUPLICATE_BOTH` 分支，§289 恢复可达），但**库文件级**没有——四处出口只有破坏性选项或干脆没有出路。
- **核实时间点与方式**：2026-10-07 主控**代码直读**（四处 UI / 流程 + 全仓字符串表）+ 与用户经 `AskUserQuestion` 核实场景。
- **四处（含涉及文件）**：
  1. **库身份绑定不符**「整库覆盖云端副本？」（`app/.../ui/screens/vault/VaultListDialogs.kt` 的 `VaultBindingTakeoverDialog`）：**覆盖并绑定当前库 / 取消**——覆盖会整体替换云端原属另一库的数据（文案自陈「无法恢复」），**无保留项**；
  2. **密码库管理「移除」**（`app/.../ui/screens/database/VaultRemovalPresentation.kt`）：云端库文件落在 `filesDir` ⇒ `PRIVATE_FILE` ⇒ **永久删除 / 取消**，**无先导出/另存副本项**；
  3. **从云端打开遇同名本地文件**（`app/.../sync/CloudVaultImporter.kt`）：只有 fail-closed 提示「请换一个展示名称后重试」，**无覆盖 / 以副本导入**出路；
  4. **冲突解决策略**（`strings.xml` 的 `sync_conflict_*` 四项）：无「保留两份」口径。
- **验收标准**：AC① 上述四处破坏性取舍处都能「两份都留」（云端副本另存为独立库 / 本地副本另存后再移除 / 同名导入以副本落地 / 策略项语义明确）；AC② 另存的副本仍是**密文文件**、以**其自身凭据**可解、并在密码库列表中可见（不得引入新的明文落盘面）；AC③ **禁假按钮**——每个新入口端到端可用并有用例钉住（本仓「机制存在 ≠ 已接线」纪律）；AC④ 副本落点与命名规则（拟 `<原名> (副本 yyyyMMdd-HHmm).kdbx`，与库文件同目录）须随批次文档登记。
- **依赖**：`ISSUE-P2-528`（§467）已闭环——它解释了「云端那份为何与新口令不一致」这一成因；本条是其**出口面**的补齐。
- **裁决口径（代理裁决，可逆）**：**不做**「冲突时自动另存副本」的自动策略（会堆积副本且语义模糊），只把「保留副本」挂在上述**人为破坏性取舍**处。

## P3 低危问题、特性接线与体验优化（3 项）

> **新增（2026-10-07，用户真机反馈）**：`ISSUE-P3-528` / `ISSUE-P3-529` 出自用户 2026-10-07 的 QQ 登录
> 填充实测截图（Android 自动填充通道，非 CM）。两条均为**只读静态核对 + 截图**产出：**未在设备上
> 复现 QQ 场景**（用户手机未连 adb；本机在连设备为 Redmi 4X / LineageOS 且未安装 QQ），故涉及
> QQ 结构树具体形态的判断已按「代码链路必经点 + 待真机 dump 定性」分别标注。认领时须按条目维护规则
> ② 先复核前提（正文行号为 2026-10-07 核对时刻的快照）。
> **同日补证（2026-10-07）**：用户要求对照同类项目（keepass2android / Monica）——已按规则 3 做**定向取证**
> 并落册 [`references/自动填充关联记忆与字段识别的参考项目对照.md`](references/自动填充关联记忆与字段识别的参考项目对照.md)
> （Kp2a 的「Remember search text?」写回 `androidapp://<包名>` 就在其自动填充流内；Monica 有互动记忆 /
> 字段签名学习 / 「填充并保存 URI」三条机制与数字类账号框的专门兜底）。两条目的验收标准已按取证结果校准；
> 与既有裁决冲突的一条（把「应用名 / 包名 token」当**准入**档，会绕过 `ISSUE-P2-46` 绑定门）**未采纳**，
> 已在该文档 §4 / §6 与 `ISSUE-P3-528` 正文显式登记为「不在本条范围」。
> **编号消歧**：同日的并行工作线（§467）另行登记了 **`ISSUE-P2-528`**（换密后云端副本，已闭环）与
> **`ISSUE-P2-529`**（库文件级「保留副本」出口），与本条目的 `ISSUE-P3-528` / `ISSUE-P3-529`**仅为数字相同、
> 主题无关**（跨严重度同数字在本仓既有先例，如 `P2-518` 与 `P3-518`）。**另：§467 批次号已被该并行线占用，
> 本两条归档时须顺延取 §468 及以后**。

### ISSUE-P3-528：自动填充「手动选择器」不写回应用关联——纯 App 场景每次都要手动重选（缺「记住」闭环）

- **核实时间点**：2026-10-07；**核实方式**：用户真机截图 2 张 + 逐行核对填充候选链与选择器交付链
  （截图：`Screenshot_2026-10-07-22-01-14-391_com.tencent.m.jpg` / `Screenshot_2026-10-07-22-01-17-394_com.tencent.m.jpg`，
  应用 `com.tencent.mobileqq` 登录页）。
- **背景 / 实测现象**：QQ 登录页点账号框与密码框都会弹出填充菜单，但菜单里**只有**「搜索全部条目…／
  手动选择要填充的凭据」一项 —— 即**本批自动候选数为 0**（`buildPickerDataset` 是响应里唯一的数据集）。
  用户选中条目后，密码能填进去；**但这条凭据与 QQ 的关联没有被记录**，下次仍需重新搜索手选。
  用户原话：「哪怕你选中了密码并直接输入进去，软件也不会记录匹配的软件」。
- **代码链路（三条独立事实，共同构成「关联永不落地」）**：
  1. **候选准入**（`app/.../autofill/AutofillCandidateRanker.kt:199-203`）：纯 App 表单无 `webDomain`，
     只能走包名维度；包名维度要求「调用方已完成包名+签名首次绑定」**且**条目 url 自身是
     `android://<包名>`（`app/.../passkey/DomainMatcher.kt:210-215` `isAndroidPackageMatch` 只认该字面形态）
     ⇒ 没有任何 `android://` 绑定的既有条目（导入库 / 手建 / 其它管理器迁入）**恒不入选**。
  2. **选中后的写入面**（`app/.../autofill/AutofillPickerActivity.kt:365-376`）：用户手选条目时只写
     `AutofillCallerTrustStore`（包名 + 签名摘要信任），**不触碰条目 URL**；全仓 `AutofillPackageNames.boundUrl(`
     的消费点只有编辑页应用选择器（`ui/screens/edit/EntryEditUrlField.kt:92`）与 CM 保存链
     （`data/repository/VaultEntryWriteCoordinator.kt:366,369`、`PasskeyEntryCoordinator.kt:377`），
     `autofill/` 内**零命中** ⇒ 写完信任后条目 url 依旧不匹配，下次请求仍是 0 候选。
  3. **保存通道也不补关联**（`data/repository/VaultEntryWriteCoordinator.kt:283-334`）：`saveAutofillCredential`
     命中既有条目且账密未变时**幂等早退**（`:306-308` 直接 `return KdbxResult.Success(Unit)`），
     既不新建也不补齐 URL。
- **对照先例（已定向取证，见 [`references/自动填充关联记忆与字段识别的参考项目对照.md`](references/自动填充关联记忆与字段识别的参考项目对照.md)）**：
  1. **Kp2a 就在自动填充流里做这件事**：其自动填充选择器走的任务类即 `SearchUrlTask`
     （`services/Kp2aAutofill/ChooseForAutofillActivity.cs:83`），该任务重写 `CompleteOnCreateEntryActivity`
     （`app/AppTask.cs:494-538`）弹「Remember search text?」（`Resources/values/strings.xml:368-369`），
     确认后把搜索词写进条目 URL —— 纯 App 的搜索词即 `androidapp://<包名>`
     （`services/AutofillBase/AutofillServiceBase.cs:235`）⇒ **写回的就是应用关联**；
     三个前置否决＝库只读 / 搜索词为空 / 该条目已能被搜索词命中（`AppTask.cs:499-503`）。
  2. **本仓应用内搜索侧已实现同形闭环**（`ISSUE-P3-442`：`ui/screens/vault/VaultSearchWriteBackCoordinator.kt`
     + `SearchWriteBackPolicy.kt` + 字符串 `search_write_back_*`，只改 `URL` 一个字段、`{REF}` 拒绝写回、
     只读/回收站一票否决）。
  3. Monica 的写法**不询问**，而是条目菜单并列「填充」/「填充并保存 URI」两个显式动作
     （`autofill_ng/AutofillPickerActivityV2.kt:1776-1777`、`ui/InlinePasswordDetailContent.kt:36-37,195-204`）；
     本仓取 **Kp2a 式询问**——与应用内搜索侧口径一致，避免同一产品里两种写回交互并存。
  ⇒ 缺的只是**自动填充侧**的接线（两条通道能力不对称）；但判定内核**不可直接照搬**：
  `SearchWriteBackPolicy.isUrlTerm` 只认域名形态，`android://<包名>` 恒为 false，形态口径须另立一档。
- **附带发现（本仓自查，如实登记、本条不改）**：`AutofillCandidateRanker.scoreEntry` 在 `score <= 0` 时
  `return null`，而 `APP_TITLE_MATCH`（95）加成位于其后 ⇒ 「纯 App + 调用方未绑定」场景下该维度**不可达**
  （§352 吸收的应用名维度，在它本该服务的场景里失效，当前仅剩「包名维度已授权时的排序」价值）。
- **明确不在本条范围（改的是准入面，须另立条目 + 重开 `ISSUE-P2-46` 口径后方可做）**：Monica 的
  `EXACT_APP_TITLE 95` / `PACKAGE_TOKEN_TITLE 70` 是**准入**档（`BitwardenLikeAutofillMatcherNg.kt:203-262`），
  以及其「互动记忆可直接作为零命中来源」（`MonicaAutofillServiceNg.kt:836-877` `resolveLastFilledEntry`）——
  两者都会让**未完成包名+签名首次绑定**的调用方拿到候选，与本仓 §352 已锁定的「应用名仅排序 + 纯标题相似不得入选」
  及 P2-46 绑定门冲突，故**不在本条目**内实施。
- **涉及文件（预估）**：`app/.../autofill/AutofillPickerActivity.kt`（提示与写回入口）、
  新增判定内核（形态口径与 `SearchWriteBackPolicy` 分离：本次要写的是 `android://<包名>`，
  是非域名形态）、`data/repository/VaultRepository`（已有 `updateEntryUrl` 通道，复用不新开写口）、
  `res/values/strings.xml` + `values-en/strings.xml`（成对文案）。
- **验收标准**：
  ① 用户在手动选择器内选中条目后，应用**询问是否把该调用方关联写入该条目**，**显式同意才写**：
     纯 App 写 `android://<调用方包名>`，浏览器场景写**归属校验后**的域（`resolveUsableWebDomain` 的产出，
     **不得**用表单自报的原始 webDomain）；
  ② 写入后**后续**填充请求中该条目成为自动候选（无需再手选）；用户拒绝时**不得**改动条目（含拒绝在
     会话内不被持久化为永久拒绝）；
  ③ fail-closed 边界（与 `SearchWriteBackPolicy.shouldOffer` 的四条否决**同形**：只读 / 回收站 / `{REF}` /
     已覆盖）：只读会话 / 回收站内 / url 含 `{REF:…}` 一律不写；包名非法（`AutofillPackageNames.normalize`
     为 null）或签名摘要不可读时**不产生**降级写入；写回只改 `URL` 一个字段（复用 `updateEntryUrl`），
     `Override URL` 与自定义字段零触碰；
  ④ 新增判定内核配 JVM 正/反两态用例；`.\gradlew.bat test` 全绿 **且** `python tools/doc/gate_readings.py`
     全 PASS，读数块原样入批次文档；真机走查按 §434 先跑 `python tools/device/check_installed_build.py
     --expect-symbol <本批新增符号>`。

### ISSUE-P3-529：纯 App 登录表单「账号框」识别覆盖不足——QQ 号类中文提示 + 数字类 inputType，致只填密码不填账号

- **核实时间点**：2026-10-07；**核实方式**：用户真机截图 2 张（QQ 登录页提示文案「输入QQ号」/「输入QQ密码」，
  点账号框弹**数字键盘**）+ 逐行核对识别链 + **词表现查比对**（`AutofillFieldLexicon` 逐项 contains/token 比对）。
- **背景 / 实测现象**：QQ 登录页点账号框弹出填充菜单后选中条目，**只有密码框被填入，账号框留空**
  （用户原话：「最后也只能填充密码，不能账号和密码一起填充」）。同页在**点密码框**时同样只填密码。
- **代码链路（三处可独立成立的覆盖缺口，叠加后账号框既不被识别也不被兜底合成）**：
  1. **中文提示词表无「号」类泛化项**（`app/.../autofill/AutofillFieldLexicon.kt:10-20`）：
     `USERNAME_SUBSTRING_TERMS` 有「手机号 / 手机号码 / 电话号码 / 账号 / 登录名…」但**没有** QQ 号这类
     「<名称>+号」形态；且 `TOKEN_SPLIT_REGEX` 把 CJK 视作 `\p{L}`，提示「输入QQ号」整串是**一个 token**，
     `USERNAME_TOKEN_TERMS` 亦不命中 ⇒ 两侧均不识别。
  2. **数字类 inputType 不算账号类**（`app/.../autofill/AutofillFieldScanner.kt:259-266`）：
     `isAccountInputType` 只认 `TYPE_CLASS_PHONE` 与邮箱变体；`TYPE_CLASS_NUMBER`（截图数字键盘所对应的类）
     返回 false。
  3. **兜底合成对数字类字段不生效**（`app/.../autofill/AutofillFieldFallback.kt:97-137`，闸门在 `:132-137`
     `isSynthesizableInput`）：该兜底本意是「识别到密码框但缺账号框时，把聚焦字段合成为账号目标」，
     但其输入类型闸门只放行「未知（0）/ 纯文本 / 账号类」，`TYPE_CLASS_NUMBER` 被判 false ⇒ 有密码框
     也合不出账号框。
  ⇒ 三条叠加后 `usernameId == null`，交付数据集只含密码（`buildAuthenticationResultDataset` 只写非空字段，
  `app/.../autofill/AutofillAuthResultDelivery.kt:70-84`）⇒ 与截图现象一致。
  **具体哪一处是 QQ 的触发点未定性**（需结构树 dump；三处均为**可独立成立**的覆盖缺口，故本条不押注单点）。
- **为何不是「已接受限界」**：数字类账号框（QQ 号 / 工号 / 学号 / 会员号）在中文应用里常见，且
  `docs/architecture/已知工程限界.md` 与 `产品裁决登记.md` 均无该形态的登记；这是**覆盖缺口**而非取舍。
- **对照先例（已定向取证，见 [`references/自动填充关联记忆与字段识别的参考项目对照.md`](references/自动填充关联记忆与字段识别的参考项目对照.md) §3.2）**：Monica 对同一形态有**专门实现**——
  `TYPE_CLASS_NUMBER` + `TYPE_NUMBER_VARIATION_NORMAL` 直接产出账号候选
  （`EnhancedAutofillStructureParserV2.kt:1702-1718`），精度 `LOW`
  （`AutofillDetectionPolicy.kt:74` `genericNumberFallbackAccuracy()`），
  **保留门槛＝「表单存在密码目标」**（`:96-102` `shouldKeepTarget`：账号类「精度 ≥ MEDIUM **或** 有密码目标」）；
  可见性另有一道更严的门（`:104-115`：账号类须 ≥ MEDIUM）⇒ 数字兜底**不会**让隐藏框被误收。
  其注释两次点名 QQ（`:1648`「QQ 搜索框误弹修复」、`:1857`「无 password 术语的 QQ 搜索框不受影响」）
  ⇒ 该兜底是**带登录上下文门**的，不是无条件放宽。上游出处为其自述对齐的 Bitwarden
  （`updateForMissingPasswordFields`、「有 Login 字段就 Fillable」）。**Kp2a 侧本次取证未命中**同形兜底
  （未命中声明见对照文档 §5，不得读作「Kp2a 没有该能力」）。
- **验收标准**：
  ① 纯 App（无 `webDomain`）登录表单中「数字类账号框 + 密码框」被识别为账号 / 密码目标，填充时**账密同时写入**；
  ② **不得放宽**既有排除口径：搜索框 / 非凭据字段 / OTP 框仍排除，不可见账号框仍不参与，
     `importantForAutofill=no` 仍尊重，`FieldConfidence` 档位与「多候选择优」语义不变；
  ③ 整改口径取 **Monica 式**（对照文档 §4 第 6 行）：在**首轮**字段识别加「`TYPE_CLASS_NUMBER`（普通数字变体）
     ⇒ 账号候选（`FieldConfidence.LOW`）」一档，并在目标解析处施加**「表单存在密码目标才保留」**门
     （对齐 `shouldKeepTarget`）；可见性口径不动（账号类不可见仍须较高中等精度才准入）。
     **不**采用泛化「号」子串（Monica 亦为枚举「工号 / 学号 / 职工号 / 员工编号」，泛化会在「订单号 / 工单号」
     上误伤）；**不**以 `AutofillFieldFallback.isSynthesizableInput` 为主修（该兜底只覆盖「单字段聚焦」形态，
     覆盖不到两个框都不聚焦的场景，可作为次要不变量同步维护）；
  ④ 反例用例必配（三条，锁定不得回归）：① 无密码框的表单里纯数字框（数量 / 金额 / 搜索）**不得**入选账号目标；
     ② 页面显式声明 OTP 的框仍排除；③ 无 password 术语的搜索框（Monica 踩过的「QQ 搜索框」形态）仍排除；
  ⑤ **真机取证先行**：先在设备上 dump 该表单结构树（`hint` / `idEntry` / `inputType` / `importantForAutofill`
     四项读数）并在批次文档留痕，再据实测形态定点整改；`*/src/androidTest/**` 的新增或修改用例必须真机实跑
     （测试资产纪律②）；
  ⑥ `.\gradlew.bat test` 全绿 **且** `python tools/doc/gate_readings.py` 全 PASS，读数块原样入批次文档。

> **新增（2026-10-07，用户真机反馈）**：`ISSUE-P3-531` 是 `ISSUE-P3-530`（用户反馈「密码库锁定倒计时会
> 出现负数」，经 `AskUserQuestion` 追问确认出现在**通知栏常驻通知**）整改后的**待办收尾**——整改本身
> 已由批次 `§469` 闭环并有宿主守卫（纯函数边界用例 + 接线守卫先跑出红），但建成效果**依赖 OEM 通知面**，
> 须在设备上对照走查。`ISSUE-P3-530` 已闭环，**不留在本清单**。

### ISSUE-P3-531：常驻通知倒计时负数修复的**设备侧对照走查**未做（`ISSUE-P3-530` / §469 的收尾义务）

- **核实时间点**：2026-10-07；**核实方式**：`adb devices -l` 现查**无任何设备在位**，故 §469 的整改
  只有宿主证据（5 例纯函数边界用例 + 1 条接线守卫〔先跑出红〕+ 全量 `test` + 门禁 9/9），**无设备读数**。
- **背景**：`ISSUE-P3-530` 的机制面（到期撤销交系统侧 `setTimeoutAfter`、「倒计时 → 非倒计时」重投前
  先撤销、非倒计时态不设 timeout）已按 AOSP `NotificationManagerService` 源码接线并有宿主守卫，
  但**建成效果依赖 OEM 通知面**——本仓已有先例：§428～§432 的通道重要度 / 展开态样式 / 动作落点三项
  **都只有装机回执才能判定**，且 KeePassDX 源码那句「Won't work with Xiaomi」在本机并不成立。
  故本条的验收必须落到设备上，不得以宿主全绿代替。
- **涉及文件**：无（纯验证条目）；被测对象＝`app/src/main/java/com/keepasskey/app/notification/`
  `UnlockedNotificationController.kt` 与 `NotificationGate.kt`（§469 已入库）。
- **验收标准**（在**无待保留数据**的设备或 AVD 上执行；§263 前置：先用 `adb devices -l` 确认设备，
  装机前按 §434 跑前置读数）：
  ① `python tools/device/check_installed_build.py --expect-symbol autoLockCountdownTimeoutMs` 退出码 0；
  ② 设置页把「自动锁定倒计时」设为 1 分钟 → 解锁 → 退到后台（常驻通知出现秒级倒计时）；
  ③ `adb shell dumpsys alarm` 出现**由平台包**为本通知排定的 timeout 精确闹钟（`ELAPSED_REALTIME_WAKEUP`）；
  ④ `adb shell am kill <包名>` 模拟进程回收（**不得**用 `force-stop`：它本身就会撤掉通知，造成假绿）→
  等过截止时刻后通知**自行消失**（`adb shell dumpsys notification --noredact` 中不再有该条），
  **而不是**留在通知栏显示负数；
  ⑤ 补一态（§469 新增的防回归点）：截止前回前台 → 通知回落固定文案，且**在原截止时刻不被无声撤掉**
  （由「重投前先撤销」保证）。
- **如实声明**：`uiautomator dump` 对带秒级倒计时的通知恒报 `could not get idle state`
  （`PD-71` 已记）⇒ 判据取 `dumpsys alarm` / `dumpsys notification` 的**结构读数**，不依赖无障碍树文本。
