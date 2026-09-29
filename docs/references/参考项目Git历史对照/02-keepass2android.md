# 参考项目 Git 历史对照 · keepass2android 分册

**范围**：🥈 keepass2android（云同步参考）全量提交历史中修复/防护类提交的挖掘与主项目对照。
**统计**：挖掘记录 **40** 条，对照行 **40** 条——yes 3 / unclear 0 / excluded 2 / no 35。
**复核状态**：**confirmed=true**（经独立复核，修正记录见「三」节）。
**日期**：2026-09-29。总表见 [00-对照总表.md](00-对照总表.md)。

## 一、挖掘记录（40 条）

| # | 提交 | subject | 本质（essence） | 触发（trigger） | 位置（area） | 领域 | 类型 | 置信度 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | c2795b2d（2026-07-20） | Fix the bug causing to "forget" the keyfile with Sync in background | 后台同步路径以 null 密钥文件名更新记录，清空已存密钥文件关联；改为 null=保留、空串=显式清除 | 后台同步时未显式提供 keyfile | src/keepass2android-app/fileselect/FileDbHelper.cs | 同步 | bugfix | confirmed |
| 2 | 86b8afe2（2025-09-26） | Fix issues with Webdav connection: fix non-chunked upload invalid data, disable chunked upload by default… | WebDAV 非分块上传把数据包成 multipart 体致服务器收到非法文件内容；改为纯 application/binary PUT，分块上传默认关闭 | WebDAV 非分块上传保存库文件 | src/java/…/WebDavStorage.java; app/App.cs | 同步 | bugfix | confirmed |
| 3 | 9c8ee243（2025-11-09） | Fix WebDAV HTTP 409 error with file transactions | MOVE 带 Overwrite:T 在目标已存在时多服务器拒为 409；改先删目标再 MOVE、409 重试并清理临时文件 | 文件事务开启+目标已存在 | src/java/…/WebDavStorage.java | 同步 | bugfix | confirmed |
| 4 | bcf34081（2025-11-09） | fix(del): don't remove tmp file if move failed | MOVE 失败后的清理会删临时文件，而失败可能发生在删目标后，临时文件是唯一新副本；改为保留不回删 | WebDAV 事务上传后 MOVE 失败 | src/java/…/WebDavStorage.java | 同步 | bugfix | confirmed |
| 5 | b1ae0482（2026-04-02） | Preserve original file extension in WebDAV temporary files | 临时文件 .tmp 后缀加在扩展名后，部分服务器禁止改扩展名致上传失败；改为把 .tmp 插到扩展名前 | 不支持改扩展名的 WebDAV 服务器 | src/java/…/WebDavStorage.java | 同步 | bugfix | confirmed |
| 6 | 14e9d03f（2025-11-03） | normalize WebDav-URLs to fix an issue when renaming files with non-ascii-characters (#3030) | 重命名用未规范化 URL，非 ASCII 字符（空格/变音符）两侧不一致致失败；C# 侧 AbsoluteUri 规范化、Java 侧 URL 解码对齐 | 非 ASCII 文件名重命名 | JavaFileStorage.cs/WebDavFileStorage.cs/WebDavStorage.java | 同步 | bugfix | confirmed |
| 7 | c9a7d56d（2025-03-04） | Webdav username and password could got lost during file selection… | PROPFIND 响应 href 不含 userinfo，后续请求丢失 WebDAV 用户名/口令；从父路径把 @user:pass 拼回子项路径 | 服务器 PROPFIND href 不含凭据 | src/java/…/WebDavStorage.java | 同步 | bugfix | confirmed |
| 8 | be1cc06a（2020-06-11） | reuse WebDav client; forcing WebDav to use http1.1. This is a workaround to fix #747 | OkHttp HTTP/2 实现缺陷(#4964) 致 WebDAV 上传数据异常；强制 HTTP/1.1 并复用客户端连接池变通 | WebDAV 协商到 HTTP/2 | src/java/…/WebDavStorage.java | 同步 | bugfix | confirmed |
| 9 | 3159af19（2014-03-23） | Fixed bug in IOConnection.cs: Errors when uploading data to http(s) were not handled correctly… | http(s) 上传仅处理 401，其余 WebException 被吞致上传失败当成功；补 else throw 向上传递 | http(s) 上传遇非 401 错误 | src/KeePassLib2Android/Serialization/IOConnection.cs | 同步 | bugfix | confirmed |
| 10 | 0273b4d2（2020-01-08） | AndroidContentStorage: truncate file before writing, closes #583 | SAF 覆盖写用 "w" 模式不截断，新内容更短时残留旧尾部字节致库损坏；改 "rwt" 截断写 | 覆盖写更短的库文件 | src/Kp2aBusinessLogic/Io/AndroidContentStorage.cs | 文件IO | bugfix | confirmed |
| 11 | caf42d42（2018-07-09） | make sure the underlying stream is only written when the write transaction is commited… | 缓存写事务在流 Close() 即写缓存并推远端，取消 YubiChallenge 放弃保存也触发写入致损坏/覆盖；改为仅 CommitWrite() 落盘 | 保存中途取消（如 YubiChallenge） | src/Kp2aBusinessLogic/Io/CachingFileStorage.cs | 文件IO | bugfix | confirmed |
| 12 | cd189e01（2018-09-18） | use internal directory for offline caching. this reduces the likelihood of data loss. closes #83 | 离线缓存迁出可被系统/用户清除的外部目录到内部存储，降低本地改动丢失概率；旧目录保留兼容 | 外部缓存被清除 | CachingFileStorage.cs/BuiltInFileStorage.cs | 文件IO | guard | confirmed |
| 13 | a671c4f2（2013-08-08） | Fixed SaveDb for CachingFileStorage and target file not existing -> + Tests | 缓存模式下远端目标不存在时文件哈希对比出错致保存失败；引入三态 FileHashChange(含 FileNotAvailable)+GetRemoteDataAndHash | 缓存模式+远端文件不存在时保存 | SaveDB.cs/CachingFileStorage.cs | 文件IO | bugfix | confirmed |
| 14 | 5fc22b95（2018-04-11） | introduced automatic local backups after successfully opening a database…(#238) | 新增成功打开库后自动本地备份并标记只读，文件损坏后仍有可用副本可取回 | 每次成功打开数据库 | BuiltInFileStorage.cs/IKp2aApp.cs | 文件IO | guard | confirmed |
| 15 | 0cd9df74（2025-06-03） | fix issues with background sync and multiple databases (especially autoopen) | 后台同步用全局 CurrentDb 而非目标 Database，多库/autoopen 时合并作用在错误库；改显式传 Database 实例+RequiresSubsequentSync | 多库/autoopen 时后台同步 | SynchronizeCachedDatabase.cs/LoadDB.cs | 同步 | bugfix | confirmed |
| 16 | 73fc93ed（2025-01-14） | fix issue with argon2 kdf (regression from .net8 migration)… | .NET8 迁移后 Argon2 KDF 失效：缺 armeabi-v7a/arm64-v8a 的 libargon2.so 且 DllImport 不再解析；补 .so 并改 LibraryImport+LoadLibrary | Argon2 派生（.NET8 构建） | …/Cryptography/KeyDerivation/Argon2Kdf.cs | 加密KDF | bugfix | confirmed |
| 17 | e4c17e2e（2016-09-05） | fix error with using native libary on Android, now allowing AesKdf again | 原生 AES-KDF TransformKey256 成功后又 SHA256 哈希结果致组合键错误无法解锁；改为直接返回变换后的 256 位密钥 | 走原生 AES-KDF 解锁 | …/Cryptography/KeyDerivation/AesKdf.cs | 加密KDF | bugfix | confirmed |
| 18 | 50d6598b（2025-04-08） | fix reading of cryptostream. The implementation issue became a bug in .net8. Closes #2816 | CryptoStream.Read 单次不保证填满缓冲（.NET8 行为变化），KeeChallenge 解密 secret 被截断；改为循环读至满 | KeeChallenge/OTP 解密 secret | src/keepass2android-app/KeeChallenge.cs | 加密KDF | bugfix | confirmed |
| 19 | 35f2e95d（2017-08-14） | fix potential xxe attacks when parsing xml files | 密钥文件 XML/OTP aux/ChallengeInfo 解析用默认 XmlResolver 可被 XXE（读文件/SSRF）；统一 XmlResolver=null+DtdProcessing.Ignore | 恶意构造的密钥文件/OTP aux | KcpKeyFile.cs/ChallengeInfo.cs/PasswordActivity.cs | 敏感数据 | guard | confirmed |
| 20 | fc2e0fa1（2015-02-02） | …fix bug in PwDate: wrong conversion from Java time to C-Date, off by one day | PwDate Java 时间转 C 日期时把「日」当 0 基再减 1，序列化日期整体偏一天；去掉错误减一 | kdb 库日期序列化/反序列化 | src/java/KP2AKdbLibrary/…/PwDate.java | kdbx格式 | bugfix | confirmed |
| 21 | bc235b3b（2015-02-07） | added tests for kdb writing, fixed issue with syncing (keep UUIDs when loading again) | kdb(v3) 加载每次 UUID 全变致合并把全部数据当新增；改为由组/条目 ID 确定性重建 UUID 保持跨加载稳定 | kdb 库再次加载后同步合并 | src/Kp2aBusinessLogic/database/KdbDatabaseFormat.cs | 合并 | bugfix | confirmed |
| 22 | ba1e591d（2015-02-19） | Make sure "duplicate UUID" error is not repeated after fixing the db… | 取消保存后再保存重复添加条目造成重复 UUID；AddEntry 加已存在检查，Database 加载失败 Clear() 防半初始化状态反复报错 | 取消保存后重试保存/加载失败 | AddEntry.cs/Database.cs | kdbx格式 | bugfix | confirmed |
| 23 | f83554c8（2018-02-12） | prevent database from being loaded twice simultaneously, fixes #15 | PasswordActivity 可重入触发两次并发加载数据库（双重解密/状态错乱）；加 _performingLoad 标志防重入 | 快速重复触发打开/加载 | src/keepass2android/PasswordActivity.cs | 并发 | bugfix | confirmed |
| 24 | 980df2b3（2025-01-14） | fix hostname matching logic | URL 匹配用子串包含，主机名互为子串即命中致凭据建议给错站点；改为精确相等或 "."+host 后缀匹配 | 主机名互为子串的不同站点 | src/Kp2aBusinessLogic/SearchDbHelper.cs | 凭据passkey | bugfix | confirmed |
| 25 | 84c96325（2019-03-11） | using public suffix to determine canonical domains which fixes the aliexpress issue (#711) | 填充域匹配无可注册域归一，子域与主域不互通（aliexpress 问题）；引入 Mozilla PSL DomainParser 取 registrable domain 再比对 | 子域与可注册域不一致的站点 | services/AutofillBase/DomainParser.cs(新增)/StructureParser.cs | 凭据passkey | bugfix | confirmed |
| 26 | e2e7666c（2018-12-10） | fix incorrect webDomain checking, fixes #592 | 解析器 webDomain 一致性检查条件写反（==应为!=），子节点异域字段不抛 SecurityException 即通过校验 | 同一填充结构含不同 webDomain | services/AutofillBase/StructureParser.cs | Autofill | bugfix | confirmed |
| 27 | 0f5b411d（2025-02-11） | Fix an issue that autofill didn't work with compose apps as described on #2371 | Compose 应用视图节点不带 PackageId，域名/包名匹配落空致填充失效；解析后回退取 Structure.ActivityComponent.PackageName | Compose 应用的填充/保存 | services/AutofillBase/StructureParser.cs | Autofill | bugfix | confirmed |
| 28 | 07f08a88（2025-03-11） | autofill: avoid crash when looking up a null key. closes #2362 | 填充提示映射按 null hint 查找抛崩溃；hint 为 null 时返回空串防崩溃 | 页面字段 hint 为 null | src/Kp2aAutofillParser/AutofillParser.cs | Autofill | bugfix | confirmed |
| 29 | 6110166a（2023-03-16） | code simplification and fix for Autofill not being able to save credentials, closes #2269 | 保存凭据流程解析得 null autofillView 且字段类型不匹配致保存失败；非泛型化+FillFilledAutofillValue 多态+空值防护 | 经 Autofill 保存新凭据 | AutofillParser.cs/StructureParser.cs | Autofill | bugfix | confirmed |
| 30 | 85d852cc（2020-06-13） | fix #1282 (saving through autofill not working on Android 10 due to missing NewTask flag) | 从服务上下文启动保存界面缺 NewTask 标志，Android 10 上保存失败；补 NewTask\|ClearTop\|SingleTop | Android 10 经 Autofill 保存 | services/Kp2aAutofill/Kp2aAutofillService.cs | Autofill | bugfix | confirmed |
| 31 | 3fb358ca（2021-01-10） | further improvements for Autofill for usability with Firefox (#1399), also fix security bug (#1527) | 焦点 hints 为空(Firefox)时按 null 取分区索引，分区过滤失效可把口令填入不相关字段；仅当有焦点 hints 才按分区过滤，取消时释放填充锁 | Firefox 等 hints 缺失的填充请求 | AutofillServiceBase.cs/AutofillHintsHelper.cs | Autofill | bugfix | confirmed |
| 32 | ca61fa9d（2021-01-02） | several changes to fix #1399: adding a lock to avoid flickering/disappearing prompt… | Firefox 一次连接连发多个 onFillRequest 致提示闪烁/消失；加 AtomicBoolean 锁请求期间拒绝重入，认证活动改返回 fill-response | Firefox 重复 onFillRequest | services/AutofillBase/AutofillServiceBase.cs | Autofill | bugfix | confirmed |
| 33 | 712f476d（2021-06-14） | call SetInvalidatedByBiometricEnrollment for security reasons, but show a warning to users… | 生物识别密钥设 InvalidatedByBiometricEnrollment(false)，新增指纹后旧密钥仍可解密；改回 true 使生物特征变化即失效并警告勿忘主密码 | 录入新指纹/面部后解锁 | src/keepass2android/BiometricModule.cs | 凭据passkey | bugfix | confirmed |
| 34 | db74e573（2017-12-02） | catch exception when decrypting key fails, fixes #50 | 指纹密钥被系统失效后解密存档主密码抛 GeneralSecurityException 未捕获致崩溃；捕获后走密钥失效重置流程 | 生物特征/锁屏变更后解锁 | PasswordActivity.cs/QuickUnlock.cs | 凭据passkey | bugfix | confirmed |
| 35 | fcc4d447（2024-01-03） | replace base32 parsing algorithm to fix issue with some TOTP entries using KeePass2 style #2020 #2246 | MemUtil.ParseBase32 解析 KeePass2 风格 base32 的 TOTP 种子出错致动态码算错；换 Base32.Decode | KeePass2 风格 base32 TOTP 种子 | src/keepass2android/Totp/Keepass2TotpPluginAdapter.cs | 凭据passkey | bugfix | confirmed |
| 36 | 6604ae29（2014-10-26） | fixed a bug in TOTP calculation. see workitem 279 | (int)Math.Pow(10,6) 把 999999.999… 截断为 999999，TOTP 模数少 1 致 6 位码有偏；改 Convert.ToInt32 四舍五入 | 计算任意位数 TOTP | src/keepass2android/Totp/Totp_Client.cs | 凭据passkey | bugfix | confirmed |
| 37 | 3fb5749c（2018-11-08） | avoid leakage of IOC username/password to logcat/debuglog for some protocols | 部分协议把 IOC 路径（内嵌用户名/口令）整条写日志；删除或包 #ifdef DEBUG，防凭据泄漏 logcat | 带凭据的 IOC 打日志 | CachingFileStorage.cs/JavaFileStorage.cs/FileStorageSetup*.cs | 敏感数据 | bugfix | confirmed |
| 38 | 77593969（2018-05-07） | fix leaking data to logcat | 插件 SDK 广播接收器把收到的每个键值（即条目字段明文）打进 logcat；删除该日志 | 插件接收条目字段数据 | src/java/Keepass2AndroidPluginSDK2/…/PluginActionBroadcastReceiver.java | 敏感数据 | bugfix | confirmed |
| 39 | 5cb1709f（2018-12-03） | Correctly extract protected fields from intent. Fixes #627 | 受保护字段清单经 Intent 传 JSON 串，SDK 却按 StringArrayExtra 读取得 null；改为解析 JSON 数组 | 插件读取受保护字段清单 | …/pluginsdk/PluginActionBroadcastReceiver.java | 敏感数据 | bugfix | confirmed |
| 40 | 177b36d8（2021-01-17） | fix bug preventing screen protection to work, closes #1543 | FLAG_SECURE 屏幕保护判断把 no_secure_display_check 偏好语义写反，防截屏从未生效；取反修正 | 开启屏幕保护查看条目 | src/keepass2android/Utils/Util.cs | 敏感数据 | bugfix | confirmed |

## 二、对照行全文（40 条）

### 行 1｜c2795b2d（2026-07-20）
- **本质**：后台同步路径以 null 密钥文件名更新库记录，静默清空已存密钥文件关联；修复为 null=保留、空串=显式清除
- **触发**：后台同步时未显式提供 keyfile
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/ui/screens/unlock/SafKeyFileAccess.kt:124,130
- **相似度**：功能相似（都涉及密钥文件关联的持久化），但主项目 rememberKeyFileLocation 偏好的唯一写入/清除点在解锁 UI（SafKeyFileAccess），同步引擎（sync/）与保存路径对该偏好零触碰，不存在「后台路径以 null 回写」的写入者
- **risk**：no

### 行 2｜86b8afe2（2025-09-26）
- **本质**：WebDAV 非分块上传把数据包成 multipart 体致服务器收到非法文件内容；改纯 application/binary PUT 且分块上传默认关闭
- **触发**：WebDAV 非分块上传保存库文件
- **我们的对应位置**：sync/src/main/java/com/keepasskey/sync/webdav/WebDavUploadBody.kt:26-33；sync/src/main/java/com/keepasskey/sync/network/SyncTransferOptions.kt:56
- **相似度**：同类模式（WebDAV 上传体构造），但主项目非分块路径为 data.toRequestBody(application/octet-stream) 纯二进制定长 PUT（WebDavSyncProvider.kt:242），无 multipart 面；分块上传同为默认关闭（DEFAULT_CHUNKED_UPLOAD_ENABLED=false），与 kp2a 修复后口径一致
- **risk**：no

### 行 3｜9c8ee243（2025-11-09）
- **本质**：MOVE 带 Overwrite:T 在目标已存在时多服务器拒为 409；改先删目标再 MOVE、409 重试并清理临时文件
- **触发**：文件事务开启 + 目标已存在
- **我们的对应位置**：sync/src/main/java/com/keepasskey/sync/webdav/WebDavSyncProvider.kt:349-389
- **相似度**：功能相似（同为事务写 PUT 临时名→MOVE 覆盖），主项目 MOVE 循环（attempt 0..1）仅特判 412→ConflictError、2xx→成功，409/423 落入 else 关闭响应后第二次尝试同错、最终抛 ProtocolError(500)「MOVE 失败已清理临时文件」，无 409 兜底
- **risk**：**yes**
- **advice**：既往注释批次已判 yes（WebDavSyncProvider.kt:330）。整改时采纳 kp2a 教训组合：409/423 → 容忍 404 先 DELETE 目标再单次重试 MOVE；且一旦引入「先删目标」步骤，MOVE 失败后的清理**不得**回删临时文件（此时临时文件是唯一新副本，见本表 bcf34081 条），现 :382-389 的「MOVE 失败即 delete(tmpPath)」回滚语义须随该改动同步修订；409/423 与 412 分开报错文案
- **duplicateOf**：注释批次：WebDAV MOVE 覆盖 409 无兜底（WebDavSyncProvider.kt:330）

### 行 4｜bcf34081（2025-11-09）
- **本质**：MOVE 失败后的清理会删临时文件，而失败可能发生在删目标之后，临时文件是唯一新副本；改为保留不回删
- **触发**：WebDAV 事务上传后 MOVE 失败
- **我们的对应位置**：sync/src/main/java/com/keepasskey/sync/webdav/WebDavSyncProvider.kt:382-389
- **相似度**：功能相似（同在事务上传失败后回删临时文件），但触发面不同：kp2a 的坑源于其「先删目标再 MOVE」的两步流程；主项目从不预删目标（Overwrite:T 由服务器原子替换目标，:317-347），MOVE 失败时目标仍是原内容、临时文件非唯一副本，回删临时文件无数据丢失面
- **risk**：no

### 行 5｜b1ae0482（2026-04-02）
- **本质**：临时文件 .tmp 后缀加在扩展名之后，部分服务器禁止改扩展名致上传失败；改为把 .tmp 插到扩展名前保持原扩展名
- **触发**：不支持改扩展名的 WebDAV 服务器
- **我们的对应位置**：sync/src/main/java/com/keepasskey/sync/webdav/WebDavSyncProvider.kt:308,416
- **相似度**：功能相似（同为事务上传临时命名）：主项目临时名 "$remotePath.${UUID}.kpktmp"（如 db.kdbx.<uuid>.kpktmp），末段扩展名被改成 .kpktmp，与 kp2a 修复前的形态同型；kp2a 实测证明存在按扩展名限制上传的服务器
- **risk**：**yes**
- **advice**：把固定后缀插到原扩展名之前（如 db.<uuid>.kpktmp.kdbx），使临时文件保持与目标一致的末段扩展名；同步核对 ATOMIC_TMP_SUFFIX 相关单测与清理匹配（SyncCacheMaintenance 类似物不存在于远端，仅影响命名）。不采纳则在《已知工程限界》登记「按扩展名限制的服务器上事务上传不可用」，并把影响写入 P3-387 目录浏览同域评估

### 行 6｜14e9d03f（2025-11-03）
- **本质**：重命名/移动用未规范化 URL，非 ASCII 字符（空格/变音符）两侧编码不一致致失败；两侧统一规范化
- **触发**：非 ASCII 文件名重命名
- **我们的对应位置**：sync/src/main/java/com/keepasskey/sync/webdav/WebDavUrlCodec.kt:12-25
- **相似度**：同类模式（路径↔URL 编码），主项目单点 encodePath 逐段 UTF-8 编码（空格转 %20），源 URL 与 MOVE Destination 头（WebDavSyncProvider.kt:314-315,334）共用同一 buildUrl 出口，不存在 kp2a 的「C# AbsoluteUri 规范化 vs Java 未解码」双实现漂移
- **risk**：no

### 行 7｜c9a7d56d（2025-03-04）
- **本质**：PROPFIND 响应 href 不含 userinfo，后续请求从 href 拼路径时丢失 WebDAV 用户名/口令；从父路径把凭据拼回子项路径
- **触发**：服务器 PROPFIND href 不含凭据
- **我们的对应位置**：sync/src/main/java/com/keepasskey/sync/webdav/WebDavSyncProvider.kt:143-199
- **相似度**：功能相似（同有 Depth:0 PROPFIND），但主项目从不消费响应里的 href（WebDavPropfindParser 只取 getetag/getcontentlength/getlastmodified/resourcetype，grep href 零命中），全部 URL 由 serverUrl+remotePath 自建，凭据走独立 Authorization 头（:85-86），无「从 href 重建 URL 丢凭据」面
- **risk**：no

### 行 8｜be1cc06a（2020-06-11）
- **本质**：OkHttp 旧版 HTTP/2 实现缺陷（okhttp#4964）致 WebDAV 上传数据异常；强制 HTTP/1.1 并复用客户端连接池变通
- **触发**：WebDAV 协商到 HTTP/2
- **我们的对应位置**：sync/src/main/java/com/keepasskey/sync/network/SyncHttpClientFactory.kt:34-52
- **相似度**：同类模式（同步专用 OkHttpClient 装配），主项目未限制协议版本（OkHttp 5.5.0，gradle/libs.versions.toml:55）；kp2a 规避的是 2018 年 OkHttp HTTP/2 的上游缺陷，该缺陷早已在上游修复，主项目所用现代版本无该触发前提
- **risk**：no

### 行 9｜3159af19（2014-03-23）
- **本质**：http(s) 上传仅处理 401，其余 WebException 被吞致「上传失败当成功」；补 else throw 向上传递
- **触发**：http(s) 上传遇非 401 错误
- **我们的对应位置**：sync/src/main/java/com/keepasskey/sync/webdav/WebDavSyncProvider.kt:258-276
- **相似度**：同类模式（上传响应码裁决），主项目对 PUT 响应逐码显式裁决（412→ConflictError、401/403→AuthenticationError、其余非 2xx/201/204→ProtocolError），无「非白名单错误被吞当成功」的分支
- **risk**：no

### 行 10｜0273b4d2（2020-01-08）
- **本质**：SAF/内容流覆盖写用 "w" 模式不截断，新内容更短时残留旧尾部字节致库损坏；改 "rwt" 截断写
- **触发**：覆盖写更短的库文件
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/data/repository/SafVaultCreation.kt:132-137
- **相似度**：功能相似（SAF 覆盖写回），主项目写回路径已用 "rwt" 截断式（:137，KDoc 明示为 SAF 能力上限并登记限界 §24），同款坑已规避
- **risk**：no

### 行 11｜caf42d42（2018-07-09）
- **本质**：缓存写事务在流 Close() 即写缓存并推远端，保存中途取消也触发写入致损坏/覆盖；改为仅 CommitWrite() 落盘
- **触发**：保存中途取消
- **我们的对应位置**：sync/src/main/java/com/keepasskey/sync/engine/SyncEngine.kt:354-371
- **相似度**：同类模式（本地缓存写 + 远端推送分离），但主项目无「Close() 触发远端推送」的隐式生命周期写：commitLocal 显式两步（先写缓存 :360、后上传 :371），保存取消=不调用；SessionPersistence.save 由显式调用驱动，无流关闭副产物写远端的面
- **risk**：no

### 行 12｜cd189e01（2018-09-18）
- **本质**：离线缓存迁出可被系统/用户清除的外部目录到内部存储，降低本地改动丢失概率
- **触发**：外部缓存被清除
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/sync/SyncCycleSetup.kt:82,93
- **相似度**：同类模式（同步缓存落位），主项目同步缓存已在应用内部 cacheDir（:82），防回滚状态更按 F-23 整改刻意落在 filesDir 持久目录与可丢弃缓存隔离（:93），与 kp2a 修复方向一致且已有既有留痕
- **risk**：no

### 行 13｜a671c4f2（2013-08-08）
- **本质**：缓存模式下远端目标不存在时文件哈希对比出错致保存失败；引入三态 FileHashChange(含 FileNotAvailable)+GetRemoteDataAndHash
- **触发**：缓存模式 + 远端文件不存在时保存
- **我们的对应位置**：sync/src/main/java/com/keepasskey/sync/engine/SyncEngine.kt:204-225
- **相似度**：同类模式（缓存同步的「远端丢失」分支），主项目 recoverFromMetaFailure 已做显式三态：404→以缓存恢复上传（RemoteLostRestored）、其余失败→降级读缓存（RemoteUnreachableUsingCache），无「对不存在的远端做哈希对比出错」面
- **risk**：no

### 行 14｜5fc22b95（2018-04-11）
- **本质**：新增成功打开库后自动本地备份并标记只读，文件损坏后仍有可用副本可取回
- **触发**：每次成功打开数据库
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/session/DatabaseSession.kt（createBackupBeforeSave，限界 §1.4）
- **相似度**：功能相似（同属「库损坏后可取回副本」防护面），主项目已有保存前滚动 .bak（上一次成功写入版本）+ 同步远端副本 + 防回滚摘要链三层承接，与 kp2a「打开即额外备份+标记只读」是防护力度/形态差异，非同型缺陷；.bak 覆盖不到「从未在应用内保存过的外部文件」这一点属既有 §1.4 口径
- **risk**：no

### 行 15｜0cd9df74（2025-06-03）
- **本质**：后台同步用全局 CurrentDb 而非目标 Database 实例，多库/autoopen 时合并作用在错误库；改显式传 Database 实例
- **触发**：多库/autoopen 时后台同步
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/sync/SyncCycleRunner.kt:241-242
- **相似度**：功能相似（多库支持 + 同步），但主项目无「全局 CurrentDb」概念：同步周期在装配时绑定当前打开会话与对应 SyncCache/SyncEngine 实例（SyncCycleSetup.kt:82-93），且无后台自动换库/autoopen 机制，不存在「同步作用到错误库实例」的共享可变全局面
- **risk**：no

### 行 16｜73fc93ed（2025-01-14）
- **本质**：.NET8 迁移后 Argon2 KDF 失效：缺 armeabi-v7a/arm64-v8a 的 libargon2.so 且 DllImport 不再解析；补 .so 并改 LibraryImport+LoadLibrary
- **触发**：Argon2 派生（特定 ABI 构建）
- **我们的对应位置**：crypto/build.gradle.kts:40
- **相似度**：同类面（原生 KDF 内核的 ABI 可用性），主项目 abiFilters 显式覆盖 armeabi-v7a/arm64-v8a/x86/x86_64 四 ABI 且统一产出自有 Rust 内核 so，无「某 ABI 缺 .so 致 KDF 整体不可用」面；原生探活失败回落 JCE 的可观测性残余已由既往注释批次判 yes 跟踪
- **risk**：no
- **duplicateOf**：注释批次：原生 KDF 探活失败静默降级不可观测（Argon2KdfEngine.kt:68）

### 行 17｜e4c17e2e（2016-09-05）
- **本质**：原生 AES-KDF TransformKey256 成功后又对结果 SHA256 哈希致组合键错误无法解锁；改为直接返回变换后的 256 位密钥
- **触发**：走原生 AES-KDF 解锁
- **我们的对应位置**：crypto/src/main/java/com/keepasskey/crypto/kdf/AesKdfEngine.kt:38-40
- **相似度**：同类面（原生 AES-KDF 派生结果的去向），主项目原生分支 `return NativeAesKdf.derive(...)` 直接返回、无二次哈希；兜底 AesKdfJce「原样迁出行为零变更」，两侧等价由 CipherFallbackParityTest 逐字节锁定
- **risk**：no

### 行 18｜50d6598b（2025-04-08）
- **本质**：CryptoStream.Read 单次不保证填满缓冲，解密 secret 被截断；改为循环读至满
- **触发**：从解密流读定长数据块
- **我们的对应位置**：sync/src/main/java/com/keepasskey/sync/network/SyncDownloadLimits.kt；database/src/main/java/com/keepasskey/database/io/LittleEndianUtil.kt:66
- **相似度**：同类模式（包装流定长读取），主项目定长读取均为循环读满（既往批次已核实 LittleEndianUtil 逐字节循环、SyncDownloadLimits 按块累积），同款坑已按同法规避
- **risk**：no
- **duplicateOf**：注释批次：包装流单次 read 不保证读满致解析错位（LittleEndianUtil.kt:66）

### 行 19｜35f2e95d（2017-08-14）
- **本质**：密钥文件 XML/OTP aux 解析用默认 XmlResolver 可被 XXE（读文件/SSRF）；统一 XmlResolver=null+DtdProcessing.Ignore
- **触发**：恶意构造的密钥文件/OTP aux
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/file/KdbxKeyFile.kt:93-121
- **相似度**：同类面（不可信 XML 解析），主项目密钥文件解析为「纯字节标签定位」（KdbxKeyFile KDoc 明示，ISSUE-P2-62），全程无 XML 解析器、无 DTD/实体展开面；PROPFIND DOM 解析器有 XXE 加固特性（WebDavPropfindParser.kt:130,174）；导入走 HardenedXmlReader；内层 XML-DTD 拦截另有 §83 批次设备侧断言
- **risk**：no

### 行 20｜fc2e0fa1（2015-02-02）
- **本质**：PwDate Java 时间转 C 日期时把「日」当 0 基再减 1，kdb 库日期序列化整体偏一天
- **触发**：kdb 库日期序列化/反序列化
- **我们的对应位置**：无直接对应
- **相似度**：该坑位于 kp2a 的 KDB(v3) 专用库 KP2AKdbLibrary；主项目按 PD-53 明示不做 KDBX 4.0 以前格式（KDB v3/KDBX 3.x）读取兼容，无 kdb 日期编码面
- **risk**：excluded（decisionRef：PD-53）

### 行 21｜bc235b3b（2015-02-07）
- **本质**：kdb(v3) 加载每次 UUID 全变致合并把全部数据当新增；改为由组/条目 ID 确定性重建 UUID 保持跨加载稳定
- **触发**：kdb 库再次加载后同步合并
- **我们的对应位置**：无直接对应
- **相似度**：该坑位于 kp2a 的 KdbDatabaseFormat（kdb v3 专属）；主项目仅支持 kdbx v4（UUID 原生驻留、跨加载稳定），PD-53 明示不做老格式兼容，触发面不存在
- **risk**：excluded（decisionRef：PD-53）

### 行 22｜ba1e591d（2015-02-19）
- **本质**：取消保存后再保存重复添加条目造成重复 UUID；AddEntry 加已存在检查，加载失败 Clear() 防半初始化反复报错
- **触发**：取消保存后重试保存/加载失败
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/data/repository/VaultEntryWriteCoordinator.kt:346
- **相似度**：同类模式（条目创建与保存重试），主项目 UUID 在条目创建时一次性赋值（KdbxUuid.random()），保存为「内存树整树序列化写盘」，取消/失败不改变内存树，重试保存是重序列化同一棵树而非重放 AddEntry，无重复添加面；DatabaseSession 打开失败由状态机归位（:144,307,329-350），无半初始化态
- **risk**：no

### 行 23｜f83554c8（2018-02-12）
- **本质**：PasswordActivity 可重入触发两次并发加载数据库（双重解密/状态错乱）；加 _performingLoad 标志防重入
- **触发**：快速重复触发打开/加载
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/session/DatabaseSession.kt:51,94
- **相似度**：同类面（库打开并发防护），主项目 SessionOpener 持有 DatabaseSession 的同一 Mutex（:51、:94 构造注入），打开全程串行化，不存在二次并发加载面
- **risk**：no

### 行 24｜980df2b3（2025-01-14）
- **本质**：URL/主机名匹配用子串包含，主机名互为子串即命中致凭据建议给错站点；改精确相等或「."+host 后缀匹配
- **触发**：主机名互为子串的不同站点
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/autofill/AutofillCandidateRanker.kt
- **相似度**：同类面（凭据候选的域匹配），主项目候选按 PSL registrableDomain 严格匹配（兄弟子域/私有段/IP/PSL 不可用恒 fail-closed，负例单测锁定），无子串包含匹配面；既往注释批次已核实同款坑为 no
- **risk**：no
- **duplicateOf**：注释批次：autofill/搜索主机名匹配缺陷未按可注册域匹配（AutofillCandidateRanker.kt:272）

### 行 25｜84c96325（2019-03-11）
- **本质**：填充域匹配无可注册域归一，子域与主域不互通（aliexpress 问题）；引入 Mozilla PSL DomainParser 取 registrable domain 再比对
- **触发**：子域与可注册域不一致的站点
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/passkey/PublicSuffixList.kt；app/src/main/java/com/keepasskey/app/autofill/AutofillCandidateRanker.kt
- **相似度**：同类面（域匹配的 PSL 归一），主项目接入 Mozilla PSL 且 §356 已完成分值档对账（registrableDomain/SAME_BASE_DOMAIN 等五档严格序），起点即高于 kp2a 修复前形态；同款坑既往批次已判 no
- **risk**：no
- **duplicateOf**：注释批次：autofill/搜索主机名匹配缺陷未按可注册域匹配（AutofillCandidateRanker.kt:272）

### 行 26｜e2e7666c（2018-12-10）
- **本质**：解析器对「同一填充结构含不同 webDomain」的一致性校验条件写反（==应为!=），异域字段不抛 SecurityException 即通过；修复后同结构混域即拒
- **触发**：同一填充结构含不同 webDomain（如跨域 iframe 混合表单）
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/autofill/AutofillFieldScanner.kt:177-190
- **相似度**：功能相似（同一标准 Autofill 结构树上的 webDomain 归属判定），但主项目不仅没有写反的校验，而是**完全没有**跨节点 webDomain 一致性校验：scanner 取首个非空 webDomain 胜出（:185-187），AutofillOriginResolver 只对这一个域做受信浏览器/DAL 归属校验（AutofillOriginResolver.kt:43-75）。受信浏览器下，顶层页域与恶意 iframe 域混合的结构会以首见域取候选、凭据可被填进另一域的 iframe 字段，归属展示与实际落点可背离——kp2a 修复后的语义正是拦这个面
- **risk**：**yes**
- **advice**：在 resolveUsableWebDomain 之前对结构内全部非空 webDomain 归一化后判一致性：出现 ≥2 个互异域时 fail-closed 置 null（本次放弃域维度匹配，回落包名/绑定维度）或直接拒绝本次填充域匹配；同批补用例——同域多节点正例 + 跨域 iframe 负例 + 全空域正例。落点在 AutofillFieldScanner.scan 返回前或 AutofillOriginResolver 入口，不动 trusted browser 判定本体

### 行 27｜0f5b411d（2025-02-11）
- **本质**：Compose 应用视图节点不带 PackageId，按节点 idPackage 取包名落空致填充失效；解析后回退取 Structure.ActivityComponent.PackageName
- **触发**：Compose 应用的填充/保存
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/autofill/KeePasskeyAutofillService.kt:127；app/src/main/java/com/keepasskey/app/autofill/AutofillStructureScan.kt:80
- **相似度**：同类面（填充请求的调用方包名来源），主项目包名恒取 structure.activityComponent.packageName（即 kp2a 修复所用的回退源）并单点注入每个 ScanNode（:80），从头就不依赖节点 idPackage，触发面不存在
- **risk**：no

### 行 28｜07f08a88（2025-03-11）
- **本质**：填充提示映射按 null hint 查字典抛崩溃；hint 为 null 时返回空串防崩溃
- **触发**：页面字段 hint 为 null
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/autofill/AutofillStructureScan.kt:108；app/src/main/java/com/keepasskey/app/autofill/AutofillFieldScanner.kt:244,250-253
- **相似度**：同类面（hint 缺失的健壮性），主项目 hints 经 node.autofillHints?.toList().orEmpty() 归一为非空列表，hint 判定走 any{谓词} 与归一化字符串比较，无「按 null 键查映射」结构；label/hint 以 String? 承载判空后才消费
- **risk**：no

### 行 29｜6110166a（2023-03-16）
- **本质**：保存凭据流程解析得 null autofillView 且字段类型不匹配致保存失败；非泛型化+多态取值+空值防护
- **触发**：经 Autofill 保存新凭据
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/autofill/AutofillSaveExtractor.kt:37-50
- **相似度**：同类面（Autofill 保存路径取值），主项目以节点索引回查文本，getOrNull+orEmpty 双层空防护，密码/用户名各带按 inputType 与 hint 的兜底搜寻（:41-62），空密码即整个放弃保存（:367-371），无 null 解引用/类型失配崩溃面
- **risk**：no

### 行 30｜85d852cc（2020-06-13）
- **本质**：从服务上下文直接 startActivity 启动保存界面缺 NewTask 标志，Android 10 上保存失败；补 NewTask\|ClearTop\|SingleTop
- **触发**：Android 10 经 Autofill 保存
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/autofill/KeePasskeyAutofillService.kt:414-433
- **相似度**：功能相似（Autofill 保存 UI），但主项目不从服务上下文 startActivity：锁定态保存经 callback.onSuccess(pendingIntent.intentSender) 把 PendingIntent 交系统拉起 PasswordSaveActivity（:426-432），非服务直接启动，无需也不应加 NEW_TASK（既往批次已核实 CM 侧 CredentialPendingIntents 同样确无 NEW_TASK）
- **risk**：no

### 行 31｜3fb358ca（2021-01-10）
- **本质**：Firefox 焦点 hints 为空时按 null 取分区索引，分区过滤失效可把口令填入不相关字段；仅当有焦点 hints 才按分区过滤
- **触发**：Firefox 等 hints 缺失的填充请求
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/autofill/KeePasskeyAutofillService.kt（标准 AssistStructure 单通道）
- **相似度**：主项目无 Firefox 专有通道与「按焦点 hints 分区过滤」结构，走标准 AssistStructure 全树扫描 + 候选打分；同款坑既往注释批次已判 no（无自有 Firefox 通道专有数据结构）
- **risk**：no
- **duplicateOf**：注释批次：Firefox 下焦点字段 canonical hints 可能为空须判空跳过分区过滤

### 行 32｜ca61fa9d（2021-01-02）
- **本质**：Firefox 一次连接连发多个 onFillRequest 致提示闪烁/消失；加 AtomicBoolean 锁请求期间拒绝重入
- **触发**：Firefox 重复 onFillRequest
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/autofill/KeePasskeyAutofillService.kt:91
- **相似度**：主项目走标准 AutofillService 单连接单回调契约（超时/异常各恰好一次，取消路径静默），无多次应答面；同款坑既往注释批次已核实为 no
- **risk**：no
- **duplicateOf**：注释批次：Firefox 一次连接多次回调 onFillRequest 须超时锁去重应答（KeePasskeyAutofillService.kt:91）

### 行 33｜712f476d（2021-06-14）
- **本质**：生物识别密钥误设 InvalidatedByBiometricEnrollment(false)，新增指纹后旧密钥仍可解密；改回 true 并警告勿忘主密码
- **触发**：录入新指纹/面部后解锁
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/security/KeystoreManager.kt:112-128
- **相似度**：同类面（生物识别密钥吊销语义），主项目纯生物识别密钥显式 setInvalidatedByBiometricEnrollment(true)（ISSUE-P1-08 整改留痕，KDoc 明示「系统录入/清空指纹时自动吊销」），与 kp2a 修复后口径一致
- **risk**：no

### 行 34｜db74e573（2017-12-02）
- **本质**：指纹密钥被系统失效后解密存档主密码抛 GeneralSecurityException 未捕获致崩溃；捕获后走密钥失效重置流程
- **触发**：生物特征/锁屏变更后解锁
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/ui/screens/unlock/BiometricUnlockCoordinator.kt:162-181,250-268
- **相似度**：同类面（生物识别解封异常处置），主项目启动侧捕获 KeyPermanentlyInvalidatedException → 清凭据+删密钥别名+回落主密码（:162-173），解封侧 doFinal 的通用 Exception catch → 清陈旧凭据、下次主密码解锁自动重新封印（:250-268，KDoc 明示杜绝「永远解不开又永不重登记」死循环），与 kp2a 修复语义一致且更完整
- **risk**：no

### 行 35｜fcc4d447（2024-01-03）
- **本质**：MemUtil.ParseBase32 解析 KeePass2 风格 base32 的 TOTP 种子出错致动态码算错；换 Base32.Decode
- **触发**：KeePass2 风格 base32 TOTP 种子
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/otp/TotpKeyUriParser.kt（严格 A-Z2-7 字母表）
- **相似度**：同类面（TOTP 种子格式广度）：主项目仅支持「otp」字段与 otpauth:// URI 的标准 base32（既往批次已核实 KeePass2 内建 TimeOtp-*/TrayTOTP/KeeOtp 风格全仓零实现，grep 零命中），因此该**解析 bug** 的触发面不存在（不支持即不会解错），但「这些风格的种子无法取码」的广度缺口是既往批次标记的待产品裁决项（P3-383 为其 Steam 变体子集）
- **risk**：no
- **duplicateOf**：注释批次：TOTP 格式广度缺口——KeePass2/TrayTOTP/KeeOtp 风格未支持（待产品裁决，KdbxConstants.kt:128/TotpKeyUriParser.kt:76）

### 行 36｜6604ae29（2014-10-26）
- **本质**：(int)Math.Pow(10,6) 把 999999.999… 截断为 999999，TOTP 模数少 1 致 6 位码有偏；改四舍五入
- **触发**：计算任意位数 TOTP
- **我们的对应位置**：core/src/main/java/com/keepasskey/core/otp/OtpEngine.kt:33-34,96-102
- **相似度**：同类面（TOTP 模数取值），主项目 10^digits 走编译期 int 查表 POW10（ISSUE-P3-173），浮点 pow 仅作 >9 位的越界回退（digits 实际恒为 6/8，走表），模数恒精确，无截断偏差面
- **risk**：no

### 行 37｜3fb5749c（2018-11-08）
- **本质**：部分协议把内嵌用户名/口令的 IOC 路径整条写日志，防凭据泄漏 logcat；删除或包 #ifdef DEBUG
- **触发**：带凭据的 URL/路径打日志
- **我们的对应位置**：sync/src/main/java/com/keepasskey/sync/webdav/WebDavSyncProvider.kt:63-86
- **相似度**：同类面（同步路径日志卫生），主项目凭据不进 URL（Basic 头构造期单点算好，:63-66 KDoc 明示防「清零后延迟求值」）、日志仅含 remotePath/状态码/异常类型；自动填充节点结构体覆写 toString 只出非秘密摘要（AutofillStructureScan.kt:35-40，ISSUE-P2-68），无「带凭据字符串进日志」面
- **risk**：no

### 行 38｜77593969（2018-05-07）
- **本质**：插件 SDK 广播接收器把收到的每个键值（即条目字段明文）打进 logcat；删除该日志
- **触发**：插件接收条目字段数据
- **我们的对应位置**：无直接对应
- **相似度**：主项目五模块无插件宿主/SDK（P3-388 已登记为评估类待办），无插件广播接收面；若未来实施插件宿主，须把「跨进程条目字段严禁落日志」作为评估 AC 之一
- **risk**：no

### 行 39｜5cb1709f（2018-12-03）
- **本质**：受保护字段清单经 Intent 传 JSON 串，SDK 却按 StringArrayExtra 读取得 null；改为解析 JSON 数组
- **触发**：插件读取受保护字段清单
- **我们的对应位置**：无直接对应
- **相似度**：同上：无插件宿主面（P3-388 评估中）；主项目跨 Activity 传 extra 全为 String/ArrayList<String> 原始类型且读写两侧同源（既往批次已核实），无「写入形态与读取 API 失配」面
- **risk**：no

### 行 40｜177b36d8（2021-01-17）
- **本质**：FLAG_SECURE 屏幕保护判断把 no_secure_display_check 偏好语义写反，防截屏从未生效；取反修正
- **触发**：开启屏幕保护查看条目
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/security/FlagSecureGuard.kt:44-64
- **相似度**：同类面（防截屏开关语义），主项目判定收敛为纯函数 FlagSecurePolicy.shouldApplySecure（锁定态恒强制 ∨ 用户开关），且 2026-09-12 已把原「关闭无效」假开关整改为「开关即生效」语义（KDoc 留痕），极性与偏好语义由 JVM 用例锁定，无写反面
- **risk**：no

## 三、复核修正记录

1. **复核确认（未改动）**：yes 行 9c8ee243 成立——WebDavSyncProvider.kt:353-380 MOVE 循环仅特判 412→ConflictError 与 2xx/201/204，sync/src/main 全模块 grep -rn "409|423" 仅命中 S3ClockSkewGuard.kt:96 的 4096 常量（非状态码），TransientHttpRetry.kt:33-34 可重试集 408/425/429/5xx 不含 409/423，上层 SyncEngine 无二次兜底；duplicateOf 所指注释批次真实存在（docs/references/参考项目踩坑对照/03-keepass2android.md:578-583）；advice 纯 sync 模块协议分支，不违反模块依赖与敏感数据纪律。
2. **复核确认（未改动）**：yes 行 b1ae0482 成立——WebDavSyncProvider.kt:308 tmpPath="$remotePath.${UUID.randomUUID()}$ATOMIC_TMP_SUFFIX"、:416 ATOMIC_TMP_SUFFIX=".kpktmp"，临时名末段扩展名确被替换为 .kpktmp（db.kdbx.<uuid>.kpktmp），与 kp2a 修复前同型；测试锚点 WebDavSyncProviderTest.kt:209,287、StatefulMockServers.kt:67、WebDavSyncScenarioTest.kt:235 均断言 endsWith(".kpktmp")，advice 提示同步核对测试准确可行。
3. **复核确认（未改动）**：yes 行 e2e7666c 成立——AutofillFieldScanner.kt:185-187 首个非空 webDomain 胜出、全函数无跨节点一致性判定；AutofillOriginResolver.kt:43-75 只对这单个域做归属校验（受信浏览器分支 :53 直接过）；AutofillStructureScan.kt:79,119 装配层零一致性闸门；AutofillFieldScannerTest/AutofillWebDomainPolicyTest grep「混域/互异/跨域/不一致」零命中，无既有守卫；advice（≥2 互异域 fail-closed + 三类用例）不违反硬纪律。
4. **复核确认（未改动）**：excluded 行 fc2e0fa1 与 bc235b3b 抽查通过（2/2 全抽）——产品裁决登记.md:1626-1638 PD-53 真实存在（2026-09-28 用户明示），裁决=「major≠4 一律拒绝、不新增 KDB v3/KDBX 3.x 解析通路、对照评估不得登记为缺陷」，两坑均位于 kp2a 的 kdb v3 专属代码（KP2AKdbLibrary/KdbDatabaseFormat），语义确被 PD-53 覆盖。
5. **复核确认（未改动）**：no 行 bcf34081 的「临时文件非唯一副本」前提在现行代码下成立（主项目从不预删目标，Overwrite:T 由服务端原子替换），但与 9c8ee243 的 advice 存在既有联动——一旦引入先删目标步骤，:382-388 回删临时文件即成数据丢失面；该联动已写明在 9c8ee243 行的 advice 内，无需另行修改。
