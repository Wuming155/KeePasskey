# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。
> **闭环纪律**：任务完成后，将该条目从本文件**整条移入** [**docs/RESOLVED_LOG.md**](RESOLVED_LOG.md)，并执行 `git commit & push`；本文件**不保留**已闭环条目的正文或索引段，历史实现与验收证据一律以归档库为单一真相源。

---

## 条目维护规则（ISSUE-P3-28 确立）

1. **新增条目必须附「核实时间点」与「核实方式」**：任何声称「某文件需要删除 / 某处没有消费方 / 某硬编码为 0」
   一类**关于代码库现状的前提**，都必须写明**何时、以何种手段**核实过，例如
   「2026-09-10 经 `Test-Path` 核实」「2026-09-10 经全仓 `grep maskPasswordsDefault` 核实（仅设置页回显）」。
   > 立规缘由：曾发现 ISSUE-P3-08 与 ISSUE-P3-16 的正文前提在开工时**已不成立**——两者都声称
   > `docs/plans/`、`STATUS.md` 等文件「需要删除」，但这些文件早已先行删除，`AGENTS.md` 也已不含相关引用。
   > 条目与代码库演进之间存在时间差，会导致执行者去做已经完成的工作。
2. **开工前复核前提**：认领条目时先复核其正文前提（路径是否存在、行号是否漂移、消费方是否已出现）。
   前提已不成立的，**就地修正或显式标注**后再动手；完全无对象可改的条目应直接归档并注明原因。
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

## 第四轮独立复核定版结论：对本表 AC 的更正（实施前必读）

> **依据**：[`docs/security/SECURITY_RECHECK_2026-09.md`](security/SECURITY_RECHECK_2026-09.md)
> （**2026-09-15 由 `523d0fd^` 恢复入库**，见 `RESOLVED_LOG.md` §69）的 §10 定级表与
> §11「不可照做的 AC」清单，以及各行处的逐条裁定。
> **效力**：凡本表与下方条目正文的 AC 冲突，**以本表为准**——下文逐项给出**更正后的实施口径**，
> 照原文 AC 实施者可能**降低安全性**或**引入数据损坏**。
> **登记缘由**：本表多条目的 AC 早于该报告定版、且此前虽有部分行内批注，仍有 16 条未同步；
> 该报告入库后按「依据恢复 ⇒ 逐条对齐」补登（`ISSUE-P3-127` AC② 的落地形式）。

| 条目 | 第四轮定级 | 更正后的实施口径（与正文 AC 冲突时以本行为准） |
|---|---|---|
| `P2-42` | P2 / **UNVERIFIED** | AC③ 有**前置**：新补的 instrumented 用例**不会在 CI 跑**（= `P3-123③`），只能本地跑；3 项均需设备侧实测 |
| `P2-44` | P2 / LOW | 设备侧判据：TalkBack + `uiautomator dump` 检查 **`isPassword`** 语义（宿主 JVM 不构成证据） |
| `P2-45` | P2 / LOW | **AC① 撤销**——默认关闭系 2026-09-12 用户裁决（有留痕），非缺陷；**AC② 原「哨兵」方案原理上不可闭环**（哨兵可被同路径删除），须改用 Keystore 绑定的存在性证明，或登记为已接受边界 |
| `P2-46` | P2 / **MEDIUM** | 原 AC 的「未安装 → **弱候选**」**仍会把凭据交给侧载应用** ⇒ 必须选「**不命中**」（不得降级为弱候选） |
| `P2-47` | P2 / MEDIUM | 原 AC① 依赖的「S3 Versioning / ETag 单调性」**作为客户端可信信道不成立** ⇒ 须改**本地单调记录**；另 `SyncRollbackGuard.State.sequence` **已持久化但不参与裁决**，勿据「sequence 已持久化」推断防回滚强度 |
| `P2-49` | **P1** / MEDIUM | 无 `I×M` 联合预算（AC①**已完成**）；**AC② 不引入协程 `withTimeout`**——阻塞式原生派生不可被打断，属无效纸面加固；墙钟量级须 `P2-80` 真机实测，**不得以推算替代** |
| `P2-73` | P3 / **INFO** | **AC② 是负向变更，不可照做**：改 `FLAG_MUTABLE` 是「为对齐文档而降低安全性」——本仓这两条路径**不消费**平台 fillIn extras；AC①/③ 仍有效，且本项与 `IPC-01`（`P3-122`）**互斥**，**须同批实测** |
| `P2-79` | P2 / LOW | AC① **仓库已满足**（`KdfBenchmark` 已存在）；**AC② 含削弱陷阱**：直接接入建议值会出现 `8 MiB < 64 MiB` 的**降强**，须取 `max(建议值, 现默认)` |
| `P3-79` | — / **NOT REPRODUCIBLE** | 7 处 Popup 菜单项**全部为静态文案 / provider 名称**，无口令 / TOTP 明文 ⇒ 按 AC③ 留痕「**无需接线**」即可闭环，**不得**「全量加 flag」 |
| `P3-82` | P3 / HARDENING | 用例须**分别**断言 D-1 / D-2 / D-3；「合法口令 KDBX + 注入 DTD」需重新加密 ⇒ 推荐**直接喂 XML 给 `KdbxXmlParser`** 绕过外层 |
| `P3-85` | P3 / HARDENING | ② 的 CI 白名单**过窄会立即误红**（须逐条登记豁免，勿一刀切） |
| `P3-97` | P3 / LOW | 前提修正：`rust-supply-chain` **确实**跑 `cargo test --locked`；真实缺口是 **Gradle 侧 `:crypto:test` 从不运行** + `.so` 导出符号核对未实现 |
| `P3-108` | P3 / **部分误报** → HARDENING | 「附件明文经 `AtomicFileWriter`」**无据**（该部分为误报）；`.tmp` 残留口径**仍须判定**后再改断言，不得静默放宽 |
| `P3-111` | P3 / LOW | 与 `IPC-02` 为**同一整改项**，**顺序不可颠倒**：先保证 `retrieve*` 非 null，否则交叉核对**恒失败**、把「能填充」变成「不能填充」 |
| `P3-120` | **P1** / UNVERIFIED | 本项是 `P2-53` / `P2-63` / `P2-76` / `P1-23` 的**共同前提**，排期须先行 |
| `P3-121` | P3 / INFO | 原表述「与 `README` 矛盾」**过强**（须先定产品口径再谈文档一致性） |
| `P3-123` | P3 / **MEDIUM**（升格） | ①②③ 成立；④ `mapping.txt`（77.5 MB）由 CI **无条件上传**（公开仓库等同公开去混淆映射） |
| `P3-124` | P3 / INFO | 作为漏洞 = **误报**（维持 HARDENING）：scheme 硬编码 `https://`、路径固定、结果不回流三值枚举，可利用性为零 |

---

## P1 高危与核心功能问题（0 项）

> **历史**：**ISSUE-P1-16 ~ P1-21**（附件 Ref/Compressed 解析、块 HMAC 异常分型、外层头部总量闸门、
> 附件缓存冷启动清理、防回滚状态目录、敏感对话框 FLAG_SECURE）已于 2026-09-12 整改归档，见 §38。
>
> **2026-09-13 新增（红队攻击路径批次）**：下列条目由红队批次对拍后**留存为开放项**转登。
> 该批次文档已于同日处置归档并**删除**，来源证据、已撤回项与纪律见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) §39。
>
> **2026-09-13 P1 双项整改批次闭环**：`ISSUE-P1-24`（确认页归属展示 + 首次绑定授权 + 口令
> 无 UI 下发禁止）与 `ISSUE-P1-25`（`copyUsername` 口令面引用敏感通道 + 擦除调度保全）同批
> 整改归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §45。
>
> **2026-09-13 P1 双项整改批次闭环（第二批）**：`ISSUE-P1-22`（软件级 Keystore 快速解锁
> 显式降级确认 + 常驻声明；附带发现并整改 `SecretKeyFactory` 探针误用致快速解锁必然失败的
> 隐藏缺陷）与 `ISSUE-P1-23`（官方签名指纹对外公布 + `installer==null` 决策留痕）同批
> 整改归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §46。**P1 节暂无开放项。**

---

## P2 中危缺陷与协议/测试缺口（3 项）

> **历史**：**ISSUE-P2-28 ~ P2-41**（往返丢字段与布尔/数值语义、MemoryProtection 读写语义、
> KDF 缺参 fail-closed、isPackageMatch 的 android:// 硬约束、requireRiskNotice 接线、
> XML 元素计数上限、明文副本清零等 14 项）已于 2026-09-12 整改归档，见 §38。
>
> **2026-09-13 新增（红队攻击路径批次）**：P2-43 ~ P2-47 五项由红队批次对拍后留存为开放项转登，
> 来源与已撤回项见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §39。
>
> **2026-09-14 闭环**：**ISSUE-P2-48**（附件引用累计预算）、**ISSUE-P2-51**（剪贴板清理接线 + 空读误清修复）、
> **ISSUE-P2-53 + P2-63**（CM 通道完整性门控对称化 + 生物快速解锁实时 hook 信号，同批）、
> **ISSUE-P2-61**（TOTP 种子写入恒非保护）、**ISSUE-P2-65**（明文持有者注册锁观察者）、
> **ISSUE-P2-72**（`androidPackageName` 归属修正 + 禁用 `android://android` 回退）与 **ISSUE-P2-76**
> （CM 通道验证升级为密码学绑定）随存量安全整改批次归档，见 §48；**ISSUE-P2-49** 的 AC①③ 同批完成、
> **AC② 未闭环仍保留**（见该行 2026-09-14 进展批注）。
>
> **2026-09-15 闭环**：**ISSUE-P2-62**（密钥文件纯字节解析）、**ISSUE-P2-50**（DAL 响应有界流式读取 +
> 字节数裁决）、**ISSUE-P2-52**（选择器会话锁定对齐 + 空读 fail-safe）、**ISSUE-P2-78**
> （CM 保存 URL 形态分流）、**ISSUE-P2-60**（KDF secret 清零生命周期契约）、**ISSUE-P2-56**
> （Argon2 `m_cost` 工作内存自持清零，附 crate 宣称不成立的源码实证）、**ISSUE-P2-57**
> （派生密钥栈副本消除 + `sha2/zeroize` 启用）、**ISSUE-P2-58**（口令强度评估的平方级路径线性化 +
> 长度上限与线性惩罚 + 两个主线程调用点移出）、**ISSUE-P2-59**（派生入口逐项上界镜像 +
> 受检窄化，附跨模块上界锁）、**ISSUE-P2-55**（示例口令占位符化 + 构建期 fail-closed 闸门 +
> 既有 PKCS#12 re-key，同证书指纹）与 **ISSUE-P2-54**
> （依赖扫描补 PR / push 触发；AC② 分支保护留痕待仓库所有者配置）随存量安全整改批次（续）
> 归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §49。
>
> **2026-09-15 闭环（续）**：**ISSUE-P2-68**（四个数据类覆写 `toString()`，消除「一行 `log("$obj")` 即漏明文」面）
> 与 **ISSUE-P2-69**（日志抽样口径改为「按日志调用特征跨行抽取」，覆盖注入型 `debugLog` / `debugLogBuffer`
> 通道与委托型 `preferences.verbose(`；并顺带整改该通道 9 处插值点，含 `SyncCoordinator` 把
> `InvalidEndpointError` 内嵌的原始 endpoint URL 写入调试缓冲一项）随日志与对象字符串化卫生批次归档，
> 见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §50。
>
> **2026-09-15 闭环（续 2）**：**ISSUE-P2-43**（`autofillCopyTotp` 默认关闭，TOTP 不再默认入剪贴板）、
> **ISSUE-P2-71**（`inlineSuggestionsEnabled` 默认关闭，候选用户名 / 标题不再默认交给 IME）、
> **ISSUE-P2-64**（「内存密封」与「写出标志」两条口径在 KDoc 明确分离 + 口令密封不可降级回归锁；
> AC② 裁决不采纳「库级开启即内存密封」、AC③ 前提经官方语义更正）、
> **ISSUE-P2-66**（落盘清理 unlink-only 登记为已接受边界，`AGENTS.md` §6）随自动填充默认值与
> 外泄通道批次归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §51。
> 其中 P2-43 / P2-71 的整改**必须同时改单键读取的硬编码兜底**（`ExtendedSettingsStore` 的
> `isAutofillCopyTotpEnabled` / `isInlineSuggestionsEnabled` 原为 `?: true`），否则数据类默认值改动
> 在「无持久化层 / 键缺失」路径失效——该陷阱已由新增用例锁定。
>
> **2026-09-15 闭环（续 3）**：**ISSUE-P2-67**（同步路径解析远端库未传 `binaryStore` → 远端大附件整批内联进堆）
> 随「同步解析落盘与内存池擦除边界」批次归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §52；同批一并处置
> **ISSUE-P3-119**（内存池擦除边界与树外可达性）。整改要点：解析**收口到会话层**
> `DatabaseSession.parseExternalDatabase`（「与主会话同一个 store」由构造关系保证，新增调用方无法再忘记传参）。
>
> **2026-09-15 闭环（续 4）**：**ISSUE-P2-70**（选择器页强制展示请求方身份：包名 / 应用名 / 签名摘要 / 表单自报域）
> 与 **ISSUE-P2-81**（会话授权宽限的匹配域收窄：域不可归属时既不写入也不匹配授权）随
> 「自动填充请求方归属与授权宽限收窄」批次归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §53。
> **注**：`P2-70` 的 AC③（与 `ISSUE-P3-93` 同批）**未采纳**——该条要把「单摘要」模型改为「摘要集合」，
> 牵动信任存储键语义（`AutofillCallerTrustStore` 按「包名 + 证书」记录）与浏览器白名单比较，
> 属独立批次；`P3-93` 仍在表内，理由见 §53.3.4。
>
> **2026-09-15 闭环（续 5）**：**ISSUE-P2-74**（清单补**最小 `<queries>`**：`https` VIEW intent +
> 与 `BrowserSigningFingerprints.TRUSTED` 逐一对应的显式 `<package>`，使 Android 11+ 下调用方
> 证书指纹可读，恢复「浏览器委派」web 域归属判定）随「包可见性与序列化缓冲擦除」批次归档，
> 见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §54；同批一并处置 **ISSUE-P3-118**（整库序列化缓冲擦除）。
> **残余（如实声明）**：P2-74 的 AC①（设备侧复现）与 AC③（真机验证浏览器域自动填充）
> **仍需设备**——本批以官方文档为据（`getPackageInfo` 受包可见性过滤、无 autofill 豁免），
> 且修复为**纯增量声明**（不改变既有匹配逻辑），已写入 §54.3 待设备侧补验。
>
> **2026-09-13 第四轮独立复核定版（《SECURITY_RECHECK_2026-09.md》1272 行定版稿）**：
> ① **升格 P1 排期**（条目编号留原地、闭环按编号归档）：`P2-48 / P2-49 / P2-51 / P2-53 / P2-61 / P2-63 /
> P2-65 / P2-72 / P2-76 / P2-77`，及 P3 节的 `P3-109 / P3-116 / P3-117 / P3-120` 与 P1 节 `P1-22` 批注——
> 其中 **`P2-53` 与 `P2-63` 互为前提，必须同批**（若 `P2-53` 收口点取非 suspend 的 `currentEnforcement()`，
> 修复会被 `P2-63` 完全抵消）——**二者已于 2026-09-14 同批闭环（§48）**，`P2-48 / P2-51 / P2-61 / P2-65 / P2-72 / P2-76 / P2-77` 同批；
> 余下 `P2-49`（AC② 未闭环）与四项 P3 升格项（`P3-109` / `P3-117` 已于 2026-09-14 闭环）仍在表内；② **升格 P0**：`P2-75` → `ISSUE-P0-09`（**已整改闭环**，连同 `P2-75` 原行一并归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §44）；③ 新增 `ISSUE-P2-80`（M-1/M-2
> 设备实测）与 `ISSUE-P2-81`；④ 各行内「第四轮批注」为定版结论与 AC 修正，**实施前必读**；
> ⑤ 摘要：六项原审计让步 + `P2-49` 定级论证，全过程见该报告 §15.2(a)–(r)。
>
> **2026-09-15 闭环（续 18，§71 批次）**：**ISSUE-P2-42**（§38 批次遗留的 3 项「JVM 不可闭环」行为）
> 已由 **arm64 真机**（Redmi 4X / LineageOS / Android 17 / **API 37**）**经 ADB 逐项实测**收口——
> ① 敏感对话框窗口 `FLAG_SECURE` 真实生效（`dumpsys window` 证对话框窗口 `fl= DIM_BEHIND SECURE …`，
> 且 `screencap` meanLuma≈0.43/255 全黑）；② 附件缓存冷启动清理端到端（探针落盘 → `force-stop` 后**仍在**
> → 冷启动后**目录为空**）；③ `SecureDialog` **确实取到** `DialogWindowProvider`
> （非 fail-safe 空操作）。并按 AC③ 新增 2 例设备侧用例（`app` 15 → **17 例**，真机复跑
> 17 tests / 0 failures / 0 skipped）。该行已移出本表，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §71。
>
> **2026-09-15 闭环（续 19，§72 批次）**：**ISSUE-P2-44**（无障碍服务信号未纳入运行完整性体系
> + 敏感输入未显式声明 `password` 语义）已收口——① `IntegritySignals` 新增
> `thirdPartyAccessibilityEnabled`（官方 `AccessibilityManager` 枚举，口径为「服务包名 ≠ 本应用」，
> **含系统预装 TalkBack**）；② 策略新增 `requireAccessibilityNotice`，**只提示、不降级**
> （不因合法可及性配置禁用生物解锁 / 自动填充）；③ 主密码输入页渲染无障碍提示；
> ④ `SecurePasswordField` 显式 `semantics { password() }`。
> **AC② 前提经实测更正**（详见 §72.3）：改动**前**设备侧两类状态已报 `password="true"`
> （Compose 由 `PasswordVisualTransformation` 隐式推导），故本项为**显式化 + 回归锁定**，
> 而非修复已观测缺失——**改动前后无障碍树逐项一致**。
> **真机正负对照**（arm64 / API 37）：启用无障碍服务 → 提示出现且主密码框 `password="true"`；
> 关闭 → 提示消失、语义仍为真。该行已移出本表，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §72。
>
> **2026-09-15 闭环（续 20，§74 批次，§73 施工中的附带发现）**：登记并**同日闭环**
> **`ISSUE-P2-82`**（缓存清理幂等性缺陷，**不在本表留存行**，体例同 `ISSUE-P3-128`）——
> 全量单测中 `SyncCacheEvictorTest` 的 F-23 用例偶发失败（该用例**早已**记录偶发，当时只归因于
> Windows 文件句柄时序），本次靠其内建的失败上下文采集定位到**真实缺陷**：
> `SyncCache.clearAll()` 以 `File.delete()` 的返回值判定成功，而该 API 对**已不存在**的目标返回 false，
> 于是并发 / 连续两次清理必然出现后者误报失败——产出「**残留 0 项，密文可能仍可恢复**」的
> **自相矛盾 WARN**，并把 false 经 `evictAll()` 传回
> `MainApplication.purgeVolatileCachesBeforeExit()`（§68 的退出清理面）。
> 已改为「**删除后目标是否仍存在**」判定，并补 3 例回归（含 4 实例并发清理）。
> 详见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §74。
>
> **2026-09-15 闭环（续 22，§75 批次）**：**ISSUE-P2-46**（`android://` 包名绑定缺调用方签名指纹绑定）
> 已按定版口径闭环**自动填充通道**——① 新增纯函数 `AndroidPackageBindingPolicy`（**包名 + 签名摘要**
> 已绑定 **且摘要可读** 才放行；摘要不可读 **不得**退化到「仅包名」）；② `AutofillCandidateRanker.rank(...)`
> 新增**无默认值**的 `packageDimensionAuthorized`，`EXACT_PACKAGE` 只在授权为真时成立
> （**未授权一律「不命中」**，非报告所否决的「弱候选」）；③ 首次绑定**写入**落在选择器
> `AutofillPickerActivity.deliver()`——它是唯一由用户在受保护窗口内显式指认调用方的入口
> （该页已展示包名 / 应用名 / 签名摘要），否则会形成「候选被拦 ⇒ 无法绑定 ⇒ 永久失效」的死锁；
> ④ 服务注入与确认页 / 选择器**同一份**信任存储（写入面 = 判定面）。新增 11 例回归
> （策略 8 + 接线 3）并补齐排序器门控用例。
> **同根因拆分**：**Credential Manager 通道**的 4 处调用点（`CredentialResponseAssembler:117/188`、
> `KeePasskeyCredentialProviderService:342`、`PasswordFillActivity:97`）仍无签名绑定 ⇒ 已登记
> **`ISSUE-P2-83`** 独立跟踪（**不得**把本行闭环读作「`android://` 维度整体已加固」）。
> 该行已移出本表，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §75。
>
> **2026-09-15 闭环（续 23，§76 批次）**：**ISSUE-P2-80**（KDF 墙钟与闸门**分路径**实测缺口）
> 已由 **arm64 真机**（Redmi 4X / LineageOS / Android 17 / **API 37** / `maxHeap` 192 MiB）
> 经 `:database:connectedDebugAndroidTest` **真实执行**收口——① **M-2 分路径**：codec 动态堆门槛
> 接受 `M = 堆/2`（96 MiB）且拒绝 `+1 MiB`（边界双向核对）；JVM 兜底闸门（0.6×maxHeap）亦接受
> ⇒ 对 JVM 路径 **codec 那道更紧**；**原生路径无第二道闸**由「`M = 堆/2` 真实派生成功」证实，
> 实际到达内核 **98 304 KiB**；本机**有效内存上界 = 96 MiB**（纠正原文推算的「128–256 MiB」）。
> ② **M-1 墙钟**：默认 0.609/0.635 s、重载（96 MiB × I=8）2.470/2.537 s、吞吐 ≈2.1×10⁸ 字节·轮/秒；
> **当前预算最坏合法配置 ≈1.44 小时**（按实测吞吐换算，非独立推算）。
> ③ 实测值已回填 `ISSUE-P2-49` 口径，其 **AC② 处置路径据此定案**（协程超时无效；
> 有效路径为收紧联合预算或可取消派生）。数据登记于
> [`docs/records/原生Argon2真机验证记录.md`](records/原生Argon2真机验证记录.md) **§9**（与原 x86_64 表**禁混**）。
> 该行已移出本表，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §76。

> **2026-09-15 闭环（续 25，§78 批次）**：**ISSUE-P2-49**（KDF 联合预算 / 解锁派生超时，复核**升格 P1**）
> 已整体收口——AC①③ 见 §48；**AC②** 依 §76 真机实测速率把联合预算由 `2^40` **收紧为 `2^33`**
> （实测吞吐 2.169×10⁸ 字节·轮/秒 ⇒ 最坏合法配置墙钟由 **≈1.4 小时降至 ≈40 s**），
> 并以「按实测速率锚定的**有界工作量**」替代被复核定版否决的协程 `withTimeout`
> （阻塞式原生派生不可被打断）。同时补上此前完全缺失的 **AES-KDF 封顶轮数墙钟实测**：
> `R = 2^28` 实测换算 **≈35 s**（默认 `R = 6×10⁶` ≈0.79 s）⇒ 该封顶**无需调整**，
> 两个 KDF 族的最坏墙钟已收敛到同一量级。边界双向锁（新边界通过 / 原 `2^40` 档必须被拒）
> 与覆盖性核对（官方默认、真机可达上界、桌面偏执档）见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §78。
> 残余（无中途取消）登记于 `AGENTS.md` §6。该行已移出本表。
>
> **2026-09-16 闭环（§80 批次）**：**ISSUE-P2-45**（解锁失败节流的「删键复位」旁路）已收口——
> ① **AC① 维持撤销**（默认关闭系 2026-09-12 用户裁决，非缺陷），故本批只做 AC②；
> ② **AC② 按第四轮定版批注重新设计**（原「哨兵值」方案原理上不可闭环）：引入**每库一条
> AndroidKeyStore 存在性标记**（MAC 只能证明「在案记录未被改动」，标记才能证明「记录本应存在」），
> 并令 `reset()` 改写**带有效 MAC 的零值记录**而非删键 ⇒「三键全缺」在首次写入后不再有合法来源；
> ③ 新增 17 例宿主用例 + 5 例**真机** Keystore 回归（`UnlockThrottleDeletionBypassDeviceTest`）。
> **施工中的附带发现**：登记并同日闭环 **`ISSUE-P0-10`**——`Mac.getInstance(..., "AndroidKeyStore")`
> 在实机必然抛 `NoSuchAlgorithmException`（该 provider 不注册 Mac 服务）⇒ 节流 MAC 恒为空 ⇒
> **单次输错主密码即永久 fail-closed**。两项并入 §80，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §80。
>
> **2026-09-16 闭环（§81 批次，施工中的附带发现）**：登记并同日闭环 **`ISSUE-P2-84`**
> ——`PasswordSaveActivity` 丢弃 `saveAutofillCredential` 的 `KdbxResult` 后**无条件回传
> `RESULT_OK`**，落盘失败（磁盘满 / 会话 save 失败）被谎报为保存成功，系统据此认为凭据已入库
> 并**可能不再提示保存**，用户口令静默丢失。已改为失败即 `failAndFinish()`，并加接线守卫锁定
> 「失败判定前置于成功回传」。体例同 §74（同轮登记并闭环，不在本表留存行）。
> **本批同时推进 `ISSUE-P2-83`（AC 分步，本行仍留表内）与闭环 `ISSUE-P3-111`**，见 §81。
>
> **2026-09-16 闭环（§82 批次）**：**`ISSUE-P2-83`** 剩余部分（AC② 候选层 + AC③ 设备侧）已收口——
> ① 新增**三值**门控 `CredentialManagerPackageBindingGate`：把「已绑定但签名不匹配」（**拒绝**）
> 与「从未绑定」（退回既有行为）分开；二元判据会因 CM **没有选择器中立面**而使存量
> `android://` 条目无路可走。② 候选层判据抽为纯函数 `CredentialCandidateMatcher`，
> 使「不命中」可在宿主与真机**两条**上断言。③ 四处调用点全部接入（组装器两处、
> 密码填充与通行密钥断言的回传/签发前二次校验），`findMatchingEntries` 的入参**无默认值**。
> **残余（如实声明）**：AC③ 的「系统 CM UI 全链路」**未**由 ADB 驱动——本机
> `cmd credential` 返回 `No shell command implementation`，无入口构造 `BeginGetCredentialRequest`；
> 已在真机以**真实绑定存储 + 候选层判据**覆盖两条链路的入选/不入选。见 §82。
> **本项不得读作「`UNBOUND` 也已加固」**——从未绑定过的包名仍按既有包名维度放行，
> 其边界与理由写在门控 KDoc 中。
>
> **2026-09-16 进展（§84 批次，`ISSUE-P2-73` 与 `ISSUE-P3-122` 的 IPC-01 **同批**，本行均仍留表内）**：
> 第四轮定版批注要求本项与 `IPC-01`「**须同批实测**」，故两项合并为 §84 批次。
> - **`P2-73` AC① 已完成（且发现原条文低估了严重性）**：官方 `FillResponse.Builder#setAuthentication`
>   明文写着「**IMPORTANT: Extras must be non-null on the intent being set for Android 12 otherwise it
>   will cause a crash. Do not use `Activity.setResult(int)`, instead use `Activity.setResult(int, Intent)`
>   with non-null extras**」。两处裸 `setResult(RESULT_OK)` 因此不只是「协议漂移」，而是
>   **Android 12+ 上的崩溃级缺陷**（minSdk 36 ⇒ 恒在该区间）。已按官方给出的等价做法改为
>   `setResult(RESULT_OK, Intent().putExtras(Bundle.EMPTY))`（extras 非空、载荷语义不变）。
> - **AC① 的「回传数据集」部分与 AC② 存在硬冲突，已登记为决策点（本批不自行择一）**：要让框架
>   真正写入数据集，活动需构造并回传 `Dataset`；而活动拿到字段 `AutofillId` 的正规途径是框架
>   注入的 `EXTRA_ASSIST_STRUCTURE`，**该注入要求 PendingIntent 为 `FLAG_MUTABLE`**——正是第四轮
>   批注明令**不得**改的那一项（「为对齐文档而降低安全性」）。另一条路是让服务把已填充的
>   `Dataset` 经自家 Intent 传给活动，代价是**新增一份明文 Parcel 副本**，与「敏感数据铁律」相抵。
>   两条路各有代价，**须由定版方在「AC② 撤销」与「AC① 数据集回传」之间裁决**。
> - **AC② 未实施**（遵循第四轮定版批注）；并由接线守卫**反向锁定** FLAG 口径：选择器恒
>   `FLAG_MUTABLE`（1 处）、解锁与确认恒 `FLAG_IMMUTABLE`（2 处），防止后人「顺手统一」。
> - **AC③（设备侧实测认证填充链路）未完成**：需要真实 autofill 客户端触发认证流；
>   本机无 ADB 侧入口（与 `P2-83` AC③ 同类边界），已登记残余。
> - **`P3-122` IPC-01 已完成**：认证 `PendingIntent` 的 requestCode 由**常量**改为**进程级单调
>   分配器**（与 CM 通道 `RequestCodeAllocator` 同构），从根上消除「两次 `onFillRequest` 共用
>   requestCode + `FLAG_UPDATE_CURRENT` ⇒ 点旧候选拉起新上下文」的 TOTP 错配 / 会话授权串扰面。
> - **`P3-122` IPC-10 未完成**：`onSaveRequest` 的超时预算涉及整段协程体包裹与收尾语义，
>   本批未动（该条**仍在表内**）。
> - 详见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §84。

### ISSUE-P2-47（新登记）：同步与封印凭据的回滚防护不足

- **优先级**：P2（数据新鲜性；需后端写权限或同 UID 能力）
- **核实时间点与核实方式（2026-09-13）**：确认 `sync/src/main/.../SyncRollbackGuard.kt` +
  `SyncIntegrityMac.kt` 存在且由回滚路径消费（`SyncEngine` / `SyncCache` / `KeystoreSyncIntegrityMac`）；
  读取 `KeystoreManager.kt` L206-218 注释确认其完整性 MAC 密钥**无用户认证门控**（"锁屏态亦可用"）。
- **问题描述**：① **同步回滚** —— 服务端（或 MITM）提供一个**合法的旧 `.kdbx`** 时，
  防护取决于本地"已见版本"记录是否单调且不可回滚；因 MAC 密钥无认证门控，同 UID 可重算，回滚可能不被拦住；
  ② **封印凭据回滚** —— 封印载荷未绑定"库 header 摘要 + 封印序号"，攻击者可把快速解锁
  回滚到旧主密码版本（价值有限，除非用户复用旧口令）。
- **验收标准**：① 把"已见库版本"绑定到**远端不可回滚信道**（如 S3 Object Versioning / ETag 单调性），
  或对远端 header 摘要做本地单调记录，并对回退**强制走冲突裁决 UI 而非静默覆盖**；
  ② 封印载荷内绑定"库 header 摘要 + 封印序号"，解封时与当前库比对，不匹配即失效并要求主密码解锁；
  ③ 两条各有回归用例（含"远端回退 → 拒绝自动合并"）。
- **第四轮定版批注（2026-09-13）**：复核证实 `SyncRollbackGuard.State.sequence` **已持久化但不参与裁决**  （写点 `:142`/`:180`；裁决点 `inspect :118-127` 只比对 `current`/`recent`；类 KDoc `:76-80` 自认）——
  **勿据"sequence 已持久化"推断防回滚强度**（"看似已修"陷阱）。防护现状为"Keystore-HMAC 已见摘要链 +
  状态缺失 / MAC 失效 **fail-open**（`:145-158`，已留痕取舍）"。另 **AC① 修正**：不得依赖
  "S3 Versioning / ETag 单调性"作为客户端可信信道（不可实施），须本地单调记录。
- **2026-09-16 复核批注（§83 批次，实施前必读）**：
  - **AC② 按原文不可实施（代码取证，非推测）**：`KdbxFile.kt:337` 保存时 `p.copy(salt = freshSalt)`、
    `:342` `masterSeed = freshMasterSeed`——**KDF 盐与主种子每次保存都重新生成**，故任何形式的
    「库 header 摘要」**每次保存都会变**。照 AC② 原文把封印载荷绑定 header 摘要，结果是
    **每次保存后快速解锁立即失效**（核心功能回归）。另：封印载荷持有的是**主密码**，
    「回滚到旧主密码版本」的旧口令本就解不开当前库（原条文亦自认"价值有限"）；
    而「库与封印被**一致**回滚」属同步防回滚链（AC①）的职责，封印侧无参照可比。
    ⇒ 须改设计（可行方向：封印载荷绑定**库文件内容摘要**并在每次保存后刷新，代价是刷新窗口内需
    回退主密码）或登记为**已接受边界**——**属架构决策，不得由实施者自行择一**。
  - **AC① 的实质已具备（复核确认）**：`SyncRollbackGuard` 的「已见内容摘要链」本身就是**本地记录**
    （不依赖任何服务端版本号）；命中 `recent` 即 `ReplayDetected`，`SyncEngine` 三处裁决点
    （`:108` / `:173` / `:215`）均**不应用**远端内容，经 `SyncCycleRunner:387` 落为
    `SyncOutcome.Error(sync_error_rollback_rejected)`——**不存在静默覆盖**。
  - **`sequence` 处理方式（本批刻意不动格式）**：字段已持久化但不参与裁决，类 KDoc `:76-80`
    已明写「启用前不得据此推断防回滚强度」。**不得**为「让它看起来有用」而改其持久化格式——
    那会变更 MAC 载荷、使**全部存量状态文件 MAC 失效**，按既有的 MAC 失效 fail-open 口径，
    等于给所有存量用户打开一次重放窗口，代价大于收益。
  - **AC③ 状态**：`SyncRollbackGuardTest` 已有 8 例（摘要链、曾接受版本判重放、F-23 状态存活、
    fail-open 口径、状态不被缓存清理删除）；**尚缺引擎级「远端回退 → 拒绝且不覆盖本地」用例**（待补）。
  - **本项仍未闭环**：卡在 AC② 的设计取舍（见上），须架构 / 产品决策后再实施。

---

> **2026-09-13 新增（外部安全审计批次）**：`docs/SECURITY_AUDIT_2026-09.md` 主报告已于同日本批次
> **退役删除**（处置归档见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §40）。下表为在该报告基线 `d32f3e7`
> 与当前 `a669a48` **双向对拍后仍成立**的条目转登，逐项附核实结果；详细修复方案、误报排除论证、
> 待复核区、无法确认项与 CVSS↔CWE 对照见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §43
> （承载该轮审计结论的 `SECURITY_AUDIT_REMEDIATION.md` 亦已于 2026-09-13 退役，其附录 A–F 全部留存于该节）。
> **重合声明**：审计 `F-01`（解锁节流默认关闭）已由本轮红队批次的 `ISSUE-P2-45` 覆盖，**不重复登记**。

> **（该表已于 2026-09-15 清空）** 本批次唯一在册条目 **`ISSUE-P2-49`** 已随 **§78 批次闭环**并移出本表：
> AC①（`I×M` 联合预算）与 AC③（官方参数域对照）见 `RESOLVED_LOG.md` §48；
> **AC② 见 §78**——依 §76 真机实测速率把联合预算由 `2^40` 收紧为 **`2^33`**（最坏耗时由 ≈1.4 小时降至 ≈40 s），
> 并以「有界工作量」替代被否决的协程 `withTimeout`；残余（无中途取消）登记于 `AGENTS.md` §6。

> **2026-09-13 新增（敏感数据流审计批次）**：`docs/SENSITIVE_DATA_FLOW_AUDIT_2026-09.md` 已于同日
> **退役删除**（处置归档见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §41）。该文档为**未跟踪文件**，
> 原文**不可**经 `git show` 取回，故其结论已按 §41 完成分流。下表为在该报告基线 `d32f3e7`
> 与当前 `a669a48` **双向对拍后仍成立**的条目转登，逐项附核实结果。
>
> **重合声明（不重复登记）**：`H-new-1`（CM 主通道不受完整性门控）→ 已由 `ISSUE-P2-53` 覆盖；
> `S1`（发布签名口令 = 示例值）→ 已由 `ISSUE-P2-55` 覆盖；`E3`（自动填充后 TOTP 默认进剪贴板）→
> 已由 `ISSUE-P2-43` 覆盖；`M9`（剪贴板不随锁定 / 熄屏清理）→ 已由 `ISSUE-P2-51` + `ISSUE-P3-84` 覆盖；
> `M5`（明文导出缓冲未清零）→ 已由 `ISSUE-P3-86` 覆盖；`M2`（主密码在 Compose 中为不可擦 `String`）→
> 其**输入法通道**已由 `ISSUE-P3-76` 覆盖，其余属已接受残余风险（见 `AGENTS.md` §6）。
>
> **已撤回 / 非缺陷（不转登）**：`H-new-3`（`requireRiskNotice` 无消费者）经复核实为**误报**——
> 基线与 HEAD 均已在设置页渲染 `IntegrityRiskCard`（`SecuritySettingsScreen.kt`）；`H1 附注`
> （`VaultEntryMapperTotpTest` "虚假安全感"）系误读测试意图（该用例测**读取**路径兼容性）；
> `M8`（`flagSecureEnabled=false` 解除截屏保护）为产品裁决的有意设计（`RESOLVED_LOG` §29.3 / §38）。

| 编号 | 来源 | 问题与位置（核实于 2026-09-13，对 HEAD `a669a48`） | 验收标准 |
|---|---|---|---|
| ISSUE-P2-73 | 审计 E5 | 自动填充认证流两处协议漂移：① `AutofillUnlockActivity.kt:65` / `AutofillConfirmActivity.kt:160` 用裸 `setResult(RESULT_OK)`，**不带** `AutofillManager.EXTRA_AUTHENTICATION_RESULT`（官方要求经该 extra 回传数据集）；② `AutofillDatasetBuilders.kt:69,206` 创建认证 `PendingIntent` 用 `FLAG_IMMUTABLE`，而平台需向其中填认证参数（picker 路径 `:240` 用 `FLAG_MUTABLE` 是对的） | ① 按官方以 `EXTRA_AUTHENTICATION_RESULT` 回传；② 认证 `PendingIntent` 改 `FLAG_MUTABLE`（并保持 base intent 显式 + `Intent.fillIn` 覆盖语义）；③ 设备侧实测认证填充链路 |

---

> **2026-09-13 新增（威胁建模报告退役批次）**：`docs/THREAT-MODEL-AUDIT-d32f3e7.md` 已于同日
> **退役删除**（处置归档见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §42）。下表为在该报告基线 `d32f3e7`
> 与当前 `a669a48` 对拍后**仍成立、且此前未登记**的开放项。
>
> **核实时间点与核实方式（2026-09-13，对 HEAD `a669a48`）**：逐条读取源码复核——
> `WebDavSyncProvider.kt` / `WebDavPropfindParser.kt` / `CredentialVerificationLauncher.kt` /
> `SessionOpener.kt` / `KeePasskeyCredentialProviderService.kt` / `PasswordSaveActivity.kt` /
> `VaultEntryWriteCoordinator.kt` / `KdbxHeader.kt` / `SyncProviderResolver.kt`（均为直读，非 grep 片段推断）。
>
> **已覆盖（不重复登记）**：`Q-1→ISSUE-P2-53`、`Q-4→§38 ISSUE-P2-36`、`Q-5→ISSUE-P2-62`、
> `Q-7→ISSUE-P1-25`、`Q-9→ISSUE-P3-107`、`Q-12→ISSUE-P2-69`、`Q-13`/`T-8→§38 ISSUE-P1-20`、
> `Q-17 前半→ISSUE-P3-100`、`T-4→ISSUE-P1-24`/`ISSUE-P2-70`、`T-11→ISSUE-P1-25`、
> `T-12→ISSUE-P3-103`、`T-13→ISSUE-P1-22`、`T-14→ISSUE-P3-92`、`T-15 部分→ISSUE-P2-60`、
> `Q-6→ISSUE-P3-82`、`T-3→ISSUE-P3-76`。

| 编号 | 来源 | 问题与位置（核实于 2026-09-13，对 HEAD `a669a48`） | 验收标准 |
|---|---|---|---|
| ISSUE-P2-79 | 威胁建模 Q-3 / 审计 A-1、A-2（**需产品确认**） | **KDF 强度基线未对齐**（唯一纯密码学边界的强度参数）：① 建库默认 `Argon2id m=64 MiB / t=2 / p=2`（`KdbxHeader.kt:162-171`），而仓库已具备设备自适应推荐 `KdfBenchmark`（`SettingsKdfBenchmarkController.kt:17-49`）——**仅设置页展示，建库路径零消费**（核实：`SessionOpener.create` 直接走 `KdbxHeader.createDefault`）；② 读取路径接受极弱参数（`m=1 MiB, t=1, p=1`；AES-KDF `R=1`）并在保存时**原样保留**（仅刷新盐） | ① 产品确认目标强度口径（如"设备实测约 1 秒"）；② 建库路径消费 `KdfBenchmark` 建议值（或提供"使用推荐参数"默认）；③ 对过低工作量的导入给出告警或升级选项；④ 参数变更不得破坏既有库可解锁性与官方客户端互操作 |

---

## P3 低危问题、特性接线与体验优化（2 项）

> **状态（2026-09-12）**：历史 P3 批次 **ISSUE-P3-01 ~ P3-68** 除 P3-23（经产品裁决「不排期」）外
> 已全部闭环并归档，逐条实现细节与验收证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md)（§3 ~ §32）。
> 2026-09-12 存量修复批次闭环 P3-63 / P3-65 / P3-67（§28.2 ~ §28.4）；
> **ISSUE-P3-68**（重试节流开关与自定义最长锁定时长）同日闭环归档（§29.2）；
> **外部安全审计整改批次 P3-69 ~ P3-72** 同日闭环归档（§30）；
> **文档类存量整改批次 P3-75 / P3-77** 同日闭环归档（§31）；
> **CSV 导入 / 导出扩充 P3-73** 同日闭环归档（§32）。
> **设备侧验证缺口 P2-27 收口**：§34（app 导入解析设备侧骨架）+ §36（app 域解析 / 解锁落盘、
> sync 权限基线）同日闭环；**工程整洁收口批次**（死文案 / 假开关 / `AGENTS` §6 更正）同日归档（§37）。
> **产品裁决「不排期」**：P3-58 / P3-66 / P3-74（见 §33）。
> **保留待办**：**ISSUE-P3-76**（框架阻塞的已接受残余风险，保留跟踪与解除条件）。
> **2026-09-13 新增（红队攻击路径批次）**：P3-82 ~ P3-85 四项由红队批次对拍后留存为开放项转登，
> 来源与已撤回项见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §39。
> **2026-09-14 闭环**：**ISSUE-P3-84**（剪贴板风险明示 + 切后台即清 + 自动填充引导）随
> 存量安全整改批次归档，见 §48。
> **2026-09-15 闭环（续）**：**ISSUE-P3-119**（内存附件池擦除边界 + 树外可达性 + 同步路径解析产物显式擦除）
> 随同批 §52 归档——三处同步路径中，**仅服务单次内容判定 / 一次性合并**的解析产物已显式擦除；
> 池内擦除（`InnerHeader.binaries`）因 `KdbxDatabase.copy()` 共享列表需先定所有权规则，
> 按已接受边界登记于 `AGENTS.md` §6（含解除条件）。
> **2026-09-15 闭环（续 6）**：**ISSUE-P3-103**（TOTP 取景窗口接线遮挡触摸过滤）、
> **ISSUE-P3-104**（`KdbxAttachment` 类 KDoc 与实现口径统一）、**ISSUE-P3-105**（附件读取双拷贝消除 +
> 写出侧交付副本用毕清零）随「附件字节所有权与敏感窗口接线」批次归档，见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) §59。
> **2026-09-15 闭环（续 7）**：**ISSUE-P3-106**（备份 / 迁移排除域穷举补全 `external` 与 `device_*`）、
> **ISSUE-P3-112**（`SafDocumentCleanup` 删除前补文档 URI 归属判定）、**ISSUE-P3-115**
> （Gradle 分发镜像来源与锁定哈希补记入 `AGENTS.md` §1）随「退路面加固与来源登记」批次归档，见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) §60。
> **2026-09-15 闭环（续 8）**：**ISSUE-P3-113**（字段屏蔽签名密钥不可用的 fail-closed 故障显式化：
> 密钥探针区分「输入非法」与「密钥不可用」，后者经自动填充健康自检卡片对用户可见）随
> 「fail-closed 可观测性」批次归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §61。
> **2026-09-15 闭环（续 9）**：**ISSUE-P3-114**（剪贴板文案按平台语义更正——`EXTRA_IS_SENSITIVE`
> 是渲染提示而非行为约束）、**ISSUE-P3-107**（`.kdbx.bak` 滚动备份的语义 / 保留期 / 残余登记 +
> 设置页文案更正）、**ISSUE-P3-110**（明文导出确认令牌下沉到导出控制器层，令牌不可伪造）随
> 「导出确认令牌下沉与文案一致性」批次归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §62。
> **同批附带发现**：密钥文件导出缺二次确认（策略已定为 PLAINTEXT 风险但调用点未接线），
> 已登记为 **`ISSUE-P3-128`** 并于**同日 §63 批次闭环**（不留在表内）。
> **2026-09-15 闭环（续 10）**：**ISSUE-P3-128**（密钥文件导出二次确认 + 令牌门控，
> 与明文 XML / CSV 同口径）随「密钥文件导出确认」批次归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §63。
> **2026-09-15 闭环（续 11）**：**第一轮审计 F 系列收尾批次**——**ISSUE-P3-86**（整库导出缓冲清零）、
> **ISSUE-P3-87**（明文导出令牌下沉，与 P3-110 同根因）、**ISSUE-P3-89**（审计短摘要熵修正）、
> **ISSUE-P3-90**（删除 `OtpEngine` 未加固的 `parseOtpAuthUri` 副本）、**ISSUE-P3-92**
> （ChaCha20 标签纠偏 + 词汇表收敛）五项整改归档；**ISSUE-P3-91** 经复核**前提已不成立**
> （`HmacBlockStream.readAll` 早已改用 `MessageDigest.isEqual`）一并归档，见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) §64。
> **2026-09-15 闭环（续 12）**：**ISSUE-P3-88**（Chrome 签名指纹 65-hex 笔误：三处无冒号副本同批修正为
> 冒号规范副本的 64 位形式 + 新增「全部指纹 64 位大写 hex」与「同值两种写法必须规范化一致」双向断言）
> 随「浏览器指纹格式守卫」批次归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §65。
> **残余（如实声明）**：官方 `gpm-passkeys-privileged-apps/apps.json` 的**上游再核对未执行**
> （本次无对外网络访问：GitHub MCP 与公网检索均不可达），本批修正取自**仓内冒号分隔规范副本**（非 retype），
> 该残余与复现方式记于 §65.3。
> **2026-09-15 部分闭环（续 13，§66 批次）**：**ISSUE-P3-125 ③**（原生内核构建失败 fail-closed：
> 移除 `isIgnoreExitValue`，仅保留「cargo 缺失即跳过」；已以「注入非法 cargo 参数 ⇒ BUILD FAILED」实测）
> 与 **ISSUE-P3-126 ③**（KDBX 版本策略显式声明「仅校验 major」+ 删除死常量 `VERSION_4_1`
> 与死字段 `SettingsUiState.kdbxFormat`）**子项闭环**，两行的 ①② 子项仍开放——故两条**保留在表内**，
> 见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §66。
> **2026-09-15 闭环（续 14，§67 批次）**：**ISSUE-P3-126** 整体闭环（① CI 产物明确标注「非发布签名」：
> artifact 更名 + `NOT-FOR-RELEASE.txt`；② 更正 `dependency-scan.yml` 的「清单为空」失真说明并改为只声明纪律，
> 新增 `SupplyChainSuppressionPolicyTest` 守卫）⇒ 行已移出本表；**ISSUE-P3-125 ②** 子项闭环
> （DAL 端点/时钟由「可写 internal var」改为**构造注入只读策略**，生产不可重定向）——该行保留
> （仅剩 ① 选择器零匹配门槛），见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §67。
> **2026-09-15 闭环（续 15，§68 批次）**：**ISSUE-P3-116**（「彻底退出应用」不清缓存）闭环——
> 终止动作改为「退栈 → **清理易失缓存** → 退出进程」固定顺序（清理必须早于 `exitProcess`，
> 否则永远不执行），退出前统一清理 `cacheDir/attachments`（附件解密明文）与 `cacheDir/sync`
> （KDBX 密文快照），⇒ 行已移出本表；`.kdbx.bak` 与 `filesDir/rollback` **不在**清理面内，
> 口径已写入 `AGENTS.md` §6，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §68。
> **2026-09-15 闭环（续 16，§69 批次）**：**ISSUE-P3-129**（文档索引悬空引用三处）与 **ISSUE-P3-127**
> （复核报告退役未登记；与 `P3-129` ② 为**同根因重复登记**）**同批收口**——① 补录 §68 批次正文
> `docs/resolved/batches/68-退出前清理易失缓存批次.md`；② `docs/SECURITY_RECHECK_2026-09.md`
> 由 `523d0fd^` **恢复入库**至 `docs/security/`，一致性闸门 `check_recheck_consistency.sh` 复效
> （复跑 `PASS`，扫描 1272 行 / 11 条禁用短语），`AGENTS.md` §4 索引、§5 命令与脚本默认路径同步更正；
> ③ 4 处 `docs/.handoff/ISSUE-P3-09.md` 引用（该目录**从未入库**、不可 `git show` 取回）
> 改为承接文档 `docs/records/退役依据承接-ISSUE-P3-09.md`。两条已移出本表，
> 见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §69。
> **2026-09-15 闭环（续 17，§70 批次）**：**ISSUE-P3-78**（Argon2 `S` 长度未按官方上下界校验）
> 与 **ISSUE-P3-80**（`KdbxConstants.Xml` 缺 `Compressed` 常量）**同批收口**——① 解码侧新增
> `validateArgon2SaltBounds()`（官方 `Argon2Kdf.cs:57-58` 的 `MinSalt=8` / `MaxSalt=0x3FFFFFFF`，
> **2026-09-15 定点直读该文件核实**）并接线到 Argon2 解码分支；② `Compressed` 上收为
> `KdbxConstants.Xml.COMPRESSED`（取值不变），实现内私有常量删除。新增 4 例回归（走
> 「写侧序列化 → 读侧反序列化」真实字节流）；全量 **1774 例 / 0 失败 / 0 错误 / 13 跳过**。
> 两条已移出本表，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §70。
> **2026-09-15 闭环（续 21，§73 批次）**：**ISSUE-P3-97**（CI 无一 job 同时具备 Rust 工具链与
> 原生用例真实执行）与 **ISSUE-P3-85**（自动填充 / 组件面低危硬化）**同批收口**——
> ① `native-gate` 在 `cargoNdkBuild` 后**真实执行** `:crypto:test`，并新增「用例数 > 0 且跳过数 == 0」
> 断言与 **4 ABI `.so` 导出符号核对**（恰好 5 个 `Java_com_keepasskey`；期望值以本机
> NDK `llvm-nm` 对已构建产物**实测**为准，非引用条目原文）；
> ② `MainActivity` 置 `launchMode="singleTask"` + `taskAffinity=""`，并新增
> `ExportedComponentHygieneTest`（5 例）锁定导出面：导出组件须带 `BIND_*` 权限或在白名单内、
> **白名单与实际无权限导出集合双向相等**、启动器入口不得消费任何外部 intent 数据。
> **`ISSUE-P3-85 ③`（一次性 nonce）按条目 AC 明示「可延后」未实施**，如实登记为残余。
> 两条已移出本表，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §73。
> **2026-09-15 闭环（续 24，§77 批次）**：**ISSUE-P3-79**（Compose Popup 系窗口未施加 `FLAG_SECURE`）
> 已收口——**逐点直读源码独立复核**（非引用复核报告结论）确认全仓 Popup 调用点**恰好 4 处、菜单项 8 个**
> （`VaultListTopBars` 1 / `VaultGroupRow` 3 / `EntryDetailTopBar` 3 / `CloudSyncComponents` 1），
> **全部为静态动作文案或 provider 名称，无任何凭据类插值**；全仓亦无其它
> `Popup(` / `TooltipBox(` / `ModalBottomSheet(` 调用点 ⇒ 按 AC③ **留痕「无需接线」**，
> 不采用「全量加 flag」的过度改动。新增 `PopupSecureFlagInventoryTest`（3 例）把「今天无需接线」
> 变成可执行守卫：**调用点清单锁**（新增未复核的 Popup 即报红）+ **菜单块敏感记号扫描**
> （`readString(` / `password` / `totp` / `otpauth` / `secret` / `userName`）+
> **防空扫断言**（抽取到的菜单项数必须恰为 8，防止「扫描通过但其实什么都没扫到」的假绿）。
> 结论与接线条件同步写入 `SecureDialog` 的 KDoc。该行已移出本表，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §77。
>
> **2026-09-15 闭环（续 26，§79 批次）**：**ISSUE-P3-108**（`AtomicFileWriter` / `SyncCache.updateBase`
> 的 `.tmp` 残留窗口）已收口——**先判定再定口径**：`.tmp` 承载 KDBX 密文快照片段，
> 「锁定即销毁密文快照」的语义下**残留 `.tmp` 构成可观测缺陷**，故**不放宽任何断言**，改修根因。
> **根因**：`File.delete()` 在句柄尚未释放的瞬间返回 false，原实现把该**瞬时**失败当作清理失败
> （`clearAll()` 返回 false → 产出「残留 N 项，密文可能仍可恢复」告警 → 传回退出清理入口）。
> **修复**：`SyncCache.deleteCacheChild` 增**有界重试**（3 次 × 15 ms，仅在真的删除失败时等待），
> 并把 `clear(remotePath)` 也改走同一原语（原先丢弃返回值、行为不一致）。
> **新增覆盖**：`updateBase` 路径清理后**无任何 `.tmp`** + 异常路径残留（写中断的 `.cache.<uuid>.tmp`、
> 未交付的 `.rollback.<uuid>.tmp`）必须被清且已交付的 `.rollback` 必须保留。
> 该行已移出本表，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §79。
> **2026-09-15 补登（§69 附带）**：依 `ISSUE-P3-127` AC②「依据恢复 ⇒ 逐条对齐」，已按恢复入库的
> 复核报告 §10 / §11 逐条核对本表 AC，并在本文件开头新增
> **「第四轮独立复核定版结论：对本表 AC 的更正（实施前必读）」** 一节（18 行）。
> **实施对应条目前必须先读该节**——其中 `P2-73` AC②、`P2-79` AC②、`P2-46` AC 按原文实施会
> **降低安全性**或**仍把凭据交给侧载应用**。
> **2026-09-13 新增（威胁建模 / 安全整改报告退役批次）**：P3-116 ~ P3-124 九项由
> `THREAT-MODEL-AUDIT-d32f3e7.md` 与 `SECURITY_AUDIT_REMEDIATION.md` 的对拍结果转登
> （两份报告同日退役删除，处置归档见 §42 / §43）。
> **2026-09-13 第四轮独立复核定版**：`P3-109 / P3-116 / P3-117 / P3-120` **升格 P1 排期**（编号留原地）；
> `P3-86` 升格 MEDIUM / 修 P2；`P3-122` 的 4 项复核结论已裁（转残余整改）；新增 `ISSUE-P3-125` /
> `ISSUE-P3-126`（复核新发现 6 小项）；各行「第四轮批注」为定版结论，实施前必读。

---


> **2026-09-13 新增（敏感数据流审计批次）**：转登自已退役的 `docs/SENSITIVE_DATA_FLOW_AUDIT_2026-09.md`
> （处置归档见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §41）。以下为在基线 `d32f3e7` 与当前 `a669a48`
> 对拍后**仍成立**、且**不改变安全承诺**的低危 / 卫生项，逐项附核实结果。
>
> **不转登**：`L11`（`KdbxKeyFileGenerator` 的 hex `String` 为交付物）属**格式边界且设计接受**，非缺陷；
> `L12`（口令长度进日志）经复核**在 HEAD 已不存在**（`UnlockViewModel.kt:191` 文案为 `input updated`，不含长度）——
> 该条在报告基线之后已由代码变更消除；`L22`（`_gitobj/` 未被 `.gitignore` 覆盖）为**空目录**，
> git 本不跟踪空目录，非问题。

> **（该表已于 2026-09-16 清空）** 本批次唯一在册条目 **`ISSUE-P3-111`** 已随 **§81 批次闭环**并移出本表。
> **前提更正（`AGENTS.md` §3.6 规则 2：开工前复核前提）**：原正文称「全仓无
> `retrieveProviderGetCredentialRequest` 调用」——该前提在 HEAD 上**已部分不成立**：
> `PasskeyAssertionActivity.kt:67` 自 **ISSUE-P2-72**（§48）起已检索系统请求并与 `expectedPackage`
> 交叉核对。故本项**实际残留只有 `PasswordFillActivity` 一条**，已按第四轮复核对 P3-111 的定版提醒
> （「先保证 `retrieve*` 非 null，否则交叉核对恒失败，把『能填充』变成『不能填充』」）补齐，
> 并加接线守卫锁定。详见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §81。
>
> **2026-09-16 闭环（§83 批次）**：**`ISSUE-P3-82`** 已收口——新增
> `database/src/androidTest/.../KdbxXmlDtdRejectionDeviceTest`（5 例，真机 Redmi 4X 实跑）：
> 按第四轮复核对本项 AC 的更正，**分别断言 D-1 / D-2 / D-3 三条前置依赖**，而不是笼统断言
> 「注入 DTD 会抛异常」——因为三条前置任一条失效，笼统断言都可能仍然「绿」。
> 同时按定版建议**直接喂 XML 给 `KdbxXmlParser`**（绕过需要重新加密的外层 KDBX），
> 使断言精确落在被保护的那一层；并补**反向对照**（无 DTD 文档不得被 DTD 兜底误伤）。
> 详见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §83。
>
> **2026-09-16 进展（§84 批次）**：**`ISSUE-P3-122` 的 `IPC-01` 已完成**（认证 `PendingIntent`
> 的 requestCode 由常量改为**进程级单调分配器**，与 CM 通道同构）；**`IPC-10`（`onSaveRequest`
> 超时预算）未完成**。本批与 `ISSUE-P2-73` **同批**（第四轮定版批注要求二者「须同批实测」），
> 该条 AC① 已完成、AC② 依定版批注**未实施**、AC③ 未完成。**两条目均仍在表内**，
> 逐项进展与决策点见本文件 P2 节前言（§84 段）与 [RESOLVED_LOG.md](RESOLVED_LOG.md) §84。
>
> **2026-09-16 闭环（§85 批次）**：**`ISSUE-P3-83`**（`TracerPid` / 内存取证门控缺失）已收口——
> ① **AC①**：新增 `ProcTracerPid`（纯解析）+ `TracedProcessProbe`（生产实现**同步**读
> `/proc/self/status`，有界 8 KiB），并把 `beingTraced` 作为**实时信号**并入既有
> `RuntimeIntegrityPolicy.escalateForLiveSignals` ⇒ 命中即 `COMPROMISED`，
> **生物快速解锁与自动填充（CM 通道）两条既有门控同时获得该信号**（`currentEnforcement()` /
> `awaitEnforcement()` 两个入口均已接线）。之所以必须**同步**求值而非并入周期重扫：
> `TracerPid` 是瞬时信号，「附加 → 读取 → 脱离」窗口会被重扫间隔整个漏掉。
> ② **AC②（`prctl(PR_SET_DUMPABLE,0)` / `MADV_DONTDUMP`）评估结论：不实施**，理由见 §85.3
> ——不得以牺牲稳定性换取纸面加固。③ **AC③**：边界已写入两处 KDoc 与批次文档
> （**非阻断承诺**；读不到 `/proc/self/status` 时按「未检测到」处理为**明示 fail-open**，
> 并由用例锁定）。
> 真机 `tests=33` 全绿，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §85。
>
> **2026-09-16 闭环（§86 批次）**：**`ISSUE-P3-124`**（DAL 出口未接纵深防御）已收口——
> ① **AC①**：DAL 出口客户端不再由校验器自建，改由 `DalVerifierModule` 以 `@DalHttpClient`
> 提供，构建路径统一走 `SyncHttpClientFactory.createSyncClient`，直接获得**TLS-only
> `connectionSpecs`**（显式排除 `CLEARTEXT`）与 **`SsrfGuardDns`**（连接期拦截内网 / 保留网段、
> 抵御 DNS 重绑定）两项同步侧已测试的加固；`ssrfAllowedHosts` **保持空集**——
> `ISSUE-P3-121` 的内网 WebDAV 逃生通道**不适用**于 DAL（RP 声明只可能来自公网）。
> ② **AC②**：新增 `DigitalAssetLinksEgressHardeningTest`（5 例）**对生产提供方法求值**——
> 断言 TLS-only、装配 `SsrfGuardDns`、**拒绝回环目标**（证明守卫真的生效而非纸面配置）、
> 沿用紧超时预算、且校验器不得在运行期把出口改回裸客户端。
> ③ **AC④（两侧 skip 开关不对称）留痕结论**：**不对称是安全性驱动的有意设计**，不追求对称化
> ——注册侧（Passkey）是「用户显式创建凭据」，用户看得见并接受降级风险；自动填充侧判的是
> **域归属**（决定凭据可被送往哪个域名），提供跳过开关等于在无逐次可见确认的情况下把 web 域
> 匹配降级为「表单自报域」，是**放松放行面**而非隐私让步。已写入校验器 KDoc。
> 见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §86。
>
> **2026-09-16 闭环（§87 批次）**：**`ISSUE-P3-123`**（CI / 供应链硬化 4 项）已收口，逐项处置如下
> （**④ 为实质整改，①②③ 为取证后的如实登记**）：
> - **④ `mapping.txt`（第四轮升格 MEDIUM）——已整改**：公开仓库的 artifact 上传等同公开文件，
>   整改前 `build.yml` 在 `if: always()` 的归档步骤里一并上传 `mapping/seeds/usage/configuration`
>   ⇒ 对密码管理器而言等于公开**类 / 方法 / 字段命名全貌**。现**移出产物集**，改为把四份映射的
>   **体积 / 行数 / SHA-256** 写入 `$GITHUB_STEP_SUMMARY`——保留「保留面审计」能力而不公开内容；
>   完整映射的获取路径写进 `NOT-FOR-RELEASE.txt`（本地 / 私有制品库）。新增
>   `CiArtifactVisibilityGuardTest`（4 例）逐个 `upload-artifact` 步骤扫描，防回加。
> - **① 依赖校验 / 版本锁定——登记为已知限界（AC 的「或如实登记」分支）**：生成
>   `gradle/verification-metadata.xml` 需在干净环境以 `--write-verification-metadata` 重写全量
>   依赖图哈希，属独立工程；且它防的是「依赖被替换 / 投毒」，与既有 `dependency-scan.yml`
>   （CVSS ≥ 7.0 硬断言）＋ Dependabot 覆盖的 **advisory 面是不同威胁**，不可互相替代。
> - **② Daemon JVM 分发校验和——登记为已知限界**：`gradle/gradle-daemon-jvm.properties` 由
>   `updateDaemonJvm` 生成，当前内容**仅** `toolchainVersion=21`（无校验和落点）；而
>   **Gradle wrapper 的分发校验和已锁定**（`gradle-wrapper.properties:7`
>   `distributionSha256Sum=acd53f1e…`）⇒ 「分发被替换」这一层已有校验，不为凑 AC 新增无落点的配置。
> - **③ CI 跑设备侧用例——登记为需仓库所有者裁决的成本项**：三份工作流对
>   `connectedAndroidTest` **零命中**（实测）；AC 自身指出 Linux runner 无 KVM 且
>   `AssumptionViolatedException` 会被 AGP 记为 `<failure>`（陷阱 #21），须改 `macos-latest`
>   并**接受计费**——属付费额度决策，**不擅自引入**。
> 见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §87。
>
> **2026-09-16 闭环（§88 批次）**：**`ISSUE-P3-122`**（IPC 面 4 项裁定后的残余整改）已收口——
> **IPC-01** 见 §84（认证 requestCode 单调化）；本批完成 **IPC-10：`onSaveRequest` 超时预算**。
> **缺陷后果比原记更重**：平台对该回调**不提供** `CancellationSignal`，故「无上限」直接等于
> **系统保存 UI 永久等待**（不只是 Availability 降级）——而 `awaitEnforcement()` 在首次扫描未完成时
> 可等待一整个扫描周期。
> **修复**：`onSaveRequest` 经 `withTimeoutOrNull(SAVE_REQUEST_TIMEOUT_MS = 5_000L)` 包裹，
> 超时按「本次无需保存」收尾（`onSuccess`）——给系统明确答复、不落库、不报错，
> 与「用户关闭保存提示」同一收敛语义；服务解绑仍原样重抛 `CancellationException`（不得回调）。
> **一处编译期硬约束（值得留痕）**：处理体**必须**抽为 `private suspend fun handleSaveRequest`——
> `withTimeoutOrNull` **不是** inline 函数，处理体若留在 lambda 内，其中的 `return@launch` 属
> 非局部返回、**无法编译**（本批首版就地包裹即因此编译失败）。抽取后各早退分支的
> `return@launch` 改为普通 `return`，语义逐字不变。
> **回归断言**：新增 `AutofillSaveTimeoutWiringTest`（4 例）钉住「必须受预算约束 / 超时必须回调
> 且留痕 / 处理体必须抽为普通 suspend 函数且不得残留 `return@launch` / 解绑仍不得回调」。
> 见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §88。
>
> **2026-09-16 闭环（§89 批次，两项）**：
> - **`ISSUE-P3-125` ①（选择器零匹配仍无条件挂入）——留痕接受现设计，并把该设计固化为可执行不变量**。
>   复核结论：该「无条件」是**设计**而非疏漏。选择器是**用户显式指认调用方**的唯一入口，
>   也正是 `ISSUE-P1-24`（首现授权）与 `ISSUE-P2-46`（`android://` 首次绑定）的**写入点**；
>   若按「零匹配就不挂」或「零匹配才挂」设门槛，它会**在最需要的时候（零匹配）不可达**——
>   零匹配正是用户唯一需要手动搜索的场景。严格匹配保护的是**自动下发**，那一条**一字未放宽**：
>   候选层仍先过域归属（`ISSUE-P2-07`）与包名维度签名绑定（`ISSUE-P2-46`）；
>   选择器路径在用户显式点选前**不携带任何明文**（数据集值恒为 `null`），点选后由
>   `AutofillPickerActivity` 在受保护窗口内展示调用方归属（`ISSUE-P2-70`）并记录绑定。
>   新增 `AutofillPickerEntryInvariantTest`（4 例）把上述不变量钉住：入口**必须无条件挂入**、
>   文案**不得自称命中**、点选前**不得携带明文**、自动候选**仍须经严格匹配与绑定门控**。
> - **`ISSUE-P3-76`（IME 个性化学习）——按其已定处置归档**：该条已在正文中完成处置与留痕
>   （框架阻塞实测：Compose 1.11.4 无公开 API 可下发 `IME_FLAG_NO_PERSONALIZED_LEARNING`；
>   登记为**已接受残余风险、不实施**，并给出**可复现的解除条件**）。
>   处置已定 ⇒ 按 `ISSUE-P3-79` 的先例归档；解除条件原样收录于 §89 批次文档。
> 见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §89。

---

> **2026-09-13 新增（威胁建模 / 安全整改报告退役批次）**：转登自同日退役删除的
> `THREAT-MODEL-AUDIT-d32f3e7.md` 与 `SECURITY_AUDIT_REMEDIATION.md`
> （处置归档见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §42 / §43）。以下为对拍后仍成立、
> 且**不改变安全承诺**的低危 / 卫生 / 核实缺口项。
>
> **核实时间点与核实方式（2026-09-13，对 HEAD `a669a48`）**：直读源码 + 全仓检索双证据——
> `AppTerminationPolicy.kt` / `KeePasskeyApp.kt`（退出路径）、全仓 `clearPasswordOnLeave` 检索（9 处命中均为
> 持久化 / 投影 / UI 回调）、`DatabaseSession.kt`（`save` / `exportToBytes`）、`KdbxDatabase.kt`、
> `RuntimeIntegrityDetector.kt`、`SyncNetworkOptions.kt` / `SyncProviderResolver.kt`（SSRF 逃生通道）、
> `DigitalAssetLinksVerifier.kt`（HTTP 客户端构造）、`git ls-files gradle/`（无 `verification-metadata.xml`）、
> `.github/workflows/`（无 `connectedAndroidTest`）。

| 编号 | 来源 | 问题与位置（核实于 2026-09-13，对 HEAD `a669a48`） | 验收标准 |
|---|---|---|---|
| ISSUE-P3-120 | 威胁建模 Q-11 | **`RuntimeIntegrityDetector` 拦截力无实测**：检测为"磁盘路径存在性 + `/proc/self/maps` 特征串"启发式（`RuntimeIntegrityDetector.kt:100-149`），非完整性证明；真实 Frida（默认 gadget 名、改名、内存加载）下的命中率未知。若命中率低，则"COMPROMISED 时禁用生物解锁 + 自动填充"的政策在真实攻击下形同虚设，却给用户"已被保护"的错觉 | ① 真机以 Frida 实测三种形态（默认 / 改名 / 内存加载），记录命中率并写入设备侧基线；② 按结果决定加强检测**或**在 `RuntimeIntegrityPolicy` / UI 如实声明"启发式、非证明"；③ 结论与 `ISSUE-P2-63`（快照门控）统一口径 |
| ISSUE-P3-121 | 威胁建模 T-17 | **自建内网 WebDAV / NAS 在出厂配置下不可用**：`SyncNetworkOptions.ssrfAllowedHosts` 默认空集（`SyncNetworkOptions.kt:25`），`SyncProviderResolver` 两处均传默认 `SyncNetworkOptions()`（`:52,77`）→ RFC1918 / `.local` 主机一律被 `SyncEndpointGuard` 拒绝，且**无生产逃生通道**；与 `README` 宣称支持 WebDAV（Nextcloud / ownCloud 等常见家用自建形态）存在张力 | ① 明确产品口径（"支持内网自建"或"明确不支持"）；② 若支持，提供**受控**逃生通道（用户显式声明内网主机 + 风险二次确认，默认仍为拒绝）；③ 文档与实现必须一致，不得只改其一 |

> **2026-09-13 新增（第四轮独立复核定版批次）**：以下 1 项为复核新发现低危项
> （来源 SECURITY_RECHECK §11，附核实行号）。

| 编号 | 来源 | 问题与位置（核实于 2026-09-13） | 验收标准 |
|---|---|---|---|
