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

> **本区近期变动**：§244 闭环 `ISSUE-P1-238`（release 生产包真机 10 轮冷启动应答实测：
> `Start proc` → `onBeginCreateCredentialRequest` 最小 185 / 最大 247 / 中位 213 ms，
> 10/10 零超时，AC①~⑤ 全部满足；debug 无 odex 的平台属性按 AC⑤ 口径保持登记）——证据见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/244-release生产包冷启动验证与P1-238闭环批次.md`](resolved/batches/244-release生产包冷启动验证与P1-238闭环批次.md)。

> **本区历史上一次归零**：§229 闭环的 `ISSUE-P1-223`（设置页生物识别开关闪退）/
> `ISSUE-P1-224`（外部输入账号密码点击保存未落盘）；实现与验收证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/229-生物识别闪退与自动填充保存及解锁通知倒计时批次.md`](resolved/batches/229-生物识别闪退与自动填充保存及解锁通知倒计时批次.md)。

---

## P2 中危缺陷与协议/测试缺口（1 项）

> **本区上一次归零**：§247 闭环 `ISSUE-P2-239`（凭据提供者通道「系统未登记本应用」的失效完全静默且用户无法自救）——整改＝新增该通道健康检查：三态判定落纯函数（`REGISTERED` / `NOT_REGISTERED` / `UNKNOWN`，**「读不到」不得呈现为「正常」**）、平台查询经**公开 API** `CredentialManager.isEnabledCredentialProviderService` 单点化、设置页健康卡给出用户可见状态与系统设置指引（action 不可解析时如实降级为纯文案）；真机两态实证「未登记」与系统 `TYPE_NO_CREATE_OPTIONS` 同态、已登记则请求被正常路由。新增限界 §27（本机 ROM 缺该设置页 activity ⇒ 一键入口如实降级）。证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/247-凭据提供者通道健康检查批次.md`](resolved/batches/247-凭据提供者通道健康检查批次.md)。）（本区随后补登 §248 复核发现的 3 项；**§249 已闭环其中 2 项**——`ISSUE-P2-243`（子库只读会话装载传入**应用级 `FileBinaryStore`**，超阈值附件真实落盘、投影仍不带附件字段）与 `ISSUE-P2-244`（S3 覆盖前 HEAD 未返回 ETag 时 **fail-closed 上抛**，绝不发无条件 PUT），余 1 项见下）

### `ISSUE-P2-245`：Compose 对话框窗口未接遮挡触摸过滤（限界 §3.3「无盲区」不成立，含主密码输入面）

- **核实时间点**：2026-09-21（同上轮复核）。
- **核实方式**：全仓 `grep -rn "ApplyObscuredTouchFilter"` 调用点清单 ∩ `setFilterTouchesWhenObscured` 接线面，
  与对话框文件比对：`app/src/main/java/com/keepasskey/app/ui/screens/database/CreateVaultWizardDialog.kt:184`
  （新建库向导，**含主密码字段**）、`.../settings/MasterKeyChangeDialog.kt:75`、
  `.../settings/subscreens/ChildDatabaseDialogs.kt:85/176`、`.../detail/EntryDetailPreviewDiffComponents.kt:81/253`
  只经 `SecureDialogWindowEffect` 施 `FLAG_SECURE`（`app/.../security/SecureDialog.kt:98-118`），
  **无**遮挡触摸过滤接线；Compose `Dialog` 是独立窗口，不继承 Activity `decorView` 的过滤。
- **背景与影响**：限界 §3.3 标题为「接线面（**无盲区**）」。上述窗口承载主密码 / 密钥文件等高价值输入，
  若上层存在遮挡窗口（tapjacking）而无过滤，属既有加固面上的空档。**可利用性未在真机验证**，本条先以
  「如实登记 + 补接线或改判标题」处置。
- **验收标准**：
  - AC① 直读当前 Compose 版本（BOM 钉 `ui 1.12.0`）的 `Dialog` 窗口 API，判定能否对 dialog window
    施加 `setFilterTouchesWhenObscured`（或等价加固），**结论就地落 KDoc**。
  - AC② 按结论接线；或改判限界 §3.3 标题与「无盲区」结论（二者取一，不得两处并存）。
  - AC③ 宿主用例锁定接线存在性（对齐 `ObscuredTouchWiringTest` 的静态守卫口径）；涉及窗口行为的改动
    需真机冒烟 tapjacking 场景。
  - AC④ 不得移除既有任何一处的过滤接线。
- **依据**：`SecureDialog.kt` / `security/SecureTouchCompose.kt` 与四个对话框文件直读；
  `app/src/test/java/com/keepasskey/app/security/ObscuredTouchWiringTest.kt`；
  `docs/architecture/已知工程限界.md` §3.3。

---

> **本区近期变动**：§245 闭环 `ISSUE-P2-242`（条目列表与详情页标题过长时 Passkey 徽标被挤压变形或消失缺陷：`PasskeyBadge` 锁定单行不软折行、列表与详情页标题 Text 增加 `Modifier.weight(1f, fill = false)` 自适应让位约束，真机实测 46 字符超长标题项与中长项均完整水平横向呈现 `[Passkey]`；新增 `PasskeyBadgeLayoutWiringTest` 锁定布局契约）——证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 [`resolved/batches/245-条目行Passkey徽标防折行与标题自适应让位批次.md`](resolved/batches/245-条目行Passkey徽标防折行与标题自适应让位批次.md)；§243 闭环 `ISSUE-P2-240`（设置页「跳过 DAL 校验」开关的文案按其**真实语义**更正为
> 「跳过通行密钥站点归属校验」，并与 `DigitalAssetLinksVerifier` KDoc / 字段注释 / 告警日志逐字同锚；
> AC② 裁决「**不新增**独立的『跳过浏览器兼容层』偏好项」落
> [`architecture/产品裁决登记.md`](architecture/产品裁决登记.md) `PD-16`）——证据见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/243-设置页DAL降级开关文案更正批次.md`](resolved/batches/243-设置页DAL降级开关文案更正批次.md)。

> **本区历史上一次归零**：§236 闭环 `ISSUE-P2-231`（Java 依赖面完整性锁定缺失）/
> `ISSUE-P2-232`（完整性风险升级无主动熔断接线）——前者落**重开决策**判「仍不引入」并交付
> 可机检的替代缓解口径（`PD-14`），后者经前置裁决 `PD-13` 判「维持现状」、残余风险登记限界 §26；
> 证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 [`docs/resolved/batches/`](resolved/batches/) 的 §236 批次）。
> 本区上一次归零为 §230 ~ §233 四批闭环 `ISSUE-P2-226` / `P2-227` / `P2-228` / `P2-229`
> ——即用户 2026-09-20 真机报告的四项问题（自身界面仍出现填充建议 / 生物识别被禁用时归因笼统 /
> 自动填充卡三个假开关且文案谎称需要无障碍 / 新建库无位置选择入口）。

---

## P3 低危问题、特性接线与体验优化（0 项）

> **暂无开放项**（本区归零：§251 闭环 `ISSUE-P3-246` / `ISSUE-P3-247`——
> sync 测试侧两处口径收窄（限界 §7 实体口径判据 + 明文豁免由整进程收窄到两域），
> 真机 `:sync:` 24 例 / `:app:` 71 例全绿；另 §250 闭环 `ISSUE-P3-249`。
> 逐条摘要见下方「本区近期变动」引用块）。

---

> **本区历史上一次归零（§238）**：§238 闭环 `ISSUE-P3-235`（`RC-02` 敏感缓冲所有权收口）——
> AC① 设计交付 [`architecture/敏感缓冲所有权契约.md`](architecture/敏感缓冲所有权契约.md)；
> AC② 逐处迁移：`G1`（§235）/ `G2`（§238）闭环、`G3`（池内擦除）维持已登记限界 §1.6、
> `G4`（命名统一）降为「按需」、`G5` / Step 1 已核实；`test` 343 类 / 2402 例全绿。
> 证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`docs/resolved/batches/238-待决冲突解析树身份判定擦除批次.md`](resolved/batches/238-待决冲突解析树身份判定擦除批次.md)）。（本区随后补登 §248 复核发现的 5 项；**§249 已闭环其中 2 项**——`ISSUE-P3-248`（`DatabasePickerViewModel` 的弱因子提示 KDoc 更正为**如实表述**，由 `WeakKdfNoticeHonestyGuardTest` 钉死口径）与 `ISSUE-P3-250`（限界 §18 **收窄授权**为「不得新增含实现体成员」+ 5 处薄编排待消化清单、§20 按 65 行现值**重新裁定**为接受为限界）；**§250 随之闭环 `ISSUE-P3-249`**、**§251 闭环余下 2 项（`ISSUE-P3-246` / `ISSUE-P3-247`）**——逐条摘要见下方「本区近期变动」引用块。）

> **本区近期变动**：§251 闭环 `ISSUE-P3-246` / `ISSUE-P3-247`（**本区由此归零**）——
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
