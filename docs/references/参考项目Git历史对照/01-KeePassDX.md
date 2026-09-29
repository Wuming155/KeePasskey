# 参考项目 Git 历史对照 · KeePassDX 分册

**范围**：🥇 KeePassDX（核心参考）全量提交历史中修复/防护类提交的挖掘与主项目对照。
**统计**：挖掘记录 **40** 条，对照行 **41** 条（`2fc2a9c7c` 一条挖控行对应两条对照行）——yes 0 / unclear 0 / excluded 5 / no 36。
**复核状态**：**confirmed=false**（本轮未经独立复核确认，corrections 为空，对照行按挖掘原样收录；行号与断言的复核义务留待条目被认领整改时按规则 6 重新核实）。
**日期**：2026-09-29。总表见 [00-对照总表.md](00-对照总表.md)。

## 一、挖掘记录（40 条）

| # | 提交 | subject | 本质（essence） | 触发（trigger） | 位置（area） | 领域 | 类型 | 置信度 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 2fc2a9c7c（2025-09-11） | fix: Delete algo during merge #1516 | 合并删除组时连同组内未删除的子条目/子组一并移除，同步后数据丢失；改为先把未删子节点迁到首个未删父组 | 对端库删除对象覆盖本地仍含活跃子节点的组 | database/src/main/java/com/kunzisoft/keepass/database/merge/DatabaseKDBXMerger.kt | 合并引擎 | bugfix | confirmed |
| 2 | d557e8b51（2025-10-27） | fix: merge algorithm #2223 | 合并时旧条目被移除、来库条目顶替，旧条目自身与历史丢失；改为始终保留当前条目并只把对方并入历史 | 按修改时间合并、且双方父位置不一致的条目 | database/src/main/java/com/kunzisoft/keepass/database/merge/DatabaseKDBXMerger.kt | 合并引擎 | bugfix | confirmed |
| 3 | a48dccf27（2025-10-21） | fix: Entries missing after database merge #2223 | 修改时间相同的条目合并时被移除后，父组为空则不再挂回导致条目消失；补齐同刻分支的挂载逻辑 | 合并双方条目修改时间完全相同 | database/src/main/java/com/kunzisoft/keepass/database/merge/DatabaseKDBXMerger.kt | 合并引擎 | bugfix | confirmed |
| 4 | 1e43a6574（2022-01-17） | Do not close the database when a merge failed | 合并失败即 clearAndClose 关闭当前已开数据库，用户被迫重开且丢失未保存状态；失败时保持库打开 | 合并任一阶段失败返回 | app/src/main/java/com/kunzisoft/keepass/database/action/MergeDatabaseRunnable.kt | 合并引擎 | bugfix | confirmed |
| 5 | 0fac0c3d5（2026-02-23） | fix custom data lastModificationTime ignored during KDBX parse | KDBX 解析把组/条目自定义数据的 LastModificationTime 读后丢弃，合并裁决恒取来库数据；改为保存并参与时间戳比较 | 解析含自定义数据时间戳的 KDBX 后合并 | database/src/main/java/com/kunzisoft/keepass/database/file/input/DatabaseInputKDBX.kt | 合并引擎 | bugfix | confirmed |
| 6 | 8120bb913（2026-09-10） | fix: Merge / load database error #2701 | 载入流程在打开文件流之前就清空当前库，URI 打不开时已开库状态被清掉；改为先取流再清理并规范失败退出 | 载入/合并/重载时数据库流获取失败 | database/action/LoadDatabaseRunnable.kt、MergeDatabaseRunnable.kt、ContextualDatabase.kt | 合并引擎 | bugfix | confirmed |
| 7 | 77c8207c7（2018-01-02） | Fix kdbx4 date corruption | KDBX4 时间戳按 .NET 纪元换算偏移错误，产生早于 1970 的坏日期损坏库；修正换算并对异常日期钳制 | 读写 KDBX4 的时间戳字段 | app/src/main/java/com/keepassdroid/utils/DateUtil.java、ImporterV4.java | kdbx格式 | bugfix | confirmed |
| 8 | 30da52934（2020-08-27） | Fix corruption in header | 内部头部写受保护二进制时长度按解压数据计、流却写 gzip 压缩数据，长度不匹配损坏库；写前强制解压 | 缓存中的二进制处于压缩态时保存 KDBX4 | database/file/output/DatabaseInnerHeaderOutputKDBX.kt | kdbx格式 | bugfix | confirmed |
| 9 | 0f021fae9（2022-01-30） | Fix Kdbx4 tag in Kdbx3 database #1222 | kdfParameters 为空被误判为非 AES，KDBX3 库被写上 V4 版本标记致其他客户端不兼容；改按 KDF 引擎判定版本 | 保存未加载 KDF 参数的 KDBX3 库 | database/element/database/DatabaseKDBX.kt、file/output/DatabaseOutputKDBX.kt | kdbx格式 | bugfix | confirmed |
| 10 | b9be8ff13（2020-04-22） | Fix KDBX header reader for KeeWeb database #533 | 遇到不认识的内部头部字段（KeeWeb 写入）直接返回失败且未消费字段数据，后续解析全部错位；改为读掉未知字段继续 | 读取含未知内部头部类型的 KDBX4 | database/file/input/DatabaseInputKDBX.kt | kdbx格式 | bugfix | confirmed |
| 11 | d34344623（2019-12-18） | Check file size to not corrupt a database v1 if too high | KDB v1 附件二进制长度超 Int 上限时被截断写入产出损坏库；超限不再写长度字段并留异常出口 | 向 KDB v1 写入超大附件 | database/file/output/EntryOutputKDB.kt | kdbx格式 | guard | confirmed |
| 12 | f0810ba0b（2026-04-21） | fix: Twofish algorithm name #2480 | Twofish 密钥用 "AES" 算法名构造 SecretKeySpec，走错 JCE 算法致 Twofish 库加解密失败；改用 "Twofish" | 以 Twofish 加密打开/保存数据库 | crypto/src/main/java/com/kunzisoft/encrypt/CipherFactory.kt | 加密与KDF | bugfix | confirmed |
| 13 | e9da2c19f（2026-08-22） | fix: KDF selection #2640 | KdfEngine 未按 UUID 实现 equals/hashCode，实例互不相等导致 KDF 选中与匹配失灵；补齐按 UUID 相等并加单测 | 凭 engine 实例匹配/选择 KDF | database/crypto/kdf/KdfEngine.kt、tests/crypto/KdfSerializationTest.kt | 加密与KDF | bugfix | confirmed |
| 14 | 3dfe4ace7（2021-01-11） | Fix binary keyfiles of 64 bytes #835 | 64 字节二进制 keyfile 被先按 hex 尝试解码导致密钥错误；识别顺序改为 32 字节直取→XML→整体 SHA-256 | 使用恰为 64 字节的二进制密钥文件 | database/element/database/DatabaseVersioned.kt | 加密与KDF | bugfix | confirmed |
| 15 | 28f79aec1（2021-01-11） | Check keyfile XML hash | XML keyfile 不校验 Version/Hash 属性，坏文件或错编码静默产出错误密钥；解析时校验数据哈希 | 导入 XML 版本密钥文件 | database/element/database/DatabaseKDBX.kt、utils/StringUtil.kt | 加密与KDF | guard | confirmed |
| 16 | e3f5ab3a1（2026-09-04） | fix: 32 zero-byte key file | 32 字节 keyfile 直接返回底层缓冲，缓冲随后被清零，派生密钥变全零打不开库；返回前 copyOf 拷贝 | 使用 32 字节原生密钥文件解锁 | database/src/main/java/com/kunzisoft/keepass/database/element/MasterCredential.kt | 加密与KDF | bugfix | confirmed |
| 17 | b57630e0e（2026-08-31） | fix: Memory limits #2671 | KDF 内存/迭代/并行度超限抛泛型 SecurityException 且内存上限系数不当；改 fail-closed 类型化异常并校准上限 | 以极端 KDF 参数打开或保存数据库 | database/crypto/kdf/Argon2Kdf.kt、Limits.kt、exception/DatabaseException.kt | 加密与KDF | guard | confirmed |
| 18 | f2cc98c63（2026-04-15） | fix: Improve security #2481 #2480 | Argon2 JNI 的 password/salt/secret/ad 缓冲 free 前未清零，口令等敏感数据残留堆内存；free 前逐一擦除 | 每次原生 Argon2 派生完成 | crypto/src/main/jni/argon2/argon2_jni.c | 敏感数据处理 | bugfix | confirmed |
| 19 | f28a3cf6c（2026-04-15） | fix: Wipe memory #2480 | AES JNI 密钥缓冲与加解密 I/O 缓冲（含错误路径）未擦除即 free，敏感数据残留；引入 secure_wipe_memory 全路径擦除 | 原生 AES 加解密初始化、异常与清理路径 | crypto/src/main/jni/aes/aes_jni.c | 敏感数据处理 | bugfix | confirmed |
| 20 | faa74585a（2026-04-15） | fix: Clear sensitive data #2482 #2480 | CompositeKey 口令/密钥文件/转换密钥用后不清零，库关闭后敏感字节驻留内存；补 clear() 与各清零路径 | 加载失败、关闭或切换数据库 | database/element/CompositeKey.kt、MasterCredential.kt、DatabaseKDB(X).kt | 敏感数据处理 | bugfix | confirmed |
| 21 | 287795d73（2026-04-16） | fix: MasterCredential plain text #2485 #2480 | 主凭据口令用 String 承载并跨 Parcelable 传递无法擦除；改 CharArray 并提供 clear() 与相应视图/生成器适配 | 录入、传递或清理主凭据口令 | app/database/MainCredential.kt、MainCredentialActivity.kt、password/*Generator.kt | 敏感数据处理 | bugfix | confirmed |
| 22 | d8e051177（2026-04-15） | fix: OTP clipboard sensitive #2488 #2480 | 通知里复制 OTP 走普通剪贴板复制，锁屏/预览明文泄漏验证码；改用带敏感标记的 timeoutCopyToClipboard | 从 OTP 复制通知点复制动作 | app/services/ClipboardEntryNotificationService.kt、timeout/ClipboardHelper.kt | 敏感数据处理 | bugfix | confirmed |
| 23 | 6cfa64aaf（2026-04-21） | fix: Autofill logs #2487 #2480 | release 包未开混淆，Log.d/v 打印 autofill 表单结构等敏感内容进系统日志；开启 R8 并 strip 调试日志 | release 构建期间任意 autofill 会话 | app/build.gradle、app/proguard-rules.pro | 敏感数据处理 | bugfix | confirmed |
| 24 | 3e56521ea（2022-04-08） | Empty Magikeyboard memory when the main service is killed #1261 | 主服务被杀后 Magikeyboard 仍残留上次条目的字段记忆，可被后续会话读出；服务重建时清空键盘记忆 | 系统杀死主服务后再次使用键盘 | app/magikeyboard/MagikeyboardService.kt、EntrySelectionLauncherActivity.kt | 敏感数据处理 | guard | **suspected** |
| 25 | 0608fdec4（2015-11-21） | java.util.Random is insecure, use java.security.SecureRandom instead | 口令生成器用可预测的 java.util.Random；改用 SecureRandom | 生成随机口令 | src/com/keepassdroid/password/PasswordGenerator.java | 敏感数据处理 | bugfix | confirmed |
| 26 | cfa71fb1c（2025-12-20） | fix: Disable autofill for webView | 应用内 WebView 输入框也被识别为可填充目标，口令可被填进不可信网页；结构解析命中 WebView 即判无效拒绝填充 | autofill 目标界面含 WebView（大小写不敏感） | app/credentialprovider/autofill/StructureParser.kt | Autofill | guard | confirmed |
| 27 | d2549d61d（2025-10-24） | fix: Autofill pending intent bypass #2238 | dataset 的 PendingIntent 重建后不携带 autofill 结构/搜索信息/模式 extras，回填落在错误上下文；改为完整上下文写入 Bundle | 经 PendingIntent 重新拉起 autofill 选择页 | app/credentialprovider/autofill/AutofillHelper.kt、EntrySelectionHelper.kt、AutofillLauncherActivity.kt | Autofill | bugfix | confirmed |
| 28 | a3acd7e11（2026-04-15） | fix: Device credential not authentication-bound #2483 #2480 | 设备凭据（PIN/图案）解锁的 Keystore 密钥未设认证要求，无需认证即可加解密；按平台补认证绑定与有效期参数 | 启用设备凭据解锁并生成 Keystore 密钥 | app/biometric/DeviceUnlockManager.kt、viewmodels/DeviceUnlockViewModel.kt | 凭据与passkey | bugfix | confirmed |
| 29 | d9bafc0bc（2026-04-21） | fix: Passkey validation #2480 | passkey 认证码把 hex 字符串与原始字节直比恒不等（校验形同虚设），HMAC 密钥仅 128 位；改字节比对并升至 256 位 | 校验凭据入口携带的认证码 | app/credentialprovider/passkey/util/PassHelper.kt | 凭据与passkey | bugfix | confirmed |
| 30 | 7e09532d5（2025-08-20） | fix: Add check security | Passkey 启动器把 checkSecurity（调用方校验）留成 TODO 未执行，任意应用可带 nodeId 拉起选择/注册；恢复调用校验 | 外部 intent 拉起 PasskeyLauncherActivity | app/credentialprovider/activity/PasskeyLauncherActivity.kt、passkey/util/PasskeyHelper.kt | 凭据与passkey | guard | confirmed |
| 31 | f640cca26（2026-01-04） | fix: force UV when webauthn ceremony #2321 | WebAuthn ceremony 时用户验证跟随默认设置而非强制，UV 要求被绕过；PASSWORD/PASSKEY 模式强制开启 UV | 凭据提供方执行 WebAuthn 注册/断言 | app/activities/MainCredentialActivity.kt | 凭据与passkey | guard | confirmed |
| 32 | 964f4ae23（2025-11-26） | fix: Passkey subdomain #2291 | RP 匹配用模糊子串搜索，恶意域可子串命中目标站条目导致错发通行密钥；改为精确忽略大小写相等 | 凭据搜索匹配 relyingParty 与条目 RP 字段 | database/src/main/java/com/kunzisoft/keepass/database/search/SearchHelper.kt | 凭据与passkey | bugfix | confirmed |
| 33 | 4222b9d15（2026-07-15） | fix: Change Cbor and rigorous WebAuthn implementation #2502 | PRF 扩展输出 enabled 字段未按 WebAuthn3 区分注册/认证场景（认证时应省略），与规范不符致互操作失败；按场景生成 | 带 PRF 扩展的 WebAuthn 请求 | app/credentialprovider/passkey/util/PasskeyHelper.kt、data/FidoDataTypes.kt | 凭据与passkey | bugfix | confirmed |
| 34 | 8be687465（2021-11-02） | Auto remove all biometric keys when invalidated | Keystore 密钥永久失效（如新录指纹）后旧密钥残留，生物解锁持续失败；检测失效即自动清除全部密钥 | KeyPermanentlyInvalidatedException 发生 | app/biometric/AdvancedUnlockManager.kt、AdvancedUnlockFragment.kt | 凭据与passkey | guard | **suspected** |
| 35 | 9e714c419（2024-05-13） | fix: Database is 0 Byte if Yubikey save is canceled #1680 | 保存等待 Yubikey 挑战响应被取消后流程继续执行，数据库被写成 0 字节；取消传播中断保存并提示 | 保存时取消/中断 Yubikey 挑战响应 | app/services/DatabaseTaskNotificationService.kt | 文件IO | bugfix | confirmed |
| 36 | d841c25bd（2021-08-21） | Check URI permissions #626 | SAF 选择的库 URI 权限未持久化，进程或设备重启后失去读写授权打不开库；补 takePersistableUriPermission 与校验 | 重启后经 SAF URI 重新打开/保存数据库 | app/utils/UriUtil.kt、activities/FileDatabaseSelectActivity.kt | 文件IO | bugfix | confirmed |
| 37 | 6dc0c42b1（2021-03-15） | Fix database save with bad binary #924 | 附件源文件在盘上缺失时 md5 去重抛异常中止整个保存；缺失二进制按容错处理继续保存 | 保存时引用的二进制文件已不存在 | app/database/element/database/BinaryPool.kt、BinaryFile.kt | 文件IO | bugfix | confirmed |
| 38 | cc5b96f53（2019-11-26） | Fix OOM by stream implementation and add KDBX version for DatabaseV2 | 数据库与附件全量读入内存致大库 OOM；改流式读写实现 | 打开或保存大体积 KDBX/多附件库 | database/file/load/ImporterV4.kt、file/save/PwDbV4Output.kt、security/ProtectedBinary.kt | 文件IO | bugfix | confirmed |
| 39 | 74107b90b（2022-02-20） | Max binary byte as 10 MB to prevent OOM #256 | 附件入 RAM 分配仅凭可用内存估算且无上限，超大附件 OOM；加 10MB 硬上限并收紧可用内存倍数 | 加载/缓存超过 10MB 的附件 | app/database/element/binary/BinaryData.kt | 文件IO | guard | confirmed |
| 40 | 223a8e9a5（2022-01-19） | Fix concurrent modification exception | updateWith 默认向上更新父组修改时间，遍历树期间更新集合抛 ConcurrentModificationException；补 updateParents 开关遍历时跳过 | 在节点遍历/合并过程中更新条目或组 | database/element/entry/EntryKDB(X).kt、group/GroupKDB(X).kt、GroupVersioned.kt | 并发 | bugfix | confirmed |

## 二、对照行全文（41 条）

### 行 1｜2fc2a9c7c fix: Delete algo during merge #1516
- **本质**：合并删除组时连同组内未删除的子条目/子组一并移除，同步后数据丢失
- **触发**：对端库删除对象覆盖本地仍含活跃子节点的组
- **我们的对应位置**：sync/src/main/java/com/keepasskey/sync/merge/KdbxMerger.kt:119-151
- **相似度**：同域不同型：主项目为墓碑感知三方合并，每个 UUID 独立裁决存活，删除不级联
- **risk**：no

### 行 2｜2fc2a9c7c（同条延续）
- **本质**：被删组的活跃子节点去向
- **触发**：同上
- **我们的对应位置**：sync/src/main/java/com/keepasskey/sync/merge/KdbxEntryMerger.kt:177-195
- **相似度**：孤儿条目经 assignEntriesToGroups 回退 previousParentGroup、仍无处可挂才归属根组（KdbxGroupMerger.kt:101 分组同口径挂根），即 KeePassDX 修复后同款语义
- **risk**：no

### 行 3｜d557e8b51 fix: merge algorithm #2223
- **本质**：合并时旧条目被移除、来库条目顶替，旧条目自身与历史丢失
- **触发**：按修改时间合并、且双方父位置不一致的条目
- **我们的对应位置**：sync/src/main/java/com/keepasskey/sync/merge/KdbxEntryMerger.kt:115-129
- **相似度**：功能相似不同型：双侧在时恒走字段级三方合并且以本地为底版（mergeConflictedEntry:197），父位单侧移动取该侧（resolveMergedParentGroup:403-411），历史并集按时间去重（:221-223），旧条目与历史不会整条丢失
- **risk**：no

### 行 4｜a48dccf27 fix: Entries missing after database merge #2223
- **本质**：修改时间相同的条目合并时被移除后父组为空则不再挂回导致条目消失
- **触发**：合并双方条目修改时间完全相同
- **我们的对应位置**：sync/src/main/java/com/keepasskey/sync/merge/KdbxEntryMerger.kt:89-139
- **相似度**：触发面不存在：条目存活裁决不经修改时间比较（时间仅用于双侧同改时的 LWW 取值且平局取本地），且 assignEntriesToGroups（:186-193）保证每个存活条目必挂载（回退根组），无「父组为空不挂回」分支
- **risk**：no

### 行 5｜1e43a6574 Do not close the database when a merge failed
- **本质**：合并失败即关闭当前已开数据库，用户被迫重开且丢失未保存状态
- **触发**：合并任一阶段失败返回
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/sync/SyncConflictController.kt:276-287
- **相似度**：同域不同型：合并全程内存内，解析/合并失败一律返回 SyncOutcome.Error，活动会话不触碰（:278-280 KDoc 明示「传入内存快照绝不擦除，擦除活动库为 P0 级」）
- **risk**：no

### 行 6｜0fac0c3d5 fix custom data lastModificationTime ignored during KDBX parse
- **本质**：KDBX 解析把自定义数据的 LastModificationTime 读后丢弃，合并裁决恒取来库数据
- **触发**：解析含自定义数据时间戳的 KDBX 后合并
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/xml/KdbxXmlMetaReader.kt:395-407
- **相似度**：解析面已防护：Meta 级 CustomData 的 LastModificationTime 已解析保留并写回（KdbxVersion41Features.kt:46-47）；合并面按 PD-35 裁定 customData 以本地为准、不做时间戳裁决，属既有裁决口径
- **risk**：excluded（decisionRef：PD-35）

### 行 7｜8120bb913 fix: Merge / load database error #2701
- **本质**：载入流程在打开文件流之前就清空当前库，URI 打不开时已开库状态被清掉
- **触发**：载入/合并/重载时数据库流获取失败
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/session/SessionOpener.kt:169-175
- **相似度**：同类模式但触发面现不存在：openStream 确实在 releaseCurrentSession()（:169）之后才取流（:175），但全仓仅两个调用点（VaultLifecycleCoordinator.kt:60/:94、SafVaultCreation.kt:108）且均在会话已锁定态下进入，释放为幂等空操作；换库只改活动指针（RealVaultRepository.kt:117-125）不经 open
- **risk**：no
- **advice（duplicateOf 字段注记，非既往批次重复）**：P2-378 落地新增「重载」路径时若从已解锁态直接 openStream，该时序即激活 KeePassDX #2701 同型（已开会话被失败清掉），届时须把取流挪到 releaseCurrentSession 之前（P2-77 硬约束只要求释放先于 KdbxFile.load，不要求先于取流）

### 行 8｜77c8207c7 Fix kdbx4 date corruption
- **本质**：KDBX4 时间戳按 .NET 纪元换算偏移错误，产生早于 1970 的坏日期损坏库
- **触发**：读写 KDBX4 的时间戳字段
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/xml/KdbxXmlTimeHelper.kt:39-104
- **相似度**：同功能已正确实现：常量 62135596800L 即 0001-01-01 纪元偏移，ticksToInstant 用 floorDiv 处理负值，ANCIENT_INSTANT（:63-71）供异常日期钳制
- **risk**：no

### 行 9｜30da52934 Fix corruption in header
- **本质**：内部头写受保护二进制时长度按解压数据计、流却写 gzip 压缩数据，长度不匹配损坏库
- **触发**：缓存中的二进制处于压缩态时保存 KDBX4
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/file/InnerHeader.kt:211-214
- **相似度**：触发面不存在：主项目二进制池恒存原始字节（落盘经 FileBinaryStore.storeFromStream 不压缩），写出长度=bin.size+flags 且 writeTo 直写原字节；GZIP 仅用于整载荷压缩（KdbxFile.kt:211 按 header.compression 旗标），不对单个二进制压缩
- **risk**：no

### 行 10｜0f021fae9 Fix Kdbx4 tag in Kdbx3 database #1222
- **本质**：kdfParameters 为空被误判为非 AES，KDBX3 库被写上 V4 版本标记致其他客户端不兼容
- **触发**：保存未加载 KDF 参数的 KDBX3 库
- **我们的对应位置**：无直接对应
- **相似度**：触发面不存在且属已裁决：主项目只读 KDBX4（KdbxHeader validateVersion major≠4 拒绝），KDBX3 库根本进不了内存更无从保存
- **risk**：excluded（decisionRef：PD-53）

### 行 11｜b9be8ff13 Fix KDBX header reader for KeeWeb database #533
- **本质**：遇到不认识的内部头部字段直接返回失败且未消费字段数据，后续解析全部错位
- **触发**：读取含未知内部头部类型的 KDBX4
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/file/InnerHeaderReader.kt:73-74
- **相似度**：已防护：字段数据先按 fieldLen 统一读入（readBytes，:74）再进 acceptField 分派（:103-108），未知 fieldId 在 when 中无分支自然跳过，数据已被消费，无错位面
- **risk**：no

### 行 12｜d34344623 Check file size to not corrupt a database v1 if too high
- **本质**：KDB v1 附件二进制长度超 Int 上限时被截断写入产出损坏库
- **触发**：向 KDB v1 写入超大附件
- **我们的对应位置**：无直接对应
- **相似度**：无 KDB v1 读写器（老格式不做兼容，用户明示裁决）；二进制池写入侧另有 MAX_BINARY_POOL_TOTAL_BYTES fail-closed 上限
- **risk**：excluded（decisionRef：PD-53）

### 行 13｜f0810ba0b fix: Twofish algorithm name #2480
- **本质**：Twofish 密钥用 "AES" 算法名构造 SecretKeySpec，走错 JCE 算法致加解密失败
- **触发**：以 Twofish 加密打开/保存数据库
- **我们的对应位置**：crypto/src/main/java/com/keepasskey/crypto/cipher/NativeTwofish.kt:103
- **相似度**：同功能已正确实现：兜底 JCE 路径即用 SecretKeySpec(key, "Twofish")；主路径为 Rust 自有内核（TwofishCipherEngine），无 "AES" 名错配路径
- **risk**：no

### 行 14｜e9da2c19f fix: KDF selection #2640
- **本质**：KdfEngine 未按 UUID 实现 equals/hashCode，实例互不相等导致 KDF 选中与匹配失灵
- **触发**：凭 engine 实例匹配/选择 KDF
- **我们的对应位置**：crypto/src/main/java/com/keepasskey/crypto/kdf/KdfFactory.kt:16-22
- **相似度**：同功能不同实现：引擎选择按 KdbxUuid 值在 when 中分派（AES_KDF/ARGON2D/ARGON2ID/未知即类型化异常），从不依赖引擎实例相等性
- **risk**：no

### 行 15｜3dfe4ace7 Fix binary keyfiles of 64 bytes #835
- **本质**：64 字节二进制 keyfile 被先按 hex 尝试解码导致密钥错误
- **触发**：使用恰为 64 字节的二进制密钥文件
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/file/KdbxKeyFile.kt:64-83
- **相似度**：同功能已按修复后顺序实现：恰 32 字节直取 copyOf → XML → 「非空白恰 64 且全 hex」才按 hex 解码（随机 64 二进制字节几乎不可能全为 hex 位）→ 整文件 SHA-256
- **risk**：no

### 行 16｜28f79aec1 Check keyfile XML hash
- **本质**：XML keyfile 不校验 Version/Hash 属性，坏文件静默产出错误密钥
- **触发**：导入 XML 版本密钥文件
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/file/KdbxKeyFile.kt:126-138
- **相似度**：已防护：v2.0 路径 decodeHexKeyWithHash 校验 Hash 属性（密钥 SHA-256 前 4 字节），不符即清零并抛 KdbxCorruptFileException；且声明式 XML 但结构非法绝不回退整文件哈希（:59-62）
- **risk**：no

### 行 17｜e3f5ab3a1 fix: 32 zero-byte key file
- **本质**：32 字节 keyfile 直接返回底层缓冲，缓冲随后被清零，派生密钥变全零打不开库
- **触发**：使用 32 字节原生密钥文件解锁
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/file/KdbxKeyFile.kt:65-67
- **相似度**：已防护：恰 32 字节分支返回 raw.copyOf()（拷贝而非底层缓冲引用），调用方清零原件不影响密钥
- **risk**：no

### 行 18｜b57630e0e fix: Memory limits #2671
- **本质**：KDF 内存/迭代/并行度超限抛泛型异常且内存上限系数不当
- **触发**：以极端 KDF 参数打开或保存数据库
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/file/KdbxKdfParameterCodec.kt:224-274
- **相似度**：同守卫更强：全部 fail-closed 抛类型化 KdbxCorruptFileException，含逐参数上下限、memory≥8×parallelism 交叉约束、I×M 联合预算 2^33、超堆一半动态门槛（KDoc 明示对照 KeePassDX Limits/KeePassXC 封顶语义）
- **risk**：no

### 行 19｜f2cc98c63 fix: Improve security #2481 #2480
- **本质**：Argon2 JNI 的 password/salt/secret/ad 缓冲 free 前未清零，敏感数据残留堆内存
- **触发**：每次原生 Argon2 派生完成
- **我们的对应位置**：无直接对应
- **相似度**：无同型面：主项目原生侧为 Rust 内核（crypto/src/main/rust/），敏感缓冲由 Zeroizing RAII 全路径擦除，且 AGENTS.md 铁律禁止手写 C/C++ 秘密缓冲管理，无 C JNI 文件
- **risk**：no

### 行 20｜f28a3cf6c fix: Wipe memory #2480
- **本质**：AES JNI 密钥缓冲与加解密 I/O 缓冲（含错误路径）未擦除即 free
- **触发**：原生 AES 加解密初始化、异常与清理路径
- **我们的对应位置**：无直接对应
- **相似度**：同上：无 C JNI AES；自有流式骨架按 ownedSecrets 契约确定性擦除（close 即清），兜底 JCE 流语义由 CipherFallbackParityTest 锁定
- **risk**：no

### 行 21｜faa74585a fix: Clear sensitive data #2482 #2480
- **本质**：CompositeKey 口令/密钥文件/转换密钥用后不清零，库关闭后敏感字节驻留内存
- **触发**：加载失败、关闭或切换数据库
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/file/KdbxKeyDerivation.kt:62-179
- **相似度**：同功能已防护：复合密钥/口令字节/密钥文件密钥/转换密钥/cipherKeyBytes 全路径 Arrays.fill 清零；派生产物 cipherKey/hmacKey64 在读写两管线 finally 中擦除（KdbxFile.kt:189-190、save 侧 finally）；会话凭据缓存锁库即 fill('0')（SessionCredentialCache.kt:99-102）
- **risk**：no

### 行 22｜287795d73 fix: MasterCredential plain text #2485 #2480
- **本质**：主凭据口令用 String 承载并跨 Parcelable 传递无法擦除
- **触发**：录入、传递或清理主凭据口令
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/session/SessionCredentialCache.kt:19
- **相似度**：功能相似已防护：主密码全程 CharArray（passwordCache: CharArray，clone 写入、fill('0') 清零）；残余的 Compose 输入态与平台边界 String 副本属已登记限界，非同型缺陷
- **risk**：no

### 行 23｜d8e051177 fix: OTP clipboard sensitive #2488 #2480
- **本质**：通知里复制 OTP 走普通剪贴板，锁屏/预览明文泄漏验证码
- **触发**：从 OTP 复制入口点复制动作
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/autofill/AutofillTotpCopyPolicy.kt:12
- **相似度**：同功能已防护：TOTP 复制统一走 ClipboardSecurityManager.copySensitiveText（EXTRA_IS_SENSITIVE=true，ClipboardSecurityManager.kt:161）+ 定时自动擦除
- **risk**：no

### 行 24｜6cfa64aaf fix: Autofill logs #2487 #2480
- **本质**：release 包未开混淆，Log.d/v 打印 autofill 表单结构等敏感内容进系统日志
- **触发**：release 构建期间任意 autofill 会话
- **我们的对应位置**：app/build.gradle.kts:144
- **相似度**：同功能已防护：isMinifyEnabled=true + shrinkResources；proguard-rules.pro:146-150 assumenosideeffects 剥离框架 Log.v/d 与 AppLog.v/d 调用点，AppLog.e/w 运行期脱敏（仅异常类名），AppLogProguardRuleTest 交叉锁定规则形态
- **risk**：no

### 行 25｜3e56521ea Empty Magikeyboard memory when the main service is killed #1261
- **本质**：主服务被杀后自定义键盘仍残留上次条目字段记忆，可被后续会话读出
- **触发**：系统杀死主服务后再次使用键盘
- **我们的对应位置**：无直接对应
- **相似度**：无键盘通道（Manifest 无 InputMethodService，全仓检索零命中），属用户明示放弃的功能
- **risk**：excluded（decisionRef：PD-55）

### 行 26｜0608fdec4 java.util.Random is insecure, use SecureRandom instead
- **本质**：口令生成器用可预测的 java.util.Random
- **触发**：生成随机口令
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditFormProjection.kt:164
- **相似度**：同功能已防护：generatePasswordChars(state, random: SecureRandom) 强制 SecureRandom 注入（EntryEditPasswordGenerator.kt:55、PasswordDraftActivity.kt:168 均传 SecureRandom()）；全仓无 java.util.Random 用于安全用途
- **risk**：no

### 行 27｜cfa71fb1c fix: Disable autofill for webView
- **本质**：应用内 WebView 输入框也被识别为可填充目标，口令可被填进不可信网页
- **触发**：autofill 目标界面含 WebView
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/autofill/AutofillWebDomainPolicy.kt:16-21
- **相似度**：同威胁面结构性防护（且强于一刀切拒绝）：WebView 上报的 webDomain 由调用方可控，仅「受信浏览器包名+已取证签名指纹」或 DAL 验证方可参与域匹配，其余一律 REJECTED fail-closed；非浏览器应用凭据按包名归属匹配，不存在「按不可信网页域选凭据」路径
- **risk**：no

### 行 28｜d2549d61d fix: Autofill pending intent bypass #2238
- **本质**：dataset 的 PendingIntent 重建后不携带 autofill 结构/搜索信息/模式 extras，回填落在错误上下文
- **触发**：经 PendingIntent 重新拉起 autofill 选择页/确认页
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/autofill/AutofillDatasetBuilders.kt:186-199
- **相似度**：同功能已防护：认证 confirmIntent 完整携带目标框 id（username/password/otp）+结构化角色与 id + 条目 id + 授权上下文（全为 String/ArrayList 原始类型），确认页按 extras 重建 fully populated dataset 经 EXTRA_AUTHENTICATION_RESULT 回传（ISSUE-P2-88 契约，AutofillConfirmActivity.kt:309-318）
- **risk**：no

### 行 29｜a3acd7e11 fix: Device credential not authentication-bound #2483 #2480
- **本质**：设备凭据解锁的 Keystore 密钥未设认证要求，无需认证即可加解密
- **触发**：启用设备凭据解锁并生成 Keystore 密钥
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/security/KeystoreKeyMaterial.kt:99-107
- **相似度**：同功能已防护：恒 setUserAuthenticationRequired(requireUserAuth)（:99），快速解锁密钥 AUTH_BIOMETRIC_STRONG（可含 AUTH_DEVICE_CREDENTIAL 组合）且经 per-operation CryptoObject 授权；「只写 parameters 不写 required」平台陷阱已由 ISSUE-P1-09 修复并有 KeyInfo 全等探测守卫（:176-178）
- **risk**：no

### 行 30｜d9bafc0bc fix: Passkey validation #2480
- **本质**：passkey 认证码把 hex 字符串与原始字节直比恒不等（校验形同虚设），HMAC 密钥仅 128 位
- **触发**：校验凭据入口携带的认证码
- **我们的对应位置**：无直接对应
- **相似度**：主项目无「认证码」机制可校验（该坑依赖 KeePassDX 的认证码直通形态）；入口安全性由调用方解析（CallingOriginResolver.resolveTrustedOrigin，KeePasskeyCredentialProviderService.kt:275）与包名签名绑定门控（:287）承担
- **risk**：no

### 行 31｜7e09532d5 fix: Add check security
- **本质**：Passkey 启动器把 checkSecurity（调用方校验）留成 TODO 未执行，任意应用可带 nodeId 拉起
- **触发**：外部 intent 拉起 PasskeyLauncherActivity
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/passkey/KeePasskeyCredentialProviderService.kt:275-287
- **相似度**：同功能已防护：每个凭据请求先经 resolveTrustedOrigin 来源解析，再过 CredentialManagerPackageBindingGate 签名绑定门控，未校验不产候选
- **risk**：no

### 行 32｜f640cca26 fix: force UV when webauthn ceremony #2321
- **本质**：WebAuthn ceremony 时用户验证跟随默认设置而非强制，UV 要求被绕过
- **触发**：凭据提供方执行 WebAuthn 注册/断言
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/passkey/CredentialVerificationLauncher.kt:57
- **相似度**：同功能已防护且更严：需 UV 的请求且本会话未验过时必须走验证流程（对照总表 02 分册首行已核实）；注册侧另有 PasskeyRegistrationGate fail-closed 门禁
- **risk**：no

### 行 33｜964f4ae23 fix: Passkey subdomain #2291
- **本质**：RP 匹配用模糊子串搜索，恶意域可子串命中目标站条目导致错发通行密钥
- **触发**：凭据搜索匹配 relyingParty 与条目 RP 字段
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/passkey/DomainMatcher.kt:94-101
- **相似度**：同功能已防护：isDomainMatch 为「精确相等 或 点号边界后缀 d2.endsWith(".$d1")」，配合 extractDomain 归一与 isRegistrableDomain 公共后缀下限，无子串匹配；消费点 KeePasskeyCredentialProviderService.kt:304-305 且 passkey 域维度只认 passkeyRpId 单一真相源（P3-339 已收紧）
- **risk**：no

### 行 34｜4222b9d15 fix: Change Cbor and rigorous WebAuthn implementation #2502
- **本质**：PRF 扩展输出 enabled 字段未按 WebAuthn3 区分注册/认证场景，与规范不符致互操作失败
- **触发**：带 PRF 扩展的 WebAuthn 请求
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/passkey/PasskeyAssertionPayload.kt:129-162
- **相似度**：同功能已按规范实现：prf 输出仅在断言场景、请求携带 eval 且凭据持有 PRF 秘密时回传 results.first/second，从不产出 enabled 字段、算不出即空对象绝不伪造（KDoc 引 WebAuthn L3 §10.1）
- **risk**：no

### 行 35｜8be687465 Auto remove all biometric keys when invalidated
- **本质**：Keystore 密钥永久失效后旧密钥残留，生物解锁持续失败
- **触发**：KeyPermanentlyInvalidatedException 发生
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/security/KeystoreManager.kt:113-117
- **相似度**：同功能已防护：捕获 KeyPermanentlyInvalidatedException 自动清除脏密钥（:155）并抛类型化异常；同名密钥经 KeyInfo 认证形态全等探测后自动删除重建（:117），杜绝残留密钥反复失败
- **risk**：no

### 行 36｜9e714c419 fix: Database is 0 Byte if Yubikey save is canceled #1680
- **本质**：保存等待硬件挑战响应被取消后流程继续执行，数据库被写成 0 字节
- **触发**：保存时取消/中断硬件密钥挑战响应
- **我们的对应位置**：无直接对应
- **相似度**：触发面不存在：无硬件密钥复合凭据（用户明示放弃）；且保存链为内存全量序列化加密完成后才落盘，无「先截断后等待」窗口
- **risk**：excluded（decisionRef：PD-54）

### 行 37｜d841c25bd Check URI permissions #626
- **本质**：SAF 选择的库 URI 权限未持久化，进程或设备重启后失去读写授权打不开库
- **触发**：重启后经 SAF URI 重新打开/保存数据库
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/data/repository/SafVaultCreation.kt:64
- **相似度**：同功能已防护：建库路径授权先行（拿不到即在建库前显式失败，CreateVaultLocationWiringTest 锁定）；importExternalDatabase 亦 takePersistableUriPermission（VaultLifecycleCoordinator.kt:247），部分 provider 不支持时容错继续但脱敏告警留痕并向用户归因（:251-256，ISSUE-P3-230 AC①③）
- **risk**：no

### 行 38｜6dc0c42b1 Fix database save with bad binary #924
- **本质**：附件源文件在盘上缺失时抛异常中止整个保存（对端改为容错继续保存）
- **触发**：保存时引用的二进制文件已不存在
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/file/InnerHeader.kt:137-146
- **相似度**：同触发面但裁决方向相反且为有意防护：verifySpillIntact（ISSUE-P2-310）在保存前校验落盘附件字节量与解析期登记一致，不一致即抛 KdbxAttachmentSpillMissingException 终止保存——KDoc 明示若按 fail-open 空流继续写会致内层头长度自相矛盾、整库下次打开判损坏；fail-closed 终止优于静默丢附件
- **risk**：no

### 行 39｜cc5b96f53 Fix OOM by stream implementation and add KDBX version for DatabaseV2
- **本质**：数据库与附件全量读入内存致大库 OOM；改流式读写实现
- **触发**：打开或保存大体积 KDBX/多附件库
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/file/InnerHeaderReader.kt:90-98
- **相似度**：同方向已实现：解析流式（限界 §1.1），>1MiB 附件解析期流式落盘不整份物化（BinaryStore.shouldSpill 分流），保存侧附件流式拷出（InnerHeader.kt:211-214 writeTo）；残余仅「KDBX 对象树整体驻留内存（附件除外）」为已登记客观限界
- **risk**：no

### 行 40｜74107b90b Max binary byte as 10 MB to prevent OOM #256
- **本质**：附件入 RAM 分配仅凭可用内存估算且无上限，超大附件 OOM
- **触发**：加载/缓存超过上限的附件
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/file/InnerHeaderReader.kt:85-98
- **相似度**：同防护更强：解析期即封顶（二进制池条目数 MAX_BINARY_POOL_ENTRIES + 累计字节 MAX_BINARY_POOL_TOTAL_BYTES 128MiB fail-closed，疑似解析炸弹即拒），>1MiB 走落盘不进 RAM；写侧另有附件池引用计费三面判据（限界 §29）
- **risk**：no

### 行 41｜223a8e9a5 Fix concurrent modification exception
- **本质**：遍历树期间更新父组修改时间抛 ConcurrentModificationException；补开关遍历时跳过父更新
- **触发**：在节点遍历/合并过程中更新条目或组
- **我们的对应位置**：core/src/main/java/com/keepasskey/core/model/KdbxGroup.kt:6-25
- **相似度**：结构性规避：KDBX 模型为不可变 data class（entries/subgroups 为只读 List，更新一律 copy 重建，PD-18 的整树重建即其产物），不存在「遍历中原地改集合」的时序面，也无需 updateWith 开关
- **risk**：no

## 三、复核修正记录

（无——本项目本轮 **confirmed=false**，corrections 为空、未经独立复核确认，对照行按挖掘原样收录。该分册条目被认领整改时，须按 AGENTS.md 规则 6 先行核实行号与断言。）
