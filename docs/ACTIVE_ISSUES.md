# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。
> **只放现存问题**：本文件**只保留尚未闭环的条目**——已闭环条目的正文、批次流水与索引段一律不留；历史实现与验收证据以 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 `docs/resolved/batches/` 为单一真相源。
> **实施前必读**：各条目的 AC 已按 [`SECURITY_RECHECK_2026-09.md`](security/SECURITY_RECHECK_2026-09.md) §10 / §11 的**第四轮独立复核定版结论**逐条对齐，更正口径已内联到对应条目——照条目原文 AC 实施者可能**降低安全性**或**引入数据损坏**。
> **闭环纪律**：任务完成后，将该条目从本文件**整条移入** [RESOLVED_LOG.md](RESOLVED_LOG.md)（加一行索引 + 新增批次正文），并执行 `git commit & push`。

---

## 条目维护规则（ISSUE-P3-28 确立）

1. **新增条目必须附「核实时间点」与「核实方式」**：任何声称「某文件需要删除 / 某处没有消费方 / 某硬编码为 0」一类**关于代码库现状的前提**，都必须写明**何时、以何种手段**核实过，例如「2026-09-10 经 `Test-Path` 核实」「2026-09-10 经全仓 `grep maskPasswordsDefault` 核实（仅设置页回显）」。
   > 立规缘由：条目与代码库演进之间存在时间差，曾出现正文前提在开工时**已不成立**（文件早已删除、引用早已清理），致执行者去做已经完成的工作。
2. **开工前复核前提**：认领条目时先复核其正文前提（路径是否存在、行号是否漂移、消费方是否已出现）。前提已不成立的，**就地修正或显式标注**后再动手；完全无对象可改的条目应直接归档并注明原因。
3. **行号一律视为「核实时刻的快照」**：正文内引用的 `文件:行号` 仅作定位提示，实现前须以真实内容为准。

---

## 优先级定义

| 等级 | 严重度与类型 | 处理原则 |
|:---:|---|:---:|
| **P0** | **阻断级 / 致命安全漏洞**（数据损坏、篡改未拦截、明文泄露、严重崩溃） | 立即停止其他工作优先修复 |
| **P1** | **高危缺陷 / 核心功能阻断**（权限失效、关键契约异常、核心流程失败） | 最高优先级排期修复 |
| **P2** | **中危缺陷 / 协议互操作 / 性能瓶颈 / 测试有效性缺口** | 正常排期，按批次连续解决 |
| **P3** | **低危项 / 进阶特性接线 / 代码整洁度 / 评估与体验优化** | 渐进优化与特性补齐 |

---

## P1 高危与核心功能问题（0 项）

> **暂无开放项**（历史 P1 条目的实现与验收证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md)）。

---

## P2 中危缺陷与协议/测试缺口（0 项）

> **暂无开放项**。2026-09-17 登记的两条 CPU 占用瓶颈（`ISSUE-P2-89` 列表页秒级整页重建、
> `ISSUE-P2-90` TOTP 重算 O(T×N)）已于同日在 §114 批次闭环，实现与验证证据见
> [`resolved/batches/114-列表页秒级重建与TOTP重算收敛批次.md`](resolved/batches/114-列表页秒级重建与TOTP重算收敛批次.md)。
> 本批的**残留风险**（整库投影仍在收集上下文执行）转登为 `ISSUE-P3-154`，见下。

---

## P3 低危问题、特性接线与体验优化（5 项）

> 本批为 2026-09-17「降低 CPU / 内存占用」排查的其余结论。
> 条目 150 / 151 为**纯 Kotlin 层**整改，条目 152 为常驻轮询收敛，条目 153 为 **Rust 下沉候选的评估结论**，
> 条目 154 为 §114 批次的**残留**（`ISSUE-P3-149` ③ 未做部分）。
> 原 `ISSUE-P3-149`（投影热路径）的 ①②④ 已于 §114 闭环，**③ 转登为 `ISSUE-P3-154`**。

### ISSUE-P3-150 KDBX 保存路径整树重建与附件字节重复拷贝

- **背景**：`save()` 的去重器 `deduplicate` **无条件重建整棵分组树**（逐组 `group.copy` / 逐条 `entry.copy`），
  且对每个附件引用做 `pooled.data.copyOf()`——即便 `isSpilled` 分支已避开落盘附件的读回，
  内存附件仍呈「池内一份 + 条目一份」双份驻留。单条条目编辑同样沿 `updateOrAddEntry` **遍历并复制全部分组**（非路径复制），
  随后 `clearSupersededSensitiveData` 再把全库（含历史）的敏感实例收进一个 `IdentityHashMap` 集合。
- **量级**：O(条目数 + 分组数 + 内存附件总字节) /每次保存；身份集合 O(元素数 × 字段数) /每次编辑。
- **核实时间点与方式**：2026-09-17 逐处阅读下列源码核实。
- **依据**：`database/.../file/KdbxBinaryDeduplicator.kt:47-60,68-88,113-118`、
  `database/.../session/SessionTreeEditor.kt:34-37`、`database/.../session/SessionContentMutations.kt:30-33`、
  `core/.../model/KdbxGroup.kt:103-105`。
- **整改方向**：① 去重器仅在**索引变化**时重建受影响条目；② 池内共享条目的交付改为引用而非 `copyOf()`
  （别名隔离由 `openStream()` 契约承担，须与 ISSUE-P3-07 借用语义对齐）；③ 编辑器改路径复制；
  ④ 身份集合同步增量维护或仅在锁定路径做一次。
- **验收标准**：同一库「保存前后内容一致」的既有回归全绿（含附件去重与别名隔离用例）；
  保存期附件字节的拷贝次数由 2 降为 ≤1（可断言分配量或引用同一性）；`clearSupersededSensitiveData` 语义不变。
- **风险提示**：本条触及**写入路径与内存所有权**，是 P2/P3 这批里回归风险最高的一条——须以「附件别名隔离 + 敏感数据清零」两类既有用例作前置护栏。

### ISSUE-P3-151 IO 缓冲与解析常数项（GZip 512 B / XML 逐字符写出 / 时间戳正则）

- **背景**：三处与库规模线性相关的常数项：
  ① `GZIPInputStream(raw)` / `GZIPOutputStream(cipherOut)` 未传缓冲尺寸，`java.util.zip` 默认内部缓冲为 **512 字节**，
     多 MB 载荷以极小粒度反复进出 inflate/deflate；同链上的 `CipherInputStream` / `CipherOutputStream`
     默认缓冲同为 512 字节，AES 硬件加速的收益被每 512 字节一次的 JNI 往返摊薄；
  ② `KdbxXmlStreamWriter.escape` 对文本逐**字符**调 `writer.write(int)`（保存侧随 XML 字符数线性）；
  ③ `KdbxXmlTimeHelper.parseDate` 每次先跑一次正则嗅探再 Base64 解码，而每个 `<Times>` 固定调用 **5 次**。
- **量级**：①O(载荷字节 / 512)；②O(XML 字符数)；③O(条目数 × 5) 次正则 + Base64。
- **核实时间点与方式**：2026-09-17 逐处阅读下列源码核实（①②为 Kotlin 侧形参缺省，非平台行为推断）。
- **依据**：`database/.../file/KdbxFile.kt:85,390-394`、`crypto/.../cipher/AesCipherEngine.kt:42-58`、
  `database/.../xml/KdbxXmlStreamWriter.kt:23,84-102`、`database/.../xml/KdbxXmlTimesNode.kt:53-59`、
  `database/.../xml/KdbxXmlTimeHelper.kt:143,152`（正则本身已是顶层 `val`，**不是**每条目重建，此处只优化调用次数与前缀判断）。
- **整改方向**：① 三处流构造传 `64 * 1024` 缓冲（一行改动）；② 转义按「无需转义的连续区间」批量 `write`；
  ③ 以廉价前缀判断替代正则，或对重复时间戳做小缓存。
- **验收标准**：解锁与保存的墙钟耗时在**同一真机、同一语料**下可复现下降（须给出前后对比数据，口径见 [`records/原生Argon2真机验证记录.md`](records/原生Argon2真机验证记录.md) §方法学）；
  `.kdbx` 往返字节级一致（现有互操作与 golden 用例全绿）。

### ISSUE-P3-152 常驻周期轮询（设置快照每 2 s / 完整性检测每 30 s）

- **背景**：两处与用户操作无关的定时轮询：
  ① `UnlockedNotificationController` 以 **2 s** 周期 `settingsStore.load()`——逐 key 读取约 50 项偏好并构造整个 `ExtendedSettings`
     对象，仅用于刷新「已解锁通知」目标态，且不比较新旧值即整体替换；
  ② `RuntimeIntegrityDetector` 每 **30 s** 重扫一次（含 `/proc/self/maps`、无障碍服务列表等 IO）。
- **量级**：O(1) 但**持续**发生（仅解锁期间 / 常驻），属「用户看不见也在耗电」类成本。
- **核实时间点与方式**：2026-09-17 逐处阅读下列源码核实。
- **依据**：`app/.../notification/UnlockedNotificationController.kt:79-84,151`、
  `app/.../data/repository/ExtendedSettingsStore.kt:60+`（`load()` 逐 key 读取）、
  `app/.../security/RuntimeIntegrityDetector.kt:74-81`。
- **整改方向**：① 偏好改 `OnSharedPreferenceChangeListener` 事件驱动，或退一步：`load()` 结果做相等性早退（当前不做比较）；
  ② 完整性检测的周期与触发条件按实际威胁模型复核（**不得**为省电削弱既有检测面——若判定不可动，应登记到
  [`已知工程限界.md`](architecture/已知工程限界.md)）。
- **验收标准**：解锁态静置 5 分钟，偏好读取次数由 O(150) 降为「仅变更时」；完整性检测语义与覆盖面不变（现有用例全绿），或在限界表登记为不整改并给出理由。

### ISSUE-P3-153 Rust 下沉候选的评估结论（**评估项，非整改项**）

- **背景**：用户 2026-09-17 提出「是否有些 Kotlin 可以改用 Rust 重写」。经全量排查，**结论是按收益/风险比，绝大多数候选不应下沉**。
  唯二值得进一步评估的是「**纯 Java BouncyCastle 且作用于数据面 / 用户可见时延**」的两处。
- **不应下沉（有依据的否定结论）**：
  1. **AES-256-CBC 外层流**（`crypto/.../cipher/AesCipherEngine.kt`，本仓默认 cipher）：走平台 JCE（Conscrypt 原生 + 硬件加速），Rust 无收益；
  2. **SHA-256 / HMAC**：`HashUtil` 走 JCE（平台原生），Rust 无收益——真正的浪费是**每次新建 `Mac`/`MessageDigest` 实例**（属 Kotlin 侧整改）；
  3. **Argon2 / AES-KDF / Twofish / 口令强度**：已是 Rust 内核（`crypto/src/main/rust/`）；
  4. **GZip/Deflate**：平台 zlib 原生；
  5. **KDBX 主管线整体下沉（XML 解析 + 模型树）**：读写链已是流式 `HMAC 块流 → AES(平台) → GZip(zlib) → SAX(平台 Expat)`，
     剩余开销是 **JVM 对象分配与树复制**（ISSUE-P3-150/151）——只有把整个对象模型搬进 Rust 才可能省掉，代价与回归面远超收益，与「保持现有稳定性」的前提冲突；
     另有 `AGENTS.md` §3.8 互操作证据纪律带来的对拍成本；
  6. **Compose UI 层**：与语言无关，属 ISSUE-P2-89/90 的结构性整改。
- **值得进一步评估的两处**（**均为纯 Java BouncyCastle，且不在硬件加速路径上**）：
  1. `ChaCha20CipherEngine` 以 `Cipher.getInstance("ChaCha7539", BC)` 承载**整库密文流**（选 ChaCha20 作为外层 cipher 的库全程走纯 Java 实现）；
  2. `PasskeyCryptoEngine`：ES256 / Ed25519 / RS256 的密钥生成与签名全部为 BC 纯 Java（RSA-2048 生成含 `certainty=80` 的素性搜索，属用户可见时延）。
- **「Rust 更快」的量化前提（不得外推）**：本仓唯一实测锚点是原生 Argon2 对 BouncyCastle 的 **2.2~5.4×（宿主）/ 5~11×（真机）**
  加速比（见 [`records/原生Argon2真机验证记录.md`](records/原生Argon2真机验证记录.md)）。该数字**只对「纯 Java BC vs Rust」成立**，
  **不得**据此推断「凡 Kotlin 改 Rust 都快」——对已走平台原生（AES/SHA/HMAC/zlib）的路径，重写只会引入 FFI 与供应链成本。
- **验收标准（评估项）**：若认领本项，须先给出上述两处各一份「BC 现行吞吐 vs Rust 候选吞吐」的真机实测对比（同一语料、同一设备、
  含离散度），再决定是否立项；**未取得实测数据前不得以「Rust 更快」为由发起重写**。
- **核实时间点与方式**：2026-09-17 逐处阅读 `crypto/` 全部 cipher / kdf / hash / passkey 实现与其 provider 选择，
  并核对 `crypto/src/main/rust/Cargo.toml` 现有内核边界与 `records/原生Argon2真机验证记录.md` 实测数据。
- **风险提示**：两处候选均需**新增 Rust 依赖**（如 `chacha20` / `p256` / `ed25519-dalek` / `rsa`），
  须先过 `crypto/src/main/rust/deny.toml`（`wildcards = "deny"`、依赖树无重复密码学实现）与本仓供应链 CVSS ≥ 7.0 闸门。

### ISSUE-P3-154 仓库投影流补 `flowOn`：整库投影仍在收集上下文（列表页为 Main）执行

- **背景**：`ISSUE-P3-149` ③ 原计划为 `RealVaultRepository.getEntries()` / `getGroups()` 补
  `flowOn(Dispatchers.Default)`，使「整库条目 → `UiVaultEntry`」的映射（逐字段解密 + 格式化 + TOTP 计算）
  彻底离开收集上下文。§114 已把该投影的**频率**由「每秒」降为「每次数据变更」，但**执行线程未变**：
  列表页的收集上下文是 `viewModelScope`（Main）。
- **未在 §114 一并做的理由**：`RealVaultRepository` 是数据层 `@Singleton`、无注入调度器，
  直接写死 `flowOn(Dispatchers.Default)` 会把投影放到**测试虚拟时钟无法控制**的真实线程池上，
  而 `RealVaultRepositoryTest` 有 40+ 处直接构造与同步断言 ⇒ 有把既有用例改成偶发红的实际风险。
- **整改方向**：先在数据层引入**可注入的调度器限定符**（对齐 UI 层既有 `@EntryDisplayDispatcher` 的做法），
  由 DI 提供 `Dispatchers.Default`、由测试注入测试调度器；再补 `flowOn`，并同步改测试构造点。
- **核实时间点与方式**：2026-09-17 由 §114 实施过程中的实测阻塞得出（工作区行尾与全量测试口径均已核实）。
- **验收标准**：整库投影不再在 Main 上执行（可用测试调度器断言）；`RealVaultRepositoryTest` 全绿且**不引入**
  依赖真实线程池的偶发断言；`getEntries()` 的收集者（列表页 / 自动填充 / 子库）行为零变化。

