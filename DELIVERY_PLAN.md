# KeePasskey 完整项目交付规划（Delivery Plan）

> **目标**：以工业级质量标准，将 KeePasskey 构建为一款支持 **.kdbx v4** 格式、**WebDAV / S3 兼容协议同步**、**Android 16+ (API 36+) Credential Manager / Passkey** 原生集成的现代化密码管理器，并最终实现面向社区（F-Droid / GitHub Releases / Google Play）的稳定交付。

---

## 阶段规划全景图（Phase Overview）

全生命周期共划分为 **7 个阶段**。阶段之间存在严格的前后依赖与质量验收门禁（Quality Gates）：

```
[阶段 1: UI 优先与交互原型]  ✅ (已完成，15个页面/全交互闭环/中英文对齐)
         │
         ▼
[阶段 2: 密码学核心与 KDBX 数据库引擎] ✅ (已完成：crypto + database + session + 真实Repository打通)
         │
         ▼
[阶段 3: 生物识别与防御性安全加固] ✅ (已完成：AndroidX Biometric + Keystore + FLAG_SECURE + 剪贴板擦除 + AutoLock)
         │
         ▼
[阶段 4: 通行密钥 (Passkey) 与 Credential Manager 自动填充] ✅ (已完成：WebAuthn + API 36+ CredentialProviderService + AutofillService)
         │
         ▼
[阶段 5: 多协议云同步 (WebDAV / S3) 与三方冲突合并] 🚀 (当前重点：SyncProvider + ETag + 冲突比对)
         │
         ▼
[阶段 6: KeePass 高级特性与全功能工具箱] (健康检查审计 + 附件预览 + 历史版本回滚)
         │
         ▼
[阶段 7: 质量工程、测试基线、混淆加固与全渠道交付] 🏁 (兼容性向量 + R8 + CI/CD + 发布)
```

---

## 阶段详细任务与交付标准

---

### 阶段 1：UI 优先与交互原型（已完成 ✅）

* **目标**：在不依赖复杂底层真实存储的前提下，用 Jetpack Compose + Material 3 搭建全部 UI 与导航框架，验证交互动效、单向数据流与界面自适应能力。
* **交付物**：
  * 全量 15 个屏幕与子屏幕（`UnlockScreen`, `VaultListScreen`, `EntryDetailScreen`, `EntryEditScreen`, `DatabasePickerScreen`, `GeneratorScreen`, `AuthenticatorScreen`, `ConflictResolutionScreen` 及 9 个设置子页）。
  * 内存响应式假数据层（`FakeVaultRepository` 与 `FakeSettingsRepository`）。
  * 中英双语资源文件（`res/values/strings.xml` 与 `res/values-en/strings.xml` 各 772 行完全对齐）。
  * 响应式双端适配（手机端 `AppBottomBar` ↔ 平板/折叠屏 `AppNavigationRail`）。
* **验收状态**：**已通过全面验收**。

---

### 阶段 2：密码学核心与 KDBX 数据库引擎（已完成 ✅）

* **目标**：构建独立、安全、可复用的 `crypto` 与 `database` 核心，实现对标准 `.kdbx`（重点 v4，向下兼容 v3）的无损读取、解析、修改与原子写入，并替换 `FakeVaultRepository`。
* **涉及模块**：`core`, `crypto`, `database`, `app` (数据层注入)
* **核心任务清单**：
  1. **`core` 基础领域模型与安全内存设计**：
     - [x] 定义不可变条目模型 `KdbxEntry`、分组树模型 `KdbxGroup`、自定义字段 `KdbxCustomField`、历史快照 `KdbxHistory`、附件元数据 `KdbxAttachment`。
     - [x] 封装敏感内存擦除抽象：`ProtectedString` 与 `ClearableByteArray`，杜绝敏感密码常驻 GC 堆。
  2. **`crypto` 密码学驱动器**：
     - [x] 引入 `BouncyCastle` 密码学库（或 Android 原生实现评估）。
     - [x] 对称分组加解密：AES-256 (CBC/GCM)、ChaCha20-Poly1305、Twofish。
     - [x] 密钥派生函数（KDF）：Argon2d / Argon2id（内存、迭代次数、并行度可调）、AES-KDF（SHA-256 rounds）。
     - [x] Inner Random Stream（内部内存流加密）：Salsa20 / ChaCha20 Protected Stream Cipher，用于保护内存中的自定义密码字段。
     - [x] HMAC-SHA256 块签名与校验引擎。
  3. **`database` KDBX 格式解析与序列化**：
     - [x] **KDBX v4 解析器**：文件魔数签名校验（`0x9AA2D903`, `0xB54BFB67`）、外层 Header 动态字典解析（Cipher ID、KDF 参数、主种子 Master Seed、转换种子 Transform Seed、加密 IV 等）。
     - [x] **Payload 解密与解压缩**：GZip 解压缩、Inner Stream 解密、HMAC 块流校验（Block Stream Validation）。
     - [x] **XML 数据树反序列化**：解析 `<KeePassFile>` 节点树，映射 Group/Entry/Times/Binary 等元数据，支持 KeePass 官方和 KeePassXC 导出的标注文档。
     - [x] **KDBX 序列化与写回**：将内存树完整写回标准二进制 KDBX 格式，计算 Header HMAC 与数据块校验和。
  4. **会话层 `DatabaseSession` 与持久化调度**：
     - [x] 内存中维护活动数据库状态机（`CLOSED`, `LOCKED`, `OPENED`, `DIRTY`）。
     - [x] **原子写盘机制（Atomic File Write）**：严格遵循 `tmp -> sync -> atomic rename + .bak 滚动备份` 策略，杜绝因掉电、崩溃导致主数据库损坏。
  5. **`app` 数据层接入**：
     - [x] 实现 `RealVaultRepository`，对接 `DatabaseSession`。
     - [x] 在 Hilt `RepositoryModule` 中无缝替换，完成全套 UI 对物理 `.kdbx` 文件的读取与增删改查。
* **交付物**：
  * `crypto` 独立模块（单元测试覆盖 AES/ChaCha20/Argon2 标准向量）。
  * `database` 独立模块（标准测试库解密、双向序列化与原子写盘测试全绿）。
  * `RealVaultRepository` 驱动的真实打开与保存功能。
* **验收门禁（DoD）**：
  * 使用 KeePass 2.x / KeePassXC 创建的含有 AES/Argon2/ChaCha20 的 `.kdbx` 文件可在本 App 中成功解锁并展示全部条目。
  * 在 App 中新增/修改条目并保存后，该文件重新在 PC 端 KeePassXC 中打开无报错、校验通过。
  * 关键内存敏感变量（Master Password、Master Key）在解密完成后立即执行 `.fill(0)`。
* **验收状态**：**已通过全面验收 ✅**。

---

### 阶段 3：系统级生物识别与防御性安全加固（已完成 ✅）

* **目标**：打通 Android 系统指纹/面容快速解锁，并在运行时为 App 构筑严密的防泄漏安全屏障。
* **涉及模块**：`app`（`biometric` 与安全组件）
* **核心任务清单**：
  1. **AndroidX Biometric 与硬件 Keystore 集成**：
     - [x] 基于 Android Keystore 生成硬件隔离的 AES-256-GCM 封装主密钥（MasterKey）。
     - [x] 封装 `BiometricManager`：支持 `BIOMETRIC_STRONG`（Class 3 强生物识别）。
     - [x] 生物识别加密凭据（Biometric CryptoObject）：使用生物识别验证结果解封主数据库密钥，实现免输主密码解锁。
     - [x] 设备指纹变更检测（`setInvalidatedByBiometricEnrollment(true)`，指纹发生增删时强制作废生物缓存并回退主密码）。
  2. **防偷窥与系统安全策略（FLAG_SECURE）**：
     - [x] 统一在 `MainActivity` 及关键 Compose 路由中根据安全设置动态挂载 `WindowManager.LayoutParams.FLAG_SECURE`。
     - [x] 阻止多任务卡片预览、系统截屏与投屏捕获敏感密码数据。
  3. **剪贴板安全生命周期**：
     - [x] 适配 Android 13+ 剪贴板标记：写入敏感字段时附加 `ClipDescription.EXTRA_IS_SENSITIVE = true`，遮蔽系统浮动预览。
     - [x] 剪贴板超时自动清空调度器：默认 30 秒（可在安全设置中自定义），超时后若剪贴板内容未被覆盖，自动物理置空。
  4. **自动锁定（Auto-Lock）熔断机制**：
     - [x] 监听应用退至后台事件（`ProcessLifecycleOwner`）。
     - [x] 监听设备锁屏广播（`Intent.ACTION_SCREEN_OFF`）。
     - [x] 超时熔断：后台超过指定阈值或屏幕熄灭时，立刻触发内存密钥销毁并重定向至 `UnlockScreen`。
* **交付物**：
  * 生产可用的生物识别解锁与错误退化（回退主密码）。
  * 完整的后台安全守卫系统与剪贴板自动擦除器。
* **验收门禁（DoD）**：
  * 指纹解锁延迟 < 300ms；
  * 退至后台超过设定时间（如 1 分钟）切回时，应用已被彻底锁定，内存中无敏感明文缓存；
  * 多任务切换界面显示白屏或占位黑屏。
* **验收状态**：**已通过全面验收 ✅**。

---

### 阶段 4：通行密钥（Passkey / WebAuthn）与 Credential Manager 自动填充（已完成 ✅）

* **目标**：充分利用 Android 16+（API 36+）原生现代化特性，将 KeePasskey 注册为系统级凭据提供者，支持通行密钥（FIDO2 / WebAuthn）的端到端创建、存储、认证与自动填充。
* **涉及模块**：`app`（`passkey` + `autofill` 内部包）、`database`（扩展凭据模型）
* **核心任务清单**：
  1. **KDBX 通行密钥扩展存储标准**：
     - [x] 对齐 KeePassXC / KeeWeb 的 Passkey 存储规范（PasskeyData 映射为条目自定义字段：私钥 ProtectedString 隔离、Credential ID、User Handle、RP ID、Counter 等）。
  2. **Android 16+ Credential Manager 接入**：
     - [x] 实现 `CredentialProviderService`：声明系统凭据提供商权限 `android.permission.BIND_CREDENTIAL_PROVIDER_SERVICE` 与服务意图。
     - [x] 接入 `onBeginCreateCredential` 注册预处理。
     - [x] 接入 `onBeginGetCredential` 凭据选项预过滤与匹配。
  3. **WebAuthn / Passkey 注册流程（Create Passkey）**：
     - [x] 实现 `PasskeyCryptoEngine`，本地生成高安全性 ES256 (ECDSA P-256 / SHA-256) 椭圆曲线密钥对。
     - [x] 组装标准 `AuthenticatorData` 二进制结构（包含 RP ID 哈希、Flags 标志位与 SignCount 计数器）。
     - [x] 将 Passkey 凭据与对应 RP ID 绑定并提供标准 CustomFields 双向序列化支持。
  4. **WebAuthn / Passkey 认证与断言流程（Get Passkey / Assertion）**：
     - [x] 匹配用户当前访问的域名（Relying Party ID）与 Android 包名。
     - [x] 使用本地私钥对 `authenticatorData || clientDataHash` 执行 RFC 6979 确定性 ECDSA-SHA256 签名，生成合规 ASN.1 DER 签名字节。
  5. **传统密码表单自动填充（Autofill Service）**：
     - [x] 实现传统 `KeePasskeyAutofillService` 兼容层，声明 `android.permission.BIND_AUTOFILL_SERVICE`。
* **交付物**：
  * 系统级 Passkey 密码学引擎与 WebAuthn 完整签名/数据结构。
  * 注册在 Android 系统的 `KeePasskeyCredentialProviderService` 与 `KeePasskeyAutofillService`。
* **验收门禁（DoD）**：
  * 密码学 ES256 密钥对生成、DER 签名与 AuthenticatorData 构建 100% 单元测试通过；
  * Android Manifest 与 XML 凭据提供者服务描述配置完毕，`assembleDebug` 编译通过。
* **验收状态**：**已通过全面验收 ✅**。

---

### 阶段 5：多协议云端同步引擎（WebDAV / S3）与三方冲突合并

* **目标**：实现安全的远程数据库双向同步（WebDAV 与 S3 兼容协议），在弱网与离线环境下具备可靠的本地缓存，并在版本冲突时提供友好的三方合并决策。
* **涉及模块**：`sync` 模块、`app`（同步状态与冲突解决调度）
* **核心任务清单**：
  1. **统一存储抽象层 `SyncProvider`**：
     - [ ] 定义通用云存储契约：`connect()`, `getRemoteMetadata()`, `downloadFile()`, `uploadFile()`, `deleteFile()`。
     - [ ] 统一异常与重试策略（区分网络故障、鉴权失效 401、路径不存在 404、并发修改 412 Precondition Failed）。
  2. **WebDAV 客户端实现**：
     - [ ] 基于 OkHttp 实现 WebDAV 核心动词：`PROPFIND`（拉取 ETag 与 Last-Modified）、`GET`、`PUT`、`MKCOL`。
     - [ ] 实现基于 HTTP ETag 的乐观锁检查（`If-Match: <remote-etag>`），防止覆盖他人提交。
     - [ ] 支持自签名 SSL/TLS 证书信任与局域网 HTTP 明确豁免。
  3. **S3 兼容协议客户端实现**：
     - [ ] 针对 AWS S3、Cloudflare R2、MinIO 等实现轻量级 AWS Signature Version 4（SigV4）鉴权算法。
     - [ ] 支持 Bucket 探测、对象读取、版本控制（Versioning）与流式上传。
  4. **离线缓存与后台同步调度**：
     - [ ] 本地离线缓存策略：当无网络连接时，允许在本地缓存读写；重新联网时自动触发同步并提交变更。
     - [ ] 集成 Android `WorkManager`：支持设置中定义的周期性后台同步、仅 Wi-Fi 同步。
  5. **冲突检测与可视化三方合并（Conflict Resolution）**：
     - [ ] 冲突判定算法：本地数据库有未同步变更，且远端文件的 ETag/修改时间晚于上次同步时间戳。
     - [ ] 调用阶段 1 已经构建的 `ConflictResolutionScreen`：
       - 展示两端文件元数据；
       - 解析出两端差异条目，支持用户逐字段（账号、密码、URL、备注）单选合并决策；
       - 执行数据库合并算法并原子推送到云端。
* **交付物**：
  * 独立的 `sync` 模块（包含 WebDAV 与 S3 实现）。
  * 完整的同步生命周期（状态栏显示已同步/同步中/冲突/离线）。
  * 冲突合并引擎。
* **验收门禁（DoD）**：
  * 在 Nextcloud（WebDAV）和 MinIO（S3）真实服务器上完成文件上传与拉取。
  * 模拟两台设备同时修改同一条目的不同字段，App 能准确捕获冲突，唤起冲突界面完成合并并成功写回云端，双方数据均不丢失。

---

### 阶段 6：KeePass 高级特性与全功能工具箱

* **目标**：补齐 KeePass 标准的高级特性，让 KeePasskey 从“基础可用”跃升为“全功能强悍”的旗舰密码工具。
* **涉及模块**：`app`, `database`
* **核心任务清单**：
  1. **两步验证（TOTP / HOTP）引擎实装**：
     - [ ] 接入纯 Kotlin 的 RFC 6238（TOTP）与 RFC 4226（HOTP）计算引擎（SHA-1、SHA-256、SHA-512，6位/8位代码，步长 30s）。
     - [ ] 条目内扫描 KeyUri 二维码导入（支持 CameraX 扫码识别）。
     - [ ] 替换现有的 Mock TOTP 计算，使主列表、详情页和 `AuthenticatorScreen` 展示真实倒计时的实时双重认证码。
  2. **条目附件（Attachments）物理管理**：
     - [ ] 接入 Android SAF 文件选择器（`ActivityResultContracts.GetContent`），支持从手机相册/文件选择附件嵌入 KDBX。
     - [ ] 附件提取与外部应用查看（安全 FileProvider 导出临时查看并在退出时清理）。
  3. **版本历史（History Revisions）与一键回滚**：
     - [ ] 条目修改时自动将旧快照追加至 `<History>` XML 节点。
     - [ ] 在 `EntryDetailScreen` 的 Visual Diff 对话框中，打通真实的“一键恢复该历史版本”持久化逻辑。
  4. **密码健康度与安全审计（Health Check）**：
     - [ ] 离线弱密码字典扫描与重复使用分析。
     - [ ] 密码过期时间（Expiry Date）告警提示。
  5. **密码库导入与导出**：
     - [ ] 支持标准 CSV 导出与主流密码管理器（Bitwarden、1Password、KeePass XML）格式的互导映射。
* **交付物**：
  * 生产可用的真实 TOTP 引擎与二维码识别。
  * 真实的附件存储与历史回溯回滚。
* **验收门禁（DoD）**：
  * TOTP 计算结果与 Google Authenticator、1Password 完全一致。
  * 导入 1000+ 条目的大型数据库时，搜索与列表渲染丝滑无掉帧。

---

### 阶段 7：质量工程、测试基线、混淆加固与全渠道交付（正式交付 🏁）

* **目标**：完成工业级测试、漏洞与安全合规审查、编译混淆优化，建立持续集成与发布流水线，正式发布版本。
* **涉及模块**：工程全局、CI/CD、发布脚本
* **核心任务清单**：
  1. **标准兼容性与单元测试基线**：
     - [ ] 建立 `src/test` 测试套件：引入 KeePass 官方测试向量（Test Vector Databases）。
     - [ ] 覆盖核心测试用例：密码学加解密、KDF 计算、KDBX 二进制解析/写回、TOTP 校验、Sync 重试机制。
     - [ ] UI 关键流 Instrumented 测试（Compose Test）。
  2. **代码混淆、体积优化与安全防逆向（R8 / Proguard）**：
     - [ ] 编写精确的 `proguard-rules.pro`：保护 BouncyCastle 密码学实现、Protobuf/XML 序列化模型与 Hilt 注入。
     - [ ] 开启 R8 全量压缩与资源瘦身（Shrink Resources）。
     - [ ] 剥离所有 Debug 级别敏感日志输出（使用 Timber / 自定义安全 Logger 守卫）。
  3. **安全合规审计与内存渗透走查**：
     - [ ] 检查堆转储（Heap Dump）：确认解锁退出后内存中无残存的密码明文字符串。
     - [ ] 静态代码分析：零硬编码私钥、零未经保护的 IPC 暴露。
  4. **全渠道打包与自动化流水线（CI/CD）**：
     - [ ] 配置 GitHub Actions 自动化构建与测试工作流。
     - [ ] 配置 Release 签名密钥库（Keystore）与分发密钥管理。
     - [ ] 准备 **F-Droid** 开源发布元数据（`metadata` / Fastlane 格式），确保完全符合 F-Droid 纯自由软件规范。
     - [ ] 准备 **GitHub Releases** 自动发布工作流（发布 APK、校验 SHA-256 Checksum）。
     - [ ] 准备 **Google Play Store** 提交资产（AAB 打包、图标、隐私权政策文档 Privacy Policy、Play Integrity 准备）。
  5. **交付归档文档**：
     - [ ] 完善用户使用手册（User Manual）与常见问题解答（FAQ）。
     - [ ] 输出《架构设计与安全白皮书》（Architecture & Security Whitepaper）。
* **交付物**：
  * 生产签名 Release APK 与 AAB。
  * GitHub CI/CD 绿色构建徽标。
  * 完整的安全审计报告与合规发布资产包。
* **验收门禁（DoD）**：
  * Release 安装包体积控制在合理范围（< 25MB）。
  * 在纯物理真机（Android 16+）上执行全流程冒烟测试（安装 -> 新建库 -> 指纹解锁 -> 添加Passkey -> 浏览器登录 -> WebDAV同步 -> 杀死重登）零崩溃、零安全警告。
  * 获得正式版本号（`v1.0.0`）。

---

## 阶段执行与进度追踪看板

| 阶段编号 | 阶段名称 | 关键责任模块 | 预计规模 | 状态 |
| :---: | :--- | :--- | :---: | :---: |
| **阶段 1** | UI 优先与交互原型 | `app:ui` | 47+ 文件 | **已完成 ✅** |
| **阶段 2** | 密码学核心与 KDBX 数据库引擎 | `crypto`, `database`, `core` | 核心引擎 | **已完成 ✅** |
| **阶段 3** | 生物识别与防御性安全加固 | `app:biometric`, `app:security` | 硬件集成 | **已完成 ✅** |
| **阶段 4** | 通行密钥 (Passkey) 与 Credential Manager | `app:passkey`, `app:autofill` | 系统服务 | **已完成 ✅** |
| **阶段 5** | 多协议云同步 (WebDAV / S3) 与冲突合并 | `sync`, `app` | 云端同步 | **当前重点 🚀** |
| **阶段 4** | 通行密钥 (Passkey) 与 Credential Manager | `app:passkey`, `app:autofill` | 系统服务 | 待开始 ⏳ |
| **阶段 5** | 多协议云同步 (WebDAV/S3) 与冲突合并 | `sync` | 网络引擎 | 待开始 ⏳ |
| **阶段 6** | KeePass 高级特性与全功能工具箱 | `app`, `database` | 功能增强 | 待开始 ⏳ |
| **阶段 7** | 质量工程、混淆加固与全渠道交付 | 全局, `CI/CD` | 交付闭环 | 待开始 ⏳ |

---

## 开发执行纪律（给 Coding Agent 的硬性准则）

1. **严格按阶段递进**：在当前阶段的任务未达成验收标准（DoD）前，不得越级提前引入后续阶段的非必要复杂逻辑。
2. **零破坏已有成果**：阶段 2 在替换 `FakeVaultRepository` 时，应保持现有的 `VaultRepository` 接口契约稳定，确保阶段 1 中已经打磨完毕的 UI 层（15 个界面）不受冲击。
3. **安全第一（Security First）**：自阶段 2 起，凡涉及密码、主密钥、加解密流的地方，必须严格遵守 `CharArray`/`ByteArray` 显式 `.fill(0)` 擦除规范，严禁明文入日志或使用全局未保护的临时文件。
4. **阶段交付与 Git 归档准则**：每顺利完成并验收一个阶段后，必须立即：
   - 1）同步更新 `AGENTS.md` 与 `.codebuddy/memory/project-status.md`（记录该阶段交付物与下一阶段目标）；
   - 2）将该阶段的所有工作成果完整暂存并提交至本地 Git 仓库（`git add .` 与语义化 `git commit -m "docs/feat: 完成阶段 X「...」全部交付"`），确保每个里程碑都拥有独立、干净、可追溯的本地版本记录。
