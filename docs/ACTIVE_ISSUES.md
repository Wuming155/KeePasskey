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

## P2 中危缺陷与协议/测试缺口（0 项）

> **暂无开放项**。2026-09-28 深度交互审查登记的 5 条（`ISSUE-P2-353` ~ `ISSUE-P2-357`）当日整批整改闭环，
> 归档见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md) §349 与批次正文
> [`349-交互体验深度审查整改批次.md`](resolved/batches/349-交互体验深度审查整改批次.md)；
> 2026-09-27 两条真机手测缺陷（`ISSUE-P2-341` / `ISSUE-P2-343`）分别收口于 §343 与 §344；
> 更早的 P2 闭环流水见 `RESOLVED_LOG.md` §315 ~ §325。

## P3 低危问题、特性接线与体验优化（6 项）

> **开放项 6 条**（这里只列**各条还欠什么**；已完成的实施细节留在条目正文与 commit 里，不重复登记）：
> ① `ISSUE-P3-339` —— **用户指示暂时搁置**（浏览器半环需外部域名与信任链资源）。
> ②~⑥ `ISSUE-P3-366` ~ `ISSUE-P3-370` —— 2026-09-28 参考项目对照评估补登的五条 P3 小项
> （自动锁后台时间戳持久化 / 写侧恒写 4.1 / 解析进度回调 / 通用占位符引擎 / getCheckKey 同型通道）；
> 同次评估发现的三项能力差距已按用户裁决归位产品裁决表 `PD-53` ~ `PD-55`，属取舍不属缺陷。
> 原 ②~⑥ `ISSUE-P3-361` ~ `ISSUE-P3-365`（假开关清点五条）已于 §350 整批闭环归档。
> 2026-09-28 深度交互审查新增的 `ISSUE-P3-358` ~ `ISSUE-P3-360` 三条已同批闭环于
> [`RESOLVED_LOG.md`](RESOLVED_LOG.md) §349。
> `ISSUE-P3-345`（无匹配「就地新建并用于本次填充」）收口于 `RESOLVED_LOG.md` §347：主体整改落
> commit `37fdf145`，Autofill 通道真机日志坐实（§347.1），CM 浏览器面按用户裁决（`PD-52`：目标生态
> **非 Chrome / 非 GMS**）收口——呈现面登记入限界表 §36。
> `ISSUE-P3-340`（`@Preview` 状态覆盖普查 / 规则条文）按用户裁决收口于 `RESOLVED_LOG.md` §346：
> 普查**未**升级为 `hygiene-gate` 第九条，维持「只出读数、不进 CI」（闸门化前置——带理由的显式豁免清单
> ——指针留在该批正文 §346.2）；2026-09-28 UI 现代化批次（`ISSUE-P3-346` ~ `ISSUE-P3-351` 六条，含两条
> 登记即复核否决的 350 / 351 中 350 归档注明）收口于 `RESOLVED_LOG.md` §345；
> 更早的 P3 闭环流水见 `RESOLVED_LOG.md` §326 ~ §344（`ISSUE-P3-337` / `ISSUE-P3-342` 收口于 §343，
> `ISSUE-P2-343` 收口于 §344）。

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

### ISSUE-P3-366：自动锁后台时间戳不持久化（进程重建后超时判定丢失）；且无长任务挂锁机制

- **核实时间点与方式（2026-09-28）**：直读 `app/src/main/java/com/keepasskey/app/security/AutoLockManager.kt:60,118`——
  `backgroundTimestamp` 为内存变量，无 DataStore / Preferences 持久化键（全仓检索零命中）；
  「长任务挂锁」经检索 `temporarilyDisable` 类符号零命中，现仅 `UnsavedEditRegistry` 脏表单拦截部分覆盖
  （§349 闭环面）。
- **背景**：KeePassDX 两个细节（`docs/references/KeePassDX-架构分析.md` §8.2-7）：① 超时时间戳持久化到
  Preferences，进程被杀后 Alarm 恢复仍能判定超时；② 长任务期间 `temporarilyDisableTimeout` 挂起锁。
  本仓会话驻进程内存、进程死亡即会话消失、冷启动必回锁屏，故**实害面有限，属健壮性差距而非安全洞**
  （如实声明，避免拔高）；非表单类长任务（如同步合并）中途弹锁面由 `AutoLockSessionGuard` 会话熔断兜底。
- **验收标准**：AC① 后台化时间戳持久化（DataStore / Preferences 任一，单源），会话恢复路径据此判定
  是否已超自动锁时限；AC② 评估「长任务挂锁」并实施，至少覆盖保存（KDF 派生 + 整库重序列化）与
  同步合并两类长任务；AC③ 单测覆盖持久化恢复判定与挂锁 / 恢复对称性；AC④ 与 `ISSUE-P3-362`
  （超时回显漂移）互不越界——本条不动 `securityTimeoutStateFlow` 显示链。

### ISSUE-P3-367：KDBX 写侧恒写 4.1 版本号，而非按特性动态计算最小版本

- **核实时间点与方式（2026-09-28）**：直读 `database/src/main/java/com/keepasskey/database/file/KdbxHeader.kt:48-54`——
  新建库 XML 恒含 4.1 元素、头部恒写 `VERSION_4_1`（`core/model/KdbxConstants.kt`，`ISSUE-P2-266`），
  读取仅校验 major。
- **背景**：KeePassDX 按「是否用到 4.1 专有特性」动态计算最小版本（`docs/references/KeePassDX-架构分析.md`
  §8.2-8）。恒写 4.1 把互操作下限抬到「必须支持 4.1 的客户端」；现实影响小（主流客户端均已支持 4.1），故 P3。
- **验收标准**：AC① 二选一——(a) 实施动态最小版本（按实际写出的 4.1 专有特性回退 4.0），
  或 (b) 复核确认全库恒用 4.1 特性面 / 代价不值，登记入限界表并归档本条；AC② 若 (a)，
  版本判定单测 + 官方客户端互操作对拍不回归（`AGENTS.md` 规则 8）。

### ISSUE-P3-368：流式解析已就位，但读写全程无进度回调

- **核实时间点与方式（2026-09-28）**：database / crypto / sync 三模块 `progress`（-i）检索零命中；
  直读 `database/src/main/java/com/keepasskey/database/xml/KdbxXmlParser.kt` 确认为流式 SAX
  （官方 `ReadXmlStreamed` 同族，非 DOM）。
- **背景**：大库打开 / 保存 / KDF 派生期间无可感知进度；§349 仅给部分屏加了 loading 骨架（不确定态）。
  KeePassDX `ProgressTaskUpdater` 贯穿读写（`docs/references/KeePassDX-架构分析.md` §8.2-10）。
- **验收标准**：AC① 读写链路挂 0..1 进度回调（Flow 形态），至少覆盖打开（解密→解压→XML）与保存两段；
  AC② 大库场景 UI 呈现进度（确定或分段不确定均可）；AC③ 不回退流式解析与内存擦除纪律
  （敏感缓冲不因进度层新增驻留点）。

### ISSUE-P3-369：通用占位符引擎缺失（仅 `{REF:}` 字段引用引擎）

- **核实时间点与方式（2026-09-28）**：检索 `resolvePlaceholder|expandPlaceholder|\{TITLE|\{URL|\{USERNAME`——
  仅命中 AutoType 序列字面量（`app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditComponents.kt:415`）
  与 UI 输入框 placeholder 文案；`{REF:}` 引擎在
  `database/src/main/java/com/keepasskey/database/fieldref/FieldReferenceEngine.kt`（消费点展开）。
- **背景**：kp2a 有 SPR 占位符引擎（`docs/references/keepass2android-架构分析.md` §6.6）。
  **红线**：`PD-42`——AutoType 只保存不执行，占位符只服务**复制 / 填充消费点**，不得成为 AutoType 执行面；
  展开纪律与 `{REF}` 一致：仅在取值消费点解析，投影层不物化。
- **验收标准**：AC① 先评估真实受益面（URL 模板 / 备注模板类条目在复制与填充时的展开需求）；
  AC② 有则实施常用占位符子集 + 转义规则，消费点纪律同 `{REF}`；无则复核否决归档并注明。

### ISSUE-P3-370：「不解库校验已记住凭据」通道（getCheckKey 同型）缺失

- **核实时间点与方式（2026-09-28）**：检索 `getCheckKey|checkKey|prefixHash|knownKey` 全仓零命中；
  最近似机制为生物识别封印凭据（`app/src/main/java/com/keepasskey/app/security/BiometricCredentialStorage.kt`）
  与 KDBX4 头部 HMAC 快速凭据校验（解锁链路直读确认已存在）。
- **背景**：KeePassDX `MasterCredential.getCheckKey` 以密码前缀哈希在**不解锁全库**前提下校验
  「已记住凭据」（`docs/references/KeePassDX-架构分析.md` §8.2-1）。本仓解锁路径已有头部 HMAC 快速判错、
  「记住凭据」主场景已由生物封印覆盖，实益面可能很小——如实登记，防后续对照评估再误报为缺口。
- **验收标准**：AC① 列出真实受益场景；AC② 有则实施（派生 `getCheckKey` 形态：凭据全程 `CharArray`
  即用即清、不解锁全库、不落地 `String`）；无则复核否决归档并注明。


