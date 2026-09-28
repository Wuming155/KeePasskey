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

## P3 低危问题、特性接线与体验优化（5 项）

> **开放项 5 条**（这里只列**各条还欠什么**；历史闭环流水一律见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)）：
> ① `ISSUE-P3-339` —— **用户指示暂时搁置**（浏览器半环需外部域名与信任链资源）。
> ② `ISSUE-P3-373` ~ `ISSUE-P3-376` —— 2026-09-28 用户指示「将本项目可以吸收的 Monica 自动填充
> 配置全部吸收过来」（功能差距 + 偏好开关两者都要、含结构化数据与主动提示两个大项），按差距调研补登。
> `ISSUE-P3-371`（`{REF:}` 三处消费点）与 `ISSUE-P3-372`（解析层与打分吸收）已于 §352 整批闭环归档；
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

### ISSUE-P3-373：Monica 轻中型特性吸收（Wi-Fi 填充启发 + 选择器/新建流程密码生成直达）

- **核实时间点与核实方式（2026-09-28）**：Monica 侧定向取证——`WifiAutofillAssist.kt:16-72`（不抓 SSID，
  设置页包名命中即置顶全部 WIFI 条目；调用于 `MonicaAutofillServiceNg.kt:607`）、
  `AutofillPickerActivityV2.kt:901`（选择器内生成密码填充）。本仓侧直读——全仓无 Wi-Fi 启发逻辑
  （`autofill/` 目录 grep 零命中）；生成器存在于 `ui/screens/generator/` 与编辑页
  `EntryEditPasswordGenerator.kt`，但 `passkey/PasswordDraftScreen.kt`（就地新建页）无生成入口、
  选择器亦无生成入口。
- **背景**：2026-09-28 Monica 对比调研列为「轻量可吸收」项；用户明示全部吸收。本仓无 WIFI 条目类型，
  以**标签/标题信号启发**等价落地；不引入 SSID 采集（Monica 同样刻意不做，权限取舍一致）。
- **涉及文件**：`app/.../autofill/`（新增 Wi-Fi 启发策略 + 排序挂点）、`passkey/PasswordDraftScreen.kt`、
  `ui/screens/generator/`（复用）、`autofill/AutofillPickerScreen.kt`（如选型含选择器入口）。
- **验收标准**：AC① 新增 `WifiFillBoostPolicy`（纯函数）：目标包名命中 Wi-Fi 设置类应用清单
  （清单收敛命名常量，含 AOSP/主流 ROM 设置包名）时，对标题/URL/标签含 wifi 信号（`wifi`/`wi-fi`/
  `无线`/`ssid`，大小写不敏感、词边界）的候选给排序加成；不在清单内恒零加成（反例覆盖）；
  AC② 加成只改排序不改准入（无信号条目照常可见）；AC③ `PasswordDraftScreen` 增「生成密码」入口，
  复用既有生成器实现（不自写第二套生成逻辑），生成值经既有敏感通道写入、不落日志；
  AC④ 新增开关/清单若有可见 UI 须补 `@Preview` 态（`ISSUE-P3-340` 立规）；AC⑤ `test` 全绿 +
  `gate_readings.py` 全 PASS。

### ISSUE-P3-374：主动填充提示通知吸收（Monica ActiveFillNotification）

- **核实时间点与核实方式（2026-09-28）**：Monica 侧定向取证——`ActiveFillNotificationHelper.kt:65-75`
  （通知点击进选择器，不直接填充）、`ActiveFillPromptThrottle.kt`（节流）、挂点在
  `MonicaAccessibilityService.kt:334-363`（无障碍检测到登录框后发主动提示）。本仓侧直读——全仓无
  主动填充提示（通知面仅有解锁倒计时 `UnlockedNotificationController` 与同步失败通知先例）；
  本仓无障碍通道 `autofill/legacy/` 为 v1 裁剪、默认关闭（§332）。
- **背景**：2026-09-28 Monica 对比调研列为差距项；用户明示含大项全部吸收。**安全口径（预先约束，
  实现不得背离）**：通知内容**零敏感插值**（不得出现条目标题/用户名/域名之外的任何凭据字段，且域名
  是否入文案须在批次文档显式裁决）；点击动作只进**受保护窗口选择器**，绝不直达填充；节流必须存在
  （同包名冷却时间命名常量）；通知通道独立、系统设置可关；库锁定时不发（无可交付内容）。
- **涉及文件**：新增 `autofill/` 主动提示组件 + 通知通道常量；挂点候选＝legacy 无障碍通道
  （`LegacyAutofillAccessibilityService`，仅其启用时）与 `KeePasskeyAutofillService.onFillRequest`
  （有候选但系统 UI 未展示的场景定义须在实现时据平台行为写明）；复用既有通知先例范式。
- **验收标准**：AC① 触发面与挂点在批次文档写明（平台依据 + 为何不越权）；AC② 节流纯函数 +
  冷却反例用例；AC③ 通知渠道注册、重要度低调、内容零敏感（源码守卫断言文案资源不含条目字段插值）；
  AC④ 点击只进选择器/受保护窗口（接线守卫）；AC⑤ 库锁定 / 通道开关关闭 / 自身包名三态不发；
  AC⑥ `test` 全绿 + `gate_readings.py` 全 PASS；真机呈现面若无法实跑，在批次文档如实声明。

### ISSUE-P3-375：结构化数据填充吸收（信用卡 / 证件 / 账单地址）

- **核实时间点与核实方式（2026-09-28）**：Monica 侧定向取证——`AutofillStructuredDataSupport.kt:12-46`
  （卡/证件/地址 hint 画像与低置信跳过）、`EnhancedAutofillStructureParserV2.kt:40-73`（FieldHint 含
  `creditCard*`/地址类）、`AutofillPickerActivityV2.kt:1157,1229,1310`（选择器填卡/证件/账单地址）。
  本仓侧直读——`AutofillFieldScanner` 只识别用户名/密码/OTP；P3-45 评估结论为「暂不实现」
  （`docs/resolved/batches/09-自动填充能力对标批次归档.md:14`，**非**产品裁决登记项，可重开）。
- **背景**：2026-09-28 用户明示把该大项纳入吸收范围（AskUserQuestion「全部纳入」当场裁决，取代
  P3-45 的暂缓结论）。**存储口径**：不新增条目类型——结构化数据落在 KDBX 条目**自定义字符串字段**，
  字段名采用 Android autofill hint 惯例（`creditCardNumber` / `creditCardSecurityCode` /
  `creditCardExpirationMonth|Year` / `billingAddress*` 等，常量收敛单点），与 KeePassDX 生态的
  「字符串字段承载结构化数据」形态一致；**不碰** passkey schema（`KPEX_PASSKEY_*`）。
- **涉及文件**：`AutofillFieldScanner.kt` / `AutofillTargetFieldResolver.kt`（识别）、
  `AutofillEntrySearch.kt` + `AutofillCandidateRanker.kt`（候选供给）、`AutofillDatasetBuilders.kt`
  （多字段 Dataset）、选择器 UI（结构化数据分组展示与点选）、编辑页（可选录入入口，若工作量超界
  允许只做「填充+已有字段识别」并在批次文档如实声明录入面范围）。
- **验收标准**：AC① 解析层识别卡/地址类目标字段（autofill hint / html autocomplete / label 术语
  三源，置信不足不填——Monica 低置信跳过同款）；AC② 候选供给＝含对应自定义字段的条目，按域/包名
  既有排序合并；AC③ Dataset 把条目字段值填入匹配的 `AutofillId`（逐字段匹配，缺字段的卡不入选）；
  AC④ 选择器呈现结构化数据条目（与口令条目分组或明确标注）；AC⑤ 敏感字段值全程按既有纪律
  （`AutofillValue` 出口即边界，日志零明文）；AC⑥ 识别/匹配/填充三段各有正反例宿主用例；
  AC⑦ `test` 全绿 + `gate_readings.py` 全 PASS；AC⑧ 录入面（编辑页表单）若未做，批次文档写明
  「已有字段识别可填、录入走自定义字段手工/导入」的边界。

### ISSUE-P3-376：Monica AutofillPreferences 偏好开关盘点与缺项吸收

- **核实时间点与核实方式（2026-09-28）**：Monica 侧定向取证——`autofill_ng/AutofillPreferences.kt`
  （约 30+ 偏好项，`:118-477` 等）；本仓侧既有开关盘点——`ExtendedSettings` 读写键（`autofillServiceEnabled` /
  `offerSaveCredentials` / `sessionGrant` / `inlineSuggestions` / `totpCopy` / `overrideNoAutofill` /
  `autofillLegacyAccessibilityEnabled` 等，§232 / §51 / §332 批次）+ 三级黑名单持久化。
- **背景**：2026-09-28 用户裁「两者都要」——功能差距之外，偏好开关缺项一并吸收。**历史教训先约束**：
  假开关（`ISSUE-P2-228` / `ISSUE-P3-65` / `ISSUE-P3-361~365`）明令禁止——**凡新增开关必须
  读写键成对 + 服务侧真实消费点 + 接线计数守卫**，做不出消费点的偏好一律登记「裁决不做」而非摆设。
- **涉及文件**：`ExtendedSettings` / `SettingsUiState` / 设置页自动填充子页 / `KeePasskeyAutofillService`。
- **验收标准**：AC① 盘点表落批次文档：Monica 每个偏好 → 本仓「已有等价 / 本批新增 / 裁决不做（写明
  理由）」三分类，逐项无遗漏（以 `AutofillPreferences.kt` 成员清单为分母）；AC② 本仓缺且判「可吸收」
  的开关逐个真实接线（读写键 + 消费点 + 中英文案）；AC③ 每新增开关配接线守卫测试（计数断言防
  只接一处，仿 `AutofillChannelSwitchWiringTest` 口径）；AC④ `test` 全绿 + `gate_readings.py` 全 PASS。


