# 参考项目地图与架构分析导航（Reference Projects Skill）

> **由 `AGENTS.md` 硬约束第 3 条强制索引。**
> **【铁律】严禁盲目扫描或直接翻看 `参考项目/` 源码树！**
> 5 个成熟参考项目的全量深度技术架构分析已收录在 **`docs/references/`**。
> **在实现相关功能前，必须无条件先阅读对应的架构分析文档！**

---

## 一、 参考项目优先级层级（Strict Priority Hierarchy）

实现各模块业务逻辑时，必须严格按照以下明确的优先级顺序查阅文档与进行设计推导：

1. 🥇 **第 1 优先级（核心主参考）：KeePassDX**
   - **理由**：同为原生 Android Kotlin 实现，模块划分（`app` / `database` / `crypto`）与 KeePasskey 高度一致；
   - **参考范围**：`database` 与 `crypto` 的领域模型、`DatabaseSession` 会话与脏状态管理、Android Credential Provider 与 WebAuthn/Passkey；
   - **警惕点**：避免其 View 体系历史包袱与巨型类反模式（如超大解析类），借鉴其格式逻辑而非组织结构。

2. 🥈 **第 2 优先级（次核心参考）：keepass2android**
   - **理由**：Android 端云同步功能最完善、最成熟的工业级实现；
   - **参考范围**：WebDAV 与 S3 云同步架构、`IFileStorage` 插件化文件抽象层、本地离线缓存策略、ETag 与三方哈希冲突检测算法、Quick Unlock 快速解锁。

3. ⚖️ **标准实现参考（格式与协议裁决者）：KeePass-2.61.1 (官方 C#)**
   - **理由**：`.kdbx` 格式的“标准唯一定义者”，权威基准；
   - **参考范围**：不参考其 UI，仅在遇到 KDBX v4 二进制文件头动态字典、Inner Random Stream（ChaCha20/Salsa20）、Payload 解密校验、XML 标签语义有分歧或歧义时，**以官方实现作为最终裁决依据**。

4. ⚖️ **算法级参考（合并引擎与通行密钥 schema）：KeePassXC (C++/Qt)**
   - **理由**：KeePass 生态中工程质量最高的社区实现，其合并引擎与 passkey 属性规范可跨语言直接移植；
   - **参考范围**：`Merger`（`src/core/Merger.cpp`）条目级合并、秒级时间戳截断与墓碑复活规则 → `KdbxMerger`；`KdbxReader/KdbxWriter` 读写管线 → `database` 模块交叉验证；浏览器集成中的 `KPEX_PASSKEY_*` Entry 属性 schema 与 WebAuthn 栈隔离分层 → `PasskeyData` 与 Credential Provider 隔离设计。

5. 🥉 **辅助参考（不做重点）：Monica**
   - **理由**：轻量级参考；
   - **参考范围**：仅作为现代 Material 3 + Compose 交互与本地优先（Local-first）数据同步思路的补充视野，**不作为核心实现重点**。

---

## 二、 架构分析文档清单（纳入版本管理）

所有架构分析文档均存放在 `docs/references/` 目录下：

| 文档路径 | 分析对象 | 核心价值与重点参考内容 |
| :--- | :--- | :--- |
| [`docs/references/README.md`](references/README.md) | 总览索引 | 5 个参考项目特点对比、选型差异与适用边界 |
| [`docs/references/KeePassDX-架构分析.md`](references/KeePassDX-架构分析.md) | `KeePassDX-master` (Kotlin) | • `crypto` 模块设计与 JNI 加速边界<br>• `database` 模块 KDBX 解析与 `DatabaseSession` 会话<br>• 通行密钥（WebAuthn / Credential Provider）实现<br>• 架构优缺点与反面教材剖析（58KB 详尽分析） |
| [`docs/references/keepass2android-架构分析.md`](references/keepass2android-架构分析.md) | `keepass2android-main` (C#+Java) | • `IFileStorage` 插件化云存储抽象层<br>• WebDAV / S3 云同步与本地缓存机制<br>• 乐观并发控制、ETag 与三方哈希冲突检测<br>• Quick Unlock（快速解锁）机制实现 |
| [`docs/references/KeePass-2.61.1-架构分析.md`](references/KeePass-2.61.1-架构分析.md) | `KeePass-2.61.1-Source` (C#) | • KDBX v4 二进制文件格式与 Header 动态字典权威定义<br>• GZip Payload、Inner Random Stream（ChaCha20/Salsa20）<br>• XML 节点结构 `<KeePassFile>` 的官方权威规范 |
| [`docs/references/Monica-架构分析.md`](references/Monica-架构分析.md) | `Monica-main` (Kotlin+Rust) | • 现代 Material 3 + Compose 密码管理器工程结构<br>• `mdbx` 本地优先（Local-first）同步与版本历史设计<br>• 现代 Android 密钥安全与敏感数据生命周期实践 |
| [`docs/references/KeePassXC-架构分析.md`](references/KeePassXC-架构分析.md) | `keepassxc-develop` (C++/Qt) | • `Merger` 合并引擎（时间戳截断 / 墓碑复活）→ `KdbxMerger` 直接算法参考<br>• `KdbxReader/KdbxWriter` KDBX 3/4 读写管线与附件去重<br>• 浏览器集成通行密钥（`KPEX_PASSKEY_*` 属性 schema、CBOR attestation/assertion）<br>• 内存清零 / 原子保存 / InactivityTimer 安全实践（728 行详尽分析） |

---

## 三、 任务阶段 ↔ 架构分析文档对照表（AI 执行路线）

当进入某一具体开发阶段时，AI 代理必须按照下表**精准阅读对应文档的指定章节**：

### 阶段 2：密码学核心与 KDBX 数据库引擎（当前重点 🚀）
1. **加解密与 KDF（`crypto` 模块）**：
   - 优先阅读：[`KeePassDX-架构分析.md`](references/KeePassDX-架构分析.md) 的 **第 4 节「crypto 模块：加密引擎与 JNI 边界」**；
   - 权威对照：[`KeePass-2.61.1-架构分析.md`](references/KeePass-2.61.1-架构分析.md) 的 **第 4 节「加密管线与密码学实现」**。
2. **KDBX 文件解析与写回（`database` 模块）**：
   - 优先阅读：[`KeePassDX-架构分析.md`](references/KeePassDX-架构分析.md) 的 **第 5 节「database 模块：数据模型、kdb 读写与会话生命周期」**；
   - 权威对照：[`KeePass-2.61.1-架构分析.md`](references/KeePass-2.61.1-架构分析.md) 的 **第 5 节「KeePassLib 核心：数据模型与文件 I/O」**。
3. **会话管理与原子写入（`DatabaseSession`）**：
   - 阅读：[`keepass2android-架构分析.md`](references/keepass2android-架构分析.md) 的 **第 6 节「文件事务与原子写入」**；
   - 交叉验证：[`KeePassXC-架构分析.md`](references/KeePassXC-架构分析.md) 的 **第 4 节「format 模块：KDBX 序列化管线」** 与 **第 10.2 节「原子保存」**。

### 阶段 3：生物识别与防御性安全加固
- 阅读：[`KeePassDX-架构分析.md`](references/KeePassDX-架构分析.md) 的 **第 6.4 节「生物识别解锁流程」** 与 **第 8 节「安全防护策略」**；
- 阅读：[`Monica-架构分析.md`](references/Monica-架构分析.md) 的 **第 6 节「安全与凭据保护」**。

### 阶段 4：通行密钥（Passkey）与 Credential Manager 自动填充
- 重点阅读：[`KeePassDX-架构分析.md`](references/KeePassDX-架构分析.md) 的 **第 6.5 节「凭据提供服务（CredentialProviderService 与 WebAuthn/Passkey）」**；
- 重点阅读：[`keepass2android-架构分析.md`](references/keepass2android-架构分析.md) 的 **第 7 节「Autofill 框架与输入法填充」**；
- 数据模型对照：[`KeePassXC-架构分析.md`](references/KeePassXC-架构分析.md) 的 **第 8 节「browser 与 proxy 模块」**（`KPEX_PASSKEY_*` 属性 schema、CBOR attestation/assertion 构造）。

### 阶段 5：多协议云端同步引擎（WebDAV / S3）与三方冲突合并
- 重点阅读：[`keepass2android-架构分析.md`](references/keepass2android-架构分析.md) 的 **第 4 节「文件存储抽象体系」** 与 **第 5 节「缓存与冲突检测机制」**；
- 重点阅读：[`Monica-架构分析.md`](references/Monica-架构分析.md) 的 **第 4 节「存储与同步设计」**；
- 合并算法直参：[`KeePassXC-架构分析.md`](references/KeePassXC-架构分析.md) 的 **第 3.5 节「Merger」** 与 **第 12.2 节「Merger → KdbxMerger 直接参考」**（秒级时间戳截断、墓碑复活、Group MergeMode）。

---

## 四、 按图索骥细则（何时才允许看源码）

1. **原则**：90% 以上的架构决策、协议细节、数据结构与流程图已在上述分析文档中详尽给出，**绝大多数情况下仅读文档即可直接编写 Kotlin 代码**。
2. **例外**：当且仅当遇到极端边界情况（例如特定加密模式的 IV 补齐规则、某个 XML 标签大小写、二进制 Header 的特定掩码字段），且文档中的描述不足以消除歧义时：
   - 允许根据架构分析文档中提供的**精准类名或路径**，单点读取该特定文件；
   - **严禁**将源码中的任何片段复制到本项目（必须独立实现）。
