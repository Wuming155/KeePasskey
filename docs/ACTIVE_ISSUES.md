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

### ISSUE-P2-636 同步缓存与版本标记分离提交可在重启后误判无修改

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认，崩溃矩阵待实跑；置信度：高（false-clean分支）；后续覆盖后果待验证。
- 问题：writeCache先替换.cache，再独立更新.version。两步之间中断会留下新字节与旧/缺失version；hasLocalChanges仅比较version/baseversion，缺失version直接false。冷启内容比较以当前库对新cache，两者相同仍判UNCHANGED，无法修复旧标记。
- 影响与触发：已保存本地内容可能不上传，远端长期落后；远端后续变化时有覆盖本地新内容的风险。不能表述为崩溃立即损坏正式KDBX。 触发序列及反证详见方案。
- 位置：`sync/src/main/java/com/keepasskey/sync/engine/SyncCache.kt:126`；`sync/src/main/java/com/keepasskey/sync/engine/SyncCacheMaintenance.kt:29`。
- 已实现（2026-10-19）：`hasLocalChanges` 以 `.cache` 实际字节摘要对照 `.baseversion`，缓存替换与 `.version` 更新间崩溃时不再因旧/缺失标记误判 clean。尚未完成：同步缓存故障注入与重启矩阵、受影响单测运行。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-636/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-637 前台闲置锁定任务未保存句柄，关闭或重排无法取消旧定时器

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：scheduleForegroundIdleLock取消foregroundLockJob后启动scope.launch，却没有把返回Job赋给该字段。所有取消点实际拿不到已启动任务；延迟结束又使用捕获的旧settings，不重新检查开关/前后台状态。
- 影响与触发：关闭前台锁定、调长超时或转后台后旧计时器仍可能锁库；连续触摸积累多个定时任务。此路径更可能提前锁定，不支持“绕过锁定泄露”的结论。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/security/AutoLockManager.kt:115`。
- 已实现（2026-10-19）：保存前台闲置任务句柄以便取消；延迟到期后重新读取当前设置并核对前后台/锁态。尚未完成：虚拟时钟回归用例与设置重排矩阵运行。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-637/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-638 恢复副本差异比较忽略密码和实际附件内容

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：恢复预览的fingerprint只比title/userName/url/notes/tags及附件/历史数量，未比较密码、OTP、受保护自定义字段、Passkey材料或附件字节。内容改变但数量相同可显示“修改0”。
- 影响与触发：恢复/覆盖/丢弃前的决策依据失真，尤其密码单独变化、历史已达容量上限或禁用历史的库；不意味着系统自动删除副本。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/security/VaultRecoveryCoordinator.kt:211`。
- 已实现（2026-10-19）：差异比较使用字段内容等值（不解密为字符串），并比较自定义字段、附件字节、历史及条目元数据。尚未完成：密码/OTP/Passkey/附件同数量变化的预览回归用例运行。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-638/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-639 恢复预览迟到结果未绑定所选副本

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认，交错场景待实跑；置信度：高。
- 问题：预览A期间列表项仍可点击切换B；select清空preview，但A异步完成时无请求代次/selectedKey比较，直接把A预览写入当前B状态。restore只检查当前选中B及preview非空，可能用A的成功预览放行恢复B。
- 影响与触发：显示和恢复前置校验目标错配，用户可能未解锁/比较B就覆盖其正式库；不表示能解密B或绕过KDBX密码学。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/ui/screens/recovery/RecoveryViewModel.kt:67`；`app/src/main/java/com/keepasskey/app/ui/screens/recovery/VaultRecoveryDialog.kt:112`。
- 已实现（2026-10-19）：恢复预览增加请求代次与所选副本键复核；切换副本会使旧预览失效。尚未完成：A 慢/B 快交错与旧结果不得放行恢复的确定性用例运行。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-639/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-640 明文批量导入缺少目标库与会话代次绑定

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；确认静态缺口，跨库后果需要进一步验证；置信度：中。
- 问题：导入generation只防止旧进度覆盖UI；persist开头只检查一次isLocked，后续各条saveEntry/saveGroup面向当前活动单例，没有捕获目标path/session ticket。锁库/换库/重新解锁交错下，剩余条目可能写入另一个库。
- 影响与触发：可能产生跨库错投或部分导入；目前只有缺少目标绑定的静态证据，实际UI/锁定交错需确定性验证，不宣称已发生数据泄露。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/ui/screens/importer/VaultImportController.kt:91`；`app/src/main/java/com/keepasskey/app/data/importer/VaultImporter.kt:81`。
- 已实现（2026-10-20）：在接受文件选择时固定会话请求，分组、条目、软删与正式保存均在原会话互斥锁内复核票据；会话变化立即停止余下导入，分别报告已写入、未处理及回收站归位未完成条目。运行证据：已通过导入/会话 JVM 回归及 A→B 真实 CSV/生产仓库 AVD 1/1；尚未完成取消再启动、进程终止与完整设备矩阵，见独立方案。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-640/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-641 CM Passkey与创建保存入口未消费已登记的库代次上下文

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；确认静态缺口，旧入口设备影响待验证；置信度：高（遗漏）；中（系统旧入口后果）。
- 问题：构造入口时已登记databaseId/sessionTicket，但只有PasswordFill消费并最终复核。Passkey断言/创建及密码保存/草稿读取当前活动库；BaseCredentialActivity仅做窗口保护，没有补偿票据检查。
- 影响与触发：旧A入口在活动库变B后可能对B同UUID条目计数/签名或把创建数据写入B，造成账户/库上下文混淆。需用户选择与验证；未证明任意应用、静默或跨RP泄漏。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/passkey/CredentialResponseAssembler.kt:370`；`app/src/main/java/com/keepasskey/app/passkey/PasskeyAssertionActivity.kt:102`。
- 已实现（2026-10-20）：四类 CM 入口消费已登记的随机请求上下文，绑定库路径和会话票据；实际改树、落盘及成功回传均复核原票据。锁定时无票据的创建入口仅允许解锁登记的原路径后绑定一次；传统 Autofill 保留独立令牌语义。运行证据：JVM 会话门控 2 例与 Android Intent/正式文件门控 1/1 均通过。尚未完成：真实 Credential Manager 四入口跨进程、认证中换库、相同 UUID 签名/计数与正式文件全矩阵。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-641/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-649 合并库密钥文件同步读取且失败静默降级

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：密钥文件URI重载由按钮回调同步进入presenter，直接openInputStream().readBytes()，没有IO调度或累计上限；catch Throwable后用null继续提交，读取失败被解释成未提供密钥因子。
- 影响与触发：慢SAF提供者或巨大/未知长度文件可阻塞主线程、引发内存压力；错误被延后呈现为解锁失败且用户选定因子未兑现。读取大小按既有用户裁决不设上限，见限界 §37；本项修复调度、反馈和清理，不改变合法密钥材料范围。触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsImportPresenter.kt:71`；`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsViewModel.kt:127`。
- 已实现（2026-10-20）：所选密钥文件改在受控并入作业中经生产 KeyFileAccess 的 IO 通道读取；空、不可读或读取异常明确停止并入，取消接入并入作业，工作缓冲与取消交接未交付结果清零。按既有用户裁决和限界 §37 保留合法大密钥文件无大小上限的完整读取。尚未完成：完整 SAF 异常/慢流/虚报 SIZE 矩阵及实际凭据表单 UI 验收；已通过密钥读取/所有权 JVM 回归及 AVD 不可读拒绝、2.28 MB 完整读取 2/2，见独立方案。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-649/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-651 同步配置保存失败后输入显示与已擦除凭据副本不一致

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：表单把本地password/accessKey/secretKey数组直接传给controller；后者在HTTPS拒绝及保存收尾均清零。UI只在成功时替换/清空本地状态，失败时输入框仍显示已输入而提交数组已全零。修正端点再次提交会被isAllZero持续拒绝。
- 影响与触发：WebDAV/S3配置错误后难以重试，输入状态误导用户，须重新键入秘密才能恢复；不应通过停止所有清理来修复。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/CloudSyncScreen.kt:160`；`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsSyncController.kt:244`。
- 已实现（2026-10-19）：配置保存向控制器传入独立秘密副本；失败清零控制器所有权副本但保留 UI 输入以便修正重试，成功时再清 UI 副本。尚未完成：保存失败、重试与成功清理路径验证。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-651/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-652 测试连接忽略配置保存失败，仍验证并同步旧目标

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：SyncStatusCard.onTestConnection先persistConfig()但不检查返回值，无条件onSyncAfterSave。与同页保存并同步按钮的if守卫不同，保存失败时后续流程使用仍在存储中的旧配置。
- 影响与触发：测试的并非用户当前输入目标，且回调还会同步旧目标；用户可能把旧连接成功当作新表单验证，产生不期望的同步副作用。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/CloudSyncScreen.kt:402`；`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsSyncController.kt:244`。
- 已实现（2026-10-19）：测试连接仅在 `persistConfig()` 返回成功后才继续测连/同步。尚未完成：保存失败时不触发测试/同步的 UI 回归运行。
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
- 已实现（2026-10-19）：请求含明确 RP ID 时只接受 RP 域匹配；仅 RP 缺失时才允许按已绑定包名回退。尚未完成：协议匹配正反用例运行及设备端旧入口验收。
- [独立整改方案](architecture/问题整改方案/P3/ISSUE-P3-642/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P3-643 重复选择自定义图标会清掉其它未保存编辑的脏标记

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：onCustomIconSelected直接设置isDirty=(旧customIconId!=新id)，没有保留旧isDirty。用户已编辑标题/密码后选择当前相同图标，或连续选择同一新图标，脏位变false。
- 影响与触发：离页不弹丢弃确认，未保存修改容易丢失；未保存编辑登记也会错误认为没有草稿。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditViewModel.kt:307`；`app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditScreen.kt:204`。
- 已实现（2026-10-19）：重复选择图标保留既有表单脏标记，只有此前未脏且图标未变时才保持 clean。尚未完成：标题/密码未保存后重复选择当前图标的 UI 状态回归运行。
- [独立整改方案](architecture/问题整改方案/P3/ISSUE-P3-643/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P3-644 分组图标保存失败仍报成功，只读菜单缺少可执行性反馈

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：可写会话改分组图标丢弃saveGroup结果并无条件提示成功。只读菜单仍显示重命名/换图标/删除及批量写入口，但控制器在launch前return，点击无反馈；只读不会误报这条成功。
- 影响与触发：磁盘失败或漂移拒绝时用户误认为图标已保存；只读用户面对不能执行的操作。没有绕过底层只读写保护证据。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListActionController.kt:231`；`app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultGroupRow.kt:130`。
- 已实现（2026-10-19）：分组图标保存根据仓库结果反馈成功/失败；只读会话触发分组/条目写操作时给出明确拒绝反馈。尚未完成：只读菜单点击与存盘失败的 UI 验收运行。
- [独立整改方案](architecture/问题整改方案/P3/ISSUE-P3-644/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P3-646 KDBX解密探针与内联附件解压的短命明文缓冲未清理

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认缓冲生命周期缺口；置信度：高。
- 问题：解密探针累计首块到普通ByteArrayOutputStream后复制prefix，buffer/内部数组/prefix没有finally清理；内联压缩附件gunzip亦保留普通输出缓冲和拷贝buffer。它们属于解析临时独占副本，不是交付给活动库的唯一数据。
- 影响与触发：函数返回后明文副本等待GC，探针可含内层key/首块未保护字段，附件路径含明文附件。未建立远程读取或无权限获取堆的利用链；不把生命周期缺口等同已发生泄露。 触发序列及反证详见方案。
- 位置：`database/src/main/java/com/keepasskey/database/file/KdbxCipherKeyResolver.kt:124`；`database/src/main/java/com/keepasskey/database/xml/KdbxXmlBinaryNode.kt:169`。
- 已实现（2026-10-19）：解密探针与附件解压改用可擦输出流，清零工作缓冲、内部扩容缓冲及探针前缀副本。尚未完成：正常、结构失败与解压异常路径的缓冲擦除观测用例运行。
- [独立整改方案](architecture/问题整改方案/P3/ISSUE-P3-646/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P3-647 CXF通行密钥导入遗留UTF-8工作字节与JSON副本

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认缓冲生命周期缺口；置信度：高。
- 问题：begin直接把toUtf8Bytes结果传解析器，只擦decoded。scanner每个字符串的ByteSink复制后未擦内部数组；reader不递归清理完整AST，只清已选秘密节点。多凭据、未知字段与失败分支的私钥/PRF/其它秘密副本可留下。
- 影响与触发：最多4KiB载荷的可擦中间副本驻留到GC，违背本类KDoc和所有权契约；未证明跨进程/远程秘密泄露，不使用真实载荷做复现。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/passkey/PasskeyImportDraftHost.kt:51`；`app/src/main/java/com/keepasskey/app/passkey/ByteJsonScanner.kt:138`。
- 已实现（2026-10-19）：CXF 入口清零 UTF-8 工作载荷；JSON AST 提供递归清零，成功解析后清零所有字符串副本，字符串扫描工作缓冲在返回/拒收路径擦除。尚未完成：多凭据、未知字段与失败路径缓冲观测用例运行。
- [独立整改方案](architecture/问题整改方案/P3/ISSUE-P3-647/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P3-648 生成器异步结果缺少代次校验

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态竞态候选，需要进一步验证；置信度：中。
- 问题：每次参数变化按快照启动独立Default计算，完成后直接更新currentPassword/entropy/history，没有取消旧任务或核对代次。较早请求迟到可能覆盖当前设置对应结果；锁定清理后迟到任务重新发布也需核实。
- 影响与触发：可能出现结果长度/模式与设置不符或锁定后重挂新生成值；未跑确定性乱序复现，不定性为已发生泄露。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/ui/screens/generator/GeneratorViewModel.kt:173`；`app/src/main/java/com/keepasskey/app/ui/screens/generator/GeneratorViewModel.kt:222`。
- 已实现（2026-10-19）：生成请求增加代次；新参数请求或锁定清理会使在途旧结果失效，迟到的秘密结果立即清零。尚未完成：确定性乱序与锁定后完成用例运行。
- [独立整改方案](architecture/问题整改方案/P3/ISSUE-P3-648/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P3-650 本地同步联调服务默认全接口监听且认证凭据公开固定

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认，网络可达性未实测；置信度：高。
- 问题：一键MinIO路径把源码固定S3凭据放入root环境并用:9000/:9001监听全部接口；单独WebDAV默认0.0.0.0和仓内固定测试认证，目录可写。文档称仅本机，但手动命令同样全接口。
- 影响与触发：运行联调工具且主机防火墙允许时，可路由局域网访问者能凭公开测试认证读写测试对象。默认没有真实用户数据证据，不影响Android生产APK。 触发序列及反证详见方案。
- 位置：`tools/local-sync/run_lab.py:94`；`tools/local-sync/run_webdav.py:16`。
- 已实现（2026-10-19）：WebDAV 默认绑定 loopback 且不再有固定默认凭据；联调脚本按次生成凭据并令 MinIO API/Console 均只监听 loopback。尚未完成：本机与外部网络命名空间监听/认证负反例运行。
- [独立整改方案](architecture/问题整改方案/P3/ISSUE-P3-650/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P3-653 使用品牌调色盘按钮未清除残留自定义种子色

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：自定义种子色保留时开启动态取色，再点使用品牌调色盘，路由只setDynamicColorEnabled(false)。仓库该setter不清seed，resolveColorSource回到CUSTOM，品牌项继续禁用。
- 影响与触发：一键切回品牌配色承诺失效，用户仍停留自定义颜色；不涉及秘密或数据库内容。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/ui/KeePasskeySettingsDisplayNavGraphRoutes.kt:35`；`app/src/main/java/com/keepasskey/app/data/repository/RealSettingsRepository.kt:168`。
- 已实现（2026-10-19）：新增品牌调色盘原子偏好操作，同一事务清除自定义种子色并关闭动态取色。尚未完成：自定义种子色 + 动态取色开启状态下切回品牌调色盘的 UI/持久化回归运行。
- [独立整改方案](architecture/问题整改方案/P3/ISSUE-P3-653/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P3-654 crypto的PRF与CBC兜底临时秘密副本未确定清零

- 核实：2026-10-16（Asia/Shanghai），当前源码调用链；静态确认缓冲生命周期缺口；置信度：高。
- 问题：PRF secretToProtected新建Base64 CharArray交给只读借用构造器后未擦原件；stripWhitespace缩短数组后未擦旧out。CBC堆兜底尾块在head非空时用head+stripped复制到out，但旧head/stripped明文数组未擦。
- 影响与触发：PRF秘密文本和CBC末段明文的可擦短命副本等待GC。CBC仅堆兜底路径且head非空时成立；不据此推导远程、跨进程读取或密码学算法失效。
- 位置：`crypto/src/main/java/com/keepasskey/crypto/passkey/PasskeyPrf.kt:124`；`crypto/src/main/java/com/keepasskey/crypto/cipher/CbcDecryptingInputStream.kt:286`。
- 已实现（2026-10-19）：PRF 编码字符副本与 whitespace 缩短前数组在清理出口清零；CBC 堆兜底合并时清零 head/stripped 临时明文副本。尚未完成：正常、异常、提前退出下工作缓冲擦除观测用例运行。
- [独立整改方案](architecture/问题整改方案/P3/ISSUE-P3-654/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。
