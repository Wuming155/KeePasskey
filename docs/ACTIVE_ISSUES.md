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

### ISSUE-P2-655 S3目录参数先拼原文再拆分，含&前缀导致签名不一致

- 核实：2026-10-16（Asia/Shanghai），当前源码与协议消费链；静态确认；置信度：高。
- 问题：目录列举将原始queryPairs拼成signQuery，值内&被signer当成参数分隔；wire URL则先编码值。prefix=foo&bar/因此在线上是一个参数，签名里却被拆为两项，canonical request不一致。
- 影响与触发：含&的合法S3目录前缀无法浏览；若服务返回此类续页token，翻页亦失败。普通参数不受影响，不是凭据泄漏。 复现与反证详见方案。
- 位置：`sync/src/main/java/com/keepasskey/sync/s3/S3DirectoryList.kt:48`；`sync/src/main/java/com/keepasskey/sync/s3/S3RequestSigner.kt:110`。
- 已实现（2026-10-17）：`S3DirectoryList` 与 `S3RequestSigner` 改为**结构化键值表**——`S3RequestSigner.canonicalQueryString` 对键与值分别按 SigV4 编码、按键（同键按值）排序，wire URL 与签名 canonical query 消费**同一份输出**；不再存在「先拼串再拆」这一步。JVM：`S3QueryCanonicalizeTest` 6 例 + `S3DirectoryListMetadataTest` 7 例（`&` / `=` / `%` / `+` / 空格 / Unicode / 重复键；含「含 `&` 前缀不得改变参数个数」与「wire query 与 canonical 逐字一致」断言），0 失败。
- 验证（2026-10-18）：串行运行 `:sync:testDebugUnitTest --tests S3QueryCanonicalizeTest --tests S3DirectoryListMetadataTest`，13 例、0 失败。
- 尚未完成：① 真实 S3 服务端到端（含服务端返回含 `&` 的续页 token）；② 独立 AWS SigV4 canonical-query 签名向量对拍。本机无 docker/podman/MinIO 命令，9000/9001/9443 无监听；离线 JVM 逐字一致性不能替代服务端验收。
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
- 已实现：无，本轮仅审查和编制方案。尚未完成：原路径产品复现、实现、失败对照及相应运行验收；静态结论不代表已闭环。
- [独立整改方案](architecture/问题整改方案/P3/ISSUE-P3-642/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P3-650 本地同步联调服务默认全接口监听且认证凭据公开固定

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认，网络可达性未实测；置信度：高。
- 问题：一键MinIO路径把源码固定S3凭据放入root环境并用:9000/:9001监听全部接口；单独WebDAV默认0.0.0.0和仓内固定测试认证，目录可写。文档称仅本机，但手动命令同样全接口。
- 影响与触发：运行联调工具且主机防火墙允许时，可路由局域网访问者能凭公开测试认证读写测试对象。默认没有真实用户数据证据，不影响Android生产APK。 触发序列及反证详见方案。
- 位置：`tools/local-sync/run_lab.py:94`；`tools/local-sync/run_webdav.py:16`。
- 已实现：无，本轮仅审查和编制方案。尚未完成：原路径产品复现、实现、失败对照及相应运行验收；静态结论不代表已闭环。
- [独立整改方案](architecture/问题整改方案/P3/ISSUE-P3-650/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。
