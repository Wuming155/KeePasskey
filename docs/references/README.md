# 参考项目总览

本目录是 KeePasskey 项目的实现思路参考库，收录了 5 个开源密码管理器项目。**目录内各项目源码只读，仅作借鉴，严禁修改或将其代码复制入库**（许可证约束，详见各项目自带 LICENSE / license 文件）。

## 项目列表

| 项目 | 平台 / 语言 | 简介 | 架构分析文档 |
|------|-------------|------|--------------|
| **KeePass-2.61.1-Source** | Windows 桌面 / C# (.NET) | KeePass 官方桌面版，.kdbx 格式的"标准定义者"，`KeePassLib` 是所有第三方实现的事实参考 | [KeePass-2.61.1-架构分析.md](KeePass-2.61.1-架构分析.md) |
| **KeePassDX-master** | Android / Kotlin + Java + JNI(C) | 轻量级 Android KeePass 客户端，支持 kdb/kdbx v1–v4、通行密钥、生物识别解锁、TOTP、Autofill | [KeePassDX-架构分析.md](KeePassDX-架构分析.md) |
| **keepass2android-main** | Android / C# (Xamarin) + Java 绑定 | 功能最全的 Android KeePass 客户端之一，内置多网盘同步（Dropbox / OneDrive / WebDAV / SFTP 等）、键盘与 Autofill 填充 | [keepass2android-架构分析.md](keepass2android-架构分析.md) |
| **Monica-main** | Android (Kotlin) + Rust | 现代 Android 密码管理器，Material 3 + Compose 风格 UI；附带的 `mdbx` 是其 Rust 实现的本地优先加密 vault 格式（含类 Git 历史、同步冲突处理、快照恢复） | [Monica-架构分析.md](Monica-架构分析.md) |
| **keepassxc-develop** | 跨平台桌面 / C++ (Qt) + CMake | KeePassXC（develop 分支），KeePass 生态中质量最高的社区实现：KDBX 3/4 读写、Argon2/AES-KDF、Twofish/ChaCha20、浏览器集成与通行密钥、`Merger` KDBX 合并引擎 | [KeePassXC-架构分析.md](KeePassXC-架构分析.md) |

## 参考优先级层级（严格执行）

1. 🥇 **第 1 优先级（核心参考）**：**KeePassDX**
   - 原生 Android Kotlin 实现，模块边界（`app` / `database` / `crypto`）完全同构。重点参考数据模型、`DatabaseSession` 生命周期管理、Argon2 策略与 Passkey。
2. 🥈 **第 2 优先级（次核心参考）**：**keepass2android**
   - 重点参考其在 Android 上沉淀的工业级同步能力：`IFileStorage` 插件化抽象、WebDAV/S3 适配、本地离线缓存、ETag 与三方冲突检测机制、Quick Unlock 快速解锁。
3. ⚖️ **标准实现参考（格式裁决者）**：**KeePass-2.61.1 (官方 C#)**
   - `.kdbx`（v3/v4）格式事实标准。不参考其 UI，仅在格式解析、二进制头字段、Inner Random Stream 或 XML 节点规范出现歧义时作为权威终审标准。
4. ⚖️ **算法级参考（合并引擎与通行密钥 schema）**：**KeePassXC (C++/Qt)**
   - `Merger`（`src/core/Merger.cpp`）的条目级合并与墓碑复活规则是 `KdbxMerger` 的直接算法参考；浏览器集成中的 `KPEX_PASSKEY_*` Entry 属性 schema 对 `PasskeyData` 互操作有直接价值；KDBX 3/4 读写器管线可作为 `database` 模块的实现交叉验证。
5. 🥉 **辅助参考（不做重点）**：**Monica**
   - 辅助参考，不做重点。仅用于拓宽现代 Compose UI 动效与本地优先数据流的设计思路。

## 各项目对 KeePasskey 的参考价值

- **KeePass（C#）**：理解 .kdbx v4 文件格式、加密管线（AES-KDF / Argon2 KDF、内层流加密、Header/HMAC 校验）的最权威来源；KeePasskey 的 `crypto` 模块以其 `KeePassLib` 为主要参照。
- **KeePassDX**：模块划分（`app` / `database` / `crypto`）与 KeePasskey 的规划最接近，其 Compose 化改造、通行密钥存储、DatabaseSession 生命周期管理是 `database` 模块与 UI 层的直接参照。
- **keepass2android**：WebDAV / 云同步、文件存储抽象层（`FileStorage` 插件体系）是 KeePasskey `sync` 模块（WebDAV / S3）的主要借鉴对象；其 Autofill 与键盘填充实现也很成熟。
- **Monica**：代表"新式" Android 密码管理器的 UI/UX 与状态管理实践；`mdbx` 子项目的本地优先 vault 设计（逻辑历史、冲突解决、附件、快照）对 KeePasskey 的同步模型设计有前瞻参考意义。
- **KeePassXC**：C++ 实现的 kdbx 读写管线（`KdbxReader`/`KdbxWriter`）与 **`Merger` 冲突合并引擎**是 KeePasskey `KdbxMerger` 的直接算法参考；其浏览器集成中的通行密钥（passkey）协议实现可作为 Android Credential Provider 侧数据模型的对照。

## 使用方式

1. 实现某模块前，先查阅 `docs/reference-projects.md` 中的参考项目地图，定位应参照的具体文件。
2. 再阅读本项目录中对应的架构分析文档，建立整体认识。
3. **只读约束**：任何操作不得改动本目录下 5 个项目目录中的任何文件；新增文档只允许放在本目录根下。
