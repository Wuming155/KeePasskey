# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。
> **只放现存问题**：本文件**只保留尚未闭环的条目**——已闭环条目的正文、批次流水与索引段一律不留；历史实现与验收证据以 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 `docs/resolved/batches/` 为单一真相源。
> **实施前必读**：各条目的 AC 已按 [`SECURITY_RECHECK_2026-09.md`](security/SECURITY_RECHECK_2026-09.md) §10 / §11 的**第四轮独立复核定版结论**逐条对齐，更正口径已内联到对应条目——照条目原文 AC 实施者可能**降低安全性**或**引入数据损坏**。
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

> **暂无开放项**（本区归零：§246 闭环 `ISSUE-P1-241`（「移除密码库关联」的确认文案承诺「不会删除物理文件」，而应用私有库的文件**会被真的删除**）——整改＝确认弹窗文案与动作按**存储类型**分列两套、判据落纯函数并单点化、数据层只在「应用私有库」分支删物理文件（产品口径落 `PD-17`）；真机逐字实证「界面声明与文件系统结果一致」（私有库删除后文件确已消失，外部库确认后文件原样在）。证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/246-移除密码库确认文案与真实行为一致批次.md`](resolved/batches/246-移除密码库确认文案与真实行为一致批次.md)。）

---

## P2 中危缺陷与协议/测试缺口（2 项）

### `ISSUE-P2-254`：通行密钥交互链路三项端到端验证缺口（github.com 手工冒烟未执行 / PRF 未对真实 RP 对拍 / CM 选择器 UI 未覆盖）

- **核实时间点**：2026-09-22（文档审核批次 §260）。
- **核实方式**：直读 `records/通行密钥互操作对拍记录_2026-09-19.md` §6（标题自陈「本次未执行」）、`architecture/实现约定与验证现状.md` §4.3 边界②③（「2026-09-19 尚未执行」「PRF 仍未与真实 RP 对拍」）、`docs/resolved/batches/220-通行密钥互操作对拍与Ed25519-PKCS8形态整改批次.md` 结案「如实声明」段——三处一致，且 `RESOLVED_LOG.md` 自 §220 起无任何批次执行过该清单；`ACTIVE_ISSUES` 检索无对应条目 ⇒ 缺口自 2026-09-19 起**无跟踪**。
- **背景与影响**：`ISSUE-P2-210` 以**文件级 / 离库**证据结案并如实留下三项未覆盖面，但「留下边界声明」≠「有人跟踪执行」——checklist 沉在 records 里无限期搁置。缺口不补，「通行密钥互操作」的对外表述只能停在限界 §4.3 / 实现约定 §4.3 的收窄口径。
- **验收标准**：
  - AC① 按 `records/通行密钥互操作对拍记录_2026-09-19.md` §6 清单完成 github.com 端到端真机贯通（8 步逐项「通过 / 失败 + 截图或日志」留痕，回写该记录或新增批次正文）；
  - AC② 完成至少一次 **PRF 与真实 RP** 的对拍；若确客观无可用 RP，须以限界 / 产品裁决登记「不可达」边界并经确认——**不得静默留白**；
  - AC③ 系统 CM 选择器 UI 全链路 + 通知渲染核对留痕（可与 AC① 同轮）；
  - AC④ 三项完成后同步收口 `architecture/实现约定与验证现状.md` §4.3 与限界 §4.3 存根的对应边界句（原句保留作留痕）并归档本条。任一项若裁决「不做」，必须以限界 / 产品裁决登记承接。
- **依据**：`docs/records/通行密钥互操作对拍记录_2026-09-19.md` §6；`docs/architecture/实现约定与验证现状.md` §4.3；`docs/resolved/batches/220-通行密钥互操作对拍与Ed25519-PKCS8形态整改批次.md`（结案如实声明）；同类「逐条收口」先例 `docs/resolved/batches/242-凭据链路未验证项逐条收口批次.md`。

### `ISSUE-P2-266`：新建库写侧声明 KDBX 4.0（`0x00040000`）却写出 6 项 4.1 专有 XML 字段（SettingsChanged / MasterKeyChangeForceOnce / CustomData Item LastModificationTime / CustomIcon Name·LastModificationTime / Entry QualityCheck）

- **核实时间点**：2026-09-22（对照 `keepass.info/help/kb/kdbx.html` 的格式层核对走查）。
- **核实方式**：直读 HEAD——① `core/src/main/java/com/keepasskey/core/model/KdbxConstants.kt:32` 仅 `VERSION_4_0 = 0x00040000`，全仓无 `0x00040001` 版本常量（`ISSUE-P3-126③` 曾显式删除死常量 `VERSION_4_1`）；② 新建库路径 `database/src/main/java/com/keepasskey/database/session/SessionOpener.kt:69` → `KdbxHeader.createDefault()`，而 `KdbxHeader.kt:48` 默认 `version = VERSION_4_0`；③ 写出侧 6 处 4.1 字段逐一确认：`KdbxXmlMetaSerializer.kt:30-32`（`SettingsChanged`）、`:63-65`（`MasterKeyChangeForceOnce`）、`:77-89`（CustomIcon `Name` / `LastModificationTime`）、`:128-131`（CustomData Item `LastModificationTime`）、`KdbxXmlEntrySerializer.kt:46-47`（Entry `QualityCheck`），且各处 KDoc 均自陈「KDBX 4.1 追加字段」；④ 边界澄清：已存在的 4.1 库往返**保留**原版本——`KdbxHeader.deserialize` 经 `toHeader(sig1, sig2, version)`（`KdbxHeader.kt:409-412`）原样传入读到的 version，`KdbxFile.save` 的 `database.header.copy(...)`（`KdbxFile.kt:373`）不重置 version ⇒ 缺陷面仅限**新建库**；⑤ `SettingsUiState.kt:237` 注释「写侧恒为 4.0」表述不精确（只对新建场景成立），随本条一并修正。
- **背景与影响**：规格明文「For KDBX 4.1, the format version value is `0x00040001`」。新建库声明 4.0 却夹带 4.1 元素：严格客户端可能丢弃未知元素或告警（规格仅对 minor **更大**的文件建议「可忽略未知项」，对本仓这种「声明小、夹带大」无宽容承诺）；经 4.0 严格客户端往返时，4.1 元数据可能被静默丢掉。读取侧「仅校验 major」策略（`KdbxHeader.validateVersion`）不受影响、不动。
- **正确的方式（整改方向）**：凡写出任一 4.1 XML 字段，format version 写 `0x00040001`——推荐**新建库直接恒写 4.1**（官方 KeePass 2.53+ / KeePassXC 新写文件即 4.1，既有读取路径对 4.0 / 4.1 完全一致，见 `ISSUE-P3-126③` 声明）；或条件化「写了 4.1 字段才升版本」。读取侧继续只校验 major。
- **验收标准**：
  - AC① 写出侧不再存在「声明 4.0 且含 4.1 字段」的文件：新建库保存后外层 header version 字段为 `0x00040001`（或写出内容不含任何 4.1 专属元素——二选一，须留证）；
  - AC② 官方语料与互操作对拍回归全绿：`OwnProductInteropProbeTest` + `python tools/kdbx-corpus/generate_corpus.py --check`；
  - AC③ `SettingsUiState.kt` 相关注释口径随裁决同步修正；
  - AC④ 若裁决「维持 4.0 版本号不动」，须在 [`architecture/产品裁决登记.md`](architecture/产品裁决登记.md) 或 [`architecture/已知工程限界.md`](architecture/已知工程限界.md) 登记取舍与重开条件，**不得静默留白**。
- **依据**：`keepass.info/help/kb/kdbx.html`（KDBX 4.1 版本值与追加元素清单）；`core/src/main/java/com/keepasskey/core/model/KdbxConstants.kt:32`；`database/src/main/java/com/keepasskey/database/file/KdbxHeader.kt:48, 277-282, 409-412`；`database/src/main/java/com/keepasskey/database/file/KdbxFile.kt:373`；`database/src/main/java/com/keepasskey/database/xml/KdbxXmlMetaSerializer.kt:29-32, 62-65, 76-89, 128-131`；`database/src/main/java/com/keepasskey/database/xml/KdbxXmlEntrySerializer.kt:46-47`；`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiState.kt:235-240`。

---

> **本区最近一次归零记录**（2026-09-22 §260 补登 `ISSUE-P2-254` 前）：§254 闭环 `ISSUE-P2-246`——敏感对话框遮罩改由调用点
> `SecureFlagPolicy.SecureOn` 强制（7 处调用点），真机对照实证；§259 闭环 `ISSUE-P2-253`——
> 关闭生物识别开关只落偏好、不删封印凭据与断言登记，现关闭即撤销全部生物识别数据（关闭 = 删除）。
> 证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/259-关闭生物识别开关撤销封印数据批次.md`](resolved/batches/259-关闭生物识别开关撤销封印数据批次.md)。

---

## P3 低危问题、特性接线与体验优化（0 项）

> **暂无开放项**（本区归零：§267 闭环 `ISSUE-P3-268`，P3 1 → **0 项**——HMAC 块流读取上限 1 MiB
> 经用户裁决**移除**：三家参考实现（官方 KeePass 2.61.1 / keepass2android / KeePassXC）读侧均仅拒绝
> 负数块长、均无设置项，本仓对齐之；读侧只查负数 + 块数据 EOF 路径补类型化包裹 + 工具层 16 MiB
> 分配护栏（P0-5）兜底；新增 4 例回归用例（>1 MiB 接受双路径 / 负数拒绝 / 截断 fail-closed）；
> 裁决登记 `PD-31`。证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/267-HMAC块流读上限移除批次.md`](resolved/batches/267-HMAC块流读上限移除批次.md)。）

---

> **本区最近一次归零记录**（2026-09-23 §267 闭环 `ISSUE-P3-268`，P3 1 → **0 项**）：HMAC 块流读侧
> 上限 1 MiB **整体移除**（用户裁决「别家都没有上限，咱们也不要设置上限，只检查负数」）——
> 实证三家参考实现（官方 KeePass 2.61.1 / keepass2android / KeePassXC）读侧均仅拒绝负数块长、
> 均无设置项，旧上限会把第三方写出的「块长 > 1 MiB」合法文件误判为损坏；读侧两处只查负数，
> 块数据读取的 EOF 路径补类型化包裹（`KdbxCorruptFileException`），恶意分配由工具层 16 MiB
> 护栏（P0-5）兜底、该护栏一行未动；旧「2 MiB 必拒」回归用例按测试资产纪律改写 + 新增 3 例
> （>1 MiB 接受双路径 / 负数拒绝 / 截断 fail-closed）；裁决登记 `PD-31`。
> 证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/267-HMAC块流读上限移除批次.md`](resolved/batches/267-HMAC块流读上限移除批次.md)。
>
> **前次归零记录**（2026-09-23 §266 闭环 `ISSUE-P3-263`，P3 2 → **1 项**）：动态取色与主题调色盘收敛为
> 「配色来源」**互斥单选**（用户裁决候选 A，登记 `PD-30`）——判据单点化（`resolveColorSource` 纯函数，
> 设置页与 `KeePasskeyTheme` 共用）、动态取色生效期间调色盘 5 项整节置灰不可点且不呈现任何「已选中」
> （附行内原因说明 + 「改用主题调色盘」一步切回）、`setThemePalette` 单 DataStore 事务幂等互斥写
> `dynamic_color_enabled = false`；新增 3 类 11 例测试（真值表 / 互斥行为 / 接线守卫）。
> 证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/266-动态取色与调色盘互斥单选批次.md`](resolved/batches/266-动态取色与调色盘互斥单选批次.md)。
>
> **前次归零记录**（2026-09-23 §265 闭环 `ISSUE-P3-264`，P3 3 → **2 项**）：保存黑名单弹窗
> 「关闭」去状态化——评估揭出条目前提有误：confirm 槽 `else onDismiss` **缺调用括号**（函数引用求值即丢弃），
> 默认态唯一的「关闭」实为**死按钮**（用户所报「点了不关」的静态根因），按条目自身升级条件按 P2 严格度整改；
> 整改＝「关闭」无条件落 `dismissButton` 槽、confirm 槽仅手工态承载「新增」、`onToggleManual` 改真双态切换
> （新文案 `autofill_blacklist_manual_back` zh/en）＋ 新增 `PackageBlocklistDialogWiringTest` 4 例接线守卫
> （§205 历史坏形态代入谓词须报红的区分力证明）。证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/265-保存黑名单弹窗关闭去状态化与接线守卫批次.md`](resolved/batches/265-保存黑名单弹窗关闭去状态化与接线守卫批次.md)。
>
> **前次归零记录**（2026-09-22 §264 同批闭环三条，P3 6 → **3 项**）：`ISSUE-P3-267`——AES-KDF 的 `S`
> 解析期 32 字节定长校验（恰 16 字节曾静默按 AES-128 派生；新增 `AesKdfSeedBoundsTest` 5 例边界用例，走真实字节流）；
> `ISSUE-P3-265`——CI 签名口令改 job 运行时随机生成（固定字面量 `keepasskey-ci-ephemeral` 移除，
> `openssl rand -base64 24` 一次生成同供 keytool 与 Gradle）；`ISSUE-P3-269`——`InnerHeader` KDoc
> 「GZip 解压前」笔误更正（连同 references 架构分析载荷层次图的同形错误一并收口）。
> 证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/264-AES-KDF种子定长与CI口令卫生及KDoc笔误批次.md`](resolved/batches/264-AES-KDF种子定长与CI口令卫生及KDoc笔误批次.md)。
>
> **前次归零记录**（2026-09-22 §263 补登 `ISSUE-P3-263`、其后补登 `ISSUE-P3-264` 前）：§262 闭环 `ISSUE-P3-261`，条目正文原样收录批次 §1——全局转场动效
> 由「自研 tween 一套 + 主题 Expressive 一套」收敛为**双栏定标**——空间段（位移 / 缩放）走
> `MaterialTheme.motionScheme` 的 spring（与底栏指示器同族）、效果段（透明度）走官方 shared axis 的
> **顺序淡化时间轴**（出向 90ms / 入向 210ms 延迟 90ms，35% 阈值以两条不变式锁定）；
> 同级 Tab 补齐真正顺序的 Fade Through 并把 `Screen.Settings` 纳入同族、层级下钻的前进与返回
> 改为逐项镜像（视差比例落具名 token）、预测性返回启用两个专属槽位并按全屏表面规格补齐
> 缩放与插值器 `(.1,.1,0,1)`、内容层 6 处改读 MotionScheme 且列表项加 `Modifier.animateItem`。
> 四条「有意偏离」逐条登记 `PD-26` ~ `PD-29`（含**不做**共享元素过渡的评估结论）。
> 同批闭环 `ISSUE-P2-260`——底栏顶层 Tab 切换的 `popUpTo` 目标改取**栈上当前路由**，
> 使 `saveState` / `restoreState` 真正生效、返回栈不再无界增长。
>
> **前次归零记录**（2026-09-22 §261 四条同批闭环，条目正文原样收录批次 §1）：`ISSUE-P3-255`——`ISSUE-P3-221` 自陈的三项
> 真机核验（拒绝页按钮状态流 / 授权后回浏览器放行 / `STATUS_FAILED` 文案）已执行：①② 真机核验通过（Firefox 149 +
> passkeys.io，逐字证据见批次），③ 判定稳态真机不可达（资格守卫与签名读取互斥的 TOCTOU 论证）并按限界 §22 以
> 「发版前人工核对承担」显式承接；`ISSUE-P3-256`——`PD-05` 待裁决归位并按用户裁定**候选 C** 落地（唯一强匹配 + 已绑定
> 改链确认页，路由判据落纯函数 `AutofillUnlockRouter`，未绑定 fail-closed 回选择器，解锁页不自建 Dataset）；`ISSUE-P3-257`——
> `SettingsViewModel` 4 处薄编排全部下沉控制器、ViewModel 收单语句委托，`PD-23` 待消化清单清空；`ISSUE-P3-258`——
> 池内擦除准入①穷举 9 类持有者全部满足，Step 4 按身份集合判定实施（新原语 `clearBinaryPool` + 四处收口点 + 反校 4 组），
> 限界 §1.6 标记已解除、契约 Step 0~4 完结。证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §261 与
> [`resolved/batches/261-P3低危四项闭环批次.md`](resolved/batches/261-P3低危四项闭环批次.md)。
>
> **前次归零记录**（2026-09-22 §260 补登四条前）：§258 同批闭环 `ISSUE-P3-251` 与 `ISSUE-P3-252`——前者＝限界表 §13 缺
> 「事实」「边界」两字段、违反该表自己的「事实 → 边界 → 依据 → 解除条件」登记纪律，整改＝事实段自汇录
> **逐字搬移**回补 ＋ 边界段按条目两候选**裁决新写并留痕**（批次正文即留痕）；后者＝限界表等文件正文
> `[附二](#附二更正留痕按日期) §X` 形编号留痕的锚点在自身文件内**无落点**（正典实居汇录附二 21 条，
> 而 `check_md_links.py` 对含锚链接不匹配、判不出），整改＝按条目**处置方向 ①**「附二正典归汇录」——
> 汇录附二标题修 `### ##` 畸形、收纳判据与 `ISSUE-P2-243` / `P2-244` 两留痕块**逐字迁入**、
> **5 文件 14 处**引用（条目所称 3 文件 10 处 ＋ 现场扩展 `实现约定与验证现状.md` 3 处 / `宿主开发环境现象.md` 1 处）
> 改指汇录锚点、限界表附二降为「一行指针 + 3 个块去向」。证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/258-限界表13字段补齐与附二正典归汇录批次.md`](resolved/batches/258-限界表13字段补齐与附二正典归汇录批次.md)。
