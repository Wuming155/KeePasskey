# Monica 密码管理器架构深度分析报告

> **目标参考项目**：`Monica-main`（包含 Android 应用、Rust workspace `mdbx`、规范文档与技术文档）  
> **分析者**：资深 Android + Rust 架构分析师  
> **文档定位**：为 KeePasskey（原生 Kotlin + Jetpack Compose + Hilt + KDBXv4 + WebDAV/S3 + Credential Manager Passkey）提供架构级设计参考与工程实践指南。  
> **版权与许可声明**：参考项目 Monica 基于 GPL-3.0 许可。本文档纯属架构分析、设计提炼与工程总结，不包含任何大段 GPL 代码复制，仅引用少量概念定义与简明结构说明。

---

## 目录

- [1. 整体形态与系统全景](#1-整体形态与系统全景)
  - [1.1 Android 应用与 Rust Vault (mdbx) 的演进关系](#11-android-应用与-rust-vault-mdbx-的演进关系)
  - [1.2 Android 端真实存储全景](#12-android-端真实存储全景)
  - [1.3 整体系统架构图 (Mermaid & ASCII)](#13-整体系统架构图-mermaid--ascii)
- [2. Android 应用架构深度剖析](#2-android-应用架构深度剖析)
  - [2.1 模块与代码包拓扑结构](#21-模块与代码包拓扑结构)
  - [2.2 UI 表现层与 Material 3 主题系统](#22-ui-表现层与-material-3-主题系统)
  - [2.3 ViewModel、单向数据流与零秘密热路径](#23-viewmodel单向数据流与零秘密热路径)
  - [2.4 本地数据持久化（Room 数据库 78 版演进）](#24-本地数据持久化room-数据库-78-版演进)
  - [2.5 加密与密钥管理（SecurityManager & KeyStore）](#25-加密与密钥管理securitymanager--keystore)
  - [2.6 核心功能特性实现机制](#26-核心功能特性实现机制)
- [3. MDBX (Rust Workspace) 架构深度解析](#3-mdbx-rust-workspace-架构深度解析)
  - [3.1 设计哲学：4ever And 4ever 与 Tiga 三安全状态](#31-设计哲学4ever-and-4ever-与-tiga-三安全状态)
  - [3.2 领域模型 (mdbx-core)](#32-领域模型-mdbx-core)
  - [3.3 加密、KDF 与 AEAD Envelope (mdbx-crypto)](#33-加密kdf-与-aead-envelope-mdbx-crypto)
  - [3.4 存储层与 SQLite 逻辑 Schema (mdbx-storage)](#34-存储层与-sqlite-逻辑-schema-mdbx-storage)
  - [3.5 类 Git 逻辑历史与 Commit DAG 模型](#35-类-git-逻辑历史与-commit-dag-模型)
  - [3.6 同步协议、向量时钟与冲突处理 (mdbx-sync)](#36-同步协议向量时钟与冲突处理-mdbx-sync)
- [4. 端到端关键数据流](#4-端到端关键数据流)
  - [4.1 新建 Vault 流程](#41-新建-vault-流程)
  - [4.2 写入/更新条目流程 (WAL 追加写)](#42-写入更新条目流程-wal-追加写)
  - [4.3 历史版本与快照生成/回滚流程](#43-历史版本与快照生成回滚流程)
  - [4.4 跨设备同步与冲突解决流程](#44-跨设备同步与冲突解决流程)
  - [4.5 数据库健康诊断与灾难恢复流程](#45-数据库健康诊断与灾难恢复流程)
- [5. 对 KeePasskey 项目的深度借鉴与架构启示](#5-对-keepasskey-项目的深度借鉴与架构启示)
  - [5.1 UI/UX 与状态管理：零秘密列表热路径设计](#51-uiux-与状态管理零秘密列表热路径设计)
  - [5.2 Credential Manager Passkey 的工程落地与避坑经验](#52-credential-manager-passkey-的工程落地与避坑经验)
  - [5.3 同步冲突、快照与离线变更队列设计](#53-同步冲突快照与离线变更队列设计)
  - [5.4 架构甄别：Monica 特有设计 vs KDBX 标准约束](#54-架构甄别monica-特有设计-vs-kdbx-标准约束)
- [6. 关键源码与规范文档索引](#6-关键源码与规范文档索引)

---

## 1. 整体形态与系统全景

### 1.1 Android 应用与 Rust Vault (mdbx) 的演进关系

在 Monica 项目中，Android 应用与 Rust vault（mdbx）并不是简单的单一 JNI 绑定，而是经历了一套**清晰的多阶段架构演进**：

```text
+---------------------------------------------------------------------------------------------------+
|                                  Monica 架构演进阶段历史                                          |
+---------------------------------------------------------------------------------------------------+
| 阶段 1: 原生 Android 单机密码库 (Kotlin + Room + AES-256-GCM + PBKDF2 100k + KeyStore)           |
|                                         |                                                         |
|                                         v                                                         |
| 阶段 2: 扩展多源同步生态 (引入 Kotpass 支持 .kdbx，引入 Retrofit 对接 Bitwarden API)               |
|                                         |                                                         |
|                                         v                                                         |
| 阶段 3: Rust 性能加速核心 (rust-core / rust-jni: 零秘密列表元数据紧凑传输、模糊搜索与去重)       |
|                                         |                                                         |
|                                         v                                                         |
| 阶段 4: MDBX 本地优先规范落地 (Rust Workspace: mdbx-core, mdbx-crypto, mdbx-storage, mdbx-sync)   |
|                                         |                                                         |
|                                         v                                                         |
| 阶段 5: 双运行时协同 (UniFFI 绑定 mdbx-engine + Kotlin MdbxVaultStore 兼容层，由 Router 动态调度) |
+---------------------------------------------------------------------------------------------------+
```

1. **第一阶段：原生 Android 独立单机密码管理器**  
   使用纯 Kotlin + Room (SQLite) 构建，基于 `SecurityManager` 结合 Android KeyStore 与 AES-256-GCM 进行字段级加密，主密码使用 PBKDF2WithHmacSHA256 (100,000 次迭代) 派生。
2. **第二阶段：多源接入与生态兼容（KeePass / Bitwarden）**  
   引入 `kotpass` 实现对标准 `.kdbx` 数据库文件的读写与 WebDAV/本地同步；引入 Retrofit 实现与 Bitwarden 官方及 Vaultwarden 服务器的 API 对接。
3. **第三阶段：Rust 性能重构与零秘密列表加速（rust-core / rust-jni）**  
   为了解决超大密码库（数千条密码）在 Kotlin/JVM 层的滚动卡顿与全量解密性能开销，开发了 `rust-core`（领域过滤/排序/去重算法）与 `rust-jni`（`libmonica_rust_jni.so`），将无明文的紧凑元数据一次性送入 Rust 进行高效模糊搜索和列表投影。
4. **第四阶段：全新本地优先加密数据库格式 MDBX (mdbx workspace)**  
   设计了一套旨在解决 KDBX 文件全量重写、网盘同步冲突、大附件膨胀等痛点的现代加密数据库规范 `MDBX`（采用 Rust 实现核心 `crates/mdbx-*`，通过 UniFFI 生成 `mdbx-engine` 模块与 `libmdbx_ffi.so`）。
5. **第五阶段：双引擎运行时路由（MdbxRepositoryRouter）**  
   在 Android 端，通过 `MdbxRepositoryRouter` 统一路由 `MdbxVaultStore`（Kotlin 原生实现的 MDBX 1.0 存储）与 `Mdbx2Repository`（Rust 原生 `MdbxVault` UniFFI 运行时），平滑兼容历史版本与前沿 Rust 引擎。

### 1.2 Android 端真实存储全景

Monica Android 应用当前在本地实际并存着**四种数据存储形态**：

| 存储形态 | 底层引擎 / 文件 | 加密机制 | 适用场景 |
| :--- | :--- | :--- | :--- |
| **主本地库 (Local Room)** | SQLite (`password_database.db`) | 敏感字段经 AES-256-GCM Base64 加密，KeyStore 硬件保护 MasterKey | 默认本地密码、安全笔记、银行卡、证件、分类、操作日志、Passkey 元数据 |
| **KeePass 数据库 (KDBX)** | 独立 `.kdbx` 文件 (v3/v4) | Kotpass 库，Argon2d/Argon2id/AES-KDF + AES-256-CBC/ChaCha20 | 用户主动导入或关联的 KeePass 数据库，支持 WebDAV/本地同步 |
| **MDBX 数据库 (.mdbx)** | 独立 `.mdbx` 文件 (SQLite WAL + 自定义加密) | Argon2id + HKDF + XChaCha20-Poly1305 / AES-256-GCM (Tiga 模式) | Monica 自研新一代本地优先数据库，支持增量追加、快照、冲突 DAG |
| **Bitwarden 离线缓存** | Room 表 (`bitwarden_vaults` 等) | 客户端对称密钥加解密，Argon2id/PBKDF2 | 同步自 Bitwarden 云端的离线条目副本 |

### 1.3 整体系统架构图 (Mermaid & ASCII)

```mermaid
graph TD
    subgraph UI_Layer [Android UI 表现层 (Jetpack Compose + Material 3)]
        MainActivity[MainActivity / BaseMonicaActivity]
        NavHost[NavHost & Compose Screens]
        ViewModels[PasswordViewModel / MdbxViewModel / KeePassViewModel]
        ThemeEngine[Monet Dynamic / Catppuccin Theme Engine]
    end

    subgraph Domain_Bridge [领域与路由层]
        RepoRouter[MdbxRepositoryRouter]
        DedupService[DedupMergeService]
        PasskeyService[MonicaCredentialProviderService]
        RustJniFacade[RustPasswordListCore / RustBitwardenKdfCore]
    end

    subgraph Rust_Subsystem [Rust 原生子系统]
        RustCore[rust-core: 零秘密列表投影/搜索/去重]
        RustCrypto[rust-crypto: Argon2id / PBKDF2 JNI]
        MdbxEngineUniFFI[mdbx-engine UniFFI: MdbxVault / SyncWireSession]
    end

    subgraph Data_Storage [本地持久化与外部存储]
        RoomDB[(Room AppDatabase v78: password_database.db)]
        KeyStore[(Android KeyStore: MasterKey & TEE/SE)]
        MdbxFile[(MDBX Vault File: SQLite WAL + AEAD Envelope)]
        KdbxFile[(KeePass KDBX File: Kotpass KDBX4)]
        WebDavRemote[远程同步端: WebDAV / OneDrive / S3]
    end

    UI_Layer --> Domain_Bridge
    Domain_Bridge --> Rust_Subsystem
    Domain_Bridge --> Data_Storage
    Rust_Subsystem --> MdbxFile
    Data_Storage <--> WebDavRemote
```

```text
+-----------------------------------------------------------------------------------+
|                           Monica Android Architecture                             |
+-----------------------------------------------------------------------------------+
|  UI Layer (Jetpack Compose + Material 3 + Navigation Compose)                     |
|    - Screens: PasswordList, SecureItems, MDBXManager, KeePassManager, Passkey...   |
|    - ViewModels: PasswordViewModel, MdbxViewModel, DetailViewModels...             |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
|  Domain & Bridge Layer                                                            |
|    - MdbxRepositoryRouter (路由至 Kotlin MdbxVaultStore 或 Rust Mdbx2Repository) |
|    - Zero-Secret Hot Path (列表与搜索热路径严禁解密密码)                           |
|    - MonicaCredentialProviderService (Android 14+ Passkey Credential Provider)    |
+-----------------------------------------------------------------------------------+
          |                                                   |
          v (JNI / UniFFI)                                     v (Kotlin Direct)
+------------------------------------+  +-------------------------------------------+
|  Rust Workspace & JNI              |  |  Android Native Data Layer                |
|  - rust-core: 搜索/投影/去重       |  |  - Room Database (v78, 25+ Tables)        |
|  - rust-crypto: Argon2id/PBKDF2    |  |  - SecurityManager (KeyStore, AES-GCM)    |
|  - mdbx-engine: MdbxVault UniFFI   |  |  - Kotpass Engine (.kdbx 读写/合并)       |
+------------------------------------+  |  - Sardine WebDAV / MSAL OneDrive Client  |
                                        +-------------------------------------------+
```

---

## 2. Android 应用架构深度剖析

### 2.1 模块与代码包拓扑结构

Monica Android 采用单工程多模块结构（`app`、`mdbx-engine`、`baselineprofile`、`rust-core`、`rust-crypto`、`rust-jni`）。其核心主应用包 `takagi.ru.monica` 的职责划分极其严谨：

- `takagi.ru.monica.ui`: 所有 Jetpack Compose 界面、组件、自定义布局、过渡动画、列表卡片。
- `takagi.ru.monica.viewmodel`: 页面状态持有者，负责业务逻辑驱动与 Coroutine 协程调度。
- `takagi.ru.monica.data`: Room 实体（`PasswordEntry`、`SecureItem` 等）、DAO 接口、TypeConverters、Preferences 封装。
- `takagi.ru.monica.repository`: 仓库层契约与具体实现，包含 `PasswordRepository`、`MdbxRepository`、`MdbxVaultStore`、`Mdbx2Repository` 等。
- `takagi.ru.monica.security`: 核心安全中枢，包含 `SecurityManager`、KeyStore 集成、`SessionManager` 会话管理、锁定策略。
- `takagi.ru.monica.passkey`: Android 14+ Credential Provider API 完整实现，包含服务、鉴权/注册 Activity 及密钥编解码器。
- `takagi.ru.monica.keepass`: KeePass 专有逻辑，包含 `kotpass` 集成、KDBX 导入导出、合并与变更集计算。
- `takagi.ru.monica.mdbx`: MDBX 诊断与日志记录工具。
- `takagi.ru.monica.rustcore`: Rust JNI 的 Kotlin Facade（`RustPasswordListCore`、`RustBitwardenKdfCore`）。
- `takagi.ru.monica.webdav`: WebDAV 客户端操作、自动备份与远程同步任务。

### 2.2 UI 表现层与 Material 3 主题系统

1. **纯声明式 Compose UI**：完全弃用传统 XML Views，100% 采用 Jetpack Compose 构建界面。
2. **Material 3 全套规范**：
   - 深度集成 Material 3 动态配色（Dynamic Color / Monet），在 Android 12+ 上根据系统壁纸自适应提取主色系。
   - 内置 **Catppuccin**（Latte、Frappe、Macchiato、Mocha）与经典自然色系等多套主题，支持深色/浅色/纯黑（OLED 友好）模式。
3. **导航架构 (Navigation Compose)**：
   - 使用 `NavHost` 结合密封类 `Screen`（如 `Screen.Main`、`Screen.PasswordList`、`Screen.AddEditPassword`、`Screen.AddEditTotp`、`Screen.AddEditBankCard` 等）构建单 Activity 路由网络。
   - 使用 `SharedTransitionCompat` 与精确的 `EnterTransition`/`ExitTransition`（平滑横向滑动与淡入淡出）打造丝滑切换体验。

### 2.3 ViewModel、单向数据流与零秘密热路径

Monica 的架构重构经验中，最为关键的性能突破在于**零秘密列表热路径（Zero-Secret Hot Path）**：

1. **旧架构痛点**：早期版本在列表加载、搜索或分类过滤时，会对每个可见项调用解密逻辑。对于 1000+ 条记录的密码库，滚动或搜索会引发大量 AES-GCM 解密，导致严重的 CPU 抢占与掉帧。
2. **零秘密投影机制**：
   - Room 查出的 `PasswordEntry` 列表在传递给 UI 前，保留原始密文，或直接映射为不含敏感密码的轻量级展示模型 `PasswordListProjection`。
   - 列表项渲染、分类筛选、排序、模糊搜索均只基于元数据（标题、用户名、网址、应用包名、备注预览），绝不解密登录密码。
   - 仅当用户发生**显式交互**（点击查看明文、点击复制密码、触发自动填充或发起跨库迁移）时，才触发单条按需解密。
3. **状态管理**：
   - ViewModel 内部通过 Kotlin `StateFlow` / `SharedFlow` 暴露状态，UI 使用 `collectAsStateWithLifecycle()` 进行生命周期安全的消费。

### 2.4 本地数据持久化（Room 数据库 78 版演进）

Monica 的 Room 数据库 `PasswordDatabase.kt` 经历了 78 个版本的迭代演进，展现了高度成熟的企业级本地数据库设计：

```text
+-----------------------------------------------------------------------------------------+
|                       Room Database Entities (version = 78)                             |
+-----------------------------------------------------------------------------------------+
| 核心凭据类:                                                                             |
|   - PasswordEntry                 (常规账号密码、网站、绑定的 TOTP Key、来源外键)       |
|   - SecureItem                    (多态安全项: Note, Bank Card, Document, WiFi, SSH)    |
|   - PasskeyEntry                  (WebAuthn 通行密钥凭据、公钥、私钥密文、RP ID)        |
|   - CustomField                   (动态自定义扩展字段)                                   |
|   - PasswordHistoryEntry          (密码历史变更追溯表)                                   |
| 分类与运维类:                                                                           |
|   - Category                      (用户自定义分类与排序)                                 |
|   - OperationLog                  (审计时间线日志)                                       |
|   - TimelineVersionSnapshot       (条目操作快照备份)                                     |
|   - Attachment                    (跨来源统一附件元数据)                                 |
| 多源同步支持类:                                                                         |
|   - LocalKeePassDatabase / KeepassRemoteSource / KeepassRemoteSyncState                 |
|   - KeePassPendingChange          (KeePass 条目级离线变更暂存队列)                       |
|   - LocalMdbxDatabase / MdbxRemoteSource / MdbxSyncStateEntity                          |
|   - BitwardenVault / BitwardenFolder / BitwardenSend / BitwardenConflictBackup          |
+-----------------------------------------------------------------------------------------+
```

- **多来源统一抽象 (`StorageTarget`)**：
  - 定义了 `StorageTarget` 枚举与标识体系，使得同一个 UI 列表能够无缝展示来自本地 Room、KeePass KDBX 文件、MDBX 数据库与 Bitwarden 云端的所有凭据，实现多库混合管理。

### 2.5 加密与密钥管理（SecurityManager & KeyStore）

`SecurityManager.kt` 构成了 Monica Android 端的数据安全基石：

1. **主密码派生 (PBKDF2)**：
   - 算法：`PBKDF2WithHmacSHA256`。
   - 迭代次数：`100,000` 次高强度迭代。
   - 盐值：每位用户独立生成 16 字节随机 Salt。
   - 哈希验证值安全存储于受硬件保护的 `EncryptedSharedPreferences` 中。
2. **硬件密钥库集成 (Android KeyStore)**：
   - 使用 Android Jetpack Security 的 `MasterKey`（AES-256-GCM scheme）。
   - 在支持 TEE（可信执行环境）或 StrongBox Keymaster（安全芯片 SE）的设备上，主密钥受硬件硬件级保护，私钥永不出芯片。
3. **字段级加密流程**：
   - 算法：`AES-256-GCM`（带认证的 AEAD）。
   - 每次加密动态生成 12 字节加密安全随机 IV。
   - 密文封包采用前缀标记格式：`V2|{Base64(IV + CipherText + Tag)}`，以便于多版本算法平滑演进与向后兼容。
4. **MDK (Master Data Key) 与生物识别会话**：
   - 支持通过系统 BiometricPrompt 校验授权，释放内存中缓存的临时会话密钥，避免用户频繁输入冗长的主密码。

### 2.6 核心功能特性实现机制

| 功能特性 | 实现类与关键组件 | 技术方案与核心要点 |
| :--- | :--- | :--- |
| **TOTP / 2FA 验证器** | `TotpDataResolver`, `OtpCalculator`, MLKit | 基于 RFC 6238 标准计算 30s/60s 动态验证码；支持 SHA-1/SHA-256/SHA-512；支持 Steam Guard 特殊字符映射；支持 MLKit / ZXing 实时扫描二维码。 |
| **密码生成器** | `PasswordGeneratorUtil`, `zxcvbn` | 支持自定义长度、大写、小写、数字、特殊符号、排除易混淆字符；集成 `zxcvbn` 进行密码强度、破解时间与熵值实时评估。 |
| **数据备份与导出** | `BackupPreferences`, WebDAV Zip 规范 | 支持生成标准 `.zip` 或 AES-256-GCM 加密的 `.enc.zip`；内部按 `passwords/*.json`、`notes/*.json` 规范化分块打包，附带 CSV 汇总。 |
| **截屏保护与防录屏** | `BaseMonicaActivity`, `ScreenshotProtectionUtil` | 在 Activity `onCreate` / 设置变更时根据配置调用 `window.setFlags(FLAG_SECURE, FLAG_SECURE)`，从系统层面阻断录屏、截屏和最近任务缩略图泄漏。 |
| **自动锁定策略** | `SessionManager`, `MainAppLockPolicy` | 监听 `onUserInteraction()` 刷新会话计时器；在 `onResume()` 判定超时（如切后台 30 秒或 5 分钟），超时立即清理解密缓存并路由至解锁屏。 |
| **Passkey 通行密钥** | `MonicaCredentialProviderService` | Android 14+ (API 34+) 原生 Credential Provider；响应 `BeginCreateCredentialRequest` 与 `BeginGetCredentialRequest`；支持 WebAuthn 注册与断言签名。 |

---

## 3. MDBX (Rust Workspace) 架构深度解析

`mdbx` 是 Monica 团队为解决传统密码库（如 KDBX）在现代多端同步、大附件存储、因果历史追踪上的局限性而独立设计的本地优先加密数据库格式与 Rust 实现库。

### 3.1 设计哲学：4ever And 4ever 与 Tiga 三安全状态

#### 3.1.1 "4ever And 4ever" 长期兼容性准则
- **核心宗旨**：密码数据库是用户生命周期最长的数据资产之一。旧版本数据库必须**永久可读**，新功能引入必须具备平滑降级路径。
- **不可孤岛化**：实现不得在 schema 迁移中将用户数据破坏为孤岛。数据安全与完整性永远高于代码简化。

#### 3.1.2 "Tiga" 三安全状态模型
MDBX 原生定义了三档安全运行模式（以奥特曼三形态命名，象征不同战斗状态）：

```text
+-------------------+-------------------------------------------------------------------------+
| Tiga 模式 (Mode)   | 密码学参数与系统行为特征                                                |
+-------------------+-------------------------------------------------------------------------+
| Power (强力型)    | 最高安全防护。Argon2id 极高内存/时间成本；内存明文极短驻留；严格禁用明文缓存；|
|                   | 建议强制配合硬件安全密钥 (Security Key) 或 双因子解锁。                 |
+-------------------+-------------------------------------------------------------------------+
| Multi (复合型)    | 平衡默认模式。高强度 Argon2id 参数；适度内存缓存；便携性与高安全性并存； |
|                   | 适合跨多设备与网盘同步场景。                                             |
+-------------------+-------------------------------------------------------------------------+
| Sky (空中型)      | 灵活便携模式。合格基线 KDF 参数；解锁极快；支持便携式密码/PIN/生物识别包装；|
|                   | 针对低算力移动设备优化，兼顾日常便利与核心加密强度。                     |
+-------------------+-------------------------------------------------------------------------+
```

### 3.2 领域模型 (mdbx-core)

MDBX 彻底摒弃了传统密码库“扁平条目袋（Bag of Entries）”的设计，确立了**以 Project 为核心的层级领域模型**：

```text
 [ Project (主容器: 网站/工作空间/银行关系/应用) ]
        |
        +---> [ Entry 1 (Login: 用户名/密码) ]
        |
        +---> [ Entry 2 (TOTP: 二步骤验证码) ]
        |
        +---> [ Entry 3 (Passkey: WebAuthn 凭据) ]
        |
        +---> [ Entry 4 (Secure Note: 安全备注/恢复码) ]
        |
        +---> [ Attachment 1 (原生附件: 证件扫描件/密钥文件) ]
```

- **Project**：用户可感知的顶级业务单元（如 Google 账号、公司 VPN）。包含项目标题、分组、标签、收藏状态及附件摘要。
- **Entry**：Project 下挂载的具体类型化记录（`login`、`note`、`card`、`identity`、`totp`、`passkey`、`ssh-key`、`api-token`）。
- **Attachment**：原生一等公民对象。具备独立 ID、归属 Project/Entry、内容哈希、加密元数据及分块索引。

### 3.3 加密、KDF 与 AEAD Envelope (mdbx-crypto)

MDBX 规范制定了现代、健壮的分层加密体系：

```text
[ 用户输入: 密码 / 密钥文件 / 安全密钥 ]
                  |
                  v  (Argon2id KDF: 参数由 Tiga 模式决定)
        [ 主解锁密钥 (Master Unlock Key) ]
                  |
                  v  (HKDF-SHA-256 派生)
             [ Vault Key ]
                  |
       +----------+----------+----------+
       |                     |          |
       v (HKDF)              v (HKDF)   v (HKDF)
[ Metadata Key ]      [ Record Key ]  [ Attachment Key ]
```

#### AEAD Committed Envelope 格式
为了防止密文在不同记录间被恶意置换（Key-Commitment 攻击），MDBX 强制要求新写入密文采用认证封包格式：

$$\text{Envelope} = \texttt{"MDBXAE1}\backslash 0\texttt{"} \parallel \text{Commitment (HMAC-SHA-256)} \parallel \text{Nonce (24B/12B)} \parallel \text{Ciphertext}$$

其中 `Commitment` 绑定了记录上下文（Table/ID/Epoch）、关联数据（AD）、Nonce 与密文载荷。解密器必须验证 Commitment 成功后方可执行解密。

### 3.4 存储层与 SQLite 逻辑 Schema (mdbx-storage)

MDBX 容器在物理上是一个单一的 `.mdbx` 文件，其底层选用 **SQLite 引擎 + WAL 模式 + 自定义加密层**。

规范在 `06-sqlite-schema-v1.zh-CN.md` 中定义了 16 张核心逻辑表：

| 表名 (Table Name) | 核心职责与设计要点 |
| :--- | :--- |
| `vault_meta` | 存储 Vault ID、`format_version` (如 MDBX-1)、默认 Tiga 模式、活动 Key Epoch、扩展标记。 |
| `projects` | Project 主表。`title_ct` (加密标题)、`summary_ct`、`object_clock`、`head_commit_id`、附件计数。 |
| `entries` | Entry 明细表。`project_id` 外键、`entry_type`、`payload_ct` (加密载荷)、`head_commit_id`。 |
| `attachments` | 附件元数据表。`file_name_ct`、`content_hash`、`storage_mode`、`chunk_count`、大小信息。 |
| `attachment_chunks` | 附件分块表。`attachment_id`、`chunk_index`、`chunk_hash`、`chunk_ct` (加密分块二进制)。 |
| `commits` | 变更提交记录表。`commit_id`、`device_id`、`local_seq`、`vector_clock`、`changed_object_ids_ct`。 |
| `commit_parents` | Commit DAG 边关系表。`(commit_id, parent_commit_id)` 复合主键。 |
| `device_heads` | 记录各设备当前已知的 Head Commit。 |
| `branches` | 逻辑分支引用表。 |
| `object_versions` | 行级快照表。保存对象在特定 commit 时的序列化快照 `snapshot_ct`，用于三方合并。 |
| `tombstones` | 墓碑记录表。`target_object_id`、`delete_clock`，防止多端同步时已被删除的项目被旧数据复活。 |
| `snapshots` | 全库恢复检查点表。`snapshot_ct`、`snapshot_hash`、`base_commit_id`。 |
| `key_epochs` | 密钥轮换纪元表。支持在不重写全部历史的前提下轮换 Vault Key。 |
| `conflicts` | 未解决冲突表。记录并发冲突对象、`base_commit`、`local_commit`、`incoming_commit`。 |
| `unlock_methods` | 用户解锁方式配置表（PIN/Password/Security Key 包装的 Vault Key）。 |
| `project_tags` | 非秘密标签索引关系表。 |

### 3.5 类 Git 逻辑历史与 Commit DAG 模型

MDBX 与传统密码库最本质的区别在于其**内置了类 Git 的因果历史有向无环图 (DAG)**：

1. **单调自增提交**：每次本地写入（新增、修改、删除）均在 `commits` 表追加一条记录，包含设备序列号 `local_seq` 与向量时钟 `vector_clock`。
2. **不可变变更溯源**：每次提交在 `commit_parents` 记录父节点，并在 `object_versions` 中保存该次修改对象的行级快照。
3. **低成本追加**：日常修改仅向 SQLite WAL 写入数 KB 的增量，不重写大附件和无关条目，极大减轻了网盘同步的 I/O 压力。

### 3.6 同步协议、向量时钟与冲突处理 (mdbx-sync)

MDBX 支持去中心化的点对点与网盘同步：

```mermaid
graph TD
    SyncStart[开始同步: 导入 Sync Bundle] --> HashVerify{验证 Bundle Hash & HMAC}
    HashVerify -- 校验失败 --> SafeRollback[安全事务回滚, 报错]
    HashVerify -- 校验通过 --> IngestDAG[解析 Bundle 中的 Commits, 写入 DAG]
    IngestDAG --> TraverseObjects[遍历变更对象列表]
    TraverseObjects --> CheckCausality{因果关系判定: Vector Clock / DAG}
    
    CheckCausality -- Fast-Forward --> ApplyDirect[直接应用 Incoming 变更]
    CheckCausality -- 同一对象并发分叉 --> CheckFieldDiff{比较变更字段}
    
    CheckFieldDiff -- 非秘密字段不重叠 --> Auto3WayMerge[自动三方合并 (Base + Local + Incoming)]
    CheckFieldDiff -- 秘密字段同时被修改 --> CreateConflict[写入 conflicts 表 (状态: unresolved)]
    
    ApplyDirect --> UpdateDeviceHead[更新 device_heads & sync_state]
    Auto3WayMerge --> GenMergeCommit[生成 Merge Commit] --> UpdateDeviceHead
    CreateConflict --> KeepBoth[保留双方版本, 提示用户介入] --> UpdateDeviceHead
```

- **显式冲突保证**：**绝不对秘密字段（如密码、私钥）进行静默的覆盖合并**。一旦发生并发修改，立即在 `conflicts` 表生成记录，保留双方数据，等待用户在 UI 界面显式选择 `Local Wins`、`Incoming Wins` 或手动合并。
- **墓碑机制 (Tombstone)**：删除操作生成带有因果时钟的 Tombstone，即使另一端离线很久后同步，也不会因缺少删除记录而将旧数据重新插入。

---

## 4. 端到端关键数据流

### 4.1 新建 Vault 流程

```text
[用户界面]               [MdbxRepository]           [MdbxCrypto]             [SQLite Storage]
    |                          |                          |                          |
    |-- 1. 选择模式与密码 ---->|                          |                          |
    |   (Tiga: Multi, Pwd)     |-- 2. 派生解锁密钥 ------>|                          |
    |                          |   (Argon2id + HKDF)      |                          |
    |                          |<-- 3. 返回 Vault Key ----|                          |
    |                          |                                                     |
    |                          |-- 4. 初始化 SQLite 数据库 (PRAGMA journal_mode=WAL) |
    |                          |-- 5. 执行 DDL 创建 16 张核心表 -------------------->|
    |                          |-- 6. 写入 vault_meta (MDBX-1 / MDBX-1.0) ---------->|
    |                          |-- 7. 写入 key_epochs & unlock_methods ------------->|
    |                          |-- 8. 写入 Root Commit & Initial Device Head ------->|
    |<-- 9. 返回就绪 Vault ----|                          |                          |
```

### 4.2 写入/更新条目流程 (WAL 追加写)

1. **输入准备**：UI 传入待更新的 `PasswordEntry`（包含所属 `projectId`）。
2. **加密封包**：`MdbxCrypto` 使用从 Vault Key 派生的 Record Key 对明文载荷进行 AEAD 加密，生成带 Commitment 的 `payload_ct`。
3. **开启 SQLite 事务**：
   - `UPDATE/INSERT INTO entries`：更新条目行数据。
   - `UPDATE projects`：更新父项目的 `updated_at` 与 `head_commit_id`。
   - `INSERT INTO commits`：生成新的 `commit_id`，自增 `local_seq`，记录 `vector_clock`。
   - `INSERT INTO commit_parents`：建立与上一 Commit 的父子关系。
   - `INSERT INTO object_versions`：将当前条目的快照写入版本历史表。
   - `UPDATE device_heads`：将当前设备的 Head 指向新 `commit_id`。
4. **提交事务**：SQLite 将修改以追加方式写入 WAL 文件，耗时通常 $< 15\text{ms}$，生成的网盘增量极小。

### 4.3 历史版本与快照生成/回滚流程

- **快照生成 (Create Snapshot)**：系统在达到特定 commit 阈值（或用户手动触发）时，对当前活跃的 Project、Entry、Attachment 状态进行紧凑序列化，经 AEAD 加密后写入 `snapshots` 表，生成快照摘要哈希。
- **快照预览 (Snapshot Structure Preview)**：UI 可以在不修改当前活动数据的前提下，查询指定 `snapshot_id` 或 `commit_id`，在内存中还原当时的树状结构并展示给用户。
- **单条目/全库回滚 (Rollback)**：从 `object_versions` 读取历史快照，生成一个新的“回滚 Commit”（遵循只追加不回滚 DAG 历史原则），安全覆盖当前状态。

### 4.4 跨设备同步与冲突解决流程

1. **打包 (Export Sync Bundle)**：读取自上次同步以来新增的 `commits`、`commit_parents`、`object_versions`、`tombstones` 和 `project_tags`，序列化并使用同步密钥加密，生成 `.mdbx-bundle`。
2. **传输 (Transport)**：通过 WebDAV 或 OneDrive 上传到远端同步目录。
3. **对端接入 (Import Sync Bundle)**：
   - 校验 Bundle 完整性与 HMAC 签名。
   - 重放 Commit DAG。
   - 利用向量时钟判断因果。若无冲突直接 Fast-Forward 应用；若存在字段级并发冲突，将冲突元数据持久化至 `conflicts` 表。
4. **用户介入消冲突**：用户打开“冲突管理”面板，对比 Local 与 Incoming 的字段差异，点击确认后系统生成一个 Merge Commit，平息分叉。

### 4.5 数据库健康诊断与灾难恢复流程

`MdbxHealthGuidance` 与底层诊断引擎提供了系统级自愈机制：

- **诊断检查项**：扫描孤立的 `object_versions`、悬空附件 Chunk、损坏的 Commit 链、缺失的父节点引用、未闭合的 Tombstone。
- **生成修复计划 (MdbxHealthRepairPlan)**：诊断模块生成包含若干 `MdbxHealthRepairItem` 的修复计划，明确列出可修复项目与潜在数据丢失风险。
- **安全应用修复**：用户确认后，在独立只读副本上执行修复演练，校验通过后原子替换主库，确保断电或崩溃场景下不会雪上加霜。

---

## 5. 对 KeePasskey 项目的深度借鉴与架构启示

KeePasskey 是一款基于原生 Kotlin + Jetpack Compose + Hilt 开发的现代 Android 密码管理器，其核心目标是基于**标准 `.kdbx` (v4) 格式**，通过 WebDAV/S3 同步，并在 Android 16+ (API 36+) 上深度集成 Credential Manager Passkey。

Monica 项目在架构、性能与工程细节上为 KeePasskey 提供了极具价值的参考经验：

### 5.1 UI/UX 与状态管理：零秘密列表热路径设计

#### 启示 1：列表与搜索热路径严禁解密秘密
- **Monica 的教训**：在列表滚动、搜索框实时输入、分类切换时，千万不要对条目的密码进行全量解密。
- **KeePasskey 落地建议**：
  - 在 `core`/`database` 模块定义轻量级的 `VaultItemSummary` 或 `PasswordListProjection`，仅包含 `id`、`title`、`username`、`iconRef`、`updatedAt`、`isFavorite` 等公开元数据。
  - Compose 列表 `LazyColumn` 仅绑定 `VaultItemSummary`。
  - 只有在用户点击“眼睛”查看密码、点击“复制”、触发“自动填充”或进入“编辑详情页”时，才通过 `crypto` 模块发起针对单个条目的解密。

#### 启示 2：UI 状态与 Compose 渲染隔离
- 将同步状态、冲突弹窗、批量选择模式等高频变化状态与主列表数据源解耦，避免因同步进度微小变动触发整个界面的 Recomposition。

### 5.2 Credential Manager Passkey 的工程落地与避坑经验

#### 启示 1：PendingIntent 绝对不能添加 `FLAG_ACTIVITY_NEW_TASK`
- **关键细节**（见 `MonicaCredentialProviderService.kt`）：
  ```kotlin
  // 重要：不要使用 FLAG_ACTIVITY_NEW_TASK！
  // Credential Provider API 会自动处理任务栈，
  // 使用 NEW_TASK 会导致 Activity 在错误的任务栈中启动，
  // 从而无法正确返回到调用方应用。
  PendingIntent.getActivity(
      this, requestCode, intent,
      PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
  )
  ```
- KeePasskey 在实现 `CredentialProviderService` 时必须严格遵循此规范，确保 Passkey 注册与认证 Activity 能正确融入调用方（如 Chrome / 目标 App）的系统凭据交互流程。

#### 启示 2：KDBX v4 中 Passkey 的标准化存储规范
- Monica 采用与 **KeePassDX / KeePassXC** 完全互通的自定义字段方案：
  - `KPEX_PASSKEY_USERNAME`：用户名。
  - `KPEX_PASSKEY_PRIVATE_KEY_PEM`：PKCS#8 格式私钥 PEM 字符串。
  - `KPEX_PASSKEY_CREDENTIAL_ID`：Base64 编码的 Credential ID。
  - `KPEX_PASSKEY_USER_HANDLE`：WebAuthn User Handle。
  - `KPEX_PASSKEY_RELYING_PARTY`：依赖方域名（RP ID）。
  - `Passkey`：标记字段。
- KeePasskey 应直接沿用这一业界事实标准，确保在 KeePasskey 中创建的 Passkey 能够被桌面端 KeePassXC 和移动端 KeePassDX 无缝识别和验证。

### 5.3 同步冲突、快照与离线变更队列设计

#### 启示 1：基于 ChangeSet 的离线变更暂存与 Rebase
- **问题**：KDBX 是单文件全量加密格式，在无网或弱网环境下用户进行多次编辑，若远端已发生变更，直接覆盖会导致远端数据丢失。
- **借鉴方案**：借鉴 Monica 的 `KeePassPendingChange` 与 `KeePassChangeSet` 机制：
  1. 本地编辑在未与远端成功同步前，记录为一条增量操作变更集（`PendingChange`）。
  2. 同步触发时，先从 WebDAV/S3 下载远端最新 `.kdbx` 并解密。
  3. 执行三方条目级合并（Three-Way Merge），将本地暂存的 `PendingChange` 线性应用（Rebase）到远端基线上。
  4. 重新加密并原子上传至远端。

#### 启示 2：本地快照与安全回收站
- 即使 KDBX 规范本身没有 Commit DAG，KeePasskey 也可以在本地数据库（Room 缓存或本地元数据）中维护每次修改的局部快照，支持用户在界面上查看字段级修改历史和误删恢复。

### 5.4 架构甄别：Monica 特有设计 vs KDBX 标准约束

在借鉴 Monica 时，必须清醒区分**哪些是 Monica 私有格式 (MDBX) 特有的设计，不应直接套用到标准 KDBX 项目中**：

| 设计维度 | Monica MDBX 专有设计 (不可直搬) | KeePasskey KDBX 4 架构落位标准 |
| :--- | :--- | :--- |
| **底层持久化** | SQLite WAL 模式 + 16 张明文元数据/密文载荷表。 | 单一 `.kdbx` 二进制文件（XML DOM 树封装于加密负载中）。 |
| **增量写盘** | 仅向 SQLite WAL 文件追加数 KB，零全量重写。 | 每次保存必须全量重构 KDBX XML 树、重新计算 HMAC/KDF 并全文件写盘。 |
| **密钥分层** | 动态 Key Epoch 轮换与 Tiga 模式切换。 | 标准 KDBX4 头部（Master Seed + Transform Seed + Argon2 参数）。 |
| **附件机制** | 数据库内 `attachment_chunks` 分块与外部哈希引用。 | KDBX 内部统一存储在 `<Meta><Binaries>` 池中，通过引用 ID 绑定条目。 |
| **技术栈选择** | 混合使用 UniFFI Rust、JNI C-ABI 与 Kotlin。 | 坚持**纯 Kotlin 现代化实现**（Kotpass / 官方协程），保证在 Android 16+ 上的纯粹性与极简依赖。 |

---

## 6. 关键源码与规范文档索引

下表列出了本次架构分析所深入阅读与引用的真实文件清单：

| 类别 | 文件绝对路径 | 核心分析点 / 职责 |
| :--- | :--- | :--- |
| **规范文档** | `D:\GithubWorkplace\KeePasskey\参考项目\Monica-main\mdbx\docs\01-product-spec.zh-CN.md` | MDBX 产品目标、Project/Entry/Attachment 领域模型、4ever 准则 |
| **规范文档** | `D:\GithubWorkplace\KeePasskey\参考项目\Monica-main\mdbx\docs\02-storage-sync-spec.zh-CN.md` | 单文件容器、SQLite WAL 追加、Commit DAG、向量时钟、三方合并 |
| **规范文档** | `D:\GithubWorkplace\KeePasskey\参考项目\Monica-main\mdbx\docs\03-security-spec.zh-CN.md` | Tiga 三安全状态 (Power/Multi/Sky)、AEAD Envelope、KeyStore 与 KDF |
| **规范文档** | `D:\GithubWorkplace\KeePasskey\参考项目\Monica-main\mdbx\docs\06-sqlite-schema-v1.zh-CN.md` | 16 张核心表 Schema、字段定义、索引建议与外键约束 |
| **接入指南** | `D:\GithubWorkplace\KeePasskey\参考项目\Monica-main\mdbx\CLIENT_INTEGRATION_GUIDE.zh-CN.md` | 客户端支持等级、必备管理面板、诊断与快照结构规范 |
| **技术文档** | `D:\GithubWorkplace\KeePasskey\参考项目\Monica-main\documentation\Implementation Doc\Monica-Android-本地存储与加密技术文档.md` | Android Room 数据库、AES-256-GCM、PBKDF2 (100k) 与 KeyStore |
| **技术文档** | `D:\GithubWorkplace\KeePasskey\参考项目\Monica-main\documentation\Implementation Doc\Monica-Android-WebDAV备份格式规范.md` | WebDAV Zip 备份包结构、JSON 条目规范与加密方案 |
| **重构文档** | `D:\GithubWorkplace\KeePasskey\参考项目\Monica-main\Monica for Android\docs\performance-rust-refactor.zh-CN.md` | 零秘密列表热路径、Rust JNI 批量搜索、首屏维护任务分阶段 |
| **验收文档** | `D:\GithubWorkplace\KeePasskey\参考项目\Monica-main\Monica for Android\docs\MDBX_1_ANDROID_ACCEPTANCE.md` | MDBX 1.0 发布边界、SQLite Instrumentation 测试、真机验收矩阵 |
| **Android 核心** | `D:\GithubWorkplace\KeePasskey\参考项目\Monica-main\Monica for Android\app\src\main\java\takagi\ru\monica\security\SecurityManager.kt` | KeyStore MasterKey、EncryptedSharedPreferences、PBKDF2 密码校验 |
| **Android 核心** | `D:\GithubWorkplace\KeePasskey\参考项目\Monica-main\Monica for Android\app\src\main\java\takagi\ru\monica\data\PasswordDatabase.kt` | Room 数据库定义 (Version 78)、实体与 DAO 清单 |
| **Android 核心** | `D:\GithubWorkplace\KeePasskey\参考项目\Monica-main\Monica for Android\app\src\main\java\takagi\ru\monica\repository\MdbxRepositoryRouter.kt` | MDBX 双引擎路由（Kotlin MdbxVaultStore vs Rust Mdbx2Repository） |
| **Android 核心** | `D:\GithubWorkplace\KeePasskey\参考项目\Monica-main\Monica for Android\app\src\main\java\takagi\ru\monica\passkey\MonicaCredentialProviderService.kt` | Android 14+ Credential Provider API、PendingIntent 任务栈规则 |
| **Android 核心** | `D:\GithubWorkplace\KeePasskey\参考项目\Monica-main\Monica for Android\app\src\main\java\takagi\ru\monica\keepass\KeePassDxPasskeyCodec.kt` | KeePassDX/KeePassXC 标准 Passkey 自定义字段编解码 |
| **Rust 源码** | `D:\GithubWorkplace\KeePasskey\参考项目\Monica-main\Monica for Android\rust-core\src\lib.rs` | 零秘密无明文列表数据结构 `PasswordListRecord` 与 `PasswordListProjection` |
| **Rust 源码** | `D:\GithubWorkplace\KeePasskey\参考项目\Monica-main\Monica for Android\rust-jni\src\lib.rs` | 批量 JNI 元数据模糊搜索与 Bitwarden Argon2id/PBKDF2 原生派生 |
| **UniFFI 绑定** | `D:\GithubWorkplace\KeePasskey\参考项目\Monica-main\Monica for Android\mdbx-engine\src\main\java\uniffi\mdbx_ffi\mdbx_ffi.kt` | UniFFI 生成的 Rust MDBX 核心 API 绑定（`MdbxVault` 等） |
