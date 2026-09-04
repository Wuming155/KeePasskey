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
| 语言 | **Kotlin 2.4.10**（Compose 编译器随 Kotlin 一同发布） |
| 构建 | Gradle 9.3.1（Wrapper）+ AGP 9.1.0 |
| UI | **Jetpack Compose**（Material 3），优先声明式、可预览的 Compose 方案 |
| 异步 | **Kotlin Coroutines + Flow** |
| 依赖注入 | **Hilt 2.60.1**（当前 kapt，后续评估迁移 KSP） |
| 本地缓存 | **Room**（规划中：同步状态、最近文件、UI 元数据，不存明文） |
| 数据库解析 | 自研或封装 KeePass 解析（参考 KeePassDX 的 `database` 模块） |
| 加密 | AES / Twofish / ChaCha20 分组加密，Argon2 / SHA-256 KDF（参考 KeePassDX `crypto` 模块） |
| 网络 | **OkHttp + ktor**（规划中），WebDAV 走 HTTP/XML，S3 走 AWS SDK / MinIO SDK |
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
> 全生命周期 7 大阶段的完整交付物清单、任务拆解与发布验收门禁详见：[**DELIVERY_PLAN.md**](DELIVERY_PLAN.md)。

### 阶段 0：工程脚手架
- [x] 初始化 Android 工程（Gradle Kotlin DSL）
- [x] 接入 Compose、Hilt、Coroutines 等依赖（Room、OkHttp 待实际接入）
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

### 阶段 2：数据库核心（当前重点）⭐
- [ ] `crypto` 模块：引入 BouncyCastle，实现 AES-256 / ChaCha20 / Twofish 分组加密
- [ ] `crypto` 模块：实现 Argon2d/id 与 SHA-256 KDF 派生，严格遵守敏感数据显式清零规范
- [ ] `database` 模块：标准 `.kdbx`（v4/v3）二进制格式头解析与 Payload 解密/加密
- [ ] `database` 模块：XML 树解析与序列化，映射分组（Group）与条目（Entry）领域模型
- [ ] `database` 模块：内存模型 `DatabaseSession` 维护与增删改查
- [ ] 数据层替换：实现真实 `VaultRepository` 替换 `FakeVaultRepository`，连接 UI 与真实文件

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
- **KeePass 官方**（`参考项目/KeePass-2.61.1-Source`）：官方 C# 实现，kdbx 二进制 / XML 格式的权威参照。
- **Monica**（`参考项目/Monica-main`）：Kotlin 项目，可参考通用工程结构与 Compose 实践。

> 仅作为学习与架构参考，注意各自的开源许可证约束；本仓库代码独立编写，严禁复制其代码入库。各功能的参考定位详见 `.codebuddy/skills/reference-projects.md`。

---

## 7. 许可证

本项目计划以开源方式发布（具体许可证待定，参考 GPLv3 等）。
