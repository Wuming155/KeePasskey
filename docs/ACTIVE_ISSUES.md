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
> 本批的**残留风险**（整库投影仍在收集上下文执行）已由 §117 闭环（`ISSUE-P3-154`），见
> [`resolved/batches/117-仓库投影流补flowOn批次.md`](resolved/batches/117-仓库投影流补flowOn批次.md)。

---

## P3 低危问题、特性接线与体验优化（3 项）

> 本批为 2026-09-17「降低 CPU / 内存占用」排查的**其余开放结论**。
> 条目 153 为 **Rust 下沉候选的评估结论**（评估项）；条目 155 为同轮后续批次（§115）开工复核转登、
> 条目 157 为 §118 开工复核转登。
> 同轮排查的其余条目均已闭环：150 / 151 见 §115、152 见 §116、154 见 §117、156 见 §118，
> 原 `ISSUE-P3-149`（投影热路径）的 ①②④ 见 §114、**③ 转登的 `ISSUE-P3-154` 见 §117**。

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

### ISSUE-P3-155 外层 AES-CBC 流的块粒度受限于 `CipherInputStream` 内部 512 B 缓冲

- **背景**：`ISSUE-P3-151` ① 的前提复核发现：**`CipherInputStream` / `CipherOutputStream`
  没有缓冲尺寸构造参数**（内部固定 512 B 常量），故 AES-256-CBC 外层流（本仓**默认 cipher**）
  每次只向平台加密实现投递 512 字节 —— 硬件加速的吞吐被每 512 字节一次的调用开销摊薄，
  而 `GZIPInputStream` 那侧的同类问题已在 §115 用构造参数解决。同仓已有先例可循：
  `CbcDecryptingInputStream` / `CbcEncryptingOutputStream` 正是为原生 Twofish 写的**自研分块流**
  （64 KiB 分块 + 单一 PKCS#7 实现 + 明确的异常语义，由 `CbcStreamFramingTest` 逐例对齐 JCE 基线）。
- **量级**：O(载荷字节 / 512) 次加密实现调用（10 MB 库约 2 万次）；绝对耗时取决于平台实现，
  **未实测**（宿主 JVM 的 Conscrypt 行为不代表设备侧）。
- **核实时间点与方式**：2026-09-17 由 §115 的开工复核发现（`AesCipherEngine.kt:42-58` 无缓冲形参），
  并以 `CbcStreams.kt` 既有实现作为可行路径的旁证。
- **整改方向**：为 AES-CBC 复用既有分块流骨架（`transform` 注入 JCE `Cipher.update`），
  **关键约束**：`Cipher` 自带链值状态，与既有 `transform(key, iv, block)` 的「iv 原地演化」契约不匹配，
  需先定义新契约（或让 AES 走独立实现），**不得**为复用而扭曲既有 Twofish 路径。
- **验收标准**：`.kdbx` 往返字节级一致；与官方实现互操作（`OwnProductInteropProbeTest` + `keepassxc-cli`）通过；
  流式语义（填充非法 / 长度非整数倍 / 提前 close）逐例对齐 JCE 基线；**并给出设备侧前后吞吐对比**再决定是否采纳。
- **风险提示**：本条触及**主加密数据面**，属高回归面改动，须排在所有零风险项之后。

### ISSUE-P3-157 `updateDatabaseMeta` 单条条目更新路径的整树重建与全库身份集合重建（passkey 计数器补丁）

- **背景**：`ISSUE-P3-156`（§118）已把 `SessionTreeEditor` 驱动的三条写路径（`saveEntry` / `saveGroup` /
  `batchMoveEntries`）改为增量定点擦除，但**单条条目更新还有一条不经编辑器的路径**：
  `PasskeyEntryCoordinator.applySignCountPatch` 经 `DatabaseSession.updateDatabaseMeta` 做原子读-改-写，
  而 `updateDatabaseMeta` 的变换是**任意整库变换**（同步合并 / 远端库接管会整体替换分组树），
  无法定位被替换节点 ⇒ 擦除只能退回通用实现（为整棵新树建身份集合，O(全库)）。
  该路径同时自带一处整树重建：`PasskeyEntryCoordinator.replaceEntry` 对**每个分组**做
  `group.copy(entries = …, subgroups = …)`（未命中分支也重建）。
- **量级**：O(分组数) 对象复制 + O(条目数 × 字段数) 身份集合元素 /每次 passkey 断言。
- **核实时间点与方式**：2026-09-17 逐处阅读 `PasskeyEntryCoordinator.kt:123-164`（原子读-改-写主体与
  `replaceEntry`）与 `SessionContentMutations.updateDatabaseMeta` 确认；该路径**不在** `ISSUE-P3-156`
  正文点名范围内（点名者为 `SessionTreeEditor.updateOrAddEntry` + `SessionContentMutations.
  clearSupersededSensitiveData`），故 §118 未纳入、转登本条。
- **整改方向**：新增「单条条目原子读-改-写」入口（会话 Mutex 内单次变换：按 id 定位 → 变换 → 落树 →
  增量定点擦除），并把 `replaceEntry` 改为路径复制；**不得**放宽 `updateDatabaseMeta` 的通用擦除
  （同步合并 / 远端库接管仍需它）。
- **验收标准**：passkey 计数器并发用例（32 路递增）与单调性契约全绿；该路径的擦除候选集合规模不再随全库增长；
  负向对照（故意漏擦计数器旧实例）用例必红。
- **风险提示**：计数器是 RP 侧防克隆校验的依据，读-改-写的原子性不可削弱（不得把变换拆到锁外）。

