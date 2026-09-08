# KeePasskey

> 用 Kotlin 开发的 Android 版 KeePass 密码管理器，支持 **WebDAV** 与 **S3 兼容协议** 同步，原生支持 **通行密钥（Passkey）**，并覆盖 KeePass 的常用功能。

---

## 1. 项目目标

| 目标 | 说明 |
|------|------|
| **KeePass 兼容** | 读写标准 `.kdbx` **v4** 数据库（v2/v3 抛出 `KdbxUnsupportedVersionException` 明确拒绝），可与 KeePass / KeePassXC / pykeepass 互通 |
| **多方式同步** | 支持 **WebDAV**（Nextcloud、ownCloud 等）与 **S3 兼容协议**（AWS S3、MinIO、Cloudflare R2 等）的云端同步 |
| **通行密钥** | 支持 FIDO2 / WebAuthn 通行密钥的存储与作为解锁 / 认证方式 |
| **全功能** | 条目/分组管理、TOTP、附件、模板、历史、搜索、自动填充、生物识别解锁等 |
| **UI 优先** | 第一步先构建并打磨 UI 与交互流程，UI 确认合适后再实现具体功能逻辑 |

---

## 2. 技术栈

| 领域 | 选型 |
|------|------|
| 系统基准 | **Android API 36+**（`minSdk 36`, `compileSdk 36`, `targetSdk 36`），仅针对 Android 16+ 深度优化，无需向下兼容负担 |
| 语言 | **Kotlin 2.4.10**（Compose 编译器随 Kotlin 一同发布） |
| 构建 | Gradle 9.3.1（Wrapper）+ AGP 9.1.0 |
| UI | **Jetpack Compose**（Material 3），优先声明式、可预览的 Compose 方案 |
| 异步 | **Kotlin Coroutines + Flow** |
| 依赖注入 | **Hilt 2.60.1**（当前 kapt，批次 D 规划迁移 KSP，见 `docs/HEALTH_CHECK_ROADMAP.md`） |
| 本地缓存 | 自研 `SyncCache`（三哈希磁盘布局 + 原子写盘，位于 `sync` 模块）；**未引入 Room** |
| 数据库解析 | 自研 `database` 模块：KDBX **v4** 全链路流式解析 / 写回（参考 KeePassDX `database` 与 KeePass 2.61.1 官方 C#） |
| 加密 | AES-256 / Twofish / ChaCha20 分组加密，Argon2d / Argon2id / AES-KDF（SHA-256）派生（BouncyCastle） |
| 网络 | **OkHttp**（TLS-only，全站无明文豁免）；WebDAV 走 XML / PROPFIND，S3 走自研 AWS SigV4 签名；**未引入 ktor** |
| 生物识别 | AndroidX Biometric |
| 自动填充 | Android Autofill Framework + 自定义键盘 |
| 通行密钥 | Android Credential Manager / FIDO2 API |

---

## 3. 模块架构（已落地）

```
app/                 # 应用壳：导航、入口、Hilt、平台集成
 ├── ui/             # Compose 界面与状态（第一步重点）
 ├── biometric/      # 生物识别解锁（app 内部包）
 ├── autofill/       # 系统自动填充（app 内部包）
 ├── passkey/        # 通行密钥认证接入（app 内部包）
 └── di/             # 依赖注入
core/                # 共享基础层：领域模型、工具
crypto/              # 加密层：分组加密、KDF、KDBX 块流
database/            # 数据库层：kdbx 解析、条目/分组模型、搜索、合并、通行密钥凭据存储
sync/                # 同步层：文件存储抽象 + WebDAV / S3 兼容实现
```

> 详细模块依赖与关键架构决策见 `ARCHITECTURE.md`。

**同步抽象层**设计要点：定义统一的 `SyncProvider` 接口（连接、拉取、推送、冲突检测），WebDAV 与 S3 各自实现，便于后续扩展更多后端。

---

## 4. 功能清单（KeePass 全功能）

> 勾选状态与代码一致（2026-09-07 全量核对）。未勾选项在「已知未实现」中列出。

### 4.1 数据库与解锁
- [x] 创建 / 打开 `.kdbx` 数据库（**仅 v4**；v3 及以下明确拒绝）
- [x] 主密码、密钥文件（key file：`KdbxKeyFile` 四级解析梯子）、生物识别解锁
- [x] AES-256 / Twofish / ChaCha20 加密算法
- [x] Argon2（d / id）、AES-KDF（SHA-256）派生
- [x] 与 KeePass / KeePassXC / pykeepass 文件互通（真实复合密钥库解锁 + 第三方往返校验已实测）
- [x] 只读模式打开（会话期写盘硬拒绝）

### 4.2 条目与分组
- [x] 分组树结构（无限层级）
- [x] 条目字段：标题、用户名、密码、URL、备注、自定义字段（含 tags / overrideUrl / AutoType）
- [~] 图标：**内置图标选择已完成**；自定义图标上传 / 选择 UI 未实现（模型与序列化层已就绪）
- [x] 附件（文件嵌入、增删导）
- [x] 条目历史版本与恢复（含全字段回滚、Visual Diff 比对）
- [x] 模板（预置网页登录 / 银行卡等常用模板，设置页一键安装）
- [x] 回收站（KDBX 标准库内回收站 + 墓碑 + `previousParentGroup` 还原）
- [x] 全文搜索与过滤（防抖 + 回收站过滤）

### 4.3 增强功能
- [x] **TOTP / HOTP**（`OtpEngine` 支持 SHA-1/256/512，扫码与手动添加，RFC 4226/6238 官方向量校验）
- [x] 密码生成器（强度评估 + Diceware 词表）
- [x] 自动填充（Credential Provider + Autofill 双通道，含 IME 内联建议）
- [ ] 自定义键盘（Magikeyboard 式字段填充）：未实现，当前仅提供 IME 内联建议
- [x] 条目移动 / 只读锁定；**条目克隆未实现**
- [x] 密码健康度离线审计（`HealthCheckEngine`）

### 4.4 同步
- [x] **WebDAV** 同步（账号、URL、路径，全站强制 HTTPS）
- [x] **S3 兼容协议** 同步（Endpoint、Bucket、Access Key/Secret、区域、path-style）
- [x] 同步状态展示（最新同步时间、云端版本、同步反馈）
- [x] 冲突处理：三方哈希状态机 + 墓碑感知合并 + 可视化逐字段决策界面
- [ ] 后台自动同步与手动同步：手动同步与冷启动同步已完成；**周期性后台同步仅有设置项，WorkManager 调度未实现**
- [ ] KeePass 字段引用（`{REF:...}`）引擎：未实现

### 4.5 通行密钥（Passkey）
- [x] 在数据库中安全存储 FIDO2 / WebAuthn 凭据（对齐 KeePassXC `KPEX_PASSKEY_*` 属性 schema）
- [x] 通过 Credential Manager 创建 / 调用通行密钥（ES256 / Ed25519 / RS256 三算法）
- [ ] 使用通行密钥作为数据库解锁方式之一：未实现（快速解锁为设备锁屏凭据绑定密钥）

---

## 5. 开发路线图

> 核心原则：**先 UI，后功能**。先把界面与交互流程搭好并确认合适，再逐层接入真实数据。
> 全生命周期 7 大阶段的完整交付物清单、任务拆解与发布验收门禁曾收录于已归档的 `DELIVERY_PLAN.md`（已于 2026-09-07 并入 `docs/STATUS.md`）。
> **进度（2026-09-07 核对）**：7 大阶段已于 2026-09-04 全部竣工（git `b0cdc89`）；此后专项整改（含已冻结的旧 Wave 编号体系）的逐轮记录以 `docs/STATUS.md` §4「历史改动日志索引」为唯一追溯入口；实时未完成任务看板见 `docs/STATUS.md` §2。

### 阶段 0：工程脚手架
- [x] 初始化 Android 工程（Gradle Kotlin DSL）
- [x] 接入 Compose、Hilt、Coroutines、OkHttp 等依赖（Room 经评估未引入，本地缓存改用自研 `SyncCache`）
- [x] 配置 Material 3 主题（浅色 / 深色 / 动态配色，另含 5 套品牌配色与 OLED 纯黑优化）
- [x] 确定包结构与模块划分（5 模块架构）

### 阶段 1：UI 优先（已完成）
目标：用**静态 / 假数据**搭建全部界面与导航，确认交互与视觉。

- [x] **导航骨架**：启动页 → 数据库列表 → 解锁 → 主界面（分组/条目）
- [x] **数据库列表页**：已添加数据库卡片、添加数据库入口（多密码库管理：创建 / 移除 / 导入外部库）
- [x] **解锁页**：主密码输入框、密钥文件选择、生物识别按钮、快速解锁（Quick Unlock）
- [x] **主列表页**：分组树 + 条目列表 + 搜索栏 + 排序/过滤 + 批量操作
- [x] **条目详情页**：字段展示、复制、显示/隐藏密码、附件、历史、Visual Diff 差异比对与回滚
- [x] **条目编辑页**：各字段输入、图标选择、自定义字段增删
- [x] **分组页**：分组创建/重命名/移动/图标更换
- [x] **设置页**：主题、默认打开、同步管理入口（含 9 个子页：主题 / 安全 / WebDAV与S3 / 自动填充 / 数据库 / 健康检查 / TOTP / 调试 / 关于）
- [x] **同步配置页**：WebDAV / S3 表单（仅 UI）
- [x] **独立模块页**：独立双重认证（Authenticator）、全功能密码生成器（Generator）、云同步双栏冲突合并（Conflict Resolver）
- [x] 统一组件库：密码可见切换、复制按钮、空状态、加载态、对话框（`AppBottomBar`、`AppNavigationRail`、`BentoCard`、`IconPickerDialog`、`SecurityBadge` 等）
- [x] 中英双语字符串资源（`values` / `values-en` 均各 772 行，完全对齐）
- [x] 在设备/模拟器与代码层走查完整流程，确认 UI 完备可用

### 阶段 2：密码学核心与 KDBX 数据库引擎（已完成 ✅）
- [x] `crypto`：BouncyCastle + AES-256 / ChaCha20 / Twofish 分组加密
- [x] `crypto`：Argon2d/id 与 AES-KDF（SHA-256）派生，敏感数据显式清零
- [x] `database`：KDBX **v4** 头部解析与 Payload 加解密（全链路流式：HMAC 块流 → 加密流 → GZip → XML）
- [x] `database`：XML 流式解析 / 序列化，映射 Group / Entry 领域模型
- [x] `database`：`DatabaseSession` 状态机维护与原子写盘（tmp → sync → rename + .bak 滚动备份）
- [x] 数据层替换：`RealVaultRepository` 替换 `FakeVaultRepository`（Fake 出库至 `src/test`）

### 阶段 3：生物识别与防御性安全加固（已完成 ✅）
- [x] 生物识别解锁（AndroidX Biometric，Class 3 强验证 + Keystore 硬件封装）
- [x] FLAG_SECURE / 剪贴板自动擦除 / 自动锁定熔断 / overlay 攻击防护（Wave 16）
- [x] Autofill 框架接入

### 阶段 4：通行密钥与 Credential Manager 自动填充（已完成 ✅）
- [x] FIDO2 / WebAuthn 凭据在 KDBX 中的安全存储
- [x] Credential Manager 集成（`KeePasskeyCredentialProviderService`，API 36+）
- [x] Origin / RP-ID 强绑定（`CallingOriginResolver`）

### 阶段 5：多协议云同步与三方冲突合并（已完成 ✅）
- [x] 抽象 `SyncProvider` 接口（连接 / 元数据 / 下载 / 上传 / 删除）
- [x] WebDAV 拉取 / 推送 / 冲突处理（ETag 乐观锁 + uploadAtomic 事务写）
- [x] S3 兼容协议接入（自研 AWS SigV4，非 SDK）
- [x] 同步状态展示与手动 / 冷启动同步
- [ ] 周期性后台同步（WorkManager）：**未实现**，仅设置项就绪

### 阶段 6：KeePass 高级特性与全功能工具箱（已完成 ✅）
- [x] TOTP / HOTP 引擎、密码生成器（含 Diceware）
- [x] 附件管理、版本历史与回滚、密码健康度审计、条目模板
- [x] 多语言（中英）

### 阶段 7：质量工程、测试基线、混淆加固（已完成 🏁）
- [x] 全模块 **417 个单元测试全绿**（app 99 / core 21 / crypto 42 / database 146 / sync 109）
- [x] R8 全量混淆 `assembleRelease` 构建闭环 + 敏感内存方法防剥离 keep 规则
- [x] 官方标准向量校验（RFC 4226 / 6238、RFC 4231、AWS SigV4、KDBX 互操作）
- [ ] 构建发布（F-Droid / GitHub Release）：**未发布**，仅构建链路就绪

---

## 5.1 已知未实现（如实记录，勿据勾选状态误判）

> 以下为面向用户的**已知局限摘要**；完整的实时未实现 / 未修复任务清单以 `docs/STATUS.md` §2「未完成工作唯一看板」为唯一真相源（本表不重复维护状态）。

| 项 | 现状 |
|---|---|
| 自定义图标上传 / 选择 UI | 模型与序列化层完好，无 UI |
| KeePass 字段引用 `{REF:...}` | 引擎未实现 |
| 自定义键盘（Magikeyboard 式） | 未实现；已提供 IME 内联建议作为替代 |
| 条目克隆（duplicate） | 未实现 |
| 周期性后台同步（WorkManager） | 仅有间隔 / 仅 Wi-Fi 设置项，无调度实现 |
| Passkey 作为数据库解锁方式 | 未实现；快速解锁为设备锁屏凭据绑定密钥 |
| KDBX v3 及以下读写 | 明确拒绝（`KdbxUnsupportedVersionException`） |
| 应用发布 | 构建链路就绪，未完成 F-Droid / GitHub Release 发布 |

> 其余工程限界（对象树内存驻留、条件写依赖服务端、`ProtectedString` 纵深防御边界等）见 `AGENTS.md`「已知限界」。

---

## 6. 参考项目

本仓库 `参考项目/` 目录收录了 5 个成熟开源实现的源码快照（**只读，仅作借鉴，严禁修改或复制其代码入库**）。下表列出各项目的官方仓库地址与开源许可证：

| 参考项目 | GitHub 仓库 | 许可证 | 在本项目中的参考定位 |
| :--- | :--- | :--- | :--- |
| **KeePassDX**（`KeePassDX-master`） | [Kunzisoft/KeePassDX](https://github.com/Kunzisoft/KeePassDX) | **GPL-3.0** | 同为原生 Android Kotlin 实现，模块划分最贴近；参考 `database` / `crypto` 领域模型、`DatabaseSession` 会话与 WebAuthn/Passkey 接入 |
| **keepass2android**（`keepass2android-main`） | [PhilippC/keepass2android](https://github.com/PhilippC/keepass2android) | **GPL-3.0** | 云同步架构最成熟；参考 WebDAV抽象层、本地缓存、ETag 三方哈希冲突检测与 Quick Unlock |
| **KeePass 官方（2.61.1）**（`KeePass-2.61.1-Source`） | [SourceForge](https://sourceforge.net/projects/keepass/) | **GPL-2.0** | `.kdbx` 格式的权威基准；仅在二进制 Header、XML 树结构歧义时作最终裁决 |
| **KeePassXC**（`keepassxc-develop`） | [keepassxreboot/keepassxc](https://github.com/keepassxreboot/keepassxc) | **GPL-2.0 或 GPL-3.0**（双许可） | `Merger` 条目级合并与墓碑复活规则 → `KdbxMerger`；`KPEX_PASSKEY_*` 属性 schema → `PasskeyData` |
| **Monica**（`Monica-main`） | [Monica-Pass/Monica](https://github.com/Monica-Pass/Monica) | **GPL-3.0** | 辅助参考；现代 Material 3 + Compose 交互与本地优先（Local-first）同步思路 |

各功能的详细参考定位见 `docs/reference-projects.md`；5 个参考项目的深度架构分析文档集中于 `docs/references/`。

---

## 7. 许可证

本项目以 **GNU General Public License v3.0（GPL-3.0）** 开源发布。完整许可证文本见仓库根目录的 [`LICENSE`](LICENSE) 文件。

> **Copyleft 说明**：GPL-3.0 为强 Copyleft 许可证。任何基于本仓库源码的衍生作品（含修改后分发的版本）必须以相同许可证（GPL-3.0）开源，并保留版权与许可证声明。

---

## 8. 致谢

本项目受益于众多优秀的开源项目、规范与库的坚实基础，在此一并致谢：

- **参考项目（架构与设计借鉴，详见 §6）**：[KeePassDX](https://github.com/Kunzisoft/KeePassDX)、[keepass2android](https://github.com/PhilippC/keepass2android)、[KeePass 官方](https://sourceforge.net/projects/keepass/)、[KeePassXC](https://github.com/keepassxreboot/keepassxc)、[Monica](https://github.com/Monica-Pass/Monica)。感谢它们作为开源实现的标杆，为 KDBX 格式、云同步与通行密钥落地提供了权威参照。
- **密码学**：[BouncyCastle](https://www.bouncycastle.org/) 提供的 AES / Twofish / ChaCha20 与 Argon2 等算法实现。
- **网络与同步**：[OkHttp](https://square.github.io/okhttp/)（TLS-only 网络栈）、自研 AWS SigV4 与 WebDAV 实现。
- **平台与依赖注入**：AndroidX（Biometric、Autofill、Credential Manager）、[Jetpack Compose](https://developer.android.com/compose) 与 [Hilt](https://developer.android.com/training/dependency-injection/hilt-android)。
- **KeePass 生态**：感谢 KeePass / KeePassXC 社区维护的 `.kdbx` 标准与 `KPEX_PASSKEY_*` 等属性规范，使跨客户端互通成为可能。

本项目代码为独立编写，仅借鉴上述项目的架构与设计思路。
