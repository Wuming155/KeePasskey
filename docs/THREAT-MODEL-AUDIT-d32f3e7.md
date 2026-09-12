# KeePasskey 第三方安全审计 · 威胁建模与架构评估（只读阶段）

> **目标版本**：KeePasskey，commit `d32f3e7`（minSdk 36 / targetSdk 36 / compileSdk 37，Kotlin 2.4.20 + Compose + Hilt）。
> **本次性质**：**只读**架构 / 威胁建模阶段，**未修改任何文件**。本文是后续代码审计员的输入基线。
> **证据分级（全文逐条标注）**：
> - **(a) 代码核实** —— 直接读取源码/字节码得到，附 `path:line`；
> - **(b) 推断** —— 由已核实代码推理得出，未在设备上执行；
> - **(c) 无法确认** —— 需真机/运行时/外部环境，列入 §Cannot Confirm。
>
> **纪律**：不虚构漏洞。凡代码确实挡住某攻击的，一律写明「**不可利用，因为 X（file:line）**」。项目文档已声明的**已接受限界**按其原状引用（`AGENTS.md` §6 / `docs/ACTIVE_ISSUES.md` / `docs/RESOLVED_LOG.md`），不冒充新发现；但**实现弱于文档声称之处**会明确标出。

---

## 0. 结论摘要（先读这一段）

| 判定 | 内容 |
|---|---|
| **静态机密性核心是可靠的** | KDBX4 的头部 SHA-256 + 头部 HMAC 先于任何载荷解密（`KdbxFile.kt:114,137`），块 HMAC 逐块先验后用并有权威终止检查点（`HmacBlockStream.kt:228-268`、`KdbxFile.kt:207`）。**没有任何「未验完整性即解密」的代码路径**（唯一入口 `KdbxFile.load`）。**离线数据库破解攻击者 (O) 面对的是标准 KDBX4 + 用户所选 Argon2 参数**——这是全系统唯一真正意义上的密码学边界。 |
| **最危险的单个设计决策** | **「应用进程 = 信任域」**。主密码明文、解密后的对象树、`ProtectedString` 的进程内主密钥（`InMemoryCipher.kt:62,65`，静态 `object` 字段、**永不轮换、永不擦除**）全部同时存在于同一进程堆中。因此 root / Hook / 恶意 IME / 同 UID 代码执行 / 被 patch 的 APK —— **这些对手全部一步到位拿到明文**，其余所有加固层都只是提高门槛。 |
| **最高价值的、值得保留的设计决策** | **KDBX4 单体加密流的「先验后用」完整性契约**（头 HMAC → 块 HMAC → 终止块三道 fail-closed，且明确处理了 `CipherInputStream` 吞异常伪装 EOF 的平台陷阱，`HmacBlockStream.kt:145-151,192-206`）。它使**恶意 KDBX 文件攻击者 (D)** 与**不可信云端 (I)** 在无主密钥时完全无法构造可被接受的伪造内容。 |
| **最大单点失效** | **主密码 → 唯一凭据**（默认无第二因子）。破解者拿到 `.kdbx` 后只需一次离线 Argon2 攻击；而 `unlockThrottleEnabled` **出厂默认 false**（`SettingsRepository.kt:53`，2026-09-12 用户裁决），在线暴力破解**默认无节流**。 |
| **最被高估的机制** | **同步防回滚**。其状态文件位于 `cacheDir/sync`，而该目录在**每次锁库时被整体销毁**（`SyncCacheEvictor.kt:37-39,46-63` + `SyncCache.kt:286,306-314`）。状态一空，`SyncRollbackGuard.kt:79` 即返回 `Accept` → **防重放窗口实际只覆盖「当前这次未锁定会话」**。该限制**未写在该守卫的 KDoc 里**（`SyncRollbackGuard.kt:44-52`），而 `docs/同步层记录级完整性威胁建模.md:49-51` 只描述了「状态文件被篡改→按无历史处理」，未描述「正常锁库即清空」。文档强于实现，须作为新发现上报。 |
| **A/B/C/D/E/F/G/H/I/J/K/L/M/N/O 十五个对手中，最终能拿到明文口令的** | **E（物理接触，若设备解锁）/ F（设备已解锁）/ G（root）/ H（被攻陷的 OS）/ J（供应链/patch/Frida）/ K（恶意第三方组件）/ L（恶意 WebView/Intent 源，仅经由 IME 或无障碍等系统级能力）/ N（恶意 IME）**——前提是 **Android 沙箱/TEE 被突破或应用进程被控制**。**A/B/C/D/I/M/O 均不能**（各有明确拦截点，见 §3）。 |

---

# Deliverable 1 — 系统分解与信任边界

## 1.1 真实数据流（ASCII）

```
 ┌─────────────────────────────────────────────────────────────────────────────────────────┐
 │ 用户（人类）—— 唯一被授权的秘密输入者                                                    │
 └───┬─────────────────────────────────────────────────────────────────────────────────────┘
     │ 主密码（CharArray，永不 String）
     ▼
╔══════════════════════ TB-1：用户 → UI ══════════════════════════════════════════════════╗
║ 验证者：无（人机边界）。防侧漏：FLAG_SECURE（锁定态无条件）+ 遮挡触摸过滤 + 反 overlay ║
║ app/.../ui/components/SecurePasswordField.kt（唯一的 CharArray 桥接点）                  ║
║ app/.../security/FlagSecurePolicy.kt:24-27   FlagSecureGuard.kt:41-59,80-82              ║
║ app/.../MainActivity.kt:64,67   BaseCredentialActivity.kt:20-25                          ║
║ 已接受残留：输入法个性化学习未禁用（ACTIVE_ISSUES ISSUE-P3-76，框架阻塞，保留跟踪）      ║
╚═══════════════════════════════┬══════════════════════════════════════════════════════════╝
                                ▼
╔══════════════════════ TB-2：UI/ViewModel → Repository/会话 ═════════════════════════════╗
║ 验证者：UnlockViewModel（节流闸门 + 空密码拒绝 + 失败清零）                               ║
║ app/.../ui/screens/unlock/UnlockViewModel.kt:226-341（gate :232, 清零 :335-341）          ║
║ app/.../security/UnlockThrottle.kt:257-299（完整性失效恒 fail-closed :262-272）            ║
║ ⚠ 出厂默认不启用节流：SettingsRepository.kt:53  unlockThrottleEnabled = false            ║
╚═══════════════════════════════┬══════════════════════════════════════════════════════════╝
                                ▼
╔══════════════════════ TB-3：Repository → KDBX 编解码（database 模块）═══════════════════╗
║ 验证者：SessionOpener（只读模式、凭据缓存克隆语义）                                       ║
║ database/.../session/SessionOpener.kt:41-164   DatabaseSession.kt:134-173                 ║
║ 单一解析入口：KdbxFile.load（SessionOpener:146 / ChildReadOnlySession.kt:134 /            ║
║   app/.../sync/SyncDatabaseCodec.kt:55）—— 无第二个 KDBX 读取器                            ║
╚═══════════════════════════════┬══════════════════════════════════════════════════════════╝
                                ▼
╔══════════════════════ TB-4：KDBX 编解码 → 密码学（crypto 模块 + Rust JNI）══════════════╗
║ 验证者（全部 fail-closed）：                                                              ║
║  ① 头部 SHA-256 常量时间比对           KdbxFile.kt:108-115                                ║
║  ② 头部 HMAC-SHA256（先于任何解密）    KdbxFile.kt:118-139 ← 凭据正确性的唯一裁决点        ║
║  ③ 块 HMAC（索引并入密钥）             BlockHmac.kt:55-77  HmacBlockStream.kt:228-268      ║
║  ④ 终止块权威检查（含吞异常重放）      HmacBlockStream.kt:192-206 ← KdbxFile.kt:207         ║
║  ⑤ 解压炸弹上限 128 MiB                KdbxFile.kt:75,83-86,191-194 + SizeBoundedInputStream║
║  ⑥ XXE / DTD / 深度 / 文本长度守卫     KdbxXmlParser.kt:52-78,145-224  KdbxXmlSaxNodes:44-51 ║
║  ⑦ KDF 参数上界（M≤4GiB, t≤2^24, p≤64）KdbxKdfParameterCodec.kt:106-132                     ║
║ Rust 内核：crypto/src/main/rust/src/{lib.rs,aes_kdf.rs,twofish_cbc.rs,strength.rs}          ║
║   跨 FFI 只传基本类型/数组；Zeroizing 全路径擦除；catch_unwind 阻断 panic（jni_bridge.rs:79）║
╚═══════════════════════════════┬══════════════════════════════════════════════════════════╝
                                ▼
╔══════════════════════ TB-5：密码学 → Android Keystore / TEE ════════════════════════════╗
║ 验证者：KeyGenParameterSpec 契约 + KeyInfo 全等探测迁移                                   ║
║ app/.../security/KeystoreKeyMaterial.kt:82-128（AES-GCM-256，per-op 强生物识别，          ║
║   setInvalidatedByBiometricEnrollment(true)，setUnlockedDeviceRequired(true)，StrongBox    ║
║   优先回退 TEE，spec 漂移全等探测重建 :162-177 / :246-292）                               ║
║ app/.../security/UnlockAuthPolicy.kt:31-65（语义唯一声明点：仅 BIOMETRIC_STRONG）          ║
║ ⚠ 软 Keystore 落位仅告警不硬失败：KeystoreKeyMaterial.kt:119-127（b，fail-open）          ║
╚═══════════════════════════════┬══════════════════════════════════════════════════════════╝
                                │
   ┌────────────────────────────┴────────────────────────────────────────────┐
   ▼                                                                         ▼
╔══ TB-6：附件磁盘缓存 ═══════════════╗                    ╔══ TB-7：同步出口（WebDAV/S3）══╗
║ >1 MiB 附件流式落盘 cacheDir/attachments ║              ║ 验证者：SyncEndpointGuard（SSRF+DNS 重绑定）║
║ app/.../data/binary/FileBinaryStore.kt:28-73        ║   sync/.../network/SyncEndpointGuard.kt:55-241 ║
║ 权限 0600/0700（SyncCache.kt:362-377,427-431）      ║ TLS-only：SyncHttpClientFactory.kt:26-33        ║
║ 锁定即清：SessionLockObserver（:61-65）             ║ 凭据封印于 Keystore（无用户认证）              ║
║ ⚠ 非流式写路径 .tmp 未先收权限（SyncCache:101-111）║   app/.../sync/SyncCredentialSealer.kt:45-73    ║
╚═════════════════════════════════════╝                  ║ 防回滚 MAC：SyncRollbackGuard.kt:75-96        ║
                                                          ║ ⚠ 无证书固定（有意，政策 §3 声明）           ║
                                                          ╚═══════════════┬═════════════════════════════╝
                                                                          │ 不可信云端（Assume Breach）
                                                                          ▼
╔══ TB-8：自动填充 IPC 入口（AutofillService）═════════════════════════════════════════════╗
║ 验证者（全部在产出任何数据集之前）：                                                        ║
║  callingPkg = structure.activityComponent.packageName（系统背书）   :118                    ║
║  完整性闸门 + 黑名单（空包名 fail-closed）    :123-137 / AutofillBlocklistStore.kt:57-76     ║
║  字段级屏蔽                                  :161-173                                        ║
║  webDomain 归属：受信浏览器「包名+已取证签名指纹」二元组，否则 DAL，否则 REJECTED            ║
║    app/.../autofill/AutofillOriginResolver.kt:34-75                                          ║
║    app/.../security/BrowserSigningFingerprints.kt:32-56（仅 Chrome/Firefox/Beta）             ║
║    app/.../passkey/DigitalAssetLinksVerifier.kt:88,145-169                                     ║
║  域匹配严格标签边界 + PSL 下限      passkey/DomainMatcher.kt:78-85,124-126                     ║
║  每个已解锁数据集强制 setAuthentication 二次确认                                                ║
║    AutofillDatasetBuilders.kt:192-209（FLAG_IMMUTABLE :206）+ AutofillConfirmActivity        ║
║  ⚠ 确认弹窗不显示请求方包名/域（同意保真度）：strings.xml:957-965                             ║
╚═══════════════════════════════════════════════════════════════════════════════════════════╝

╔══ TB-9：Credential Manager / Passkey IPC 入口 ═══════════════════════════════════════════╗
║ 验证者：CallingOriginResolver（浏览器委派必须官方 getOrigin + 特权白名单；                 ║
║   普通应用固定颁发 android:apk-key-hash:<b64(sha256(签名证书))>）  :57-97                   ║
║   白名单仅 com.android.chrome（app/.../passkey/CallingOriginResolver.kt:33-50）              ║
║   RP-ID/域匹配 + 交付前独立复验：CredentialResponseAssembler.kt:102-118,179-188              ║
║                                  PasswordFillActivity.kt:92-100                              ║
║                                  PasskeyAssertionActivity.kt:100-120                         ║
║   ⚠ 本通道不消费 RuntimeIntegrityGate（fail-open，与策略自述不符）                            ║
║   ⚠ CM 通道 BiometricPrompt 未绑 CryptoObject（与自动填充通道不对称）                         ║
║     CredentialVerificationLauncher.kt:43-62 vs AutofillConfirmActivity.kt:88-103            ║
╚═══════════════════════════════════════════════════════════════════════════════════════════╝

╔══ TB-10：剪贴板 / 通知 / 备份 / 导出 出口 ═══════════════════════════════════════════════╗
║ 剪贴板：EXTRA_IS_SENSITIVE + 定时摘要比对擦除（默认 30 s）ClipboardSecurityManager.kt:44-149 ║
║ 通知：仅验证码或通用文案，VISIBILITY_SECRET，无用户数据 TotpNotificationPublisher.kt:58-75  ║
║ 备份：allowBackup=false + 全域排除（cloud + device-transfer）AndroidManifest.xml:18-19       ║
║ 导出：明文 XML/CSV/附件/密钥文件一律经 ExportConfirmationPolicy fail-closed 二次确认         ║
║        SettingsExportController.kt:201-265 + DatabaseSettingsScreen.kt:366-414               ║
╚═══════════════════════════════════════════════════════════════════════════════════════════╝
```

## 1.2 边界清单（跨越什么 / 谁验证 / 证据）

| # | 边界 | 跨越的东西 | 验证者 | 证据（file:line） |
|---|---|---|---|---|
| **TB-1** | 用户 → UI | 主密码、条目口令、TOTP 种子 | 无（人机）；侧漏防护 = FLAG_SECURE / 遮挡触摸 / 反 overlay | `security/FlagSecurePolicy.kt:24-27`；`FlagSecureGuard.kt:41-59,80-82`；`MainActivity.kt:64,67`；`BaseCredentialActivity.kt:20-25` |
| **TB-2** | UI → Repository/会话 | `CharArray` 主密码 / 密钥文件字节 / 解锁意图 | 节流闸门、空密码拒绝、失败清零、完整性闸门 | `unlock/UnlockViewModel.kt:226-341`；`security/UnlockThrottle.kt:257-299`；`security/RuntimeIntegrityPolicy.kt:107-141` |
| **TB-3** | Repository → KDBX 编解码 | 明文口令字节、复合密钥、`KdbxDatabase` 对象树 | 单入口 + 凭据缓存克隆语义 + 只读模式 | `session/SessionOpener.kt:133-164`；`session/SessionCredentialCache.kt:28-58`；`DatabaseSession.kt:178-233` |
| **TB-4a** | 文件 → 头部认证 | header bytes + 存储的 SHA-256/HMAC | 常量时间比对，失败即 `KdbxCorruptFileException` / `KdbxInvalidCredentialsException` | `file/KdbxFile.kt:105-139` |
| **TB-4b** | 密文 → 明文（载荷） | HMAC 认证后的分块密文 | 块 HMAC 先验后用 + 终止块权威检查 | `file/BlockHmac.kt:55-77`；`file/HmacBlockStream.kt:228-268,192-206`；`KdbxFile.kt:160-210` |
| **TB-4c** | 解压/XML → 对象树 | 解压后明文 XML | 128 MiB 上限、XXE 四特性 + handler 侧 fail-closed、深度 ≤64、文本 ≤8Mi 字符、内层各上限 | `KdbxFile.kt:75,83-86,191-194`；`xml/KdbxXmlParser.kt:52-78,145-224`；`xml/KdbxXmlSaxNodes.kt:44-51`；`file/InnerHeader.kt:186-249` |
| **TB-4d** | Kotlin ↔ Rust (JNI) | 复合密钥、KDF 参数、块数据 | 定长布局契约 + `available` 探活 + `catch_unwind` + `Zeroizing`；失败一律回退 JVM 而非静默重派生 | `crypto/.../NativeCryptoLibrary.kt:23-37`；`kdf/NativeAesKdf.kt:34-55`；`rust/src/jni_bridge.rs:79`；`rust/Cargo.toml:52-53` |
| **TB-5** | 密码学 → Keystore/TEE | 封印凭据、MAC 密钥、断言私钥 | `KeyGenParameterSpec` + `KeyInfo` 全等探测；解封需 per-op 强生物识别 | `security/KeystoreKeyMaterial.kt:82-128,158-204,246-331`；`security/UnlockAuthPolicy.kt:31-65` |
| **TB-6** | 附件 → 磁盘 | 明文附件字节 | 0600/0700 + 锁定即清 | `data/binary/FileBinaryStore.kt:28-73`；`sync/.../SyncCache.kt:306-314,362-377,427-431` |
| **TB-7** | 同步出口 → 不可信云端 | 整份 `.kdbx` 密文、同步凭据、S3 SigV4 签名 | TLS-only + SSRF/DNS 重绑定守卫 + 防回滚 MAC + 三哈希状态机 | `sync/.../SyncHttpClientFactory.kt:26-33`；`SyncEndpointGuard.kt:55-241`；`SyncRollbackGuard.kt:75-96`；`app/.../sync/SyncCredentialSealer.kt:45-73` |
| **TB-8** | 其他应用 → 自动填充服务 | `AssistStructure`（调用方**可控**）、`autofillId`、`webDomain` | 系统背书包名 + 完整性/黑名单 + 域归属双向绑定 + 强制二次确认 | `autofill/KeePasskeyAutofillService.kt:118-213`；`AutofillOriginResolver.kt:34-75`；`AutofillDatasetBuilders.kt:192-209` |
| **TB-9** | 其他应用 → Credential Provider | `BeginGetCredentialRequest`、`requestJson`、`origin` | 官方 `getOrigin` + 特权白名单 / apk-key-hash 固定颁发；RP-ID 与包名双重严格匹配 + 交付前复验 | `passkey/CallingOriginResolver.kt:57-97`；`CredentialResponseAssembler.kt:102-118`；`PasswordFillActivity.kt:92-100`；`PasskeyAssertionActivity.kt:100-120` |
| **TB-10** | 应用 → 其他应用/系统（出口） | 剪贴板明文、验证码、导出明文、备份 | 敏感标记 + 定时擦除；通知最小化；备份全排除；明文导出需显式二次确认 | `security/ClipboardSecurityManager.kt:44-149`；`notification/TotpNotificationPublisher.kt:58-75`；`AndroidManifest.xml:18-19`；`SettingsExportController.kt:224-265` |

---

# Deliverable 2 — 资产清单

| 资产 | 存在位置（内存/磁盘/IPC） | 机密性要求 | 合法访问者 | 保护机制 | 证据（file:line） |
|---|---|---|---|---|---|
| **主密码** | 内存：`CharArray`（UI 输入框 → `SessionCredentialCache` 克隆）；磁盘：**仅以 Keystore 封印密文形式**出现在 `biometric_credentials` SharedPreferences | 极高（等同全库） | 用户本人；`DatabaseSession.save` 派生管线 | 绝不入 `String`；显式清零；快速解锁封印走 AES-GCM per-op 强生物识别 Keystore 密钥 | `session/SessionCredentialCache.kt:47-50,64-96`；`security/BiometricCredentialStorage.kt:78-100`；`security/KeystoreKeyMaterial.kt:106-111` |
| **KDBX 数据库文件** | 磁盘：`filesDir/*.kdbx`（应用沙箱）；`.bak` 滚动备份；SAF URI（用户自选介质） | 高（离线攻击面） | 应用进程；被授权用户（经 SAF） | 整库 AES-256-CBC / ChaCha20 / Twofish-CBC + 头 HMAC + 块 HMAC。**文件系统权限未显式收紧**（依赖 Android 沙箱默认） | `data/repository/VaultLifecycleCoordinator.kt:113-118`；`session/AtomicFileWriter.kt:65-112`；`file/KdbxFile.kt:316-341` |
| **数据库加密密钥（cipherKey / hmacKey64）** | 只在内存，派生后用于流式加解密，`finally` 清零 | 极高 | 会话内 `KdbxFile.load/save` | 单次派生即用即清；无任何落盘 | `file/KdbxKeyDerivation.kt:98-127`；`file/KdbxFile.kt:141-145,337-340` |
| **密码条目（口令字段）** | 内存：`ProtectedString`（**堆内密文**，AES-256-CTR + 每次随机 IV + HMAC 等值标签） | 极高 | 详情页揭示、自动填充、导入导出 | 驻留加密 + 读取瞬间副本用毕即擦 + `toString()` 永不吐明文 | `core/.../security/ProtectedString.kt:40-51,92-127,209-215`；`core/.../security/InMemoryCipher.kt:94-113` |
| **用户名** | 内存：`ProtectedString`（通常 `isProtected=false` → 明文克隆）；UI 投影 `UiVaultEntry` | 中 | 大部分 UI 路径 | 按 KDBX `Protected` 标志；非保护值明文驻留 | `core/.../model/KdbxEntry.kt:32,38,41`；`ProtectedString.kt:46-50` |
| **TOTP / HOTP 种子** | 内存：`ProtectedString`（`TOTP Seed` 受保护字段）；**二维码取景窗口**曾为泄露面 | 高（可推未来码、部分场景可推账户） | 详情页、验证器页、自动填充后复制 | 驻留加密 + 扫码窗口已纳入 FLAG_SECURE（ISSUE-P3-71） | `xml/KdbxXmlStringNode.kt:30`；`security/SecureCaptureActivity.kt:24-28`；`AndroidManifest.xml:111-118` |
| **附件（字节）** | 内存：≤1 MiB 在二进制池；>1 MiB 落盘 `cacheDir/attachments` | 高 | 预览 / 导出 / 同步 | 落盘 0600/0700 + 锁定即清；**内存池无擦除 API**（仅随引用丢弃） | `file/InnerHeader.kt:296-313`；`data/binary/FileBinaryStore.kt:32-65`；`file/KdbxDatabase.kt:46-48`（缺口，见 §5） |
| **DB 元数据** | 内存对象树 + 明文外层头（库名/KDF 参数/主种子/IV） | 中（可指纹化设备与库） | 应用进程 | 外层头按 KDBX 规范为明文（**不可避免**）；KDF secret/AD 若存在则属于高敏 | `file/KdbxKdfParameterCodec.kt:49-56,85-96`；`KdfParameters.kt:35-36` |
| **密钥文件（第二因子）** | 内存：`keyFileCache` 克隆；磁盘：仅用户显式导出到 SAF 目标 | 极高（与主密码等权） | 解锁 / 保存 / 显式导出 | 会话内克隆 + 锁定清零；**解析梯子中物化为不可擦除 `String`**（缺口） | `session/SessionCredentialCache.kt:53-58,91-96`；`file/KdbxKeyFile.kt:40-50`（缺口） |
| **Android Keystore 密钥（8 个别名）** | TEE/StrongBox 内（不可导出）；磁盘仅存别名 | 极高 | 应用进程按 spec 约束使用 | per-op 强生物识别 / `setUnlockedDeviceRequired` / 录入变更吊销；HMAC 类密钥无认证门控（完整性用途） | `KeystoreKeyMaterial.kt:82-128,294-331`；别名清单见 §3 表 |
| **生物识别授权状态** | 内存：`BiometricResult` / `CryptoObject`；无持久化布尔位 | 高（门控决策） | 应用进程 | 自动填充通道要求 **CryptoObject 绑定**（`Success && cipher != null`）；CM 通道未绑定（不对称） | `security/AutofillAuthBindingPolicy.kt:20-21`；`AutofillConfirmActivity.kt:88-107` vs `CredentialVerificationLauncher.kt:43-62` |
| **剪贴板内容** | 系统剪贴板（跨进程，**其他前台应用可读**） | 高（窗口期） | 用户选定目标应用 | `EXTRA_IS_SENSITIVE`（仅抑制 13+ 预览，非强制）+ 定时摘要比对擦除（默认 30 s） | `security/ClipboardSecurityManager.kt:44-56,106-136`；默认值 `RealSettingsRepository.kt:98,105` |

---

# Deliverable 3 — 对手模型（15 个）

## 3.1 汇总表

| # | 对手 | (1) 能看 | (2) 能控 | (3) 能改 | (4) 能执行 | (5) 拦截边界 | (6) **能否最终拿到明文口令** |
|---|---|---|---|---|---|---|---|
| **A** | 普通恶意应用（无权限） | 自身沙箱、公开 Intent | 自身 UI、自身包名 | 仅自身数据 | 自身进程 | Android 沙箱 + `exported`/BIND 权限 + 域归属校验 | **否** |
| **B** | 恶意应用（normal 权限） | 同上（无 READ_EXTERNAL 敏感路径） | 同上 + 前台窗口/悬浮窗 | 同上 | 同上 | 同上 + `setHideOverlayWindows` + 遮挡触摸过滤 | **否** |
| **C** | 恶意输入文件攻击者 | 用户交给它的文件通道 | 键文件/导入文件内容 | 键文件/导入文件 | 解析器代码路径 | 解析上限 + XXE fail-closed + 密钥文件字符串搜索（无 XML 解析） | **否** |
| **D** | 恶意 KDBX 数据库攻击者 | — | 任意 `.kdbx` 字节 | 同上 | 解析器 + 解密管线 | 头 HMAC → 块 HMAC → 终止块三道 fail-closed，**无主密钥不可构造可通过的内容** | **否** |
| **E** | 物理接触攻击者（关机/未解锁） | 加密的 `.kdbx`、SharedPreferences 密文、Keystore 别名 | 文件系统（离线） | 文件（离线） | 仅离线计算 | Argon2 KDF + TEE 不可导出密钥。**若设备处于解锁态或被强制解锁，退化为 F** | **否**（离线）；**是**（若设备已解锁且未自动锁定，见 F） |
| **F** | 设备已解锁状态下的攻击者 | 全部 UI、解密后的会话（若未锁定） | 设备输入、拉起任意应用 | 应用数据目录（可写） | 任意已安装应用；可点选自动填充 | 自动锁定（默认 60 s）**+ LOCKED 时强制 FLAG_SECURE** | **是**（前提：在自动锁定窗口内 + 用户被诱导确认一次填充；见下文详细论证） |
| **G** | root 攻击者 | **全部**（进程内存、Keystore 别名可用性、所有文件） | 全部 | 全部 | 任意代码（ptrace/Frida/直接读 `/proc/pid/mem`） | **无软件边界**——仅剩 TEE 内密钥不可导出（不足以阻止读取瞬间截获明文） | **是** |
| **H** | 被攻陷的 OS | 同 G（可能更早） | 同 G | 同 G | 同 G | 无 | **是** |
| **I** | 网络攻击者（同步在途） | TLS 流量元数据；若降级则全量密文 | 网络路径 | 可篡改/重放/替换/删除远端字节 | 服务端响应 | TLS-only + 系统 CA（无固定）+ SSRF 守卫 + 防回滚 MAC（**会话内有效**）+ 块 HMAC | **否**（无主密钥无法伪造可解密内容） |
| **J** | 供应链攻击者（APK 可反编译/patch/Frida） | **全部**（可改代码） | 全部 | 全部 | 任意 | **无**——R8 只提高逆向成本 | **是** |
| **K** | 恶意插件/第三方组件 | 依赖其代码路径上的数据 | 其 API 表面 | 其自身状态 | 其代码（同进程） | 无进程内隔离 | **是**（同 J 逻辑：进程内代码执行） |
| **L** | 恶意 WebView/Intent 源 | 自身 WebView 内容/Intent | `webDomain`、`AssistStructure` 节点、`origin` 字符串 | 同上 | 自身进程 | 系统背书包名 + 域归属（浏览器指纹/DAL）+ 强制二次确认 | **否**（作为普通应用）；**能**，若其同时具备 IME/无障碍/root 等系统级能力 |
| **M** | 恶意自动填充客户端 | 系统下发的数据集**展示内容** | 焦点、`autofillId`、Web 表单 | 表单内容 | 自身进程 | 数据集值受 `setAuthentication` 门控，未确认不落值；且候选先经域归属校验 | **否**（不能静默取走口令；可诱导用户在确认框点「确认」） |
| **N** | 恶意 IME / 键盘 | **用户键入的一切**（含主密码、条目口令） | 输入连接、候选、`EditorInfo` | 输入内容 | 自身进程 | **应用层无有效边界**：仅 `KeyboardType.Password` 提示；`IME_FLAG_NO_PERSONALIZED_LEARNING` 无法从 Compose 公开 API 下发（ISSUE-P3-76，框架阻塞，**明示接受**） | **是**（用户在该 IME 激活状态下键入任何秘密；前提：用户已启用该 IME） |
| **O** | 离线数据库破解攻击者 | 完整 `.kdbx`（含 `.bak`） | 无（纯离线） | 无 | 任意算力 | **Argon2 KDF 参数 + 主密码熵** | **否**（除非口令弱或 KDF 参数被降级；这是唯一纯密码学边界） |

## 3.2 每个「否」的逐条论证（这是本节的核心价值）

### A / B —— 普通恶意应用（无/有 normal 权限）：**否**
1. **拿不到库文件**：`.kdbx` 位于 `context.filesDir`（`VaultLifecycleCoordinator.kt:113-118`），属应用私有目录；Android 沙箱下其他 UID 无法读取（(b)，依赖平台保证，本机未在真机验证越权读取）。
2. **绑定不了服务**：`KeePasskeyAutofillService` / `KeePasskeyCredentialProviderService` 虽 `exported=true`，但分别受 `android.permission.BIND_AUTOFILL_SERVICE` 与 `BIND_CREDENTIAL_PROVIDER_SERVICE` 保护（`AndroidManifest.xml:79,99`）——二者是系统签名权限，第三方应用无法获取（(a) 声明核实；(b) 平台语义）。
3. **伪造不了 `webDomain`**：`AssistStructure.ViewNode.webDomain` 由调用方提供（代码自身明确标注 `AutofillWebDomainPolicy.kt:58-59`），因此**不直接采信**。放行需要二者之一：
   - 「包名 + **已取证签名证书指纹**」二元组命中（`BrowserSigningFingerprints.kt:52-56`；指纹常量的冒号/无冒号两种写法确实一致——已逐字节核对：`32A2FC74…2849F` / `F0FD6C5B…0DB83`）。侧载一个占用 `com.android.chrome` 包名的 APK **签名指纹不匹配** → 降级 DAL；
   - 通过 Digital Asset Links：站点在 `https://<host>/.well-known/assetlinks.json` 中显式声明授权该包名+指纹（`DigitalAssetLinksVerifier.kt:88,145-169`）。受害者站点不会为攻击者签名。
   - 二者皆不满足 → `WebDomainAttribution.REJECTED` → `webDomain = null`（`AutofillOriginResolver.kt:54-60`）→ 只能匹配 `android://<攻击者自己的包名>` 的条目。
4. **跨应用读凭据被严格包名相等阻断**：`DomainMatcher.isPackageMatch` 为 `scheme` 剥离后**精确相等**（`DomainMatcher.kt:137-151`），显式拒绝 `evil.com.victim.app` 假冒 `com.victim.app`；跨域被 `isDomainMatch` 的点号标签边界 + PSL 可注册域下限阻断（`DomainMatcher.kt:78-85` + `PublicSuffixList.kt:51-58`）——`xexample.com` **不能**匹配 `example.com`。
5. **不能静默填充**：解锁态每个数据集强制 `setAuthentication(...)`（`AutofillDatasetBuilders.kt:208`），未获确认前框架不写入值；确认入口 `AutofillConfirmActivity` / `AutofillPickerActivity` / `BaseCredentialActivity` 全为 `exported=false`（`AndroidManifest.xml:36-73`），且确认 PendingIntent 为 `FLAG_IMMUTABLE`（`AutofillDatasetBuilders.kt:206`），攻击者无法注入 extras。
6. **CM 通道同样安全**：`request.callingAppInfo` 由系统填充；普通应用固定获得 `android:apk-key-hash:<sha256(其签名证书)>` origin（`CallingOriginResolver.kt:74-83`），因此只能命中自身包名绑定条目；浏览器委派必须通过官方 `getOrigin(白名单)`，白名单仅 `com.android.chrome`（`:33-50`）。
   **残余（非可利用，但属于真实弱点）**：确认弹窗只写「即将向当前应用填充凭据「X」」，**不含请求方包名/域**（`strings.xml:957-965`）——用户在同意时刻无法区分正常应用与恶意应用。这使 A/B 可以把「取得明文」的目标从「绕过代码」降级为「骗取一次人工确认」。这是**同意保真度**缺陷，不是代码绕过。

### C —— 恶意输入文件攻击者：**否**
1. 导入路径（Bitwarden JSON / CSV / 1Password PUX / KeePass XML）全部经 `ImportParseGuard` 归一化，解析失败归一为 `MALFORMED`（`app/.../data/importer/**`；`HardenedXmlReader` 逐轮剔除降级且 handler 侧 fail-closed，见 RESOLVED_LOG §27.3）。
2. 密钥文件不使用 XML 解析器：`KdbxKeyFile.kt:53-58` 以字符串搜索实现 **XML 分支，零 XXE 攻击面**（(a) 已核实）。
3. 解析上限：解压输出 ≤128 MiB、内层单字段 ≤64 MiB、池条目 ≤1024、池累计 ≤min(256 MiB, 上限)、HMAC 块 ≤1 MiB、XML 深度 ≤64、文本节点 ≤8 Mi 字符（证据见 TB-4c）。
4. **不能借此拿到现有库的明文**：恶意输入文件只会成为库中条目，不会解密既有内容。

### D —— 恶意 KDBX 数据库攻击者：**否**（本节最强的「不可利用」结论）
- **唯一入口**：`KdbxFile.load`（三个生产调用点：`SessionOpener.kt:146`、`ChildReadOnlySession.kt:134`、`SyncDatabaseCodec.kt:55`）。不存在第二个 KDBX 读取器（(a) 全仓核实）。
- **顺序不可绕过**：头部 SHA-256（常量时间）→ **头部 HMAC** → 才进入 `loadPayload`（`KdbxFile.kt:105-141`）。头部 HMAC 使用由主密码派生的 `hmacKey64` 生成的 `headerKey`（`KdbxFile.kt:130-133`），攻击者无主密钥无法通过。
- **即使头部恰好通过（即已知口令），载荷仍逐块认证**：每块 `HMAC-SHA256_{SHA-512(LE64(index)‖hmacKey64)}(LE64(index)‖LE32(size)‖data)`（`BlockHmac.kt:55-77`），**块索引并入密钥**（`BlockHmac.kt:65-68`）→ 不可重排、不可跨位替换、不可截断。
- **平台陷阱已被显式处理**：`javax.crypto.CipherInputStream` 会把底层 `IOException` 吞掉伪装成 EOF（两类 KDBX 异常都继承 `IOException`），代码以 `terminalValidationFailed` 记账 + `verifyEndOfStream()` 作为**权威检查点**重放失败，且该检查点消费的是**原始** HMAC 流而非被 cipher 包裹的流（`HmacBlockStream.kt:192-206,251`；调用点 `KdbxFile.kt:207`）——因此「截断文件/篡改终止块」必定 fail-closed。
- **`legacy cipherKey` 探针不是绕过**：它在头部 HMAC 已通过之后运行，只试解第 0 块，而该块的 HMAC 已先被验证（`KdbxFile.kt:164` ← `HmacBlockStream.kt:213-219`）。
- **残余（非安全）**：`KdbxCipherKeyResolver.kt:139-153` 的 `offset += FIELD_HEADER_SIZE + length` 可整型溢出为负下标抛未分类 `ArrayIndexOutOfBoundsException`；触发需头部 HMAC 已通过 + 解密前缀恰好满足 `prefix[0]∈{0..3}` 且 `length ≥ 0x7FFFFFFB`（约 2^-37/次，且仅 legacy 派生库），最终被 `SessionOpener.kt:159` 捕获为普通解锁失败。**不是绕过、不是崩溃**。

### I —— 网络攻击者（同步在途）：**否**
- **看不到明文**：全站 TLS-only（`network_security_config.xml:14-18` + `SyncHttpClientFactory.kt:26-33`），且传输体是**已加密的整库**。
- **无证书固定**（有意，`Privacy-Policy.md:71`）：**系统 CA 被攻陷**（企业 MITM、被植入的系统 CA）时攻击者可 MITM——但**仍只能看到密文**，安全性不因此下降（机密性由 KDBX 承担，不依赖 TLS）。
- **改不出可被接受的内容**：块 HMAC 由主密钥派生（TB-4b）。可做的是**重放/置换合法密文**：全局防回滚「已见内容摘要链」（`SyncRollbackGuard.kt:75-96`，状态由 Keystore HMAC 认证 `:111`）拦截「设备曾接受过的旧版本」重放；**跨路径整文件混淆仍不可拦**（已明示接受的低危残余，`docs/同步层记录级完整性威胁建模.md:79-88,109-110`）。
- **⚠ 防回滚的实际有效窗口远小于文档暗示**（新发现，实现弱于文档）：状态文件落在 `cacheDir/sync`，而 `SyncCacheEvictor.onSessionLocked()` → `SyncCache.clearAll()` 会删除该目录下**全部**子项，**包括 `*.rollback`**（`SyncCacheEvictor.kt:37-39,46-63`；`SyncCache.kt:286,306-314`）。锁库（用户手动、熄屏、后台超时、换库）之后状态归零 → `state.current == null → Accept`（`SyncRollbackGuard.kt:79`）。因此**攻击者只要等用户锁一次库再重放旧库，防回滚即失效**；`recent` 也只有 32 条且 `sequence` 虽被持久化但**从不参与裁决**（`:113,122`）。`docs/同步层记录级完整性威胁建模.md:49-55` 与 `SyncRollbackGuard.kt:44-52` 均**未声明**该「锁库即清状态」语义。
- **拒绝服务面（可控云端 / MITM 可触发，无证书固定时 MITM 可达）**：远端读取**完全没有尺寸上限**——`response.body?.bytes()`（`WebDavSyncProvider.kt:170`、`S3SyncProvider.kt:204`），`RemoteFileMetadata.contentLength` 被填充但**从不作为守卫使用**（`SyncModels.kt:20`，grep 无消费方）；KDBX 内部的解压炸弹守卫**只在下载完成之后**才生效（`SizeBoundedInputStream.kt:40`）。WebDAV 的 PROPFIND 响应体被整体读入 `String`（`WebDavSyncProvider.kt:125`）且 DOM 遍历是**递归**的、只 `catch (e: Exception)`（`WebDavPropfindParser.kt:50-59,82`）→ 超大/超深响应可致 OOM 或 `StackOverflowError`（属 `Error`，不被捕获）→ **进程崩溃**。XXE 本身已被封堵（`:35-47`，且 `isExpandEntityReferences=false` 即使特性不被支持也能阻断内部实体膨胀）。
- **内容替换（不涉及伪造）**：云端返回一份**密码学有效但内容不同**的库时，若本地无改动则被直接接受并落库（`SyncEngine.kt:169-181` → `SyncCycleRunner.kt:312`）；防回滚对**全新内容**不告警（`SyncRollbackGuard.kt:82`）。这是 Assume-Breach 同步模型的固有性质（攻击者本就有权读写该密文），**不是代码缺陷**，但它意味着「云端被攻陷 = 用户可能在无提示下接受一份空库/旧业务的其它库」。
- **SSRF/主机注入**：`SyncEndpointGuard` 在构造期拒绝 userinfo 注入、`localhost`/`.local`/`.internal`、字面内网 IP（`SyncEndpointGuard.kt:81-131`），并在**连接期**以 `SsrfGuardDns` 对解析结果逐一判定、任一命中即整体拒绝（`:223-241`，抗 DNS 重绑定）；S3 桶名严格正则使 authority 注入不可能（`:38,55-72`）。⚠ `ssrfAllowedHosts` 逃生通道在生产**未接线**（`SyncProviderResolver.kt:52,77` 恒传默认空集）→ **自建内网 NAS/WebDAV 在出厂配置下不可用**（代码注释明确「不支持自建服务器」`SyncHttpClientFactory.kt:16-18`，与 README:8「WebDAV（Nextcloud、ownCloud 等）」的常见部署形态存在张力）。

### L —— 恶意 WebView/Intent 源：**否**（作为普通应用）
- 其可控的只有 `webDomain` 与 `AssistStructure` 节点内容，而 `webDomain` 必须过归属校验（同 A/B 论证 3），节点内容只影响「填哪个框」不影响「填谁的凭据」。
- 其可控的 `origin`（CM 通道）在**非浏览器**分支被完全忽略：一律固定颁发 `android:apk-key-hash:`（`CallingOriginResolver.kt:74-83`）；浏览器分支需命中 Chrome 特权白名单（指纹级）。
- 不存在外部 `startActivity`/`ACTION_VIEW`/`ACTION_SEND`/`createChooser`（(a) 全仓 grep 无命中），因此没有「恶意 Intent 拉入应用内敏感页面」的通道；唯一 `exported=true` 的 Activity 是 `MainActivity`，且**不读任何 extra**（`MainActivity.kt:58-74`）。
- **边界之外**：若该对手同时是 IME 或拥有无障碍/root，则退化为 N/G——这不是 WebView 本身的能力。

### M —— 恶意自动填充客户端：**否**
- 数据集中的值受 `setAuthentication` 门控，**未获确认前框架不把值写入表单**（`AutofillDatasetBuilders.kt:192-209`）。
- 候选集合先经域归属校验（同上）；恶意客户端只能拿到**与自己包名严格相等**或**自己站点经 DAL 声明授权**的条目。
- 它能看到的是数据集**展示内容**（用户名/标题的 `RemoteViews`），这些本身不是口令。
- **可利用的社交工程面**（非代码绕过）：确认弹窗不指名请求方（`strings.xml:957-965`），且**手动选择器（`AutofillPickerActivity`）是全库搜索，返回任意条目，不做域匹配**——恶意应用可以弹出登录表单引导用户在「选择器」里手选银行条目。用户的显式点选被当作确认。**这是本系统最现实的口令外泄路径之一**（需要用户交互，见 §8 威胁 T-4）。

### O —— 离线数据库破解攻击者：**否**（唯一纯密码学边界）
- 攻击者得到完整 `.kdbx`（含 `.bak`），拥有任意算力。
- 必须突破：`Argon2d/id（M/I/P 由用户/文件头决定）` → 复合密钥 → 头部 HMAC。**没有任何捷径**：`cipherKey` 与 `hmacKey64` 均派生自主密码（`KdbxKeyDerivation.kt:98-127`）。
- **真实前提（会被忽略的风险）**：
  1. **默认 KDF 参数的实际强度**：`SessionOpener.create` 走 Argon2（`SessionOpener.kt:50-53`），但**具体 M/I/P 由 `KdbxHeader.createDefault` 决定**——需代码审计员确认出厂值是否达到现代密码管理器基线（本威胁模型不臆断，列入 §7 开放问题 Q-3）。
  2. **`.bak` 是旧口令加密的副本**：换密后主文件受新口令保护，但滚动备份在换密前可能已用**旧口令**加密；代码在换密**成功后**才删除 `.bak`（`DatabaseSession.kt:387-390`），因此换密**之前**的任何 `.bak` 都是「旧口令 + 同一份数据」的离线攻击目标。若用户历史口令更弱，这是**降级攻击面**。`createBackupBeforeSave` 默认 **true**（`DatabaseSession.kt:65`）。
  3. **在线暴力破解默认无节流**：`unlockThrottleEnabled = false`（`SettingsRepository.kt:53`），是 2026-09-12 的**用户明示裁决**（RESOLVED_LOG §29.2/§30.1）。这不直接帮助离线攻击者，但使用户选用的弱主密码在「设备被控制但未 root」的场景下更快被验证。

### E / F / G / H / J / K / N —— **是**（能拿到明文），前提与理由

| 对手 | 前提（prerequisites） | 为什么其余防线都无法阻止（逐条） |
|---|---|---|
| **E 物理接触** | 设备**关机或未解锁**时：**否**（只剩离线攻击 O）。设备**已解锁**（哪怕只解锁过一次且未自动锁定）时退化为 F。 | 设备解锁后 Keystore「解锁设备要求」密钥可用（`setUnlockedDeviceRequired(true)` 只在设备锁定时拒绝），自动锁定是**唯一**时间窗（默认 60 s，`RealSettingsRepository.kt:95`）。 |
| **F 设备已解锁** | ① 在自动锁定窗口内（默认 60 s；用户若选「从不」则无窗口，`AutoLockTimeoutPolicy.kt:70`）；② 通过一次用户交互（点确认）或直接读屏。 | `FlagSecurePolicy.kt:27` = `sessionLocked || userEnabled`：**解锁态若用户关闭了防截屏开关则 FLAG_SECURE 真实解除**（2026-09-12 用户裁决，RESOLVED_LOG §29.3）。此时**所有**敏感 Compose 页面（详情页明文口令、编辑页、TOTP 种子、同步凭据、导入导出）都在同一个 `MainActivity` 窗口里（(a) 已核实：不存在独立敏感 Activity）→ 可截屏/录屏/读屏无障碍抓取。 |
| **G root** | root 已获得。 | 软件边界全部失效：可读 `/proc/<pid>/mem`、可用 Frida、可调 Keystore（TEE 密钥不可导出但**可在授权窗口内调用**）。`ProtectedString` 的进程内主密钥是静态 `object` 字段且**永不擦除/永不轮换**（`InMemoryCipher.kt:62,65`，理由已文档化 `:36-44`）→ 一次 dump 即可解出全部受保护字段。`RuntimeIntegrityDetector` 只是基于文件路径/`/proc/self/maps` 的启发式，**改名/移除路径或 hook `File.exists`、`Debug.isDebuggerConnected`、`RuntimeIntegrityPolicy.evaluate` 即可完全规避**（`:100-110,116-135`；非 suspend 路径 `hookFrameworkDetected` 硬编码 false，`:69,84`）。 |
| **H 被攻陷的 OS** | 同上，且可能早于应用启动。 | 同 G，且可在应用安装时即植入；`RuntimeIntegrityDetector` 的 installer 信号只覆盖「非受信任安装来源」，对已被攻陷的系统无意义。 |
| **J 供应链 / APK patch / Frida** | 攻击者可获得 APK 并反编译/patch/注入。 | **R8 只是成本提升，不是边界**：release 仅 `isMinifyEnabled + isShrinkResources`（`app/build.gradle.kts:81-84`），保留 `LineNumberTable`（`proguard-rules.pro:18-19`），无字符串加密、无完整性自检、无反调试（仅 `Debug.isDebuggerConnected()` 一次探测）。patch 后可让 `FlagSecurePolicy` 恒返回 false、让 `RuntimeIntegrityPolicy` 恒返回 TRUSTED、直接打印 `ProtectedString.readString()`。 |
| **K 恶意第三方组件** | 依赖链中任一组件被执行（同进程）。 | 与 J 同理：**同进程即同信任域**。`proguard-rules.pro:37-39,42-43` 对 Dagger/Hilt/BouncyCastle 做整包 `-keep { *; }`，进一步降低利用难度。 |
| **N 恶意 IME** | 用户在恶意输入法处于活动状态时键入任何秘密。 | 应用层**没有**有效边界：`ACTIVE_ISSUES.md` ISSUE-P3-76 已核实全仓 `IME_FLAG_NO_PERSONALIZED_LEARNING` **零命中**，且逐项核实了 Compose 1.11.4 的 `EditorInfo` 构造链**无公开 API 可下发该位域**，参考项目 spela PR #1114 结论一致。项目**明示接受**该残余风险并给出解除条件（`:109-118`）。当前仅依赖 `KeyboardType.Password` → `TYPE_TEXT_VARIATION_PASSWORD` 的**提示性**保护（主流输入法默认不学习该 inputType）。**这是「文档诚实 ≥ 实现强度」的一个正面例子**，但结论仍是：恶意 IME 可直接获得明文。 |

---

# Deliverable 4 — 安全架构评估

## 4.1 当前架构的安全边界是什么？

存在且**真实有效**的边界有 6 条（全部 (a) 核实）：

| 边界 | 拦截的对象 | 强度 |
|---|---|---|
| **B-1 KDBX4 完整性契约**（头 SHA-256 → 头 HMAC → 块 HMAC → 终止块） | D（恶意 `.kdbx`）、I（篡改流量）、C（恶意输入文件） | **强**。密码学级，`fail-closed`，无旁路。 |
| **B-2 Argon2 KDF + 主密码熵** | O（离线破解）、E（未解锁物理接触） | **强但依赖参数与口令强度**（见 §7 Q-3 与 E 表）。 |
| **B-3 Android 沙箱 + 组件权限模型** | A、B、L、M（作为普通应用） | **强**（平台提供）。 |
| **B-4 域/包名归属绑定**（受信浏览器指纹 + DAL + PSL + 严格标签边界） | A、B、L、M 的跨应用/跨域读取 | **强**（(a) 核实多处针对具体攻击的显式修复，见 RESOLVED_LOG §22.7 ISSUE-P1-11）。 |
| **B-5 自动填充强制二次确认 + Keystore CryptoObject 绑定** | M 的静默填充 | **中强**。自动填充通道**已做密码学绑定**（`AutofillAuthBindingPolicy`）；CM 通道**未绑定**（不对称）。 |
| **B-6 TEE/StrongBox 不可导出密钥** | E（未解锁）、离线窃取 SharedPreferences | **中**。可防「拷走文件后解密」，**不能防**「进程内调用」。⚠ 软 Keystore 落位仅告警不硬失败（`KeystoreKeyMaterial.kt:119-127`）→ **fail-open**。 |

## 4.2 最重要的信任假设（违反后的后果）

| # | 信任假设 | 若违反 |
|---|---|---|
| **TA-1** | **「应用进程内的代码都是受信的」** | 全系统崩溃式失效：主密码、解密树、进程内 `ProtectedString` 主密钥同处一堆。→ G/H/J/K 全部成功。 |
| **TA-2** | **「Android 沙箱 + TEE 是真的」** | `Keystore` 密钥可导出、`filesDir` 可被其他 UID 读 → B-3/B-6 归零，退化为普通文件加密方案。 |
| **TA-3** | **「用户主密码有足够熵」** | 唯一纯密码学边界失效；`.kdbx`（尤其 `.bak`）可能被离线解开。 |
| **TA-4** | **「用户会看清确认框再点确认」** | 自动填充/选择器的确认门控退化为形式。**确认框不显示请求方**（`strings.xml:957-965`）使该假设**更脆弱**。 |
| **TA-5** | **「设备未被解锁/未被攻击者短暂持有」** | FLAG_SECURE 的解锁态语义取决于用户开关；自动锁定默认 60 s；窗口期内所有明文可见。 |
| **TA-6** | **「KDBX 对象树整体驻留内存是唯一可行实现」**（`AGENTS.md` §6 已接受） | 内存驻留 = 内存 dump 可得（与 TA-1 同源）。项目已把**附件字节**移出（RESOLVED_LOG §35），但对象树仍在。 |
| **TA-7** | **「云端只做存储，不参与信任」** | 架构上成立；但**跨路径整文件混淆**不可拦（已明示接受）。 |
| **TA-8** | **「用户不需要输入法保护」**（ISSUE-P3-76 明示接受） | 恶意 IME 取得全部键入秘密。 |

## 4.3 最危险的设计决策

**「把整个信任域收敛到单一应用进程，并在该进程内长期持有明文与进程级对称密钥。」**

具体证据链：
- 进程级 `InMemoryCipher.encKey/eqKey` 是 `internal object` 的明文静态字段，**永不擦除、永不轮换**（`InMemoryCipher.kt:62,65`），且项目**已论证并接受**无法轮换（`:36-44`：`SyncCoordinator` 持有整棵树的快照，换钥会导致静默数据破坏）。
- `DatabaseSession.lock()` 只清凭据缓存与对象树引用（`DatabaseSession.kt:325-333`），**不清** KDF `secret(K)`/`A`（`KdfParameters.kt:35-36` 随 header 存活到会话对象被 GC），也**不清** `InMemoryCipher` 的密钥。
- 因此「锁定」在密码学意义上**不是擦除**，而是「丢弃引用 + 期待 GC」。这是**已文档化的取舍**（`AGENTS.md` §6：「`ProtectedString` 驻留加密为纵深防御层；持有进程密钥或任意代码执行者仍可在读取瞬间截获明文」），诚实但危险。

**为什么它比「缺少某功能」更危险**：它把 J/K/G/H/N 五类对手的结论从「困难」变成「一步到位」，并且它不是可以「补一个开关」修好的问题——它是原语选择（单进程密码管理器）的必然结果。

## 4.4 最有价值、值得保留的设计决策

**KDBX4 单体流的「先验后用 + 平台陷阱显式处理」完整性契约。**

- 头 HMAC 在**任何载荷解密之前**（`KdbxFile.kt:137` → `:141`）；
- 块 HMAC 把**块索引并入密钥**（`BlockHmac.kt:65-68`），从结构上消灭块级重排/替换；
- 终止块有**权威检查点**，并且**专门处理了 `CipherInputStream` 吞 `IOException` 伪装 EOF 的平台陷阱**（`HmacBlockStream.kt:145-151,192-206`）——这不是照抄参考项目，而是踩过坑后的加固（TASK-01 回归锁）；
- 该契约使「恶意 KDBX 文件」「不可信云」「恶意导入文件」三类**不需要任何用户交互**的对手全部失效——这是本产品**最大的安全资产**。
- 同类值得保留的：**`ExportConfirmationPolicy` 的 fail-closed 明文导出门控**（`SettingsExportController.kt:224-265`）、**`allowBackup=false` + 全域排除**（`AndroidManifest.xml:18-19` + `data_extraction_rules.xml`）、**通知零用户数据**（`TotpNotificationPublisher.kt:58-75`）。

## 4.5 最大单点失效

**主密码 → 唯一凭据（默认无第二因子），且在线破解默认不节流。**

- 默认建库路径仅要求主密码（`SessionOpener.create(file, name, passwordChars, useArgon2 = true)`，`SessionOpener.kt:41-46`；密钥文件为可选 `keyFileData = null`）。
- `unlockThrottleEnabled = false` 出厂默认（`SettingsRepository.kt:53`；2026-09-12 用户裁决）。
- **不是单点失效但加剧它**：`.bak` 保留旧口令加密的历史副本（`DatabaseSession.kt:65,387-390`）。
- 与之并列的第二个单点：**`InMemoryCipher` 的进程级密钥**（见 4.3）。

## 4.6 条件化生存性分析

### (a) 若攻击者取得 root，哪些安全保证**仍然成立**？
| 仍成立 | 证据 |
|---|---|
| **TEE/StrongBox 密钥不可导出**（无法把 Keystore 密钥拷走离线使用） | `KeystoreKeyMaterial.kt:82-128`（(a) spec；「不可导出」是官方 TEE 语义，(b)） |
| **`.kdbx` 的静态加密**（root 也只能拿到密文） | `KdbxFile.kt:316-341` |
| **KDBX4 完整性的 fail-closed 语义**（root 改文件仍会被 HMAC 检出；但 root 可以直接读内存，故仅对「离线篡改后回归」场景有意义） | `KdbxFile.kt:114,137`；`HmacBlockStream.kt:228-268` |
| **生物识别封印的「录入变更即吊销」**（增删指纹使快速解锁密钥永久失效） | `KeystoreKeyMaterial.kt:103,238-242` |
**不再成立**：`ProtectedString` 驻留加密（进程密钥可读）、FLAG_SECURE（可截屏）、`RuntimeIntegrityDetector`（可 hook）、自动锁定（可直接读内存）、剪贴板擦除（可直接监听）。

### (b) 若攻击者取得 APK（可反编译/patch/Frida），哪些保证**仍然成立**？
- **仍然成立**：KDBX 静态加密强度（不依赖代码保密）；TEE 密钥不可导出；服务端侧无任何秘密可偷（纯本地应用）。
- **不再成立**：`RuntimeIntegrityDetector` 全部结论；`FlagSecurePolicy`；`AutofillAuthBindingPolicy`（可 patch 为 `return true`）；`CredentialFillVerifier`；`UnlockThrottle` 与其 MAC 校验；`ExportConfirmationPolicy`。**R8 只是成本提升**——`app/build.gradle.kts:81-84` + `proguard-rules.pro`，明确保留行号（`:18-19`），无字符串加密/无完整性自检。

### (c) 若攻击者取得 KDBX 文件，哪些保证**仍然成立**？
- **全部机密性与完整性的密码学保证仍然成立**——前提是主密码有足够熵且 KDF 参数足够（TA-3）。攻击者**不能**：读出条目、伪造条目（块 HMAC）、重排块、截断（终止块）。
- **可以**：离线暴力破解（唯一目标）；**重放整份旧文件**到同步路径（若攻击者同时控制云端）——受防回滚链拦截（`SyncRollbackGuard.kt:75-96`），但**跨路径混淆**不拦（已接受）。
- **`.bak` 使攻击面扩大**到「曾经用过的任何主密码」（见 4.5）。

### (d) 若攻击者能控制应用进程，哪些保证**仍然成立**？
- **仍然成立（仅此两项）**：① TEE 内密钥不可导出；② 加解密操作仍受 Keystore `KeyGenParameterSpec` 约束（例如 `biometric_master_key_<dbId>` 的 per-op 强生物识别在**未授权时**无法 `doFinal`）。
- **不再成立（其余全部）**：主密码明文（`SessionCredentialCache` 可读）、`ProtectedString` 全部明文（进程密钥可读）、解密对象树、`FlagSecurePolicy`、`AutofillAuthBindingPolicy`、节流完整性、运行时完整性。**注意**：即便密钥不可导出，攻击者也能在**合法读取瞬间**截获明文——项目已如实声明（`InMemoryCipher.kt:31-34`、`AGENTS.md` §6）。

### (e) 若攻击者只能控制一个恶意 KDBX 文件，哪些保证**仍然成立**？
- **全部保证仍然成立**。这是本架构最强的结论：
  - 无法通过头部认证（无主密钥）→ 连 `loadPayload` 都进不去（`KdbxFile.kt:137`）。
  - 无法构造可通过块 HMAC 的载荷（索引并入密钥）→ 无法注入条目、无法重排、无法截断。
  - 无法借解析器实现任意代码执行：SAX 流式解析、XXE 四特性 + handler 侧独立 fail-closed（externel entity / DTD 直接抛）、深度与文本长度上限、VariantDictionary 长度前置校验、`LittleEndianUtil.readBytes` 拒绝负数/超长（证据见 TB-4c）。
  - 唯一可达的**非安全**影响是有界 DoS：Argon2 内存上限放到 **4 GiB**（`KdbxKdfParameterCodec.kt:25,106-126`）——攻击者投喂一个 `M=4GiB` 的库会让解锁尝试掉入内存分配失败/极慢路径。项目已把该缺口登记为「纵深防御缺口当前不可达」（RESOLVED_LOG §22.2 子项 5）。

---

# Deliverable 5 — 「看起来安全但不一定」逐项裁定

| 假设 | 本项目是否**赚到**了该假设？ | 证据 |
|---|---|---|
| **用 AES ≠ 安全** | **赚到（在正确用法范围内）。** AES-256-CBC + PKCS#5/7；AES-ECB **仅**出现在 KDBX 规范指定的 AES-KDF 变换中且有 lint 抑制理由（`AesKdfJce.kt:25-33`）。IV 每次保存由 `SecureRandom` 重新生成且长度取自算法（`KdbxFile.kt:289-294`）；无固定 IV、无 ECB 误用、无 `java.util.Random`（全仓仅 1 处测试 import）。CBC 无内置认证，但**整条载荷被块 HMAC 覆盖**（TB-4b）→ 不是裸 CBC。 | `AesCipherEngine.kt:20-70`；`KdbxFile.kt:289-294,352-354`；`AesKdfJce.kt:25-33` |
| **用 Argon2 ≠ 安全** | **部分赚到。** Argon2d/Argon2id 正确接线（`KdfFactory.kt:13-22`），版本仅 0x10/0x13，参数上界有界（`KdbxKdfParameterCodec.kt:106-132`），原生/兜底两条路径都有真实探活与差分等价测试（`NativeAesKdf.kt:34-55`、`NativeTwofish.kt:34-57`）。**未赚到的部分**：出厂 `M/I/P` 的具体值需审计确认是否达现代基线（§7 Q-3）；`secret(K)`/`A` 随会话长期驻留且从不擦除（`KdfParameters.kt:35-36`、`KdbxFile.kt:302-306`）。**「用了 Argon2」本身不等于抗爆破。** | 同上 |
| **用 Android Keystore ≠ 自动安全** | **部分赚到。** 规格确实很讲究：per-op 强生物识别、`setInvalidatedByBiometricEnrollment(true)`、`setUnlockedDeviceRequired(true)`、StrongBox 优先、`KeyInfo.userAuthenticationType` **全等**探测迁移（防止旧的 `BIOMETRIC_STRONG\|DEVICE_CREDENTIAL` 密钥继续可用——这是有实际意义的修复，`KeystoreKeyMaterial.kt:162-177`）。**未赚到的部分**：① 软 Keystore 落位**只告警不硬失败**（`:119-127`，为兼容模拟器/CI）→ fail-open；② HMAC 类密钥（节流完整性、字段签名、解锁通行密钥完整性）**无 `setUnlockedDeviceRequired`、无用户认证**（`:307-331`、`UnlockThrottleIntegrity.kt:72-83`、`KeystoreHmacFieldSignatureSource.kt:65-81`）——同 UID 任意代码可调用它们重算 MAC，KDoc 亦如实声明（`BiometricCredentialStorage.kt:128-131`）。 | 同上 |
| **用 Rust ≠ 自动内存安全** | **赚到（就内存安全而言）。** `catch_unwind` 包裹每个导出（`jni_bridge.rs:79,119-123`）、`panic="abort"` 刻意未设（`rust/Cargo.toml:52-53`）、`Zeroizing` 全路径擦除、signed-before-narrowing 防负数回绕（`jni_bridge.rs:29-49`）。**但**：秘密仍会跨 FFI 复制（Rust 侧擦除、Kotlin 侧部分擦除），且 `CbcStreams` 在整个流生命周期内**保留调用方的 key 数组引用**（`CbcStreams.kt:31,45,139,154`）——顺序上安全（`KdbxFile.kt:143-144` 在流关闭后擦），但是**脆弱的不变式**。 | 同上 |
| **用 Kotlin ≠ 自动安全** | **未赚到——Kotlin 甚至更危险。** `String` 不可变且不可擦除是本项目**主动对抗**的对象，工程规则要求 CharArray/ByteArray 显式清零（`.codebuddy/rules/engineering-rules.md:31-32`）。**实测缺口**：① `KdbxKeyFile.kt:40-50` 把密钥文件第二因子（含 64-hex / Base64 秘密）物化为**不可擦除的 `String`**；② `KdbxXmlStringNode.kt:50-51` 把解密后的受保护明文字节交给**主构造函数**（借用语义，不擦除）→ **每个受保护字段泄漏一份明文副本给 GC**（`ProtectedString.kt:40-51,66-69` vs 自有通道 `:71-77`）；③ `UnlockViewModel.kt:293` 把 `activeDb=$dbId`、`keyFileLen=...`、`err=${result.message}` 写入调试缓冲（库文件名/路径进入可导出日志）。**这些都是「用了 Kotlin」不会自动避免的。** | 同上 |
| **用生物识别 ≠ 安全** | **部分赚到，但存在通道不对称。** 快速解锁：密码学绑定到位（AES-GCM per-op + CryptoObject + `Success && cipher != null`）。**自动填充**：已绑定（`AutofillAuthBindingPolicy.kt:20-21`）。**CM/Passkey 通道：未绑定**——`CredentialVerificationLauncher.kt:43-62` **不传 Cipher**，`BiometricAuthManager.kt:165-169` 走无 CryptoObject 重载，`CredentialFillVerifier.isSatisfied` 仅凭 `BiometricSucceeded` 放行（`:85-96`）。因此该通道的「验证」只是 UI 回调契约（可被 Frida/hook 伪造），而自动填充通道是密码学绑定。**开发者已诚实地解释了为何不给 entry 挂 `BiometricPromptData`**（`CredentialFillVerifier.kt:52-57`：库未暴露读取入口，挂了反而制造「看起来已验证」的假象）——这是好判断，但结论仍是「CM 通道弱于自动填充通道」。另：设备**无**强生物识别时两条通道都退化为「受保护窗口内手动确认」（`:64-79`）——降级的是手段，不是免验证。 | 同上 |
| **`FLAG_SECURE` ≠ 全部截屏都被阻止** | **项目自己就承认了这一点。** ① 官方明确 FLAG_SECURE **不覆盖 overlay 攻击面**，故补 `setHideOverlayWindows(true)` + `HIDE_OVERLAY_WINDOWS` 权限（`AndroidManifest.xml:7-11`）；② 锁定态无条件强制，**解锁态随用户开关真实解除**（`FlagSecurePolicy.kt:24-27`，2026-09-12 语义修订）；③ 所有敏感页面共用 `MainActivity` 单窗口，因此「开关关闭」时**全部**敏感页面同时失去保护（(a)：不存在独立敏感 Activity）；④ `SecureCaptureActivity`（TOTP 扫码）**缺遮挡触摸过滤**（`SecureCaptureActivity.kt:24-28`，无 `filterTouchesWhenObscured`）——唯一真实的接线缺口。 | 同上 |
| **ProGuard/R8 ≠ 防逆向** | **项目未声称能防，实现也未做到。** release 只有 `isMinifyEnabled + isShrinkResources`（`app/build.gradle.kts:81-84`）；`-keepattributes SourceFile,LineNumberTable` + `-renamesourcefileattribute SourceFile`（`proguard-rules.pro:18-19`，**主动保留行号以保可运维性**，取舍已写明）；Dagger/Hilt/BouncyCastle/ZXing/credentials 整包 `-keep { *; }`（`:37-39,42-43,102-105,109,131-132`）；无字符串加密、无完整性自检、无反调试。结论：**逆向与 patch 难度 ≈ 普通加固后的 Android 应用**。 | 同上 |
| **ChaCha20-Poly1305 ≠ 实现正确** | **本项目根本没有 ChaCha20-Poly1305。** 实现是 **raw ChaCha20（ChaCha7539，RFC 7539）12 字节 nonce**，完整性由独立的 HMAC 块流承担（这正是 KDBX4 规范）。**但 UI/设置页把它标签为「ChaCha20-Poly1305 (256-bit)」**（`SettingsPreferencesController.kt:36`、`SettingsUiState.kt:69`、`DatabaseAlgorithmDialogs.kt:42`）→ **面向用户的事实性错误**（不是密码学弱化，因为 HMAC 块流仍在，但用户被告知了错误的算法）。另有 P0-4 修复痕迹：曾把 16 字节 IV 静默截断为 12，现已 fail-fast（`ChaCha20CipherEngine.kt:70-83`）。 | 同上 |
| **成熟密码学 crate ≠ 用对了** | **大体赚到，但有真实落差。** 用对口：IV/nonce 长度硬校验（`ChaCha20CipherEngine.kt:76-83`）、`Pkcs7` 单一实现并被整型+流式共用、差分等价测试（native vs BC/JCE）、KAT 向量。**落差**：① RustCrypto Argon2 的 `AD ≤ 32B` 上限使超长 AD **静默改走 BouncyCastle**（`Argon2KdfEngine.kt:47-52`）——行为等价，但意味着「原生路径」并非覆盖全部输入；② `AesKdfJce.kt:37` 忽略 `Cipher.update(...)` 的返回值（对 `AES/ECB/NoPadding` 32 字节在 SunJCE/BC 上正确，但属 provider 依赖）；③ `KdfParameters.Argon2.equals/hashCode` **忽略 `secretKey`/`associatedData`**（`KdfParameters.kt:51-71`）、`KdbxHeader.equals` 忽略 `publicCustomData`（`KdbxHeader.kt:29-53`）——当前无生产代码用头部相等性做密码学门控（(a) grep 无命中），属**潜伏隐患**而非漏洞。 | 同上 |
| **实现 KeePass/KDBX 格式 ≠ 继承 KeePass 的安全属性** | **未完全继承——项目自己就发现并修了两个「本地自读自写永远通过」的结构性掩盖缺陷。** ① P0-4：写侧恒写 16 字节 IV，ChaCha20 读侧对称截断为 12 → 自读自写往返永远通过，但**官方客户端打不开**（`ChaCha20CipherEngine.kt:70-74`，已修）；② ISSUE-P1-13：写侧 Argon2 `P` 用 UInt64 违反 KDBX4 规范，且「host JVM 宽容读侧回读」掩盖了它，最终靠 `keepassxc-cli` 官方实现才暴露（RESOLVED_LOG §27.1/§27.10）。**教训**：格式兼容性必须由**外部官方实现当裁判**，自测往返不具有证据力。此外还有两处「JVM 过、Android 挂」的致命缺陷（RESOLVED_LOG §24 KDBX XML 全量失败、§26 字段引用正则 ICU 非法致崩），说明「实现规范」不等于「在目标平台正确」。**残余**：arm64 真机 + 真实语料端到端仍缺（ISSUE-P3-23，产品裁决不排期）；KDBX v3 及以下明确拒绝（README:7）。 | 同上 |

---

# Deliverable 6 — 组件生命周期分析

## 6.1 密钥生命周期（Key lifecycle）

```
[1] 进程启动
     └─ InMemoryCipher.<clinit>：SecureRandom 32B 主密钥 → HMAC 域分离派生 encKey/eqKey
        → 主密钥 Arrays.fill(master,0) 清零               InMemoryCipher.kt:67-75
        ⚠ encKey/eqKey 为 internal object 明文静态字段，**此后永不擦除、永不轮换**  :62,65
          （理由：ProtectedString 实例可能存活于会话树之外，换钥=静默数据破坏  :36-44）

[2] 用户键入主密码
     └─ CharArray 直达（永不 String）
        UI：ui/components/SecurePasswordField.kt（唯一 CharArray 桥接点）
        UnlockViewModel.onPasswordChangeSecure(CharArray)                     UnlockViewModel.kt:188

[3] 解锁尝试
     └─ 节流闸门 gate(dbId)                                                   UnlockThrottle.kt:257-281
        └─ 记录完整性失效 → 恒 fail-closed（不受开关影响）                      :262-272
        └─ ⚠ 开关关闭（出厂默认）→ 直接放行 :274
     └─ 空密码且无密钥文件 → 拒绝                                              UnlockViewModel.kt:249-252

[4] KDF 派生（database/crypto）
     passwordBytes → SHA-256 → compositeKey → KDF transform → transformedKey
     → cmpKey(65B) → cipherKey(32B) / hmacKey64(64B)
     每个中间量在 finally/紧随其后清零：                                        KdbxKeyDerivation.kt:52-127
       passwordBytes :56,89   passwordHash :69,93   keyFileKey :66,79
       compositeKey :100      transformedKey :106   cmpKey :124   cipherKeyBytes :120
     └─ 使用后：cipherKey/hmacKey64 在 load/save 的 finally 清零                 KdbxFile.kt:143-144,338-339

[5] 会话期驻留
     └─ 主密码：SessionCredentialCache.passwordCache（克隆）                    SessionCredentialCache.kt:47-50
     └─ 密钥文件：keyFileCache（克隆）                                          :53-58
     └─ ⚠ KDF secret(K)/associatedData(A)：随 header.kdfParameters 存活，
          锁定/关闭**均不清除**，且每次保存重新写回                             KdfParameters.kt:35-36；KdbxFile.kt:302-306

[6] 快速解锁封印（可选）
     └─ BiometricSealedPayloadCodec.encode(passwordChars, keyFileBytes)        unlock/BiometricSealedPayloadCodec.kt:58
     └─ Keystore AES-GCM per-op 强生物识别密钥加密 → SharedPreferences(Base64)  BiometricCredentialStorage.kt:78-85
     └─ 明文因子副本用毕 fill(0)                                                BiometricEnrollmentCoordinator.kt:100-104

[7] 换密（changeCredentials）
     └─ rotateCredentials：旧 passwordCache 先 fill('0') 再置新                  SessionCredentialCache.kt:64-71
     └─ 失败回滚（restoreCredentials）                                          :74-79
     └─ 成功后删除滚动备份（防旧口令仍可解 .bak）                                DatabaseSession.kt:387-390
     ⚠ 本次写盘**可能已生成用旧凭据加密的 .bak**；历史遗留 .bak 同理            AtomicFileWriter.kt:134-168

[8] 锁定 / 关闭
     └─ credentials.clear()：passwordCache/keyFileCache fill 后置 null           SessionCredentialCache.kt:82-96
     └─ database.clearSensitiveData()：条目受保护值密文+IV+标签清零             KdbxEntry.kt:56-61
     └─ 通知 SessionLockObserver（附件缓存、同步缓存、防回滚状态）                 DatabaseSession.kt:332,348
     ⚠ InMemoryCipher 密钥、KDF secret/AD、内存附件池**均不清除**
```

## 6.2 数据生命周期

| 阶段 | 数据形态 | 落点 | 擦除/清理 | 证据 |
|---|---|---|---|---|
| 输入 | `CharArray` | 仅堆 | `SecurePasswordField` wipeToken / ViewModel 清零 | `UnlockViewModel.kt:335-341` |
| 派生 | `ByteArray` 中间量 | 仅堆 | 逐项 `Arrays.fill` | `KdbxKeyDerivation.kt:52-127` |
| 解析 | 对象树 + `ProtectedString`（**堆内密文**） | 堆 | 锁定时 `clear()` | `ProtectedString.kt:158-166`；`DatabaseSession.kt:328,341` |
| 附件 ≤1 MiB | 池内明文 `ByteArray` | 堆 | ⚠ **无擦除 API**，仅随引用丢弃 | `KdbxDatabase.kt:46-48`（缺口） |
| 附件 >1 MiB | 明文文件 | `cacheDir/attachments`（0600/0700） | 锁定即 `clearAll()` | `FileBinaryStore.kt:32,61-65` |
| 同步缓存 | **完整 `.kdbx` 密文快照** | `cacheDir/sync`（0600/0700） | 锁定即清（`SyncCacheEvictor`） | `SyncCache.kt:280-314`；`DatabaseModule.kt:37-42` |
| 保存写入 | 密文（tmp → .bak → 原子替换 → 目录 fsync） | `filesDir` | 序列化缓冲写毕 `fill(0)`（writer 抛异常时**跳过**） | `AtomicFileWriter.kt:65-112`；`DatabaseSession.kt:224-226` |
| 导出 | **明文** XML / CSV / 附件 / 密钥文件 | 用户 SAF 目标 | 取消/失败清理空文档（`SafDocumentCleanup`） | `SettingsExportController.kt:224-265`；`SafDocumentCleanup.kt` |

## 6.3 认证生命周期

```
无已登记凭据 ──(主密码完整解锁成功)──> 登记封印（仅强生物识别可用时；UnlockAuthPolicy.canSeal :63-65）
     │                                          │
     │                                          ▼
     │                             BiometricPrompt(BIOMETRIC_STRONG) 授权加密 Cipher
     │                                          │
     │                                          ▼
     │                             AES-GCM 封印（密码 ⊕ 可选密钥文件）→ prefs
     │
     ├──(下次启动)──> prepareDecryptCipher(alias, iv)  ← KeyPermanentlyInvalidated 时
     │                    │                             删除密钥 + 清凭据 + 回落主密码
     │                    ▼                             （KeystoreKeyMaterial.kt:228-243）
     │               BiometricPrompt(BIOMETRIC_STRONG, CryptoObject)
     │                    │
     │              Success(cipher) ──> doFinal 解封
     │                    │                 │
     │                    │                 ├─ 失败 → 清陈旧凭据，下次主密码解锁重新封印
     │                    │                 │        （BiometricUnlockCoordinator.kt:290-305）
     │                    │                 ▼
     │                    │          verifyUnlockPasskey（随机 challenge + ES256 断言 +
     │                    │            signCount 严格单调；记录缺失/被删/验签失败一律 fail-closed）
     │                    │                 │           UnlockPasskeyManager.kt:221-251
     │                    │                 ▼
     │                    └────> unlockActiveDatabase(password, keyFileData) → UnlockSuccess
     │
     └──(指纹增删)──> setInvalidatedByBiometricEnrollment(true) 生效 → 密钥永久失效
                        + setUnlockedDeviceRequired(true) → 设备锁定时不可用
```
- **门控唯一语义声明点**：`UnlockAuthPolicy.kt:31-65`（`USE_DEVICE_CREDENTIAL = false` → 仅 `AUTH_BIOMETRIC_STRONG`）→ 从结构上杜绝「锁屏弱 PIN 解封」与「含 DEVICE_CREDENTIAL 的密钥被系统忽略录入失效标志」两个缺陷（ISSUE-P1-08）。
- **CM/Passkey 通道的认证生命周期**（`CredentialVerificationLauncher.kt:43-79`）：`BIOMETRIC` 要求 → 成功即放行（**未做 CryptoObject 绑定**）；无认证器 → `MANUAL_CONFIRMATION`（受保护窗口内显式点选，**降级手段而非免验证**，`:64-79`）。

## 6.4 数据库生命周期
`CLOSED ↔ LOCKED ↔ OPENED ↔ DIRTY`（`DatabaseSession.kt:40-45`）；只读是**正交标志** `core.readOnlyMode`（`:84-85,152,179-184`），**不是状态**。

### 6.4.1 状态迁移与每个迁移点上秘密的处置（逐条核实）

| 迁移 | 触发 | 被**真实清零**的东西 | **未被清零**的东西 | 证据 |
|---|---|---|---|---|
| `CLOSED → OPENED` | `create()` / `open()` / `openStream()` 成功 | — | — | `SessionOpener.kt:90,157` |
| `OPENED → DIRTY` | 任何内容变更 | copy-on-write **被取代**的旧实例按身份集合定点擦除（`clearSupersededSensitiveData`） | **删除路径刻意不擦除**（无替换树，怕误伤共享实例）→ 被删条目的 `ProtectedString` 仅丢给 GC | `SessionContentMutations.kt:32,67,81,111`；删除路径 `:42-48,92-94,124-126`；`KdbxGroup.kt:101-106` |
| `DIRTY → OPENED` | `save()` 成功 | `serialized.fill(0)`（**只是返回的那个数组**） | ⚠ 产生它的 `ByteArrayOutputStream` **内部缓冲**（第二份完整序列化密文）**从不擦除**；写入抛异常时 `fill(0)` 被跳过 | `DatabaseSession.kt:219-226,229`；`exportToBytes` 同型 `:305-307` |
| `OPENED/DIRTY → LOCKED` | `lock()` | ① `credentials.clear()` → 主密码/密钥文件 `Arrays.fill` 后置 null；② `database.clearSensitiveData()` → 递归 `ProtectedString.clear()` = `data/iv/tag` 全 `fill(0)`；③ 通知**全部** `SessionLockObserver`（同步缓存、附件缓存、子库会话、`SyncCoordinator` 的树外快照） | ⚠ `InMemoryCipher` 进程密钥；⚠ KDF `secret(K)`/`A`（随 header）；⚠ 内存附件池（无擦除 API）；⚠ `.bak` 密文副本；⚠ ViewModel 内明文 | `DatabaseSession.kt:325-333,110-119`；`SessionCredentialCache.kt:82-96`；`KdbxEntry.kt:56-61`；`ProtectedString.kt:158-166`；`KdbxDatabase.kt:46-48` |
| `→ CLOSED` | `close()` | 同 LOCKED，另清文件/路径/写通道 | 同上 | `DatabaseSession.kt:338-349` |
| **`OPENED → OPENED`（换库/建新库）** | `SessionOpener.create()` / `openStream()` 再次成功 | — | ⚠ **旧库对象树被直接替换 `core.database.value`，既不 `clearSensitiveData()` 也不通知锁观察者** → 旧库全部 `ProtectedString` 缓冲滞留堆中等待 GC，且旧库的同步/附件缓存**不被驱逐** | `SessionOpener.kt:89,149-157`；`:73-90`（create） |

**结论**：**「锁定」在可达对象范围内是真实的数组清零，不是单纯丢引用**——这一点项目做得比大多数实现好，且 `ProtectedString.clear()` 对共享 `EMPTY` 单例故意 no-op（`ProtectedString.kt:158-159`）。但存在上表三处明确的「引用替换而非擦除」缺口，其中**换库路径**（第 6 行）是本次审计新发现、且**不受锁观察者保护**。

### 6.4.2 崩溃安全
- 原子替换 + 五处目录 fsync；降级分支**拒绝无保护覆盖**（`AtomicFileWriter.kt:188-223`）；异常路径删 tmp、绝不损坏原文件（`:105-111`）。
- 进程崩溃/被 kill 时**没有任何清理钩子**：`cacheDir/sync` 密文快照、`cacheDir/attachments` **明文附件**、`.bak`、常驻通知全部存活到「下一次锁定」或下次冷启动的通知撤销（`UnlockedNotificationController.kt:61-64`）。
- **「彻底退出应用」不清缓存**：`exitProcess` 路径（`KeePasskeyApp.kt:57-69`、`AppTerminationPolicy.kt:29-32`）不触发 `SessionLockObserver` → 明文附件与 `.bak` 留在磁盘上。用户以为「退出即安全」，实际不是——这是**产品语义与安全语义不一致**（新发现）。

## 6.5 应用 / Android 生命周期

| 场景 | 行为 | 证据 |
|---|---|---|
| **锁屏（熄屏）** | `ACTION_SCREEN_OFF` 广播（`RECEIVER_NOT_EXPORTED`，防伪造广播触发 DoS）→ `lockOnScreenOff()`；`lockWhenScreenOff \|\| autoLockBackground` 任一开启即锁 | `AutoLockManager.kt:62-70,88-97`；`AutoLockSessionGuard.kt:43-48` |
| **后台** | `onStop` 时**即按当前超时值调度延迟锁定任务**（不再等回前台）；`onStart` 取消定时器并做补偿判定；设置变更响应式重排 | `AutoLockManager.kt:110-149`；`AutoLockSessionGuard.kt:60-80` |
| **进程被杀** | 内存全部失效；常驻通知由 `UnlockedNotificationController.start()` 在冷启动**先无条件撤销一次**，清除「已锁定却显示已解锁」的失真状态 | `UnlockedNotificationController.kt:57-70` |
| **恢复（冷启动）** | `MainApplication.onCreate` 注册自动锁定守卫与常驻通知（覆盖 Autofill/Credential 不经 MainActivity 的冷启动入口） | `AutoLockManager.kt:72-82` |
| **进程终止 / OOM** | `onTrimMemory`/`onLowMemory` **未实现**（(a) 无覆盖）。秘密仅随进程消失；无额外擦除 | — |
| **崩溃** | `save()` 的 `writeAtomic` 保证原文件不被破坏；但**序列化缓冲的 `fill(0)` 被跳过**（异常路径 `DatabaseSession.kt:229`）——内容为密文，影响有限 | `DatabaseSession.kt:224-233` |
| **配置变更（旋转）** | `DatabaseSession`/`AutoLockManager` 为 `@Singleton`，**不随 Activity 重建销毁**（TASK-22 修复过单例误销毁，RESOLVED_LOG §1 TASK-22） | `DatabaseSession.kt:31`；`AutoLockManager.kt:42` |
| **`savedInstanceState` / Bundle** | **未发现任何把明文写入 Bundle/`onSaveInstanceState` 的路径**（(a)：敏感 Activity 无 `putExtra` 写秘密；跨组件只传标识与 PII-free 状态）。`ConfigChange` 下 Compose 状态由 `rememberSaveable` 承担——**是否可能承载口令字符未逐点核实**（(c)，列入 §7 Q-8） | — |
| **异常路径（统一）** | 对外回调一律用预定义用户文案，禁止透传 `t.message`（ISSUE-P1-10）；`AppLog.e/w` 在 release 只保留异常类名 | `AppLog.kt:44-55`；`KeePasskeyAutofillService.kt:102,234,294,309` |
| **锁定后的导航** | 锁库事件 → 导航强退解锁页；守卫用 `rememberUpdatedState(currentRoute)` 取实时路由（修复过「已在解锁页仍 `popUpTo(0)` 清空已输入主密码」） | `KeePasskeyApp.kt`（RESOLVED_LOG §28.3） |

---

# 7. 代码审计员的开放问题（最高风险，只能靠代码评审回答）

| # | 问题 | 为什么高风险 | 建议核查落点 |
|---|---|---|---|
| **Q-1** | **CM/Passkey 通道为何不消费 `RuntimeIntegrityGate`？** 是有意还是遗漏？在 COMPROMISED 设备上，「Android 16+ 主通道」仍会下发密码与签发断言——与 `RuntimeIntegrityPolicy.kt:47` 自述的「禁用自动填充下发」语义不符。 | 这是策略与实现的不一致（fail-open）。若为有意，需给出理由并更新策略自述；若为遗漏，是一处可直接修的真实缺口。 | `passkey/KeePasskeyCredentialProviderService.kt`、`passkey/CredentialResponseAssembler.kt`（全仓 grep：`RuntimeIntegrityGate` 仅 2 个消费方） |
| **Q-2** | **CM 通道的 BiometricPrompt 未做 CryptoObject 绑定是否可接受？** 自动填充通道已绑定（`AutofillAuthBindingPolicy`），CM 通道仅凭 `BiometricSucceeded` 放行（`CredentialFillVerifier.kt:85-96`）。 | 同一产品内两条凭据通道的安全等级不一致。虽然开发者已解释 `BiometricPromptData` 的库限制，但**自动填充通道证明了绑定是可行的**——为什么不能复用 `prepareAutofillAuthCipher()`？ | `passkey/CredentialVerificationLauncher.kt:43-62` vs `autofill/AutofillConfirmActivity.kt:88-107` |
| **Q-3** | **出厂 Argon2 `M/I/P` 的实际取值是多少？是否达现代基线？** 本威胁模型未臆断。 | 这是**唯一**纯密码学边界的强度参数。若出厂值偏低（例如 M < 64 MiB），离线攻击成本显著下降，且用户无感知。 | `KdbxHeader.createDefault(...)` 的 `KdfParameters.Argon2` 构造；`KdfBenchmark.kt:43-58` 的边界常量；`SessionOpener.kt:50-53` |
| **Q-4** | **`KdbxXmlStringNode.kt:50-51` 的受保护明文副本未清零，是否每个受保护字段都泄漏一份完整明文给 GC？** | 这是「敏感数据铁律」的**实现级违反**（自有通道已清零，这条借用通道没有）。若确实如此，任何堆 dump（含 Android `DropBox`/ANR trace/低内存 dump）都可能命中明文。 | `database/.../xml/KdbxXmlStringNode.kt:50-51` vs `core/.../security/ProtectedString.kt:40-51,71-77` |
| **Q-5** | **`KdbxKeyFile.kt:40-50` 把第二因子物化为不可擦除 `String`，范围有多大？** 是否覆盖 XML `.keyx` 的 `<Data>`（最常见形态）？ | 密钥文件与主密码等权。若 `<Data>` 内容经 `String` 流转，则第二因子在堆中不可控驻留。 | `database/.../file/KdbxKeyFile.kt:32-140` |
| **Q-6** | **XML 加固在 API 36（Harmony/Expat）上实际生效几项？** 代码已知至少 `resolve-dtd-uris` 会被跳过（`KdbxXmlParser.kt:129-134`）。若 `lexical-handler` 属性也被拒绝且 `disallow-doctype-decl` 不被识别，则**DTD 拒绝完全失效**，只剩 `resolveEntity`（拦外部实体，不拦内部实体膨胀）。 | 这是「宿主 JVM 过、Android 挂」的**第三次**同类风险（前两次见 RESOLVED_LOG §24/§26/§27.3）。且项目已有「device-side 才暴露」的历史。 | `database/.../xml/KdbxXmlParser.kt:100-224`；需设备侧插桩确认实际生效的特性集合 |
| **Q-7** | **`copyUsername` 是否会经 `{REF:P@...}` 把口令以「非敏感标记 + 无自动擦除」方式写入剪贴板？** | 若成立，是一个具体的「口令泄露到剪贴板且永不自清」路径（`copyPlainText` 会取消清理任务）。需要确认 REF 引擎在用户名路径是否真的解析 `P`（password）源。 | `app/.../ui/screens/detail/EntryDetailViewModel.kt:456-463`；`database/.../fieldref/FieldReferenceEngine.kt:140,149`；`security/ClipboardSecurityManager.kt:97-101` |
| **Q-8** | **Compose 的 `rememberSaveable`/`SavedStateHandle` 是否可能承载主密码或条目口令字符？** | 进程被系统回收后 `Bundle` 由 system_server 持有；若口令进 Bundle，则跨越了「秘密不出进程」的不变式。 | 全仓 `rememberSaveable` / `SavedStateHandle` / `onSaveInstanceState` 使用点，逐一判定是否与敏感字段相关（(c) 未核实） |
| **Q-9** | **`.bak` 的实际生命周期**：换密前后各存在几份、以哪一代口令加密、是否存在「用户以为已删除但仍在」的路径？ | 直接决定离线攻击者能拿到的**密文×口令代际**组合数。代码只在「换密成功后」与「关闭备份偏好时」删除 `.bak`；主文件目录中的 `.bak` 权限也未显式收紧。 | `session/AtomicFileWriter.kt:134-168,251-267`；`DatabaseSession.kt:65,387-390`；`SessionFileWriter.kt:26-32` |
| **Q-10** | **`KdbxDatabase.binaries` / `InnerHeader.BinaryItem` 无擦除 API**：内存附件池（≤128 MiB）在锁定后是否确定不可达？是否可能被其他长生命周期引用者（如 `SyncCoordinator` 的 `lastSyncedDb`）继续持有？ | 若是，则「锁定」对附件明文不成立，且 `ProtectedString` 的密钥轮换不可行性论证（`InMemoryCipher.kt:36-44`）暗示**确实存在**这类树外引用者。 | `database/.../file/KdbxDatabase.kt:46-48`；`database/.../file/InnerHeader.kt:33-59`；`app/.../sync/SyncCoordinator.kt:95-99` |
| **Q-11** | **`RuntimeIntegrityDetector` 的实际拦截力**：在真实 Frida 场景下（默认 gadget 名、内存加载、`frida-server` 改名）能否命中？ | 当前实现是**路径/字符串特征**启发式，非完整性证明。若命中率低，则「COMPROMISED 时禁用生物解锁+自动填充」的政策在真实攻击下形同虚设，却给用户以「已被保护」的错觉。 | `security/RuntimeIntegrityDetector.kt:100-135,160-202`；建议真机 Frida 实测 |
| **Q-12** | **`AppLog.i` 无运行时闸门且 R8 不剥离**：当前所有调用点确实无敏感插值（已逐条审阅 81 处），但这是一条**靠人守的纪律**。是否有自动化断言（类似 `LogHygieneTest`）覆盖 `AppLog.i/w/e` 的插值内容？ | 未来一次 `AppLog.i("...$dbId...")` 就会把库路径写进 release 日志。已知 `UnlockViewModel.kt:293` 已把 `activeDb=$dbId`、`keyFileLen=` 写入 `DebugLogBuffer`（该缓冲可经设置页导出）。 | `core/.../log/AppLog.kt:32,36,46`；`proguard-rules.pro:140-147`；`UnlockViewModel.kt:293` |
| **Q-13** | **防回滚状态为何放在会被锁库清空的 `cacheDir/sync`？** 这是有意取舍还是缺陷？ | 它把「Assume Breach + 云端不可信」下唯一的重放防线降级为**会话级**。若为有意，`SyncRollbackGuard.kt:44-52` 的 KDoc 与 `docs/同步层记录级完整性威胁建模.md` 必须补上该语义；若为缺陷，状态应移到**锁库不清**的位置（但仍需 MAC 保护），并考虑持久化更长的摘要链/单调序号。 | `SyncCacheEvictor.kt:37-39,46-63`；`SyncCache.kt:286,306-314`；`SyncRollbackGuard.kt:79,113,122,183` |
| **Q-14** | **远端读取为何不设尺寸上限，且 `contentLength` 不被当作守卫？** 是遗漏还是有意？ | 云端/MITM 可让应用下载任意大小对象 → OOM；PROPFIND 响应还可以递归遍历超深 XML → `StackOverflowError`（`Error`，不被 `catch (Exception)` 捕获）→ 进程崩溃。这是**唯一**能由远端单方面触发的崩溃面。修法很小：对 `body.bytes()` 加封顶、PROPFIND 改流式/迭代、捕获 `Throwable`。 | `WebDavSyncProvider.kt:125,170`；`S3SyncProvider.kt:204`；`SyncModels.kt:20`；`WebDavPropfindParser.kt:50-59,82` |
| **Q-15** | **`clearPasswordOnLeave` 是死开关吗？用户键入但未提交的主密码会在后台/旋转后存活多久？** | `UnlockViewModel.passwordChars` 全程持有未提交的 `CharArray`（`:81,188-193`），仅在提交结果/`onCleared` 时擦除（`:329,335-338,387`）；而 `clearPasswordOnLeave` 只被持久化与投影，**零消费方**。这类「可切换但无行为」的开关在 RESOLVED_LOG 中已多次被登记为缺陷（假开关），本条若属同族即为**新发现**。 | `ExtendedSettings.kt:29`；`ExtendedSettingsStore.kt:90,167`；`SettingsUiState.kt:153`；`UnlockViewModel.kt:81,188-193,329,387` |
| **Q-16** | **切换/新建密码库时旧库对象树为何不擦除？** `SessionOpener.create()/openStream()` 直接替换 `core.database.value`，不调用 `clearSensitiveData()`、不通知锁观察者（`SyncCacheEvictor`/`FileBinaryStore`/`SyncCoordinator`）。 | 用户在库 A 解锁后切到库 B，库 A 的全部受保护值密文与缓存密文快照都滞留到 GC；旧库的同步缓存与**明文附件缓存**也继续留在磁盘上。与 `lock()` 的严格擦除形成明显不一致。 | `SessionOpener.kt:73-90,149-157`；对比 `DatabaseSession.kt:110-119,325-349` |
| **Q-17** | **`SyncProviderResolver.resolveRemotePath()` 与 `WebDavAuthHeader` 的凭据是否确实未清零？** 前者加载完整的 WebDAV 口令/S3 密钥对象却只取路径字符串；后者把 base64(`user:password`) 作为 `String` 字段持有整个 provider 生命周期。 | 同一文件的其他分支（`:56-58,93-100`）都做了严格擦除，这两处不擦除属**实现不一致**，是具体的「铁律违反」候选。 | `SyncProviderResolver.kt:105-121`；`WebDavAuthHeader.kt:25-26,44`；`WebDavSyncProvider.kt:62,81` |

---

# 8. 按（可能性 × 影响）排序的威胁清单

| 排名 | 威胁 | 可能性 | 影响 | 缓解边界 | 证据 |
|---|:---|:---:|:---:|---|---|
| **T-1** | **用户设备被 root / 安装被 patch 的 APK / 运行时 Hook（Frida）→ 直接读进程内存取得全部明文** | 中 | **致命** | **无有效软件边界**。`RuntimeIntegrityDetector` 为启发式，可绕过；`ProtectedString` 进程密钥可读 | `InMemoryCipher.kt:31-34,62,65`；`RuntimeIntegrityDetector.kt:100-135` |
| **T-2** | **弱主密码 + `.kdbx`（尤其 `.bak`）泄露 → 离线破解** | 中高 | **致命** | Argon2 KDF 参数（**Q-3 待确认**）+ 口令熵；`.bak` 扩大代际攻击面 | `KdbxKeyDerivation.kt:98-127`；`DatabaseSession.kt:65,387-390` |
| **T-3** | **恶意 IME 记录主密码/条目口令**（ISSUE-P3-76，已明示接受） | 中高（取决于用户是否安装第三方 IME） | **致命** | 仅 `TYPE_TEXT_VARIATION_PASSWORD` 提示 + `SecurePasswordField` 单点封装；框架层无法下发 `IME_FLAG_NO_PERSONALIZED_LEARNING` | `ACTIVE_ISSUES.md` ISSUE-P3-76:78-118 |
| **T-4** | **同意保真度缺陷被社交工程利用**：自动填充确认框不显示请求方；手动选择器返回**全库任意条目**（不做域匹配），恶意应用可诱导用户手选银行条目并点确认 | 中 | **高** | 强制二次确认（会弹出）；但**用户无法在弹窗中区分请求方** | `strings.xml:957-965`；`AutofillPickerActivity.kt:68-86,146-181`；`AutofillPickerViewModel`（全库搜索） |
| **T-5** | **受信任浏览器列表过窄导致的「静默降级」被误读为已保护**：仅 Chrome/Firefox/Beta 收录；其余浏览器（Brave/Edge/Samsung/Focus）走 DAL，而 DAL 3 s 预算在 4 s 填充预算内**经常超时** → `onSuccess(null)` | 中 | 中（可用性 + 用户误判安全状态） | fail-closed（不下发候选） | `BrowserSigningFingerprints.kt:21-24,32-46`；`DigitalAssetLinksVerifier.kt:128`；`KeePasskeyAutofillService.kt:90-95,322` |
| **T-6** | **解锁态 FLAG_SECURE 被用户关闭后，同一窗口内的全部敏感页面同时失去截屏保护**（详情页明文口令、TOTP 种子、同步凭据、导出） | 中（用户主动关闭，风险弹窗已提示） | 中高 | 无第二道防线；锁定态仍强制遮蔽 | `FlagSecurePolicy.kt:24-27`；`MainActivity.kt:67`；(a) 无独立敏感 Activity |
| **T-7** | **恶意 KDBX 文件造成有界 DoS**（Argon2 `M` 上限 4 GiB → 内存分配失败/极慢） | 低中 | 低（可用性） | KDF 参数上界 + JVM 堆预检 + 原生失败回退；已登记为「纵深防御缺口当前不可达」 | `KdbxKdfParameterCodec.kt:25,106-126`；`Argon2KdfEngine.kt:130-133` |
| **T-8** | **防回滚窗口被锁库破坏**：每次锁库清空 `cacheDir/sync` 后，云端重放「设备曾接受过的旧库」即被 `Accept` → 被入侵的云端可复活已删条目、回退已更新字段 | **中**（攻击者只需等一次自然锁库） | 中高 | 防回滚链**仅在当前未锁定会话内有效**；`recent` 仅 32 条；`sequence` 未参与裁决。**文档未声明该语义** | `SyncCacheEvictor.kt:37-39,46-63`；`SyncCache.kt:286,306-314`；`SyncRollbackGuard.kt:79,113,122,183`；对比 `docs/同步层记录级完整性威胁建模.md:49-55` |
| **T-8b** | **云端/MITM 触发的远端读取 DoS**：无下载尺寸上限 + PROPFIND 响应整体入 `String` + 递归 DOM（只 catch `Exception`）→ OOM / `StackOverflowError` 进程崩溃 | 中（需控制端点或 MITM；无证书固定故系统 CA 级 MITM 即可） | 中（可用性） | **无有效边界**——`contentLength` 被填充却从不作为守卫；KDBX 内部炸弹守卫在下载之后才生效 | `WebDavSyncProvider.kt:125,170`；`S3SyncProvider.kt:204`；`SyncModels.kt:20`；`WebDavPropfindParser.kt:50-59,82` |
| **T-8c** | **云端静默替换内容**：返回一份密码学有效但内容不同的库，本地无改动时被直接接受 | 中 | 中（数据完整性/可用性，非机密性） | 防回滚对**全新内容**不告警；属 Assume-Breach 模型固有性质 | `SyncEngine.kt:169-181`；`SyncCycleRunner.kt:312`；`SyncRollbackGuard.kt:82` |
| **T-9** | **换密后残留的旧口令 `.bak`** | 低中 | 中高（若能取得文件） | 换密成功后删除；关闭备份偏好时删除；但**换密前生成的 .bak 一直存在**，且**锁库与「彻底退出」都不删** | `DatabaseSession.kt:387-390`；`AtomicFileWriter.kt:134-168,251-267`；`AppTerminationPolicy.kt:29-32` |
| **T-9b** | **「彻底退出应用」不清缓存**：`exitProcess` 不触发 `SessionLockObserver` → 明文附件缓存（`cacheDir/attachments`）与 `.bak` 留在磁盘上 | 中（用户会主动以为「已退出即安全」） | 中 | **无**——退出路径不清理 | `KeePasskeyApp.kt:57-69`；`AppTerminationPolicy.kt:29-32`；对比 `FileBinaryStore.kt:61-65` |
| **T-9c** | **切换/新建密码库不擦除旧库**：旧库 `ProtectedString` 密文与 `cacheDir/sync` 快照、`cacheDir/attachments` 明文附件均不被清理 | 中 | 中 | **无**——`SessionOpener` 不通知锁观察者 | `SessionOpener.kt:73-90,149-157` |
| **T-9d** | **未提交的主密码跨后台/旋转长期驻留**，且 `clearPasswordOnLeave` 为死开关 | 中 | 中 | 仅 `onCleared`/提交时擦除 | `UnlockViewModel.kt:81,188-193,329,387`；`ExtendedSettings.kt:29` |
| **T-10** | **CM 密码保存路径写入畸形 URL**（`https://https://host` 或 `https://android:apk-key-hash:...`）→ 条目永久无法被域匹配 | 中 | 低（完整性/可用性，无口令泄露） | 无；`VaultEntryWriteCoordinator.kt:248` 只做非空判定 | `KeePasskeyCredentialProviderService.kt:247-250`；`PasswordSaveActivity.kt:48,68-73`；`VaultEntryWriteCoordinator.kt:248,272-274` |
| **T-11** | **`copyUsername` 经 `{REF:P@...}` 把口令以非敏感标记 + 取消自动擦除方式放入剪贴板** | 低（需用户自建该引用） | 中 | 无（若 Q-7 成立） | `EntryDetailViewModel.kt:456-463`；`ClipboardSecurityManager.kt:97-101` |
| **T-12** | **`SecureCaptureActivity` 缺遮挡触摸过滤**（TOTP 扫码窗口可被 overlay 覆盖诱发误扫） | 低 | 低 | 该窗口已有 FLAG_SECURE + `setHideOverlayWindows(true)` | `SecureCaptureActivity.kt:24-28` |
| **T-13** | **软 Keystore 落位 fail-open**（模拟器/个别机型无 TEE 时封印凭据仅由软件密钥保护） | 低（真实设备基本都有 TEE） | 中高（若发生） | 仅告警日志 | `KeystoreKeyMaterial.kt:119-127`；`KeystoreManager.kt:29-31` |
| **T-14** | **UI 把 raw ChaCha20 错误标注为「ChaCha20-Poly1305 (256-bit)」** | 高（必现） | 低（事实性错误，非密码学弱化） | 无 | `SettingsPreferencesController.kt:36`；`SettingsUiState.kt:69`；`DatabaseAlgorithmDialogs.kt:42` |
| **T-15** | **内存附件池（≤128 MiB）与 KDF `secret(K)/A` 在锁定后不与凭据一同擦除** | 中 | 中（内存取证窗口） | 仅随引用丢弃并期待 GC；无显式擦除 | `KdbxDatabase.kt:46-48`；`InnerHeader.kt:33-59`；`KdfParameters.kt:35-36` |
| **T-16** | **凭据未清零的实现不一致**：`resolveRemotePath()` 加载完整 WebDAV 口令/S3 密钥却不擦；`WebDavAuthHeader` 把 base64(`user:password`) 作为 `String` 持有整个 provider 生命周期；`save()/exportToBytes()` 的 `ByteArrayOutputStream` 内部缓冲（第二份完整序列化密文）从不擦 | 中高（必然发生） | 低（多为密文或 provider 生命周期内；口令/S3 key 属真明文） | 同一文件其他分支已正确擦除 → 属实现不一致，非无解 | `SyncProviderResolver.kt:105-121`（对比 `:56-58,93-100`）；`WebDavAuthHeader.kt:25-26,44`；`WebDavSyncProvider.kt:62,81`；`DatabaseSession.kt:219-226,305-307` |
| **T-17** | **自建内网 WebDAV/NAS 在出厂配置下不可用**（SSRF 防护拒绝 RFC1918/`.local`，且 `ssrfAllowedHosts` 生产未接线） | 高（对家用 NAS 用户） | 低（可用性；隐含用户会把敏感库放到公网云或放弃使用） | 无生产逃生通道（`SyncProviderResolver.kt:52,77` 恒传默认空集） | `SyncEndpointGuard.kt:114-131`；`SyncHttpClientFactory.kt:16-18,35`；`SyncNetworkOptions.kt:21-25` |

---

# 9. Cannot Confirm / Missing Information

以下事项**无法在不具备真机、外部环境或额外权限的情况下确认**，如实列出：

| # | 事项 | 为何无法确认 | 归属 |
|---|---|---|---|
| C-1 | **arm64 真机 + 真实语料的端到端解锁** | 本机无 arm64-v8a 镜像、无真机连接 | ISSUE-P3-23（产品裁决不排期） |
| C-2 | **Passkey 系统级交互 / `AssistStructure` 真实结构树 / 通知渲染的设备侧行为** | 依赖系统凭据对话框、真实自动填充会话、通知栏 | ISSUE-P3-66（产品裁决不排期） |
| C-3 | **API 36 上 SAX 加固特性的实际生效集合**（Q-6） | 需设备侧插桩；代码已知至少一项被跳过 | 本模型新增 |
| C-4 | **Android 沙箱是否确实阻止其他 UID 读取 `filesDir/*.kdbx` 与 `cacheDir`** | 平台语义 + (b) 推断；未在真机做越权读取验证 | 平台保证 |
| C-5 | **`securityLevel` 为 SOFTWARE 的设备上的实际风险**（T-13） | 需特定机型/模拟器实测 | 本模型新增 |
| C-6 | **Compose `rememberSaveable`/`SavedStateHandle` 是否承载过敏感字符**（Q-8） | 需逐点审阅 + 运行时观察 | 本模型新增 |
| C-7 | **核验报告提供的 `webDomain` 在真实浏览器上是否总能拿到可用值** | 依赖具体浏览器与页面 | 设备侧 |
| C-8 | **`onSaveRequest` 是否只会在用户确认保存 UI 之后被系统调用** | 框架行为，仓库内无证据 | 框架语义 |
| C-9 | **`AtomicFileWriter` tmp/target 的实际 umask 权限** | 需设备侧 `stat` | 本模型新增 |
| C-10 | **ProGuard/R8 release 产物中是否存在未被预期的类/字符串残留** | 需对 `app-release.apk` 做实际逆向核对 | 建议审计员补 |

## 附：本次审计覆盖范围与未覆盖范围

**已覆盖（并逐条引用）**：`docs/ARCHITECTURE.md`、`AGENTS.md`、`docs/ACTIVE_ISSUES.md`、`docs/RESOLVED_LOG.md`、`docs/Privacy-Policy.md`、`docs/同步层记录级完整性威胁建模.md`、`README.md`、`app/src/main/AndroidManifest.xml`、`app/src/main/res/xml/*`、`app/build.gradle.kts`、`app/proguard-rules.pro`、`.codebuddy/rules/engineering-rules.md`；`app` 模块 `security/`、`autofill/`、`passkey/`、`notification/`、`data/binary/`、`sync/`、`ui/screens/unlock/`；`core/security/`、`core/log/`；`crypto` Kotlin 层与 `crypto/src/main/rust/src/`；`database` 的 `file/`、`session/`、`xml/`、`fieldref/`；`sync/` 的 `network/`、`engine/`、`webdav/`、`s3/`。

**未覆盖（按任务约束排除）**：`build/`、`.gradle/`、`crypto/src/main/rust/target/`、`参考项目/`、`KeePasskey测试/`、`KeePasskey测试_backup/`；以及 `app/src/main/java/.../ui/screens/**` 的逐页面渲染细节、`data/importer/**` 的逐解析器实现、`data/childdb/**` 的完整状态机（仅覆盖其在边界上的表现）。
