# 参考项目地图与架构分析导航（Reference Projects Skill）

> **由 `AGENTS.md` 硬约束第 3 条强制索引。**
> **【铁律】严禁盲目扫描或直接翻看 `参考项目/` 源码树！**
> 4 个成熟参考项目的全量深度技术架构分析已收录在 **`.codebuddy/skills/references/`**。
> **在实现相关功能前，必须无条件先阅读对应的架构分析文档！**

---

## 一、 架构分析文档清单（纳入版本管理）

所有架构分析文档均存放在 `.codebuddy/skills/references/` 目录下：

| 文档路径 | 分析对象 | 核心价值与重点参考内容 |
| :--- | :--- | :--- |
| [`.codebuddy/skills/references/README.md`](references/README.md) | 总览索引 | 4 个参考项目特点对比、选型差异与适用边界 |
| [`.codebuddy/skills/references/KeePassDX-架构分析.md`](references/KeePassDX-架构分析.md) | `KeePassDX-master` (Kotlin) | • `crypto` 模块设计与 JNI 加速边界<br>• `database` 模块 KDBX 解析与 `DatabaseSession` 会话<br>• 通行密钥（WebAuthn / Credential Provider）实现<br>• 架构优缺点与反面教材剖析（58KB 详尽分析） |
| [`.codebuddy/skills/references/keepass2android-架构分析.md`](references/keepass2android-架构分析.md) | `keepass2android-main` (C#+Java) | • `IFileStorage` 插件化云存储抽象层<br>• WebDAV / S3 云同步与本地缓存机制<br>• 乐观并发控制、ETag 与三方哈希冲突检测<br>• Quick Unlock（快速解锁）机制实现 |
| [`.codebuddy/skills/references/KeePass-2.61.1-架构分析.md`](references/KeePass-2.61.1-架构分析.md) | `KeePass-2.61.1-Source` (C#) | • KDBX v4 二进制文件格式与 Header 动态字典权威定义<br>• GZip Payload、Inner Random Stream（ChaCha20/Salsa20）<br>• XML 节点结构 `<KeePassFile>` 的官方权威规范 |
| [`.codebuddy/skills/references/Monica-架构分析.md`](references/Monica-架构分析.md) | `Monica-main` (Kotlin+Rust) | • 现代 Material 3 + Compose 密码管理器工程结构<br>• `mdbx` 本地优先（Local-first）同步与版本历史设计<br>• 现代 Android 密钥安全与敏感数据生命周期实践 |

---

## 二、 任务阶段 ↔ 架构分析文档对照表（AI 执行路线）

当进入某一具体开发阶段时，AI 代理必须按照下表**精准阅读对应文档的指定章节**：

### 阶段 2：密码学核心与 KDBX 数据库引擎（当前重点 🚀）
1. **加解密与 KDF（`crypto` 模块）**：
   - 优先阅读：[`KeePassDX-架构分析.md`](references/KeePassDX-架构分析.md) 的 **第 4 节「crypto 模块：加密引擎与 JNI 边界」**；
   - 权威对照：[`KeePass-2.61.1-架构分析.md`](references/KeePass-2.61.1-架构分析.md) 的 **第 4 节「加密管线与密码学实现」**。
2. **KDBX 文件解析与写回（`database` 模块）**：
   - 优先阅读：[`KeePassDX-架构分析.md`](references/KeePassDX-架构分析.md) 的 **第 5 节「database 模块：数据模型、kdb 读写与会话生命周期」**；
   - 权威对照：[`KeePass-2.61.1-架构分析.md`](references/KeePass-2.61.1-架构分析.md) 的 **第 5 节「KeePassLib 核心：数据模型与文件 I/O」**。
3. **会话管理与原子写入（`DatabaseSession`）**：
   - 阅读：[`keepass2android-架构分析.md`](references/keepass2android-架构分析.md) 的 **第 6 节「文件事务与原子写入」**。

### 阶段 3：生物识别与防御性安全加固
- 阅读：[`KeePassDX-架构分析.md`](references/KeePassDX-架构分析.md) 的 **第 6.4 节「生物识别解锁流程」** 与 **第 8 节「安全防护策略」**；
- 阅读：[`Monica-架构分析.md`](references/Monica-架构分析.md) 的 **第 6 节「安全与凭据保护」**。

### 阶段 4：通行密钥（Passkey）与 Credential Manager 自动填充
- 重点阅读：[`KeePassDX-架构分析.md`](references/KeePassDX-架构分析.md) 的 **第 6.5 节「凭据提供服务（CredentialProviderService 与 WebAuthn/Passkey）」**；
- 重点阅读：[`keepass2android-架构分析.md`](references/keepass2android-架构分析.md) 的 **第 7 节「Autofill 框架与输入法填充」**。

### 阶段 5：多协议云端同步引擎（WebDAV / S3）与三方冲突合并
- 重点阅读：[`keepass2android-架构分析.md`](references/keepass2android-架构分析.md) 的 **第 4 节「文件存储抽象体系」** 与 **第 5 节「缓存与冲突检测机制」**；
- 重点阅读：[`Monica-架构分析.md`](references/Monica-架构分析.md) 的 **第 4 节「存储与同步设计」**。

---

## 三、 按图索骥细则（何时才允许看源码）

1. **原则**：90% 以上的架构决策、协议细节、数据结构与流程图已在上述分析文档中详尽给出，**绝大多数情况下仅读文档即可直接编写 Kotlin 代码**。
2. **例外**：当且仅当遇到极端边界情况（例如特定加密模式的 IV 补齐规则、某个 XML 标签大小写、二进制 Header 的特定掩码字段），且文档中的描述不足以消除歧义时：
   - 允许根据架构分析文档中提供的**精准类名或路径**，单点读取该特定文件；
   - **严禁**将源码中的任何片段复制到本项目（必须独立实现）。
