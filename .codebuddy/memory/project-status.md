# 项目记忆（Project Memory）

> 由 `AGENTS.md` 索引。升级依赖、评估项目进度或判断历史决策缘由时阅读。

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

构建统一使用 Gradle Wrapper（Gradle 9.3.1），Windows 下执行 `.\gradlew.bat`；常用任务：`assembleDebug`、`:app:compileDebugKotlin`（快速编译检查）、`lint`、`test`（尚无 `src/test`）。

## 项目现状（阶段 6「高级特性与全功能工具箱」已圆满完成，阶段 7「质量工程与发布交付」准备就绪）

- **阶段 6 核心成果全量落地**：
  - **动态双重认证引擎（OtpEngine）**：
    - 纯 Kotlin 实现标准 RFC 6238 TOTP 与 RFC 4226 HOTP 动态验证码生成算法；
    - 纯净实现 RFC 4648 Base32 解码器与 `otpauth://` KeyUri 参数解析器，覆盖 SHA-1/256/512 与 6位/8位代码；
  - **离线密码健康审计引擎（HealthCheckEngine）**：
    - 本地优先扫描离线弱口令字典、密码长度告警与跨条目重复复用检测；
  - **KDBX 条目历史版本管理（HistoryManager）**：
    - 条目修改时自动将历史快照安全隔离追加至 `<History>` 列表；
    - 支持一键版本回滚（`rollbackToSnapshot`）并还原数据模型；
  - **条目二进制附件管理器（AttachmentManager）**：
    - 支持附件内容提取至应用私有缓存目录与退出时物理清零；
  - **测试与构建验证全绿**：
    - 新增 `OtpEngineTest`（覆盖 RFC 官方测试向量）、`HealthCheckEngineTest`、`HistoryManagerTest` 单元测试；
    - 全模块 114 个测试任务全部绿色通过，`assembleDebug` 编译通过。
- **阶段 7 即将开始**：聚焦 R8 混淆加固、敏感数据保护规则与全渠道构建发布。

## 决策日志

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

- kapt 迁移 KSP（需与 Kotlin 升级联动）。
- 引入 version catalog（`libs.versions.toml`）收敛依赖版本。
- `crypto` 模块待引入 BouncyCastle 依赖；Argon2 纯 Java 性能待评估，NDK 加速列为远期优化。
- `database` / `crypto` 需规划 KDBX 标准测试向量与 `src/test`。
