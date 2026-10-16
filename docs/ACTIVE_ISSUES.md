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

### ISSUE-P1-631 外部漂移合并伪造共同基线，可能丢失本地新增和编辑

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：mergeAndSave 把当前 localDb 同时传作 base 与 local。合并器因此认为本地从未修改：本地新增条目在磁盘缺失时被推断为远端删除，同 ID 本地编辑则让磁盘版本成为唯一修改。随后采用并保存该结果。分组也受相同基线错误影响。
- 影响与触发：用户选择“合并后保存”仍可能丢失未提交本地条目/分组或恢复磁盘旧值；影响本地文件和 SAF 外部漂移处置，不代表普通三方同步均有此缺陷。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/data/repository/VaultFileDriftResolve.kt:141`；`sync/src/main/java/com/keepasskey/sync/merge/KdbxEntryMerger.kt:49`。
- 已实现：无，本轮仅审查和编制方案。尚未完成：原路径产品复现、实现、失败对照及相应运行验收；静态结论不代表已闭环。
- [独立整改方案](architecture/问题整改方案/P1/ISSUE-P1-631/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

## P2 中危缺陷与协议/测试缺口

### ISSUE-P2-632 历史版本以条目 UUID 定位，多个修订总命中首项

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：历史快照保留条目 UUID，投影又把该 UUID 当修订 id；读取历史密码/快照时使用 firstOrNull(id==revisionUuid)，故多条历史记录具有同一定位值，选择较早记录仍读取列表首个版本。
- 影响与触发：历史对比、历史密码和回滚可能选择错误版本；本仓自产历史和按同 UUID 保存的外部历史都受影响。 触发序列及反证详见方案。
- 位置：`database/src/main/java/com/keepasskey/database/history/HistoryManager.kt:46`；`app/src/main/java/com/keepasskey/app/data/repository/VaultEntryMapper.kt:56`。
- 已实现：无，本轮仅审查和编制方案。尚未完成：原路径产品复现、实现、失败对照及相应运行验收；静态结论不代表已闭环。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-632/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-633 历史回滚通过当前附件索引合并，不能恢复历史附件

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：回滚将历史投影传入通用saveEntry；历史附件投影只有refIndex/名称，没有历史字节来源。AttachmentMergePolicy按当前条目附件列表的同索引或同名项取数据，未命中时构造空附件。历史附件内容被当前内容替代或变空。
- 影响与触发：历史回滚显示成功却未恢复附件；同名附件、替换/删除/重新排序后尤其明显。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailRevisionController.kt:55`；`app/src/main/java/com/keepasskey/app/data/repository/VaultEntryMapper.kt:70`。
- 已实现：无，本轮仅审查和编制方案。尚未完成：原路径产品复现、实现、失败对照及相应运行验收；静态结论不代表已闭环。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-633/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-634 自定义字段删除和改名在保存时被补回旧键

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：UI删除字段会从customFields中移除，但mergeCustomFields无条件保留所有未出现在UI键集合中的旧字段。删除的旧键被补回；改名则保留旧键并新增新键。
- 影响与触发：用户不能真正删除普通/受保护自定义字段，可能误以为旧敏感信息已移除；不等于明文对外泄露。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditCustomFields.kt:74`；`app/src/main/java/com/keepasskey/app/data/repository/VaultEntryWriteCoordinator.kt:175`。
- 已实现：无，本轮仅审查和编制方案。尚未完成：原路径产品复现、实现、失败对照及相应运行验收；静态结论不代表已闭环。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-634/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P2-635 编辑页选择根分组被当成保留原分组

- 核实：2026-10-16（Asia/Shanghai），当前源码与定向调用链；静态确认；置信度：高。
- 问题：根分组chip传onGroupChange(null)，保存时却用 entry.groupId?.parse ?: existing.parentGroupId；null被解释成“不变”，因此已有条目无法通过编辑页移回根。
- 影响与触发：界面显示已选根且保存成功，但条目仍在旧分组；批量移动有独立实现，不直接受此问题影响。 触发序列及反证详见方案。
- 位置：`app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditGroupAndAppSections.kt:74`；`app/src/main/java/com/keepasskey/app/data/repository/VaultEntryWriteCoordinator.kt:106`。
- 已实现：无，本轮仅审查和编制方案。尚未完成：原路径产品复现、实现、失败对照及相应运行验收；静态结论不代表已闭环。
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

### ISSUE-P2-645 现行CI工程门禁有三项失败

- 核实：2026-10-16（Asia/Shanghai），现行机检实际运行；已实跑确认；置信度：高。
- 问题：本轮实跑gate_readings：13/16通过；count_line_tiers报6个>500行文件、400-500行38个超过预算35；long_functions报3个>=100行函数；check_cancellation_semantics报12处未按规则说明/传播取消。
- 影响与触发：按当前CI定义hygiene-gate会失败，结构与取消处理审查成本回升；12个命中不全部等于真实吞取消，部分是非挂起清理块缺说明。 触发序列及反证详见方案。
- 位置：`.github/workflows/build.yml:1`；`tools/doc/gate_readings.py:1`。
- 已实现：无，本轮仅审查和编制方案。尚未完成：原路径产品复现、实现、失败对照及相应运行验收；静态结论不代表已闭环。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-645/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

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
- 已实现：无，本轮仅审查与方案。尚未完成：修复、原触发与失败对照、相应真实服务/运行验收。
- [独立整改方案](architecture/问题整改方案/P2/ISSUE-P2-655/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

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
- 已实现：无，本轮仅审查与方案。尚未完成：修复、原触发与失败对照、相应真实服务/运行验收。
- [独立整改方案](architecture/问题整改方案/P3/ISSUE-P3-656/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。

### ISSUE-P3-657 S3签名密钥级联派生在中途异常时遗漏清理

- 核实：2026-10-16（Asia/Shanghai），当前源码与协议消费链；静态确认异常出口缺口；置信度：高（控制流）；异常触发概率低。
- 问题：kDate/kRegion/kService在第一个try内依次生成，该finally只清kSecret；清前三级的第二个try只有全部派生完成后才进入。中间HMAC抛异常时，已分配派生数组跳过清理。
- 影响与触发：异常路径的可擦派生秘密等待GC；正常路径已有清理，真实HMAC异常/资源错误才触发。无外部未授权读取内存证据，不是签名密钥直接外传。 复现与反证详见方案。
- 位置：`sync/src/main/java/com/keepasskey/sync/s3/S3RequestSigner.kt:174`。
- 已实现：无，本轮仅审查与方案。尚未完成：修复、原触发与失败对照、相应真实服务/运行验收。
- [独立整改方案](architecture/问题整改方案/P3/ISSUE-P3-657/整改方案.md)：目标、模块、步骤、失败处理、验收与回退。
