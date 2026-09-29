# 01 · KeePass-2.61.1（官方 C#，格式裁决者）踩坑对照详情

> 来源：参考项目 `参考项目/KeePass-2.61.1-Source/` 的踩坑标注与主项目 KeePasskey 的逐条对照。
> 扫描日期 2026-09-28。踩坑标注 34 条，对照行 34 条（risk=yes 1 / unclear 0 / no 33）。
> 总表见 [00-对照总表.md](00-对照总表.md)。

## 一、踩坑标注（34 条）

### 1. KeePassLib/Serialization/FileTransactionEx.cs:117
- **代码上下文**：保存数据库前决定是否用 TxF/事务式写入
- **原文**：
  > Prevent transactions for FTP URLs under .NET 4.0 in order to avoid/workaround .NET bug 621450
- **链接**：`https://connect.microsoft.com/VisualStudio/feedback/details/621450/problem-renaming-file-on-ftp-server-using-ftpwebrequest-in-net-framework-4-0-vs2010-only`（可达性：no）
- **坑的本质**：.NET 4.0 的 FtpWebRequest 重命名有 bug 621450，FTP 目标走事务保存会失败，须条件性置 m_bTransacted=false
- **置信度**：confirmed

### 2. KeePassLib/Serialization/FileTransactionEx.cs:434
- **代码上下文**：TxfIsUnusable 判定 TxF 事务写入是否可用
- **原文**：
  > Due to a bug in Microsoft's 'cldflt.sys' driver, a TxF transaction results in a Blue Screen of Death
- **链接**：无（可达性：none）
- **坑的本质**：cldflt.sys 驱动 bug 致 TxF 事务在 Win10 1903/1909 蓝屏、1809 上 OneDrive 目录内崩溃，命中即放弃事务改普通写入
- **置信度**：confirmed

### 3. KeePassLib/Serialization/HashedBlockStream.cs:221
- **代码上下文**：读 kdbx HashedBlockStream 的块长字段
- **原文**：
  > catch(NullReferenceException) // Mono bug workaround (LaunchPad 783268)
- **链接**：无（可达性：none）
- **坑的本质**：Mono 下读块长会抛上游 NullReferenceException；仅在 Unix 平台吞掉转为正常校验失败，Windows 上仍重抛，防掩盖真实损坏
- **置信度**：confirmed

### 4. KeePassLib/Cryptography/CryptoUtil.cs:194
- **代码上下文**：CreateAesCore 选择 AES 实现（AesCng 分支已注释）
- **原文**：
  > // https://github.com/dotnet/runtime/issues/18012
- **链接**：`https://github.com/dotnet/runtime/issues/18012`（可达性：yes）
- **坑的本质**：AesCng 比 AesCryptoServiceProvider 慢数十倍（issue 标题即 "AesCng much slower"），整段 AesCng 加速代码被注释弃用
- **置信度**：confirmed

### 5. KeePassLib/Cryptography/CryptoStreamEx.cs:48
- **代码上下文**：CryptoStreamEx.Dispose 包裹基类释放
- **原文**：
  > Unnecessary exception from CryptoStream with RijndaelManagedTransform when a stream hasn't been read completely
- **链接**：无（可达性：none）
- **坑的本质**：流未读完（如主密钥错误的正常失败路径）时 CryptoStream.Dispose 反而抛多余 CryptographicException/IndexOutOfRange，须吞掉否则错误处理被打断
- **置信度**：confirmed

### 6. KeePassLib/Cryptography/CryptoRandom.cs:187
- **代码上下文**：随机数池播种时采集鼠标位置熵
- **原文**：
  > In try-catch for systems without GUI;
- **链接**：`https://sourceforge.net/p/keepass/discussion/329221/thread/20335b73/`（可达性：yes）
- **坑的本质**：无 GUI/无 X11 的系统上读 Cursor.Position 会抛异常，播种必须 try-catch 包裹，否则随机种子生成整体崩溃（KPScript 无头运行踩过）
- **置信度**：confirmed

### 7. KeePassLib/Security/ProtectedBinary.cs:413
- **代码上下文**：ProtectedBinary 生成保护用随机 XOR pad
- **原文**：
  > Do not use CryptoRandom here, as it uses ProtectedBinary; we would have an infinite recursion
- **链接**：无（可达性：none）
- **坑的本质**：CryptoRandom 内部用 ProtectedBinary 存随机池，此处再调它会无限递归；必须直接用 RNGCryptoServiceProvider 取 32 字节
- **置信度**：confirmed

### 8. KeePassLib/Security/ProtectedString.cs:190
- **代码上下文**：ProtectedString.ReadString 转普通 String
- **原文**：
  > Be careful with this function, as the returned string object isn't protected anymore and stored in plain-text
- **链接**：无（可达性：none）
- **坑的本质**：ReadString 返回的 System.String 无法擦除，明文常驻进程内存；敏感数据应走 ReadUtf8 并显式清零，此方法只限非敏感场景
- **置信度**：confirmed

### 9. KeePassLib/Security/XorredBuffer.cs:50
- **代码上下文**：XorredBuffer 构造（受保护字段解密前状态）
- **原文**：
  > The XorredBuffer object takes ownership of the two byte arrays, i.e. the caller must not use them afterwards.
- **链接**：无（可达性：none）
- **坑的本质**：构造即接管两个数组所有权（含后续清零责任），调用方继续复用原数组会读到被清零/污染的数据
- **置信度**：confirmed

### 10. KeePassLib/Native/NativeLib.cs:211
- **代码上下文**：平台判定（GetPlatformID 初始化）
- **原文**：
  > Mono returns PlatformID.Unix on MacOS, workaround this
- **链接**：无（可达性：none）
- **坑的本质**：Mono 把 macOS 报成 PlatformID.Unix，靠运行 uname 探测 Darwin 纠正；直接信 Environment.OSVersion 会让平台分支走错
- **置信度**：confirmed

### 11. KeePassLib/Native/NativeLib.cs:789
- **代码上下文**：Unix 上启动外部程序/打开文件前的路径整形
- **原文**：
  > Mono's Process.Start method replaces '\\' by '/', which may cause a different file to be executed
- **链接**：无（可达性：none）
- **坑的本质**：Mono 的 Process.Start 会把反斜杠替换为斜杠，可能执行到另一个文件（安全隐患），代码对含 \\ 的路径直接抛 ArgumentException 拒绝启动
- **置信度**：confirmed

### 12. KeePassLib/Utility/MonoWorkarounds.cs:64
- **代码上下文**：1219 号规避：EnsureNoBom 剥除 StreamWriter 前导码
- **原文**：
  > Mono prepends byte order mark (BOM) to StdIn.
- **链接**：无（可达性：none）
- **坑的本质**：Mono 向子进程 StdIn 写入时前置 BOM，污染传给外部命令的输入；写前需按 workaround 1219 检测并剥除 preamble
- **置信度**：confirmed

### 13. KeePassLib/Utility/MonoWorkarounds.cs:144
- **代码上下文**：10163 号：IocStream 补调 GetResponse（见 IOConnection.cs:202）
- **原文**：
  > WebRequest GetResponse call missing, breaks WebDAV due to no PUT.
- **链接**：`https://bugzilla.xamarin.com/show_bug.cgi?id=10163`（可达性：no）
- **坑的本质**：Mono 不主动发起 WebRequest 的 GetResponse，WebDAV PUT 根本不执行导致保存失败；Dispose 时反射调 GetResponse 并按需包 IocStream（链接不可达，基于注释推断）
- **置信度**：confirmed

### 14. KeePassLib/Utility/MonoWorkarounds.cs:156
- **代码上下文**：19836 号：NativeLib.cs:776 改用 xdg-open/open
- **原文**：
  > URLs/documents cannot be opened using Process.Start anymore (even when UseShellExecute = true).
- **链接**：`https://github.com/mono/mono/issues/19836`（可达性：yes）
- **坑的本质**：新版 Mono 的 Process.Start 打不开 URL/文档（抛 Win32Exception），改为拼 xdg-open（macOS 用 open）并把 URL 编码进参数
- **置信度**：confirmed

### 15. KeePassLib/Utility/MonoWorkarounds.cs:190
- **代码上下文**：IOConnection 无用户名时补 anonymous 凭据（IOConnection.cs:584）
- **原文**：
  > Credentials are required for anonymous web requests.
- **链接**：`https://bugzilla.novell.com/show_bug.cgi?id=688007`（可达性：no）
- **坑的本质**：Mono 对匿名请求也要求显式凭据否则 401；未配用户名口令时补 NetworkCredential("anonymous","") 兜底（链接已 404，基于注释推断）
- **置信度**：confirmed

### 16. KeePassLib/Utility/MonoWorkarounds.cs:90
- **代码上下文**：1468 号：AesKdf.GCrypt.cs:39 切 LibGCrypt 引擎
- **原文**：
  > Use LibGCrypt for AES-KDF, because Mono's implementations of RijndaelManaged and AesCryptoServiceProvider are slow.
- **链接**：无（可达性：none）
- **坑的本质**：Mono 的托管 AES 实现慢，AES-KDF 大轮次下不可接受；Unix 上换 LibGCrypt 原生加速，且可用 -wa-disable 关掉
- **置信度**：confirmed

### 17. KeePassLib/Utility/MonoWorkarounds.cs:169
- **代码上下文**：100004 号：Argon2Kdf.cs:222 仅走原生内核
- **原文**：
  > Use native Argon2 implementation.
- **链接**：无（可达性：none）
- **坑的本质**：Unix 上 Argon2 只用原生 argon2_hash_u0；DllNotFound 时静默回退托管实现（性能骤降），100004 可被命令行关闭
- **置信度**：confirmed

### 18. KeePassLib/Serialization/KdbxFile.Read.Streamed.cs:163
- **代码上下文**：流式解读 kdbx 时向 UI 上报进度
- **原文**：
  > Clip percent value in case the stream reports incorrect position/length values (M120413)
- **链接**：无（可达性：none）
- **坑的本质**：底层流可能上报错乱的 Position/Length（M120413），算出 >100% 的进度会砸进度回调，必须钳制到 100
- **置信度**：confirmed

### 19. KeePassLib/PwDatabase.cs:787
- **代码上下文**：数据库同步/合并时定位本地对应组
- **原文**：
  > Do not use ppOrg for finding the group, because new groups might have been added
- **链接**：无（可达性：none）
- **坑的本质**：合并过程中新组尚未进对象池且池不许改，用合并前的池查组会漏掉新组；必须按 UUID 在实时根树上 FindGroup
- **置信度**：confirmed

### 20. KeePassLib/Cryptography/QualityEstimation.cs:338
- **代码上下文**：口令质量/熵估计的编码器实例化
- **原文**：
  > Encoders must not be static, because the entropy estimation may run concurrently in multiple threads
- **链接**：无（可达性：none）
- **坑的本质**：熵编码器带内部计数状态且非只读，多线程并发评估会互踩结果；必须每次调用新建实例而非复用静态对象
- **置信度**：confirmed

### 21. KeePassLib/Utility/MonoWorkarounds.cs:316
- **代码上下文**：剪贴板修复后台线程的启动门槛
- **原文**：
  > Without XDoTool, the workaround would be applied to all applications, which may corrupt the clipboard
- **链接**：`https://sourceforge.net/p/keepass/bugs/1603/`（可达性：yes）
- **坑的本质**：修复线程靠持续重写剪贴板生效，检测不到 xdotool 就无法只对自身窗口生效，会破坏非纯文本剪贴板内容——检测失败即不启动
- **置信度**：confirmed

### 22. KeePassLib/Native/ClipboardU.cs:43
- **代码上下文**：Unix 剪贴板读写入口
- **原文**：
  > System.Windows.Forms.Clipboard doesn't work properly, see Mono workarounds 1530/1613
- **链接**：无（可达性：none）
- **坑的本质**：Mono 的 WinForms Clipboard 实现不可靠（内容失效/丢失），读写全部改走 xsel 外部命令，而非调系统剪贴板 API
- **置信度**：confirmed

### 23. KeePass/Util/SendInputExt/SiCodes.cs:268
- **代码上下文**：Auto-Type 字符→X KeySym 映射表构建
- **原文**：
  > XDoTool sends some characters in the wrong case; a workaround is to specify the keypress as an XKeySym combination
- **链接**：`https://github.com/jordansissel/xdotool/issues/41`（可达性：yes）
- **坑的本质**：xdotool 对部分字符大小写发错（上游 issue#41：土耳其大写变小写），须逐字符改用 XKeySym/dead-key 组合映射重音与大写字母
- **置信度**：confirmed

### 24. KeePass/Util/SendInputEx.cs:520
- **代码上下文**：Auto-Type 键序发送节奏控制
- **原文**：
  > Also delay key modifiers, as a workaround for applications with broken time-dependent message processing
- **链接**：`https://sourceforge.net/p/keepass/bugs/1213/`（可达性：yes）
- **坑的本质**：部分应用的消息处理依赖时序（如 Steam 丢字符），修饰键不一起延迟会被目标应用吞键/错序；键、字符、修饰键都插间隔
- **置信度**：confirmed

### 25. KeePass/Util/SendInputExt/SiWindowInfo.cs:108
- **代码上下文**：按目标进程选择自动输入发送方式
- **原文**：
  > The workaround attempt for Edge below doesn't work; Edge simply ignores Unicode packets for '@', Euro sign, etc.
- **链接**：`https://sourceforge.net/p/keepass/discussion/329220/thread/fd3a6776/`（可达性：yes）
- **坑的本质**：曾尝试按窗口标题识别 Edge 换 Unicode 包，实测 Edge 直接忽略 '@'/€ 等特殊字符的 Unicode 包——规避手段无效，整段注释弃用
- **置信度**：confirmed

### 26. KeePass/UI/CustomMessageFilterEx.cs:42
- **代码上下文**：全局消息过滤器预处理窗口消息
- **原文**：
  > Workaround for .NET 4.6 overflow bug in InputLanguage.Culture (handle casted to Int32 without the 'unchecked' keyword)
- **链接**：`https://sourceforge.net/p/keepass/bugs/1598/`（可达性：yes）
- **坑的本质**：.NET 4.6 把输入语言句柄强转 Int32 溢出抛异常；检测到 WM_INPUTLANGCHANGEREQUEST 的 LParam 超 int 范围就吞掉消息（比崩溃好）
- **置信度**：confirmed

### 27. KeePass/UI/CustomRichTextBoxEx.cs:120
- **代码上下文**：安全富文本框句柄创建时的属性修正
- **原文**：
  > when setting AutoWordSelection to false, the control style is toggled instead of turned off
- **链接**：无（可达性：none）
- **坑的本质**：.NET 的 AutoWordSelection=false 实际发 EM_SETOPTIONS 做"切换"而非"关闭"（内部值却正确更新），须先置 true 再 false 才真正关掉
- **置信度**：confirmed

### 28. KeePass/UI/RichTextBuilder.cs:248
- **代码上下文**：富文本编辑器文本→RTF 编码
- **原文**：
  > Workaround for encoding bugs in Windows and Mono
- **链接**：无（可达性：none）
- **坑的本质**：Windows(KPB 1780)/Mono(586901) 的 RichTextBox 对 >U+00FF 字符编码出错，先用随机占位码替换、生成 RTF 后再回填真实字符
- **置信度**：confirmed

### 29. KeePass/Native/NativeMethods.New.cs:936
- **代码上下文**：虚拟键状态转 Unicode 字符（ToUnicode 前置）
- **原文**：
  > Windows' GetKeyboardState function does not return the current virtual key array
- **链接**：无（可达性：none）
- **坑的本质**：GetKeyboardState 拿不到当前虚拟键数组，传闻的 GetKeyState 预热也不可靠；无法确证键态时直接 Debug.Assert 返回 null 拒绝转换
- **置信度**：confirmed

### 30. KeePass/UI/ExpiryControlGroup.cs:54
- **代码上下文**：条目过期时间 DateTimePicker 取值
- **原文**：
  > Force validation/update of incomplete edit (workaround for KPB 3505269)
- **链接**：无（可达性：none）
- **坑的本质**：用户正在输入未提交时直接读 Value 会拿到旧值；先闪隐控件强逼校验，再额外挂 KeyPress 事件兜底（KPB 3505269）
- **置信度**：confirmed

### 31. KeePass/UI/ProtectedDialog.cs:219
- **代码上下文**：模态对话框关闭后恢复前台焦点
- **原文**：
  > Workaround for focus bug in Windows 11
- **链接**：`https://sourceforge.net/p/keepass/discussion/329221/thread/0a4a1a7ee7/`（可达性：yes）
- **坑的本质**：Win11 上 SetForegroundWindowEx 无法把焦点还给原窗口（如错误主密码提示框被压在后面）；改用 ActivateTopWindowEx 激活栈顶窗口
- **置信度**：confirmed

### 32. KeePass/UI/UIUtil.cs:3307
- **代码上下文**：SetWindowState 设置窗口最小化/还原状态
- **原文**：
  > If the window state change / resize handler changes the window state again, the property gets out of sync
- **链接**：`https://sourceforge.net/projects/keepass/forums/forum/329221/topic/4610118`（可达性：yes）
- **坑的本质**：WindowState 变更处理器内再次改状态会让属性与真实窗口状态脱钩；设置后必须回读真实窗口状态强制同步属性
- **置信度**：confirmed

### 33. Docs/History.txt:1318
- **代码上下文**：2.47 更新日志：Mono 选项禁用
- **原文**：
  > restoring from tray' are disabled now (because they do not work reliably due to a bug in Mono)
- **链接**：无（可达性：none）
- **坑的本质**：Mono 下「从任务栏/托盘恢复时聚焦快速搜索框」两选项工作不可靠（Mono bug），产品层面在 Mono 上直接禁用整组选项
- **置信度**：confirmed

### 34. Docs/History.txt:1430
- **代码上下文**：更新日志：安全桌面禁用 IME
- **原文**：
  > In order to avoid a Windows Input Method Editor (IME) bug (resulting in a black screen
- **链接**：无（可达性：none）
- **坑的本质**：Windows IME bug 会致黑屏/IME(CTF) 进程 CPU 飙升，切换到安全桌面（UAC）前主动禁用输入法规避
- **置信度**：confirmed

## 二、对照行（34 条）

### 1. FileTransactionEx.cs:117 → WebDavSyncProvider.kt:74
- **坑的本质**：.NET 4.0 的 FtpWebRequest 重命名有 bug 621450，FTP 目标走事务保存会失败，须条件性置 m_bTransacted=false
- **触发条件**：Windows + .NET≥4.0 + ftp:// 目标保存
- **主项目对应位置**：`sync/src/main/java/com/keepasskey/sync/webdav/WebDavSyncProvider.kt:74`
- **对照情况**：同类模式（同步目标上的事务式原子写入），但主项目同步目标仅限 HTTPS WebDAV/S3：非 https:// 端点在构造期 fail-fast 拒绝（:74-79），全仓无 FTP 通道，触发面不存在
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 2. FileTransactionEx.cs:434 → AtomicFileWriter.kt:195
- **坑的本质**：cldflt.sys 驱动 bug 致 TxF 事务写入蓝屏/崩溃，命中即条件性放弃事务改普通写入
- **触发条件**：Windows 1809 且文件在 OneDrive 目录（1903/1909 已注释）
- **主项目对应位置**：`database/src/main/java/com/keepasskey/database/session/AtomicFileWriter.kt:195`
- **对照情况**：同类模式（原子替换须有安全降级路径）；Android 无 TxF/cldflt.sys 面，本仓 AtomicFileWriter 已自备逐级降级：ATOMIC_MOVE 失败→标准 rename→仅在 .bak 兜底可用时才 copy 覆盖（:195-230），且严禁替换前无保护删原文件
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 3. HashedBlockStream.cs:221 → HmacBlockStream.kt:109
- **坑的本质**：Mono 下读块长抛上游 NRE，仅 Unix 平台吞掉转校验失败、Windows 仍重抛，防掩盖真实损坏
- **触发条件**：Unix/Mono 解析 kdbx 分块流
- **主项目对应位置**：`database/src/main/java/com/keepasskey/database/file/HmacBlockStream.kt:109`
- **对照情况**：同类模式（读 HMAC 块流块长/块数据的异常语义）；主项目无 Mono 面，块长负数拒绝（:110-112）、EOF 归一为类型化 KdbxCorruptFileException（fail-closed 不吞异常），另以 verifyEndOfStream 防 javax CipherInputStream 吞完整性异常（:161-165），语义方向与该坑的防掩盖诉求一致
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 4. CryptoUtil.cs:194 → AesCipherEngine.kt:143
- **坑的本质**：AesCng 比 AesCryptoServiceProvider 慢数十倍，整段 AesCng 加速代码被注释弃用
- **触发条件**：KDBX 主密钥加解密构造 AES 时
- **主项目对应位置**：`crypto/src/main/java/com/keepasskey/crypto/cipher/AesCipherEngine.kt:143`
- **对照情况**：同类模式（AES 实现选择面）；主项目用平台默认 JCE provider（AndroidOpenSSL/Conscrypt，无 Cng 类慢实现可选），且载荷主路径走 Rust 原生内核（NativeAes direct 直扣形态），不存在该坑的选择面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 5. CryptoStreamEx.cs:48 → CbcDecryptingInputStream.kt:152
- **坑的本质**：流未读完（如主密钥错误的正常失败路径）时 CryptoStream.Dispose 反抛多余异常打断错误处理，须吞掉
- **触发条件**：解密流未读完即 Dispose
- **主项目对应位置**：`crypto/src/main/java/com/keepasskey/crypto/cipher/CbcDecryptingInputStream.kt:152`
- **对照情况**：同类模式（解密流提前 close 的收尾语义）；本类 close() 有意不做收尾填充校验，KDoc :29-33 明确声明该保守选择，close 自身只做各缓冲清零与 source.close()，不会复现「关闭期抛填充/越界异常打断错误处理」
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 6. CryptoRandom.cs:187 → InMemoryCipher.kt:59
- **坑的本质**：无 GUI/无 X11 系统读 Cursor.Position 采熵会抛异常，播种必须 try-catch，否则随机种子生成整体崩溃（KPScript 无头运行踩过）
- **触发条件**：无 GUI 的 Unix 控制台运行
- **主项目对应位置**：`core/src/main/java/com/keepasskey/core/security/InMemoryCipher.kt:59`
- **对照情况**：同类模式（进程内随机源）；主项目全仓直接用平台 SecureRandom（Android 内核自播种，grep 核实无任何自采 GUI 熵代码路径），不存在可抛异常的自采熵步骤
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 7. ProtectedBinary.cs:413 → InMemoryCipher.kt:116
- **坑的本质**：CryptoRandom 内部用 ProtectedBinary 存随机池，ProtectedBinary 生成 XOR pad 时再调它即无限递归，必须直用底层 RNGCryptoServiceProvider
- **触发条件**：构造受保护二进制时生成随机 pad
- **主项目对应位置**：`core/src/main/java/com/keepasskey/core/security/InMemoryCipher.kt:116`
- **对照情况**：同类模式（受保护值的随机化处理中取随机数）；主项目 InMemoryCipher.seal 用独立 SecureRandom 实例（:59），SecureRandom 不依赖 ProtectedString，无递归环
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 8. ProtectedString.cs:190 → ProtectedString.kt:127
- **坑的本质**：ReadString 返回的 String 不可擦除、明文常驻进程内存；敏感数据应走 ReadUtf8 并显式清零，此方法只限非敏感场景
- **触发条件**：调用 ReadString 读受保护字段
- **主项目对应位置**：`core/src/main/java/com/keepasskey/core/security/ProtectedString.kt:127`
- **对照情况**：功能相似（同名同用途的 readString，KDoc :124-125 带同样的明文驻留警示）；主项目已按此纪律整改：密码/TOTP 等敏感消费方经 VaultEntrySecretReader 的 CharArray 独占副本中转即时清零（app/src/main/java/com/keepasskey/app/data/repository/VaultEntrySecretReader.kt:106），CSV/XML 导出不再经 readString 物化（database/src/main/java/com/keepasskey/database/csv/KdbxCsvExporter.kt:79），冲突页敏感字段一律掩码不物化（app/src/main/java/com/keepasskey/app/ui/screens/conflict/ConflictResolutionViewModel.kt:133-146）；残余 readString 仅限平台硬边界（自动填充/无障碍回填 API 只收 CharSequence，app/src/main/java/com/keepasskey/app/autofill/legacy/LegacyAutofillCoordinator.kt:21 已声明）与非敏感字段展示
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 9. XorredBuffer.cs:50 → CbcDecryptingInputStream.kt:49
- **坑的本质**：XorredBuffer 构造即接管两数组所有权（含清零责任），调用方继续复用原数组会读到被清零/污染的数据
- **触发条件**：传入 pbCT/pbXorPad 后仍引用原数组
- **主项目对应位置**：`crypto/src/main/java/com/keepasskey/crypto/cipher/CbcDecryptingInputStream.kt:49`
- **对照情况**：同类模式（缓冲区所有权/清零责任的显式移交契约）；主项目无 XOR-pad 解密形态（驻留态走 AES-CTR 密封、派生密钥每次新副本），等价契约由 ownedSecrets（流自持密钥副本、close 确定性擦除，:49-58 KDoc）等显式承担，调用方复用原数组的污染面不存在
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 10. NativeLib.cs:211 → 无直接对应
- **坑的本质**：Mono 把 macOS 报成 PlatformID.Unix，须 uname 探测 Darwin 纠正，否则平台分支走错
- **触发条件**：Mono 运行时 + macOS
- **主项目对应位置**：无直接对应
- **对照情况**：无（主项目为 Android 单平台应用，无跨桌面运行时的平台判定分支面）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 11. NativeLib.cs:789 → EntryDetailUrlActions.kt:34
- **坑的本质**：Mono 的 Process.Start 会把反斜杠替换为斜杠，可能执行到另一个文件（安全隐患），含 \\ 的路径直接拒绝启动
- **触发条件**：Mono + 文件路径含反斜杠
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailUrlActions.kt:34`
- **对照情况**：同类模式（唤起外部处理前的 URL/路径闸门）；主项目不启动外部程序，仅对 http(s):// 前缀放行 ACTION_VIEW，其余 scheme（含 ftp:/自定义）一律回落复制（:34-43），无文件路径执行面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 12. MonoWorkarounds.cs:64 → 无直接对应
- **坑的本质**：Mono 向子进程 StdIn 前置 BOM 污染外部命令输入，写前需按 workaround 1219 剥除 preamble
- **触发条件**：Mono 调外部进程并写 StdIn
- **主项目对应位置**：无直接对应
- **对照情况**：无（grep 核实主项目全仓无 ProcessBuilder / Runtime.exec 外部进程调用，无 StdIn 写入面）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 13. MonoWorkarounds.cs:144 → WebDavSyncProvider.kt:258
- **坑的本质**：Mono 不主动发起 WebRequest 的 GetResponse，WebDAV PUT 根本不执行导致保存失败
- **触发条件**：Mono + WebDAV 写入/保存
- **主项目对应位置**：`sync/src/main/java/com/keepasskey/sync/webdav/WebDavSyncProvider.kt:258`
- **对照情况**：同类模式（WebDAV PUT 保存）；主项目走 OkHttp，execute() 同步真实发起请求并对响应码逐一裁决（412/401/403 等），不存在「请求未发出」面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 14. MonoWorkarounds.cs:156 → EntryDetailUrlActions.kt:66
- **坑的本质**：新版 Mono 的 Process.Start 打不开 URL/文档（抛 Win32Exception），改拼 xdg-open（macOS 用 open）并把 URL 编码进参数
- **触发条件**：Mono 2020+ 打开 URL 或文档
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailUrlActions.kt:66`
- **对照情况**：同类模式（呼起浏览器）；主项目用标准 ACTION_VIEW + runCatching 兜底，失败回落复制网址（:66-75），无 Mono 面且呼起失败有如实提示
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 15. MonoWorkarounds.cs:190 → WebDavAuthHeader.kt:28
- **坑的本质**：Mono 对匿名请求也要求显式凭据否则 401，未配用户名口令时补 NetworkCredential("anonymous","") 兜底
- **触发条件**：Mono + 匿名 WebDAV/HTTP 读取
- **主项目对应位置**：`sync/src/main/java/com/keepasskey/sync/webdav/WebDavAuthHeader.kt:28`
- **对照情况**：同类模式（WebDAV 凭据头构造）；主项目每个请求恒带 Basic Authorization 头（WebDavSyncProvider.kt:85 构造期一次算好、全部请求统一挂 :128/:149/:208 等），即使凭据为空也发送「user:pass」形态头，不存在「匿名请求缺凭据头」面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 16. MonoWorkarounds.cs:90 → AesKdfEngine.kt:30
- **坑的本质**：Mono 的托管 AES 实现慢，AES-KDF 大轮次下不可接受，Unix 上换 LibGCrypt 原生加速
- **触发条件**：Unix/Mono 上执行 AES-KDF 密钥派生
- **主项目对应位置**：`crypto/src/main/java/com/keepasskey/crypto/kdf/AesKdfEngine.kt:30`
- **对照情况**：同类模式（AES-KDF 为性能敏感热路径）；主项目已是原生优先——探活 KAT 通过走 Rust NativeAesKdf，JCE 仅兜底（:30-34），与参考项目该条的解决方向一致且已在安卓落地
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 17. MonoWorkarounds.cs:169 → Argon2KdfEngine.kt:68 ⚠ risk=yes
- **坑的本质**：Unix 上 Argon2 只用原生内核，DllNotFound 时静默回退托管实现（性能骤降），且可被命令行关闭
- **触发条件**：Unix + 解锁数据库做 Argon2 派生
- **主项目对应位置**：`crypto/src/main/java/com/keepasskey/crypto/kdf/Argon2KdfEngine.kt:68`
- **对照情况**：同类模式（原生内核探活失败→静默回落 JVM/托管实现）：Argon2KdfEngine.kt:68-82 与 AesKdfEngine.kt:30-34 在原生探活失败（so 加载失败/KAT 不匹配的「个别机型」）时均静默走 BC/JCE 兜底。缓解面：探活为 KAT 对照（NativeArgon2.kt:46-72）、JVM 兜底带堆预检防 OOM（Argon2KdfEngine.kt:97-101）。残余风险：crypto 模块全程零日志（grep 核实），生产代码与 HealthCheckEngine 均无「原生探活失败已回落」的可观测痕迹，已知工程限界表也未登记该静默降级——本仓自测 BC 慢 2.2~5.4 倍（Argon2KdfEngine.kt:14），高 KDF 参数机型上解锁耗时成倍增长且用户与排障方均不可见
- **是否有同样风险**：**yes**
- **建议**：在 Argon2KdfEngine.transform / AesKdfEngine.transform 回落 JVM 兜底的分支加一次性诊断日志（复用 core 的 AppLog，仅记「原生内核探活失败、已回落 JVM 兜底」事实，不含任何 KDF 参数/凭据），或将「原生 KDF 探活状态」纳入 HealthCheckEngine 检查项使降级可观测；若产品裁定接受静默降级，则在 docs/architecture/已知工程限界.md 登记该限界（PasswordStrengthEvaluator.evaluate 的降级分支 crypto/src/main/java/com/keepasskey/crypto/strength/PasswordStrength.kt:115-118 同理）。
- **需补充信息**：（无）

### 18. KdbxFile.Read.Streamed.cs:163 → KdbxProgress.kt:63
- **坑的本质**：底层流可能上报错乱的 Position/Length（M120413），算出 >100% 的进度会砸进度回调，必须钳制到 100
- **触发条件**：读取中流报告异常位置/长度
- **主项目对应位置**：`database/src/main/java/com/keepasskey/database/file/KdbxProgress.kt:63`
- **对照情况**：同类模式（流式读取进度防错乱取值）；KdbxProgressReporter 对每次 emit 做 coerceIn(0f,1f) 并恒单调不减（:63），ProgressCountingInputStream 的比例再钳制一次（:118-121），且回调异常一律吞掉不打断解密/清零（:65-69），两面均已覆盖
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 19. PwDatabase.cs:787 → KdbxGroupMerger.kt:21
- **坑的本质**：合并过程中新组尚未进对象池且池不许改，用合并前的池查组会漏掉新组，必须按 UUID 在实时根树上查找
- **触发条件**：合并遍历源库组结构时
- **主项目对应位置**：`sync/src/main/java/com/keepasskey/sync/merge/KdbxGroupMerger.kt:21`
- **对照情况**：同类模式（合并时定位分组）；主项目合并架构不同——不可变数据模型 + 合并前一次性构建 base/local/remote 三份 UUID 索引 map，对并集逐 UUID 三方裁决（:31-83），单侧新建组有专门保留分支（:75-82），不存在「合并中途查陈旧对象池」的时序面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 20. QualityEstimation.cs:338 → PasswordStrength.kt:157
- **坑的本质**：熵编码器带内部计数状态且非只读，多线程并发评估会互踩结果，必须每次调用新建实例而非复用静态对象
- **触发条件**：多线程同时做口令熵/质量评估
- **主项目对应位置**：`crypto/src/main/java/com/keepasskey/crypto/strength/PasswordStrength.kt:157`
- **对照情况**：同类模式（口令强度/熵评估的并发安全）；主项目评估为无状态——走 Rust 原生内核逐调用评估，JVM 兜底 PasswordStrengthFallback.evaluate 仅用只读不可变词表常量（COMMON_PASSWORDS 为 listOf 不可变 List）与局部变量，无共享可变编码器实例；并发面按 AGENTS 规则跑在 Dispatchers.Default
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 21. MonoWorkarounds.cs:316 → ClipboardSecurityManager.kt:292
- **坑的本质**：剪贴板修复线程靠持续重写剪贴板生效，检测不到 xdotool 就无法只对自身窗口生效，会破坏非自身内容的剪贴板——检测失败即不启动
- **触发条件**：Unix + 剪贴板 workaround + 缺 xdotool
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/security/ClipboardSecurityManager.kt:292`
- **对照情况**：同类模式（清剪贴板动作不得误伤他人内容）；performClearIfMatching 按内容 SHA-256 摘要匹配才清（:310-315），Android 10+ 后台读不到剪贴板时绝不凭陈旧摘要误清——clipboardSuperseded 覆盖写守卫 + ClipboardClearPolicy 裁决（ISSUE-P2-51，:294-308），误清面已封堵
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 22. ClipboardU.cs:43 → ClipboardSecurityManager.kt:154
- **坑的本质**：Mono 的 WinForms Clipboard 实现不可靠（内容失效/丢失），读写全部改走 xsel 外部命令而非系统剪贴板 API
- **触发条件**：Unix/Mono 上读写剪贴板
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/security/ClipboardSecurityManager.kt:154`
- **对照情况**：同类模式（敏感值经剪贴板通道）；主项目走平台 ClipboardManager（Android 唯一系统落点，无 Mono 类实现缺陷面），并叠加 EXTRA_IS_SENSITIVE、超时/熄屏/锁定/冷启动四路自动擦除（:77-82），可靠性由本仓自身纪律保障
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 23. SiCodes.cs:268 → LegacyAutofillAccessibilityService.kt:216
- **坑的本质**：xdotool 对部分字符大小写发错（上游 issue#41），须逐字符改用 XKeySym/dead-key 组合映射重音与大写字母
- **触发条件**：Linux + xdotool 发送重音/大写字符
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/autofill/legacy/LegacyAutofillAccessibilityService.kt:216`
- **对照情况**：同类模式（向目标应用自动填入口令）；主项目无逐字符键击模拟——回填走 AccessibilityNodeInfo.ACTION_SET_TEXT 整体写入（:216），无字符→键映射面，该坑不适用
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 24. SendInputEx.cs:520 → 无直接对应
- **坑的本质**：部分应用的消息处理依赖时序（如 Steam 丢字符），修饰键不一起延迟会被目标应用吞键/错序；键、字符、修饰键都插间隔
- **触发条件**：向时序敏感应用自动输入
- **主项目对应位置**：无直接对应
- **对照情况**：同类模式（向目标应用自动填入），但主项目无逐键发送与时序控制机制（ACTION_SET_TEXT 为原子整体写入，且回填前有窗口包名复核防陈旧窗口，LegacyAutofillAccessibilityService.kt:178），时序丢字符坑不适用
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 25. SiWindowInfo.cs:108 → 无直接对应
- **坑的本质**：按窗口标题识别 Edge 换 Unicode 包的规避手段实测无效（Edge 直接忽略 '@'/€ 等特殊字符的 Unicode 包），整段注释弃用
- **触发条件**：向 Edge 发送 AltGr/特殊字符
- **主项目对应位置**：无直接对应
- **对照情况**：同类模式（按目标应用定制自动输入发送方式），但主项目无按目标进程分派发送方式的机制（统一 ACTION_SET_TEXT），无「规避手段被实测证伪后残留」的面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 26. CustomMessageFilterEx.cs:42 → 无直接对应
- **坑的本质**：.NET 4.6 把输入语言句柄强转 Int32 溢出抛异常，检测到 WM_INPUTLANGCHANGEREQUEST 超范围就吞掉消息防崩溃
- **触发条件**：.NET 4.6 + 切换输入法/按 CapsLock
- **主项目对应位置**：无直接对应
- **对照情况**：无（Win32 消息循环/.NET 句柄强转面，Android Compose 无对应机制）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 27. CustomRichTextBoxEx.cs:120 → 无直接对应
- **坑的本质**：.NET 的 AutoWordSelection=false 实际发 EM_SETOPTIONS 做「切换」而非「关闭」，须先置 true 再 false 才真正关掉
- **触发条件**：设置 AutoWordSelection=false
- **主项目对应位置**：无直接对应
- **对照情况**：无（WinForms RichTextBox 控件样式面，主项目为 Compose 声明式 UI，无此 API）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 28. RichTextBuilder.cs:248 → 无直接对应
- **坑的本质**：Windows(KPB 1780)/Mono(586901) 的 RichTextBox 对 >U+00FF 字符编码出错，先用随机占位码替换、生成 RTF 后再回填真实字符
- **触发条件**：含高位 Unicode 字符的文本进富文本框
- **主项目对应位置**：无直接对应
- **对照情况**：无（RTF 编码面）；主项目笔记为纯文本展示与存储，grep 核实无 RichText/Html.fromHtml/Markdown 渲染路径，无 RTF 编码环节
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 29. NativeMethods.New.cs:936 → 无直接对应
- **坑的本质**：Windows 的 GetKeyboardState 拿不到当前虚拟键数组，传闻的预热也不可靠；无法确证键态时拒绝转换
- **触发条件**：未显式提供键态快照就做键转字符
- **主项目对应位置**：无直接对应
- **对照情况**：无（Win32 键盘状态 API 面，Android 无对应机制，主项目也不做键态→字符转换）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 30. ExpiryControlGroup.cs:54 → EntryEditViewModel.kt:385
- **坑的本质**：用户正在输入未提交时直接读 DateTimePicker.Value 会拿到旧值；先闪隐控件强逼校验再挂 KeyPress 兜底（KPB 3505269）
- **触发条件**：读取时 DateTimePicker 仍持有焦点
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditViewModel.kt:385`
- **对照情况**：同类模式（条目过期时间编辑取值）；主项目为 Compose 状态驱动——onExpiryDateSelected 显式落 uiState（:385-387），选择即提交，无「控件持焦读旧值」的隐式取值时序面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 31. ProtectedDialog.cs:219 → 无直接对应
- **坑的本质**：Win11 上 SetForegroundWindowEx 无法把焦点还给原窗口（错误提示框被压在后面），改用 ActivateTopWindowEx 激活栈顶窗口
- **触发条件**：Windows 11 + 模态对话框关闭
- **主项目对应位置**：无直接对应
- **对照情况**：无（Win32 前台窗口/焦点管理面，Android 窗口栈由系统管理，主项目无自管焦点恢复代码）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 32. UIUtil.cs:3307 → 无直接对应
- **坑的本质**：WindowState 变更处理器内再次改状态会让属性与真实窗口状态脱钩，设置后必须回读真实状态强制同步
- **触发条件**：WindowState 处理器内递归改状态
- **主项目对应位置**：无直接对应
- **对照情况**：无（WinForms 窗口状态属性同步面；Compose 的 UI 状态为单向数据流，无「属性与真实窗口脱钩」的同型机制）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 33. History.txt:1318 → 无直接对应
- **坑的本质**：Mono 下「从任务栏/托盘恢复时聚焦快速搜索框」两选项工作不可靠（Mono bug），产品层面在 Mono 上直接禁用整组选项
- **触发条件**：Mono 运行 + 恢复自托盘
- **主项目对应位置**：无直接对应
- **对照情况**：无（主项目为 Android 应用，无系统托盘/任务栏恢复机制，无对应的平台可靠性开关组）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 34. History.txt:1430 → 无直接对应
- **坑的本质**：Windows IME bug 致黑屏/IME(CTF) 进程 CPU 飙升，切换到安全桌面（UAC）前主动禁用输入法规避
- **触发条件**：切到安全桌面 + 启用 IME
- **主项目对应位置**：无直接对应
- **对照情况**：无（Windows 安全桌面/IME 系统级规避面，Android 无 UAC 安全桌面机制）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

## 三、复核记录（独立复核结论）

无需修正的核实记录（所有行复核通过、字段零改动）：

1. **risk=yes 行（MonoWorkarounds.cs:169 → Argon2/AesKdfEngine 静默降级）逐证据点核实全部成立**——Argon2KdfEngine.kt:68 原生门控与 :82 静默回落 transformJvm、AesKdfEngine.kt:30-34 available==false 即静默走 AesKdfJce 均属实；NativeArgon2.kt:46-72 确为 KAT 探活失败仅返回 false；Argon2KdfEngine.kt:97-101 堆预检、:14「快 2.2~5.4 倍」、PasswordStrength.kt:115-118 降级分支均属实。
2. **「不可观测」面证据**：grep -rn "AppLog\|Log\.\|println\|Logger" crypto/src/main/java 零命中（crypto 全程零日志）；grep -rn "nativeAvailable\|NativeArgon2.available\|NativeAesKdf.available\|NativePasswordStrength.available" app/src/main database/src/main 零命中（无生产消费点）；HealthCheckEngine（database/src/main/java/com/keepasskey/database/audit/HealthCheckEngine.kt:41）仅做条目级口令体检无原生探活项；grep -n "探活\|静默降级\|JVM 兜底" docs/architecture/已知工程限界.md 仅命中 366（导入提示）/700（ROM 启动）两处无关条目，确未登记该降级。
3. **advice 可行**：AppLog 存在于 core/src/main/java/com/keepasskey/core/log/AppLog.kt（android.util.Log 包装、release 经 R8 剥离、fail-safe），crypto/build.gradle.kts:86 implementation(project(":core")) 依赖合法（crypto→core 单向），日志仅记「探活失败已回落」事实不含敏感数据不违反铁律；PasswordStrength.nativeAvailable（PasswordStrength.kt:107）public 且 database→crypto 依赖合法，HealthCheckEngine 备选成立（注：该引擎现为条目级体检器 analyzeEntries，纳入设备级检查项属扩展其职责，落地时以「回落日志 + 限界表登记」两条最直接）。
4. **risk=no 抽查两条一致**：WebDavSyncProvider.kt:74-79 非 https 构造期抛 InvalidEndpointError 属实，grep -rni "ftp" 主源码仅命中 draftPassword 子串误命中、确无 FTP 通道；EntryDetailUrlActions.kt:34-43 仅 http(s) 放行 LAUNCH、其余 COPY_FALLBACK，:66-75 runCatching 兜底回落复制属实。
