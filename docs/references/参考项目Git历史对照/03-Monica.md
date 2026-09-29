# 参考项目 Git 历史对照 · Monica 分册

**范围**：Monica（UI 补充参考）全量提交历史中修复/防护类提交的挖掘与主项目对照。
**统计**：挖掘记录 **40** 条，对照行 **40** 条——yes 1 / unclear 0 / excluded 0 / no 39。
**复核状态**：**confirmed=true**（经独立复核，修正记录见「三」节；复核范围声明：仅 risk=yes 行全查 + 两条承重 no 行旁证，其余 37 行 risk=no 维持原判、未逐行重查）。
**日期**：2026-09-29。总表见 [00-对照总表.md](00-对照总表.md)。

## 一、挖掘记录（40 条）

| # | 提交 | subject | 本质（essence） | 触发（trigger） | 位置（area） | 领域 | 类型 | 置信度 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 226354b5（2026-09-28） | fix(storage): preserve absent OTP in MDBX exports | MDBX 导出快照对空 OTP 也走 encryptData("") 生成伪密文，恢复端出现假验证器密钥；改为空白存空串 | MDBX 导出含无 TOTP 的条目 | transfer/DatabaseExportSnapshot.kt | 文件IO | bugfix | confirmed |
| 2 | 57fb60f9（2026-09-28） | fix(mdbx): preserve literal JSON keys across native roundtrips | 精确数值与字面扩展键经 FFI、写库、合并、同步后失真；Rust 引擎补丁全链路保真并刷新三个运行时+CLI 契约夹具 | 含精确 JSON 值的条目跨 FFI/合并/同步 | mdbx-engine/patches + jniLibs 三 ABI + docs/storage | 其他 | bugfix | confirmed |
| 3 | fc7f7bb4（2026-09-03） | fix(rust): enable Argon2 allocation support | rust-crypto 的 argon2 crate 未开 alloc feature 致 KDF 无法分配内存；补 features=["alloc"] | Rust Argon2id KDF 内核构建/运行 | rust-crypto/Cargo.toml | 加密KDF | bugfix | confirmed |
| 4 | e23e9b2b（2026-05-17） | Fix Bitwarden Argon2 OOM with native KDF | Bitwarden 登录遇高内存 Argon2id 参数在 JVM 侧 OOM；改走原生 KDF，内存不可分配时给出明确降级提示 | 服务端 KDF 内存参数超出设备可用内存 | bitwarden/crypto/BitwardenCrypto.kt 等 | 加密KDF | bugfix | confirmed |
| 5 | 51cb037f（2026-08-17） | fix: prevent unreadable secrets in KDBX exports | KDBX 导出把 Monica 设备绑定密文原样写入致他端不可读；新增导出策略——解密失败即取消并指明条目 | 导出时敏感字段仍是设备绑定密文 | KeePassPortableSecretExportPolicy.kt + KeePassKdbxViewModel.kt | kdbx格式 | bugfix | confirmed |
| 6 | d53e7389（2026-08-17） | fix: make backups portable across installations | ZIP/WebDAV 备份混入当前安装绑定的密文换机不可读；PortableSecretExportPolicy 强制导出真实值，失败即取消 | 备份恢复到另一安装/设备 | PortableSecretExportPolicy.kt、BackupRestoreApplier.kt 等 | 文件IO | bugfix | confirmed |
| 7 | afaf23d0（2026-08-10） | fix(android): harden external vault imports | 外部导入丢失 Steam maFile 识别与附件元数据、外部 KDBX 文档显示名解析错误；修复并补回归覆盖 | 经 SAF 导入外部 KDBX/Bitwarden/Steam 文件 | KeePass/Bitwarden/Steam 导入路径 | kdbx格式 | bugfix | confirmed |
| 8 | 52afa9f9（2026-08-22） | fix(webdav): allow confirmed hostname mismatch | 自定义信任管理器路径下主机名校验被静默全局放行；改为默认保留系统校验，仅按主机+证书指纹显式确认 | 证书与主机名不符的 WebDAV 服务器 | webdav/WebDavGateway.kt | 同步 | guard | confirmed |
| 9 | 5c210f09（2026-08-22） | feat(webdav): allow explicit certificate trust | 自签证书 WebDAV 此前只能全局关校验；新增按主机指纹的信任库与用户确认对话框，默认校验不放松 | 自建 WebDAV 使用自签/私有 CA 证书 | WebDavCertificateTrustStore.kt 等 14 文件 | 同步 | guard | confirmed |
| 10 | 38478f25（2026-06-04） | Fix WebDAV backup content scope | WebDAV 手动/自动备份默认只收 Monica 本地条目，KeePass/Bitwarden 离线条目漏备；显式传 ALL_OFFLINE 并加守卫测试 | 备份内容含 KDBX/Bitwarden 条目 | WebDavHelper.kt、AutoBackupWorker.kt | 同步 | bugfix | confirmed |
| 11 | 9b4c3a9f（2026-01-15） | fix: 修复WebDAV备份缺少第三方登录(SSO)字段和常用账号信息 | 备份模型缺 SSO 登录字段与常用账号数据；补字段与 ID 映射，SSO 引用无效时自动清空防悬空引用 | 恢复含 SSO 绑定的备份 | WebDavHelper 备份模型、CommonAccountBackupEntry | 同步 | bugfix | confirmed |
| 12 | a2f30b5b（2026-01-12） | feat: add image compression to WebDAV backup and fix OOM/concurrency issues | WebDAV 备份可被并发重复触发；WebDavHelper 加 AtomicBoolean 互斥，上传流内存管理缓解 OOM | 备份进行中再次触发备份 | WebDavHelper.kt、ImageCompressor | 同步 | guard | confirmed |
| 13 | ed3e6cda（2026-06-04） | Fix authenticator privacy and backup coverage | 验证器条目未纳入备份内容范围且 Monica 导出混入 KeePass 绑定元数据；新增 BackupContentScope 范围与脱敏策略 | 备份/导出验证器与绑定条目 | BackupContentPolicy.kt、OneDriveBackupHelper 等 | 同步 | bugfix | confirmed |
| 14 | ca23667f（2026-06-25） | Fix remote KeePass sync compatibility | 远端 KDBX 同步新增 base/working/remote 三方 SHA-256 比对：双端有改走合并并保留冲突副本，避免直接覆盖 | 本地与远端 .kdbx 同时变更 | utils/KeePassKdbxService.kt 等 | 合并 | bugfix | confirmed |
| 15 | 1a7d06fc（2026-09-26） | fix(android): preserve native vault content and backup round trips | 原生 MDBX/KDBX 条目扩展内容字段在导入与备份回环中被 Room 投影截断丢失；补 MdbxPasswordContentFields 回填+统一去重判定 | 原生 vault 条目导入/备份回环 | MdbxPasswordContentFields.kt、ImportDestinationWriter.kt 等 | 其他 | bugfix | confirmed |
| 16 | d0a1bf29（2026-02-01） | fix(passkey): 修复通行密钥流程——修正 PendingIntent 标志、Activity 配置与返回异常处理 | Credential Provider 流程 PendingIntent 标志与返回处理错误，登录即报 Authentication failed 且无法返回调用方；逐一修正 | 系统凭据管理器调起注册/认证 | passkey/PasskeyAuthActivity、PasskeyCreateActivity | 凭据passkey | bugfix | confirmed |
| 17 | 30a41dd9（2026-06-09） | Fix Android passkey discoverability compatibility | publicKey 包装与字符串型 requireResidentKey 创建请求解析失败致可发现性异常；回 credProps.rk 并保旧 allowCredentials 凭据可用 | RP 请求 discoverable passkey | MonicaCredentialProviderService 等 | 凭据passkey | bugfix | confirmed |
| 18 | 2845bf3a（2026-05-29） | Android: fix all TotpDataResolver recursion paths causing StackOverflowError | TOTP secret 为不可解析 URI 时 normalize→reparse→fromAuthenticatorKey 无限递归爆栈；三层修复含深度限制正确传播 | TOTP 记录含 otpauth:// 无 secret 的坏 URI | TotpDataResolver | 其他 | bugfix | confirmed |
| 19 | 602f6f03（2026-01-05） | fix: restore TOTP linkage via ID mapping and prevent duplicate TOTP entries | 恢复时跳过重复条目却不建 ID 映射，TOTP 关联断链且重复建条目；建 originalId→newId 映射后回挂 | 恢复含 TOTP 关联的备份 | DataExportImportViewModel.kt | 文件IO | bugfix | confirmed |
| 20 | 77d84394（2026-01-06） | Fix: TOTP binding restoration in WebDAV backup and CSV import | WebDAV 备份恢复与 CSV 导入后 TOTP 与条目的绑定丢失/错乱；重建关联并新增 TimeSync 服务器时间偏移校准 | 恢复备份或导入 CSV 后使用验证器 | WebDavBackupScreen、util/TimeSync.kt、TotpGenerator | 同步 | bugfix | confirmed |
| 21 | 537b4fa3（2026-06-21） | fix: protect passkey keys and sensitive logs | 通行密钥 PKCS#8 私钥原文存 Room 列；迁出到受保护存储仅留引用，并清理日志中的敏感明文 | 通行密钥创建/认证与日志输出 | PasskeyPrivateKeyStore.kt（新增）等 | 敏感数据 | guard | confirmed |
| 22 | 67bc6d6f（2026-06-21） | fix: protect sensitive local data | 密码生成历史/常用账号等明文落 DataStore；改 AES-GCM 加密并迁移旧明文，诊断日志同步脱敏 | 生成历史/常用账号写盘 | PasswordHistoryManager.kt、CommonAccountPreferences.kt | 敏感数据 | guard | confirmed |
| 23 | b7f14872（2026-06-21） | fix: reduce residual sensitive metadata exposure | 日志输出 credentialId、条目标题、URI 绑定、附件路径等敏感元数据；全部改为布尔/去值输出 | 通行密钥与 Bitwarden 同步打日志 | PasskeyAuth/Create、CipherSync/UploadProcessor 等 | 敏感数据 | guard | confirmed |
| 24 | 126d9132（2026-08-26） | fix(export): prompt backup password dialog when exporting zip with passkeys | 导出含通行密钥的 ZIP 未强制加密，私钥可明文落盘；检测到 passkey 将导出时强制弹备份密码对话框 | ZIP 导出包含通行密钥 | ui/screens/ExportDataScreen.kt | 敏感数据 | guard | confirmed |
| 25 | 22a4877d（2026-06-03） | fix: verify downloaded update apk | 应用内更新 APK 安装前无校验；校验包名与签名证书摘要与已装 Monica 一致才允许安装 | 应用内下载更新 APK 后 | utils/UpdateChecker.kt | 其他 | guard | confirmed |
| 26 | d0ddca05（2026-01-31） | fix(monica-android): 修复 MainActivity 导航竞态、生命周期闭包捕获与 runBlocking 超时；替换强制解包为安全空检查；修复 KeePass WebDAV 中 sardine 非空断言 | 导航竞态、生命周期闭包捕获、runBlocking 超时与多处强制解包崩溃；逐一替换为安全判空并修正 | 启动导航与 KeePass WebDAV 操作 | MainActivity、SecurityQuestionsSetupScreen 等 | 并发 | bugfix | confirmed |
| 27 | 0990cd4e（2026-09-12） | Initialize generator state before restoring cached preferences | IO 协程与构造器竞态向未初始化 StateFlow 写入；全部 holder 初始化后再启动恢复并加 200 次构造回归 | release 启动恢复缓存偏好 | 生成器 ViewModel | 并发 | bugfix | confirmed |
| 28 | 3fa80d9d（2026-02-03） | fix(db): 修复 bitwarden_pending_operations 索引不匹配导致的崩溃 | 迁移脚本建了 item_type 索引但 Room Entity 未声明，运行时校验崩溃；补 Entity indices 声明 | 升级后访问待同步操作表 | BitwardenPendingOperation Entity | 其他 | bugfix | confirmed |
| 29 | d18e14d9（2026-06-25） | Fix local ZIP export writing empty files | 部分 DocumentsProvider 下 openOutputStream("wt") 返回空/截断流致导出 ZIP 全空；依次回退 rwt/w 并失败即报错 | 本地导出 ZIP 到特定 SAF provider | DataExportImportViewModel.kt | 文件IO | bugfix | confirmed |
| 30 | 90995f0b（2026-09-16） | fix(android): preserve attachment errors and stream ZIP exports | 附件密钥不可读被压成通用错误且明文全量缓冲(64MiB 上限)；保留根因、流式写 ZIP、校验大小与 SHA-256，失败使归档无效 | 导出大附件或损坏附件 | 附件导出/ZIP 流水线 | 文件IO | guard | confirmed |
| 31 | 8a6beeb0（2026-07-26） | fix(autofill): forceAutofillOff 保留同组非 OFF 候选，修复电影猎手有密码框却不弹 | importantForAutofill=NO 的 OFF:HIGHEST 候选致整组被跳过、同组有效字段连带丢弃；改为仅全组 OFF 才跳过 | 同一 autofillId 组混有 OFF 与非 OFF 候选 | autofill_ng 解析分组逻辑 | Autofill | bugfix | confirmed |
| 32 | f8f987c8（2026-07-26） | fix(autofill): 收紧 TYPE_TEXT_VARIATION_NORMAL fallback，修复 QQ 搜索框误弹 | 纯 text 输入框被无条件 fallback 为 USERNAME:LOWEST 致搜索框误弹；对齐 Bitwarden 仅按 id/类型术语定 MEDIUM 精度 | QQ 等纯 text 搜索框页面 | parseNodeByAndroidInput | Autofill | bugfix | confirmed |
| 33 | bff24388（2026-07-26） | fix(autofill): 排除搜索框误弹密码条目（Edge/GitHub 等网页搜索框被当作登录字段） | 页面有密码框即整页按登录上下文处理，搜索框被当作可填充 USERNAME；新增 isSearchField() 在解析层清空其候选 | Edge/GitHub 等网页搜索框聚焦 | autofill 解析器 | Autofill | bugfix | confirmed |
| 34 | 7f7685e5（2026-07-25） | fix(autofill): 解锁会话跨进程共享, 消除每次填充强制二次解锁 | 解锁状态仅存主进程内存，Autofill 独立服务进程读不到致「永不过期」仍每次弹解锁；持久化到 MODE_MULTI_PROCESS 并按密钥材料可读判锁态 | 独立进程 Autofill 服务每次填充 | SessionManager、FilledDataBuilderNg | Autofill | bugfix | confirmed |
| 35 | 8fcaf0dd（2026-07-22） | fix(autofill): WebView 填充改用 ACTION_PASTE 优先 | ACTION_SET_TEXT 对 WebView 输入不触发 JS input 事件，React/Vue 读不到新值；改 PASTE 优先、SET_TEXT 回退，对齐 Bitwarden/KeePassDX | React/Vue 等 WebView 登录表单 | autofill 无障碍填充 | Autofill | bugfix | confirmed |
| 36 | c854ef2a（2026-09-17） | Invalidate cached autofill prompts when fields are blocked | 字段被拉黑后旧填充响应/认证结果仍生效；返回 ignored-ids 响应并带 client state，使系统重新查询并失效旧结果 | 用户拉黑字段签名后再次填充 | autofill_ng/AutofillBlockedRequest.kt（新增）等 | Autofill | bugfix | confirmed |
| 37 | 759ee008（2026-09-17） | fix(autofill): stabilize nonstandard and dynamic login forms | 动态重建的 WebView 输入与缓存填充目标跨表单错配；字段与缓存限定活动表单，顺序键盘填充前校验焦点 | 动态登录表单/重创建的 WebView 输入框 | autofill_ng | Autofill | bugfix | confirmed |
| 38 | 60523e4b（2026-06-21） | fix: prevent autofill placeholder from filling | "PLACEHOLDER" 哨兵串被放进 AutofillValue 可能被真实填入字段；删除哨兵路径并加源码级守卫断言 | 需认证数据集的填充回环 | FillResponseBuilderNg、FilledDataBuilderNg | Autofill | bugfix | confirmed |
| 39 | b5ccc678（2026-06-21） | fix: avoid autofill auth result reuse | autofill 相关 Activity 设 singleTop 复用旧实例 extras，认证结果跨请求被复用；移除 launchMode 保每次新实例 | 短时间连续两次填充请求 | AndroidManifest.xml | Autofill | bugfix | confirmed |
| 40 | e7ea0a4d（2026-09-13） | fix(android): split oversized TotpListContent to avoid ART VerifyError | 278 寄存器方法被 d8 以 move/16 复用参数寄存器，ART 校验拒绝类加载，打开验证器页即崩；拆分至 236 寄存器并加装载冒烟测试 | debug 构建打开验证器 tab | ui/totp/TotpListContent.kt 拆分 | 其他 | bugfix | confirmed |

## 二、对照行全文（40 条）

### 行 1｜226354b5 fix(storage): preserve absent OTP in MDBX exports
- **本质**：导出快照对空 OTP 走 encryptData("") 生成伪密文，恢复端出现假验证器密钥；应空白存空
- **触发**：导出含无 TOTP 的条目
- **我们的对应位置**：无直接对应
- **相似度**：无同型存储层：主项目无 MDBX，导出面为 kdbx 原生格式（字段忠实序列化）与明文 XML/CSV（VaultExportCoordinator.kt / KdbxCsvExporter.kt，两者均无按条目伪造 OTP 值的路径，grep otp 零命中）
- **risk**：no

### 行 2｜57fb60f9 fix(mdbx): preserve literal JSON keys across native roundtrips
- **本质**：精确数值与字面扩展键经 FFI/写库/合并/同步后失真，需全链路保真
- **触发**：含精确 JSON 值的条目跨 FFI/合并/同步
- **我们的对应位置**：无直接对应
- **相似度**：无同类面：主项目条目模型是 KDBX XML 字段与 ProtectedString（逐字保真读写），无 MDBX 引擎、无 JSON 字段经原生 FFI 往返的链路
- **risk**：no

### 行 3｜fc7f7bb4 fix(rust): enable Argon2 allocation support
- **本质**：argon2 crate 未开 alloc feature 致 KDF 无法分配内存
- **触发**：Rust Argon2id KDF 内核构建/运行
- **我们的对应位置**：crypto/src/main/rust/Cargo.toml:34
- **相似度**：功能相同（同一 RustCrypto argon2 内核），主项目该依赖已显式声明 features=["alloc","kdf","parallel","zeroize"]，触发面不存在
- **risk**：no

### 行 4｜e23e9b2b Fix Bitwarden Argon2 OOM with native KDF
- **本质**：服务端 KDF 高内存参数在 JVM 侧 OOM；改走原生 KDF 并对不可分配给出明确提示
- **触发**：KDF 内存参数超出设备可用内存
- **我们的对应位置**：database/src/main/java/com/keepasskey/database/file/KdbxKdfParameterCodec.kt:96-97,224-243
- **相似度**：功能相似（同为大内存 KDF 参数面），但主项目派生生产路径全走 Rust 原生 derive_into（无 JVM 堆 Argon2 分配），且解析层 fail-closed：m > 4 GiB 绝对封顶拒绝、memory ≥ 8×p×1024 交叉下界校验，原生分配失败为类型化错误而非 JVM OOM
- **risk**：no
- **needInfo**：Monica 的「不可分配时明确降级提示」在主项目对应为打开失败报错，若要评估低内存设备打开合法大 m 库的提示语，需真机实测分配失败路径的实际文案

### 行 5｜51cb037f fix: prevent unreadable secrets in KDBX exports
- **本质**：导出把设备绑定密文原样写入致他端不可读；解密失败即取消并指明条目
- **触发**：导出时敏感字段仍是设备绑定密文
- **我们的对应位置**：无直接对应
- **相似度**：同类功能（库导出）但密文形态不同：主项目条目受保护字段的驻留加密是进程级 InMemoryCipher（永不出进程，见限界 §2.2），导出重新以库主密钥序列化 kdbx，库内不存在设备绑定密文；快速解锁封印凭据在应用私有目录、不进任何导出
- **risk**：no

### 行 6｜d53e7389 fix: make backups portable across installations
- **本质**：备份混入安装绑定密文换机不可读；强制导出真实值，失败即取消
- **触发**：备份恢复到另一安装/设备
- **我们的对应位置**：无直接对应
- **相似度**：无同类备份通道：主项目无应用数据整体备份特性，同步上传的是 .kdbx 文件本体（跨安装可移植）；设备绑定的 Keystore 封印/节流状态不在任何上传或导出物中
- **risk**：no

### 行 7｜afaf23d0 fix(android): harden external vault imports
- **本质**：外部导入丢失 Steam maFile 识别与附件元数据、外部 KDBX 显示名解析错误
- **触发**：经 SAF 导入外部 KDBX/Bitwarden/Steam 文件
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/data/importer/ImportContracts.kt:23-26
- **相似度**：功能相似（外部文件导入）但导入源集合不同：主项目仅 KEEPASS_XML/BITWARDEN_JSON/BROWSER_CSV/ONEPASSWORD_1PUX 四源，无 Steam maFile 亦无 kdbx 直接导入路径，Monica 的三个具体坑无落点；「.kdbx 并入」缺位已由 P3-384 跟踪，届时实现须回看本坑
- **risk**：no

### 行 8｜52afa9f9 fix(webdav): allow confirmed hostname mismatch
- **本质**：自定义信任管理器路径下主机名校验被静默全局放行；默认保留系统校验
- **触发**：证书与主机名不符的 WebDAV 服务器
- **我们的对应位置**：sync/src/main/java/com/keepasskey/sync/network/SyncHttpClientFactory.kt:18
- **相似度**：同类通道（WebDAV 客户端）但触发面不存在：主项目 HTTP 栈明确不注入任何自定义 TrustManager / CertificatePinner / HostnameVerifier（该 KDoc 即为口径），主机名校验恒走系统默认，无「静默全局放行」的代码路径
- **risk**：no

### 行 9｜5c210f09 feat(webdav): allow explicit certificate trust
- **本质**：自签证书 WebDAV 新增按主机指纹的用户确认信任库，默认校验不放松
- **触发**：自建 WebDAV 使用自签/私有 CA 证书
- **我们的对应位置**：无直接对应
- **相似度**：同为 WebDAV 通道但主项目连「放行面」都不存在：TLS-only、零证书固定、零信任旁路，自签服务器直接连接失败——属比 Monica 终态更严的取向（与 PD-02 端点默认收紧、不提供终端用户放开入口的裁决同向），非缺陷
- **risk**：no（decisionRef：PD-02，相邻口径：不给终端用户放开同步端点校验的入口）

### 行 10｜38478f25 Fix WebDAV backup content scope
- **本质**：备份默认只收部分来源条目致离线条目漏备；显式传 ALL_OFFLINE 并加守卫
- **触发**：备份内容含多来源条目
- **我们的对应位置**：无直接对应
- **相似度**：无同型面：主项目是单库架构，同步/上传对象为整个 .kdbx 文件，不存在「按来源筛选备份内容」的范围参数，漏备面结构上不存在
- **risk**：no

### 行 11｜9b4c3a9f fix: 修复WebDAV备份缺少第三方登录(SSO)字段和常用账号信息
- **本质**：备份模型缺 SSO 登录字段与常用账号数据，SSO 引用无效时自动清空防悬空引用
- **触发**：恢复含 SSO 绑定的备份
- **我们的对应位置**：无直接对应
- **相似度**：无对应功能域：主项目条目模型无 SSO 登录/常用账号概念，WebDAV 同步不涉及字段级备份模型
- **risk**：no

### 行 12｜a2f30b5b feat: add image compression to WebDAV backup and fix OOM/concurrency issues
- **本质**：备份可被并发重复触发；加 AtomicBoolean 互斥
- **触发**：备份进行中再次触发备份
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/sync/SyncCoordinator.kt:261-273
- **相似度**：同类面（同步/上传的并发重入）已有防护：主项目全部同步决策-提交过程串行化于 SyncSessionState.mutex（手动下拉/解锁后自动/周期任务等多触发面汇入同一协调器），等价于 Monica 的互斥闸门
- **risk**：no

### 行 13｜ed3e6cda Fix authenticator privacy and backup coverage
- **本质**：验证器条目未纳入备份范围且导出混入绑定元数据；新增 BackupContentScope 与脱敏策略
- **触发**：备份/导出验证器与绑定条目
- **我们的对应位置**：无直接对应
- **相似度**：无同型面：主项目 TOTP 种子是条目内字段（随 .kdbx 整体同步，天然全覆盖），无独立验证器存储与「备份内容范围」概念
- **risk**：no

### 行 14｜ca23667f Fix remote KeePass sync compatibility
- **本质**：远端 KDBX 同步新增 base/working/remote 三方 SHA-256 比对，双端有改走合并并保留冲突副本，避免直接覆盖
- **触发**：本地与远端 .kdbx 同时变更
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/sync/SyncContentChangeDetector.kt
- **相似度**：功能相同且防护更强：主项目既有基线快照比对（PD-21 的 <hash>.basecache/<hash>.cache 双快照）、KdbxMerger 三方合并（UUID 判定 + 冲突待决副本 SyncConflictController）、SyncRollbackGuard 已见摘要链防回滚（限界 §5），远端变更不会静默覆盖本地
- **risk**：no

### 行 15｜1a7d06fc fix(android): preserve native vault content and backup round trips
- **本质**：原生条目扩展内容字段在导入/备份回环中被 Room 投影截断丢失
- **触发**：原生 vault 条目导入/备份回环
- **我们的对应位置**：无直接对应
- **相似度**：无同型面：主项目持久化是 .kdbx 文件而非 Room，条目字段经 KdbxEntry 模型直读直写，无投影截断层
- **risk**：no

### 行 16｜d0a1bf29 fix(passkey): 修复通行密钥流程
- **本质**：Credential Provider 流程 PendingIntent 标志与返回处理错误，登录即报 Authentication failed
- **触发**：系统凭据管理器调起注册/认证
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/passkey/CredentialPendingIntents.kt:54
- **相似度**：功能相同、防护已在位：认证 PendingIntent 单点收敛为 FLAG_MUTABLE|FLAG_UPDATE_CURRENT（官方契约，Robolectric 守卫断言防回归），认证结果经 EXTRA_AUTHENTICATION_RESULT 携带真实 Dataset 回传（AutofillAuthResultDelivery KDoc 明载官方两条强制项），注册/断言链已真机对拍（PD-32/§263/§269）
- **risk**：no

### 行 17｜30a41dd9 Fix Android passkey discoverability compatibility
- **本质**：publicKey 包装与字符串型 requireResidentKey 解析失败致可发现性异常；回 credProps.rk 并保旧 allowCredentials 凭据可用
- **触发**：RP 请求 discoverable passkey
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/passkey/WebAuthnJsonKeys.kt:38-44
- **相似度**：同类面且两项均已在位：① credProps.rk 已按请求携带 extensions.credProps 时如实回传 {"rk":true}（PasskeyRegistrationPayload.kt:198-211，ISSUE-P2-265）；② residentKey/requireResidentKey 刻意不读取——KDoc 明载本仓凭据断言按 rpId 全库匹配即取出、确实可发现（对齐 KeePassDX 恒报 true），不存在字符串型解析失败面；allowCredentials 收敛与空集不收敛口径见 CredentialCandidateMatcher.kt:20-30
- **risk**：no

### 行 18｜2845bf3a fix all TotpDataResolver recursion paths causing StackOverflowError
- **本质**：TOTP secret 为不可解析 URI 时 normalize→reparse 无限递归爆栈
- **触发**：TOTP 记录含 otpauth:// 无 secret 的坏 URI
- **我们的对应位置**：core/src/main/java/com/keepasskey/core/otp/TotpKeyUriParser.kt:147-158
- **相似度**：同类解析面但结构不同：主项目解析为单趟纯函数——normalizeBase32 后不合法即显式清零并返回 null（fail-closed），无「规范化后重新解析自身」的递归路径；OtpEngine 与解析器职责分离（ISSUE-P3-90）
- **risk**：no

### 行 19｜602f6f03 fix: restore TOTP linkage via ID mapping
- **本质**：恢复时跳过重复条目却不建 ID 映射，TOTP 关联断链且重复建条目
- **触发**：恢复含 TOTP 关联的备份
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/data/importer/ImportPersistRun.kt:40-88
- **相似度**：功能相似（导入查重）但模型不同：主项目 TOTP 种子是 ImportedEntry.totpSecret 内联于同一条目（ImportContracts.kt:13），不存在「TOTP 与条目分离后回挂」的关联结构；查重跳过时整条（含 TOTP）保持原子，无断链面
- **risk**：no

### 行 20｜77d84394 Fix: TOTP binding restoration in WebDAV backup and CSV import
- **本质**：备份恢复与 CSV 导入后 TOTP 与条目的绑定丢失/错乱；重建关联并加 TimeSync 校准
- **触发**：恢复备份或导入 CSV 后使用验证器
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/data/importer/BrowserCsvImporter.kt:126,142,158
- **相似度**：同上：CSV 导入按 TOTP_HEADERS（totp/login_totp/otp）列把种子读进同一条目的 totpSecret（CharArray 承载），绑定天然不分离；主项目无「TOTP 绑定需重建」的中间态
- **risk**：no

### 行 21｜537b4fa3 fix: protect passkey keys and sensitive logs
- **本质**：通行密钥 PKCS#8 私钥原文存 Room 列；迁出受保护存储并清理日志敏感明文
- **触发**：通行密钥创建/认证与日志输出
- **我们的对应位置**：docs/architecture/产品裁决登记.md（PD-09）
- **相似度**：同类面且已有防护：主项目私钥即 KPEX_PASSKEY_PRIVATE_KEY_PEM 受保护字段（kdbx 加密驻留，PD-09 口径），从不落明文列；抽检 PasskeyAssertionActivity.kt:86-271 与 KeePasskeyAutofillService.kt:103-368 全部日志为布尔/枚举/固定文案，无 credentialId/标题/URI 明文（ISSUE-P1-10 纪律）
- **risk**：no

### 行 22｜67bc6d6f fix: protect sensitive local data
- **本质**：密码生成历史/常用账号等明文落 DataStore；改 AES-GCM 加密并迁移
- **触发**：生成历史/常用账号写盘
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/ui/screens/generator/GeneratorViewModel.kt:32,251-275
- **相似度**：功能相似但触发面不存在：主项目生成历史是纯内存 List<ProtectedString>（上限 10 条，淘汰显式清零），锁库经 SessionLockGuard 擦除、onCleared 兜底，从不落 DataStore/SharedPreferences；剪贴板走受保护定时擦除通道
- **risk**：no

### 行 23｜b7f14872 fix: reduce residual sensitive metadata exposure
- **本质**：日志输出 credentialId、条目标题、URI 绑定、附件路径等敏感元数据；全部改布尔/去值输出
- **触发**：通行密钥与同步链路打日志
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/autofill/KeePasskeyAutofillService.kt:218,222,332
- **相似度**：同类面且纪律已在位：抽检 autofill 服务、PasskeyAssertionActivity、picker 的日志全部为固定文案/布尔结果（如「调用应用已列入自动填充黑名单，拒绝下发数据集」「字段级屏蔽写入结果=$blocked role=…」），无包名/域名/条目标识（ISSUE-P1-10 与 §354 通知文案零插值同源纪律）
- **risk**：no

### 行 24｜126d9132 fix(export): prompt backup password dialog when exporting zip with passkeys
- **本质**：导出含通行密钥的 ZIP 未强制加密，私钥可明文落盘；检测到 passkey 强制弹密码对话框
- **触发**：导出包含通行密钥
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsExportController.kt:102-158
- **相似度**：同类面且防护更强：主项目一切明文导出（XML/CSV，含 KPEX_PASSKEY_* 字段）统一经 ExportTicket 闸门——令牌只能由 ExportConfirmationPolicy.confirm 在用户显式二次确认后签发，先校验令牌再序列化、失败不产生任何明文字节；不存在免确认的明文导出通道，无需 passkey 特判
- **risk**：no

### 行 25｜22a4877d fix: verify downloaded update apk
- **本质**：应用内更新 APK 安装前无校验；校验包名与签名摘要一致才安装
- **触发**：应用内下载更新 APK 后
- **我们的对应位置**：无直接对应
- **相似度**：触发面不存在：全仓无应用内更新/下载 APK/REQUEST_INSTALL_PACKAGES 代码（grep 零命中），更新由用户经应用市场或侧载自行完成
- **risk**：no

### 行 26｜d0ddca05 fix(monica-android): 导航竞态、生命周期闭包捕获与 runBlocking 超时、强制解包
- **本质**：导航竞态、runBlocking 超时与多处强制解包崩溃，逐一替换为安全判空
- **触发**：启动导航与 WebDAV 操作
- **我们的对应位置**：app/src/main/AndroidManifest.xml:102-110
- **相似度**：可验证的触发面均不存在：runBlocking 于五模块生产源码零命中；MainActivity 为 singleTask + taskAffinity=""（消解反复拉起竞态，ExportedComponentHygieneTest 锁定不消费外部 extra）。诚实声明：本条为多缺陷打包提交，「全仓强制解包普查」未在本轮执行，仅核验了 runBlocking 与导航入口两点
- **risk**：no

### 行 27｜0990cd4e Initialize generator state before restoring cached preferences
- **本质**：IO 协程与构造器竞态向未初始化 StateFlow 写入；holder 初始化后再启动恢复
- **触发**：release 启动恢复缓存偏好
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/ui/screens/generator/GeneratorViewModel.kt:35-40
- **相似度**：同类形态但无竞态：_uiState 声明（:35）先于 init{generateNewPassword()}（:38），生成协程写入的必是已初始化 StateFlow；生成器无「启动恢复缓存偏好」的异步恢复路径（历史为内存态，见 67bc6d6f 行）
- **risk**：no

### 行 28｜3fa80d9d fix(db): 修复 bitwarden_pending_operations 索引不匹配导致的崩溃
- **本质**：迁移脚本建了索引但 Room Entity 未声明，运行时校验崩溃
- **触发**：升级后访问待同步操作表
- **我们的对应位置**：无直接对应
- **相似度**：无同型面：主项目五模块无 Room（持久化为 .kdbx + 原子写文件），无 schema 校验迁移崩溃类
- **risk**：no

### 行 29｜d18e14d9 Fix local ZIP export writing empty files
- **本质**：部分 DocumentsProvider 下 openOutputStream("wt") 返回空/截断流致导出全空；回退 rwt/w
- **触发**：本地导出到特定 SAF provider
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/data/repository/SafVaultCreation.kt:92,137
- **相似度**：同类 SAF 写入面但模式已避开坑：全仓 openOutputStream 仅用 "w"（新建）与 "rwt"（覆写，:137）两态，另四处默认 "w"（DatabasePickerViewModel.kt:223 / EntryDetailAttachmentExporter.kt:48 / SettingsExportController.kt:57,210），无 Monica 踩坑的 "wt" 调用
- **risk**：no

### 行 30｜90995f0b fix(android): preserve attachment errors and stream ZIP exports
- **本质**：附件错误被压成通用错误且明文全量缓冲；流式写 ZIP、校验大小与 SHA-256
- **触发**：导出大附件或损坏附件
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailAttachmentExporter.kt:48
- **相似度**：触发面不存在：主项目无 ZIP 打包导出特性；单附件导出走 openOutputStream 流式写（不整包缓冲），整库导出为加密 kdbx 且经 WipableByteArrayOutputStream 承载（规模代价已按限界 §32 登记）
- **risk**：no

### 行 31｜8a6beeb0 fix(autofill): forceAutofillOff 保留同组非 OFF 候选
- **本质**：OFF 候选致整组被跳过、同组有效字段连带丢弃；改仅全组 OFF 才跳过
- **触发**：同一 autofillId 组混有 OFF 与非 OFF 候选
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/autofill/AutofillFieldScanner.kt:206-208
- **相似度**：同面且形态本就正确：主项目对 importantForAutofill=no 的节点按**单字段** continue 跳过（首轮扫描、弱解析、结构化识别、目标解析四处同口径），不存在「一个 OFF 拖垮整组」的组级跳过逻辑
- **risk**：no

### 行 32｜f8f987c8 fix(autofill): 收紧 TYPE_TEXT_VARIATION_NORMAL fallback
- **本质**：纯 text 输入框被无条件 fallback 为 USERNAME:LOWEST 致搜索框误弹
- **触发**：QQ 等纯 text 搜索框页面
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/autofill/AutofillFieldFallback.kt:37-76
- **相似度**：同面且已防护（并已是吸收成果）：首轮扫描无「纯 text 无条件回退 USERNAME」路径（全文件无 TYPE_TEXT_VARIATION_NORMAL 回退）；弱目标二次解析带登录上下文门——无密码目标时须全页含密码术语才采纳弱账号目标（:72-76），置信度仅 LOW，且搜索框一律排除
- **risk**：no

### 行 33｜bff24388 fix(autofill): 排除搜索框误弹密码条目
- **本质**：页面有密码框即整页按登录上下文处理，搜索框被当作 USERNAME；新增 isSearchField() 清空其候选
- **触发**：Edge/GitHub 等网页搜索框聚焦
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/autofill/AutofillFieldScanner.kt:205,352
- **相似度**：同面且防护已在位（ISSUE-P3-372 吸收 Monica 解析器时一并落地）：isSearchField 判定存在于扫描器（:352），首轮扫描（:205）、弱解析（Fallback:46）与聚焦合成（Fallback:110）三处统一排除搜索框
- **risk**：no

### 行 34｜7f7685e5 fix(autofill): 解锁会话跨进程共享, 消除每次填充强制二次解锁
- **本质**：解锁状态仅存主进程内存，独立进程 Autofill 服务读不到致每次弹解锁
- **触发**：独立进程 Autofill 服务每次填充
- **我们的对应位置**：app/src/main/AndroidManifest.xml:238-248
- **相似度**：触发面不存在：主项目 KeePasskeyAutofillService 及全部组件均未声明 android:process（Manifest 计数 0），自动填充服务与主进程同进程，会话状态直接可达，无跨进程读锁态问题
- **risk**：no

### 行 35｜8fcaf0dd fix(autofill): WebView 填充改用 ACTION_PASTE 优先
- **本质**：ACTION_SET_TEXT 对 WebView 输入不触发 JS input 事件；改 PASTE 优先、SET_TEXT 回退
- **触发**：React/Vue 等 WebView 登录表单
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/autofill/legacy/
- **相似度**：触发面不存在：主项目唯一 accessibility 组件是 LegacyAutofillAccessibilityService（Manifest:218-229，§324/§354），其职能仅「口令框检测→发通知→LegacyFillPickerActivity」，全程无任何键入模拟/SET_TEXT/PASTE 注入路径；框架填充走系统 Autofill Dataset，不经文本注入
- **risk**：no

### 行 36｜c854ef2a Invalidate cached autofill prompts when fields are blocked
- **本质**：字段被拉黑后旧填充响应/认证结果仍生效；返回 ignored-ids 响应并带 client state 使系统重查失效
- **触发**：用户拉黑字段签名后再次填充
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/autofill/AutofillTargetFieldResolver.kt:85-101；app/src/main/java/com/keepasskey/app/autofill/AutofillAuthResultDelivery.kt:49；app/src/main/java/com/keepasskey/app/autofill/AutofillPickerActivity.kt:159-170
- **相似度**：功能相似（同一「字段级屏蔽」特性，主项目 ISSUE-P3-43② 的角色级 blocklist），存在同型缺口：屏蔽判定**只**挂在 onFillRequest 的目标解析期（AutofillFieldBlockPolicy.decide 唯一调用点 = TargetFieldResolver:85）；认证回传链（picker confirmAndFill → AutofillAuthResultDelivery.buildAuthenticationResultDataset）按认证 Intent 携带的 usernameId/passwordId 构造 Dataset，**不复检** blocklist（AutofillAuthResultDelivery/PickerViewModel/ConfirmActivity 中 blocklist 检索零命中）；且全 autofill 包无 setIgnoredIds/setClientState（grep 零命中）——屏蔽写入后（PickerActivity:159 blockFieldAndFinish 仅 RESULT_CANCELED），系统侧缓存响应重放/再次确认时被屏蔽角色的值仍可交付（Monica 已实证的框架缓存行为）
- **risk**：**yes**
- **advice**：① 在认证回传链（AutofillPickerActivity.confirmAndFill、AutofillConfirmActivity、AutofillUnlockActivity 路由）交付前复检 autofillFieldBlocklistStore，被屏蔽角色（USERNAME/PASSWORD）的字段不写入回传 Dataset；② 评估 Monica 同款「ignored-ids + clientState」响应形态，使屏蔽变更后系统重查失效旧响应（FillResponse.Builder.setIgnoredIds API 26+，minSdk 36 满足）；③ 先真机验证「拉黑后同窗口缓存数据集重放→再次点选确认」是否复现被屏蔽角色值交付（本判定的平台前提沿用 Monica confirmed 记录，主项目未实测）。注意：结构化角色（structuredFields）如需纳入该复检，须先开产品裁决——§355 既有登记口径为「结构化目标不经字段级屏蔽，安全面=确认页二次认证+归属展示」（AutofillTargetFieldResolver.kt:82-84 KDoc，StructuredFillWiringTest 锁定），不得默认一并覆盖

### 行 37｜759ee008 fix(autofill): stabilize nonstandard and dynamic login forms
- **本质**：动态重建的 WebView 输入与缓存填充目标跨表单错配；字段与缓存限定活动表单，键盘填充前校验焦点
- **触发**：动态登录表单/重创建的 WebView 输入框
- **我们的对应位置**：app/src/main/java/com/keepasskey/app/autofill/AutofillTargetFieldResolver.kt:112-151
- **相似度**：同面且已有防护（且 KDoc 明载与 Monica 同口径）：跨请求登录字段记忆回补前**全键有效性复核**——记忆的全部 AutofillId 键必须仍在当前结构中（keyIndexMap 匹配）且逐侧按可见性/importantForAutofill 复核，任一键失效整体放弃（:124-140，KDoc「与 Monica『缓存 id 仍有效才回补』同口径」）；主项目填充经系统 Dataset 交付（非自管键盘逐字段填充），无需焦点前校验半环
- **risk**：no

### 行 38｜60523e4b fix: prevent autofill placeholder from filling
- **本质**："PLACEHOLDER" 哨兵串被放进 AutofillValue 可能被真实填入字段；删除哨兵路径并加守卫
- **触发**：需认证数据集的填充回环
- **我们的对应位置**：无直接对应
- **相似度**：触发面不存在：autofill 包 grep "placeholder/PLACEHOLDER" 零命中；认证数据集的占位呈现由框架 RemoteViews 菜单文案承担（AutofillAuthResultDelivery 只在用户显式确认后构造含真实值的 Dataset），无哨兵值进 AutofillValue 的路径
- **risk**：no

### 行 39｜b5ccc678 fix: avoid autofill auth result reuse
- **本质**：autofill 相关 Activity 设 singleTop 复用旧实例 extras，认证结果跨请求被复用；移除 launchMode 保每次新实例
- **触发**：短时间连续两次填充请求
- **我们的对应位置**：app/src/main/AndroidManifest.xml:183-213
- **相似度**：触发面不存在：AutofillUnlockActivity/AutofillConfirmActivity/AutofillPickerActivity（及全部 passkey 落地 Activity）均未声明 launchMode（默认 standard，每次认证新建实例），extras 不会跨请求复用；仅 MainActivity 用 singleTask 且其「不消费外部 intent 数据」由守卫锁定
- **risk**：no

### 行 40｜e7ea0a4d fix(android): split oversized TotpListContent to avoid ART VerifyError
- **本质**：超大 Compose 函数被 d8 以 move/16 复用参数寄存器，ART 校验拒绝类加载，打开页面即崩
- **触发**：debug 构建打开超大 Compose 页面
- **我们的对应位置**：docs/AGENTS.md（§5 构建与测试命令：long_functions 门禁）
- **相似度**：同类风险面有结构性防护：CI hygiene-gate 的 long_functions 为 fail-closed 硬门禁（函数 ≥100 行即红，阈值记录于 AGENTS.md §5），超大 Compose 函数在入库前即被拦下，远低于 Monica 278 寄存器的量级；本项未做寄存器数实测，属门禁推理而非 d8 读数
- **risk**：no

## 三、复核修正记录

1. **c854ef2a（risk=yes）全查通过、维持 yes**，补充证据：AutofillFieldBlockPolicy.decide 全仓唯一调用点为 AutofillTargetFieldResolver.kt:85（grep 证实）；AutofillAuthResultDelivery.kt:46-103 buildAuthenticationResultDataset 按调用方传入 id 直接 setField、零 blocklist 复检；回传链 AutofillPickerActivity.kt:302-311 / AutofillConfirmActivity.kt:310-344 / AutofillUnlockActivity.kt:159-162 字段 id 全取自认证 Intent extras 且均无复检；blockFieldAndFinish（AutofillPickerActivity.kt:159-169）仅 setResult(RESULT_CANCELED)；setIgnoredIds/setClientState 全仓 grep 零命中；测试面仅 AutofillFieldBlockPolicyTest（纯函数）+AutofillRecoveryWiringTest/StructuredFillWiringTest（接线），回传链复检零守卫——同型缺口坐实。advice 微修：原③「§355 结构化分支整改时须一并覆盖」与 §355 既有登记口径冲突（AutofillTargetFieldResolver.kt:82-84 KDoc 明载结构化目标不经字段级屏蔽、安全面为确认页二次认证+归属展示，StructuredFillWiringTest:42 锁定该分支），改为「结构化角色如需纳入须先开产品裁决」。
2. **范围说明**：全表 40 行中 risk=unclear 0 条、risk=excluded 0 条，故裁决登记表/限界表语义覆盖抽查无可执行对象；其余 39 行 risk=no 维持原判（未逐行重查，属任务口径之外）。
3. **旁证（两条承重 no 行，不作为范围外结论）**：d0ddca05 行 runBlocking 于 app/crypto/database/sync/core 五模块 main 源码 grep 计数 0；7f7685e5 行 app/src/main/AndroidManifest.xml 中 android:process 计数 0——两行与代码事实相符。
