# 项目记忆（Project Memory）

> ⚠️ **历史文档存档（HISTORICAL ARCHIVE）**
> **停止更新声明**：本文档仅作为历史决策日志保留。最新版本配套表、实时项目现状与未完成任务请参阅唯一真相源：[**docs/STATUS.md**](docs/STATUS.md)。

---

## 版本配套关系（升级时必须整体考虑）

| 组件 | 版本 | 约束 |
|------|------|------|
| AGP | 9.1.0 | 配 Gradle 9.3.1（Wrapper 已指向 9.3.1）；AGP 9 要求 Kotlin 2.x |
| Gradle | 9.3.1 | Wrapper 分发地址指向腾讯云镜像 |
| Kotlin | 2.4.10 | Compose 编译器随 Kotlin 一同发布（`kotlin.plugin.compose`，版本必须一致） |
| Hilt | 2.60.1 | 当前用 kapt，后续迁移 KSP 时需同步升级 Kotlin |
| Compose BOM | 2026.06.01 | Compose 1.11.x，要求 compileSdk ≤ 36；最新 2026.08.00（1.12.x）要求 compileSdk 37，当前 SDK 未安装，暂不升级 |
| compileSdk / targetSdk | 36 | Android 16 |
| minSdk | 36 | 仅考虑 API 36+，无旧版垫片负担 |
| JVM | 17 | 所有模块统一 source/target 17（Kotlin 2.2 起 `android.kotlinOptions` 已移除，统一用 `kotlin { compilerOptions { jvmTarget } }`） |

构建统一使用 Gradle Wrapper（Gradle 9.3.1），Windows 下执行 `.\gradlew.bat`；常用任务：`assembleDebug`、`:app:compileDebugKotlin`（快速编译检查）、`lint`、`testDebugUnitTest`（全模块 **417** 个测试）。

## 项目现状（7 阶段全量竣工 + Wave 1–16 专项整改收口，417 测试全绿）

- **2026-09-05 差距修复专项（REMEDIATION_PLAN.md，4 Wave 多子代理模式）**：
  - **Wave 1（git ed601da）**：KDBX v4 引擎与 KeePass 官方 2.61.1 逐字节互操作——cipherKey 改官方 SHA-512 截断派生（旧库自动回退迁移）、.NET Ticks 时间编码、XML 全字段往返（Meta/AutoType/Binary-Ref/CustomData）、InnerHeader 二进制池去重、类型化异常；crypto 补齐 CBOR/COSE/三算法 Passkey 签名/KDF 基准。
  - **Wave 2（git 4927189）**：sync 模块三哈希同步状态机（SyncCache/SyncEngine + ICacheSupervisor 六事件）+ KdbxMerger v2 墓碑三方合并 + WebDAV/S3 协议加固；app 模块 CredentialProviderService/AutofillService 从空壳真实化（4 个 Launcher Activity + DomainMatcher 严格域名匹配）。
  - **Wave 3（git a45bfa5）**：数据层编辑保留、库内回收站（墓碑落库）、TOTP/健康检查去假数据、SyncCoordinator 同步全链路接线、同步凭据 Keystore 加密持久化、allowBackup 加固、parseDate 含 T 误判缺陷修复、proguard 包级 keep 扩展。
  - **Wave 4（文档收口）**：AGENTS.md 与本文件如实化（22 项问题与 4 Wave 修复全记录）。
  - **测试基线（该专项收口时）**：166 个单元测试全绿（app 65 / core 9 / crypto 34 / database 28 / sync 30）；assembleDebug 与 assembleRelease（R8）通过。
  - **当前基线（2026-09-07）**：**417 个单元测试全绿**（app 99 / core 21 / crypto 42 / database 146 / sync 109），其中 `LiveSyncServersTest` 12 例默认跳过（需 `-DliveSyncTest` + 本地 WebDAV/MinIO 服务）。演进：77 → 114 → 166 → 172 → 173 → 358 → 417。

## 决策日志

- **2026-09-05**：**真实 KDBX 4.0 库与复合密钥（主密码+XML KeyFile v2.0）真机实测互操作专项验收与整改**——使用用户提供的真实文件（`测试.kdbx` + `111.keyx` + 主密码）在 Android 16（Pixel_10）模拟器环境下端到端验证，对照 KeePass 2.61.1 官方 C#、KeePassDX 与 KeePassXC 源码彻底根治 6 项深层格式与算法缺陷：
  ① **解锁页密钥文件虚假断链清零**：`VaultRepository.unlockActiveDatabase` 与实现类透传 `keyFileData: ByteArray?`，`UnlockScreen` 接入系统级真实 SAF 文档选择器（`ActivityResultContracts.OpenDocument`），安全读取并全链路直达会话，用毕显式清零；
  ② **密钥文件解析梯子落位**：新增 `KdbxKeyFile`，严格实现 XML KeyFile（v1.0 Base64 / v2.0 Hex + Hash 前 4 字节校验）、32B 裸二进制、64Hex 文本与整文件哈希四级梯子，新增 10 个测试用例（覆盖真实 111.keyx 向量）；
  ③ **Argon2 / Cipher 官方 UUID 纠正**：修正 `KdbxConstants.Kdf.ARGON2D`（`EF636DDF-8C29-444B-91F7-A9A403E30A0C`）、`ARGON2ID`（`9E298B19-56DB-4773-B23D-FC3EC6F0A1E6`）与 ChaCha20/Twofish 四个官方 UUID；
  ④ **变体字典类型宽容与 P 读参规范**：`VariantDictionary` 实现数值 getter 宽容自适应（防御 UInt32 在 Int/Long 间的非法转换），`KdbxHeader` 对齐 KeePassDX 按 `getUInt32("P")` 读参；
  ⑤ **HMAC 块签名索引前缀补齐**：块 HMAC 签名数据补齐开头的 `LittleEndian64(blockIndex)` 前缀；
  ⑥ **载荷压缩与内层 Header 读写顺序纠偏**：对齐官方 C# `KdbxFile.Read.cs:172-178`（"Binary header before XML"）与 KeePassDX `DatabaseInputKDBX`，将内层 Header 置于 GZIP 压缩流内部处理（解密 → GZIP解压 → 读取内层 Header → 解析XML）；探针相应升级为优先识别 GZIP 魔数（`1F 8B 08`）。
  **最终验证**：模拟器真机以复合密钥成功解锁 `测试.kdbx`，成功读取群组「111」及条目「11」（明文密码 `~W4hUziUy7FSRR#K@N@K` 完全一致）；在 KeePasskey 中新建条目并落盘后，经独立第三方工具 `pykeepass` 完整往返读取校验通过。详见 `docs/KDBX4与复合密钥实战互操作排查日志.md`。

- **2026-09-07**：**Wave 16「系统凭据服务真实化 + 同步稳定性收口」交付**（`a8172c1`~`7b3e756`）——① 凭据自动填充双通道：`KeePasskeyCredentialProviderService`/`KeePasskeyAutofillService` 对齐双系统服务标准，新增请求取消级联协程与 IME 内联建议（AndroidX Autofill Inline V1）、双通道保存幂等查重；**修正凭据能力注册缺陷**——`meta-data` 由错误的 `android.service.credentials.credential_provider` 改为官方契约名 `android.credentials.provider`，`credential_provider_service.xml` 补齐 `<capabilities>`（此前系统大概率从未正确注册过凭据能力，需真机回归验证）；② overlay 攻击防护：全敏感 Activity 启用 `HIDE_OVERLAY_WINDOWS` + `setHideOverlayWindows(true)`，`FlagSecureGuard` 增会话锁定状态联动与首帧同步遮蔽；③ 同步稳定性：`CacheCorruptedError` 防缓存损坏致全库覆盖丢失、KDBX4 随机 IV 误判重传修复、三方合并基准快照不可靠时退化空库合并、`SyncCache` 临时文件 UUID 化与孤儿清理、WebDAV 无 ETag Overwrite 死锁与 S3 首传无条件 PUT 风险修复；④ `SyncCredentialsStore.saveWebDavConfig` 补 `try/finally` 密码数组清零——修复 Wave 15 遗留的 2 例失败（此前被归因为「Keystore 封印 / 环境存量问题」，实为借用语义契约未落地，与 Keystore、环境均无关）；⑤ `tools/local-sync` 本地 HTTPS 联调工具链 + `LiveSyncServersTest`（默认跳过，`-DliveSyncTest` 启用）。关键决策：批次 B–F 原预分配的 Wave 15–19 已被实际交付占用，路线图改为**执行时按最新 Wave 号顺延**（当时记为下一批次 B 预计 Wave 17；**2026-09-07 后续修订**：Wave 17 已预留给插队的批次 H，批次 B 顺延为 **Wave 18**，以 `docs/HEALTH_CHECK_ROADMAP.md`「批次总览」表下说明为准）。测试基线 358 → **417 例全绿**。

- **2026-09-06/07**：**无 Wave 编号的已交付专项补记**（2026-09-07 文档核对时发现此前遗漏，现补齐）——① `d94e6dc` 全量代码审核整改与测试扩充（对齐 KeePass 2.61.1 官方规范，见 `docs/全量代码审核报告-2026-09-05.md`）；② `15a4156` **加解密实现安全审查整改**（整改明细见 `docs/加解密实现审查-2026-09-06.md` 第五节）——P2 三项全闭合：密钥硬件落位校验改用 `KeyInfo.getSecurityLevel()`（原报告的 AES Key Attestation 经核实不适用，attestation 仅支持非对称密钥）、minSdk 36 下启用 `setUnlockedDeviceRequired(true)`、`InMemoryCipher` 重构为「HMAC-SHA256 等值标签 + 随机 IV」以消除确定性密文的等值泄露；P3 四项 String 残留闭合：M1 `EntryEditUiState` 去 `password: String` 改 `passwordLength` + 私有 `passwordChars`、M2 历史回滚改走 `getEntryRevisionPasswordChars`、M3 改密对话框改双 `SecurePasswordField`、M4 删除 `PasswordSaveActivity` 中可被外部注入伪造明文密码的死分支 `EXTRA_PASSWORD`。**该报告「顺带发现的存量问题」一段（HMAC 篡改用例偶发失败）即后续批次 H（Wave 17）的立项来源**；③ `73944a2` 统一认证策略并重构多模块代码；④ `9910237` **日志脱敏与文件导出**（新增 `DebugLogBuffer`，落盘前脱敏 + 调试日志导出能力）；⑤ `ef17057` **Material You 动态取色**与主题系统优化（`Theme.kt` / `ThemeSettingsScreen.kt` 落地动态配色开关）。同区间另有 `3b95b60`（生物识别 Class 3 强验证、内存清零与凭据注册修复）、`58fe617`（VaultList / Settings 显式手动锁定入口）、`0d1fc20`（Autofill 解锁流程与密码库展示优化）。

- **2026-09-05**：**Wave 5「已知限界清零专项」交付**——四项已知限界逐项解决：① KDBX 全链路流式化（读取 SAX 状态机 / 写侧 KdbxXmlStreamWriter / HmacBlockStream 流式双向 / KdbxFile 管线化，不再物化整条密文/明文/压缩数据）；② S3 覆写 PUT 附 `If-Match` 服务端原子校验，TOCTOU 消除（不支持条件写的兼容存储降级旧行为）；③ Credential Provider 链式解锁（锁库 UX v2：锁库 Action → CredentialUnlockActivity 解锁 → setBeginGetCredentialResponse 直接回传候选，共享 CredentialResponseAssembler）；④ SecurePasswordField 组件 + 主密码 CharArray 全链路（UnlockUiState 去 String 明文）。关键设计决策：⑤ 官方与旧派生 hmacKey64 相同（仅 cipherKey 异），头部 HMAC 无法区分派生变体，旧派生识别改为首块解密探针 + 内层 Header 结构校验裁决；⑥ SAX 解析器异常包装必须放行 KdbxInvalidCredentialsException（HMAC 语义不得被吞为文件损坏）。剩余限界：对象树仍整体驻留内存、Compose 框架层 String（已收敛单点）、部分次要密码框未接 SecurePasswordField。

- **2026-09-05**：发现并修复「7 阶段全量验收」文档失真——全量代码审计对照参考项目架构分析发现 22 项问题（CredentialProviderService 空响应、triggerSync 模拟延时、TOTP 假码、cipherKey 非官方派生、parseDate Base64 含 T 误判等）。以 REMEDIATION_PLAN.md 4 波次多子代理模式完成修复，每波主会话独立复跑测试（--rerun-tasks 防缓存假绿）+ 语义化提交。关键设计决策：① cipherKey 迁移采用「官方派生优先+旧派生回退重试」，旧文件保存即自动迁移；② SyncEngine 定为纯字节级（sync 禁依赖 database），kdbx 语义合并编排放 app 层 SyncCoordinator；③ KDBX4 每次序列化随机 IV/Seed 导致哈希漂移，SyncCoordinator 以内容级比对防抖复用基线；④ 回收站改库内标准组（recycleBinUuid 落库），DatabaseSession 增量 updateDatabaseMeta 为唯一 database 适配点。已知限界：DOM 非流式解析（远期）、S3 HEAD+PUT TOCTOU 微窗口、锁库 UX v1、Compose 密码 String 边界妥协。（上述四项已由同日 Wave 5 清零，见上方决策日志）

- **2026-09-05**：新增第 5 个参考项目 **KeePassXC**（`参考项目/keepassxc-develop`，C++/Qt develop 分支），完成 728 行深度架构分析（`KeePassXC-架构分析.md`），并同步收录至 `.codebuddy/skills/references/`。重点固化三块可移植资产：① `Merger`（`src/core/Merger.cpp`）的条目级合并、秒级时间戳截断与墓碑复活规则 → `KdbxMerger` 直接算法参考；② `KdbxReader/KdbxWriter` KDBX 3/4 读写管线 → `database` 模块交叉验证；③ 浏览器集成的 `KPEX_PASSKEY_*` Entry 属性 schema 与 WebAuthn 栈隔离分层 → `PasskeyData` 与 Credential Provider 隔离设计。参考项目优先级层级更新为 5 级（KeePassXC 列为"算法级参考"，Monica 降为第 5 级），`AGENTS.md`、主 `README.md`、`.codebuddy/skills/reference-projects.md` 与两处 references README 已同步更新。

- **2026-09-04**：完成**阶段 7「质量工程、测试基线、混淆加固与全渠道交付」**全量交付。全项目 7 个阶段全面竣工！交付物涵盖：Material 3 响应式全功能界面、BouncyCastle/Argon2/AES-KDF 加密与 KDBX v4 原子读写、AndroidX Biometric 强生物识别与 Keystore 硬件加固、Android 16+ Passkey (WebAuthn) 原生 CredentialProviderService、WebDAV (ETag 412) / S3 (SigV4) 云同步与三方冲突合并、纯 Kotlin RFC 6238 TOTP 动态双重认证、附件物理管理、版本历史回滚与离线密码安全审计。全量单元测试与 R8 混淆构建 100% 通过。

- **2026-09-04**：完成**阶段 6「KeePass 高级特性与全功能工具箱」**全量交付。落地 `OtpEngine`（RFC 6238/4226）、`Base32Decoder`、`HealthCheckEngine`、`HistoryManager` 与 `AttachmentManager`，测试与编译全量通过。下一阶段正式进入**阶段 7「质量工程、测试基线、混淆加固与全渠道交付」**。

- **2026-09-04**：完成**阶段 5「多协议云端同步引擎（WebDAV / S3）与三方冲突合并」**全量交付。落地 `SyncProvider` 抽象、`WebDavSyncProvider`（ETag 412 乐观锁）、`S3SyncProvider`（AWS SigV4 规范签名）与 `KdbxMerger`（三方冲突识别与合并），测试与编译全量通过。下一阶段正式进入**阶段 6「KeePass 高级特性与全功能工具箱」**。

- **2026-09-04**：完成**阶段 4「通行密钥 (Passkey / WebAuthn) 与 Credential Manager 自动填充」**全量交付。落地 `PasskeyData` 规范映射、`PasskeyCryptoEngine` 签名引擎、`KeePasskeyCredentialProviderService` (API 36+) 与 `KeePasskeyAutofillService`，测试与编译全量通过。下一阶段正式进入**阶段 5「多协议云端同步引擎（WebDAV / S3）与三方冲突合并」**。

- **2026-09-04**：完成**阶段 3「系统级生物识别与防御性安全加固」**全量交付与验证。落地 `KeystoreManager`、`BiometricAuthManager`、`BiometricCredentialStorage`、`FlagSecureGuard`、`ClipboardSecurityManager` 与 `AutoLockManager`，打通指纹快速解封、防多任务泄露、剪贴板自动擦除与锁屏熔断，测试全绿。下一阶段正式进入**阶段 4「通行密钥 (Passkey / WebAuthn) 与 Credential Manager 自动填充」**。

- **2026-09-04**：完成**阶段 2「密码学核心与 KDBX 数据库引擎」**全量交付与双端验证。核心包含 `core` 安全内存、`crypto` 密码学引擎、`database` KDBX v4 序列化引擎与 `DatabaseSession` 原子写盘，成功通过 `RealVaultRepository` 替换数据层注入，单元测试全量通过。下一阶段正式进入**阶段 3「系统级生物识别与防御性安全加固」**。

- **2026-09-04**：完成 UI 全面深度评估，阶段 1「UI 优先」圆满验收（交互闭环、MVVM解耦、响应式适配与中英双语齐备）。决策下一次交互正式切换至**阶段 2「数据库核心」**，优先攻坚 `crypto` 与 `database` 模块。

- **2026-09-04**：架构决策确立——**项目后续全量仅考虑 API 36+（Android 16+）**。所有模块（`app`、`core`、`crypto`、`database`、`sync`）的 `compileSdk`、`minSdk`、`targetSdk` 均统一设置为 36。无需保留针对 API < 36 的向后兼容分支或冗余版本垫片，系统默认启用 Edge-to-Edge，直接调用现代 Credential Manager / FIDO2 / Passkey 与系统生物识别 API。
- **2026-09-04**：完成底部导航栏（BottomNavigationBar）集成，新增 `AppBottomBar`，包含“密码库”和“设置”一级入口，打通状态保留切换与子界面自动隐藏机制。
- **2026-09-04**：架构评审后，模块从 10 个收敛为 5 个（`app` / `core` / `crypto` / `database` / `sync`）；删除 `sync-webdav`、`sync-s3`（并入 `sync`），删除 `passkey`、`biometric`、`autofill` 独立模块（平台集成归入 `app` 内部包）。理由：零业务代码阶段过早拆分，配置重复且传递依赖复杂。
- **2026-09-04**：`database → crypto` 依赖由 `implementation` 改为 `api`，因 database 接口签名暴露 crypto 类型。
- **2026-09-04**：生成 Gradle Wrapper 8.10.2（本地缓存发行版离线生成），替代"无 Wrapper"状态。
- **2026-09-04**：`CODEBUDDY.md` 拆分为本目录下的 rules / skills / memory 分文件结构，主文件只保留概述、硬约束与索引。
- **2026-09-04**：工程规则补充 4 组密码管理器关键原则：文件 IO 原子写入（tmp → sync → rename + .bak 滚动备份）、协程调度约束（Default/IO 边界、受控 Application Scope 防临界写取消）、Credential Provider 隔离规范（无状态会话、Auto-Lock 熔断、响应超时预算）、防御性安全（FLAG_SECURE、剪贴板 EXTRA_IS_SENSITIVE + 定时清空、R8 keep 规则）。
- **2026-09-04**：完成全套 Google 官方 MVVM（Guide to app architecture）架构重构。按功能建立包（`unlock`, `vault`, `detail`, `edit`, `settings`），抽离不可变 `UiState` 数据模型与 `@HiltViewModel`（通过 `StateFlow` 单向数据流驱动），建立数据层 `VaultRepository`/`SettingsRepository` 与 Hilt DI `RepositoryModule`，并将所有 Screen 拆分为 Stateful Route 与 Stateless Content，彻底解除 Composable 与静态假数据的耦合。
- **2026-09-04**：完成多密码库管理与 UI 状态重构：新增 `database`（数据库选择器）功能页与设置 9 个子页；新增 OLED 纯黑主题优化（`oledBlackOptimization`）与 `AppThemePalette` 5 套配色；列表显示偏好（用户名 / OTP / Passkey 徽标 / URL / 滚动隐藏 FAB）；中英双语字符串资源。
- **2026-09-04**：工具链整体升级：Gradle 8.10.2 → **9.3.1**、AGP 8.5.2 → **9.1.0**、Kotlin 1.9.24 → **2.4.10**（Compose 编译器随 Kotlin 发布）、Hilt 2.51.1 → 2.60.1。Compose BOM 锁定 2026.06.01（1.12.x 需 compileSdk 37，本地 SDK 未装）。Kotlin 2.2 起 `android.kotlinOptions` 已移除，jvmTarget 统一改走 `kotlin { compilerOptions }`。
- **2026-09-04**：主指令文件 `CODEBUDDY.md` 更名为 **`AGENTS.md`**（内容同步改写），`.codebuddy/` 各分文件的索引头同步指向 `AGENTS.md`。

## 待办与已知问题

- KDBX 流式化已完成（Wave 5），剩余：对象树仍整体驻留内存——增量加载 / 解析进度 Flow 列为远期。
- S3 条件写已闭环（Wave 5 If-Match）；对未支持条件覆写的 S3 兼容存储自动降级为 HEAD 预检 + 无条件 PUT（行为不劣于旧版）。
- SecurePasswordField 已覆盖全部密码输入路径：解锁主密码（Wave 5）、DatabasePicker 创建向导（Wave 11）、Settings 同步配置页 WebDAV/S3（Wave 15）、EntryEdit 密码框（Wave 16 前已接入）——该待办项已闭合。原「QuickUnlock PIN 接入」一项随 Wave 13 自研 PIN 体系整体移除（改为设备锁屏凭据绑定密钥）而作废。
- **批次 H（Wave 17）已立项（2026-09-07）**：`KdbxCompatibilityAndSecurityTest.testCorruptHmacBlockThrowsKdbxInvalidCredentialsException` flaky 排查与定型——该用例是 HMAC 防篡改检测的关键回归锁，偶发「篡改后仍加载成功」（实测 6 次运行失败 1 次，已排除与 `KdbxFile` 非空断言清理的关联）。属安全项，插队优先于批次 B；两步排查方案与验收标准见 `docs/HEALTH_CHECK_ROADMAP.md` 批次 H。
- kapt 迁移 KSP（需与 Kotlin 升级联动）。
- 引入 version catalog（`libs.versions.toml`）收敛依赖版本。
- Argon2 纯 JVM 性能待评估，NDK 加速列为远期优化（KdfBenchmark 已提供设备自适应参数）。
- **未实现功能清单（2026-09-07 代码核对；勿据旧文档勾选状态误判）**：① 自定义图标上传 / 选择 UI（模型与序列化层完好）；② KeePass 字段引用 `{REF:...}` 引擎；③ 自定义键盘（Magikeyboard 式），当前仅提供 IME 内联建议；④ 条目克隆（duplicate）；⑤ **周期性后台同步**——设置项 `periodicBackgroundSyncIntervalMinutes`（默认 30 分钟）与 `wifiOnlySync` 已落地，但工程无 `androidx.work` 依赖与任何调度实现，`DELIVERY_PLAN.md` 阶段 5 原勾选已如实改为未实现；⑥ Passkey 作为数据库解锁方式（现快速解锁为设备锁屏凭据绑定密钥）；⑦ KDBX v2/v3 读写（代码明确拒绝，仅支持 v4）；⑧ 应用发布（构建链路就绪，F-Droid / GitHub Release 未上线）。
- **2026-09-07 文档失准专项修订**：`README.md` 功能清单 25 项与阶段路线图原全为未勾选（实际早已交付），已按代码实际状态逐项修订并新增「已知未实现」表；`DELIVERY_PLAN.md` 修正「114 → 417 个单测」「向下兼容 v3 → 仅 v4」与 WorkManager 假勾选，并删除全景图中重复且未打勾的阶段 6/7 行；本文件测试数字 166 → 417 并补记 5 个此前无记录已交付专项。
