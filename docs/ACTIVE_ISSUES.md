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

## P1 高危与核心功能问题（0 项）

> **暂无开放项**（本区归零：§246 闭环 `ISSUE-P1-241`（「移除密码库关联」的确认文案承诺「不会删除物理文件」，而应用私有库的文件**会被真的删除**）——整改＝确认弹窗文案与动作按**存储类型**分列两套、判据落纯函数并单点化、数据层只在「应用私有库」分支删物理文件（产品口径落 `PD-17`）；真机逐字实证「界面声明与文件系统结果一致」（私有库删除后文件确已消失，外部库确认后文件原样在）。证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/246-移除密码库确认文案与真实行为一致批次.md`](resolved/batches/246-移除密码库确认文案与真实行为一致批次.md)。）

---

> **本区近期变动**：§257 闭环 `ISSUE-P1-242`（生物识别快速解锁恒被断言门控拒绝——断言私钥**读路径**每次删钥重建 ⇒
> 新公钥与已登记记录脱钩 ⇒ 每一次快速解锁都 fail-closed；与 `ISSUE-P1-09` 的「`setUserAuthenticationParameters`
> 单独调用不生效」API 陷阱同源，双设备实测判红 / 判绿对照见批次正文）——证据见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/257-生物识别断言密钥形态修复批次.md`](resolved/batches/257-生物识别断言密钥形态修复批次.md)。
> （本条为本次诊断中当场发现、当场闭环，**未在待办区驻留**，故本区计数不变，仍为 0 项。）

> **本区近期变动**：§244 闭环 `ISSUE-P1-238`（release 生产包真机 10 轮冷启动应答实测：
> `Start proc` → `onBeginCreateCredentialRequest` 最小 185 / 最大 247 / 中位 213 ms，
> 10/10 零超时，AC①~⑤ 全部满足；debug 无 odex 的平台属性按 AC⑤ 口径保持登记）——证据见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/244-release生产包冷启动验证与P1-238闭环批次.md`](resolved/batches/244-release生产包冷启动验证与P1-238闭环批次.md)。

> **本区历史上一次归零**：§229 闭环的 `ISSUE-P1-223`（设置页生物识别开关闪退）/
> `ISSUE-P1-224`（外部输入账号密码点击保存未落盘）；实现与验收证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/229-生物识别闪退与自动填充保存及解锁通知倒计时批次.md`](resolved/batches/229-生物识别闪退与自动填充保存及解锁通知倒计时批次.md)。

---

## P2 中危缺陷与协议/测试缺口（0 项）

> **暂无开放项**（本区归零：§254 闭环 `ISSUE-P2-246`——敏感对话框遮罩改由调用点
> `SecureFlagPolicy.SecureOn` 强制（7 处调用点），真机对照实证；详见下方「本区近期变动」引用块）。

---

> **本区历史上一次归零（§247）**：§247 闭环 `ISSUE-P2-239`（凭据提供者通道「系统未登记本应用」的失效完全静默且用户无法自救）——整改＝新增该通道健康检查：三态判定落纯函数（`REGISTERED` / `NOT_REGISTERED` / `UNKNOWN`，**「读不到」不得呈现为「正常」**）、平台查询经**公开 API** `CredentialManager.isEnabledCredentialProviderService` 单点化、设置页健康卡给出用户可见状态与系统设置指引（action 不可解析时如实降级为纯文案）；真机两态实证「未登记」与系统 `TYPE_NO_CREATE_OPTIONS` 同态、已登记则请求被正常路由。新增限界 §27（本机 ROM 缺该设置页 activity ⇒ 一键入口如实降级）。证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/247-凭据提供者通道健康检查批次.md`](resolved/batches/247-凭据提供者通道健康检查批次.md)。）（本区随后补登 §248 复核发现的 3 项；**§249 已闭环其中 2 项**——`ISSUE-P2-243`（子库只读会话装载传入**应用级 `FileBinaryStore`**，超阈值附件真实落盘、投影仍不带附件字段）与 `ISSUE-P2-244`（S3 覆盖前 HEAD 未返回 ETag 时 **fail-closed 上抛**，绝不发无条件 PUT），**§252 闭环余下的 `ISSUE-P2-245`**（本区因此一度归零）、**§252 同轮新登记 `ISSUE-P2-246`**（**§254 已闭环**，见下）——逐条摘要见下方「本区近期变动」引用块。）

---

> **本区近期变动**：§252 闭环 `ISSUE-P2-245`——`app/.../security/SecureDialog.kt`
> 的 `SecureDialogWindowEffect()` 原只给**对话框窗口**施 `FLAG_SECURE`（防截屏），**无**遮挡触摸过滤；
> 而 `filterTouchesWhenObscured` 与 `FLAG_SECURE` 是**同形的窗口级缺口**（都不从 Activity 窗口传播到
> 对话框窗口）。整改＝在同一 `DisposableEffect` 内对**对话框窗口的 `decorView`** 叠加
> `filterTouchesWhenObscured`（save/restore 语义，取不到窗口时不动任何窗口 = fail-safe 不变；
> 既有的 `FLAG_SECURE` 施加/撤销语义与 `SecureDialogFlagPolicy` 的「只撤销自己施加的」不变式**一行未改**），
> 并因 4 类敏感对话框（含主密码字段的 `CreateVaultWizardDialog`）共 6 处调用点全部经该单一入口，
> 一次接线即全覆盖；KDoc 就地落「为何用 `decorView`」「过滤覆盖整棵子树的**实现**依据
> （`ViewGroup.dispatchTouchEvent` 以 `onFilterTouchEventForSecurity` 包住子视图派发）」
> 「为何**不**用 `Window.setHideOverlayWindows`（只抑制**绘制**且不解除**已存在**遮挡）」三项结论，
> `dialogWindowOrNull()` 由 `private` 改 `internal` 供设备侧用例复用同一解析口径。
> 宿主守卫：`ObscuredTouchWiringTest` 新增一条不变式（`SecureDialog.kt` 必须**同时**具备
> `FLAG_SECURE` 的施加+撤销、遮挡触摸过滤的**施加**（仅剩读/还原不算）、与对话框窗口解析；
> 既有两条清单与判据未动）。**真机实证**（Redmi 4X / Android 17 · API 37）：
> `DialogWindowHardeningDeviceTest` 3 条断言全部通过（`skipped=0`）——① 对话框
> `decorView.filterTouchesWhenObscured == true`；② 该窗口 `FLAG_SECURE` 位已置（同时补上
> `SecureDialogFlagPolicyTest` 自陈的「真实 `addFlags` 未覆盖」缺口）；③ 关闭对话框后 `FLAG_SECURE` 已清。
> **如实边界**：该用例证明的是「遮罩与过滤已真实施加到对话框窗口」，**不是**「遮挡窗口的触摸确实被丢弃」
> （后者需真实遮挡窗口，仍为手工冒烟项）。限界 §3.3 的「无遮挡触摸过滤接线」边界已更新为**已接线**
> （原句保留作留痕）。证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/252-对话框窗口遮挡触摸过滤接线批次.md`](resolved/batches/252-对话框窗口遮挡触摸过滤接线批次.md)；
> §245 闭环 `ISSUE-P2-242`（条目列表与详情页标题过长时 Passkey 徽标被挤压变形或消失缺陷：`PasskeyBadge` 锁定单行不软折行、列表与详情页标题 Text 增加 `Modifier.weight(1f, fill = false)` 自适应让位约束，真机实测 46 字符超长标题项与中长项均完整水平横向呈现 `[Passkey]`；新增 `PasskeyBadgeLayoutWiringTest` 锁定布局契约）——证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 [`resolved/batches/245-条目行Passkey徽标防折行与标题自适应让位批次.md`](resolved/batches/245-条目行Passkey徽标防折行与标题自适应让位批次.md)；§243 闭环 `ISSUE-P2-240`（设置页「跳过 DAL 校验」开关的文案按其**真实语义**更正为
> 「跳过通行密钥站点归属校验」，并与 `DigitalAssetLinksVerifier` KDoc / 字段注释 / 告警日志逐字同锚；
> AC② 裁决「**不新增**独立的『跳过浏览器兼容层』偏好项」落
> [`architecture/产品裁决登记.md`](architecture/产品裁决登记.md) `PD-16`）——证据见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/243-设置页DAL降级开关文案更正批次.md`](resolved/batches/243-设置页DAL降级开关文案更正批次.md)。

> **§254 闭环 `ISSUE-P2-246`（**本区由此归零**）**：§252 设备侧实测揭出——`SecureDialog.kt` KDoc 自陈的
> 「无条件施加 `FLAG_SECURE`（对用户开关的有意 fail-closed 偏离）」**不成立**：对话框窗口的该 flag 实际由
> Compose 的 `DialogProperties.securePolicy` 决定，默认 `Inherit` 以**宿主窗口**的 flag 位为准，宿主不带时
> 会 `setFlags(FLAG_SECURE.inv(), FLAG_SECURE)` **清除**本包装刚施加的那一次（真机对照：宿主带 ⇒
> `dialogFlags=0x1802002`；宿主不带 ⇒ `0x1800002`）。**定案＝让实现追上它自己写明的意图**：4 个文件的
> **7 处**调用点全部加 `properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)`
> （Compose 官方 API，强制并**保持**该 flag；`SecureFlagPolicy` 为公开枚举、`DialogProperties.securePolicy`
> 为公开参数，**无需**任何 `@OptIn`）；`SecureDialog.kt` KDoc 的旧自陈改写为如实表述并保留「与用户开关无关」
> 的意图陈述及其现有实现方式；`SecureDialogFlagPolicy` 两条不变式与遮挡触摸过滤逻辑**一行未改**。
> **产品可见语义变化**：这三个对话框此后**无视用户「防截屏」开关而始终遮罩**（依据＝该包装自陈意图），
> 若日后要恢复「尊重开关」须改判并同步 KDoc / 调用点。**守卫**：`SecureDialogFlagPolicyTest` 新增
> 「调用点全部显式要求 SecureOn」静态不变式（按文件计数，独立于既有清单）；**设备侧**把原「锁定缺口」的
> 第 2 例翻转为**锁定修复**（宿主不带 flag 时对话框**仍必须**带该 flag），并新增第 3 例作**同宿主仅改策略
> 参数**的对照（`Inherit` ⇒ 不带，证明该参数承重）。**反向反校三条全中**：①任一调用点去掉 `SecureOn` ⇒
> 守卫红；②设备第 2 例断言改回旧口径 ⇒ 红（实测 `dialogFlags=0x1802002` 含该位）；③设备第 2 例策略改回
> `Inherit` ⇒ 红。真机全量 `:app:connectedDebugAndroidTest` **74 例 / 0 失败 / 0 error / 1 skipped**
> （§252 基线 73 ⇒ +1，即新增的对照例）。证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/254-对话框遮罩改由SecureFlagPolicy强制批次.md`](resolved/batches/254-对话框遮罩改由SecureFlagPolicy强制批次.md)。

> **本区历史上一次归零**：§236 闭环 `ISSUE-P2-231`（Java 依赖面完整性锁定缺失）/
> `ISSUE-P2-232`（完整性风险升级无主动熔断接线）——前者落**重开决策**判「仍不引入」并交付
> 可机检的替代缓解口径（`PD-14`），后者经前置裁决 `PD-13` 判「维持现状」、残余风险登记限界 §26；
> 证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 [`docs/resolved/batches/`](resolved/batches/) 的 §236 批次）。
> 本区上一次归零为 §230 ~ §233 四批闭环 `ISSUE-P2-226` / `P2-227` / `P2-228` / `P2-229`
> ——即用户 2026-09-20 真机报告的四项问题（自身界面仍出现填充建议 / 生物识别被禁用时归因笼统 /
> 自动填充卡三个假开关且文案谎称需要无障碍 / 新建库无位置选择入口）。

---

## P3 低危问题、特性接线与体验优化（2 项）

> **本区现有开放项 2 条**：`ISSUE-P3-251`——限界表 §13 缺「事实」与「边界」两字段，违反该表自己的
> 「事实 → 边界 → 依据 → 解除条件」登记纪律（§256 分批时现场发现，已成条目见下方）；
> `ISSUE-P3-252`——限界表正文 7 处 `[附二](#附二更正留痕按日期) §X` 的**编号留痕不在该文件自己的 `## 附二` 内**
> （正典落在汇录），**锚点悬空且 `check_md_links.py` 判不出**（同批发现，已成条目见下方）。
> 本区上一次归零为 §251（闭环 `ISSUE-P3-246` / `ISSUE-P3-247`——sync 测试侧两处口径收窄（限界 §7 实体口径判据 +
> 明文豁免由整进程收窄到两域），真机 `:sync:` 24 例 / `:app:` 71 例全绿；另 §250 闭环 `ISSUE-P3-249`）。
> 逐条摘要见下方「本区近期变动」引用块。

---

### ISSUE-P3-251 已知工程限界 §13 缺「事实」与「边界」两字段

- **背景**：限界表文首立规「凡**不打算整改**的缺陷 / 残余风险，必须在此登记『事实 → 边界 → 依据 → 解除条件』」。
  但 §13（实时验证码窄通道在会话锁定后的驻留，`ISSUE-P3-182`；两页**各持独立实例**）现存字段只有
  **`解除条件` ＋ `依据`**——**缺「事实」与「边界」**，读者无法从限界表得知「驻留的是什么、多严重」。
  本条属**登记缺口**（不是产品缺陷）：限界表 §13 的结论本身未被推翻。
- **核实时间点与方式**：**2026-09-21**，逐节对拍 `docs/architecture/已知工程限界.md`（§256 批次施工期）
  与批次 `139-验证器页倒计时解耦批次.md` §5.6、`114-列表页秒级重建与TOTP重算收敛批次.md`、
  `120-设备无关项收尾批次.md` 正文（定向 `grep`「liveCodes / 驻留 / SessionLockObserver」，未通读全仓）
  ——§13 确无 `**事实**：` / `**边界（不得据本条外推）**：` 行；批次 114 / 120 正文该面零命中。
- **可逐字取材的来源（已核实存在，整改时直接搬移、**不得**改写）**：
  - `docs/records/限界表证据与实测数据汇录.md` §13 的 `**事实**` 段（§253 由限界表移出的原文，
    含 `TotpCountdownTracker.kt:155-163`、`WhileSubscribed(5000)`、`ISSUE-P2-65`「明文驻留点」口径）；
  - 批次 `139-…md` §5.6「会话锁定后实时码的驻留（新登记）」整段。**注意**：该段的「自 §114 起由**列表页**持有
    （本批只是让验证器页复用同一对象）」已被 `248-限界表独立复核更正与新缺口补登批次.md` 表 `B18`
    更正为「两页**各持独立实例**」，搬移时须用更正后口径（限界 §13 标题即此形态）。
- **未就地补齐的原因（如实声明，非「未做」）**：`**边界**` 一段在三份批次正文中**没有可直接逐字搬移的对应句**
  ——自行撰写即等于编造「不得据此外推什么」这一硬约束。故 §256 **未**为 §13 补字段，改为登记本条。
- **整改方向**：① `**事实**`：以汇录 §13 的 `事实` 段逐字补入；② `**边界**`：先定「本条不得被外推为什么」
  （候选：不得读作「锁库后内存中已无任何验证码」；不得据此要求在本通道之外另建擦除路径），随后逐字取自
  批次正文，或经裁决后另写入并留痕。
- **验收标准**：§13 同时具备 `**事实**：` 与 `**边界（不得据本条外推）**：` 两行，且与取材处**逐字一致**
  （可用 `python tools/doc/check_verbatim_move.py` 复核）；`check_md_links.py` 与
  `check_resolved_index_sync.py` 不因该改动变红。
- **依据**：`docs/architecture/已知工程限界.md`（文首「登记纪律」、§13）；
  `docs/resolved/batches/139-验证器页倒计时解耦批次.md` §5.6；
  `docs/records/限界表证据与实测数据汇录.md` §13；
  `docs/resolved/batches/248-限界表独立复核更正与新缺口补登批次.md`（表 `B18`）；
  `docs/resolved/batches/256-限界分类标签与口径裁决迁出批次.md`（发现现场与未补字段的理由）。

---

### ISSUE-P3-252 已知工程限界正文的「附二 §X」交叉引用在自身文件内无落点

- **背景**：`ISSUE-P3-81` 立规「凡记录**已确认缺陷 / 残余风险 / 验证结论**的文档必须登记到文档地图，
  且登记的链接**必须可跳转**」。但限界表正文的「沿革型更正」引用指向的是**本文件内的锚点**，
  而该锚点段内**没有**它们引用的编号条目——该缺口因此长期隐形。
- **事实（2026-09-21 现查）**：`docs/architecture/已知工程限界.md` 正文共 **7 处**
  `[附二](#附二更正留痕按日期) §X` 形引用——第 11 行（无编号）、§2.4、§5、§8、§13、§19、§24；
  而该文件自己的 `## 附二：更正留痕（按日期）` **只有 3 个无编号块**（§1.1 与 §1.3 的边界补充），
  **不含任何编号条目**。这些 `§X` 编号留痕实际位于
  `docs/records/限界表证据与实测数据汇录.md` 的 `### ## 附二：更正留痕（按日期）` 段内
  （该标题层级畸形：`###` 与 `##` 并存）。**另**：§256 迁出的 `PD-21` / `PD-23` / `PD-24`
  各继承 1 处同款锚点，在 `产品裁决登记.md` 内同样**无落点**（均在 `依据` 块末行）。
- **核实时间点与方式**：**2026-09-21** 现查——`grep -n "附二" docs/architecture/已知工程限界.md
  docs/architecture/产品裁决登记.md docs/records/限界表证据与实测数据汇录.md`，
  并**逐段读三处 `附二` 正文**核对编号条目是否在段落内（限界表 3 个无编号块 / 汇录 21 条编号留痕 /
  `产品裁决登记.md` 零 `## 附二` 标题）。
- **影响（为何机检抓不到）**：`python tools/doc/check_md_links.py` 只判「**文件与锚点可达**」，
  **判不出**「该锚点段内没有 `§X` 这一层」⇒ 该缺口不会被任何现有闸门报红。
- **处置方向（供后续批次择一，**§256 未实施**）**：① `附二` 正典归汇录——限界表 `附二` 降为
  「一行指针 + 3 个块去向」，7 处引用改指汇录锚点，并同时修掉汇录那边 `### ##` 的标题层级畸形；
  ② 或把编号留痕回迁限界表 `附二`，汇录侧改为指针。
  **本批一律不改链接语义**（正典归属属独立一次处置，不在 §256 面内）。
- **验收标准**：三份文件内的「附二 §X」引用**逐处**可跳转到承载该编号条目的正文；
  且同一结论**只在一处**为正典（另一处为一行指针）；`check_md_links.py` 与
  `python tools/doc/check_resolved_index_sync.py` 均不因该改动变红。
- **依据**：`docs/architecture/已知工程限界.md`（第 11 行、§2.4 / §5 / §8 / §13 / §19 / §24 的 `依据` 行、
  文末 `## 附二：更正留痕（按日期）`）；`docs/records/限界表证据与实测数据汇录.md`（`### ## 附二` 段）；
  `docs/architecture/产品裁决登记.md`（`PD-21` / `PD-23` / `PD-24` 的 `依据` 块）；
  `docs/resolved/batches/256-限界分类标签与口径裁决迁出批次.md` §5A.2 / §7.8（发现现场与未改链路语义的理由）。

---

> **本区历史上一次归零（§238）**：§238 闭环 `ISSUE-P3-235`（`RC-02` 敏感缓冲所有权收口）——
> AC① 设计交付 [`architecture/敏感缓冲所有权契约.md`](architecture/敏感缓冲所有权契约.md)；
> AC② 逐处迁移：`G1`（§235）/ `G2`（§238）闭环、`G3`（池内擦除）维持已登记限界 §1.6、
> `G4`（命名统一）降为「按需」、`G5` / Step 1 已核实；`test` 343 类 / 2402 例全绿。
> 证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`docs/resolved/batches/238-待决冲突解析树身份判定擦除批次.md`](resolved/batches/238-待决冲突解析树身份判定擦除批次.md)）。（本区随后补登 §248 复核发现的 5 项；**§249 已闭环其中 2 项**——`ISSUE-P3-248`（`DatabasePickerViewModel` 的弱因子提示 KDoc 更正为**如实表述**，由 `WeakKdfNoticeHonestyGuardTest` 钉死口径）与 `ISSUE-P3-250`（限界 §18 **收窄授权**为「不得新增含实现体成员」+ 5 处薄编排待消化清单、§20 按 65 行现值**重新裁定**为接受为限界）；**§250 随之闭环 `ISSUE-P3-249`**、**§251 闭环余下 2 项（`ISSUE-P3-246` / `ISSUE-P3-247`）**——逐条摘要见下方「本区近期变动」引用块。）

> **本区近期变动**：§251 闭环 `ISSUE-P3-246` / `ISSUE-P3-247`（**本区当时由此归零**，§256 补登 1 项）——
> `ISSUE-P3-246`：`SyncCacheAndroidRuntimeTest` 的「目录已清空」断言由**原始目录枚举返回值**（`dir.listFiles()`）
> 改为限界 §7 的**实体口径**（`walkTopDown().filter { it.isFile }` 对 `emptyList<File>()`，与 `SyncCacheTest` /
> `app` 侧 `SyncCacheEvictorTest` 同款写法），「真残留必红」的判别力未放宽，并把依据与 §7 边界 3 自陈的
> 约 3% 假阳性残余写进用例 KDoc；
> `ISSUE-P3-247`：sync 测试 APK 的明文放行由**整进程** `android:usesCleartextTraffic="true"` 收窄为该测试 APK
> **专用**的 network security config（`base-config` 显式禁明文 + 仅系统 CA 信任锚），唯一的 `domain-config`
> **只放行设备侧用例实际使用的两个主机名** `localhost` 与 `127.0.0.1`（逐份勘察该源集 6 个用例，
> 未出现「假主机名 + 回环 `Dns`」形态 ⇒ 两域**恰好完备**）；生产全站禁令
> （`app/src/main/res/xml/network_security_config.xml`）**一行未放宽**。
> **真机实证**（Redmi 4X / Android 17 · API 37，2026-09-21）：`:sync:connectedDebugAndroidTest`
> **24 例 / 0 失败 / 0 error / 0 skipped**、`:app:connectedDebugAndroidTest`
> **71 例 / 0 失败 / 0 error / 1 skipped**（该 skipped 为 `CredentialSaveChainDeviceTest` 的环境前提 `Assume`，
> 非本批所致）；`CleartextPolicyDeviceTest`（4 例）全绿 ⇒ 生产明文禁令未被测试侧改动影响。三处工程留痕
> （安装器会话残留 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / UiAutomation 槽位被占按限界 §4.1 约束 1 既有口径
> `am force-stop` 规避 / `:core:` 一度卡 Windows 文件锁与限界 §12 同类）均系**已登记约束的现场复现或宿主现象**，
> 不是新缺陷。证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/251-设备侧两处测试口径收窄批次.md`](resolved/batches/251-设备侧两处测试口径收窄批次.md)；
> §250 闭环 `ISSUE-P3-249`（复核报告四行与代码现况不一致——`P2-65` 判定依据「全仓无锁态驱动的 UI 导航」更正为「锁态驱动导航**确实存在**」（`KeePasskeyApp.kt:287-295`），`P2-73` AC② 改 `FLAG_MUTABLE` 的「不可照做」前提已随 §110 / §111 失效、CM 通道 `UNBOUND` 越权面**已登记**限界 §6、`SUPPLY-06` 的 IP 字面量面**已由 §236 直读定案**（限界 §25）；四处一律**保留原文 + 就地加更正注**）——证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/250-复核报告四行与代码现况对齐批次.md`](resolved/batches/250-复核报告四行与代码现况对齐批次.md)；
> §236 闭环 `ISSUE-P3-233`（复核报告 `P3-120` 状态陈旧——更正报告
> §10.1 / §3.3.2 / §2.3 / §2.4 / §6.7 / §9.2 并增补 §15.3 方法学第 12 条）与 `ISSUE-P3-234`
> （DAL 出口 IP 字面量面定案，登记 [`已知工程限界.md`](architecture/已知工程限界.md) §25）；
> §237 闭环 `ISSUE-P3-230`（已有 SAF 库授权失败不再静默——提示 + 列表状态 + 重授入口，
> 限界 §24 的残余段同步收口）。

> **历史 P3 条目**（含 §221 闭环的 `ISSUE-P3-201` / `202` / `203` / `204` / `205` / `206` /
> `209`，§222 闭环的 `ISSUE-P3-212` / `213` / `214`，§224 闭环的 `ISSUE-P3-215`）的实现与验收证据见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 [`docs/resolved/batches/`](resolved/batches/)。
