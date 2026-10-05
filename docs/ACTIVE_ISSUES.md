# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。  
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。  
> **只放现存问题**：本文件**只保留尚未闭环的条目**——已闭环条目的正文、批次流水与索引段一律不留；历史实现与验收证据以 [RESOLVED\_LOG.md](RESOLVED_LOG.md) 与 `docs/resolved/batches/` 为单一真相源。  
> **实施前必读**：源自安全复核的条目，其 AC 已按 [`SECURITY_RECHECK_2026-09.md`](security/SECURITY_RECHECK_2026-09.md) §10 / §11 的**第四轮独立复核定版结论**逐条对齐，更正口径已内联到对应条目——照条目原文 AC 实施者可能**降低安全性**或**引入数据损坏**。  
> **例外（`ISSUE-P1-276` ～ `ISSUE-P3-299`）**：这批 2026-09-23 条目出自**两轮全仓审查**（8 个铺开代理 + 7 个对抗复核代理，反向口径＝先假设误报再取证），不属上述复核范围；其依据为各条「核实方式」所记的逐行反校与对抗推翻结论。凡条目标注「**未经对抗轮单独攻击**」或「**需外部证据（真机 / 真实 DAV 矩阵 / 官方对拍）**」者，认领时须按规则 6.1② 先自行复核前提，不得直接开工。  
> **闭环纪律**：任务完成后，将该条目从本文件**整条移入** [RESOLVED\_LOG.md](RESOLVED_LOG.md)（加一行索引 + 新增批次正文），并执行 `git commit & push`。
> **新增批次（2026-10-05）**：`ISSUE-P2-466~470` / `ISSUE-P3-474~487` 出自 [`records/软件工程质量审查记录_2026-10-05.md`](records/软件工程质量审查记录_2026-10-05.md)（五维度主源码静态审查 + 合并 / 同步 / 健康审计引擎深审）。**严重度映射**：记录标 medium（含 low/medium）→ **P2**，low → **P3**。这批条目为**纯静态审查**产出（未运行构建 / 测试 / 真机），认领时须按规则 6.1② 先复核前提（正文行号仅作核实时刻的快照）；其中涉及 `crypto/src/main/rust/**` 的原生面条目，入库前须按 AGENTS.md 测试资产纪律**四层 `connectedDebugAndroidTest` 真机实跑**（执行前按 §263 确认设备上无待保留数据或改用 AVD）。

---

## 条目维护规则（ISSUE-P3-28 确立）

1. **新增条目必须附「核实时间点」与「核实方式」**：任何声称「某文件需要删除 / 某处没有消费方 / 某硬编码为 0」一类**关于代码库现状的前提**，都必须写明**何时、以何种手段**核实过。
   > 立规缘由：条目与代码库演进之间存在时间差，曾出现正文前提在开工时**已不成立**。
2. **开工前复核前提**：认领条目时先复核其正文前提（路径是否存在、行号是否漂移、消费方是否已出现）。前提已不成立的，**就地修正或显式标注**后再动手。
3. **行号一律视为「核实时刻的快照」**：正文内引用的 `文件:行号` 仅作定位提示，实现前须以真实内容为准。

---

## 优先级定义

|   等级   | 严重度与类型                                 |     处理原则     |
| :----: | -------------------------------------- | :----------: |
| **P0** | **阻断级 / 致命安全漏洞**（数据损坏、篡改未拦截、明文泄露、严重崩溃） | 立即停止其他工作优先修复 |
| **P1** | **高危缺陷 / 核心功能阻断**（权限失效、关键契约异常、核心流程失败）  |   最高优先级排期修复  |
| **P2** | **中危缺陷 / 协议互操作 / 性能瓶颈 / 测试有效性缺口**      | 正常排期，按批次连续解决 |
| **P3** | **低危项 / 进阶特性接线 / 代码整洁度 / 评估与体验优化**     |   渐进优化与特性补齐  |

---

## P0 阻断级问题（0 项）

> **暂无开放项**。

---

## P1 高危与核心功能问题（0 项）

> **暂无开放项**。（最近一条 `ISSUE-P1-431` 封印载荷瘦身已于 §401 整条闭环，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md) §401）

## P2 中危缺陷与协议/测试缺口（5 项）

> 本批 5 条出自 [`records/软件工程质量审查记录_2026-10-05.md`](records/软件工程质量审查记录_2026-10-05.md)（五维度静态审查 + 高风险面深审；严重度 medium 或 low/medium 映射 P2）。
> **Rust 原生面条目**（`ISSUE-P2-466` / `467` / `468`）按 AGENTS.md 测试资产纪律须**四层 `connectedDebugAndroidTest` 真机实跑**方可入库；执行前按 §263 确认设备上无待保留数据（或改用 AVD）。

### ISSUE-P2-466：`jni_bridge_ext.rs` AES / Twofish 两个 JNI 入口约 45 行逐字重复

- **核实时间点**：2026-10-05；**核实方式**：逐行比对 `twofish_cbc_jni`(124-168) 与 `aes_cbc_jni`(201-245) 函数体（差异恰 3 行），并核对两内核 `cbc_encrypt/decrypt` 签名一致（`aes_cbc.rs:64/95`、`twofish_cbc.rs:60/89`）；`grep twofish` 于 `已知工程限界.md` 零命中。
- **背景**：两函数体除 3 行（140↔217 的 `BLOCK_LEN` 常量、145↔222 与 147↔224 的内核调用）外逐字一致，涵盖判空 / `catch_unwind` / 入参转 `Zeroizing` / 长度闸门 / IV 回写 / `new_byte_array` + `set_byte_array_region` / 失败归一 null。这是安全敏感边界样板（擦除语义、panic 归一、有符号闸门全在其中），两份拷贝意味着任一侧修复必须记得同步另一处，否则两条 cipher 路径**静默漂移**。`jni_bridge_ext.rs:200` 文档注释原样承认「与 `twofish_cbc_jni` 同构，仅内核不同」。
- **涉及文件**：`crypto/src/main/rust/src/jni_bridge_ext.rs`（依赖 `aes_cbc.rs` / `twofish_cbc.rs` 的签名面）。
- **验收标准**：
  ① 合并为单个私有 `block_cbc_jni(env, key, iv, data, encrypt, kernel: fn(&[u8], &mut [u8], &[u8]) -> Option<Vec<u8>>)`，加密 / 解密侧分别传两内核函数指针；两个 `#[no_mangle]` 导出仅一行转发，**不改** JNI 定长布局契约与擦除语义；
  ② 既有 AES / Twofish KAT 与等价用例全绿（`cargo test`）；
  ③ 原生面改动四层 `connectedDebugAndroidTest` 真机实跑 + 门禁 9/9 PASS，批次文档原样粘贴读数。

### ISSUE-P2-467：`aes_cbc.rs` CBC 加/解密「拷出形态」与「原地形态」双份手写实现

- **核实时间点**：2026-10-05；**核实方式**：逐行核对 `cbc_encrypt`/`cbc_decrypt`(64-89 / 95-120) 与 `cbc_encrypt_in_place`/`cbc_decrypt_in_place`(128-149 / 155-177) 的闸门、链值 `Zeroizing` 缓冲与循环体；确认 `jni_bridge_ext.rs:311/313` 走 in_place、`:222/224` 走拷出形态。
- **背景**：两形态是同一套 CBC 链接逻辑的两份实现（参数闸门 65/96/129/156、链值单缓冲 71/101/135/161、逐块 XOR-变换-推进循环全同）；原地版 KDoc 自述「与 `cbc_encrypt` 语义逐字节一致（含 `iv` 出口契约）」（124 行），一致性目前**仅靠人工对照 + 测试维持**。两形态分别服务整块 JNI 路径与 direct ByteBuffer 零拷贝路径，任一侧漂移将导致**两条生产管线密文不一致**。
- **涉及文件**：`crypto/src/main/rust/src/aes_cbc.rs`、`crypto/src/main/rust/tests/aes_cbc_tests.rs`（既有 NIST KAT 与等价用例 202-235）。
- **验收标准**：
  ① 拷出形态改为薄委托（`data.to_vec()` → `cbc_encrypt_in_place` → `Some(out)`，解密同），闸门与循环只留 in_place 一份；
  ② 既有 KAT / 等价用例全绿且产物逐字节不变（`cargo test`）；
  ③ 原生面改动四层 `connectedDebugAndroidTest` 真机实跑 + 门禁 9/9 PASS。

### ISSUE-P2-468：`strength.rs` 热路径每次调用全量分配 `Vec<char>` / `String`（性能 · 维度②）

- **核实时间点**：2026-10-05；**核实方式**：通读 `strength.rs:estimate`(158-279) 全函数并逐处定位分配点（160 / 200 / 499 / 520 / 548 / 582），核对 `MAX_ANALYZED_CHARS=256`(84) 是否约束这些分配。
- **背景**：`estimate` 在文档明示的「全库审计对每条口令调用」热路径(23-29)上，每次调用都全量分配：`chars: Zeroizing<Vec<char>>`(160，把口令以 UTF-32 物化，长度×4 字节)、`lowered: Zeroizing<String>`(200)、`date_like_weight` 的 `Zeroizing<String>`（499、520 两处）、`unique_char_count` 的 `Vec<char> others`(548)、`minimal_period` 的 `Vec<usize>`(582)。模块已把三条平方级路径线性化，但**逐调用全量分配**未解决，对长口令 / 超长恶意输入放大堆压力。
- **涉及文件**：`crypto/src/main/rust/src/strength.rs`（及其 `tests/strength_tests.rs`）。
- **验收标准**：
  ① 字符类别 / 键盘 / 周期判定改为直接消费入参 UTF-8 字节，消除 `Vec<char>` 物化；`lowered` 以 `&[u8]` 小写视图替代 `String` 分配（或等效方式）；
  ② 语义与分档**逐字节等价**（既有 `strength_tests.rs` 全量用例锁定，含常见口令 / 周期 / 超长惩罚用例）；
  ③ 原生面改动四层 `connectedDebugAndroidTest` 真机实跑 + 门禁 9/9 PASS。

### ISSUE-P2-469：`libs.versions.toml` bouncycastle 版本登记与兜底路径健康度（维度④依赖）

- **核实时间点**：2026-10-05；**核实方式**：直读 `gradle/libs.versions.toml`（字段 59 行 / 文件头 2-6 行）；核对 `.github/dependabot.yml` 的生态与目录覆盖；仓库内证据核对 `RESOLVED_LOG.md` §330。**未联网核实 CVE**。
- **背景**：`bouncycastle = "1.86"`(59 行) 是 KDF / 分组密码的**兜底（fallback）路径**（`CipherFallbackParityTest` 锁定等价性），属密码学大攻击面依赖。该版本项**无「货币性核对」登记**，且文件头(5 行)记的 `bcprov 1.85.2` 与字段值 `1.86` **不一致**（陈旧注释）⇒ 该依赖的货币性与 CVE 状态在仓内无留痕。
- **涉及文件**：`gradle/libs.versions.toml`、`.github/dependabot.yml`、`app/.../SupplyChainScanSurfaceTest`。
- **验收标准**：
  ① 文件头陈旧注释就地修正（`bcprov 1.85.2` → `1.86`）；
  ② 版本项补「货币性核对」注释（含核对日期、Maven Central 最新 1.8x 与官方安全公告结论）；
  ③ 在文档中明确「**兜底路径也须随安全修复上调**」的规则（BC 非仅构建期，而是生产兜底）；
  ④ 门禁 9/9 PASS。

### ISSUE-P2-470：`SettingsHealthController.scanDuplicates()` 未随审计移入 `Dispatchers.Default`，仍在主线程全库遍历（性能 · 调度器）

- **核实时间点**：2026-10-05；**核实方式**：直读 `SettingsHealthController.kt:133-151`（确认 `withContext(Dispatchers.Default)` 块为 142-145、`scanDuplicates()` 在 148 行块外）、`SettingsViewModel.kt:224-236`（确认 `scope = viewModelScope` 为唯一生产构造点）、`SettingsDatabaseMetaController.kt:63-74`（确认实现为全库扁平化 + `DuplicateEntryScanner.scan`）。
- **背景**：`ISSUE-P2-58` AC④ 已把 `getKdbxEntries()` + `HealthCheckEngine.analyzeEntries` 搬入 `Dispatchers.Default`，但同一次装配里的 `scanDuplicates()`（148 行）在**该块之外**求值，仍跑在 `viewModelScope`（Main）上；其实现链为全库遍历（`SettingsDatabaseMetaController.scanDuplicateEntries` → `DuplicateEntryScanner.scan`）⇒ **万级条目库上整库遍历阻塞主线程**。`DuplicateEntryScanner.scan` 本身为 O(n)（哈希分桶），问题在**执行线程**而非复杂度。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsHealthController.kt`、`SettingsDatabaseMetaController.kt`、`SettingsViewModel.kt`。
- **验收标准**：
  ① `scanDuplicates()` 的求值随 `analyzeEntries` 一并移入同一 `Dispatchers.Default` 块（`runBreachCheck` 为挂起网络调用，按既有口径另判）；
  ② 既有单测全绿（测试调度器注入口径不变），并以「主线程零阻塞」类守卫用例或探针锁定该调用点；
  ③ 大库（万级条目）真机走查无主线程 jank；全量 `test` 绿 + 门禁 9/9 PASS。

## P3 低危问题、特性接线与体验优化（14 项）

> 本批 14 条出自 [`records/软件工程质量审查记录_2026-10-05.md`](records/软件工程质量审查记录_2026-10-05.md)（五维度静态审查 + 高风险面深审；严重度 low 映射 P3）。
> 其中 **Rust 原生面条目**（`ISSUE-P3-474` / `475` / `476` / `477` / `478` / `479` / `480`）按 AGENTS.md 测试资产纪律须**四层 `connectedDebugAndroidTest` 真机实跑**方可入库。

### ISSUE-P3-474：`twofish_cbc.rs` 仍用 `aes_cbc.rs` 文件头明令禁止的旧循环形态

- **核实时间点**：2026-10-05；**核实方式**：直读 `aes_cbc.rs:14-16` 禁令原文与 `twofish_cbc.rs:66-83`、`95-112` 循环体；确认 twofish 无 `*_in_place`。
- **背景**：`aes_cbc.rs:14-16` 立有「逐块循环里**禁止**任何逐块分配 / 逐块 `Vec::push` / 逐块 `Zeroizing`」的性能纪律（§147 实测教训）；`twofish_cbc.rs` 加/解密循环（71/100 与 78/107 行）仍是每块 `Zeroizing::new(*as_block_ref(chunk))` + `out.extend_from_slice(&block[..])` 的旧形态，且无 in_place 变体。Twofish 自述「作用于整库数据流，属数据面热点」。
- **涉及文件**：`crypto/src/main/rust/src/twofish_cbc.rs`。
- **验收标准**：① 改写为「预分配 out + 链值单缓冲 + 直接写切片」循环（可一并补 `*_in_place`）；② `cargo test` 既有 KAT / 等价用例全绿且产物逐字节不变；③ 原生面四层真机 + 门禁 9/9 PASS。

### ISSUE-P3-475：`jni_bridge_ext.rs` direct ByteBuffer 取址 / 容量校验 unsafe 样板两处逐字重复

- **核实时间点**：2026-10-05；**核实方式**：逐行比对 `aes_cbc_direct_jni`(301-309) 与 `applyKeystreamDirect`(442-450) 的取址序列与 SAFETY 注释；确认 `:425` KDoc 明言后者为非生产路径探针。
- **背景**：两处各自手写 `get_direct_buffer_address` → `get_direct_buffer_capacity` → `is_null() || len == 0` 判定 → `from_raw_parts_mut`，连 SAFETY 注释都逐字重复；unsafe 样板重复意味着任何一处修订（如容量上界校验）需人工双改。
- **涉及文件**：`crypto/src/main/rust/src/jni_bridge_ext.rs`。
- **验收标准**：① 抽 `unsafe fn direct_buffer_slice<'a>(…) -> Option<&'a mut [u8]>`（唯一一份 SAFETY 注释）供两处共用；② `cargo test` + 原生面四层真机 + 门禁 9/9 PASS。

### ISSUE-P3-476：`jni_bridge_ext.rs` JNI `catch_unwind` 护栏样板跨 8 个导出路径重复

- **核实时间点**：2026-10-05；**核实方式**：全文件清点 `catch_unwind(AssertUnwindSafe(...))` 出现点（67 / 135 / 212 / 298 / 346 / 384 / 439 / 504）与其收尾 `match` 形态。
- **背景**：8 处导出路径的 `catch_unwind` 护栏原文一致；**但收尾 `match` 分两类**——6 处 `Ok(Some(arr)) => arr, _ => null_mut()`（`deriveKey` / `twofish_cbc_jni` / `aes_cbc_jni` / `estimate` / `applyKeystream` / `passkey_sign_jni`），2 处 `Ok(Some(n)) => n, _ => -1`（`aes_cbc_direct_jni` / `applyKeystreamDirect`，返回 `jint`）。任一处 panic→失败归一语义的修订需人工同步 8 处，存在静默漂移风险。
- **涉及文件**：`crypto/src/main/rust/src/jni_bridge_ext.rs`。
- **验收标准**：① 抽按返回型参数化的护栏（`jbyteArray` / `jintArray` / `jint` 三类薄包装，或返回 `Result<Option<T>, ()>` 的泛型护栏）；**注意**返回 `Option<R>` 却写 `null_mut()` 不可编译，须按返回型分型；② 全部导出符号的既有静态签名断言用例保持绿；③ `cargo test` + 原生面四层真机 + 门禁 9/9 PASS。

### ISSUE-P3-477：`jni_bridge_ext.rs` 输出字节数组发射样板跨 5 处重复

- **核实时间点**：2026-10-05；**核实方式**：逐处核对 `new_byte_array` + `set_byte_array_region` + `into_raw()` 序列（80-84 / 157-161 / 234-237 / 396-399 / 514-517）与 `estimate` 的 IntArray 变体(351-352)。
- **背景**：5 处逐字重复（含 u8↔i8 同宽同布局的 SAFETY 注释），另有 `new_int_array` + `set_int_array_region` 的同构变体。
- **涉及文件**：`crypto/src/main/rust/src/jni_bridge_ext.rs`。
- **验收标准**：① 抽 `emit_byte_array` / `emit_int_array`（各含唯一 SAFETY 注释）供 5 处 + `estimate` 共用；② `cargo test` + 原生面四层真机 + 门禁 9/9 PASS。

### ISSUE-P3-478：`aes_cbc` / `twofish_cbc` 名义同构但已实质漂移

- **核实时间点**：2026-10-05；**核实方式**：直读两文件模块头自述（`aes_cbc.rs:3`）与四个 `cbc_encrypt/decrypt` 实现；确认 aes 有 in_place、twofish 无。
- **背景**：`aes_cbc.rs:3` 自述与 `twofish_cbc`「完全同构」，但 `aes_cbc` 已下沉 `*_in_place`(128-177) 而 twofish 完全没有，且 twofish 仍用旧循环形态（见 `ISSUE-P3-474`）⇒ 名义同构、实际不对称，漂移风险随各自迭代上升。
- **涉及文件**：`crypto/src/main/rust/src/aes_cbc.rs`、`twofish_cbc.rs`。
- **验收标准**：① 采纳 `ISSUE-P2-467` 的委托整改后为 twofish 补 `*_in_place`，或抽共享 `cbc_loop` 使两内核面完全对称；② 两内核模块头自述与实际形态一致（防「名义同构」误导）；③ `cargo test` + 原生面四层真机 + 门禁 9/9 PASS。

### ISSUE-P3-479：`jni_bridge_ext.rs` 导出函数局部 JNI 引用未显式 `DeleteLocalRef`（防御性）

- **核实时间点**：2026-10-05；**核实方式**：全文件 `grep DeleteLocalRef` 零命中；清点 `new_byte_array` / `new_int_array` 产出点（80 / 157 / 234 / 351 / 396 / 514）。
- **背景**：各导出函数在 `catch_unwind` 闭包内创建局部 JNI 引用 `java_out` 后未显式释放，依赖 native method 返回时统一回收。当前均为单次调用（无实际泄漏），但长数据分段若将来在某函数内循环处理，默认 512 的 local ref 帧会被耗尽。
- **涉及文件**：`crypto/src/main/rust/src/jni_bridge_ext.rs`。
- **验收标准**：① 对创建后即刻消费完的局部引用显式 `DeleteLocalRef`，**或**在模块级注释固化「导出函数不得在本帧内累积 local ref」纪律（二选一，写明取舍）；② `cargo test` + 原生面四层真机 + 门禁 9/9 PASS。

### ISSUE-P3-480：`strength.rs` 超长口令 `minimal_period` 全量 `Vec<usize>` 分配放大（DoS 内存面）

- **核实时间点**：2026-10-05；**核实方式**：直读 `strength.rs:221`（`minimal_period(&chars)` 传全量）与 582（`vec![0usize; len]`），核对 `MAX_ANALYZED_CHARS` 不约束此处（218-220 注释明言跑全量字符）。
- **背景**：`minimal_period`(576-601) 为 KMP 前缀函数分配随输入长度线性增长的 `Vec<usize>`，调用点传入**未经截断**的全量字符 ⇒ 恶意超长口令可在热路径上放大内存峰值。
- **涉及文件**：`crypto/src/main/rust/src/strength.rs`。
- **验收标准**：① 改为 O(1) 额外空间的滚动 / 双指针判定（或固定上限缓冲），删除随输入增长的分配；② 周期判定语义不变（`abc×100` 仍命中 `FLAG_PERIODIC_REPEAT`），既有用例全绿；③ `cargo test` + 原生面四层真机 + 门禁 9/9 PASS。

### ISSUE-P3-481：`libs.versions.toml` 的 `agp` 字段值与注释叙述不一致（配置漂移）

- **核实时间点**：2026-10-05；**核实方式**：直读 `gradle/libs.versions.toml:17`（`agp = "9.4.1"`）与 8-16 行注释；`grep 9.2.1 / 9.4.1 / 9.4.0` 于全仓 `*.kts` 零命中（无第二版本来源）；核对 `RESOLVED_LOG.md` §330。
- **背景**：字段值为 `9.4.1`，而注释(8-16 行)整段陈述「降级到 AGP 9.2.1」；`RESOLVED_LOG.md` §330 已记「AGP 9.2.1→9.4.1」由 Dependabot PR 合并（commit `d53fc93e`，附 `SupplyChainScanSurfaceTest` 对 9.4.1 工具链面的 fail-closed 验证）⇒ **9.4.1 为实际生效值，注释为陈旧遗留**；另 14 行「Gradle 9.7.1 ≥ AGP 9.2 要求的 9.4.1」表述错乱。
- **涉及文件**：`gradle/libs.versions.toml`。
- **验收标准**：① 注释改为如实记录版本演进（9.4.0 → 9.2.1（IDE 兼容）→ 9.4.1（回升级））与当前依据；② 修正 14 行错乱表述；③ `assembleDebug` 绿 + 门禁 9/9 PASS。

### ISSUE-P3-482：`libs.versions.toml` alpha 依赖的风险登记（`composeScreenshot` 未标 dev-only）

- **核实时间点**：2026-10-05；**核实方式**：直读 `libs.versions.toml:44 / 76 / 91` 与 33-44 行注释；核对 `docs/records/退役依据承接-ISSUE-P3-09.md` 与 `产品裁决登记.md`（后者无 `ISSUE-P3-09` 条目）。
- **背景**：`material3 = "1.5.0-alpha28"`(44) 的 alpha 依赖**属已裁决取舍、非缺陷**，管控结论登记于 `libs.versions.toml` 35-43 行与 `退役依据承接-ISSUE-P3-09.md`（含退出条件：1.5.0 stable 发布后回归 Compose BOM）；`composeScreenshot = "0.0.1-alpha16"`(76) 为**仅开发期**工具（非生产依赖）但未在该项显式标注属性。
- **涉及文件**：`gradle/libs.versions.toml`。
- **验收标准**：① `composeScreenshot` 版本项显式标注「dev-only / 非生产依赖」；② `material3` alpha 维持退出条件跟踪（stable 发布即触发回归），不新增管控信息缺口；③ 门禁 9/9 PASS。

### ISSUE-P3-483：`SyncCache.openCacheStream` 以裸 `FileInputStream` 返回，关闭责任在调用方

- **核实时间点**：2026-10-05；**核实方式**：直读 `SyncCache.kt:282-285`；全仓 `grep openStream()` 清点生产消费点（`InnerHeader.kt:125`、`KdbxBinaryDeduplicator.kt:153-154`、`FileBinaryStore.kt:54-55`、`KdbxAttachment.kt:55`）。
- **背景**：`openCacheStream` 直接返回 `FileInputStream(file)`，无任何 `.use{}` 强制，所有权移交调用方；若后续新增调用点遗漏关闭即泄漏文件描述符（当前两处生产消费者均已 `.use{}`，故为纪律性风险而非实测泄漏）。
- **涉及文件**：`sync/src/main/java/com/keepasskey/sync/engine/SyncCache.kt`。
- **验收标准**：① 二选一——改为「接收消费 lambda」形态以内建 `use`，或在 KDoc 显式固化「调用方必须 `use`」纪律；② `:sync:` 单测绿 + 门禁 9/9 PASS。

### ISSUE-P3-484：健康度页 UI 为行数治理做「逐字搬动」复制，展示映射存在多份拷贝

- **核实时间点**：2026-10-05；**核实方式**：直读四个文件的 KDoc 自陈（`HealthCheckScreenSections.kt:36/111/151`、`HealthCheckComponents.kt:249`）与集中处（`HealthAuditTone` 枚举 :234、`healthAuditTone` 函数 :242）。
- **背景**：`ISSUE-P3-188/382/405/409` 等分拆批次以「逐字搬动、零行为变更」满足 `count_line_tiers` tier 预算，逻辑重复度低、漂移风险小，但图标 / 配色 / 文案的展示映射在多文件存在拷贝。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/{HealthCheckScreen,HealthCheckComponents,HealthCheckIssueList,HealthCheckScreenSections}.kt`。
- **验收标准**：① 跨文件重复的「审计行展示映射」收敛为单一真相源 helper（判定侧已集中在 `healthAuditTone`，展示侧同口径收口）；② 收敛后 `count_line_tiers`（`tier1=0` / `tier2` 不超预算）与 `long_functions` 仍绿；③ 全量 `test` 绿 + 门禁 9/9 PASS。

### ISSUE-P3-485：`applyResolvedEntriesToGroup` 组内线性查找构成 O(k²)

- **核实时间点**：2026-10-05；**核实方式**：直读 `SyncConflictMergeAdjudication.kt:224-254`（`own.forEach { … working.indexOfFirst { it.id == entry.id } … }` 在 233 行）。
- **背景**：批量落位已裁决条目时，组内以 `indexOfFirst` 逐条线性查找（嵌于 `forEach`）⇒ 单组 O(k²)（`k` = 同组裁决条目数）。同组裁决条目通常很小，故为低优先；同组大量条目一次性裁决时随数量平方增长。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/sync/SyncConflictMergeAdjudication.kt`。
- **验收标准**：① 先以 `id → 下标` 表（一次建表）替代内层 `indexOfFirst`，单组降回 O(k)；② 落位语义不变（同父组内按原冲突顺序「找到即替换、找不到即追加」），既有合并等价用例全绿；③ 门禁 9/9 PASS。

### ISSUE-P3-486：`SyncCacheMaintenance.clearAll` KDoc 仍称防回滚状态含 Keystore HMAC（文档漂移）

- **核实时间点**：2026-10-05；**核实方式**：直读 `SyncCacheMaintenance.kt:173-194`（181 行原文）与 `SyncRollbackGuard.kt` 类 KDoc + `PREFIX_MAC` 遗留过滤注释(242-243)。
- **背景**：`ISSUE-P3-326` 已按用户裁决**移除**防回滚状态的 Keystore MAC 认证层（`SyncRollbackGuard` KDoc 已更新为「明文摘要文件」，`mac=` 仅为遗留兼容过滤），但 `SyncCacheMaintenance.clearAll` 的 KDoc(:181) 仍写「内容仅 SHA-256 摘要 + Keystore HMAC」⇒ 两处口径矛盾，会给后续维护者错误的信任边界认知。
- **涉及文件**：`sync/src/main/java/com/keepasskey/sync/engine/SyncCacheMaintenance.kt`。
- **验收标准**：① 该句同步为「仅 SHA-256 摘要的明文状态文件（`ISSUE-P3-326` 起无 MAC）」；② 全仓 `grep Keystore HMAC` 无残留同型陈旧表述；③ 门禁 9/9 PASS。

### ISSUE-P3-487：旧版无障碍回填通道的口令 `String` 承载未登记入 `已知工程限界.md` §2.6

- **核实时间点**：2026-10-05；**核实方式**：直读 `LegacyAutofillCoordinator.kt:27-39` 与 `LegacyAutofillAccessibilityService.kt:200-218`；`grep LegacyAutofill / P3-324 / ACTION_SET_TEXT` 于 `已知工程限界.md` §2.6 零命中。
- **背景**：旧版无障碍回填通道以 `PendingFill(password: String)` 承载口令明文并经 `Bundle.putCharSequence` 交付（`ACTION_SET_TEXT` 只收 `CharSequence`）。该形态属 `已知工程限界.md` §2.6（平台 Autofill / CM **边界瞬时** `String` 副本）**同类**、就地 KDoc 亦自述与 `ISSUE-P2-15` 同源，但 §2.6 的枚举未列入本处（该节 2026-09-23 清点，早于 `ISSUE-P3-324` / §332 落地）⇒ 限界表「仅生产路径列入本节」口径存在缺口。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/autofill/legacy/LegacyAutofillCoordinator.kt`、`LegacyAutofillAccessibilityService.kt`、`docs/architecture/已知工程限界.md`。
- **验收标准**：① 在 `已知工程限界.md` §2.6 补登记本处（同类、非新类别，含「String 是 `ACTION_SET_TEXT` API 硬约束」的理由与生命周期压缩口径）；② 门禁 9/9 PASS（`check_md_links` 断链为 0）。

