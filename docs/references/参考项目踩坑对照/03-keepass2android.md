# 03 · keepass2android（云同步参考 🥈）踩坑对照详情

> 来源：参考项目 `参考项目/keepass2android/`（仓库内路径前缀 `src/`）的踩坑标注与主项目 KeePasskey 的逐条对照。
> 扫描日期 2026-09-28。踩坑标注 37 条，对照行 37 条（risk=yes 3 / unclear 1 / no 33）。
> 总表见 [00-对照总表.md](00-对照总表.md)。

## 一、踩坑标注（37 条）

### 1. src/keepass2android-app/GroupBaseActivity.cs:1090
- **代码上下文**：搜索菜单 SearchView 激活路径
- **原文**：
  > This is the start of a pretty hacky workaround to avoid a crash on Samsung devices with Android 9.
- **链接**：`https://stackoverflow.com/questions/54530604/app-crash-but-no-app-specific-code-in-stack-trace`（可达性：no）
- **坑的本质**：三星 Android 9 修改版 InputMethodService 导致激活搜索视图即崩溃、栈迹无 app 代码；先启动再关闭一个空 Activity 后才能安全激活，属无根因的规避（链接403不可达，基于注释推断）
- **置信度**：confirmed

### 2. src/keepass2android-app/services/AutofillBase/AutofillServiceBase.cs:83
- **代码上下文**：Autofill 服务应答基类
- **原文**：
  > use a lock to avoid returning a response several times in buggy Firefox during one connection: this avoids flickering
- **链接**：无（可达性：none）
- **坑的本质**：Firefox 缺陷导致一次连接内多次回调 onFillRequest；不加锁会出现提示闪烁/消失，故用 2 秒超时锁去重应答
- **置信度**：confirmed

### 3. src/Kp2aAutofillParser/AutofillParser.cs:518
- **代码上下文**：按分区过滤已填充字段集合
- **原文**：
  > only apply partition data if we have FocusedAutofillCanonicalHints. This may be empty on buggy Firefox.
- **链接**：无（可达性：none）
- **坑的本质**：Firefox 下焦点字段的 canonical hints 可能为空，直接取 FirstOrDefault 算分区会出错；须先判空跳过分区过滤
- **置信度**：confirmed

### 4. src/Kp2aAutofillParser/AutofillParser.cs:806
- **代码上下文**：定义 IncompatiblePackageAndDomain 标志
- **原文**：
  > If we would fill credentials for the package, a malicious website could try to get credentials for the app.
- **链接**：无（可达性：none）
- **坑的本质**：包名与 Web 域不兼容（DAL 未绑定且非可信浏览器）时不可直接填充：恶意网站可钓 app 凭据、恶意 app 可钓网站凭据；须挂警告改道人工确认
- **置信度**：confirmed

### 5. src/keepass2android-app/settings/AppSettingsActivity.cs:1222
- **代码上下文**：设置页注册返回栈变化监听
- **原文**：
  > TODO adding this makes the app crash on back (https://github.com/dotnet/android-libraries/issues/1055)
- **链接**：`https://github.com/dotnet/android-libraries/issues/1055`（可达性：yes）
- **坑的本质**：实现 IOnBackStackChangedListener 会触发 Xamarin.AndroidX.Fragment 的 AbstractMethodError 崩溃（issue 可达证实）；注册即「按返回键崩溃」，只能注释禁用并放弃标题联动
- **置信度**：confirmed

### 6. src/keepass2android-app/Utils/Util.cs:60
- **代码上下文**：获取本地化后的 App Context
- **原文**：
  > the standard way of setting locale on the app context is through Application.AttachBaseContext, but because of https://bugzilla.xamarin.com/11/11182/bug.html this doesn't work
- **链接**：`https://bugzilla.xamarin.com/11/11182/bug.html`（可达性：no）
- **坑的本质**：Xamarin bug 11182：Application.AttachBaseContext 设 locale 的标准路径失效；须对 Application.Context 手动调 setLocale 兜底（链接 DNS 失效，基于注释推断）
- **置信度**：confirmed

### 7. src/KeePassLib2Android/Serialization/FileTransactionEx.cs:68
- **代码上下文**：写库事务包装的初始化
- **原文**：
  > Prevent transactions for FTP URLs under .NET 4.0 in order to avoid/workaround .NET bug 621450
- **链接**：无（可达性：no）
- **坑的本质**：.NET bug 621450：FTP 上重命名失败使事务式写库（先写临时文件再改名）不可用，只能对 ftp: 协议禁用事务、退化为直接覆盖写，失去断电保护（链接已重定向到 bing，判定失效）
- **置信度**：confirmed

### 8. src/KeePassLib2Android/Serialization/IOConnection.cs:583
- **代码上下文**：FTP 重命名（上传后改名）实现
- **原文**：
  > We're affected by .NET bug 621450: ... Prepending "./", "%2E/" or "Dummy/../" doesn't work.
- **链接**：无（可达性：no）
- **坑的本质**：同一 .NET 621450 bug 在 FTP Rename 处的备注：曾尝试 ./、%2E/、Dummy/../ 等路径前缀规避均无效，坐实无法在代码层绕过，只能放弃事务（链接失效，基于注释推断）
- **置信度**：confirmed

### 9. src/KeePassLib2Android/Serialization/HashedBlockStream.cs:255
- **代码上下文**：kdbx HashedBlock 流逐块读取
- **原文**：
  > catch (NullReferenceException) // Mono bug workaround (LaunchPad 783268)
- **链接**：无（可达性：none）
- **坑的本质**：Mono 运行时 bug（LaunchPad 783268）：读块长度时 BinReader 可抛 NullReferenceException 而非正常 EOF；Unix 上须吞掉该异常当作流结束，否则正常收尾的 kdbx 会被判损坏
- **置信度**：confirmed

### 10. src/keepass2android-app/EntryEditActivity.cs:1330
- **代码上下文**：编辑页状态恢复 Reload()
- **原文**：
  > this reload ìs necessary to overcome a strange problem with the extra string fields which get lost somehow after re-creating the activity. Maybe a Mono for Android bug?
- **链接**：无（可达性：none）
- **坑的本质**：Activity 重建（旋转/回收）后附加字符串字段神秘丢失（疑 Mono for Android bug）；靠 Finish+ForwardResult 自重启 Reload 绕过，属高成本规避
- **置信度**：confirmed

### 11. src/Kp2aBusinessLogic/Io/NetFtpFileStorage.cs:317
- **代码上下文**：FTP 目录列举实现
- **原文**：
  > For some reason GetListing(path) does not always return the contents of the directory. ... [bug #2423]
- **链接**：无（可达性：none）
- **坑的本质**：FTP 服务器 LIST 实现怪癖：GetListing(path) 偶发返回空目录；先 SetWorkingDirectory(path) 再 GetListing(null) 才稳定（bug #2423）；若信任首次空结果会误判目录为空
- **置信度**：confirmed

### 12. src/java/JavaFileStorage/app/src/main/java/keepass2android/javafilestorage/SftpStorage.java:604
- **代码上下文**：SFTP IoConnectionInfo 解析/拼装（buildUri 处 718 行同注）
- **原文**：
  > Encode/decode required to support IPv6 (colons break host:port parse logic) See Bug #2350
- **链接**：无（可达性：none）
- **坑的本质**：IPv6 主机含冒号，会破坏 host:port 切分与 URI 拼装（bug #2350）；宿主须 URL 编码、解析端解码往返，漏掉任一侧 IPv6 地址即解析失败
- **置信度**：confirmed

### 13. src/Kp2aBusinessLogic/Io/BuiltInFileStorage.cs:101
- **代码上下文**：本地文件外部修改快速检测（时间戳比对）
- **原文**：
  > don't use > operator because milliseconds are truncated
- **链接**：无（可达性：none）
- **坑的本质**：文件系统 LastWriteTime 毫秒被截断，直接用 > 比较时间戳不可靠，须退化为 1 秒粒度阈值；依赖毫秒差判外部改动会漏检
- **置信度**：confirmed

### 14. src/KeePassLib2Android/Cryptography/KeyDerivation/AesKdf.cs:175
- **代码上下文**：AES-KDF 密钥派生的 AES-ECB 引擎自检
- **原文**：
  > !iCrypt.CanReuseTransform -- doesn't work with Mono
- **链接**：无（可达性：none）
- **坑的本质**：Mono 下 ICryptoTransform.CanReuseTransform 不可信（不工作），只能靠块大小与 null 检查手验加密器；依赖该属性的通用断言在 Mono 上会误判
- **置信度**：confirmed

### 15. src/KeePassLib2Android/Security/ProtectedString.cs:31
- **代码上下文**：进程内存保护字符串选型
- **原文**：
  > SecureString objects are limited to 65536 characters, don't use
- **链接**：无（可达性：none）
- **坑的本质**：SecureString 有 65536 字符硬上限（且加解密场景受限），保护内存中的口令数据不能用它，须自建加密内存方案（ProtectedString/ISwappedData）
- **置信度**：confirmed

### 16. src/KeePassLib2Android/Serialization/KdbxFile.Write.cs:903
- **代码上下文**：kdbx XML 中内建字段名写出
- **原文**：
  > By default, language-dependent conversions should be applied, otherwise characters could be rendered incorrectly (code page problems).
- **链接**：无（可达性：none）
- **坑的本质**：kdbx 内建名称是否本地化（g_bLocalizedNames）决定是否做语言相关字符变换；不做则非拉丁码页下字符渲染损坏，是格式互操作的隐性约定
- **置信度**：confirmed

### 17. src/keepass2android-app/search/SearchResults.cs:51
- **代码上下文**：搜索结果页 onCreate 加读锁
- **原文**：
  > we don't want any background thread to update/reload the database while we're in this activity. We're showing a temporary group, so background updating doesn't work well.
- **链接**：无（可达性：none）
- **坑的本质**：搜索结果引用临时 group，后台同步/重载会把内存库换掉导致展示错乱；须持有 DatabasesBackgroundModificationLock 读锁，拿锁失败即报错退出
- **置信度**：confirmed

### 18. src/keepass2android-app/EntryEditActivityState.cs:82
- **代码上下文**：编辑页状态经 App 变量共享的设计说明
- **原文**：
  > Serializing this state (especially the Entry/EntryInDatabase) can be a performance problem when there are big attachements.
- **链接**：无（可达性：none）
- **坑的本质**：编辑中条目状态走全局 App 变量而非 Intent 序列化：大附件下序列化状态是性能坑，故设计上直接传引用
- **置信度**：confirmed

### 19. src/keepass2android-app/app/AppTask.cs:473
- **代码上下文**：URL 匹配任务（AppTask）生命周期
- **原文**：
  > removed. this causes an issue in the following workflow: When the user wants to find an entry for a URL but has the wrong database open he needs to switch to another database.
- **链接**：无（可达性：none）
- **坑的本质**：过早清除 AppTask 会打断「URL 找条目→发现开错库→切换库」流程：任务在经过 PasswordActivity 时已被删，切到正确库后任务消失；不能在首次经过时就清
- **置信度**：confirmed

### 20. src/keepass2android-app/views/EntrySection.cs:64
- **代码上下文**：条目分区视图 SetTextIsSelectable
- **原文**：
  > TODO: this seems to cause a bug when rotating the device (and the activity gets destroyed) After recreating the activity, the value fields all have the same content.
- **链接**：无（可达性：none）
- **坑的本质**：SetIsSelectable(true) 在 Activity 重建后使各分区值文本框内容互相串（全部显示同一内容），复用/状态恢复与 selectable 文本冲突，至今未修仅留 TODO
- **置信度**：confirmed

### 21. src/keepass2android-app/services/CopyToClipboardService.cs:834
- **代码上下文**：复制凭据后切换输入法对话框
- **原文**：
  > Unfortunately this no longer works starting with Android 9 if our app is not in foreground. first it seemed to be required for Samsung mostly
- **链接**：无（可达性：none）
- **坑的本质**：Android 9 起后台应用弹输入法切换对话框失效（起初以为是三星专属）；API≥28 一律改经 helper Activity 中转以获得前台上下文
- **置信度**：confirmed

### 22. src/keepass2android-app/app/App.cs:377
- **代码上下文**：前台通知服务停止路径
- **原文**：
  > Android 8 requires that we call StartForeground() shortly after starting the service with StartForegroundService. This is not possible when we're closing the service.
- **链接**：无（可达性：none）
- **坑的本质**：Android 8 强制 StartForegroundService 后须短时间内 StartForeground，而「关闭服务」场景不可能也不该转前台；故关闭时不得走 OnStartCommand 的 StopSelf，须直接 StopService，否则 ANR/crash
- **置信度**：confirmed

### 23. src/keepass2android-app/app/App.cs:633
- **代码上下文**：按名取存储类型图标
- **原文**：
  > resource was renamed. do this to avoid crashes with legacy file entries.
- **链接**：无（可达性：none）
- **坑的本质**：skydrive 图标资源历史改名，旧库文件条目仍存旧名；按名反射取资源遇旧名即空引用崩溃，须留旧名→新名映射兜底——改资源名会波及存量数据
- **置信度**：confirmed

### 24. src/Kp2aBusinessLogic/Io/OneDrive2FileStorage.cs:544
- **代码上下文**：OneDrive SDK 异常转 FileNotFoundException
- **原文**：
  > hacky solution to check for not found. errorCode was null in my tests so I had to find a workaround.
- **链接**：无（可达性：none）
- **坑的本质**：OneDrive SDK 异常分类不可靠：ServiceException.ErrorCode 实测为 null，GraphErrorCode 匹配漏报 404，只能对 Message 文本做 "\n\n404 : " 子串匹配识别「不存在」，SDK 升级改文案即失效
- **置信度**：confirmed

### 25. src/keepass2android-app/Totp/KeeOtpPluginAdapter.cs:107
- **代码上下文**：KeeOtp 插件数据的查询串解析
- **原文**：
  > Hacky query string parsing. This was done due to reports of people with just a 3.5 or 4.0 client profile getting errors as the System.Web assembly
- **链接**：无（可达性：none）
- **坑的本质**：System.Web（HttpUtility）在精简 client profile 下缺失导致报错，弃用标准解析、手写只处理 '=' 的 hacky 查询串解析；编码面收窄是刻意取舍
- **置信度**：confirmed

### 26. src/java/KP2AKdbLibrary/app/src/main/java/com/keepassdroid/database/load/ImporterV3.java:413
- **代码上下文**：KDB（v1）格式导入解析图标字段
- **原文**：
  > Clean up after bug that set icon ids to -1
- **链接**：无（可达性：none）
- **坑的本质**：历史上的写入 bug 曾把 KDB 条目图标 id 写成 -1；导入端须把 -1 钳回 0，否则取图标越界/失败——历史坏数据要靠读取端容错长期兜底
- **置信度**：confirmed

### 27. src/Kp2aBusinessLogic/Io/CachingFileStorage.cs:255
- **代码上下文**：缓存同步时下载远端并算哈希
- **原文**：
  > note: directly copying to remoteData and hashing causes NullReferenceExceptions in FTP and with Digest auth
- **链接**：无（可达性：none）
- **坑的本质**：边拷贝到目标流边过 HashingStreamEx 在 FTP 与 Digest 认证下抛 NullReferenceException；必须先拷入临时内存流再重放哈希，直连优化不可做
- **置信度**：confirmed

### 28. src/Kp2aBusinessLogic/Io/IFileStorage.cs:79
- **代码上下文**：文件存储接口的外部变化快速检测契约
- **原文**：
  > Note: This function may return false even if the file might have changed. The function should focus on being fast and cheap instead of doing things like hashing
- **链接**：无（可达性：none）
- **坑的本质**：CheckForFileChangeFast 是刻意不完整的变化检测：允许漏报（返回 false 但文件实际已变），不哈希不全量下载；调用方不能把它当可靠的外部修改检测
- **置信度**：confirmed

### 29. src/Kp2aBusinessLogic/database/edit/SaveDB.cs:253
- **代码上下文**：保存库后的合并/同步流程
- **原文**：
  > note: when synced, the file might be downloaded once again from the server. Caching the data in the hashing function would solve this but increases complexity.
- **链接**：无（可达性：none）
- **坑的本质**：已知的重复下载问题：同步时远端文件可能被再次整份下载；在哈希函数内缓存数据可解决但复杂度不划算，作者选择接受该低效
- **置信度**：suspected

### 30. src/KeePassLib2Android/Cryptography/Cipher/ChaCha20Cipher.cs:65
- **代码上下文**：kdbx ChaCha20 内置加密引擎文档
- **原文**：
  > only 256 GB of data can be encrypted securely (because the block counter is a 32-bit variable); an attempt to encrypt more data throws an exception.
- **链接**：无（可达性：none）
- **坑的本质**：RFC 7539 ChaCha20 32 位块计数器限 256GB 安全加密上限，超限须抛异常而非静默续用（nonce 重用风险）；kdbx 单文件场景足够但调用方须感知该硬界
- **置信度**：confirmed

### 31. src/java/JavaFileStorage/app/src/main/java/keepass2android/javafilestorage/WebDavStorage.java:235
- **代码上下文**：WebDAV MOVE 覆盖目标文件
- **原文**：
  > Use delete-then-move strategy to avoid HTTP 409 conflicts
- **链接**：无（可达性：none）
- **坑的本质**：WebDAV 服务器对 MOVE 覆盖已有目标普遍报 409，仅靠 Overwrite: T 头不可靠；须先删目标再 MOVE（删除失败仅容忍记录，让 MOVE 自然失败）
- **置信度**：confirmed

### 32. src/java/KP2ASoftkeyboard_AS/app/src/main/java/keepass2android/softkeyboard/PluginManager.java:318
- **代码上下文**：软键盘插件数据加载
- **原文**：
  > The try-catch is for issue 878:
- **链接**：`http://code.google.com/p/softkeyboard/issues/detail?id=878`（可达性：no）
- **坑的本质**：issue 878：插件数据加载会抛异常，须 try-catch 包住否则键盘崩溃（code.google.com 已死链超时，基于注释推断）
- **置信度**：confirmed

### 33. src/keepass2android-app/Resources/values/strings.xml:806
- **代码上下文**：1.12 版 changelog（数组资源）
- **原文**：
  > Fix hostname matching in autofill and search
- **链接**：无（可达性：none）
- **坑的本质**：1.12 之前 autofill 与搜索的主机名匹配存在缺陷（未正确按可注册域匹配），1.12 才修复；实现域匹配须以此教训校准粒度
- **置信度**：confirmed

### 34. src/keepass2android-app/Resources/values/strings.xml:854
- **代码上下文**：1.09b 版 changelog
- **原文**：
  > Fix disappearing autofill prompt in Firefox
- **链接**：无（可达性：none）
- **坑的本质**：Firefox 下自动填充提示会消失（一次连接多次应答所致），1.09b 才以超时锁修复——与 AutofillServiceBase.cs:83 的代码内注释互证
- **置信度**：confirmed

### 35. src/keepass2android-app/Resources/values/strings.xml:858
- **代码上下文**：1.09b 版 changelog
- **原文**：
  > Bug fix: Do not make filenames lowercase when saving to Dropbox
- **链接**：无（可达性：none）
- **坑的本质**：曾把保存到 Dropbox 的文件名转小写，造成大小写敏感远端上文件名被改坏；云存储实现不得擅自归一化文件名
- **置信度**：confirmed

### 36. src/keepass2android-app/Resources/values/strings.xml:788
- **代码上下文**：1.14_net 版 changelog
- **原文**：
  > WebDav improvements: Bug fix for listing folders; support for chunked uploads and transactions
- **链接**：无（可达性：none）
- **坑的本质**：WebDAV 目录列举曾有缺陷（1.14 才修），配合代码中 PROPFIND 失败时保守按「文件存在」处理的降级策略，说明列举结果不可尽信
- **置信度**：confirmed

### 37. src/keepass2android-app/Resources/values/strings.xml:824
- **代码上下文**：1.11 版 changelog
- **原文**：
  > Updated TOTP implementation to resolve compatibility issues with KeePass2 and TrayTOTP
- **链接**：无（可达性：none）
- **坑的本质**：TOTP 各实现间存在兼容性问题（KeePass2/TrayTOTP 读不出），1.11 重写才对齐；自研 TOTP 字段解析须与两参考实现交叉对拍
- **置信度**：confirmed

## 二、对照行（37 条）

### 1. GroupBaseActivity.cs:1090 → 无直接对应
- **坑的本质**：三星 Android 9 修改版 InputMethodService 导致激活搜索视图即崩溃、栈迹无 app 代码；先启动再关闭一个空 Activity 后才能安全激活，属无根因的规避
- **触发条件**：自有键盘已激活时在三星 Android 9 上点搜索
- **主项目对应位置**：无直接对应
- **对照情况**：无：主项目搜索 UI 为 Jetpack Compose 自绘（app/src/main/java 全目录 grep "SearchView" 零命中，本会话已执行），不使用平台 SearchView，该 OEM 崩溃面不存在
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 2. AutofillServiceBase.cs:83 → KeePasskeyAutofillService.kt:91
- **坑的本质**：Firefox 缺陷导致一次连接内多次回调 onFillRequest；不加锁会出现提示闪烁/消失，故用 2 秒超时锁去重应答
- **触发条件**：Firefox 发起自动填充且回调多次
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/autofill/KeePasskeyAutofillService.kt:91`
- **对照情况**：同类模式但面不同：主项目只用标准平台 AutofillService（targetSdk 36），无 keepass2android 那套自有 legacy 填充通道；onFillRequest 每次聚焦由框架单次回调，超时/异常路径各恰好回调一次、取消路径按平台契约静默不回调（KeePasskeyAutofillService.kt:96、106-108）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 3. AutofillParser.cs:518 → 无直接对应
- **坑的本质**：Firefox 下焦点字段的 canonical hints 可能为空，直接取 FirstOrDefault 算分区会出错；须先判空跳过分区过滤
- **触发条件**：Firefox 页面无 FocusedAutofillCanonicalHints
- **主项目对应位置**：无直接对应
- **对照情况**：无：该坑是 keepass2android 自有 Firefox 通道专有数据结构的判空问题；主项目经标准 AutofillService 的 AssistStructure 解析（AutofillFieldScanner/AutofillStructureScan），不存在 FocusedAutofillCanonicalHints 这个数据面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 4. AutofillParser.cs:806 → AutofillOriginResolver.kt:18
- **坑的本质**：包名与 Web 域不兼容（DAL 未绑定且非可信浏览器）时不可直接填充：恶意网站可钓 app 凭据、恶意 app 可钓网站凭据；须挂警告改道人工确认
- **触发条件**：PackageName 与 WebDomain 互不匹配的填充请求
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/autofill/AutofillOriginResolver.kt:18`
- **对照情况**：功能相似且已加固：webDomain 仅对「包名+签名证书指纹」二元组校验通过的可信浏览器放行（AutofillOriginResolver.kt:18-20、ISSUE-P1-11），非浏览器应用只按包名匹配；候选按 PSL registrableDomain 严格匹配、不命中恒空不出数据集（AutofillCandidateRanker.kt:257-273，§356 刚以负例锁定），另有确认页人工闸门（AutofillConfirmActivity）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 5. AppSettingsActivity.cs:1222 → 无直接对应
- **坑的本质**：实现 IOnBackStackChangedListener 会触发 Xamarin.AndroidX.Fragment 的 AbstractMethodError 崩溃；注册即「按返回键崩溃」，只能注释禁用并放弃标题联动
- **触发条件**：AddOnBackStackChangedListener 后按返回键
- **主项目对应位置**：无直接对应
- **对照情况**：无：这是 Xamarin.Android 绑定层 bug；主项目为 Kotlin + Compose 导航，全仓 grep "OnBackStackChangedListener" 零命中（本会话已执行），无该监听面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 6. Util.cs:60 → AutofillConfirmActivity.kt:120 ⚠ risk=yes
- **坑的本质**：Xamarin bug 11182：Application.AttachBaseContext 设 locale 的标准路径失效；须对 Application.Context 手动调 setLocale 兜底
- **触发条件**：经 AttachBaseContext 为 Application Context 设语言
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/autofill/AutofillConfirmActivity.kt:120`
- **对照情况**：同类模式（一种语言设置机制未覆盖全部 surface）：主项目应用内语言（appLanguage）只经 Compose 壳包装（KeePasskeyApp.kt:77-85）与解锁 Activity（AutofillUnlockActivity.kt:126-132、CredentialUnlockActivity.kt:101-107 的 createConfigurationContext，AppShellLocalization.kt:32-37）生效；AutofillConfirmActivity.kt:119-127/146 与 AutofillPickerActivity.kt:218-219/305 裸用 getString（系统 locale，本会话 rg "appLanguage|localeFor|AppShellLocalization" 于两文件零命中）、TotpNotificationPublisher.kt:63-65（app/.../notification/）通知文案同样未接 appLanguage——强制英文时确认页/选择器/通知仍随系统语言
- **是否有同样风险**：**yes**
- **建议**：把 appLanguage 接线推广到剩余用户可见面：①AutofillConfirmActivity / AutofillPickerActivity 同族，按 AutofillUnlockActivity.kt:126-132 同法包 createConfigurationContext（或统一改走 AppCompatDelegate.setApplicationLocales 覆盖 activity 面）；注意 CredentialUnlockActivity 已按 AppShellLocalization 同法接线（CredentialUnlockActivity.kt:101-107），勿重复列入；②通知构建（TotpNotificationPublisher、NotificationChannels 等经 context.getString 的路径）用 context.createConfigurationContext(localizedConfigurationOf(...)) 后再取串；③补一条用例锁定「appLanguage=EN 时确认页与通知文案为英文」
- **需补充信息**：（无）

### 7. FileTransactionEx.cs:68 → 无直接对应
- **坑的本质**：.NET bug 621450：FTP 上重命名失败使事务式写库不可用，只能对 ftp: 协议禁用事务、退化为直接覆盖写，失去断电保护
- **触发条件**：ftp:// 库文件 + .NET 4.0 非 Unix 运行时
- **主项目对应位置**：无直接对应
- **对照情况**：无：主项目同步只实现 WebDAV 与 S3 两 Provider（sync/src/main/java/com/keepasskey/sync/{webdav,s3}/，本会话 ls 已核），无 FTP 面；「云同步协议广度」候选已经用户明示不登记（docs/ACTIVE_ISSUES.md:52）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 8. IOConnection.cs:583 → 无直接对应
- **坑的本质**：同一 .NET 621450 bug 在 FTP Rename 处的备注：路径前缀规避均无效，只能放弃事务
- **触发条件**：对 FTP 文件执行 RenameTo 上传提交
- **主项目对应位置**：无直接对应
- **对照情况**：无：同上，主项目无 FTP；WebDAV 上传事务走「PUT 唯一随机名 .kpktmp → MOVE 覆盖」且 MOVE 失败即清理临时文件（WebDavSyncProvider.kt:283-292、388），事务语义不因服务器而禁用
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 9. HashedBlockStream.cs:255 → HmacBlockStream.kt:110
- **坑的本质**：Mono 运行时 bug（LaunchPad 783268）：读块长度时可抛 NullReferenceException 而非正常 EOF；Unix 上须吞掉该异常当作流结束，否则正常收尾的 kdbx 被判损坏
- **触发条件**：Mono/Unix 上读到 hashed block 流末尾
- **主项目对应位置**：`database/src/main/java/com/keepasskey/database/file/HmacBlockStream.kt:110`
- **对照情况**：同类位置、不同运行时：主项目为 kdbx4 的 HmacBlockStream（JVM/ART，无 Mono NRE 面），读块长度负值/零终止块/HMAC 校验均有显式分支（HmacBlockStream.kt:110-124，D20 fail-closed），EOF 正常收尾不会误判损坏
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 10. EntryEditActivity.cs:1330 → 无直接对应
- **坑的本质**：Activity 重建后附加字符串字段神秘丢失（疑 Mono bug）；靠 Finish+ForwardResult 自重启 Reload 绕过，属高成本规避
- **触发条件**：EntryEditActivity 被销毁重建后继续编辑
- **主项目对应位置**：无直接对应
- **对照情况**：无：主项目为 Compose + ViewModel 持引用，跨页面只传 ID extra（AutofillDatasetBuilders.kt:108/188/389 等，本会话 grep 已核），不经 Intent/Bundle 序列化条目对象，无「重建丢字段」面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 11. NetFtpFileStorage.cs:317 → 无直接对应
- **坑的本质**：FTP 服务器 LIST 实现怪癖：GetListing(path) 偶发返回空目录；先 SetWorkingDirectory 再列举才稳定；信任首次空结果会误判目录为空
- **触发条件**：对某些 FTP 服务器列目录
- **主项目对应位置**：无直接对应
- **对照情况**：无：主项目同步为单库文件，元数据探测用 Depth:0 单资源 PROPFIND（WebDavSyncProvider.kt:129/150），不做目录列举，无「空列举误判」面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 12. SftpStorage.java:604 → 无直接对应
- **坑的本质**：IPv6 主机含冒号破坏 host:port 切分与 URI 拼装（bug #2350）；宿主须 URL 编码、解析端解码往返
- **触发条件**：SFTP 目标为 IPv6 字面量地址
- **主项目对应位置**：无直接对应
- **对照情况**：同类模式的邻近面已规避：主项目无 SFTP；WebDAV URL 拼装为「用户输入的完整 origin + 编码路径」字符串拼接（WebDavUrlCodec.kt:21-25），不做手写 host:port 切分，IPv6 字面量按 OkHttp HttpUrl 方括号惯例解析
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 13. BuiltInFileStorage.cs:101 → docs/ACTIVE_ISSUES.md:60（ISSUE-P2-378）⚠ risk=yes
- **坑的本质**：文件系统 LastWriteTime 毫秒被截断，直接用 > 比较时间戳不可靠，须退化为 1 秒粒度阈值；依赖毫秒差判外部改动会漏检
- **触发条件**：以文件修改时间戳做变化检测
- **主项目对应位置**：`docs/ACTIVE_ISSUES.md:60（ISSUE-P2-378）`
- **对照情况**：功能相似且主项目现状更差：本地/SAF 已打开库完全缺失外部修改检测（核实记录 rg "FileObserver|WatchService|lastModified\(\)" 于 app/database/sync main 源码零命中——本会话复核复跑 exit=1 证实，lastModified 仅 sync 远端 HTTP 面）；保存直接静默覆盖无外部改动前置校验（SessionPersistence.kt:42-99，本会话直读证实）；P2-378 已登记开放待办（docs/ACTIVE_ISSUES.md:60-83），其 AC① 规划的正是 mtime+size 基线比对（:78）——keepass2android 这条毫秒截断教训恰是该实现会撞上的粒度坑
- **是否有同样风险**：**yes**
- **建议**：落实 P2-378 AC① 时：①mtime 比较不得依赖毫秒差——本地文件系统与 SAF 元数据存在毫秒截断/秒级粒度，WebDAV 的 Last-Modified（HTTP-date）本就是秒级（WebDavPropfindParser.kt:62-63，本会话已核），基线比对用「≥1 秒粒度阈值 + size」双条件，漂移判不敏感方向（宁可多提示不漏报）；②粒度取舍写进批次文档与 P2-378 的 AC 说明，避免后续以毫秒比较「优化」引入漏检
- **需补充信息**：（无）

### 14. AesKdf.cs:175 → ChaCha20CipherEngine.kt:138
- **坑的本质**：Mono 下 ICryptoTransform.CanReuseTransform 不可信，只能靠块大小与 null 检查手验加密器
- **触发条件**：在 Mono 运行时做 KDF 加密器校验
- **主项目对应位置**：`crypto/src/main/java/com/keepasskey/crypto/cipher/ChaCha20CipherEngine.kt:138`
- **对照情况**：无此面：JVM Cipher 无 CanReuseTransform 类 API；主项目引擎每次调用 initCipher 新建 Cipher 实例（ChaCha20CipherEngine.kt:138-142，AES/Twofish 同范式），AES-KDF 生产派生走 Rust derive_into（AGENTS 规则 2），不依赖任何「加密器复用」假设
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 15. ProtectedString.cs:31 → 无直接对应
- **坑的本质**：SecureString 有 65536 字符硬上限（且加解密场景受限），保护内存中的口令数据不能用它，须自建加密内存方案
- **触发条件**：为长口令/大数据选内存保护容器
- **主项目对应位置**：无直接对应
- **对照情况**：无：主项目敏感数据铁律为 CharArray/ByteArray + 显式清零（AGENTS 规则 2，Rust 侧 Zeroizing RAII），从不使用 SecureString 或任何带长度上限的保护容器，无该选型坑
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 16. KdbxFile.Write.cs:903 → KdbxXmlEntrySerializer.kt:270
- **坑的本质**：kdbx 内建名称本地化与否决定是否做语言相关字符变换；不做则非拉丁码页下字符渲染损坏，是格式互操作的隐性约定
- **触发条件**：写内建字段名且库为本地化语言创建
- **主项目对应位置**：`database/src/main/java/com/keepasskey/database/xml/KdbxXmlEntrySerializer.kt:270`
- **对照情况**：已规避：主项目恒按官方标准英文字段名写出（KdbxXmlEntrySerializer.kt:237-270 明确对照官方 PwDefs.TitleField/UserNameField/PasswordField 五字段与保护位语义），无本地化字段名写出路径
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 17. SearchResults.cs:51 → 无直接对应
- **坑的本质**：搜索结果引用临时 group，后台同步/重载会把内存库换掉导致展示错乱；须持有后台修改读锁，拿锁失败即报错退出
- **触发条件**：搜索结果停留期间触发后台库更新
- **主项目对应位置**：无直接对应
- **对照情况**：当前无此面：主项目无后台自动同步/重载（「回前台同步探测」本身是已登记缺失待办 ISSUE-P3-381，docs/ACTIVE_ISSUES.md:188），同步为用户显式触发；搜索读 StateFlow 内存树而非临时 group。注意：P3-381 整改引入回前台探测后，需重新评估「结果页停留期间树被换掉」是否要加快照/版本守卫
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 18. EntryEditActivityState.cs:82 → AutofillDatasetBuilders.kt:108
- **坑的本质**：编辑中条目状态走全局 App 变量而非 Intent 序列化：大附件下序列化状态是性能坑，故设计上直接传引用
- **触发条件**：编辑含大附件条目时保存/恢复状态
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/autofill/AutofillDatasetBuilders.kt:108`
- **对照情况**：同类设计且已成立：主项目跨页面一律只传 ID extra（AutofillDatasetBuilders.kt:108/188/389、AutofillUnlockActivity.kt:255），条目与附件由仓库/会话内存按引用承载，不经 Intent/Bundle 序列化
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 19. AppTask.cs:473 → KeePasskeyAutofillService.kt:269
- **坑的本质**：过早清除 AppTask 会打断「URL 找条目→发现开错库→切换库」流程：任务在首次经过 PasswordActivity 时已被删，切到正确库后任务消失
- **触发条件**：错误库上响应 URL 任务后切换数据库
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/autofill/KeePasskeyAutofillService.kt:269`
- **对照情况**：同类模式但已规避：主项目填充/选择/解锁流程不在内存「任务」上挂状态，锁库时只下发解锁引导数据集，认证完成后靠框架重发 onFillRequest 自然续接（KeePasskeyAutofillService.kt:163、269-271 注释与设备侧留痕），跨页只传 ID extra，无「任务被过早清掉」的生命周期假设
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 20. EntrySection.cs:64 → 无直接对应
- **坑的本质**：SetIsSelectable(true) 在 Activity 重建后使各分区值文本框内容互相串（复用/状态恢复与 selectable 文本冲突），至今未修仅留 TODO
- **触发条件**：开启文本选择后旋转设备重建页面
- **主项目对应位置**：无直接对应
- **对照情况**：无：主项目为 Jetpack Compose 声明式 UI，无 TextView 视图复用与 View 层状态恢复串值的坑面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 21. CopyToClipboardService.cs:834 → 无直接对应
- **坑的本质**：Android 9 起后台应用弹输入法切换对话框失效（起初以为是三星专属）；API≥28 一律改经 helper Activity 中转以获得前台上下文
- **触发条件**：API≥28 且 app 不在前台时切输入法
- **主项目对应位置**：无直接对应
- **对照情况**：无：主项目无自家输入法、无「复制后切输入法」对话框；填充后动作是写剪贴板 + TOTP 通知（AutofillPostFillTotpActions，grep startActivity 零命中，本会话已核），无后台弹窗面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 22. App.cs:377 → 无直接对应
- **坑的本质**：Android 8 强制 StartForegroundService 后须短时间内 StartForeground，而「关闭服务」场景不可能也不该转前台；关闭时不得走 OnStartCommand 的 StopSelf，须直接 StopService，否则 ANR/crash
- **触发条件**：Android 8+ 上停用常驻通知服务
- **主项目对应位置**：无直接对应
- **对照情况**：无：主项目 app/sync 两模块 grep "startForeground|ForegroundService" 零命中（本会话已核），无前台服务，「启动即停」的 ForegroundServiceDidNotStopInTime 面不存在
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 23. App.cs:633 → 无直接对应
- **坑的本质**：skydrive 图标资源历史改名，旧库文件条目仍存旧名；按名反射取资源遇旧名即空引用崩溃，须留旧名→新名映射兜底——改资源名会波及存量数据
- **触发条件**：打开含旧版存储类型名的库条目
- **主项目对应位置**：无直接对应
- **对照情况**：无：主项目无按名反射取资源（全仓 grep "getIdentifier" 零命中，本会话已核），存储类型为代码内枚举（WebDAV/S3），不存在「存量数据里的资源名」这一持久化面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 24. OneDrive2FileStorage.cs:544 → WebDavSyncProvider.kt:155
- **坑的本质**：OneDrive SDK 异常分类不可靠：ErrorCode 实测为 null，只能对 Message 文本做 "\n\n404 : " 子串匹配识别 404，SDK 升级改文案即失效
- **触发条件**：OneDrive 请求返回 404 的异常转换
- **主项目对应位置**：`sync/src/main/java/com/keepasskey/sync/webdav/WebDavSyncProvider.kt:155`
- **对照情况**：同类语义但已规避：主项目无 OneDrive；404 判定恒走 HTTP 状态码（WebDavSyncProvider.kt:155/213，S3 同），无任何文本匹配识别
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 25. KeeOtpPluginAdapter.cs:107 → TotpKeyUriParser.kt:76
- **坑的本质**：System.Web（HttpUtility）在精简 client profile 下缺失导致报错，弃用标准解析、手写只处理 '=' 的 hacky 查询串解析；编码面收窄是刻意取舍
- **触发条件**：解析含 System.Web 依赖的插件数据
- **主项目对应位置**：`core/src/main/java/com/keepasskey/core/otp/TotpKeyUriParser.kt:76`
- **对照情况**：同类动作（手写查询串解析）但无编码面收窄：主项目本就无 HttpUtility 等价依赖需绕开；TotpKeyUriParser 为字节级扫描，支持 '&'/ casualty '='/'?'/ 百分号解码（BYTE_PERCENT/BYTE_AMPERSAND 等，TotpKeyUriParser.kt:57-64）且有单测，非只处理 '=' 的窄化 hack。但 TOTP 格式广度问题另见 strings.xml:824 条
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 26. ImporterV3.java:413 → 无直接对应
- **坑的本质**：历史上写入 bug 曾把 KDB 条目图标 id 写成 -1；导入端须钳回 0，否则取图标越界——历史坏数据要靠读取端容错长期兜底
- **触发条件**：导入含 iconId=-1 的旧 KDB 库
- **主项目对应位置**：无直接对应
- **对照情况**：无：主项目只支持 .kdbx v4（database 模块无 KDB v1 读取器，database/src/main/java/com/keepasskey/database/file/ 全为 kdbx4 组件，本会话 ls 已核），无 KDB 导入面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 27. CachingFileStorage.cs:255 → 无直接对应
- **坑的本质**：边拷贝到目标流边过 HashingStreamEx 在 FTP 与 Digest 认证下抛 NullReferenceException；必须先拷入临时内存流再重放哈希，直连优化不可做
- **触发条件**：FTP/Digest 认证源的上传前哈希同步
- **主项目对应位置**：无直接对应
- **对照情况**：无：主项目无 FTP/Digest 认证源；S3/WebDAV 上传以内存字节数组为输入、哈希按字节计算，无「边拷边哈希」通道
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 28. IFileStorage.cs:79 → SyncEngine.kt:99
- **坑的本质**：CheckForFileChangeFast 是刻意不完整的变化检测：允许漏报、不哈希不全量下载；调用方不能把它当可靠的外部修改检测
- **触发条件**：依赖快速检测判定远端文件是否变更
- **主项目对应位置**：`sync/src/main/java/com/keepasskey/sync/engine/SyncEngine.kt:99`
- **对照情况**：同类契约但主项目从设计上就没走「快而不全」路线：远端一致性为「ETag 可用按乐观锁零下载比对，无 ETag 服务器回退整档下载 SHA-256 裁决」（SyncEngine.kt:99-101，本会话直读证实），刻意选择可靠而非快速，无「误把快速检测当可靠检测」的面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 29. SaveDB.cs:253 → SyncEngine.kt:99
- **坑的本质**：同步时远端文件可能被再次整份下载；哈希函数内缓存数据可解决但复杂度不划算，作者选择接受该低效
- **触发条件**：保存库后触发同步
- **主项目对应位置**：`sync/src/main/java/com/keepasskey/sync/engine/SyncEngine.kt:99`
- **对照情况**：同类低效面但已被设计吸收且留痕：ETag 可用路径零下载；仅无 ETag 服务器才整档下载一次做哈希裁决（SyncEngine.kt:99-102 注释明写该取舍，本会话直读证实），与 keepass2android「接受低效」同型且无遗漏缓存数据正确性问题
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 30. ChaCha20Cipher.cs:65 → chacha20_stream.rs:40
- **坑的本质**：RFC 7539 ChaCha20 32 位块计数器限 256GB 安全加密上限，超限须抛异常而非静默续用（nonce 重用风险）
- **触发条件**：RFC7539 模式加密量超 256GB
- **主项目对应位置**：`crypto/src/main/rust/src/chacha20_stream.rs:40`
- **对照情况**：同一边界且已显式设闸：原生路径 apply_keystream_at 以 checked_add + 「end_offset ≤ 2^32×64」闸门拒绝越界返回 None（chacha20_stream.rs:39-43 注释明写 256 GiB 上界），上层转 CryptoException；BC 兜底 ChaCha7539 同样按次新建实例；kdbx 单库载荷受下载/大小护栏约束远低于此
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 31. WebDavStorage.java:235 → WebDavSyncProvider.kt:330 ⚠ risk=yes
- **坑的本质**：WebDAV 服务器对 MOVE 覆盖已有目标普遍报 409，仅靠 Overwrite: T 头不可靠；须先删目标再 MOVE（删除失败仅容忍记录，让 MOVE 自然失败）
- **触发条件**：对已存在的目标做 WebDAV 移动/改名（覆盖写提交）
- **主项目对应位置**：`sync/src/main/java/com/keepasskey/sync/webdav/WebDavSyncProvider.kt:330`
- **对照情况**：同一 API 用法且缺同类兜底：主项目 uploadAtomic 以「PUT 临时名 → MOVE 覆盖」提交（WebDavSyncProvider.kt:301-396，MOVE 请求构建 :329-347），MOVE 仅带 Overwrite:T/F + If ETag 预条件并处理 412（PUT 412 :260、MOVE 412 :360），全文件无 409/423 分支（本会话 rg -n "409|423" exit=1 零命中）；遇 keepass2android 实证的那类「MOVE 覆盖报 409」服务器，MOVE 重试 1 次后仍失败 → 清理临时文件并统一抛 ProtocolError(500,「WebDAV 原子写入 MOVE 失败，已清理临时文件」)（:382-388），保存永久无法收敛且错误信息无法区分该场景
- **是否有同样风险**：**yes**
- **建议**：在 WebDavSyncProvider 的 MOVE 失败分支增加服务器兼容性降级：当 Overwrite:T 的 MOVE 返回 409（或 423 Locked）时，先 DELETE 目标资源（容忍 404，WebDavSyncProvider.kt:408 已有同语义，本会话直读证实）再单次重试 MOVE，降级发生记日志留痕——接受 delete-then-move 的短暂丢失窗口（与 keepass2android 同型取舍）；同时把 409/423 与 412 分开报错文案，便于用户定位服务器不兼容；若评估后不采纳兜底，至少在《已知工程限界》登记「MOVE 覆盖遇 409 服务器不可用」。不违反模块依赖与敏感数据纪律（纯 sync 模块协议分支）
- **需补充信息**：（无）

### 32. PluginManager.java:318 → 无直接对应
- **坑的本质**：issue 878：插件数据加载会抛异常，须 try-catch 包住否则键盘崩溃
- **触发条件**：加载/查询键盘插件数据
- **主项目对应位置**：无直接对应
- **对照情况**：无：主项目无软键盘与插件加载宿主（「插件宿主评估」是已登记待评估项，docs/ACTIVE_ISSUES.md:142）；如未来引入插件宿主，须把该教训带入——插件数据解析边界一律 try-catch 隔离
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 33. strings.xml:806 → AutofillCandidateRanker.kt:272
- **坑的本质**：1.12 之前 autofill 与搜索的主机名匹配存在缺陷（未正确按可注册域匹配），1.12 才修复；实现域匹配须以此教训校准粒度
- **触发条件**：按主机名匹配填充候选/搜索
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/autofill/AutofillCandidateRanker.kt:272`
- **对照情况**：同类面且已按教训校准：候选排序按 Mozilla PSL registrableDomain 严格匹配（AutofillCandidateRanker.kt:257-273），基域档/SAME_BASE_DOMAIN/SUBDOMAIN_OF_ORIGIN 严格序 §356 刚以负例（兄弟子域、私有段 foo.github.io、IP 字面量）锁定，IP/PSL 不可用恒 fail-closed 不命中
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 34. strings.xml:854 → KeePasskeyAutofillService.kt:91
- **坑的本质**：Firefox 下自动填充提示会消失（一次连接多次应答所致），1.09b 才以超时锁修复——与 AutofillServiceBase.cs:83 的代码内注释互证
- **触发条件**：Firefox 中触发自动填充
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/autofill/KeePasskeyAutofillService.kt:91`
- **对照情况**：同 AutofillServiceBase.cs:83 条：主项目无 Firefox 专用通道，标准 AutofillService 单连接单回调且超时/异常路径各恰好回调一次、取消路径按平台契约静默不回调，无提示闪烁面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 35. strings.xml:858 → WebDavUrlCodec.kt:21
- **坑的本质**：曾把保存到 Dropbox 的文件名转小写，造成大小写敏感远端上文件名被改坏；云存储实现不得擅自归一化文件名
- **触发条件**：向 Dropbox 保存库文件
- **主项目对应位置**：`sync/src/main/java/com/keepasskey/sync/webdav/WebDavUrlCodec.kt:21`
- **对照情况**：同类面且已规避：主项目无 Dropbox；远端路径拼装仅 trim + 百分号编码（WebDavUrlCodec.kt:21-25），S3KeyCodec 与 WebDavUrlCodec 全文 grep toLowerCase/uppercase 零命中（本会话已核），文件名大小写原样保留
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 36. strings.xml:788 → WebDavSyncProvider.kt:129
- **坑的本质**：WebDAV 目录列举曾有缺陷（1.14 才修），配合代码中 PROPFIND 失败时保守按「文件存在」处理的降级策略，说明列举结果不可尽信
- **触发条件**：WebDAV 列目录/检查文件存在性
- **主项目对应位置**：`sync/src/main/java/com/keepasskey/sync/webdav/WebDavSyncProvider.kt:129`
- **对照情况**：当前无列举面：主项目只用 Depth:0 单资源 PROPFIND 取元数据（WebDavSyncProvider.kt:129/150），404 才判不存在（:155/213）；「远端目录浏览」是已登记待办 ISSUE-P3-387（docs/ACTIVE_ISSUES.md:286）——整改时须把「列举结果不可尽信、失败保守降级」教训写入其 AC
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 37. strings.xml:824 → KdbxConstants.kt:128 ⚠ risk=unclear
- **坑的本质**：TOTP 各实现间存在兼容性问题（KeePass2/TrayTOTP 读不出），1.11 重写才对齐；自研 TOTP 字段解析须与参考实现交叉对拍
- **触发条件**：解析/显示 KeePass2、TrayTOTP 风格 TOTP 字段
- **主项目对应位置**：`core/src/main/java/com/keepasskey/core/model/KdbxConstants.kt:128`
- **对照情况**：同类兼容面且存在广度缺口（本会话逐项复核证实）：主项目 TOTP 仅认「otp」字段（KeePassXC 语义，KdbxConstants.kt:128-129），解析仅支持 otpauth:// URI 与纯 Base32 种子两形态（TotpKeyUriParser.kt:76-82）；KeePass2 内建 TimeOtp-* 字段、TrayTOTP「TOTP;Period;Digits;Secret」、KeeOtp「otp; seed=...」变体全仓零实现（本会话 rg "TimeOtp|TrayTOTP|TOTP;|KeeOtp" 于 database/core/app/sync 生产代码仅命中 TotpSettingsScreen.kt 一处注释）——由这些生态创建的库在本应用取不出验证码；Steam 变体已单独登记 ISSUE-P3-383（docs/ACTIVE_ISSUES.md:221，本会话直读证实），其余格式未登记也未裁决
- **是否有同样风险**：**unclear**
- **建议**：（待用户口径裁决后确定，见需补充信息）
- **需补充信息**：产品口径：是否需要支持 KeePass2 内建 TimeOtp-*（Base32/Hex/Seed+Period+Digits）字段与 TrayTOTP/KeeOtp 插件风格 TOTP 字段？若属支持范围，请按 P3-383 同体例登记新条目（附与 KeePass2/KeePassXC 的对拍向量，遵守规则 8 互操作证据纪律）；若刻意只支持 KeePassXC「otp」语义，请在《产品裁决登记》落一条取舍，避免后续被当缺陷重复提出

## 三、复核记录（独立复核结论）

1. **【修正】Util.cs:60 行（appLanguage 语言面，risk=yes）的 advice ①**：原稿把 CredentialUnlockActivity 列入「剩余未接线面」，与 ourSimilarity 自身矛盾——该 Activity 已按 AppShellLocalization 同法接线（本会话直读 CredentialUnlockActivity.kt:101-107，localeFor/localizedConfigurationOf/localizedContextOf 与 AutofillUnlockActivity.kt:126-132 完全同型），已从 advice ① 移除并加「勿重复列入」注记。该行其余断言全部实测证实：AutofillConfirmActivity.kt:119-127/146 裸 getString（直读）；AutofillPickerActivity.kt 无 appLanguage/localeFor 接线且 :218-219/:305 裸 getString（rg 零命中 + 直读）；TotpNotificationPublisher.kt:63-65 裸 context.getString（实际路径为 app/src/main/java/com/keepasskey/app/notification/TotpNotificationPublisher.kt，行号无误）；已接线面 KeePasskeyApp.kt:77-85、AppShellLocalization.kt:32-37 证实。risk=yes 维持：appLanguage=EN 时确认页/选择器/通知确实回落系统语言。advice 不违反硬纪律（createConfigurationContext 不物化敏感数据、不动模块依赖）。
2. **【修正】AutofillServiceBase.cs:83 行（risk=no 抽查）的 ourSimilarity**：原稿称「超时/取消/异常路径各自保证恰好回调一次」，本会话直读 KeePasskeyAutofillService.kt:91-117 证实取消路径并非回调一次而是按平台契约静默不回调（:106-108 注释明写「系统侧已取消请求：静默退出，不再回调」，CancellationException 直接上抛），超时（:102-104 callback.onSuccess(null)）与异常（:112 callback.onFailure）路径才各恰好回调一次；已改为准确表述（strings.xml:854 同源表述一并修正）。risk=no 维持：标准 AutofillService 单回调契约下无 keepass2android Firefox 通道的多次应答面。
3. **【复核通过未改动】WebDavStorage.java:235 行（MOVE 覆盖 409，risk=yes）**：本会话直读 WebDavSyncProvider.kt:240-413 全部证实——uploadAtomic 为 PUT 临时名→MOVE（:301-396）、Overwrite T/F + If tagged list（:329-347）、PUT/MOVE 412 分支（:260/:360）、rg -n "409|423" 全文件 exit=1 零命中、失败统一抛 ProtocolError(500,固定文案)（:388）错误无法区分 409、delete() 容忍 404（:408）恰为 advice 兜底提供现成语义。advice 可行且不违反硬纪律，risk=yes 维持。
4. **【复核通过未改动】BuiltInFileStorage.cs:101 行（mtime 毫秒截断，risk=yes）**：P2-378 在 docs/ACTIVE_ISSUES.md:60-83（AC① 即 mtime+size，:78）；SessionPersistence.kt:42-99 save() 直读证实无外部改动前置校验；本会话复跑 rg "FileObserver|WatchService|watchFile|lastModified\(\)" 于 app/database/sync 三模块 main 零命中（exit=1），lastModified 仅 sync 远端 HTTP 面（WebDavPropfindParser.kt:62-63 HTTP-date 秒级）。advice 合理，risk=yes 维持。
5. **【复核通过未改动】strings.xml:824 行（TOTP 格式广度，risk=unclear）**：KdbxConstants.kt:128-129 仅认「otp」字段（直读，注意实际路径在 core/ 而非 database/，行内 ourLocation 已写对）；TotpKeyUriParser.kt:76-82 直读证实仅 otpauth:// 与纯 Base32 两形态；rg "TimeOtp|TrayTOTP|TOTP;|KeeOtp" 生产代码零实现（仅 TotpSettingsScreen.kt 注释一处）；P3-383 在 docs/ACTIVE_ISSUES.md:221 证实。risk=unclear（待产品口径裁决）与 needInfo 恰当。
6. **【复核通过未改动（risk=no 抽查）】IFileStorage.cs:79 与 SaveDB.cs:253 两行**：SyncEngine.kt:99-102 KDoc 直读证实「ETag 可用按乐观锁零下载比对；无 ETag 服务器回退整档下载 SHA-256 裁决」与行述一致，risk=no 维持。
