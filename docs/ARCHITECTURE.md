# KeePasskey 目录架构

> 只确定模块划分、依赖方向与关键架构决策；文件级规划在实现阶段再细化。

## 1. 模块划分（5 个模块）

| 模块 | 类型 | 依赖 | 职责 |
|------|------|------|------|
| `app` | application | core, database, sync | Compose UI、导航、Hilt 装配；平台集成（生物识别 / 自动填充 / 通行密钥认证）作为 app 内部包实现 |
| `core` | library | — | 共享领域模型、`Result`、工具、DI 限定符；不依赖任何模块 |
| `crypto` | library | core | AES / Twofish / ChaCha20 分组加密、Argon2 / AES-KDF、KDBX 块流 |
| `database` | library | core, **crypto(api)** | kdbx v3/v4 解析与序列化、条目/分组模型、搜索、合并、OTP、通行密钥凭据存储 |
| `sync` | library | core | 文件存储抽象 + WebDAV / S3 兼容实现、本地缓存与冲突检测 |

## 2. 依赖方向（单向）

```
app ──> database ──> crypto ──> core
 └───> sync ────────────────> core
```

- `database` 对 `crypto` 用 `api` 暴露（database 的接口签名会引用 crypto 类型）。
- `sync` 抽象与 WebDAV / S3 实现暂放同一模块；若后续 SDK 依赖冲突或体量变大，再拆 `sync-webdav` / `sync-s3`。

## 3. 关键架构决策（方向级）

1. **加密与解析分离**：`crypto` 只做纯加密，不感知 kdbx 格式，可独立测试。Argon2 已落地原生加速（TASK-52 立项；ISSUE-P2-14 起内核为 **Rust**：`crypto/src/main/rust/` 经 cargo-ndk 从源码交叉编译 4 ABI，秘密缓冲 `zeroize` 确定性擦除，BouncyCastle 保留兜底，宿主侧实测快于 BC 2.2~5.4×）；AES-KDF 原生加速仍为远期性能优化项。
2. **kdbx 版本兼容**：读取时按文件头双签名嗅探分派解析器，只比对主版本号（`0xFFFF0000` 掩码），次版本号递增自动兼容（参考 KeePassDX）。
3. **已解锁数据库的所有权**：app 层持有单例 `DatabaseSession`（进程内），生物识别解锁、自动填充、通行密钥认证先与主进程同进程访问；确有需要（如 autofill 独立进程）再调整。
4. **同步模型**：kdbx 同步的本质是「整文件读 / 写 / 合并」。`sync` 层提供文件存储抽象（读取、事务式写、版本哈希检测）+ 本地缓存（对比 baseversion / version 哈希）；仅两端都修改时才报冲突，冲突合并下沉到 `database` 层的 KDBX merge（参考 keepass2android 的 `CachingFileStorage`）。
5. **通行密钥路线**：先做「kdbx 内存储 WebAuthn 凭据 + 自动填充使用」（参考 KeePassDX 的 Signature/Passkey 实现）；Android 14+ `CredentialProviderService` 注册为系统凭据提供者列为远期目标。
6. **UI 优先**：阶段 1 用静态假数据走通全部界面与导航，确认交互后再逐层接入 `database` / `sync` 真实逻辑（原 README 路线图已下架，当前待办见 [**docs/ACTIVE_ISSUES.md**](ACTIVE_ISSUES.md)）。

## 4. app 内部结构（遵循 Google 官方 MVVM 架构）

```
app/src/main/java/com/keepasskey/app/
├── MainApplication.kt / MainActivity.kt
├── data/
│   ├── repository/      # 数据仓库接口与实现（VaultRepository、SettingsRepository 等）
│   ├── logger/          # 诊断日志缓冲与偏好闸门（DebugLogBuffer、DiagnosticLogGate）
│   ├── breach/          # 泄露检测（HIBP k-anonymity 范围查询）
│   ├── childdb/         # 子库挂载（ISSUE-P3-20）：挂载注册表、只读子库会话、凭据隔离
│   └── importer/        # 明文导入框架（ISSUE-P3-19）：契约、注册表、4 源解析器、落库编排
├── di/                  # Hilt 依赖注入装配（RepositoryModule、ImporterModule 等）
├── autofill/            # 系统自动填充（AutofillService 与表单解析）
├── passkey/             # 通行密钥认证接入（Credential Manager / FIDO2）
├── notification/        # 通知通道、权限闸门与发布器（ISSUE-P3-18）
├── security/            # 运行时完整性、窗口防截屏、自动锁定、剪贴板治理
├── sync/                # 同步协调（周期编排 / 冲突决策 / Provider 解析 / 缓存驱逐）
├── util/                # 通用小工具（TickerFlow 等）
└── ui/
    ├── KeePasskeyApp.kt # 顶层路由与主题宿主
    ├── components/      # 共享 UI 组件（BentoCard、SecurityBadge、EntryIconContent 等）
    ├── navigation/      # 导航路线定义（Screen）
    ├── model/           # UI 展现层数据模型（UiVaultEntry、EntryCategory、EntryIcon 投影）
    ├── screens/         # 功能页面（MVVM 分层：UiState + ViewModel + Stateful Route + Stateless Content）
    │   ├── unlock/      # 解锁页（UnlockUiState, UnlockViewModel, UnlockScreen）
    │   ├── vault/       # 密码库列表（VaultListUiState, VaultListViewModel, VaultListScreen）
    │   ├── detail/      # 凭据详情（EntryDetailUiState, EntryDetailViewModel, EntryDetailScreen）
    │   ├── edit/        # 凭据添加/编辑（EntryEditUiState, EntryEditViewModel, EntryEditScreen）
    │   ├── importer/    # 导入结果报告（ImportUiState, VaultImportController, ImportReportDialog）
    │   ├── authenticator/ generator/ conflict/ database/   # TOTP、密码生成、冲突解决、建库/选库
    │   └── settings/    # 设置中心（SettingsUiState, SettingsViewModel, SettingsScreen）
    └── theme/           # Material 3 主题系统
```

> **包名注意**：导入结果界面所在包为 `ui/screens/importer`（**不是** `import`）——
> `import` 是 Java 关键字，KSP 会直接以
> `The name 'import' cannot be used as a package name because it is a Java keyword` 拒绝掉整个注解处理阶段。

- **数据层（Data Layer）**：由 `data/repository/` 提供响应式 `Flow` 数据流，屏蔽上层对底层数据库或内存缓存的具体实现细节。
- **状态容器（State Holders）**：每个页面配备独立 `@HiltViewModel`，通过不可变 `UiState` 数据类与 `StateFlow` 承载页面完整状态，遵循单向数据流（UDF）。
- **界面分离**：Screen 拆分为 Stateful 路由（收集状态、处理副作用与导航）与 Stateless 内容组件（纯渲染、高可预览与可测试）。
- **平台集成**：`autofill/`（AutofillService 与表单解析）与 `passkey/`（Credential Manager 接入）已按既定方向在 app 内落位为**内部包**（不为它们单独建 Gradle 模块）；
  **生物识别解锁不单独设包**，其状态机与策略落在 `ui/screens/unlock/`（`BiometricAutoPrompt` / `BiometricFailureMessagePolicy` / `UnlockModePolicy` 等）；
  平台侧另设 `notification/`（通知通道、权限闸门与发布器）与 `security/`（运行时完整性、窗口防截屏）。

## 5. 参考项目

- `参考项目/KeePassDX-master`：`database`/`crypto` 模块边界、kdbx 版本兼容、WebAuthn 凭据存储的直接参考。
- `参考项目/keepass2android-main`：文件存储抽象、本地缓存与冲突处理（`CachingFileStorage`）的参考。
- `参考项目/Monica-main`：Kotlin / Compose 工程结构与约定参考。

> 参考项目仅用于学习与架构借鉴，注意其各自的许可证约束；本仓库代码独立编写。

---

## 6. 技术栈与模块目录

### 技术栈

| 领域 | 选型 |
|------|------|
| 系统基准 | Android API 36+（`minSdk 36`, `compileSdk 37`, `targetSdk 36`），仅 Android 16+ 深度优化 |
| 语言 | Kotlin 2.4.20（Compose 编译器随 Kotlin 一同发布；`ISSUE-P3-32` 为修复 `CVE-2026-53914` 由 2.4.10 升至此版） |
| 构建 | Gradle 9.7.1（Wrapper）+ AGP 9.4.0，Kotlin 2.4.20，依赖版本统一由 `gradle/libs.versions.toml` 管理 |
| UI | Jetpack Compose（Material 3 / M3 Expressive） |
| 异步 | Kotlin Coroutines + Flow |
| 依赖注入 | Hilt 2.60.1（KSP 2.3.11，AGP 9 内置 Kotlin） |
| 本地缓存 | 自研 `SyncCache`（三哈希磁盘布局 + 原子写盘）；未引入 Room |
| 数据库解析 | 自研 `database` 模块：KDBX v4 全链路流式解析 / 写回 |
| 加密 | AES-256 / Twofish / ChaCha20，Argon2d/id / AES-KDF（SHA-256）派生（BouncyCastle） |
| 网络 | OkHttp（TLS-only）；WebDAV 走 XML/PROPFIND，S3 走自研 AWS SigV4；未引入 ktor |
| 生物识别 / 自动填充 / 通行密钥 | AndroidX Biometric / Autofill Framework / Credential Manager + FIDO2 |

### 模块目录

```
app/                 # 应用壳：导航、入口、Hilt、平台集成
 ├── ui/             # Compose 界面与状态
 ├── biometric/      # 生物识别解锁（app 内部包）
 ├── autofill/       # 系统自动填充（app 内部包）
 ├── passkey/        # 通行密钥认证接入（app 内部包）
 └── di/             # 依赖注入
core/                # 共享基础层：领域模型、工具
crypto/              # 加密层：分组加密、KDF、KDBX 块流；含原生 Argon2（src/main/rust/，Rust + cargo-ndk 交叉编译）
database/            # 数据库层：kdbx 解析、条目/分组模型、搜索、合并、通行密钥凭据存储
sync/                # 同步层：文件存储抽象 + WebDAV / S3 兼容实现
```

**同步抽象层**：定义统一的 `SyncProvider` 接口（连接、拉取、推送、冲突检测），WebDAV 与 S3 各自实现，便于后续扩展更多后端。
