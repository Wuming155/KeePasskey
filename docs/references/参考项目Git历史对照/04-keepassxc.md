# 参考项目 Git 历史对照 · keepassxc 分册

**范围**：🥦 KeePassXC（合并与 Passkey schema 参考、格式裁决者之一）全量提交历史中修复/防护类提交的挖掘与主项目对照。
**统计**：挖掘记录 **40** 条，对照行 **40** 条——yes 6 / unclear 0 / excluded 0 / no 34。
**复核状态**：**confirmed=true**（经独立复核，修正记录见「三」节；含一处 advice 事实性改写与一处路径补正）。
**日期**：2026-09-29。总表见 [00-对照总表.md](00-对照总表.md)。

## 一、挖掘记录（40 条）

| # | 提交 | subject | 本质（essence） | 触发（trigger） | 位置（area） | 领域 | 类型 | 置信度 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | c18d6b5a（2018-01-25） | Fix KDBX4 reader/writer attachment mapping error | KDBX4 重复附件在内层二进制头重复登记且读侧未跳过，附件映射错乱 | KDBX4 库中同一附件出现多次（编辑附件且开历史） | src/format/Kdbx4Reader.cpp、Kdbx4Writer.cpp | kdbx格式 | bugfix | confirmed |
| 2 | 6d5c6c7d（2019-05-25） | Read all database attachments even if duplicated | 第三方程序不按规范合并重复二进制，读取时丢重复附件致信息丢失；改为全量读取、保存时再合并 | 读取含重复二进制的非规范 KDBX4 文件 | src/format/Kdbx4Reader.cpp | kdbx格式 | bugfix | confirmed |
| 3 | 4f8c2040（2024-10-15） | Avoid hitting assert on XML export | XML 导出路径构造 KdbxXmlWriter 未传 BinaryIdxMap，导出含二进制库时触发断言；补空映射 | 导出 XML（库含附件/二进制） | src/format/KdbxWriter.cpp | kdbx格式 | bugfix | confirmed |
| 4 | c6f83b9c（2017-10-13） | Fix: Regenerate transform seed and transform master key on save. | 每次保存复用同一 transform seed 与已变换主密钥，降低离线爆破成本；保存前重新生成 | 每次保存 kdbx | src/core/Database.cpp、src/format/KeePass2Writer.cpp | 加密KDF | bugfix | confirmed |
| 5 | a895729b（2017-10-21） | :bug: Fix result propagation in SymmetricCipherGcrypt::process | gcrypt 后端 process 的 ok 标志恒置 true，加解密错误被吞；改为按返回值传播 | libgcrypt 后端加解密出错 | src/crypto/SymmetricCipherGcrypt.cpp | 加密KDF | bugfix | confirmed |
| 6 | 4fbdf0c4（2026-04-16） | Fixed uninitialized variable m_mode in SymmetricCipher.h (#13173) | m_mode 成员未初始化即被读取（未设模式时 UB）；补默认 InvalidMode | SymmetricCipher 未设模式即使用 | src/crypto/SymmetricCipher.h | 加密KDF | bugfix | confirmed |
| 7 | 13eb1c0b（2019-02-21） | Improve resilience against memory attacks | 全局 delete 前清零内存并经 gcrypt/libsodium 安全内存存放长存密钥组件，降低释放后秘密残片泄漏 | 密钥等秘密缓冲释放时 | src/core/Alloc.cpp、src/keys/PasswordKey.cpp 等 | 敏感数据 | guard | confirmed |
| 8 | 65a1d1b0（2022-04-03） | Limit zxcvbn entropy estimation length | 超长口令 zxcvbn 熵评估耗时失控；超阈值部分改按平均熵线性外推封顶耗时 | 评估超长口令强度 | src/core/PasswordHealth.cpp | 其他 | guard | confirmed |
| 9 | a8cfefe6（2024-01-02） | Fix database merge crash when fdosecrets is enabled (#10136) | 合并事务内目标组短暂并存同 UUID 两条目，fdosecrets 依赖 UUID 唯一性建 DBus 路径致崩溃；先摘除再加回并防空指针 | fdosecrets 开启时触发合并 | src/core/Entry.cpp、src/core/Merger.cpp、src/fdosecrets | 合并 | bugfix | confirmed |
| 10 | e5904135（2026-08-09） | Fix history items losing entry CustomData (#13573) | beginUpdate 建历史快照漏拷 CustomData，恢复历史即清空浏览器设置等客户端元数据 | 更新条目生成历史后回滚 | src/core/Entry.cpp | 其他 | bugfix | confirmed |
| 11 | e367c6df（2020-05-01） | Fix merging browser keys | 合并无条件覆盖 CustomData 致浏览器扩展键丢失；引入受保护键清单阻止覆盖 | 合并含浏览器集成键的库 | src/core/Merger.cpp、src/core/CustomData.cpp | 合并 | bugfix | confirmed |
| 12 | c19703c3（2019-09-16） | Merge custom data only when necessary (#3475) | 元数据 CustomData 新旧比较方向写反（> 当 <），源较新反被旧值覆盖；并跳过 LastModified 与同值键 | 合并双方均改过 CustomData | src/core/Merger.cpp | 合并 | bugfix | confirmed |
| 13 | 94ace985（2024-03-18） | Preserve Secret Service exposed group setting on merge | Secret Service 暴露组标记未列入受保护 CustomData 清单，合并后被对端覆盖丢失 | 合并含暴露组标记的库 | src/core/Merger.cpp | 合并 | bugfix | confirmed |
| 14 | 811887e5（2025-02-01） | Fix issues with reloading and handling of externally modified db file (#10612) | 外部修改重载失败即丢改动：堵漏检外部变更、重载期间禁保存、失败弹解锁并标记脏、保存前合并续跑 | 库文件被外部程序修改 | src/gui/DatabaseWidget.cpp、src/core/Database.cpp、src/gui/DatabaseOpenDialog.cpp | 同步 | bugfix | confirmed |
| 15 | ed0429ad（2025-03-06） | Write to buffer before writing directly to database file | 直写盘模式先写内存缓冲再落盘，避免硬件密钥确认期间数据库文件被截为 0 字节 | 直写模式+需按键的硬件密钥 | src/core/Database.cpp | 文件IO | bugfix | confirmed |
| 16 | 219a0f40（2019-04-20） | Prevent infinite save loop when location is unavailable (#3026) | 自动保存位置失联时，保存失败→modified 信号→再保存死循环且应用拒退出；切断该回路 | 自动保存+保存位置不可用（如盘卸载） | src/gui/DatabaseWidget.cpp | 文件IO | bugfix | confirmed |
| 17 | 2aac83d0（2019-08-30） | Improve handling of read-only files (#3408) | 只读库禁自动保存并正确标记已修改，锁定/关闭时引导另存而非静默弃改动；同路径只读保存直接报错 | 打开只读 kdbx 并改动 | src/core/Database.cpp、src/gui/DatabaseWidget.cpp | 文件IO | bugfix | confirmed |
| 18 | fbebf30b（2020-06-06） | Fix permissions changing on database save | 非安全模式保存改掉 kdbx 原有权限；新库/新密钥文件改按 0600 落盘 | 保存或新建库/密钥文件 | src/gui/DatabaseWidget.cpp 等 | 文件IO | bugfix | confirmed |
| 19 | e1c8304c（2021-05-15） | Fix unreachable setting of file permissions (#6514) | 备份恢复路径 return 之后才设权限，0600 恢复永不执行；改为拷贝成功后再设权限 | 从备份文件恢复数据库 | src/core/Database.cpp | 文件IO | bugfix | confirmed |
| 20 | 80e68bb4（2026-09-15） | Fix out-of-bounds writing to browser messages | native messaging 消息黏包/分片/含尾随垃圾时解析越界；按 } 切分重组、拼接设上限、分片暂存缓冲 | 浏览器消息跨 socket 读写边界 | src/browser/BrowserHost.cpp、BrowserMessageBuilder.cpp | Autofill | bugfix | confirmed |
| 21 | dfdd561f（2026-09-21） | Passkeys: Fix parsing punycode domains | QUrl::host() 返回解码 Unicode 域名，IDN 域被判非法/非 ASCII 而拒；改取 FullyEncoded 保留 xn-- 形式 | 国际化域名(IDN)作通行密钥 origin | src/browser/PasskeyUtils.cpp | 凭据passkey | bugfix | confirmed |
| 22 | 74326616（2025-07-02） | Fix two problems with URL wildcard matching | URL 转正则未转义点号致跨段任意匹配；*.$ 被错替换为 * 致主域误命中，二者皆修正 | 条目 URL 用通配符匹配 | src/gui/UrlTools.cpp、src/browser/BrowserService.cpp | Autofill | bugfix | confirmed |
| 23 | c46f3d37（2020-06-19） | Browser: Check for expired entry prior to custom data | 检查顺序颠倒：无 BrowserEntryConfig 的过期条目判 Unknown 走确认流程而非按过期拒绝；过期检查前置 | 过期条目无浏览器配置数据 | src/browser/BrowserService.cpp | Autofill | bugfix | confirmed |
| 24 | d2e76058（2020-01-13） | Fix base domain matching | 主机 endsWith 判定未校验注册域相等，任意同后缀主机即误配；补基域相等条件 | 站点主机以条目域名为后缀 | src/browser/BrowserService.cpp | Autofill | bugfix | confirmed |
| 25 | e2c95f75（2019-11-12） | Fix subdomain matching | 搜索只用站点主机与基域，submitUrl 未参与匹配且逐级剥域错位；改传完整 URL+submitUrl 匹配 | 提交域与站点域不同的表单 | src/browser/BrowserService.cpp | Autofill | bugfix | confirmed |
| 26 | 5b312889（2023-02-25） | Fix various bugs when returning credentials (#9136) | 返回凭据多处错：httpAuth 用未初始化变量、空结果路径漏判、确认记忆分支错位，集中修复 | 扩展请求返回凭据列表 | src/browser/BrowserService.cpp、BrowserAction.cpp | Autofill | bugfix | confirmed |
| 27 | 5883f49f（2024-03-11） | Passkeys: Fix RP ID validation | rp.id 未设时本应默认取 origin 有效域，此前直接报 RPID_MISMATCH 拒绝合法请求；按 WebAuthn 规范补默认 | 通行密钥请求未带 rp.id | src/browser/PasskeyUtils.cpp、BrowserService.cpp | 凭据passkey | bugfix | confirmed |
| 28 | 5cb6ad63（2025-05-22） | Passkeys: Fix ordering of clientDataJSON | clientDataJSON 错按 JSON 对象校验且键序不符 W3C 序列化顺序；改按字符串校验并按规范序构建 | 生成通行密钥 clientDataJSON | src/browser/PasskeyUtils.cpp、BrowserPasskeys.cpp | 凭据passkey | bugfix | confirmed |
| 29 | d9be1b06（2026-03-09） | Passkeys: Fix default BE and BS flag value (#13122) | BE/BS 标志按 "true" 写入但协议期望 "1"，读写解析不一致致标志错；统一写 "1" 并兼容两种读法 | 读写通行密钥条目 BE/BS 标志 | src/browser/BrowserService.cpp | 凭据passkey | bugfix | confirmed |
| 30 | ad8a00d5（2024-06-06） | Passkeys: Fix incorrect username fill | 条目含通行密钥时 login 字段被 Passkey 用户名无条件覆盖，普通口令填充返回错误用户名；删该分支 | 带 passkey 的条目作口令填充 | src/browser/BrowserService.cpp | Autofill | bugfix | confirmed |
| 31 | 6f114226（2024-03-29） | Prevent SSH Agent from using entries in the recycle bin | 已删入回收站的密钥条目仍被 SSH Agent 选用；补回收站判定守卫拒绝 | 密钥条目被删入回收站 | src/sshagent/SSHAgent.cpp、src/core/Group.cpp | 凭据passkey | guard | confirmed |
| 32 | 30e1fa5d（2026-08-23） | Fix use-after-free when a database tab close is re-entered (#13598) | 保存等待 worker 的嵌套事件循环中重入关 tab，Qt close 短路致下游守卫不跑，deleteLater 在嵌套循环析构仍在用对象；重入直接拒绝 | 关 tab 时嵌套循环再次触发关闭 | src/gui/DatabaseTabWidget.cpp、src/fdosecrets | 并发 | bugfix | confirmed |
| 33 | 6481eccc（2024-03-17） | Fix crash on screen lock or computer sleep | 屏幕锁/休眠竞态下清库数据把关键密钥组件置 nullptr，他线程仍在用即崩；禁置空+加断言守卫 | 锁屏/休眠与解锁操作并发 | src/core/Database.cpp | 并发 | bugfix | confirmed |
| 34 | e180980b（2022-10-18） | Fix potential deadlock in UI when saving | 保存开始瞬间 file watcher 报外部变更，锁时序错致 UI 死锁；调整保存锁位置并禁保存中重载 | 保存时外部改动触发重载 | src/gui/DatabaseWidget.cpp | 并发 | bugfix | confirmed |
| 35 | 0ad75ccb（2022-03-04） | Fix missing include in alloc preventing some secure deallocations | Alloc.cpp 缺 QtGlobal 头致 sized-delete 安全释放分支未编入，部分秘密内存不清零释放 | 平台走安全释放分支 | src/core/Alloc.cpp | 敏感数据 | bugfix | confirmed |
| 36 | 93f0fef1（2021-06-08） | Improve and secure attachment handling (fixes #2400) | 附件临时文件改全随机名+严格权限+关库时随机覆写删除，并监听外部修改提示回存，防元数据泄露与残留 | 外部程序打开库内附件 | src/core/EntryAttachments.cpp、src/gui/DatabaseWidget.cpp | 敏感数据 | guard | confirmed |
| 37 | da8874de（2024-06-16） | Improve Entry placeholder resolution (#10846) | 占位符解析对非占位符花括号文本空转到递归上限并报"最大深度"；提前判非占位符即止 | 字段含花括号非占位符文本 | src/core/Entry.cpp | 其他 | bugfix | confirmed |
| 38 | e888fec0（2026-07-21） | Fix password strength evaluation for referenced passwords (#13479) | 密码为 {REF} 引用时只解析单级占位符，强度评估取到引用原文而非真实口令；改多级解析 | 密码字段引用他条目密码 | src/core/Entry.cpp、src/gui/PasswordWidget.cpp | 其他 | bugfix | confirmed |
| 39 | 474da9b8（2026-09-20） | Fix identifying nested reference placeholders (#13688) | 深层嵌套引用占位符被判 Unknown 而漏处理；引用正则先行判别，命中即按占位符走 | 字段含嵌套 {REF} 引用 | src/core/EntryPlaceholders.cpp、EntryAttributes.cpp | 其他 | bugfix | confirmed |
| 40 | 634a5b34（2024-04-21） | Improve inactivity timer | 闲置计时器被高频事件每秒重置数百次且无下限，自动锁定计时被冲掉；加节流与 10s 最小超时 | 高频用户事件刷新闲置计时 | src/core/InactivityTimer.cpp | 其他 | bugfix | confirmed |

## 二、对照行全文（40 条）

### 行 1｜c18d6b5a Fix KDBX4 reader/writer attachment mapping error
- **本质**：KDBX4 内层二进制头重复登记同一附件且读侧未跳过，附件映射错乱
- **触发**：KDBX4 库中同一附件出现多次（编辑附件且开历史）
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/file/InnerHeaderReader.kt:31-98；database/src/main/java/com/keepasskey/database/xml/KdbxXmlBinaryNode.kt:102-114
- **相似度**：同类模式（内层二进制池与附件 Ref 下标映射），但本仓语义已是修复后形态
- **risk**：no
- **advice**：读侧对池按顺序全量 append（重复项保留，Ref 按池下标解析、越界回落内联），保存侧 KdbxBinaryDeduplicator 按 (flags,size,内容哈希) 指纹去重并统一改写 refIndex——与 KeePassXC 6d5c6c7d 修复后的「全量读取、保存时再合并」一致，无需动作。

### 行 2｜6d5c6c7d Read all database attachments even if duplicated
- **本质**：第三方文件含重复二进制时读侧丢弃重复附件致信息丢失；改为全量读取、保存时再合并
- **触发**：读取含重复二进制的非规范 KDBX4 文件
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/file/InnerHeaderReader.kt:39-138（重复项照常入池）；保存侧 database/src/main/java/com/keepasskey/database/file/KdbxBinaryDeduplicator.kt:80-82
- **相似度**：同一功能且已实现修复后语义（读全量、写去重）
- **risk**：no
- **advice**：本仓读侧不丢弃重复二进制、保存时按指纹合并，已是 KeePassXC 修复后的口径，无需动作。

### 行 3｜4f8c2040 Avoid hitting assert on XML export
- **本质**：XML 导出路径构造 KdbxXmlWriter 未传 BinaryIdxMap，导出含二进制库时触发断言
- **触发**：导出 XML（库含附件/二进制）
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/xml/KeePassXmlExporter.kt:33-51
- **相似度**：功能相似（明文 XML 导出面），但导出器只写 Generator/分组树/条目字段，不含二进制池
- **risk**：no
- **advice**：KeePassXmlExporter 按官方可缺省语义显式省略 Attachments/CustomIcons（KDoc 声明），无 BinaryIdxMap 参数亦无断言路径，触发面不存在。

### 行 4｜c6f83b9c Fix: Regenerate transform seed and transform master key on save.
- **本质**：每次保存复用同一 transform seed 与已变换主密钥，降低离线爆破成本；保存前重新生成
- **触发**：每次保存 kdbx
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/file/KdbxFile.kt:385-408
- **相似度**：同一功能且已实现：每次保存重生成 masterSeed/加密 IV/KDF 盐（Argon2 seed、AES-KDF salt 随机刷新）
- **risk**：no
- **advice**：保存链已逐次刷新盐/种子并随之重派生（PD-01/PD-03 引用的 KdbxFile.kt:332,337 现值即本段），无需动作。

### 行 5｜a895729b :bug: Fix result propagation in SymmetricCipherGcrypt::process
- **本质**：gcrypt 后端 process 的 ok 标志恒置 true，加解密错误被吞；改为按返回值传播
- **触发**：libgcrypt 后端加解密出错
- **我们的对应位置**：无直接对应（crypto/src/main/rust/src/aes_cbc.rs + crypto/src/main/java/com/keepasskey/crypto/cipher/CbcStreams.kt）
- **相似度**：无——对称密码为 Rust 内核 + JCE 兜底双栈，无 gcrypt 后端，无「布尔标志恒真吞错」结构
- **risk**：no
- **advice**：流式路径错误语义以实测 CipherInputStream 基线锁定（CbcStreamFramingTest / CipherFallbackParityTest，PD-07），兜底与原生产物逐字节对拍，无需动作。

### 行 6｜4fbdf0c4 Fixed uninitialized variable m_mode in SymmetricCipher.h (#13173)
- **本质**：m_mode 成员未初始化即被读取（未设模式时 UB）；补默认 InvalidMode
- **触发**：SymmetricCipher 未设模式即使用
- **我们的对应位置**：无直接对应
- **相似度**：无——Kotlin 属性强制初始化，不存在未初始化成员读取的 UB 面
- **risk**：no

### 行 7｜13eb1c0b Improve resilience against memory attacks
- **本质**：全局 delete 前清零内存并经 gcrypt/libsodium 安全内存存放长存密钥组件，降低释放后秘密残片泄漏
- **触发**：密钥等秘密缓冲释放时
- **我们的对应位置**：docs/architecture/敏感缓冲所有权契约.md（全仓清零契约）；core/src/main/java/com/keepasskey/core/security/BinaryStore.kt
- **相似度**：同类模式（秘密缓冲生命周期治理），本仓以 CharArray/ByteArray 显式清零 + 所有权契约 + ProtectedString 驻留加密承担
- **risk**：no
- **advice**：Android 无 gcrypt/libsodium 安全池对应面；「进程内取证在信任边界外」与「落盘清理 unlink-only」两项残余已按既有登记（已知工程限界 §1.5、§2.2）接受，无需另立整改。

### 行 8｜65a1d1b0 Limit zxcvbn entropy estimation length
- **本质**：超长口令熵评估耗时失控；超阈值部分改按平均熵线性外推封顶耗时
- **触发**：评估超长口令强度
- **我们的对应位置**：crypto/src/main/rust/src/strength.rs:84-86,175-181（MAX_ANALYZED_CHARS=256 + 超额线性惩罚）；crypto/src/main/java/com/keepasskey/crypto/strength/PasswordStrength.kt:223
- **相似度**：同一功能且已实现同款防护（ISSUE-P2-58 / 审计 RUST-03）：模式识别只做前 256 字符，超出部分线性惩罚，耗时与输入规模脱钩
- **risk**：no
- **advice**：本仓强度内核的「截断分析 + 线性外推 + 长度分档上限」即 KeePassXC 该修复的同型方案且更早落地，无需动作。

### 行 9｜a8cfefe6 Fix database merge crash when fdosecrets is enabled (#10136)
- **本质**：合并事务内目标组短暂并存同 UUID 两条目，fdosecrets 依赖 UUID 唯一性建 DBus 路径致崩溃
- **触发**：fdosecrets 开启时触发合并
- **我们的对应位置**：无直接对应（sync/src/main/java/com/keepasskey/sync/merge/KdbxMerger.kt）
- **相似度**：无——本仓无 fdosecrets/DBus 对象路径；合并按 UUID Map 一次性构建索引、产物整树替换，无「同 UUID 双对象短暂并存 + 路径唯一性依赖」面
- **risk**：no

### 行 10｜e5904135 Fix history items losing entry CustomData (#13573)
- **本质**：beginUpdate 建历史快照漏拷 CustomData，恢复历史即清空浏览器设置等客户端元数据
- **触发**：更新条目生成历史后回滚
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/history/HistoryManager.kt:47,82,89-94
- **相似度**：同一功能（历史快照/回滚）且结构性免疫：快照为整对象 data class copy（currentEntry.copy(history=emptyList())），customData 随全部字段保留
- **risk**：no

### 行 11｜e367c6df Fix merging browser keys
- **本质**：合并无条件覆盖 CustomData 致浏览器扩展键丢失；引入受保护键清单阻止覆盖
- **触发**：合并含浏览器集成键的库
- **我们的对应位置**：sync/src/main/java/com/keepasskey/sync/merge/KdbxEntryMerger.kt:157-172（isModified 不比较 customData）、:232-239（mergeConflictedEntry 的 copy 清单不含 customData，冲突路径以本地为底版）
- **相似度**：功能相似（条目级 CustomData 的合并语义），本仓同样未把 customData 纳入合并词汇表，远端改动会被静默丢弃
- **risk**：**yes**
- **advice**：把 entry.customData 纳入 ISSUE-P2-279「单一词汇表」纪律：①isModified 增加 customData 比较；②mergeConflictedEntry 对 customData 做逐键三方合并（单侧变更取该侧、双侧异值按 LWW 或并集）。已核实且唯一坐实的丢失路径：远端改 customData（如浏览器键写入者）且本地改任意字段时，双改走冲突合并、以 local 为底版且 customData 不在合并集，远端 customData 恒丢。（复核修正：原 advice 称「本地仅改 customData 而远端改字段走远端整条胜出致本地改动丢失」不成立——VaultEntryWriteCoordinator.kt:256 写收藏时同步 lastModificationTime=now()，isModified 经 times 即判本地已改，走冲突路径后本地 customData 保留。）条目级 customData 已确认 XML 读写双向存在（KdbxXmlEntrySerializer.kt:105-107），触发面真实。注意 PD-35（产品裁决登记.md:1113）只裁库级 Meta 的 customData「以本地为准」，不覆盖条目级，无可援引豁免。

### 行 12｜c19703c3 Merge custom data only when necessary (#3475)
- **本质**：元数据 CustomData 新旧比较方向写反（> 当 <），源较新反被旧值覆盖；并跳过 LastModified 与同值键
- **触发**：合并双方均改过 CustomData
- **我们的对应位置**：sync/src/main/java/com/keepasskey/sync/merge/KdbxEntryMerger.kt:157-172（与上一条同根：customData 根本不参与比较与合并）
- **相似度**：同一根因面：本仓不存在「比较方向写反」，因为 customData 压根不在比较/合并集内，丢失效果等价
- **risk**：**yes**
- **advice**：与 e367c6df 同批整改（isModified + mergeConflictedEntry 纳入 customData，单侧变更取该侧即天然实现「仅必要时合并」）；整改时同批补「同值跳过、不留痕噪声」的负例用例。

### 行 13｜94ace985 Preserve Secret Service exposed group setting on merge
- **本质**：Secret Service 暴露组标记未列入受保护 CustomData 清单，合并后被对端覆盖丢失
- **触发**：合并含暴露组标记的库
- **我们的对应位置**：sync/src/main/java/com/keepasskey/sync/merge/KdbxGroupMerger.kt:225-238,245-269（组级合并词汇表 MERGED_GROUP_FIELDS 与 both-modified 合并均不含 customData）
- **相似度**：同类模式（组级 CustomData 标记随合并丢失）；本仓组级 customData 暂无生产写入者（app/src/main/java/com/keepasskey/app/data/childdb/ChildDatabaseSessionManager.kt:28-30 明示不写入根库），但同步来库若含第三方组级标记（KeePassXC Secret Service 暴露组等）经合并会被丢弃
- **risk**：**yes**
- **advice**：与条目级 customData 同批处理：把 group.customData 纳入组级 isGroupModified 与 mergeGroupsBothModified 的三方逐键合并；若判「本仓无组级 customData 消费方、暂不整改」，须按规则 6.1 补登 ACTIVE_ISSUES 或在《已知工程限界》登记，不得留为未登记缺口。

### 行 14｜811887e5 Fix issues with reloading and handling of externally modified db file (#10612)
- **本质**：外部修改重载失败即丢改动：堵漏检外部变更、重载期间禁保存、失败弹解锁并标记脏、保存前合并续跑
- **触发**：库文件被外部程序修改
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/session/SessionPersistence.kt:41-99（save 仅只读/凭据检查后即整树覆盖写盘）；app/src/main/java/com/keepasskey/app/data/repository/VaultLifecycleCoordinator.kt:45-101（打开时无 mtime/size 基线快照）
- **相似度**：功能相似且缺口坐实：外部改动后无任何检测，下一次保存以内存旧树静默覆盖
- **risk**：**yes**
- **advice**：P2-378 已跟踪（AC①②③④⑤ 已给出 mtime+size 基线、保存/回前台两时点校验、三选一处置与测试口径，见 ACTIVE_ISSUES.md:60-83）。本条 KeePassXC 修复的增量教训供 P2-378 落实吸收：重载失败不得丢改动（弹解锁并保留脏标记）、重载进行期间禁止保存、保存前先走既有 KdbxMerger 合并续跑。
- **duplicateOf**：注释批次：keepass2android 外部修改检测 mtime 毫秒截断（对应 P2-378）

### 行 15｜ed0429ad Write to buffer before writing directly to database file
- **本质**：直写盘模式先写内存缓冲再落盘，避免硬件密钥确认期间数据库文件被截为 0 字节
- **触发**：直写模式+需按键的硬件密钥
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/session/SessionPersistence.kt:88-95
- **相似度**：同一保存面但触发条件不成立：本仓保存链为内存全量序列化+加密完成后才经 writer 落盘，KDF 纯软件、无硬件按键等待窗口
- **risk**：no
- **duplicateOf**：注释批次：KeePassXC direct write 截断为 0 字节（SessionPersistence.kt:16）

### 行 16｜219a0f40 Prevent infinite save loop when location is unavailable (#3026)
- **本质**：自动保存位置失联时，保存失败→modified 信号→再保存死循环且应用拒退出
- **触发**：自动保存+保存位置不可用（如盘卸载）
- **我们的对应位置**：无直接对应（全仓 rg "autosave|自动保存" 无保存触发器实现；保存均由显式用户动作驱动，database/src/main/java/com/keepasskey/database/session/SessionPersistence.kt:99-102 失败归一 KdbxResult.Failure）
- **相似度**：无——无自动保存回路，失败路径不产生任何「modified→再保存」信号环
- **risk**：no

### 行 17｜2aac83d0 Improve handling of read-only files (#3408)
- **本质**：只读库禁自动保存并正确标记已修改，锁定/关闭时引导另存；同路径只读保存直接报错
- **触发**：打开只读 kdbx 并改动
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/session/SessionPersistence.kt:46-52
- **相似度**：同一功能且已实现 KeePassXC 修复后的核心语义：只读保存显式类型化报错（「数据库以只读模式打开，无法保存」）；只读为调用方显式传参，无「网络盘静默降级只读」误判面
- **risk**：no

### 行 18｜fbebf30b Fix permissions changing on database save
- **本质**：非安全模式保存改掉 kdbx 原有权限；新库/新密钥文件改按 0600 落盘
- **触发**：保存或新建库/密钥文件
- **我们的对应位置**：无直接对应（库文件落应用私有目录默认私有权限；附件缓存显式 POSIX 置位见 sync/src/main/java/com/keepasskey/sync/engine/SyncCacheFiles.kt:84-97）
- **相似度**：同类模式（落盘权限治理）且已更严：0600/0700 仅主权限不变量由 SyncCacheAndroidRuntimeTest 真机锁定
- **risk**：no
- **advice**：Android 沙箱内新建文件默认私有权限，保存走同目录临时文件+rename 不触碰既有权限；导出走 SAF 用户指定位置（权限归系统文档提供方），触发面不存在。

### 行 19｜e1c8304c Fix unreachable setting of file permissions (#6514)
- **本质**：备份恢复路径 return 之后才设权限，0600 恢复永不执行；改为拷贝成功后再设权限
- **触发**：从备份文件恢复数据库
- **我们的对应位置**：无直接对应
- **相似度**：无——本仓无「从备份恢复」入口：.kdbx.bak 在应用内无读回路径（产品裁决登记 PD-17 边界③明示），无恢复代码路径可含此类不可达缺陷
- **risk**：no

### 行 20｜80e68bb4 Fix out-of-bounds writing to browser messages
- **本质**：native messaging 消息黏包/分片/含尾随垃圾时解析越界；按 } 切分重组、拼接设上限、分片暂存缓冲
- **触发**：浏览器消息跨 socket 读写边界
- **我们的对应位置**：无直接对应
- **相似度**：无——本仓无浏览器扩展/native messaging 通道（凭据交付仅传统 Autofill + Credential Manager 双通道；自定义键盘通道亦经 PD-55 裁决不做），无跨 socket 消息分片重组面
- **risk**：no

### 行 21｜dfdd561f Passkeys: Fix parsing punycode domains
- **本质**：QUrl::host() 返回解码 Unicode 域名，IDN 域被判非法/非 ASCII 而拒；改取 FullyEncoded 保留 xn-- 形式
- **触发**：国际化域名(IDN)作通行密钥 origin
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/passkey/DomainMatcher.kt:94-112（isDomainMatch 双侧 toAsciiHost 归一为 punycode）；app/src/main/java/com/keepasskey/app/passkey/PublicSuffixList.kt:19-20,337-347
- **相似度**：同一功能且已实现同向防护：IDN 不被拒收，Unicode/punycode 两种表示在匹配与 PSL 查询期统一归一
- **risk**：no
- **advice**：isRegistrableDomain 与 isDomainMatch 两侧均经 IDN.toASCII 归一，非 ASCII rpId/origin 均可匹配；与 KeePassXC 的「保留 xn-- 形式」殊途同归（本仓两侧同形归一），无需动作。

### 行 22｜74326616 Fix two problems with URL wildcard matching
- **本质**：URL 转正则未转义点号致跨段任意匹配；*.$ 被错替换为 * 致主域误命中
- **触发**：条目 URL 用通配符匹配
- **我们的对应位置**：无直接对应（app/src/main/java/com/keepasskey/app/autofill/AutofillCandidateRanker.kt 设计前提段：只排序不放宽，入选先过 DomainMatcher.isDomainMatch 严格点号边界匹配）
- **相似度**：无——本仓无 URL 通配符/正则展开特性，候选匹配恒为严格域名/包名匹配，触发面不存在
- **risk**：no

### 行 23｜c46f3d37 Browser: Check for expired entry prior to custom data
- **本质**：检查顺序颠倒：无 BrowserEntryConfig 的过期条目判 Unknown 走确认流程而非按过期拒绝；过期检查前置
- **触发**：过期条目进入凭据供给流程
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/autofill/AutofillCandidateRanker.kt（入选与排序无过期维度）；旁证：rg -rni "expired|过期" 于 app autofill/passkey 两包零命中，过期仅由 database/src/main/java/com/keepasskey/database/audit/HealthCheckEngine.kt 与条目详情 UI 消费
- **相似度**：功能相似（凭据供给面对过期条目的处置），且比 KeePassXC 修复前更彻底——本仓填充链（Autofill + CM 两通道）完全未检查过期
- **risk**：**yes**
- **advice**：候选装配入选前增加过期判定（KdbxTimes.expires/expiryTime 已解析且「编辑到期时间」已是真实产品入口 EntryEditExpiryEditor.kt）：过期条目默认不入选，或显式降档并在确认页/选择器标注「已过期」；同时在 CM 通道 CredentialResponseAssembler（app/passkey/ 包）同口径过滤。整改前按规则 6.1 附核实时间点补登或并入既有健康检查条目口径。

### 行 24｜d2e76058 Fix base domain matching
- **本质**：主机 endsWith 判定未校验注册域相等，任意同后缀主机即误配；补基域相等条件
- **触发**：站点主机以条目域名为后缀
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/autofill/AutofillCandidateRanker.kt + app/src/main/java/com/keepasskey/app/passkey/PublicSuffixList.kt（registrableDomain 严格匹配；§356 又补 SAME_BASE_DOMAIN 同站档）
- **相似度**：同一功能且已实现更强防护：PSL eTLD+1 严格匹配，兄弟子域/私有段（foo.github.io≠bar.github.io）/IP 字面量负例均由单测锁定
- **risk**：no
- **duplicateOf**：注释批次：keepass2android 主机名匹配未按可注册域（AutofillCandidateRanker.kt:272）

### 行 25｜e2c95f75 Fix subdomain matching
- **本质**：搜索只用站点主机与基域，submitUrl 未参与匹配且逐级剥域错位；改传完整 URL+submitUrl 匹配
- **触发**：提交域与站点域不同的表单
- **我们的对应位置**：无直接对应
- **相似度**：无——Android Autofill/CM 协议不暴露 submitUrl，匹配输入为框架下发的 webDomain 与调用方归属（AutofillOriginResolver 包名+签名二元组），无该触发面
- **risk**：no

### 行 26｜5b312889 Fix various bugs when returning credentials (#9136)
- **本质**：返回凭据多处错：httpAuth 用未初始化变量、空结果路径漏判、确认记忆分支错位
- **触发**：扩展请求返回凭据列表
- **我们的对应位置**：无直接对应
- **相似度**：无——触发面是浏览器扩展凭据返回协议（httpAuth/确认记忆），本仓无该通道；空候选面已由 PD-51 的「无匹配→就地新建/不呈现」处置覆盖
- **risk**：no

### 行 27｜5883f49f Passkeys: Fix RP ID validation
- **本质**：rp.id 未设时本应默认取 origin 有效域，此前直接报 RPID_MISMATCH 拒绝合法请求；按 WebAuthn 规范补默认
- **触发**：通行密钥请求未带 rp.id
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/passkey/CredentialCreateEntries.kt:48-49（rpId 空白时取 DomainMatcher.extractDomain(callingOrigin) 后再过 isRpIdTrustedForCreation）
- **相似度**：同一功能且已按 WebAuthn 规范实现：rp.id 缺省回退 origin 有效域，且仍受可注册后缀约束
- **risk**：no

### 行 28｜5cb6ad63 Passkeys: Fix ordering of clientDataJSON
- **本质**：clientDataJSON 错按 JSON 对象校验且键序不符 W3C 序列化顺序；改按字符串校验并按规范序构建
- **触发**：生成通行密钥 clientDataJSON
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/passkey/PasskeyRegistrationPayload.kt:122-134；app/src/main/java/com/keepasskey/app/passkey/PasskeyAssertionPayload.kt:64-68
- **相似度**：同一功能且已是修复后形态：按 W3C 序列化顺序（type/challenge/origin/androidPackageName/crossOrigin）以 WebAuthnJsonWriter 构建，SHA-256 对自建字节同源计算，特权调用方自带摘要时直接对其签名
- **risk**：no

### 行 29｜d9be1b06 Passkeys: Fix default BE and BS flag value (#13122)
- **本质**：BE/BS 标志按 "true" 写入但协议期望 "1"，读写解析不一致致标志错；统一写 "1" 并兼容两种读法
- **触发**：读写通行密钥条目 BE/BS 标志
- **我们的对应位置**：core/src/main/java/com/keepasskey/core/model/PasskeyData.kt:382（flagToFieldValue 恒写 "1"/"0"）、:390（读侧兼容 "1"/"true"/"yes"）
- **相似度**：同一功能且已是修复后语义（写入口径与 PD-09 的 `1`/`0` 口径一致，读侧兼容多形态）
- **risk**：no
- **duplicateOf**：注释批次：KeePassXC BE/BS 标志翻转（PasskeyData.kt:112）

### 行 30｜ad8a00d5 Passkeys: Fix incorrect username fill
- **本质**：条目含通行密钥时 login 字段被 Passkey 用户名无条件覆盖，普通口令填充返回错误用户名；删该分支
- **触发**：带 passkey 的条目作口令填充
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/autofill/AutofillDatasetBuilders.kt:329-334
- **相似度**：同面但无该分支：口令填充用户名恒取 entry.userName（先经 {REF} 解析），KPEX_PASSKEY_USERNAME 不参与口令通道取值
- **risk**：no

### 行 31｜6f114226 Prevent SSH Agent from using entries in the recycle bin
- **本质**：已删入回收站的密钥条目仍被 SSH Agent 选用；补回收站判定守卫拒绝
- **触发**：凭据类条目被删入回收站后被自动选用
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/passkey/KeePasskeyCredentialProviderService.kt、app/src/main/java/com/keepasskey/app/autofill/AutofillDatasetBuilders.kt、AutofillPickerViewModel.kt、AutofillUnlockActivity.kt（ISSUE-P2-341「回收站条目不出候选」守卫）
- **相似度**：同类守卫且已实现：五处候选装配点统一走 getUsableKdbxEntries 可用条目读口，回收站子树条目一律排除
- **risk**：no

### 行 32｜30e1fa5d Fix use-after-free when a database tab close is re-entered (#13598)
- **本质**：保存等待 worker 的嵌套事件循环中重入关 tab，Qt close 短路致守卫不跑，deleteLater 在嵌套循环析构仍在用对象；重入直接拒绝
- **触发**：关 tab 时嵌套循环再次触发关闭
- **我们的对应位置**：无直接对应
- **相似度**：无——Qt deleteLater/嵌套事件循环/手动内存管理不存在于 Kotlin+Compose 模型，导航关闭由状态机驱动且擦除按存活侧身份集合判定
- **risk**：no

### 行 33｜6481eccc Fix crash on screen lock or computer sleep
- **本质**：屏幕锁/休眠竞态下清库数据把关键密钥组件置 nullptr，他线程仍在用即崩；禁置空+加断言守卫
- **触发**：锁屏/休眠与解锁操作并发
- **我们的对应位置**：无直接对应（database/src/main/java/com/keepasskey/database/file/KdbxDatabase.kt 的 clearSensitiveData / clearBinaryPool）
- **相似度**：同类时序面但结构性规避：模型为不可变 data class + StateFlow 单写者，无「密钥组件置 nullptr 被他线程解引用」的空指针面；擦除经敏感缓冲所有权契约与存活侧身份集合判定（已知工程限界 §1.6）
- **risk**：no

### 行 34｜e180980b Fix potential deadlock in UI when saving
- **本质**：保存开始瞬间 file watcher 报外部变更，锁时序错致 UI 死锁；调整保存锁位置并禁保存中重载
- **触发**：保存时外部改动触发重载
- **我们的对应位置**：无直接对应（app/src/main/java/com/keepasskey/app/security/AutoLockSessionGuard.kt:35,105,117-119）
- **相似度**：无文件监听器（P2-378 AC③ 明令不引入常驻 FileObserver），无「保存中被重载」竞态；保存期长任务挂锁 + 挂锁期锁定请求暂存补执行（ISSUE-P3-366）已覆盖保存×锁定的并发序
- **risk**：no

### 行 35｜0ad75ccb Fix missing include in alloc preventing some secure deallocations
- **本质**：Alloc.cpp 缺 QtGlobal 头致 sized-delete 安全释放分支未编入，部分秘密内存不清零释放
- **触发**：平台走安全释放分支
- **我们的对应位置**：无直接对应
- **相似度**：无——无 C/C++ Alloc 面；Rust 内核秘密缓冲由 Zeroizing RAII 承担，擦除由所有权系统在编译期保证，不存在「头文件缺失致分支未编入」的静默失效形态
- **risk**：no

### 行 36｜93f0fef1 Improve and secure attachment handling (fixes #2400)
- **本质**：附件临时文件改全随机名+严格权限+关库时随机覆写删除，防元数据泄露与残留
- **触发**：外部程序打开库内附件
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/data/binary/FileBinaryStore.kt:21,100（UUID 随机键）；sync/src/main/java/com/keepasskey/sync/engine/SyncCacheFiles.kt:84-97（SHA-256 派生命名 + 0600/0700 置位）
- **相似度**：同类防护已就位：附件落盘文件名由随机 UUID 键派生（不含附件名等元数据）、POSIX 严格权限、锁库经 FileBinaryStore.onSessionLocked 清理；本仓亦无「外部程序打开库内附件」的常驻临时文件通道（导出走 SAF 用户显式动作）
- **risk**：no
- **advice**：「关库时随机覆写删除」未做——unlink-only 已按已知工程限界 §1.5 登记；>1MiB 附件明文暂存私有目录属 PD-36 已裁决取舍；均无需另立整改。

### 行 37｜da8874de Improve Entry placeholder resolution (#10846)
- **本质**：占位符解析对非占位符花括号文本空转到递归上限并报"最大深度"；提前判非占位符即止
- **触发**：字段含花括号非占位符文本
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/fieldref/FieldReferenceEngine.kt:110-112,167-169
- **相似度**：同面但无该触发：本仓占位符仅 {REF} 一族，非占位符花括号文本经 containsReference 快速短路原样返回，无递归空转与「最大深度」误报路径
- **risk**：no

### 行 38｜e888fec0 Fix password strength evaluation for referenced passwords (#13479)
- **本质**：密码为 {REF} 引用时只解析单级占位符，强度评估取到引用原文而非真实口令；改多级解析
- **触发**：密码字段引用他条目密码
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/audit/HealthCheckEngine.kt:111-128（强度评估直接读 entry.password 字面，无 {REF} 展开）
- **相似度**：功能相似且触发面成立：填充通道已按需多级展开引用（AutofillDatasetBuilders.kt:330-332 经 FieldReferenceEngine），健康检查未展开——口令为 {REF:P@T:…} 的条目会被按引用原文评强度，得分失真
- **risk**：**yes**
- **advice**：健康检查在评估前先经 FieldReferenceEngine.resolve（引擎同在 database 模块，无模块依赖问题）按 PASSWORD 消费面展开被引用口令后再评强度（引擎自带多级递归展开 + 深度/次数/产出三闸门，恰好覆盖 KeePassXC 该修复的「多级解析」要求）；未命中/超限引用按既有保守语义回退原文并留痕，且不把掩码输出误当真实口令评强。复核补充两点落实注意：①analyzeEntries(entries) 现签名不含 rootGroup（HealthCheckEngine.kt:62），而 resolve 需要 root 建索引（FieldReferenceEngine.kt:137），落地须把根组传入引擎或调整签名；②同根缺口也在复用检测——buildReuseIndex（HealthCheckEngine.kt:55-73）同样按引用原文取哈希，两条目经 {REF} 引用同一口令时复用检测失效，宜同批覆盖。

### 行 39｜474da9b8 Fix identifying nested reference placeholders (#13688)
- **本质**：深层嵌套引用占位符被判 Unknown 而漏处理；引用正则先行判别，命中即按占位符走
- **触发**：字段含嵌套 {REF} 引用
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/fieldref/FieldReferenceEngine.kt:16-17,87-97
- **相似度**：同一功能且语义已覆盖：SearchText 禁花括号是 KeePass 语义的刻意实现（KDoc 声明），嵌套引用经「内层先解、结果重扫」的多级递归展开处理（深度/展开次数/产出字符三闸门），无「判 Unknown 漏处理」面
- **risk**：no

### 行 40｜634a5b34 Improve inactivity timer
- **本质**：闲置计时器被高频事件每秒重置数百次且无下限，自动锁定计时被冲掉；加节流与 10s 最小超时
- **触发**：高频用户事件刷新闲置计时
- **我们的对应位置**：无直接对应（全仓无闲置计时器/交互刷新机制，rg -i "onUserInteraction|inactivity|idle" 生产代码零命中）
- **相似度**：功能缺口本身已在办（ISSUE-P2-379 前台闲置自动锁定缺失），本条的触发面（高频事件重置计时）在本仓当前不存在
- **risk**：no
- **advice**：P2-379 已跟踪；落地闲置计时器时吸收本条教训：交互事件重置计时须节流、超时档设最小下限（KeePassXC 取 10s），并与 P2-379 AC③ 的时钟回拨 fail-closed 纪律同批落实。
- **duplicateOf**：注释批次：KeePassDX recordTime 高频重置锁定计时（AutoLockManager.kt:33）

## 三、复核修正记录

1. **e367c6df（Fix merging browser keys）**：risk=yes 维持、触发面与 ourLocation 核实无误（KdbxEntryMerger.kt:157-170 isModified 不比较 customData；:232-240 冲突合并 copy 清单不含 customData，双改路径远端 customData 恒丢），但 advice 中「本地仅改 customData（如收藏标记）而远端改字段时走『远端整条胜出』，本地 customData 改动同样丢失」一句不成立：VaultEntryWriteCoordinator.kt:256 的 setEntryFavorite 写收藏时同步 lastModificationTime=now()，isModified 经 times.lastModificationTime（KdbxEntryMerger.kt:168）即判本地已改，双改走 mergeConflictedEntry 冲突路径——该路径以 local 为底版且 customData 未被触碰，本地收藏改动保留。真实丢失路径只有一条：远端改了 customData（如浏览器键）且本地改了任意字段时远端侧恒丢。已改写 advice（行 11 已收录改写后版本）。
2. **e888fec0（Fix password strength evaluation for referenced passwords）**：risk=yes 维持（HealthCheckEngine.kt:111-128 确直读 entry.password 字面无 {REF} 展开，实测 rg 确认填充通道 AutofillDatasetBuilders.kt:329-332 经 resolveFieldReferences 展开），advice 补两处落实注意：①HealthCheckEngine.analyzeEntries(entries) 现签名不含 rootGroup（HealthCheckEngine.kt:62），FieldReferenceEngine.resolve 需要 root 建索引（FieldReferenceEngine.kt:137），落地须把根组传入引擎或改签名；②同根缺口也在复用检测——buildReuseIndex（HealthCheckEngine.kt:55-73）同样按引用原文取哈希，两条目经 {REF} 引用同一口令时复用检测失效，宜同批覆盖。（行 38 已收录补充后版本。）
3. **94ace985（Preserve Secret Service exposed group setting on merge）**：核实通过不改判定，补证据留痕：组级合并词汇表 MERGED_GROUP_FIELDS/isGroupModified/mergeGroupsBothModified（KdbxGroupMerger.kt:225-238,245-269）确不含 customData，且组级 customData XML 读写双向存在（KdbxXmlGroupReader.kt:98,125,185,219；KdbxXmlGroupSerializer.kt:65-67）——第三方组级标记可解析入库、经合并即丢，risk=yes 坐实。行内 ourLocation 写的 ChildDatabaseSessionManager.kt:29-30 实际路径为 app/src/main/java/com/keepasskey/app/data/childdb/ChildDatabaseSessionManager.kt:28-30（行内少一级 childdb 目录），KDoc 内容与所述一致，仅路径补正（行 13 已收录补正后路径）。
