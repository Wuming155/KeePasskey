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

> **暂无开放项（全量待办归零）**。2026-09-25 解锁节流完整性层 fail-closed 缺陷与冗余性裁决
> （`ISSUE-P1-277`，完整性层整体移除 / 基础节流保留，`PD-46`）闭环见 §334。

---

## P2 中危缺陷与协议/测试缺口（9 项）

> **开放项 9 条**（这里只列**各条还欠什么**；历史闭环流水一律见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)）：
> ① `ISSUE-P2-378` —— 外部修改检测与重载提示缺失（外部改动后保存静默覆盖）；
> ② `ISSUE-P2-379` —— 前台闲置自动锁定缺失；
> ③ `ISSUE-P2-380` —— 结构化卡片填充与内置信用卡模板脱节（§355 遗留「另立条目」落账）；
> ④ `ISSUE-P2-381` —— WebDAV MOVE 覆盖 409 无兜底；
> ⑤ `ISSUE-P2-383` —— Autofill webDomain 混域无一致性校验；⑥ `ISSUE-P2-384` —— 字段拉黑后认证回传不复检；
> ⑦ `ISSUE-P2-385` —— customData 不参与 KDBX 合并；⑧ `ISSUE-P2-386` —— 同名附件保存折叠；
> ⑨ `ISSUE-P2-387` —— passkey signCount 多设备同库分叉。
> 另：2026-09-29 两轮对照登记的 `ISSUE-P2-382`（事务上传临时名改扩展名）已于 §358 整条闭环
> （AC③ 真实服务器覆盖写链路待 P2-381 同批实测，见批次 §1.3）。
> 来源：2026-09-28 参考项目对照调研（Monica / KeePassDX / keepass2android，提出者/独立核实员/基线盘点
> 三源隔离核实），共 12 条候选、11 条核实成立后登记（P2-378~380 与 P3-381~388）；
> 候选第 12 条「云同步协议广度（WebDAV+S3 之外的主流网盘/SSH 通道）」经用户明示**不登记**。
> 另：2026-09-29 参考项目两轮对照（注释踩坑标注扫描 + git 历史修复/防护挖掘）登记
> `ISSUE-P2-381` ~ `ISSUE-P2-387` 与 `ISSUE-P3-389` ~ `ISSUE-P3-394` 共 13 条（核实方式见各条），
> 证据全文见 [`references/参考项目踩坑对照/00-对照总表.md`](references/参考项目踩坑对照/00-对照总表.md)
> 与 [`references/参考项目Git历史对照/00-对照总表.md`](references/参考项目Git历史对照/00-对照总表.md)
> （均含独立复核记录；两份总表已登记文档地图）。
> 历史闭环：2026-09-28 深度交互审查登记的 5 条（`ISSUE-P2-353` ~ `ISSUE-P2-357`）当日整批整改闭环，
> 归档见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md) §349 与批次正文
> [`349-交互体验深度审查整改批次.md`](resolved/batches/349-交互体验深度审查整改批次.md)；
> 2026-09-27 两条真机手测缺陷（`ISSUE-P2-341` / `ISSUE-P2-343`）分别收口于 §343 与 §344；
> 更早的 P2 闭环流水见 `RESOLVED_LOG.md` §315 ~ §325。

### ISSUE-P2-378：已打开库文件的外部修改检测与重载/合并提示缺失——外部改动后保存静默覆盖

- **状态（2026-09-28 登记）**：开放。源自 KeePassDX 对照（核心参考）。
- **背景与整改依据**：本地 `File` 与 SAF `content://` 两条打开路径均不记录打开时刻的文件快照
  （`app/.../data/repository/VaultLifecycleCoordinator.kt:45-101`）；保存侧
  `database/.../session/SessionPersistence.kt:42-99` 的 `save()` 只做只读态/凭据检查后即以内存树序列化写盘，
  无任何「文件自打开后是否被外部改动」的 mtime/size/摘要前置校验；`SessionFileWriter.kt:26-32`
  仅原子写 + 可选单份 `.bak`。外部程序改动已打开库（他端写入、云盘双向同步、恢复备份）后应用无任何提示，
  下一次保存直接以内存旧树整档覆盖，外部新增内容静默丢失——仅靠用户未必自知的单份 `.bak` 兜底。
- **参考对照**：KeePassDX `DatabaseTaskNotificationService.kt:200-243` 的 `checkDatabaseInfo()`
  以 mtime+size 比对 + 10 秒去抖 + `indicateNotSavedData()` 提示，并经 `:352-355` 的
  `ACTION_DATABASE_MERGE_TASK` / `RELOAD_TASK` 出口走重载或合并。
- **核实时间点 + 核实方式**：2026-09-28，对照调研独立核实子代理（与提出者上下文隔离）自行执行：
  `rg "FileObserver|WatchService|watchFile|lastModified\(\)"` 于 app/database/sync 三模块 main 源码零命中
  （`lastModified` 仅 sync 远端 HTTP 面，不覆盖本地/SAF 已打开库）；直读上述三个文件逐行核对；
  对照《已知工程限界》§24（只登记 SAF 写回非原子且明写不得外推）与《产品裁决登记》（PD-46 为节流完整性面）
  确认非已裁决取舍；`ACTIVE_ISSUES` 与 `RESOLVED_LOG` 最近批次（§352~§356）均未涉及本面。
- **涉及文件**：`database/` session 持久化与写盘、`app/` 生命周期协调与解锁/编辑 UI 提示面。
- **AC**：①打开库时留存基线快照（mtime+size；SAF 路径取 DocumentFile 元数据，本地取 File 属性）；
  ②「保存时」与「回到前台」两个时点校验基线，漂移即 fail-closed 中止覆盖并提示用户三选
  （重载 / 走既有 `KdbxMerger` 合并 / 放弃），**不得静默整树覆盖**；
  ③不引入常驻 FileObserver（限界 §24 语境下 SAF 不参与监听，检测只挂上述两时点）；
  ④测试覆盖：本地与 SAF 两路径 × 基线命中/漂移两分支；
  ⑤与同步远端比对链路（既有 ETag 面）职责不重叠的口径说明落批次文档。
- **补充（2026-09-29 参考项目 git 历史对照）**：keepassxc 提交 `811887e5`（#10612「Fix issues with
  reloading and handling of externally modified db file」）与本条同面，其修复语义可作 AC② 的实现参照——
  外部变更检测、重载期间禁保存、重载失败弹解锁并标记脏、保存前走合并续跑；另 keepass2android
  `BuiltInFileStorage.cs:101` 注释实证文件系统 LastWriteTime 毫秒可被截断，AC① 的 mtime 基线比对须按
  「≥1 秒粒度阈值 + size」双条件、漂移判不敏感方向（宁可多提示不漏报）。证据全文见
  [`references/参考项目Git历史对照/04-keepassxc.md`](references/参考项目Git历史对照/04-keepassxc.md)
  与 [`references/参考项目踩坑对照/03-keepass2android.md`](references/参考项目踩坑对照/03-keepass2android.md)。

### ISSUE-P2-379：前台闲置自动锁定缺失——无「前台无操作超时」会话刷新机制

- **状态（2026-09-28 登记）**：开放。源自 Monica 对照（UI 补充参考）。
- **背景与整改依据**：`AutoLockManager.kt` 仅两个触发点：`onStop` 调度后台延迟锁定（148-173，到点即锁）
  + `ACTION_SCREEN_OFF` 熄屏广播（72-80）；`onStart`（127-141）只做后台停留时长补偿判定。
  全仓生产代码无 `onUserInteraction` 类交互刷新机制；设置面仅 `autoLockBackground` +
  `autoLockTimeoutSeconds`（`SettingsRepository.kt:29-30`，语义为后台超时档）。
  亮屏停在前台（平板/阅读态/系统息屏设长）时，解锁的库内明文会话无任何超时兜底——
  这是超出系统息屏之外的独立安全网（Monica/KeePassXC `InactivityTimer` 同类）。
- **参考对照**：Monica `SessionManager`/`MainAppLockPolicy` 监听 `onUserInteraction()` 刷新会话计时器
  （`docs/references/Monica-架构分析.md:257`）。
- **核实时间点 + 核实方式**：2026-09-28，独立核实子代理：`rg -i "onUserInteraction|inactivity|idle"`
  （排除 `参考项目/`）生产代码零命中（命中均为测试 `advanceUntilIdle()` 与参考文档）；
  `app/src/main` 对 `onTouchEvent|dispatchTouchEvent|setOnTouchListener|MotionEvent` 检索仅命中
  遮挡触摸过滤（`ObscuredTouchPolicy`/`SecureDialog`）；通读 `AutoLockManager.kt` 与
  `AutoLockSessionGuard.kt:46-80`；《产品裁决登记》《已知工程限界》检索「前台/闲置/息屏」零命中
  （PD-13/限界 §26 是「完整性风险不驱逐会话」，主题不同）。
- **涉及文件**：`app/` 自动锁（AutoLockManager / AutoLockSessionGuard）、设置页安全分区、触摸事件分发点。
- **AC**：①新增「前台无操作超时」触发点，与既有熄屏/后台超时并列，开关与档位入设置面（默认值先裁决留痕）；
  ②交互刷新语义明确（哪些事件重置计时），与自动填充会话、剪贴板清理等既有会话口径一致，
  不出现「一边判活一边已锁」的矛盾态；③时钟回拨 fail-closed（对齐 §354 `AutofillActivePromptThrottle` 同款纪律）；
  ④新增用例：闲置到点锁定 / 交互刷新 / 设置关闭三态 + 回拨负例。

### ISSUE-P2-380：结构化卡片填充与内置「信用卡」模板脱节——模板路径条目恒被结构化填充排除（§355 遗留「另立条目」落账）

- **状态（2026-09-28 登记）**：开放。源自 Monica 对照；同时即 §355.4 声明的
  「录入面未做/另立条目评估」的落账——该承诺此前未落本文件（与规则 6.1 相悖），本条补登。
- **背景与整改依据**：`StructuredFieldPolicy.kt:58-67` 角色→字段名按 Android hint 惯例
  （`creditCardNumber` 等），`:191 valuesFor` 与 `:207-210 hasField` 均按 `it.key == fieldNameFor(role)`
  精确匹配；而内置「信用卡」模板产出中文键自定义字段「持卡人/卡号/有效期/CVV/PIN」
  （`VaultTemplateFactory.kt:59-70`，常量 `VaultEntryMapper.kt:348-354`），且
  `EntryEditFormProjection.kt:88 applyTemplateEntry` 原样复制 customFields
  ⇒ 走内置模板（最常见录入路径）的条目**永不含 hint 名键**，`selectCandidates`（`:169-179`）的
  `requiredRoles.all{hasField}` 必然将其排除，§355 交付的结构化卡片填充对该路径**静默失效**
  （用户须自行知晓并手工创建 `creditCardNumber` 等英文命名字段）。无桥接：`VaultEntryMapper` 的 EN 回退是
  "Card Number"/"Expiry" 等人类可读名而非 hint 名，autofill 包不消费 `VaultEntryMapper` 取值；
  `rg "creditCardNumber|streetAddress"` 在非 autofill 生产代码零命中。另：证件类零模板零角色
  （`rg -i "证件|passport|identity|身份证"` 于 app ui/data 零命中），编辑页无卡/地址/证件专用录入表单
  （`VaultEntryCardLayouts.kt:52` 的「信用卡拟真」是展示视图非录入表单）。
- **核实时间点 + 核实方式**：2026-09-28，独立核实子代理逐文件核对上述行号与匹配逻辑；
  《产品裁决登记》《已知工程限界》检索「卡/地址/证件/结构化」无本面取舍（命中均为 SSRF、边界瞬时 String 等）；
  §355.4 的「另立条目」在 `ACTIVE_ISSUES` 无对应条目。
- **涉及文件**：`app/.../autofill/StructuredFieldPolicy.kt`、`VaultTemplateFactory.kt`、
  `VaultEntryMapper.kt`、`EntryEditFormProjection.kt`、模板常量与编辑录入面。
- **AC**：①先做桥接口径裁决并留痕《产品裁决登记》（二选一）：结构化识别同时接受内置模板中文名
  （含 `VaultEntryMapper` 既有 EN 回退名）映射到对应角色，**或**模板产出改用 hint 名键——
  裁决时以 KeePassXC 合并 schema / 官方 C# 为互操作裁决者，评估对存量库条目的影响；
  ②存量条目口径有结论（已按中文名录入的条目如何被识别，是否迁移）；
  ③端到端证据：用内置信用卡模板新建条目后，卡片表单可获得结构化填充候选（instrumented 或等价实证）；
  ④证件类是否入角色表**另立条目**评估，不并入本条强行做。

### ISSUE-P2-381：WebDAV MOVE 覆盖已有目标普遍 409 无兜底——保存永久失败且错误不可区分

- **状态（2026-09-29 登记）**：开放。源自参考项目对照双源（keepass2android 注释标注 + git 提交 `9c8ee243`）。
- **背景与整改依据**：`WebDavSyncProvider.kt:349-389`（快照行号）的 `uploadAtomic` 以
  「PUT 临时名 → MOVE 覆盖」提交，MOVE 仅带 `Overwrite:T` + `If` ETag 预条件且只特判 412；
  409/423 落入 else 分支后第二次尝试同错，最终抛 `ProtocolError(500)`「WebDAV 原子写入 MOVE 失败，
  已清理临时文件」。keepass2android `WebDavStorage.java:235` 注释 + 提交 `9c8ee243`（2025-11-09）实证：
  多台服务器对 MOVE 覆盖已有目标报 409，仅靠 `Overwrite:T` 不可靠；其修复 = 先 DELETE 目标
  （容忍 404）再 MOVE 并对 409 重试。本仓遇该类服务器时保存永久无法收敛且错误文案无法区分该场景。
- **核实时间点 + 核实方式**：2026-09-29 参考项目对照工作流：排查子代理定位后由独立复核子代理
  （上下文隔离）实读 `:349-389` 确认无 409/423 分支（`rg -n "409|423"` 全文件零命中，exit=1 复跑证实）；
  《产品裁决登记》《已知工程限界》无本面取舍。证据见
  [`references/参考项目Git历史对照/02-keepass2android.md`](references/参考项目Git历史对照/02-keepass2android.md)。
- **涉及文件**：`sync/src/main/java/com/keepasskey/sync/webdav/WebDavSyncProvider.kt`。
- **AC**：①MOVE 收到 409/423 时先 DELETE 目标（404 容忍，`:408` 已有同语义）再单次重试并留日志；
  ②409/423 与 412 分开报错文案；③不采纳兜底则按规则 6.1 在《已知工程限界》登记
  「MOVE 覆盖遇 409 服务器不可用」并写明触发面。

### ISSUE-P2-383：Autofill 结构树 webDomain 混域无一致性校验——受信浏览器下凭据可填进异域 iframe 字段

- **状态（2026-09-29 登记）**：开放。源自 keepass2android git 提交 `e2e7666c`（2018-12-10）。
- **背景与整改依据**：`AutofillFieldScanner.kt:177-190`（快照行号）对标准 Autofill 结构树取
  「首个非空 webDomain 胜出」（`:185-187`），`AutofillOriginResolver.kt:43-75` 只对该单域做
  受信浏览器 / DAL 归属校验，同一填充结构中顶层页域与 iframe 域混合时**无任何一致性拒绝**。
  keepass2android 的同型缺陷是「一致性校验条件写反」（`==` 应为 `!=`，修复后同结构混域即抛
  `SecurityException`）；本仓连校验都不存在——受信浏览器场景下凭据可被填进另一域的 iframe 字段，
  归属展示与实际落点背离。
- **核实时间点 + 核实方式**：2026-09-29 参考项目对照工作流：排查子代理逐行核对 scanner 与
  origin resolver 两处；独立复核子代理实读确认「无校验」定性；《产品裁决登记》《已知工程限界》
  无本面取舍（PD-32/33 为 DAL 与 caller origin 归因口径，主题不同）。证据见
  [`references/参考项目Git历史对照/02-keepass2android.md`](references/参考项目Git历史对照/02-keepass2android.md)。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/autofill/AutofillFieldScanner.kt`、
  `AutofillOriginResolver.kt`。
- **AC**：①先裁决混域处置口径（整结构拒绝 vs 只取主文档域并丢弃异域子树）并留痕；②实施 +
  负例用例（顶层域 A + iframe 域 B 的结构不出候选、纯同域结构不受影响）；③归属展示与实际落点一致的口径说明。

### ISSUE-P2-384：字段拉黑后认证回传不复检、无 ignored-ids——系统缓存重放可交付被屏蔽字段值

- **状态（2026-09-29 登记）**：开放。源自 Monica git 提交 `c854ef2a`
  （「Invalidate cached autofill prompts when fields are blocked」）。
- **背景与整改依据**：本仓字段级屏蔽（`ISSUE-P3-43` ②的角色级 blocklist）判定**只**挂在
  onFillRequest 的目标解析期（`AutofillFieldBlockPolicy.decide` 唯一调用点 =
  `AutofillTargetFieldResolver.kt:85`，快照行号）；认证回传链
  （picker `confirmAndFill` → `AutofillAuthResultDelivery.kt:49`）按认证 Intent 携带的
  usernameId/passwordId 构造 Dataset **不复检** blocklist（AutofillAuthResultDelivery /
  PickerViewModel / ConfirmActivity 中 blocklist 检索零命中）；且全 autofill 包无
  `setIgnoredIds`/`setClientState`（grep 零命中）。Monica 已实证的框架行为：屏蔽写入后系统侧
  缓存响应重放 / 再次确认时，被屏蔽字段的值仍可交付。
- **核实时间点 + 核实方式**：2026-09-29 参考项目对照工作流：排查子代理逐调用点核对 +
  独立复核子代理实读四处并复跑零命中检索；《产品裁决登记》《已知工程限界》无本面取舍。证据见
  [`references/参考项目Git历史对照/03-Monica.md`](references/参考项目Git历史对照/03-Monica.md)。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/autofill/` 的 TargetFieldResolver /
  AuthResultDelivery / PickerActivity / FieldBlockPolicy。
- **AC**：①认证回传构造 Dataset 前复检 blocklist（fail-closed：被屏蔽即不交付该字段）；
  ②评估 `setIgnoredIds` / `setClientState` 使系统侧缓存响应失效（与 Monica 修复同型）；
  ③用例覆盖「屏蔽后缓存重放」与「屏蔽后再次确认」两路径均不交付。

### ISSUE-P2-385：customData 不参与 KDBX 合并——第三方扩展键（浏览器键等）经同步静默丢失

- **状态（2026-09-29 登记）**：开放。源自 keepassxc git 三提交同根
  （`e367c6df`「Fix merging browser keys」/ `c19703c3`「Merge custom data only when necessary (#3475)」/
  `94ace985`「Preserve Secret Service exposed group setting on merge」）。
- **背景与整改依据**：条目级 `KdbxEntryMerger.kt:157-172`（快照行号）`isModified` 不比较 customData、
  `:232-239` 冲突路径的 copy 清单不含 customData（以本地为底版）；组级 `KdbxGroupMerger.kt:225-238,245-269`
  的 `MERGED_GROUP_FIELDS` 与 both-modified 合并同样不含 customData。⇒ 远端改动或第三方写入的
  CustomData（keepassxc 浏览器扩展键、Secret Service 暴露组标记等）经本仓合并会被静默丢弃。
  本仓组级 customData 暂无生产写入者（`ChildDatabaseSessionManager.kt:28-30` 明示不写入根库），
  但同步来库可含第三方标记。keepassxc 三提交分别实证：无条件覆盖丢浏览器键（受保护键清单修复）、
  新旧比较方向写反、暴露组标记被覆盖。
- **核实时间点 + 核实方式**：2026-09-29 参考项目对照工作流：排查子代理逐清单核对、
  独立复核子代理实读两 merger 与 ChildDatabaseSessionManager 确认（含一处路径补正）；
  《产品裁决登记》《已知工程限界》无本面取舍。证据见
  [`references/参考项目Git历史对照/04-keepassxc.md`](references/参考项目Git历史对照/04-keepassxc.md)。
- **涉及文件**：`sync/src/main/java/com/keepasskey/sync/merge/KdbxEntryMerger.kt`、
  `KdbxGroupMerger.kt`；`core/` customData 模型面。
- **AC**：①条目级与组级 customData 纳入合并词汇表（参与 isModified 比较与 both-modified 处置），
  第三方键默认保留（与 keepassxc 受保护键清单语义对齐）；②与 KPEX 通行密钥字段面的
  schema 口径核对（哪些键属受保护清单）并以互操作对拍自证（规则 8：`keepassxc-cli` / `pykeepass` 场景）；
  ③用例覆盖「远端新增键保留 / 双方同键不同值」两分支。

### ISSUE-P2-386：同名附件经编辑保存被折叠——mergeAttachments 按名称匹配丢数据

- **状态（2026-09-29 登记）**：开放。源自 KeePassXC `KdbxXmlReader.cpp:913` 注释标注（注释批次）与
  本仓编辑保存路径实读（git 批次佐证）。
- **背景与整改依据**：`VaultEntryWriteCoordinator.kt:214-224`（快照行号）的 `mergeAttachments`
  按名称 `firstOrNull` 匹配既有附件；而 KDBX 同名附件是合法形态——KeePassXC 注释实证
  KDBX 3.x 下同一附件键名可重复出现且值不同（其修复 = 加随机前缀改成唯一键把两份都保留）。
  打开的库中某条目若含两份同名附件，经本仓编辑保存即被折叠为一份，数据静默丢失。
- **核实时间点 + 核实方式**：2026-09-29 参考项目对照工作流：注释批次独立复核子代理实读
  `:214-224` 确认匹配语义；《产品裁决登记》《已知工程限界》无本面取舍。证据见
  [`references/参考项目踩坑对照/05-KeePassXC.md`](references/参考项目踩坑对照/05-KeePassXC.md)。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/data/repository/VaultEntryWriteCoordinator.kt`。
- **AC**：①改按下标 / refIndex 身份匹配（与导出侧 `ISSUE-P3-295` 同口径），或对「同名既有附件
  多于 UI 份数」保留无法匹配的余份；②用例覆盖「两份同名附件经编辑保存仍为两份」；
  ③暂不实现则按规则 6.1 补登《已知工程限界》并注明触发面。

### ISSUE-P2-387：passkey signCount 同库多设备分叉——合并按 LMT 取胜方，RP 单调性校验拒签

- **状态（2026-09-29 登记）**：开放（先评估口径再实施）。源自 Monica
  `PasskeyAuthActivity.kt:477` 注释标注 + git 历史佐证。
- **背景与整改依据**：本仓自产凭据 signCount 从 0 起步（`PasskeyKeyGeneration.kt:81`）并在断言前
  原子递增落库（`PasskeyAssertionActivity.kt:240` / `core` `PasskeyData.kt:398-410`）；
  条目合并时 SignCount 走自定义字段按最后修改时间取胜方（`KdbxEntryMerger.kt:341-379`，取 LMT 在
  `:373`，快照行号），**无数值 max 特例** ⇒ 同库多设备（或经同步 / 恢复的副本）各自递增后
  计数器必然分叉，RP 侧「new ≤ stored 判克隆嫌疑」的单调性校验会拒签——Monica 同型实证。
- **核实时间点 + 核实方式**：2026-09-29 参考项目对照工作流：注释批次独立复核子代理实读
  断言递增点与合并取胜方两处确认；已知限界表仅登记**导入凭据**的 signCount 面（§ 多设备自产
  分叉未登记）；PD-49 已实测否证「恒写 0」路线。证据见
  [`references/参考项目踩坑对照/04-Monica.md`](references/参考项目踩坑对照/04-Monica.md)。
- **涉及文件**：`sync/src/main/java/com/keepasskey/sync/merge/KdbxEntryMerger.kt`、
  `core/` PasskeyData 字段面。
- **AC**：①先做口径评估并留痕：对 SignCount 合并特例取 max（`FIELD_SIGN_COUNT` 在 core，
  sync→core 依赖方向合法；isProtected=false 不触敏感数据铁律）**或**登记《已知工程限界》
  （「同库多设备自产通行密钥计数器分叉」）；②不得采纳「断言恒写 0」（PD-49 已实测否证）；
  ③评估结论落《产品裁决登记》或《已知工程限界》后按结论实施或归档。

## P3 低危问题、特性接线与体验优化（10 项）

> **开放项 10 条**（这里只列**各条还欠什么**；历史闭环流水一律见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)）：
> ① `ISSUE-P3-339` —— **用户指示暂时搁置**（浏览器半环需外部域名与信任链资源）；
> ② ~ ⑧ `ISSUE-P3-381` ~ `ISSUE-P3-387` —— 2026-09-28 参考项目对照调研登记的 7 条
> 特性/体验缺口（回前台同步探测、重复条目去重、Steam TOTP、.kdbx 并入、库级 Meta 编辑、
> 通知锁定按钮、远端目录浏览）；同批来源与「云同步协议广度不登记」的
> 用户裁决见 P2 区引言——原第 8 条 `ISSUE-P3-388`（插件宿主体系）经用户 2026-09-29 裁决
> 「不接入第三方插件」，按其 AC② 登记 [`PD-56`](architecture/产品裁决登记.md) 并归档
> [`RESOLVED_LOG.md`](RESOLVED_LOG.md) §357；
> ⑨ `ISSUE-P3-392` —— 原生 KDF 探活失败静默回落不可观测；
> ⑩ `ISSUE-P3-393` —— 填充链（Autofill + CM）不检查条目过期。
> 另：2026-09-29 两轮对照登记的 `ISSUE-P3-389` / `P3-390` / `P3-391` / `P3-394`
> 四条已于 §358 整条闭环（P3-391 的 OEM 真机面为遗留验证项，见批次 §1.3）。
> Monica 自动填充吸收已全部闭环：`ISSUE-P3-371` / `ISSUE-P3-372`（§352）、
> `ISSUE-P3-373` / `ISSUE-P3-376`（§353）、`ISSUE-P3-374`（§354）、`ISSUE-P3-375`（§355）、
> `ISSUE-P3-377` 分值档对账（§356）；
> 原 `ISSUE-P3-366` ~ `ISSUE-P3-370` 五条已于 §351 整批闭环归档（366~368 实施、369/370 复核否决）；
> `ISSUE-P3-361` ~ `ISSUE-P3-365`（假开关清点五条）已于 §350 整批闭环归档。

### ISSUE-P3-339：仿冒域能否唤醒通行密钥（本地 RP 实验室 + 四类仿冒 origin 的「该醒 / 不该醒」）——**代码层已闭环，浏览器半环搁置**

- **状态（2026-09-27 用户指示）**：**暂时搁置**。剩余部分需要外部资源（自有域名 + ACME DNS-01，
  或企业策略放行自建 CA，或换自带信任库的浏览器并相应重述判据），用户裁「先精简描述、暂不做」。
- **威胁模型（本条的立规前提，勿在后续复用时被悄悄替换）**：**真实攻击者只有公开 DNS 与公网可信证书，
  没有任何设备篡改能力**。⇒ 早期"靠 `adb root` 注系统 CA + 改 hosts"的做法**已判为不成立并撤销**
  （用户质疑「真实场景中你怎么可能安装别的 CA 证书，还弄到系统目录去？」）；
  一切结论必须出自**攻击者可达的**配置。
- **已闭环的半环（代码层，2026-09-27）**：表驱动用例首跑即红两条，都是实测越界、随批修掉——
  ① `KeePasskeyCredentialProviderService.findMatchingEntries` 的 URL 兜底不区分条目类型
  ⇒ 「rpId 属 A 域、条目 URL 写了 B 域」的 passkey 会被列进 B 域候选（**凭据存在性跨域泄露**，
  签名侧另有 rpId 复核故断言交不出去，但用户一点就在签名处撞上拒绝）；收紧为
  「URL 兜底只服务口令/密码条目，passkey 在域维度只认 `passkeyRpId` 一个真相源」；
  ② `PublicSuffixList.normalizeHost` 去 DNS 根点而 `DomainMatcher.extractDomain` 不去
  ⇒ 同一主机名在两个归一器手里答案不同（尾点 origin 的合法凭据不出候选，公共后缀查找却判同域）；
  现由 `extractDomain` 统一剔除末点。新增 `CredentialProviderLookalikeMatchTest` 5 例
  （真域/子域正向 ＋ 后缀堆叠/异域/punycode 同形/前缀粘连负向 ＋ IP origin  尾点大小写），
  **每条负向都配同源正向对照**。⇒ 细节见 commit `0199bc69`。
- **一条设计更正（登记以免复发）**：`rp.testlab.xyz` 与 `rp.testlab.xyz.phish.testlab.xyz` 的 eTLD+1
  同为 `testlab.xyz` ⇒ 对 WebAuthn 是**同站**，原设计的仿冒矩阵在该形态下无效
  （代码层不受影响，因 `isDomainMatch` 是标签后缀判定，三条堆叠负向实测均正确拒绝）。
- **未闭环的半环（浏览器 → 系统 CM → 本应用 provider）**：**未做仿冒域矩阵**，且已判死三条候选路线——
  ⚠️ **但「能不能到达 provider」这一项已由真机读数排除**（2026-09-27 19:55，`mark.via` →
  系统 CM → 本应用 provider → 断言，RP 侧 AAGUID 等于本应用 `DEFAULT_AAGUID`；
  见 `ISSUE-P3-337` 的「19:55 真实 RP 的 PRF 端到端读数」）⇒ 剩余阻塞**只剩**域名与信任链。
  AVD 上 GMS 自带的 FIDO 栈会截走请求（provider 日志命中 0 次）、实验机上 Firefox 的 `get()` 永不返回、
  `adb shell cmd credential` 无 shell 实现 ⇒ 无入口直接下发 `GetCredentialRequest`。
  实验室载体已落地可复用：`tools/passkey-phish-lab/`（自建 CA + 多 SAN 证书、HTTPS RP 自跑页、
  只读 CBOR 子集、6 用例矩阵驱动；`rp_server` 默认只绑 127.0.0.1，局域网零暴露）。
  ⚠️ 该矩阵的判据本身被实测纠过一次：原版把「六例全无浏览器读数」判成 PASS——**环境假绿**，
  已改为「无浏览器读数 ⇒ 无效，不得计为通过」。
- **红线（不变，优先级高于任何进度）**：**在破不通真实信任锚之前，本条目下不得出现任何
  「仿冒域不会唤醒」的结论**。今天能声称的只有「本应用对给定 rpId 的接受/拒绝判定」，
  **不是**「Chrome 不唤醒仿冒站」（那是上游 origin 校验，未证）。
- **载体顺带承接**：`ISSUE-P3-337` 的 AC⑧(b)（对真实 RP 完成 GetAssertion）与未决 6 计数器跳变实测出口。
- **关联**：`ISSUE-P3-337` / `PD-32`、`PD-33`（DAL 与 caller origin 归因）/ commits
  `db332b78`、`8e2a7700`、`fa283ea2`、`0199bc69`、`dc741803`（完整核实与读数）。

### ISSUE-P3-381：回到前台（Resume）/网络恢复时不探测远端变化——多设备场景切回即旧数据

- **状态（2026-09-28 登记）**：开放。源自 keepass2android 对照（云同步参考）。
- **背景与整改依据**：全仓无生命周期同步触发（`rg "ON_RESUME|ON_START"` 于 app/sync/database
  生产代码零命中；`ProcessLifecycleOwner` 仅自动锁与剪贴板清理两处），无网络恢复回调
  （`registerNetworkCallback` 全仓零命中）。现有 syncNow 触发面五处：手动下拉
  （`VaultListSyncController.kt:61`）、解锁后自动（`VaultListViewModel.kt:193-200`，默认 true）、
  冷启动一次性（受开关）、设置页手动、周期 WorkManager（≥15 分钟，默认关）。
  「会话未锁切走再切回」「长会话中途远端更新」「网络恢复」三场景均无探测，须用户记住手动下拉。
- **参考对照**：keepass2android 回前台探测远端变化（`docs/references/keepass2android-架构分析.md:361`）。
- **核实时间点 + 核实方式**：2026-09-28，独立核实子代理逐触发面实测五处调用点；
  《产品裁决登记》全部 55 条 PD 无同步触发时机的裁决；最近批次 §351~§356 未涉及。
- **涉及文件**：`app/` 列表/设置控制器、`sync/` 同步协调、生命周期挂点。
- **AC**：①先裁决口径并留痕：回前台/网络恢复时是「轻量远端探测（ETag/metadata 比对）+ 提示」
  还是直接 syncNow（与电量/限流权衡）；②触发统一收口既有同步协调，不与解锁后自动同步形成双写竞争；
  ③设置面开关；④周期任务默认关闭的现状不变。

### ISSUE-P3-382：库内重复条目检测与合并工具缺失（仅导入时查重，无存量扫描/合并）

- **状态（2026-09-28 登记）**：开放。源自 Monica 对照。
- **背景与整改依据**：健康检查仅 WEAK/REUSED/EXPIRED（`HealthCheckEngine.kt:10-14`）+ 泄露计数；
  REUSED 只覆盖「同密码跨条目复用」，对同站同账号不同密码的重复条目无鉴别力且无合并动作。
  `rg -in "duplicate|dedup"` 生产命中仅三处：导入管道查重（`ImportPersistRun.kt` EntryKey）、
  条目克隆（`EntryDuplicateCoordinator.kt:16`，克隆语义非去重）、同步冲突分支
  （`ConflictResolutionUiState.kt:19`）——无任何库级存量扫描/一键合并入口。
- **参考对照**：Monica Domain_Bridge 层 `DedupMergeService`（`docs/references/Monica-架构分析.md:106`）。
- **核实时间点 + 核实方式**：2026-09-28，独立核实子代理全量检索；PD-51 仅限自动填充就地新建落库语境，
  不构成对库级去重工具的裁决。
- **涉及文件**：`app/` 健康检查/列表面、`database/` 条目查询。
- **AC**：①先出只读「重复报告」入口（判据候选：同域+同账号 / 同标题+同 URL，判据先裁决留痕）；
  ②合并动作须用户逐条确认，走既有编辑/删除管线，**不与**同步 `KdbxMerger` 混用；
  ③新增判据用例（含负例）。

### ISSUE-P3-383：Steam Guard TOTP 不支持（RFC 6238 之外的字符集/位数变体）

- **状态（2026-09-28 登记）**：开放。源自 Monica 对照。
- **背景与整改依据**：`OtpEngine.kt:9-12` KDoc 自述仅 RFC 6238/4226、SHA-1/256/512、6/8 位；
  `calculateHotp` 产出纯数字码（`:96-97`）；`TotpKeyUriParser.isBase32Alphabet` 严格 A-Z2-7
  （`:332-338`）——无 Steam 5 字符字母表变体扩展点。全仓 `rg -i steam` 于 *.kt 零命中。
  持有 Steam 2FA 的用户无法在本库取码（KeePassXC/Monica 均已支持）。
- **参考对照**：`docs/references/Monica-架构分析.md:253`（支持 Steam Guard 特殊字符映射）。
- **核实时间点 + 核实方式**：2026-09-28，独立核实子代理五模块源码检索 + 直读 OtpEngine/TotpKeyUriParser；
  两登记表与待办零命中。
- **涉及文件**：`database/`（或 `core/`）OTP 引擎与 URI 解析器。
- **AC**：①先裁决是否支持；若做：`steam://` URI 解析 + 5 字符字母表变体实现与标准 RFC 路径隔离，
  不污染既有 TOTP 行为；②与 KeePassXC 对拍向量（互操作证据纪律）；③负例：非 steam 前缀 URI
  解析行为不变（既有用例零回归）。

### ISSUE-P3-384：从另一 .kdbx 文件导入/合并（生态库或备份副本并入当前库）

- **状态（2026-09-28 登记）**：开放。源自 KeePassDX 对照。
- **背景与整改依据**：`ImportSource` 枚举仅 KEEPASS_XML/BITWARDEN_JSON/BROWSER_CSV/ONEPASSWORD_1PUX
  四源（`ImportContracts.kt:23-26`），importer/ 目录无 kdbx 源；三方合并引擎
  `KdbxMerger.mergeDatabases`（`sync/merge/KdbxMerger.kt:99`）生产调用点仅
  `SyncConflictController.kt:293` 一处（输入为同步远端内容而非用户所选文件）；子库挂载只读
  （`ChildDatabaseSessionManager.kt:47-49`）不可作并入路径。现状绕道：对方导出 XML/CSV 走导入
  （**明文落盘**）或逐条手搬。
- **参考对照**：KeePassDX `Database.mergeData` + DatabaseKDBXMerger 三方合并
  （`docs/references/KeePassDX-架构分析.md:311`）、Intent 驱动的库操作出口（`:347`）——
  对任意所选库文件的合并不限于同步。
- **核实时间点 + 核实方式**：2026-09-28，独立核实子代理；《产品裁决登记》无「不做 kdbx 并入」的裁决
  （PD-08/48/49 属通行密钥扫码 CXF 面）；子库只读仅是首版范围决定（批次 05），未登记为取舍。
- **涉及文件**：`app/` 导入入口面、`database/` 会话、`sync/merge/KdbxMerger`。
- **AC**：①入库路径全程密文（打开第二库会话→内存合并→写盘），**严禁**解密为中间文件落盘；
  ②合并语义复用 `KdbxMerger`（UUID 三方判定），重复组/条目与回收端口径明确；
  ③与进行中同步会话的互斥/串行口径；④合并结果以 pykeepass 对拍验证（互操作证据纪律）。

### ISSUE-P3-385：库级 Meta 编辑面缺失（库名/库描述/默认用户名不可编辑、不预填）

- **状态（2026-09-28 登记）**：开放。源自 KeePassDX 对照。
- **背景与整改依据**：`updateDatabaseMeta` 写入口（`DatabaseSession.kt:271`）app 侧仅回收站开关/
  自定义图标/通行密钥/冲突自动合并四类调用，零一处改库名/描述/默认用户名；`databaseName` 命中全为
  展示透传（`SettingsUiStateProjection.kt:272`、`VaultListProjection.kt:112`），`databaseDescription`
  在 app 模块零消费（`KdbxMetaData.kt:14` 已解析但无 UI）；设置页 `DatabaseSettingsComponents.kt:213-215`
  只读；`defaultUserName` 仅设置页回显链（`SettingsPreferencesController.kt:145`），
  `EntryEditViewModel` 零命中——新建条目不预填默认用户名。KDBX Meta 只能被其他客户端修改后同步过来。
- **参考对照**：KeePassDX `NestedDatabaseSettingsFragment.kt:301-323` 三项可编辑、
  `:573-580` 写回 `database.defaultUsername`。
- **核实时间点 + 核实方式**：2026-09-28，独立核实子代理逐一核对全部调用点；PD-35 仅裁合并时
  库级 Meta「以本地为准」，未裁决编辑面不做。
- **涉及文件**：`database/` Meta 写入口、`app/` 设置页与条目编辑 ViewModel。
- **AC**：①库名/库描述/默认用户名三项可编辑并经既有 `updateDatabaseMeta` 入库；
  ②新建条目消费默认用户名预填；③与 PD-35 合并口径不冲突（编辑后合并语义留痕）；④中英文案。

### ISSUE-P3-386：「库已解锁」常驻通知缺「立即锁定」快捷动作

- **状态（2026-09-28 登记）**：开放。源自 KeePassDX 对照。
- **背景与整改依据**：`UnlockedNotificationController.kt:121-147 post()` 仅标题/正文/内容 Intent/
  Chronometer 倒计时，无 `addAction` 亦无 `setDeleteIntent`；`NotificationChannels.kt:133-163`
  仅三个「打开应用」PendingIntent；无 TileService 等效缓解。用户离开设备必须解锁屏幕进应用才能锁库。
- **参考对照**：KeePassDX `DatabaseTaskNotificationService.kt:560-596`
  （`setDeleteIntent(LOCK_ACTION)` + `addAction` 锁定按钮）。
- **核实时间点 + 核实方式**：2026-09-28，独立核实子代理实读本仓通知构建区与 KeePassDX 对应实现；
  §229 加过倒计时未加动作；两登记表无相关取舍。
- **涉及文件**：`app/.../notification/`（UnlockedNotificationController / NotificationChannels）。
- **AC**：①通知加「立即锁定」action，走既有锁定管线与自动锁**同收口**（不另起第二条锁定路径）；
  ②触发后通知撤销；③中英文案零插值。

### ISSUE-P3-387：远端目录浏览选库缺失（云同步配置只能手输远端路径）

- **状态（2026-09-28 登记）**：开放。源自 keepass2android 对照。
- **背景与整改依据**：`SyncProvider.kt:11-74` 接口仅 testConnection/getMetadata/download/upload/
  uploadAtomic/delete 六成员，无列目录能力；`WebDavSyncProvider` 两处 PROPFIND 均 Depth:0
  （`:127-129`、`:148-151`），仅用于连接测试与 ETag/存在性探测；S3 无 ListObjects；
  UI `remotePath`/`objectKey` 均为手输（`CloudSyncConfigFields.kt:82-93`），远端路径记不清时
  无浏览/发现手段，只能报错试错。
- **参考对照**：keepass2android `ListContents`/`GetFileDescription` 与 FileSelectActivity 编排
  （`docs/references/keepass2android-架构分析.md:255`、`§5.6:365-368`）；该文档 `:548`
  已将「目录浏览拆 BrowsableFileStorage」列为待借鉴建议。
- **核实时间点 + 核实方式**：2026-09-28，独立核实子代理逐接口成员与 PROPFIND 请求体核对；
  两登记表无相关取舍。
- **涉及文件**：`sync/` provider 契约与实现、`app/` 同步配置面。
- **AC**：①先评估实施范围（接口加浏览成员：仅 WebDAV 先行 vs 全协议）并留痕后再动手；
  ②SSRF 口径不变（PD-02 端点默认拒绝，浏览目标同受约束）；③分页/大目录边界；④中英文案。

### ISSUE-P3-392：原生 KDF 探活失败静默回落 JCE/BC——性能骤降全程不可观测

- **状态（2026-09-29 登记）**：开放（先做「日志 vs 登记限界」二选一裁决）。源自 KeePass
  `MonoWorkarounds.cs:169` 注释标注。
- **背景与整改依据**：`Argon2KdfEngine.kt:68-82` 与 `AesKdfEngine.kt:30-34`（快照行号）在原生
  探活失败（so 加载失败 / KAT 不匹配的个别机型）时**静默**走 BC/JCE 兜底；本仓自测 BC 慢
  2.2~5.4 倍（`Argon2KdfEngine.kt:14`）——高 KDF 参数机型上解锁耗时成倍增长，但 crypto 模块
  全程零日志、生产代码与 `HealthCheckEngine` 均无「探活失败已回落」的可观测痕迹、《已知工程限界》
  亦未登记该静默降级，用户与排障方均不可见。KeePass `MonoWorkarounds.cs:169` 同型
  （DllNotFound 静默回退托管实现且可被关闭）。
- **核实时间点 + 核实方式**：2026-09-29 参考项目对照工作流：独立复核子代理实读两引擎回落分支、
  grep 核实 crypto 零日志、核对限界表未登记。证据见
  [`references/参考项目踩坑对照/01-KeePass-2.61.1.md`](references/参考项目踩坑对照/01-KeePass-2.61.1.md)。
- **涉及文件**：`crypto/src/main/java/com/keepasskey/crypto/kdf/Argon2KdfEngine.kt`、
  `AesKdfEngine.kt`；或 `docs/architecture/已知工程限界.md`。
- **AC**：二选一并留痕：①回落时经 core `AppLog` 记一次性「原生探活失败已回落」事实
  （不含 KDF 参数等敏感信息），或将探活状态纳入 `HealthCheckEngine`；②接受静默降级则
  登记《已知工程限界》（【客观限界】或【有意取舍】按实情定标）。

### ISSUE-P3-393：填充链（Autofill + CM）不检查条目过期——过期凭据照常供给

- **状态（2026-09-29 登记）**：开放。源自 keepassxc 提交 `c46f3d37`
  （「Browser: Check for expired entry prior to custom data」）。
- **背景与整改依据**：`AutofillCandidateRanker`（快照核实）入选与排序均无过期维度；
  `rg -rni "expired|过期"` 于 autofill / passkey 两包零命中——过期仅由
  `HealthCheckEngine` 与条目详情 UI 消费。本仓比 KeePassXC 修复前更彻底：填充链
  （Autofill + Credential Manager 两通道）完全未检查过期，过期凭据照常供给。
- **核实时间点 + 核实方式**：2026-09-29 参考项目对照工作流：独立复核子代理复跑零命中检索并
  实读 ranker 确认。证据见
  [`references/参考项目Git历史对照/04-keepassxc.md`](references/参考项目Git历史对照/04-keepassxc.md)。
- **涉及文件**：`app/` autofill 候选面与 CM 凭据装配面。
- **AC**：①先裁决口径（过期条目直接排除 vs 降权 + 确认）并留痕；②两通道一致实施；
  ③用例覆盖已过期 / 未过期两分支（含过期当日的边界）。
