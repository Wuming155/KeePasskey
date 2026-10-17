# KeePasskey 当前未解决问题

本文件是当前缺陷、功能缺口和待办的唯一状态源，只保留尚未闭环事项。新条目须记录核实时间与方式；开始处理前重新核对涉及的路径、行为和前提。关闭事项后从本表移除，不在此追加进度或历史流水；完成任务不自动新建永久文档。

> **本轮进度口径**：下文“已实现”只表示代码/文案已有对应改动，不表示问题已闭环；“尚未完成”列出必须继续实现或验收的项目。只要仍有未完成项，该问题就保留在本表。

## 优先级

| 等级 | 范围 | 处理原则 |
|---|---|---|
| P0 | 数据损坏、明文泄露等阻塞级问题 | 立即优先处理 |
| P1 | 高风险缺陷或核心功能阻断 | 最高优先级 |
| P2 | 协议互操作、性能或测试有效性缺口 | 正常排期 |
| P3 | 低危问题、体验和渐进改进 | 渐进处理 |

## P0 阻塞级问题

暂无。

## P1 高危与核心功能问题

暂无。

## P2 中危缺陷与协议/测试缺口

### ISSUE-P2-661 进程死亡导致未提交会话脏树与待决漂移裁决不可恢复（无 durable draft）

- 核实：2026-10-17（Asia/Shanghai），**真机实测**（隔离 AVD `sdk_gphone64_x86_64` / API 36）：提交卡在临界区前门 → `adb shell am force-stop com.keepasskey.debug`（instrumentation 报 `Process crashed`）→ 新进程重开同一库；读数：正式文件与提交前逐字节一致、重解析仅 `Disk#1`、无 `LocalOnly#1`、打开前无持久化基线、打开后 `checkDrift=false`、无 pending/conflict。置信度：高（可稳定复现）。
- 问题：会话脏树（未落盘的内存编辑）与待决漂移裁决（`pending` / `mergeConflict` / 在途提交）**全部只存在于进程内存**，无任何持久化草稿 / 崩溃恢复日志。进程死亡（force-stop / OOM / 崩溃）后它们随进程消失；重开时持久化基线以**当前磁盘内容**重新登记，因此既不会把「已处理」写进任何持久状态，也**不会凭空恢复**此前的冲突与本地修改。
- 真实状态机（实测口径，取代此前聊天中「下次保存会重新触发漂移」的错误推导）：本地脏树 → 外部改写磁盘 → 用户选 `KEEP_LOCAL`（UI 处理中、未报告成功）→ commit 前进程死亡 → 内存 session / 待决裁决 / 在途标记丢失 → 正式文件保持外部版本（无半写）→ 重启重开磁盘文件 → 基线以该文件建立 → 无 pending/conflict → 原未提交本地修改不再存在；**只有新的外部改写才会再次产生漂移**。
- 用户可见风险：进程死亡前的未提交编辑丢失（与任何未保存编辑同型）；刚作出的裁决选择需在重新编辑并再次触发漂移后才能重做。
- 恢复材料：**不存在** durable draft / draft journal；`.bak` 滚动备份只保存「上次成功写入前」的版本，不含未提交编辑；`recoveryStore` 只服务凭据轮换的旧凭据恢复，不承载树内容。
- 定级：**P2**。理由：不损坏已持久化数据、不产生半写、不前移错误基线、不谎报成功（失败/中断路径不产生成功状态）；损失限于进程死亡瞬间未提交的内存编辑，属密码管理器通用的「未保存编辑无 durable draft」边界，而非本轮整改引入；但显式裁决后立即丢失的体验成本明显，值得单独修复（durable draft / 崩溃恢复）。
- 与 `ISSUE-P1-631` 的因果边界：P1-631 管「外部漂移合并是否使用真实 base 并保住本地修改」（**有落盘机会时**，含 File / SAF / 失败 / 换库并发 / 正式库与备份重解析）；本项管「进程死亡后内存态是否可恢复」（**无落盘机会**），不是同一因果链。**回归判定以旧代码实跑为据**：`git worktree` 检出修复前提交 `3d9105d6`，在同一 AVD 上按「内存脏树 + 待决提示 → force-stop → 新进程核对」实测，读数（文件逐字节未变、仅 `Disk#1`、无 `Local#1`/`LocalOnly#1`、打开前无基线、打开后 `checkDrift=false`、无提示）与新版**逐项一致** ⇒ 非本轮回归。旧提交无阶段门与 debug 宿主（均为 `b5c0cc53` 引入），无法原样运行新版 UI 用例，改用最小自包含探针复现同一现场（临时工作树，已清理）。
- 位置：`app/src/main/java/com/keepasskey/app/security/VaultFileDriftCoordinator.kt`（待决状态全内存）；`database/src/main/java/com/keepasskey/database/session/`（会话树无持久化草稿）；`app/src/main/java/com/keepasskey/app/data/repository/VaultFileDriftResolve.kt`（在途提交）。
- 已实现：无（本轮仅实测与定级）。尚未完成：durable draft / 崩溃恢复能力的产品裁决与实现（含密文草稿的密钥生命周期与擦除策略）；限界登记见 [`已知工程限界.md`](architecture/已知工程限界.md) §49。
- 关闭条件：明确产品裁决（支持或明确不支持崩溃恢复）；若支持，需有加密草稿的写入/恢复/擦除闭环与进程死亡实测；若不支持，需在用户可见处如实声明并保持限界条目。

### ISSUE-P2-632 历史版本以条目 UUID 定位，多个修订总命中首项

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：历史快照保留条目 UUID，投影又把该 UUID 当修订 id；读取历史密码/快照时使用 firstOrNull(id==revisionUuid)，故多条历史记录具有同一定位值，选择较早记录仍读取列表首个版本。
- 影响与触发：历史对比、历史密码和回滚可能选择错误版本；本仓自产历史和按同 UUID 保存的外部历史都受影响。 触发序列及反证详见方案。
- 位置：`database/src/main/java/com/keepasskey/database/history/HistoryManager.kt:46`；`app/src/main/java/com/keepasskey/app/data/repository/VaultEntryMapper.kt:56`。
- 已实现（2026-10-17）：新增 `EntryRevisionHandle`（句柄 = 历史下标 + 修改时间；下标漂移按时间戳唯一命中自愈，已修剪 / 同时间戳歧义一律拒绝），`VaultEntryMapper` 的修订投影与 `VaultEntrySecretReader` 的修订密码 / 快照读取统一改走该句柄；磁盘 KDBX 的历史 UUID 语义未改。JVM 用例 `EntryRevisionHandleTest`（6 例）实跑通过，既有 `RealVaultRepositoryTest` 修订读取已改到句柄并同批通过。
- 尚未完成：原路径的**设备端产品复现**（详情页分别选择第 2 / 3 行做对比与回滚）、历史列表刷新与修剪后的 UI 实测、与独立 KDBX 实现读取同一库的对拍（本轮未改磁盘格式，但仍需端到端确认）。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-632/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-633 历史回滚通过当前附件索引合并，不能恢复历史附件

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：回滚将历史投影传入通用saveEntry；历史附件投影只有refIndex/名称，没有历史字节来源。AttachmentMergePolicy按当前条目附件列表的同索引或同名项取数据，未命中时构造空附件。历史附件内容被当前内容替代或变空。
- 影响与触发：历史回滚显示成功却未恢复附件；同名附件、替换/删除/重新排序后尤其明显。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailRevisionController.kt:55`；`app/src/main/java/com/keepasskey/app/data/repository/VaultEntryMapper.kt:70`。
- 已实现（2026-10-17）：新增 `VaultHistoryRestorePort.rollbackEntryToRevision` 与 `VaultEntryRevisionRollback`——按句柄定位真实历史 `KdbxEntry`，以 `HistoryManager.rollbackToSnapshot` 生成「内容取快照、UUID / 父组 / customData 取当前条目」的结果后原位落树；附件连同 `BinarySource` 引用与字节所有权一并恢复，回滚前版本归档为最新历史。回滚路径不再经 UI 投影与 `AttachmentMergePolicy`。JVM 用例 `VaultEntryRevisionRollbackTest`（5 例：同名异字节、历史独有附件、归档、句柄失效零写入、往返回滚）实跑通过。
- 尚未完成：设备端回滚后导出附件并与历史版本比对（内联与落盘两种附件形态）、独立 KDBX 工具读取核对、锁定清理不误擦恢复后附件的实测。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-633/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-634 自定义字段删除和改名在保存时被补回旧键

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：UI删除字段会从customFields中移除，但mergeCustomFields无条件保留所有未出现在UI键集合中的旧字段。删除的旧键被补回；改名则保留旧键并新增新键。
- 影响与触发：用户不能真正删除普通/受保护自定义字段，可能误以为旧敏感信息已移除；不等于明文对外泄露。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditCustomFields.kt:74`；`app/src/main/java/com/keepasskey/app/data/repository/VaultEntryWriteCoordinator.kt:175`。
- 已实现（2026-10-17）：新增 `CustomFieldMergePolicy`（纯策略）统一删除 / 改名 / 保留三态：载入基线 `UiCustomField.id → 键名` 经 `saveEntry(loadedCustomFieldKeys=…)` 提交，被移除或改名的旧键退役；Passkey schema 键与基线外新增键一律保留；无基线调用方（导入 / 草稿）维持整改前语义。重名键与标准字段键冲突在写入前拒绝（`KdbxError.ENTRY_CUSTOM_FIELD_INVALID`），不落库、草稿留在编辑页。JVM 用例 `CustomFieldMergePolicyTest`（10 例）与协调器级 `EntryWriteIntentCoordinatorTest`（2 例）实跑通过。
- 尚未完成：设备端编辑页删除 / 改名后重开条目核对当前键集合、受保护字段与 Passkey 锁字段的 UI 实测、独立 KDBX 读取确认键确实缺失。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-634/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-635 编辑页选择根分组被当成保留原分组

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：根分组chip传onGroupChange(null)，保存时却用 entry.groupId?.parse ?: existing.parentGroupId；null被解释成“不变”，因此已有条目无法通过编辑页移回根。
- 影响与触发：界面显示已选根且保存成功，但条目仍在旧分组；批量移动有独立实现，不直接受此问题影响。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditGroupAndAppSections.kt:74`；`app/src/main/java/com/keepasskey/app/data/repository/VaultEntryWriteCoordinator.kt:106`。
- 已实现（2026-10-17）：新增 `EntryGroupIntent`（`Keep` / `Root` / `ToGroup`）三态意图并贯穿 `saveEntry`，落点解析抽为纯函数 `EntryGroupTargetResolver`；编辑页按分组 chip 的选中值显式提交（`null` = 移动到根）。换址改用会话单次原语 `DatabaseSession.saveEntryInGroup`（同临界区内「摘除 + 落位」，取代 `deleteEntry + saveEntry` 的中间态）。目标分组不可解析 / 不存在 / 落在回收站子树内一律拒绝写入（`KdbxError.ENTRY_GROUP_TARGET_INVALID`），不写历史、不改树。JVM 用例 `EntryWriteIntentCoordinatorTest`（5 例：移到根、Keep 不动、跨子组、目标不存在、回收站拒绝）实跑通过。
- 尚未完成：设备端「子组 → 根目录」实测与重开核对父子关系、只读会话与保存失败路径实测、新建 / 模板 / 批量移动三条路径的根分组契约一致性复核（批量移动本就用 `null` = 根，本轮未改动）。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-635/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-636 同步缓存与版本标记分离提交可在重启后误判无修改

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认，崩溃矩阵待实跑；置信度：高（false-clean分支）；后续覆盖后果待验证。
- 问题：writeCache先替换.cache，再独立更新.version。两步之间中断会留下新字节与旧/缺失version；hasLocalChanges仅比较version/baseversion，缺失version直接false。冷启内容比较以当前库对新cache，两者相同仍判UNCHANGED，无法修复旧标记。
- 影响与触发：已保存本地内容可能不上传，远端长期落后；远端后续变化时有覆盖本地新内容的风险。不能表述为崩溃立即损坏正式KDBX。 触发序列及反证详见方案。
- 位置：`sync/src/main/java/com/keepasskey/sync/engine/SyncCache.kt:126`；`sync/src/main/java/com/keepasskey/sync/engine/SyncCacheMaintenance.kt:29`。
- 已实现：无，本轮仅审查和编制方案。尚未完成：原路径产品复现、实现、失败对照及相应运行验收；静态结论不代表已闭环。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-636/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-637 前台闲置锁定任务未保存句柄，关闭或重排无法取消旧定时器

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：scheduleForegroundIdleLock取消foregroundLockJob后启动scope.launch，却没有把返回Job赋给该字段。所有取消点实际拿不到已启动任务；延迟结束又使用捕获的旧settings，不重新检查开关/前后台状态。
- 影响与触发：关闭前台锁定、调长超时或转后台后旧计时器仍可能锁库；连续触摸积累多个定时任务。此路径更可能提前锁定，不支持“绕过锁定泄露”的结论。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/security/AutoLockManager.kt:115`。
- 已实现：无，本轮仅审查和编制方案。尚未完成：原路径产品复现、实现、失败对照及相应运行验收；静态结论不代表已闭环。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-637/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-638 恢复副本差异比较忽略密码和实际附件内容

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：恢复预览的fingerprint只比title/userName/url/notes/tags及附件/历史数量，未比较密码、OTP、受保护自定义字段、Passkey材料或附件字节。内容改变但数量相同可显示“修改0”。
- 影响与触发：恢复/覆盖/丢弃前的决策依据失真，尤其密码单独变化、历史已达容量上限或禁用历史的库；不意味着系统自动删除副本。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/security/VaultRecoveryCoordinator.kt:211`。
- 已实现：无，本轮仅审查和编制方案。尚未完成：原路径产品复现、实现、失败对照及相应运行验收；静态结论不代表已闭环。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-638/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-639 恢复预览迟到结果未绑定所选副本

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认，交错场景待实跑；置信度：高。
- 问题：预览A期间列表项仍可点击切换B；select清空preview，但A异步完成时无请求代次/selectedKey比较，直接把A预览写入当前B状态。restore只检查当前选中B及preview非空，可能用A的成功预览放行恢复B。
- 影响与触发：显示和恢复前置校验目标错配，用户可能未解锁/比较B就覆盖其正式库；不表示能解密B或绕过KDBX密码学。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/ui/screens/recovery/RecoveryViewModel.kt:67`；`app/src/main/java/com/keepasskey/app/ui/screens/recovery/VaultRecoveryDialog.kt:112`。
- 已实现：无，本轮仅审查和编制方案。尚未完成：原路径产品复现、实现、失败对照及相应运行验收；静态结论不代表已闭环。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-639/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-640 明文批量导入缺少目标库与会话代次绑定

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；确认静态缺口，跨库后果需要进一步验证；置信度：中。
- 问题：导入generation只防止旧进度覆盖UI；persist开头只检查一次isLocked，后续各条saveEntry/saveGroup面向当前活动单例，没有捕获目标path/session ticket。锁库/换库/重新解锁交错下，剩余条目可能写入另一个库。
- 影响与触发：可能产生跨库错投或部分导入；目前只有缺少目标绑定的静态证据，实际UI/锁定交错需确定性验证，不宣称已发生数据泄露。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/ui/screens/importer/VaultImportController.kt:91`；`app/src/main/java/com/keepasskey/app/data/importer/VaultImporter.kt:81`。
- 已实现：无，本轮仅审查和编制方案。尚未完成：确定性复现与反证收敛、实现、失败对照及相应运行验收；静态结论不代表已闭环。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-640/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-641 CM Passkey与创建保存入口未消费已登记的库代次上下文

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；确认静态缺口，旧入口设备影响待验证；置信度：高（遗漏）；中（系统旧入口后果）。
- 问题：构造入口时已登记databaseId/sessionTicket，但只有PasswordFill消费并最终复核。Passkey断言/创建及密码保存/草稿读取当前活动库；BaseCredentialActivity仅做窗口保护，没有补偿票据检查。
- 影响与触发：旧A入口在活动库变B后可能对B同UUID条目计数/签名或把创建数据写入B，造成账户/库上下文混淆。需用户选择与验证；未证明任意应用、静默或跨RP泄漏。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/passkey/CredentialResponseAssembler.kt:370`；`app/src/main/java/com/keepasskey/app/passkey/PasskeyAssertionActivity.kt:102`。
- 已实现：无，本轮仅审查和编制方案。尚未完成：原路径产品复现、实现、失败对照及相应运行验收；静态结论不代表已闭环。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-641/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-649 合并库密钥文件在主线程无上限读取且失败静默降级

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：密钥文件URI重载由按钮回调同步进入presenter，直接openInputStream().readBytes()，没有IO调度或累计上限；catch Throwable后用null继续提交，读取失败被解释成未提供密钥因子。
- 影响与触发：慢SAF提供者或巨大/未知长度文件可阻塞主线程、引发内存压力；错误被延后呈现为解锁失败且用户选定因子未兑现。该入口不同于已加上限的附件/漂移读取。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsImportPresenter.kt:71`；`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsViewModel.kt:127`。
- 已实现：无，本轮仅审查和编制方案。尚未完成：原路径产品复现、实现、失败对照及相应运行验收；静态结论不代表已闭环。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-649/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-651 同步配置保存失败后输入显示与已擦除凭据副本不一致

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：表单把本地password/accessKey/secretKey数组直接传给controller；后者在HTTPS拒绝及保存收尾均清零。UI只在成功时替换/清空本地状态，失败时输入框仍显示已输入而提交数组已全零。修正端点再次提交会被isAllZero持续拒绝。
- 影响与触发：WebDAV/S3配置错误后难以重试，输入状态误导用户，须重新键入秘密才能恢复；不应通过停止所有清理来修复。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/CloudSyncScreen.kt:160`；`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsSyncController.kt:244`。
- 已实现：无，本轮仅审查和编制方案。尚未完成：原路径产品复现、实现、失败对照及相应运行验收；静态结论不代表已闭环。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-651/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-652 测试连接忽略配置保存失败，仍验证并同步旧目标

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：SyncStatusCard.onTestConnection先persistConfig()但不检查返回值，无条件onSyncAfterSave。与同页保存并同步按钮的if守卫不同，保存失败时后续流程使用仍在存储中的旧配置。
- 影响与触发：测试的并非用户当前输入目标，且回调还会同步旧目标；用户可能把旧连接成功当作新表单验证，产生不期望的同步副作用。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/CloudSyncScreen.kt:402`；`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsSyncController.kt:244`。
- 已实现：无，本轮仅审查和编制方案。尚未完成：原路径产品复现、实现、失败对照及相应运行验收；静态结论不代表已闭环。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-652/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-655 S3目录参数先拼原文再拆分，含&前缀导致签名不一致

- 核实：2026-10-16（Asia/Shanghai），当前源码与协议消费链；静态确认；置信度：高。
- 问题：目录列举将原始queryPairs拼成signQuery，值内&被signer当成参数分隔；wire URL则先编码值。prefix=foo&bar/因此在线上是一个参数，签名里却被拆为两项，canonical request不一致。
- 影响与触发：含&的合法S3目录前缀无法浏览；若服务返回此类续页token，翻页亦失败。普通参数不受影响，不是凭据泄漏。 复现与反证详见方案。
- 位置：`sync/src/main/java/com/keepasskey/sync/s3/S3DirectoryList.kt:48`；`sync/src/main/java/com/keepasskey/sync/s3/S3RequestSigner.kt:110`。
- 已实现（2026-10-17）：`S3DirectoryList` 与 `S3RequestSigner` 改为**结构化键值表**——`S3RequestSigner.canonicalQueryString` 对键与值分别按 SigV4 编码、按键（同键按值）排序，wire URL 与签名 canonical query 消费**同一份输出**；不再存在「先拼串再拆」这一步。JVM：`S3QueryCanonicalizeTest` 6 例 + `S3DirectoryListMetadataTest` 7 例（`&` / `=` / `%` / `+` / 空格 / Unicode / 重复键；含「含 `&` 前缀不得改变参数个数」与「wire query 与 canonical 逐字一致」断言），0 失败。
- 尚未完成：① 真实 S3 服务端到端（含服务端返回含 `&` 的续页 token）；② 独立 AWS 签名向量对拍。本环境无 S3 凭据、无隔离 S3 服务，故只做到 wire 与 canonical 的逐字一致性断言（JVM）——静态与 JVM 结论不代替该项。
- 关闭条件（方案 §4 / §5）：原触发（含 `&` 前缀）与正常 / 失败对照在**隔离 S3 服务**上复现通过，且独立签名向量一致；仅 JVM 层对照不足以结案。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-655/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-659 漂移合并**不合并库级 Meta**：外部工具改动的库名 / 描述 / 默认用户名 / 自定义数据被静默覆盖

- 核实：2026-10-16（Asia/Shanghai），`ISSUE-P1-631` 第三轮审计；静态确认 + 落盘产物逐字段核对；置信度：高。
- 问题：漂移合并产物为 `localDb.copy(rootGroup=…, deletedObjects=…, customIcons=…)`——库级 Meta（库名 / 描述 / 默认用户名 / 颜色 / 自定义数据 / 回收站配置 / 历史维护参数 / 库级 `customData`）**一律取本地**，`KdbxMerger` 无 Meta 合并面。外部工具（KeePassXC / 官方桌面端）对 Meta 的改动，在用户选择「合并后保存」并写盘后被**永久覆盖且无任何提示**。
- 影响与触发：多工具协同同一库文件时，外部端的库名 / 描述 / 默认用户名 / 自定义数据改动会丢失；本地一侧的 Meta **不会**丢失（写盘的就是本地库）。触发序列：外部工具改 Meta → 本机继续编辑条目 → 保存触发漂移 → 选「合并后保存」。
- 定级与处置（第三轮结论）：**P2**（非 P1）。理由：①与本 P1 非同一因果链（即便基线完全真实也照旧发生）；②损失方向与 P1 相反且单向（丢的是外部那一次改动，本地 Meta 永不丢）；③与导入合并 / 同步路径**同源**（`KdbxMergeController` 同形），只改漂移会造出第三套 Meta 口径；④修复需先定义 `recycleBinUuid` / `entryTemplatesGroup` / `masterKeyChanged` 等**不可合并身份字段**的白 / 黑名单，且 Meta 为标量、「双方保留」无对应语义，需要第三类裁决面——**已按此落地**：身份字段清单收敛到 `KdbxMetaMerge.IDENTITY_FIELDS_NOTE`，标量冲突进同步冲突页「库设置」卡片逐字段裁决。限界 §47 已由「不合并」改写为「已合并（JVM 级验证）」。
- 位置：`sync/src/main/java/com/keepasskey/sync/merge/KdbxMetaMerge.kt`（合并与重裁）；`sync/.../KdbxMerger.kt`（`MergeResult.mergedMeta` / `metaConflicts`）；`app/src/main/java/com/keepasskey/app/sync/KdbxMetaApplication.kt`（镜像与落库映射）；`app/.../data/repository/VaultFileDriftResolve.kt`、`app/.../sync/SyncConflictAutoMerge.kt`、`app/.../sync/SyncConflictResolution.kt`（三处落库）；`app/.../data/importer/KdbxMergeOutcomeFactory.kt`（导入不提供 Meta 的语义）。
- 已实现（2026-10-17）：新增 `KdbxMetaMerge` / `KdbxMetaLite` / `MetaFieldConflict`（`sync` 合并面）；`KdbxDatabaseLite` 与 `MergeResult` 携带 Meta 面。逐字段三方合并：两侧同值 / 单侧等于 base 取变更侧 / 双侧异值按该字段**元数据时间戳**（较新者，一方缺失视为更旧）/ 时间戳不可区分则登记冲突并临时取本地。身份字段**不合并**、恒取本地（加密头、`recycleBin*`、`entryTemplatesGroup*`、`masterKey*`、`lastSelectedGroup`、`lastTopVisibleGroup`、`generator`、`memoryProtection`、图标池与二进制池；清单见 `KdbxMetaMerge.IDENTITY_FIELDS_NOTE`）。落库：漂移（`withMergedMeta`）、同步自动合并、同步用户裁决（`pendingMergedMeta`）三条路径同批采用；同步冲突页新增「库设置」卡片（字段级本地 / 云端）。`.kdbx` 导入**显式不提供 Meta**（产品语义：导入不改当前库身份），已写入 `KdbxMergeOutcomeFactory` 注释。JVM：`KdbxMetaMergeTest` 14 例 0 失败（含「外部单侧改库名必须合入」「无时间戳双侧异值登记冲突」「无底版时 customData 键并集」）。
- 尚未完成：设备端产品路径实跑（外部工具改 Meta → 漂移 / 同步合并 → 落盘后重开逐字段核对）。限界 §47 已按代码事实改写为「已合并（JVM 级验证）」，设备端结论是该条目的剩余项。
- 关闭条件：上述产品路径在设备上实跑通过（合并后重开，库名 / 描述 / 默认用户名 / 颜色 / 库级 customData 与外部改动一致，本地未改字段不变），并同步更新限界 §47 的验证层级。
- [独立整改方案](architecture/问题整改方案/P1/ISSUE-P1-631/整改方案.md)：本项由该方案第三轮审计发现，详见其「实施记录」§9.3。

### ISSUE-P2-660 同步（云端）路径的**分组**同字段双侧异值仍为字段级 LWW，不进冲突面

- 核实：2026-10-16（Asia/Shanghai），`ISSUE-P1-631` 第三轮审计；静态确认；置信度：高。
- 问题：合并器现已产出 `MergeResult.groupConflicts`，**漂移面**已据此阻断并裁决；但 `sync` 路径的消费方只读 `MergeResult.conflicts`（条目级），**不读** `groupConflicts` ⇒ 云端同步时分组同字段双侧异值仍由 `KdbxGroupFieldMerge` 按 LWW 静默定案，用户无任何提示。
- 影响与触发：两端各自修改同一分组的组名 / 备注 / 图标 / 父组 / 组级自定义数据且取值不同时，一端修改被静默丢弃。触发序列：设备 A 改分组名 → 设备 B 改同一分组名为另一值 → 任一端同步。
- 定级：P2（与漂移面同源同型；漂移面已证成可行，同步面照同一条路即可）。范围说明见限界 §46（第三轮已把「分组级差异不产生冲突清单」的旧边界改写为「漂移面已修 / 同步面仍 LWW」）。
- 位置：`sync/src/main/java/com/keepasskey/sync/merge/KdbxGroupFieldMerge.kt`；`sync/.../KdbxMerger.kt`（`MergeResult.groupConflicts`）；`app/src/main/java/com/keepasskey/app/sync/SyncConflictController.kt`（决策清单与 `groupConflictFlow`）；`app/.../sync/SyncGroupConflictResolution.kt`（与漂移面共用的落树原语）。
- 已实现（2026-10-17）：`SyncConflictDecision` 携带 `entries` / `groups` / `meta`，`handleConflictMerge` 任一非空即进待决（不再只判条目）；新增 `groupConflictFlow` 独立通道，列表页冲突入口与冲突页同时消费。`resolveConflicts` 增加 `groupResolutions`，落树复用与漂移面**同一实现** `applyGroupConflictChoices`（只重裁差异字段；「双方保留」由 `KdbxGroupDuplicator` 复制整棵子树并重生成 UUID）；冲突页新增分组卡片（整组取本地 / 整组取云端 / 双方保留），副本后缀为「云端冲突副本」（与漂移面「磁盘冲突副本」区分）。JVM：`SyncGroupConflictResolutionTest` 7 例 0 失败（含多组同批裁决、目标缺失与根组 fail-closed）。
- 尚未完成：① **设备端 UI 驱动验收**——两端各改同一分组 → 冲突页逐组裁决 → 上传 → 另一端同步回读；真机 Compose 点击级路径未驱动。② 分组「双方保留」在**多端循环**（A 上传副本、B 再同步）下的稳定性未实测。JVM 与代码层结论不代替这两项。
- 关闭条件：同步消费方读 `groupConflicts` 并复用 `KdbxGroupConflictResolver` / `KdbxGroupDuplicator`，且限界 §46 收窄为「已对齐」，**并补设备端验收**（本项原条件未写设备端，按台账口径补齐）。

## P3 低危问题、特性接线与体验优化
### ISSUE-P3-642 原生Passkey候选在请求RP不匹配时仍走包名回退

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认协议错配；不认定新增跨RP权限；置信度：高。
- 问题：PD-77规定rpId缺失/空白才退回包名维度，但native候选始终domainMatch||packageMatch。最终上下文未保留请求rpId，已绑定app即使请求RP-B也能选到RP-A并生成A的响应。
- 影响与触发：错误候选、被正常RP拒绝的断言和不必要计数写入；同一已绑定app本来可直接请求A，因此不把它定成新跨RP登录漏洞。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/passkey/CredentialCandidateMatcher.kt:57`；`app/src/main/java/com/keepasskey/app/passkey/PasskeyAssertionRequest.kt:104`。
- 已实现：无，本轮仅审查和编制方案。尚未完成：原路径产品复现、实现、失败对照及相应运行验收；静态结论不代表已闭环。
- [独立整改方案](architecture/问题整改方案/P3/ISSUE-P3-642/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P3-643 重复选择自定义图标会清掉其它未保存编辑的脏标记

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：onCustomIconSelected直接设置isDirty=(旧customIconId!=新id)，没有保留旧isDirty。用户已编辑标题/密码后选择当前相同图标，或连续选择同一新图标，脏位变false。
- 影响与触发：离页不弹丢弃确认，未保存修改容易丢失；未保存编辑登记也会错误认为没有草稿。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditViewModel.kt:307`；`app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditScreen.kt:204`。
- 已实现：无，本轮仅审查和编制方案。尚未完成：原路径产品复现、实现、失败对照及相应运行验收；静态结论不代表已闭环。
- [独立整改方案](architecture/问题整改方案/P3/ISSUE-P3-643/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P3-644 分组图标保存失败仍报成功，只读菜单缺少可执行性反馈

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：可写会话改分组图标丢弃saveGroup结果并无条件提示成功。只读菜单仍显示重命名/换图标/删除及批量写入口，但控制器在launch前return，点击无反馈；只读不会误报这条成功。
- 影响与触发：磁盘失败或漂移拒绝时用户误认为图标已保存；只读用户面对不能执行的操作。没有绕过底层只读写保护证据。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListActionController.kt:231`；`app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultGroupRow.kt:130`。
- 已实现：无，本轮仅审查和编制方案。尚未完成：原路径产品复现、实现、失败对照及相应运行验收；静态结论不代表已闭环。
- [独立整改方案](architecture/问题整改方案/P3/ISSUE-P3-644/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P3-646 KDBX解密探针与内联附件解压的短命明文缓冲未清理

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认缓冲生命周期缺口；置信度：高。
- 问题：解密探针累计首块到普通ByteArrayOutputStream后复制prefix，buffer/内部数组/prefix没有finally清理；内联压缩附件gunzip亦保留普通输出缓冲和拷贝buffer。它们属于解析临时独占副本，不是交付给活动库的唯一数据。
- 影响与触发：函数返回后明文副本等待GC，探针可含内层key/首块未保护字段，附件路径含明文附件。未建立远程读取或无权限获取堆的利用链；不把生命周期缺口等同已发生泄露。 触发序列及反证详见方案。
- 位置：`database/src/main/java/com/keepasskey/database/file/KdbxCipherKeyResolver.kt:124`；`database/src/main/java/com/keepasskey/database/xml/KdbxXmlBinaryNode.kt:169`。
- 已实现：无，本轮仅审查和编制方案。尚未完成：原路径产品复现、实现、失败对照及相应运行验收；静态结论不代表已闭环。
- [独立整改方案](architecture/问题整改方案/P3/ISSUE-P3-646/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P3-647 CXF通行密钥导入遗留UTF-8工作字节与JSON副本

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认缓冲生命周期缺口；置信度：高。
- 问题：begin直接把toUtf8Bytes结果传解析器，只擦decoded。scanner每个字符串的ByteSink复制后未擦内部数组；reader不递归清理完整AST，只清已选秘密节点。多凭据、未知字段与失败分支的私钥/PRF/其它秘密副本可留下。
- 影响与触发：最多4KiB载荷的可擦中间副本驻留到GC，违背本类KDoc和所有权契约；未证明跨进程/远程秘密泄露，不使用真实载荷做复现。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/passkey/PasskeyImportDraftHost.kt:51`；`app/src/main/java/com/keepasskey/app/passkey/ByteJsonScanner.kt:138`。
- 已实现：无，本轮仅审查和编制方案。尚未完成：原路径产品复现、实现、失败对照及相应运行验收；静态结论不代表已闭环。
- [独立整改方案](architecture/问题整改方案/P3/ISSUE-P3-647/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P3-648 生成器异步结果缺少代次校验

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态竞态候选，需要进一步验证；置信度：中。
- 问题：每次参数变化按快照启动独立Default计算，完成后直接更新currentPassword/entropy/history，没有取消旧任务或核对代次。较早请求迟到可能覆盖当前设置对应结果；锁定清理后迟到任务重新发布也需核实。
- 影响与触发：可能出现结果长度/模式与设置不符或锁定后重挂新生成值；未跑确定性乱序复现，不定性为已发生泄露。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/ui/screens/generator/GeneratorViewModel.kt:173`；`app/src/main/java/com/keepasskey/app/ui/screens/generator/GeneratorViewModel.kt:222`。
- 已实现：无，本轮仅审查和编制方案。尚未完成：确定性复现与反证收敛、实现、失败对照及相应运行验收；静态结论不代表已闭环。
- [独立整改方案](architecture/问题整改方案/P3/ISSUE-P3-648/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P3-650 本地同步联调服务默认全接口监听且认证凭据公开固定

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认，网络可达性未实测；置信度：高。
- 问题：一键MinIO路径把源码固定S3凭据放入root环境并用:9000/:9001监听全部接口；单独WebDAV默认0.0.0.0和仓内固定测试认证，目录可写。文档称仅本机，但手动命令同样全接口。
- 影响与触发：运行联调工具且主机防火墙允许时，可路由局域网访问者能凭公开测试认证读写测试对象。默认没有真实用户数据证据，不影响Android生产APK。 触发序列及反证详见方案。
- 位置：`tools/local-sync/run_lab.py:94`；`tools/local-sync/run_webdav.py:16`。
- 已实现：无，本轮仅审查和编制方案。尚未完成：原路径产品复现、实现、失败对照及相应运行验收；静态结论不代表已闭环。
- [独立整改方案](architecture/问题整改方案/P3/ISSUE-P3-650/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P3-653 使用品牌调色盘按钮未清除残留自定义种子色

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：自定义种子色保留时开启动态取色，再点使用品牌调色盘，路由只setDynamicColorEnabled(false)。仓库该setter不清seed，resolveColorSource回到CUSTOM，品牌项继续禁用。
- 影响与触发：一键切回品牌配色承诺失效，用户仍停留自定义颜色；不涉及秘密或数据库内容。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/ui/KeePasskeySettingsDisplayNavGraphRoutes.kt:35`；`app/src/main/java/com/keepasskey/app/data/repository/RealSettingsRepository.kt:168`。
- 已实现：无，本轮仅审查和编制方案。尚未完成：原路径产品复现、实现、失败对照及相应运行验收；静态结论不代表已闭环。
- [独立整改方案](architecture/问题整改方案/P3/ISSUE-P3-653/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P3-654 crypto的PRF与CBC兜底临时秘密副本未确定清零

- 核实：2026-10-16（Asia/Shanghai），当前源码调用链；静态确认缓冲生命周期缺口；置信度：高。
- 问题：PRF secretToProtected新建Base64 CharArray交给只读借用构造器后未擦原件；stripWhitespace缩短数组后未擦旧out。CBC堆兜底尾块在head非空时用head+stripped复制到out，但旧head/stripped明文数组未擦。
- 影响与触发：PRF秘密文本和CBC末段明文的可擦短命副本等待GC。CBC仅堆兜底路径且head非空时成立；不据此推导远程、跨进程读取或密码学算法失效。
- 位置：`crypto/src/main/java/com/keepasskey/crypto/passkey/PasskeyPrf.kt:124`；`crypto/src/main/java/com/keepasskey/crypto/cipher/CbcDecryptingInputStream.kt:286`。
- 已实现：无，本轮仅审查和编制方案。尚未完成：工作缓冲观测、修复及正常/失败/提前退出验证。
- [独立整改方案](architecture/问题整改方案/P3/ISSUE-P3-654/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P3-656 S3目录XML修改时间被按HTTP日期解析而丢失

- 核实：2026-10-16（Asia/Shanghai），当前源码与协议消费链；静态确认；置信度：高。
- 问题：ListObjects XML的LastModified使用ISO时间，但S3DirectoryList调用只认RFC1123的S3HttpDateCodec，解析失败回0。仓内fixture已含2026-10-06T00:00:00.000Z却未断言时间字段。
- 影响与触发：S3目录列表元数据中的真实修改时间丢失，依赖该接口的展示或排序不能得到正确值；不外推为同步ETag失效。 复现与反证详见方案。
- 位置：`sync/src/main/java/com/keepasskey/sync/s3/S3DirectoryList.kt:135`；`sync/src/main/java/com/keepasskey/sync/s3/S3HttpDateCodec.kt:15`。
- 已实现（2026-10-17）：新增 `S3XmlTimeCodec`（ISO-8601：`Instant` / `OffsetDateTime` / 无时区按 UTC），`S3DirectoryList` 的 `<LastModified>` 改走该解析器；HTTP 响应头仍走 `S3HttpDateCodec`（RFC 1123，协议归口不混用）。JVM：fixture 时间断言为 `1791244800000`、无小数秒与带 `+08:00` 偏移同值、非法值降级 0、HTTP 解析器不认 ISO，共 7 例 0 失败。
- 尚未完成：① 真实 S3 服务端到端（目录列表回读 `<LastModified>`，含 `&` 前缀目录的分页回执）；② 设备端未跑（本项为纯解析改动，未新增/修改 `androidTest`）。模型层 `0 = 未知` 与合法 1970 同值属既有字段边界，本项不改变该契约。
- [独立整改方案](architecture/问题整改方案/P3/ISSUE-P3-656/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P3-657 S3签名密钥级联派生在中途异常时遗漏清理

- 核实：2026-10-16（Asia/Shanghai），当前源码与协议消费链；静态确认异常出口缺口；置信度：高（控制流）；异常触发概率低。
- 问题：kDate/kRegion/kService在第一个try内依次生成，该finally只清kSecret；清前三级的第二个try只有全部派生完成后才进入。中间HMAC抛异常时，已分配派生数组跳过清理。
- 影响与触发：异常路径的可擦派生秘密等待GC；正常路径已有清理，真实HMAC异常/资源错误才触发。无外部未授权读取内存证据，不是签名密钥直接外传。 复现与反证详见方案。
- 位置：`sync/src/main/java/com/keepasskey/sync/s3/S3RequestSigner.kt:174`。
- 已实现（2026-10-17）：三个中间派生量改为可空持有、逐级「先赋值再使用」，并登记在**同一最外层 finally** 的擦除面上；派生链抽为可注入 HMAC 的 `deriveSigningKeyChain`（正常与故障共用同一清理路径）。JVM：`S3SigningKeyDerivationTest` 5 例（第 2/3/4 步受控异常 + 成功路径不擦最终 key + 空 kSecret），0 失败。
- 尚未完成：① 真实服务签名端到端（既有签名场景用例保持全绿，不代替该项）；② 时钟偏移重签路径（`S3ClockSkewGuard`）在异常清理改动后的实跑未覆盖；③ 设备端未跑。
- [独立整改方案](architecture/问题整改方案/P3/ISSUE-P3-657/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。
