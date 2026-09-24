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

> **暂无开放项**。

---

## P2 中危缺陷与协议/测试缺口（1 项）

### ISSUE-P2-307：选择器缓存三例**非确定性失败**（全量套件偶发红、隔离与成对跑皆绿）——测试有效性缺口

- **核实时间点**：2026-09-24（`ISSUE-P3-301` 批次全量回归中偶发出现；复跑同一命令即绿，无代码变更）。
- **核实方式**：
  1. **现象**：`.\gradlew.bat test --max-workers=1` 一次运行报 3 例红——`AutofillPickerViewModelCredentialLookupTest.选择器页缓存命中时不回查仓库单条`（`expected:<0> but was:<1>`）、`AutofillPickerViewModelSessionLockTest.会话锁定后选择器缓存条目被清空`（`测试前提：选择器应已缓存条目`）、`AutofillPickerViewModelSessionLockTest.锁定竞态下读取已清零条目_search与用户名按空降级而非崩溃`（`expected:<1> but was:<0>`）；同一命令**原地复跑即 BUILD SUCCESSFUL（`tests=2688 failures=0`）**，期间无任何代码改动。
  2. **隔离**：`--tests "com.keepasskey.app.autofill.AutofillPickerViewModel*"` 单跑 **BUILD SUCCESSFUL**。
  3. **成对**：与最可疑的邻居 `com.keepasskey.app.sync.SyncAssemblyOffMainThreadTest` 同批跑 **BUILD SUCCESSFUL**（该测试文件的 `HEAD` 版与待提交版**两种都试过，皆绿**）⇒ 「与该文件同分叉即失败」的假设**未成立**。
  4. **未定位**：失败无堆栈归因（皆为断言差异），未取得可复现的最小条件。
- **背景与根因（**待定位**，以下为假设而非结论）**：`SyncAssemblyOffMainThreadTest` 的 `tearDown` 按 `ISSUE-P3-189` 路线①采用「**只装不卸**」口径（不调 `resetMain`，仅走 `MainDispatcherGuard`），即该测试类结束后 JVM 内的 `Dispatchers.Main` 仍是它安装的 `TestDispatcher`。若同分叉内后续测试类**依赖真实 Main**（未自行 `setMain`），其挂起工作可能永不推进 ⇒ 呈现为「缓存应命中却未命中 / 应已缓存却为空」一类断言差异，与本次 3 例的形态吻合。**但该假设未获证据支持**（第 3 步成对实验即为否证尝试）。
- **后果**：CI 会出现**与代码无关的假红**，侵蚀「红=真问题」的信号价值（与 `ISSUE-P3-305` 的红态长期化属不同面：那是门禁恒红，这是偶发假红）。
- **涉及文件**：`app/src/test/java/com/keepasskey/app/autofill/AutofillPickerViewModelCredentialLookupTest.kt`、`app/src/test/java/com/keepasskey/app/autofill/AutofillPickerViewModelSessionLockTest.kt`；高度相关：`app/src/test/java/com/keepasskey/app/sync/SyncAssemblyOffMainThreadTest.kt`（Main「只装不卸」）与其依赖的 `MainDispatcherGuard`；`app/src/test/java/com/keepasskey/app/testutil/MainDispatcherGuard.kt`。
- **验收标准**：AC① **先定位**：取得可复现的最小条件（建议手段：`--max-workers=1` 固定分叉 + 逐类二分加入，或在 `MainDispatcherGuard` 记录「装上 / 卸下」事件并断言套件结束时 Main 已复位）；AC② 定位后**修根因**（`resetMain` 口径的取舍须一次性贯通，禁只给单个用例打补丁）；AC③ 若根因确为 Main 泄漏，须评估 `ISSUE-P3-189` 路线①的既有裁决是否需修订，并同步该裁决的登记处；AC④ 未定位前**不得**以下调断言强度 / 加 `@Ignore` / 放宽比较的方式让套件变绿（假绿比假红更危险）；AC⑤ 补一条守卫：套件级断言「测试结束时 `Dispatchers.Main` 已复位或与初始一致」，使同类泄漏当场可见。

---

## P3 低危问题、特性接线与体验优化（10 项）

### ISSUE-P3-293：剪贴板自动擦除的界面承诺与**已登记口径**不符，且冷启动对账可清除其它应用的内容

- **核实时间点**：2026-09-23 经实现分支与归档批次原文核对（行为本身**已登记**，本条的是文案与未登记的误清面）。
- **核实方式**：`app/.../security/ClipboardSecurityManager.kt:200-214` 的 `armScheduledClear` 按 `autoClearClipboard == false` 不调度，但 `:105-120/285-290` 的熄屏广播、`ON_STOP`、`onSessionLocked`、冷启动对账四条路径不查该设置直调 `clearPendingSensitive()` ⇒ 与 `res/values/strings.xml:651` / `values-en:640` 承诺的「一直留在系统剪贴板……直至被下次复制覆盖或设备重启」（并由 `detail_password_copied_no_clear`（`:329`）同向强化）相悖。方向为安全侧，且**行为已登记**于 `docs/resolved/batches/48-…:72-73`：「切后台即清与『自动擦除开关』**独立生效**；关闭自动擦除者亦受此保护」⇒ 属文案未跟上裁决。另 `reconcileOnColdStart`（`:300-306`）无摘要比对即 `clearClipboard()`，**可清除他应用写入的剪贴板内容**，批 48.4① 只登记了「不留口令等价物」，未登记此误清面。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/security/ClipboardSecurityManager.kt`、`app/src/main/res/values/strings.xml`、`app/src/main/res/values-en/strings.xml`。
- **验收标准**：AC① 中英文案改为**仅**描述真实行为（写明「切后台 / 锁屏 / 锁库 / 冷启动四类时机不受该开关约束」），禁以 KDoc 或批次结论替代用户可见文案；AC② 冷启动对账须先比对摘要、只在确属本应用写入时清除，或在文案与设置项说明中如实声明「可能清除他应用内容」并登记 `PD-*`；AC③ 用例锁定「关闭开关后四类时机仍清」的既有已登记行为不被误改（防后续以文案为准的改动反向放宽安全性）。

---

### ISSUE-P3-295：附件面两处——按文件名（而非 `refIndex`）取字节致同名导出错内容；添加时无尺寸上限

- **核实时间点**：2026-09-23 经同名可否产生的前提核实（本仓**不可**产生同名不同内容，只能来自外部库；首轮所提「改按 UI 的 id 取字节」的修法前提被否证）。
- **核实方式**：① `app/.../data/repository/VaultEntrySecretReader.kt:279` 以 `attachments.firstOrNull { it.name == fileName }` 取字节，调用方 `EntryDetailAttachmentExporter.kt:39` 传 `attachment.fileName`，Toast 亦只报文件名 ⇒ 外部库（KeePass XML / Bitwarden / 桌面版）含同名附件时导出 A 得 B 的字节且提示为 A。解析侧逐 `<Binary>` 节点 emit、去重器原样保留 `name`（`KdbxXmlBinaryNode.kt:208-238`、`KdbxBinaryDeduplicator.kt:84-92`）；本仓编辑页按 fileName 视为替换（`EntryEditViewModel.kt:330-341`）故不自产同名。**修法前提**：`VaultEntryMapper.kt:60` 的 UI `id` 就是 `"${entry.id}_${att.name}"`（名字派生），改按 id 无法消歧，**须按 `refIndex` / 列表下标**。② `ui/screens/edit/EntryEditPickers.kt:64` 对 `*/*` 选择结果整份 `readBytes()` 交 `EntryEditViewModel.kt:330-341`；全仓无 `MAX_ATTACHMENT` 类约束（`BinaryStore.kt:47-57` 的 1 MiB 是落盘阈值非上限；`KdbxXmlBinaryNode.kt:58` 的 `AttachmentBudget` 默认 `unlimited()` 且只封解析不可信库）；无路径穿越面（`FileBinaryStore.kt:38` 只用固定目录名）。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/data/repository/VaultEntrySecretReader.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailAttachmentExporter.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditPickers.kt`、`app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditViewModel.kt`。
- **验收标准**：AC① 附件寻址改按 `refIndex`（或树内下标），UI 的 `id` 生成同步去名字依赖；同名场景用例：导入含两个同名附件的第三方库 → 分别导出各自字节；AC② 对「添加无上限」给结论：设尺寸上限并如实提示，或维持现状并登记 `PD-*`（现状属用户自选大文件自伤 OOM，无攻击者面，故 P3）；AC③ 若设上限，须与解析侧预算的口径统一（`ISSUE-P1-276` 已 §273 闭环，现行三面判据与常量见 `database/src/main/java/com/keepasskey/database/xml/KdbxAttachmentBudget.kt`：单条目物化 ≤ 64 MiB / 引用次数 ≤ 1024 / 内联累计 ≤ 64 MiB），禁两套数字。

---

### ISSUE-P3-297：五项「可填 / 可存但界面无消费」的半成品能力（AutoType 序列 / 标签 / 收藏 / `VaultSyncStatus` / 搜索与到期字段）

- **核实时间点**：2026-09-23 经多口径交叉检索（符号名 + `R.string` 引用 + Manifest + 渲染点穷举）逐项确认。
- **核实方式**：① **AutoType 键入序列**可编辑并写回 KDBX（`EntryEditComponents.kt:199-206`、`strings.xml:378-379` 含 `{USERNAME}{TAB}{PASSWORD}{ENTER}` 占位符且**无免责措辞**），但全仓除映射 / 合并 / 比较（`VaultEntryMapper.kt:104/224/293`、`KdbxContentComparator.kt:86-95`）外**零执行方**，`app/autofill/` 与 `app/passkey/` 内 `autoType|overrideUrl` 0 命中。② **标签**只写不读：`app/src/main` 内 26 处命中，UI 侧仅编辑页输入（`EntryEditComponents.kt:191`）与搜索命中（`VaultListProjection.kt:327`），详情页 / 列表 / 导出零渲染。③ **收藏**能标不能看：`EntryDetailTopBar.kt:62-66` 唯一渲染点，持久化到 customData（`VaultEntryMapper.kt:91`），列表无徽标、`VaultSortOption`（`VaultListUiState.kt:24-32`）与筛选无收藏档。④ `VaultSyncStatus`（`VaultListUiState.kt:37/59`）枚举带 `labelRes` 却**零解析**（`sync_status_*` 实际由 `CloudSyncStatusSections.kt:76` 的 `syncStatusText` 承载）⇒ 纯冗余；CONFLICT / OFFLINE 已由 `VaultListScreen.kt:280` 的 `hasPendingConflict` 旁路。⑤ **搜索**为纯 `contains(ignoreCase)` 子串（`VaultListProjection.kt:320-331`，无整词 / 正则 / 含子组）；`KdbxTimes.expires/expiryTime` 有读端与序列化写端但 app 侧**零编辑入口**（`KdbxContentComparator.kt:32` 自证无写入者，`cardExpiry` 系银行卡自定义字段、非 KDBX 过期）。
- **涉及文件**：上列各文件 + `app/src/main/res/values/strings.xml` 与 `values-en/`。
- **验收标准**：AC① 逐项给「接线 / 如实标注 / 移除」三选一结论（对齐 `ISSUE-P3-65` 假开关处置口径），禁继续保留「可填可存但行为为零」的输入项；AC② AutoType 若维持无执行方，须在字段说明中如实标注「本应用不执行键入序列，仅为兼容性保存」并登记 `PD-*`；AC③ 标签 / 收藏若接线，须同时进详情页渲染与列表筛选（禁只补一半）；AC④ `VaultSyncStatus` 冗余枚举按「删除死代码」口径处置；AC⑤ 搜索增强与 `expires` 编辑入口属能力补齐，可拆独立条目，须中英文案成对。

---

### ISSUE-P3-298：借鉴参考项目仍缺的可达性与反馈能力（外部打开入口 / 大屏双栏 / 请求级重试 / 后台失败可见 / OTP 直填 / CM 排序）

- **核实时间点**：2026-09-23 经 Manifest、调度链与参考项目定点核实；其中后四项第一轮由 sync 代理提出、**未经对抗轮单独攻击**，认领时须自行复核。
- **核实方式与清单**：① 无 `.kdbx` 的 `ACTION_VIEW` / `SEND` intent-filter——三份 Manifest 检索后唯一 VIEW 出现在 `<queries>`（`AndroidManifest.xml:42-45`，属包可见性非入口）；SAF 已在页内承接（`DatabasePickerScreen.kt:157`、`OpenExistingVaultDialog.kt:43`），限界 §24（`已知工程限界.md:598`）认 SAF 为准 ⇒ 属**习惯可达性**缺口（官方 / KeePassDX 支持「打开方式」直达）。② 宽屏仅换 NavigationRail（`KeePasskeyApp.kt:362-369`，`screenWidthDp >= 600`），无 `ListDetailPaneScaffold` / `WindowWidthSizeClass` 命中 ⇒ 大屏仍逐页跳。③ 无请求级重试与退避：`app/.../sync/PeriodicSyncWorker.kt:31-41`（含异常也 `Result.success()`）、`sync/.../network/SyncHttpClientFactory.kt:38-51` 只设超时 ⇒ 瞬时 5xx / 429 只能等下个周期（对照 kp2a `BackgroundSyncService` 按状态码重试）。④ 后台同步失败零可见：`SyncCoordinator.kt:108-112` 的 `syncEvents` **全仓无订阅者**，`app/.../notification/NotificationChannels.kt:31-45` 仅「已解锁」与「填充验证码」两条通道 ⇒ 库可连续数周未同步而用户无感（与 `ISSUE-P3-272` 的「自动同步开关是假的」不同面）。⑤ OTP 字段从不直填：`AutofillPostFillTotpActions.kt:52-74` 只复制 / 通知，`AutofillFieldScanner.kt:124-131` 无 `one-time-code` / `SMS_OTP` 通道（定向检索 0 命中）；KeePassDX 有独立 `otpTokenId` 槽并真实填充（`StructureParser.kt:149/339` → `AutofillHelper.kt:314-323`），KeePassXC 有 `get-totp`。设置项文案自我限定为「复制到剪贴板 / 通知」（`strings.xml:861-866`），未宣称填充 ⇒ 属未实现的对齐项、非谎报。⑥ CM 通道无「上次使用置顶」：`CredentialResponseAssembler.kt:137-155` 未用 `AutofillLastFilledStore`——但本仓服务**未实现** `onCompleteGetCredentialRequest`（全仓 0 命中），CM 侧当前无「被选凭据」信号可作数据源 ⇒ 属框架接线面。
- **涉及文件**：`app/src/main/AndroidManifest.xml`、`app/.../ui/KeePasskeyApp.kt`、`app/.../sync/PeriodicSyncWorker.kt`、`sync/.../network/SyncHttpClientFactory.kt`、`app/.../notification/NotificationChannels.kt`、`app/.../autofill/AutofillPostFillTotpActions.kt`、`app/.../passkey/CredentialResponseAssembler.kt`。
- **验收标准**：AC① 逐项给「补齐 / 维持并登记 `PD-*` / 记限界」结论，禁停留在清单状态；AC② 若补④：须新增同步失败通知渠道（可静音、可关）并让 `syncEvents` 有真实订阅者，同时保持 `PeriodicSyncWorker` 的 `Result.success()` 不再吞掉可观测性；AC③ 若补③：重试须与 `ISSUE-P1-275` 的乐观锁语义合流（已闭环，见 `RESOLVED_LOG.md` §272；重试前重新校验基线），禁「重试即无条件 PUT」；AC④ 若补⑤：不得移除既有复制 / 通知路径，且须走 `AutofillValue` 正规通道；AC⑤ ⑥须先取框架侧权威依据（Credential Manager 回调契约）再定可行性，取不到则如实标注未证实。

---

### ISSUE-P3-300：弱 ETag 乐观锁的**真实 DAV 服务器矩阵未实测**——弱 ETag 服务器上的同步收敛行为待证（§272 AC⑤ 显式残余）

> 本条**不属**上文「`ISSUE-P1-276` ～ `ISSUE-P3-299`」审查批例外范围：它由 §272（`ISSUE-P1-275` 闭环批）
> 的 AC⑤ 未执行残余升级为待办承接，与 [`已知工程限界.md`](architecture/已知工程限界.md) §28 互为指针。

- **核实时间点**：2026-09-23（§272 整改当日，随批如实登记）。
- **核实方式**：① §272 已完成的证据面＝RFC 全文核对（RFC 7232 §2.3 / §3.1、RFC 4918 §8.6 / §10.4.2 / §10.4.4 / §10.4.9，rfc-editor.org 原文）+ 有状态 mock 的头形态与弱比较裁决守卫（`StatefulDavDispatcher.weakEtagPaths`）——**协议层与形态层已证**。② 条目 AC⑤ 要求的**真实服务器矩阵**（Apache/mod_dav、nginx-dav、Nextcloud、IIS）当日无环境可用；`tools/local-sync` 联调链路代理侧不可运行（既有口径）且 `run_webdav.py` 不产 ETag，本就不构成弱面取证 ⇒ **未执行**，登记于批次文档 §3.3 与限界表 §28。
- **背景与根因**：§272 弱 ETag 收口选定「回传服务器签发原形态」取向（MOVE `If` 头携 `W/"…"`；`If-Match` 弱期望不发送；S3 弱期望 fail-closed）。理论边界已经 RFC 划清：**弱比较**服务器上原形态必匹配；**强比较 × 弱存储标签**的组合下任何客户端的实体标签预条件均不可满足（RFC 7232 §2.3 强比较要求两侧均非弱），属服务器自绝于条件写，本仓退化为 412 → 冲突重检（不静默覆盖、不丢数据，但同步可能反复提示冲突）。**未证的是**：真实服务器对 RFC 4918 §10.4.4「弱或强比较二选一」的实际取向、以及 MOVE 事务写在弱 ETag 服务器上的兼容性——这决定弱 ETag 服务器（Apache/mod_dav 部分文件系统配置为高发面）上同步是正常收敛还是反复冲突提示。
- **涉及文件**：无生产代码改动面（纯外部验证条目）；实测结果回填 `docs/resolved/batches/272-冲突时刻ETag透传与弱校验收口批次.md` §3.3 与 `docs/architecture/已知工程限界.md` §28。
- **验收标准**：AC① 四类服务器各实测三项读数并按规则 8 留证（命令 + 原始响应）：签发 ETag 的强/弱形态；MOVE `If` 头对弱形态（`[W/"…"]`）与强形态（`["…"]`）预条件的接受性；PUT+MOVE 事务写兼容性。AC② 实测**证实**「回传原形态」取向 ⇒ 回填两处登记并闭环本条；实测**推翻** ⇒ 不得就地放宽 §272 守卫用例（限界表 §28 边界条款），须另行立条裁决新取向。AC③ 无法取得的环境（如 IIS）逐项如实标注未执行，禁以 mock 绿推定闭环。AC④ 环境不可得期间，本条与限界表 §28 维持开放，不得归档。

---

### ISSUE-P3-302：大库下拉刷新的**主线程阻塞量级未实测**（§274 AC④ 显式残余）

- **核实时间点**：2026-09-23（§274 整改当日，随批如实登记；与 `ISSUE-P3-300` 同型）。
- **核实方式**：① §274 已完成的证据面＝**调度边界层**：`SyncAssemblyOffMainThreadTest` 以五个探针（栈归因 + 非空性断言 + 线程身份断言）锁定装配段四类重活不在主线程执行，判别力实验（撤销下沉 ⇒ 五类探针全落主线程基准即红、恢复即绿）证明守卫紧扣缺陷本身；② 条目 AC④ 要求的**真机量级读数**（大库下拉刷新的帧耗时 / 主线程阻塞时长）当日本会话**无设备**，未执行任何真机或 `connected` 用例 ⇒ **未执行**，登记于批次文档 §2.4 与 §3.4。
- **背景与根因**：§274 的整改依据「大库下为**数百毫秒至秒级**」是**估算**（同一主线程上做 Keystore 解密 + 整库密文读 + 全库逐字段比较 + 整库写 + `fd.sync()` 的量级推理，非本机实测）。按定义整改后主线程应只剩一次 stat，但**未证的是**：真实大库（万级条目 / 含附件）下装配段下沉后的实际主线程阻塞与帧耗时，以及把该段整体挪到 `Dispatchers.IO` 后对**同步总时长**的影响（阻塞被移走，该段本身仍须执行完）。
- **涉及文件**：无生产代码改动面（纯外部验证条目）；实测结果回填 [`resolved/batches/274-同步周期装配段下沉IO批次.md`](resolved/batches/274-同步周期装配段下沉IO批次.md) §2.4 / §3.4，并按需登记 [`architecture/已知工程限界.md`](architecture/已知工程限界.md)。
- **验收标准**：AC① 接入设备后构造**大库**（万级条目，含 ≥1 MiB 落盘附件与历史快照）实测下拉刷新：记录装配段耗时、主线程最长连续阻塞、掉帧数与同步总时长，并按「量级须实测」留证（工具 + 原始读数）；AC② 实测**证实**「主线程无同步重活」⇒ 回填批次 §2.4 / §3.4 并闭环本条；实测**推翻**（仍见秒级主线程阻塞）⇒ 另行立条定位残余阻塞点（含 `ISSUE-P3-301` 的 stat 面与 `SyncCache` 之外的调用），**不得**就地改判 §274 的守卫口径；AC③ 无设备期间本条维持开放，**不得**以 §274 的 JVM 守卫绿推定量级已证；AC④ 若同批接入设备，与 `ISSUE-P3-300`（真实 DAV 矩阵）一并执行以减少设备占用。

---

### ISSUE-P3-303：不可擦 `String` 明文残留分层——导出侧 `readString()` 可收口，模型层字段 `String` 不可在导出器层解决

> 本条承接 [`敏感缓冲所有权契约.md`](architecture/敏感缓冲所有权契约.md) §4 **#19** 的摘除条件
> （「若日后改走 `useChars` 路径可摘除本行」）与 §278 批次 §5.1 的如实声明；两类残留**性质不同**，
> 判据与去向分列，禁混为一谈。

- **核实时间点**：2026-09-23（§278 整改当日，就「不可擦 `String` 能不能解决」命题逐层核对模型与写出口）。
- **核实方式**：
  1. **导出侧 `readString()`（可解）**：`KdbxCsvExporter.kt:76` 的 `entry.password?.readString().orEmpty()`、`KeePassXmlExporter.kt:75/91` 的 `password.readString()` / `field.value.readString()` 三处，物化 JVM `String` 后驻留至 GC。`ProtectedString` 已提供 `useChars` 闭包（`ProtectedString.kt:132-139`，`CharArray` 用毕自动 `fill`）；写出口现状只收 `String`——`KdbxCsvExporter.writeField(writer, value: String)`、`KdbxXmlWriteUtil.textElement(..., text: String)`。⇒ **技术上可解**：加 `CharArray` 写出口（含 XML 转义 / CSV RFC 4180 引号化的 `CharArray` 版），三处改走 `useChars`，产物字节不变。
  2. **模型层字段 `String`（导出器层不可解）**：`KdbxEntry` 的 `title` / `userName` / `url` / `notes`（`KdbxEntry.kt:28-40`）与 `KdbxGroup.name` / `notes`（`KdbxGroup.kt:10`）在**模型上就是 `String`**，导出器只是读取既有 `String`——`readString()` 只出现在 `ProtectedString` 字段（口令、自定义字段值）。清这批须改模型层形态（`ProtectedString` / `CharArray`），牵动映射 / 合并 / 比较 / UI / 序列化全链，**不在导出器层可解**；其性质与限界 §2.4（Compose 文本状态不可擦 `String`）/ RC-01 同族。
- **背景与根因**：§278 按 AC② 二选一取了「登记」分支（限界已接受 + 可收敛点是一致性），把「改 `useChars`」留作摘除条件未实施。用户 2026-09-23 追问「不可擦 `String` 能不能解决」后逐层核实：**一半能、一半不能**，须分开登记，避免「已登记限界」被读作「两类都动不了」或「改 `useChars` 就全干净了」。
- **涉及文件**（仅第 1 类）：`database/src/main/java/com/keepasskey/database/csv/KdbxCsvExporter.kt`、`database/src/main/java/com/keepasskey/database/xml/KeePassXmlExporter.kt`、`database/src/main/java/com/keepasskey/database/xml/KdbxXmlWriteUtil.kt`（`textElement` 增 `CharArray` 出口）；契约 [`敏感缓冲所有权契约.md`](architecture/敏感缓冲所有权契约.md) §4 #19。
- **验收标准**：
  - **第 1 类（导出侧 `readString()`，可解，本条主整改面）**：AC① 三处 `readString()` 改走 `useChars` + `CharArray` 写出口（`writeField` / `textElement` 须有 `CharArray` 重载或等价路径，XML 转义与 CSV 引号语义逐字节等价）；AC② 产物字节与改前**逐字节等价**（`KdbxCsvExporterTest` 4 例 + XML 既有产物断言原样通过，必要时补对照例）；AC③ 完成后**摘除契约 #19**（或就地改写为「已收口」并留痕），禁「代码已改、契约仍记残留」；AC④ 新增守卫锁定「导出器不得再出现 `readString()`」（静态接线，口径同 `WipableByteArrayOutputStreamTest`）。
  - **第 2 类（模型层字段 `String`，导出器层不可解）**：AC⑤ **不在本条整改**——按限界口径处置：若维持现状，须在限界表登记（或扩写 §2.4 同族）并写明「模型层 `title`/`url`/`userName`/`notes`/分组名以 `String` 驻留，进程内取证在信任边界外」；若要收口，须**另立条目**评估模型层改造（`ProtectedString` 化或 `CharArray` 字段）的牵动面，禁在本条顺手改模型。
    **（2026-09-23 §284 补全登记）**：AC⑤ 的「维持现状 + 限界登记」分支已落地——限界表新增 **§2.7**，单列**展示层**（`GeneratorScreen` `readString()` / `EntryDetailSecrets` `toDisplayString()` 与三个 `String` 状态）与**模型层**（`KdbxEntry` / `KdbxGroup` 元数据字段）两类驻留，并与 §2.4 / §2.6 分列；解除条件仍须另立条目。第 1 类（导出侧 `useChars`）**仍未实施**，本条继续开放。
  - **通则**：AC⑥ 两类的结论与去向必须**分列写明**，禁以「不可擦 `String` 都是已接受限界」一句带过（第 1 类恰恰**可以**收口）。

---

### ISSUE-P3-305：CI `hygiene-gate` 在 `main` 上为**红态**——`tier1(>500)=4`、`functions_ge_100=2`（§280 收工线已破）

- **核实时间点**：2026-09-24（§300 批次机检复核时发现；随即逐文件与 `HEAD` 对拍，确认为**既存**红态，非该批引入）。
- **核实方式**：
  1. `python tools/doc/count_line_tiers.py` → **EXIT 1**，`tier1(>500)=4`（闸门要求恒为 0）：
     `app/src/main/java/com/keepasskey/app/sync/SyncCycleRunner.kt` **561**、`app/src/main/java/com/keepasskey/app/sync/SyncConflictController.kt` **547**、
     `sync/src/main/java/com/keepasskey/sync/engine/SyncCache.kt` **507**、`database/src/main/java/com/keepasskey/database/session/DatabaseSession.kt` **501**；
     同次读数 `tier2(400~500)=33  budget=37`。
  2. `python tools/doc/long_functions.py` → **EXIT 1**，`functions_ge_100=2`：
     `SyncCycleRunner.kt::setupCycleContext`（105 行，L127）、`EntryEditFormSections.kt::EntryEditAccountSection`（100 行，L230）。
  3. **既存性证明**：对上述 4 个超大文件 + `EntryEditFormSections.kt` 逐文件 `wc -l` 与 `git show HEAD:<path> | wc -l` 比对，**行数完全一致**，且 5 个文件**均不在 §300 改动面内**。
  4. **CI 调用面**：`.github/workflows/build.yml` 的 `hygiene-gate` 第 3 / 第 4 条正是**无参数**调用这两个脚本 ⇒ 当前 `main`（`dbdf1a34`）上的该 gate 为红。
- **背景与根因**：§280 把「超长函数与超大文件」一次性压到 `functions_ge_100=0` / `tier1=0` 并立为**收工线**，§281 又把六条自研机检挂成 CI 硬门禁；§285 记 `tier1=0 tier2=37` / `functions_ge_100=0`。此后 §286~§299 的连续整改（合并标量词汇表 / 图标池 / KDF / 同步库身份绑定等）在这些同步与数据库类上净增行数，**越线未被察觉**——历史批次仅以「`.\gradlew.bat test` 全绿」结案，**未逐条复核门禁面读数**，形成「闸门存在 ≠ 闸门被执行」的缺口。
- **后果**：不影响运行时行为与用户可见功能；真正代价是 **fail-closed 硬门禁的信号价值被稀释**（红态长期化后，后续真正的越线不再被当作异常）。
- **涉及文件**：上列 4 个超大文件 + 2 个超长函数所在文件（`app/.../ui/screens/edit/EntryEditFormSections.kt`）；CI 配置无需改动。
- **验收标准**：AC① 4 个 `tier1` 文件与 2 个 ≥100 行函数按**职责拆分**整改至 `tier1=0` / `functions_ge_100=0`——**禁**以放宽阈值、扩白名单、调 `--max` 或改判据口径逃避；AC② 拆分为**纯结构性**（行为零变更），既有用例原样全绿，**不得删改任何既有用例**（测试资产纪律 ①）；AC③ 拆完复核 `tier2` 棘轮预算**只紧不松**（当前 33 / 37）；AC④ 复盘「§285 之后逐批未发现门禁转红」并落一条防回潮机制（批次文档须**逐条登记机检读数**，不得只写「六条机检 EXIT 0」）；AC⑤ 无设备侧义务（不动 `*/src/androidTest/**`）。

### ISSUE-P3-306：KDBX `<History>` 列表方向未在**读取侧归一**——官方 KeePass 形态的库（最旧在前）在本仓会按位置裁错端、修订列表倒序

- **核实时间点**：2026-09-24（`ISSUE-P3-292` 整改中，因「截断须裁最旧端」而逐侧核对方向时发现）。
- **核实方式**：
  1. **本仓约定 = 头部最新**（`HistoryManagerTest` 锁定）：`recordHistorySnapshot` 头插新快照；`pruneHistory` 以 `take(maxItems)` **保留头部**；`VaultEntryMapper` 按列表序原样产出 `UiEntryRevision`，详情页（`EntryDetailSections`）不再排序。
  2. **合并路径已改为同向**（`ISSUE-P3-292` / §303）：`KdbxEntryMerger` 由 `sortedBy` 改 `sortedByDescending`，`KdbxMergerTest` / `KdbxMergerV2Test` 的按升序断言随之更正。
  3. **读取侧未归一**：`KdbxXmlGroupReader` 以 `history.add(it)` 按**文档顺序**装配，写入侧 `KdbxXmlEntrySerializer` 亦按列表序写回 ⇒ **文件序 == 内存序**，方向完全取决于输入文件。
  4. **官方实现为「最旧在前」**：`参考项目/KeePass-2.61.1-Source/KeePassLib/PwEntry.cs` 的 `CreateBackup` 明注 `m_lHistory.Add(peCopy); // Must be added at end`；`RemoveOldestBackup` 扫描找**时间戳最小**者移除（按时间戳而非位置）。
- **背景与根因**：方向约束只在「本仓自产 / 合并产出」的路径上被满足，**读取侧从文件进入内存时不做归一**。故一条来自官方 KeePass 或其它遵从官方顺序的实现所产出的库，其内存历史方向与本仓约定相反。
- **后果**：① 此类库在本仓编辑后触发保存路径的 `pruneHistory` 时，**按位置裁掉的是最新快照**（`take(maxItems)` 保留头部 = 保留最旧一侧）——数据损失方向，仅在历史条数超过库级上限时显现；② 该库的修订列表在详情页**倒序**显示；③ 写回文件的历史顺序与本仓自产库不一致（官方读取端按时间戳维护，功能不受影响，属展示序差异）。
- **涉及文件**：`database/src/main/java/com/keepasskey/database/xml/KdbxXmlGroupReader.kt`（读侧装配点）、`database/src/main/java/com/keepasskey/database/xml/KdbxXmlEntrySerializer.kt`（写侧）、`database/src/main/java/com/keepasskey/database/history/HistoryManager.kt`（`pruneHistory` 的位置口径）、`app/src/main/java/com/keepasskey/app/data/repository/VaultEntryMapper.kt`（修订投影）。
- **验收标准**：AC① 先**裁决方向**——以「内存 / 文件均为头部最新」为准归一（与本仓既有约定及 `HistoryManagerTest` 一致），或改为官方「最旧在前」并同步翻转 `recordHistorySnapshot` / `pruneHistory` / 修订列表 UI（**两条路都须一次性贯通**，禁只改一半）；AC② 选定后在**读取侧归一**（装配时按 `lastModificationTime` 排序），使任一来源的库进入内存后方向恒一致，从而位置口径恒正确；AC③ 用例：以**升序**（官方形态）输入的库为 fixture，断言保存路径裁掉的是**最旧**一端、且修订列表顺序与本仓自产库一致；AC④ 归一**只调序、不得改写快照内容**，并复核 `KdbxMergerTest` 既有方向断言不被二次翻转；AC⑤ 若判定「文件序须与官方一致」，须按规则 8 以官方实现端到端对拍留证。

---
