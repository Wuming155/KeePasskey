# KeePasskey

> 用 Kotlin 开发的 Android 版 KeePass 密码管理器，支持 **WebDAV** 与 **S3 兼容协议** 同步，原生支持 **通行密钥（Passkey）**，并覆盖 KeePass 的常用功能。

---

## 1. 项目目标

| 目标 | 说明 |
|------|------|
| **KeePass 兼容** | 读写标准 `.kdbx`（v2/v3/v4）数据库，可与 KeePass / KeePassXC / KeeWeb 互通 |
| **多方式同步** | 支持 **WebDAV**（Nextcloud、ownCloud 等）与 **S3 兼容协议**（AWS S3、MinIO、Cloudflare R2 等）的云端同步 |
| **通行密钥** | 支持 FIDO2 / WebAuthn 通行密钥的存储与作为解锁 / 认证方式 |
| **全功能** | 条目/分组管理、TOTP、附件、模板、历史、搜索、自动填充、生物识别解锁等 |
| **UI 优先** | 第一步先构建并打磨 UI 与交互流程，UI 确认合适后再实现具体功能逻辑 |

---

## 2. 技术栈

| 领域 | 选型 |
|------|------|
| 系统基准 | **Android API 36+**（`minSdk 36`, `compileSdk 36`, `targetSdk 36`），仅针对 Android 16+ 深度优化，无需向下兼容负担 |
| 语言 | **Kotlin** |
| UI | **Jetpack Compose**（Material 3），优先声明式、可预览的 Compose 方案 |
| 异步 | **Kotlin Coroutines + Flow** |
| 依赖注入 | **Hilt**（或 Koin） |
| 本地缓存 | **Room**（同步状态、最近文件、UI 元数据，不存明文） |
| 数据库解析 | 自研或封装 KeePass 解析（参考 KeePassDX 的 `database` 模块） |
| 加密 | AES / Twofish / ChaCha20 分组加密，Argon2 / SHA-256 KDF（参考 KeePassDX `crypto` 模块） |
| 网络 | **OkHttp + ktor**，WebDAV 走 HTTP/XML，S3 走 AWS SDK / MinIO SDK |
| 生物识别 | AndroidX Biometric |
| 自动填充 | Android Autofill Framework + 自定义键盘 |
| 通行密钥 | Android Credential Manager / FIDO2 API |

---

## 3. 模块架构（规划）

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

### 4.1 数据库与解锁
- [ ] 创建 / 打开 `.kdbx` 数据库（v2 / v3 / v4）
- [ ] 主密码、密钥文件（key file）、Windows Hello / 生物识别解锁
- [ ] AES / Twofish / ChaCha20 加密算法
- [ ] Argon2（d/k）、SHA-256 KDF
- [ ] 与 KeePass / KeePassXC / KeeWeb 文件互通

### 4.2 条目与分组
- [ ] 分组树结构（无限层级）
- [ ] 条目字段：标题、用户名、密码、URL、备注、自定义字段
- [ ] 图标（内置图标 + 自定义图标）
- [ ] 附件（文件嵌入）
- [ ] 条目历史版本与恢复
- [ ] 模板（动态条目类型）
- [ ] 回收站
- [ ] 全文搜索与过滤

### 4.3 增强功能
- [ ] **TOTP / HOTP**（两步验证，扫码/手动添加）
- [ ] 密码生成器（强度评估）
- [ ] 自动填充（Autofill Framework）
- [ ] 自定义键盘（Magikeyboard 式字段填充）
- [ ] 条目克隆 / 移动 / 锁定

### 4.4 同步
- [ ] **WebDAV** 同步（账号、URL、路径、HTTPS）
- [ ] **S3 兼容协议** 同步（Endpoint、Bucket、Access Key/Secret、区域）
- [ ] 同步状态展示（最新同步时间、云端版本）
- [ ] 冲突处理：本地优先 / 云端优先 / 重命名另存
- [ ] 后台自动同步与手动同步

### 4.5 通行密钥（Passkey）
- [ ] 在数据库中安全存储 FIDO2 / WebAuthn 凭据
- [ ] 使用通行密钥作为数据库解锁方式之一
- [ ] 通过 Credential Manager 创建 / 调用通行密钥

---

## 5. 开发路线图

> 核心原则：**先 UI，后功能**。先把界面与交互流程搭好并确认合适，再逐层接入真实数据。

### 阶段 0：工程脚手架
- [ ] 初始化 Android 工程（Gradle Kotlin DSL）
- [ ] 接入 Compose、Hilt、Coroutines、Room、OkHttp 等依赖
- [ ] 配置 Material 3 主题（浅色 / 深色 / 动态配色）
- [ ] 确定包结构与模块划分

### 阶段 1：UI 优先（当前第一步）⭐
目标：用**静态 / 假数据**搭建全部界面与导航，确认交互与视觉。

- [ ] **导航骨架**：启动页 → 数据库列表 → 解锁 → 主界面（分组/条目）
- [ ] **数据库列表页**：已添加数据库卡片、添加数据库入口
- [ ] **解锁页**：主密码输入框、密钥文件选择、生物识别按钮
- [ ] **主列表页**：分组树 + 条目列表 + 搜索栏 + 排序/过滤
- [ ] **条目详情页**：字段展示、复制、显示/隐藏密码、附件、历史
- [ ] **条目编辑页**：各字段输入、图标选择、自定义字段增删
- [ ] **分组页**：分组创建/重命名/移动
- [ ] **设置页**：主题、默认打开、同步管理入口
- [ ] **同步配置页**：WebDAV / S3 表单（仅 UI）
- [ ] **通行密钥页**：通行密钥列表与添加（仅 UI）
- [ ] 统一组件库：密码可见切换、复制按钮、空状态、加载态、对话框
- [ ] 在设备/模拟器上走查完整流程，确认 UI 合适

### 阶段 2：数据库核心
- [ ] kdbx 解析与写入（接入 `database` 模块）
- [ ] 主密码 / 密钥文件 / KDF 校验解锁
- [ ] 条目与分组的增删改查（接真实模型）
- [ ] 附件、历史、模板、搜索

### 阶段 3：生物识别与自动填充
- [ ] 生物识别解锁（AndroidX Biometric）
- [ ] Autofill 框架接入
- [ ] 自定义键盘填充

### 阶段 4：同步实现
- [ ] 抽象 `SyncProvider` 接口
- [ ] WebDAV 拉取 / 推送 / 冲突处理
- [ ] S3 兼容协议接入（MinIO SDK / AWS SDK）
- [ ] 同步状态与后台同步

### 阶段 5：通行密钥
- [ ] FIDO2 / WebAuthn 凭据存储
- [ ] Credential Manager 集成
- [ ] 通行密钥作为解锁方式

### 阶段 6：打磨与发布
- [ ] TOTP、密码生成器完善
- [ ] 性能、加密正确性校验
- [ ] 多语言、无障碍
- [ ] 构建发布（F-Droid / GitHub Release）

---

## 6. 参考项目

本仓库 `参考项目/` 目录收录了可供参考的成熟实现：

- **KeePassDX**（`参考项目/KeePassDX-master`）：Kotlin 实现的 Android KeePass 应用，包含 `database`、`crypto` 模块，最贴近本项目。
- **keepass2android**（`参考项目/keepass2android-main`）：功能丰富的 Java 实现，可参考同步与自动填充方案。
- **Monica**（`参考项目/Monica-main`）：Kotlin 项目，可参考通用工程结构与 Compose 实践。

> 仅作为学习与架构参考，注意各自的开源许可证约束。

---

## 7. 许可证

本项目计划以开源方式发布（具体许可证待定，参考 GPLv3 等）。
