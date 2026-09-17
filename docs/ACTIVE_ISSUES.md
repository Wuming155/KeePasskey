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

> **暂无开放项**。`ISSUE-P2-91`（同步内容变化检测漏比 `times` 与历史内容）已于 §122 批次
> **经前提复核撤销**——「只改 `times` 的本地编辑被静默丢弃」在本应用可达面上**不成立**
> （生产代码无 `expires` / `expiryTime` 写入者；`times` 的改写必然伴随 `fields` 或 `customFields` 变化）；
> 其真实残余（两处判定**口径刻意不同** + 历史只比条数）作为**口径而非缺陷**登记
> [`architecture/已知工程限界.md`](architecture/已知工程限界.md) **§10**，见
> [`resolved/batches/122-同步变化判定口径声明与语义锁定批次.md`](resolved/batches/122-同步变化判定口径声明与语义锁定批次.md)。

> **本批历史**：2026-09-17 登记的两条 CPU 占用瓶颈（`ISSUE-P2-89` 列表页秒级整页重建、
> `ISSUE-P2-90` TOTP 重算 O(T×N)）已于同日在 §114 批次闭环，实现与验证证据见
> [`resolved/batches/114-列表页秒级重建与TOTP重算收敛批次.md`](resolved/batches/114-列表页秒级重建与TOTP重算收敛批次.md)。
> 本批的**残留风险**（整库投影仍在收集上下文执行）已由 §117 闭环（`ISSUE-P3-154`），见
> [`resolved/batches/117-仓库投影流补flowOn批次.md`](resolved/batches/117-仓库投影流补flowOn批次.md)。

---

## P3 低危问题、特性接线与体验优化（10 项）

> 本批为 2026-09-17「降低 CPU / 内存占用」排查的**其余开放结论**。
> 条目 153 为 **Rust 下沉候选的评估结论**（评估项）；条目 155 为同轮后续批次（§115）开工复核转登。
> 同轮排查的其余条目均已闭环：150 / 151 见 §115、152 见 §116、154 见 §117、156 见 §118、157 见 §119，
> 原 `ISSUE-P3-149`（投影热路径）的 ①②④ 见 §114、**③ 转登的 `ISSUE-P3-154` 见 §117**。
>
> **条目 160 ~ 181 的由来**：2026-09-17「算法与数据结构专项」排查（用户提出「看看是不是坏算法、有没有坏数据结构」），
> 方式为**五路并行静态审计 + 主控逐条复核关键点位**，覆盖 `app` / `core` / `crypto` / `database` / `sync`
> 五模块全部 `.kt` 源文件；已排除本清单与 `RESOLVED_LOG.md` 中已闭环项、以及
> [`已知工程限界.md`](architecture/已知工程限界.md) / [`产品裁决登记.md`](architecture/产品裁决登记.md) 中已裁决项。
> **口径声明（须先读）**：该批全部结论均为**静态代码结构推导**（复杂度与调用频率），**无任何性能实测数据**
> ⇒ **不得**把下列条目读作「已测得百分比收益」；整改验收标准一律按「不再产生某类工作」的结构性判据给出。
> 批内按「正确性已单列 `P2-91` → 高杠杆 → 低影响」顺序编号，编号续用不复用。
> **已闭环（§121 第一档：零风险局部项）**：`ISSUE-P3-162`（分组索引平方级投影）、
> `ISSUE-P3-172`（①②③ 循环内新建重对象；**④ 已裁决不实施**，理由登记 [`已知工程限界.md`](architecture/已知工程限界.md) §9）、
> `ISSUE-P3-173`（OTP Base32 装箱与线性查表）、`ISSUE-P3-181`（密钥文件 / CSV / 标签解析常数因子）——见
> [`resolved/batches/121-零风险局部项与守卫用例批次.md`](resolved/batches/121-零风险局部项与守卫用例批次.md)。
> **已闭环（§123 第二档：循环结构改造 ‣ 批量树操作单趟化）**：`ISSUE-P3-160`（批量删除 / 移动按 id
> 逐次重走整树）、`ISSUE-P3-161`（冲突决策逐条重建整棵树）——见
> [`resolved/batches/123-批量树操作单趟化批次.md`](resolved/batches/123-批量树操作单趟化批次.md)。
> **已闭环（§124 第三档：同一份数据重复计算 ‣ 前两条）**：`ISSUE-P3-166`（健康检查同一条口令
> 解密 3 次、哈希 2 次）、`ISSUE-P3-169`（内存驻留加密每次访问新建 `Cipher` / `Mac`；
> **其 AC ② 经复核判定原理不可实施**，依据见批次文档 §1.2）——见
> [`resolved/batches/124-同一份数据重复计算收敛批次.md`](resolved/batches/124-同一份数据重复计算收敛批次.md)。
> **已闭环（§125 第三档：同一份数据重复计算 ‣ 第三条）**：`ISSUE-P3-167`（同步接受路径对同一份字节
> 算 3 遍 SHA-256）——**① 摘要一次化已做**；**② 基线前移的「重复写盘跳过」判定不做**，理由与
> 解除条件登记 [`architecture/已知工程限界.md`](architecture/已知工程限界.md) **§11**，见
> [`resolved/batches/125-同步接受路径摘要一次化批次.md`](resolved/batches/125-同步接受路径摘要一次化批次.md)。
> **已闭环（§126 第三档：同一份数据重复计算 ‣ 第四条）**：`ISSUE-P3-171`（自动填充评分对全库条目
> 逐条字符串物化）——**① 形状短路与 ② `url` 单读已做**；**AC ② 的原文路线（为 `KdbxEntry` 加不解密
> 判定入口）判定不采用**（评分路径必须要 URL 文本，只读一次已吃掉同一条收益且不新增 API 面），见
> [`resolved/batches/126-自动填充评分逐条目物化收敛批次.md`](resolved/batches/126-自动填充评分逐条目物化收敛批次.md)。
> **已闭环（§127 第三档：同一份数据重复计算 ‣ 收口）**：`ISSUE-P3-170`（自动填充请求内的重复工作：
> 证书摘要 ×2 / Keystore IPC / hex 格式化）——①②③ 已做；「同请求内 `Mac` 复用」与「按包名跨请求
> 记忆化」两项**判定不做**并留痕，见
> [`resolved/batches/127-自动填充请求内重复读取收敛批次.md`](resolved/batches/127-自动填充请求内重复读取收敛批次.md)。
> **第三档（同一份数据被算两遍以上）已全部闭环**（166 / 167① / 169① / 170 / 171）。
> **已闭环（§128 第二档剩余项之一：循环结构改造）**：`ISSUE-P3-163`（字段引用引擎按每个引用重建整库
> 扁平列表）——改为入口建一次**按字段惰性**的引用目标索引并沿递归共享；AC 建议的「一次性建全部字段
> 索引」经复核**刻意收窄**（那会把全库口令解密一遍，属反向优化），见
> [`resolved/batches/128-字段引用解析索引一次化批次.md`](resolved/batches/128-字段引用解析索引一次化批次.md)。
> **已闭环（§129 第四档：线程落点 ‣ 首条）**：`ISSUE-P3-174`（列表页整库投影缺 `flowOn`）——`uiState`
> 在 `stateIn` 前补 `.flowOn(displayDispatcher)`；验收取**结构断言**（AC 允许二选一）并如实声明其
> 不构成运行期派发证据，见
> [`resolved/batches/129-列表页整库投影离开收集上下文批次.md`](resolved/batches/129-列表页整库投影离开收集上下文批次.md)。
> **已闭环（§130 第四档：分配面 / 线程落点 ‣ 第二条）**：`ISSUE-P3-178`（完整性探测未做字节级化）
> ——`TracerPid` 改字节级解析 + 缓冲按线程复用；maps 改流式字节匹配 + 块间重叠（并新增 16 MiB
> 有界上限，封住「hook `read` 喂无限流」的挂死面）；**AC ③（`Debug` 探针去重）判定不做**并留痕，见
> [`resolved/batches/130-完整性探测字节级化批次.md`](resolved/batches/130-完整性探测字节级化批次.md)。
> 其余条目（`P3-164` / `P3-165` / `P3-168` / `P3-175` ~ `P3-177` / `P3-179` / `P3-180`）**仍待整改**。

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


### ISSUE-P3-164 每次保存无条件执行全树历史保留期维护

- **背景**：`database/.../session/DatabaseSession.kt:290` 每次 `save()` 调
  `HistoryManager.pruneGroupHistoryByAge(rootGroup, maintenanceHistoryDays)`（默认 365 ⇒ 恒执行）；
  `history/HistoryManager.kt:130` 的实现为 `group.entries.map{…}` + `group.subgroups.map{…}` 递归，
  即便整库无一条历史快照，也仍为**每个分组各分配两个新 List**（现有 `changed` 判定只避免了 `group.copy`）。
- **整改方向**：先做 O(1) 的「本库是否存在历史快照」闸门（解析 / 变更时维护计数或惰性标记），无历史直接跳过；
  递归改为「仅当子树确有变化时才新建列表」。
- **验收标准**：无历史库的保存路径不再产生分组规模的新列表（结构断言或分配计数）；
  有历史库的修剪结果与现状等价；保留期边界用例全绿。
- **核实时间点与方式**：2026-09-17 读 `DatabaseSession.kt` 保存路径与 `HistoryManager.kt:130` 实际内容核实。
- **风险提示**：历史修剪涉及**用户数据删除**，闸门必须是「保守不修剪」方向（宁可多跑一次）。

### ISSUE-P3-165 分组父链自愈的 `O(G × 深度)` 退化与分组装配无深度上限（安全面）

- **背景**：`sync/.../merge/KdbxGroupMerger.kt:99-115` 对**每个分组**从自身起上溯父链并用 `mutableSetOf(gid)`
  逐组记录 `visited`，链状退化结构（**远端可构造**）下退化为 `O(G × d)`，最坏 `O(G²)`；
  同文件 `assembleGroupTree:131-152` 的分组树递归装配**无深度上限**。
  对照：同仓 `sync/.../webdav/WebDavPropfindParser.kt:94-111` 已用「显式栈 + 深度上限」加固过同一类风险。
- **整改方向**：以「自底向上一次染色」替代逐组上溯（或缓存每组的「到根可达」判定），复杂度降为 `O(G)`；
  装配改显式栈并加分组深度上限（上限值须显式声明依据，超限按既有失败语义处理，不得静默截断）。
- **验收标准**：新增用例覆盖「远端构造的深链 / 环状父链」（现有用例若无此形状须补）；
  越限库按既定错误语义失败而非栈溢出；正常库合并结果不变。
- **核实时间点与方式**：2026-09-17 读 `KdbxGroupMerger.kt:95-155` 与 `WebDavPropfindParser.kt:94-111` 对照核实。
- **风险提示**：属**解析/合并健壮性**（攻击面）而非纯性能项，与 `P3-155` 同属高回归面，须排在零风险项之后。

### ISSUE-P3-168 冲突同步周期最多 6 次全量 KDBX 加解密（每次各跑一遍 KDF）

- **背景**：`app/.../sync/SyncCycleRunner.kt:127` 在内存基线缺失时解析缓存快照，
  `:131` 序列化本地库，冲突时 `SyncConflictController.kt:227` 又把**刚序列化出的字节解析回树**，
  再解析远端字节（`:229`）与 base 快照（`:244`），合并后再序列化一次（`:288`）⇒ 常态冲突周期
  = 4 次全量 load + 2 次全量 save = **6 次 KDF 派生**（KDBX4 默认 Argon2id，单次数百毫秒量级），
  且全程持 `SyncSessionState.mutex`。其中 `parse(localBytes)` 的产物与内存会话树等价，属可省的一次解密。
- **整改方向**：① 合并直接用会话内存树构造待合并模型，仅在字节与内存树可能不一致时回落解析；
  ② 为 `SyncDatabaseCodec` 加会话级「内容摘要 → 已解析树」缓存（锁内使用，锁库 / 超限即 `clearSensitiveData` 淘汰）。
- **验收标准**：同一冲突周期内 KDF 派生次数下降（调用计数/日志断言）；合并结果与现状逐字段一致；
  `§90` 的防回滚不变量与 `§114` 的 TOTP 缓存失效点全绿；缓存淘汰路径有显式擦除断言。
- **核实时间点与方式**：2026-09-17 读 `SyncCycleRunner.kt:127-139`、`SyncConflictController.kt:220-292` 核实。
- **风险提示**：树级缓存**扩大解密明文的驻留面**，必须按 `§52`（同步解析落盘与内存池擦除边界）同口径登记
  所有权与擦除责任，否则不得实施；本条属高回归面，排在零风险项之后。

### ISSUE-P3-175 秒级节拍常驻与整页重建（详情页 / 验证器页 / 节拍追踪器）

- **背景**：① `app/.../ui/screens/detail/EntryDetailViewModel.kt:167` 的 TOTP ticker 在 `init` **常驻启动**，
  页面退到后台栈仍每秒唤醒（列表页 `§114` 已改订阅驱动，本页未跟进）；
  ② `app/.../ui/screens/authenticator/AuthenticatorViewModel.kt:80` 把秒级 tick 并入整页 `combine`
  ⇒ 每秒重建整表 + **逐条挂起仓库调用**（`§114` 为列表页准备的 `calculateEntryTotps` 批量通道
  **本页未接入**）；③ `app/.../ui/screens/vault/VaultListTotpTracker.kt:120` 每拍重建
  `filter` + `map` + `toSet`，而周期集合只在条目集合或其 TOTP 配置变化时才变。
- **整改方向**：① 改订阅驱动（`WhileSubscribed` 或生命周期可见性）或按 `period` 边界驱动；
  ② 验证器页接入 `calculateEntryTotps` 批量通道，并把「列表内容」与「剩余秒数」解耦
  （倒计时下沉到卡片内读共享刻度）；③ `entryPeriods()` 随条目快照缓存。
- **验收标准**：新增用例断言「1 Hz 刻度不触发整页状态新建」与「同拍内批量取码只调用一次」；
  `§120` 的 `ISSUE-P3-158` 周期口径用例（任一条目自身周期序号变化才算翻转）必须继续全绿；
  离屏停止的行为可观测。
- **核实时间点与方式**：2026-09-17 读 `EntryDetailViewModel.kt:160-175`、`AuthenticatorViewModel.kt:45-120`、
  `VaultListTotpTracker.kt:85-145` 核实。
- **风险提示**：TOTP 展示正确性已由 `P3-158` 收紧（周期口径），本条只改**驱动频率与重建面**，
  不得改变倒计时相位与翻转判据。

### ISSUE-P3-176 冷流重复订阅与应用根状态过宽导致的导航图重建

- **背景**：① `RealVaultRepository.getEntries()` / `getGroups()` 是冷流，而
  `VaultListViewModel.kt:249` 与 `VaultListDecorationsProvider.kt:38` 各自订阅一次
  ⇒ 一次数据变更做 2 份整库条目投影 + 2 份分组投影；详情页更密（`EntryDetailStateAssembler.kt` 中
  `getEntry(id)` 出现于 `:138` / `:104` / `:92` 三处，`getGroups()` 于 `:108` / `:203` 两处）。
  ② `app/.../ui/KeePasskeyApp.kt:61` 根组合读取**整个** `SettingsUiState`（100+ 字段）
  并在 `:243` 传给 `keepasskeyNavGraph(appSettings = …)`，而 `NavHost` 以
  `remember(route, startDestination, builder)` 建图 ⇒ 任一无关偏好变化都会整图 `createGraph`。
- **整改方向**：① 在 ViewModel / 仓库层对这两条流 `shareIn(scope, WhileSubscribed(5000))` 后复用
  （详情页把 `entry` 收敛为一条再 `combine` 派生路径与装饰）；② 根只读真正需要的窄字段，
  `keepasskeyNavGraph` 改接收窄参数或用 `remember` 包一层。
- **验收标准**：新增用例断言「同一次数据变更内整库投影只执行一次」（投影计数）；
  导航图不因无关设置字段变化而重建（结构断言或计数）；页面行为用例全绿。
- **核实时间点与方式**：2026-09-17 读 `VaultListViewModel.kt:248-267`、
  `VaultListDecorationsProvider.kt:30-50`、`EntryDetailStateAssembler.kt:85-210`、`KeePasskeyApp.kt:55-70/235-250` 核实。
- **风险提示**：`shareIn` 会改变流的**订阅语义与重放行为**（新收集者不再触发重算），
  须逐一核对「谁依赖冷流的重算」与 `§117` 的调度器注入口径；导航图改动须复核全仓预览与截图基线。

### ISSUE-P3-177 CBC 流式分块缓冲的反复分配与整块拷贝

- **背景**：`crypto/.../cipher/CbcStreams.kt:216-233` 每读满一个 64 KiB 块产生
  `ByteArray(chunkSize)` + `concat`（合并 pending 与整块）+ `pending = all.copyOfRange` + `transformBlocks` 的
  `slice = source.copyOfRange`（又一份整块），随后 `Arrays.fill(all, 0)` 与下轮 `wipeBuffers()` 再清一次
  ⇒ 约 **4 次 64 KiB 分配 + 3 遍整块内存搬运**；加密侧 `emitAlignedBlocks`（`:103-117`）每块再多一次
  `buffer.copyOf(aligned)`。该路径服务原生 Twofish（整库数据流）。
- **整改方向**：`chunk` / `slice` / `out` 改**实例级复用缓冲**（构造时分配一次，`Arrays.fill` 清零语义保留）；
  `concat` 改「pending 固定 16 B 缓冲 + 双缓冲轮换」；`transformBlocks` 让变换以 `(offset, len)` 工作，
  去掉 `copyOfRange`；`emitAlignedBlocks` 同理用预分配 scratch。
- **验收标准**：往返字节级一致；填充非法 / 长度非整数倍 / 提前 `close` 三类流式语义逐例对齐 JCE 基线
  （既有 `CbcStreamFramingTest` 为基线）；用毕清零语义逐条保留（含抛异常路径）。
- **核实时间点与方式**：2026-09-17 读 `CbcStreams.kt:95-300` 核实。
- **风险提示**：属**主加密数据面**（与 `ISSUE-P3-155` 同族），高回归面，须排在零风险项之后；
  「复用缓冲」**不得**成为「不清零」的借口——清零责任须逐路径重述。

### ISSUE-P3-179 非惰性大集合展开与组合期就地派生

- **背景**：① `app/.../ui/screens/settings/subscreens/DebugSettingsScreen.kt:236` 把上限 500 行的
  `DebugLogBuffer` 整体塞在**单个 LazyColumn item** 内 `forEach` 组合，且每行做 4 次 `line.contains(…)` 判色；
  ② `app/.../ui/screens/vault/VaultListDialogs.kt:241` 把全部分组 `filter{}.forEach{}` 铺进 `AlertDialog` 的 `text` 槽
  （无虚拟化、无高度上限，超出屏幕的分组不可触达）；
  ③ `app/.../ui/screens/edit/EntryEditFormSections.kt:101` 在组合期执行 `availableGroups.filter { !it.isRecycleBin }`
  （每次重组重算整库过滤）且 `items` 无 `key`；`VaultListComponents.kt:249` 面包屑 `items` 同样无 `key`。
- **整改方向**：① 日志改顶层 `items(logLines)` 并按前缀预判等级色；② 分组选择改
  `LazyColumn(Modifier.heightIn(max = 280.dp))` + `items(…, key = { it.id })`（照抄 `AppPickerDialog.kt:138`）；
  ③ 过滤下沉到 ViewModel 或 `remember(availableGroups)`，并补 `key`。
- **验收标准**：三处改为惰性 / 已记忆（结构断言）；对话框内分组可滚动触达（含超长列表）；
  既有对话框与编辑页用例全绿。
- **核实时间点与方式**：2026-09-17 读 `DebugSettingsScreen.kt:225-250`、`VaultListDialogs.kt:230-255`、
  `EntryEditFormSections.kt:90-115` 核实。
- **风险提示**：`AlertDialog` 的 `text` 槽不滚动，改为 `LazyColumn` 时须一并确认弹窗高度约束，
  避免「改好了但按钮被挤出屏幕」。

### ISSUE-P3-180 WebDAV 单次上传最多 4 个往返（重复 PROPFIND）

- **背景**：`sync/.../webdav/WebDavSyncProvider.kt:272` 在 `expectedEtag == null` 时额外插一次 PROPFIND
  探测 `Overwrite`，而上游 `app/.../sync/SyncCycleRunner.kt:204` 的 `establishRemoteBaselineIfMissing`
  已经探过一次；MOVE 成功但响应无 ETag 时再 `getMetadata` 一次（`:333`）；MOVE 失败重试 `for (attempt in 0..1)`
  （`:296`）又各带一次 412 分支的 `getMetadata`（`:300`）⇒ 首传一次最多 4 个往返，弱网下每往返被 RTT 放大。
- **整改方向**：把上游已探测到的远端存在性 / ETag 经参数下传（或让 `uploadAtomic` 接受
  `remoteAbsent: Boolean?`），单次上传内的探测结果在一次调用内复用。
- **验收标准**：首传路径的 HTTP 往返次数下降（请求计数断言，可用 MockWebServer 计次）；
  条件写失败 / 412 / 无 ETag 四条分支的既有行为与错误语义回归全绿。
- **核实时间点与方式**：2026-09-17 读 `WebDavSyncProvider.kt:215-340` 与 `SyncCycleRunner.kt:195-215` 核实。
- **风险提示**：条件写是并发正确性的正确性来源（`已知工程限界.md` §1.3），
  减少往返**不得**削弱「用 ETag 预检 + `If-Match` 条件写」的判定，只删重复探测。
