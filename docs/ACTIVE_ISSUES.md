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

## P2 中危缺陷与协议/测试缺口（3 项）

> 前 5 条（`ISSUE-P2-466` ~ `470`，出自 [`records/软件工程质量审查记录_2026-10-05.md`](records/软件工程质量审查记录_2026-10-05.md)）已于 §446 整条闭环。
> 后 3 条为 **§446 真机实跑新发现**：`490` 设备侧测试判据在等号边界自相矛盾（`maxHeap` 恰为 256 MiB 即必红，**已修，待跨设备复跑**）；`491` 漏更设备侧用例致 `:app:androidTest` 编译失败并**静默阻断**第四层真机义务（**已修，剩 CI 机检接线**）；`492` `:app:` 层设备侧在 MIUI 真机上因系统 UID 冻结 + autofill 服务反复 bind/unbind 无法推进（**环境面欠账**）。


### ISSUE-P2-490：`InlineCompressedBinaryBudgetDeviceTest` 的 `2D ≤ M` 分支判据在**等号边界**必然失败（设备侧测试缺陷 · §446 发现）

- **核实时间点**：2026-10-05（真机 `M332BF` / Android 17 / API 37 实跑）；**核实方式**：真机 `:database:connectedDebugAndroidTest` 18 例中 1 例红，取其 `TEST-M332BF - 17.xml` 的 `<failure>` 原文（`InlineCompressedBinaryBudgetDeviceTest.kt:68`，`maxHeap=268435456` 即 **256 MiB**）；对照 §217 批次记录该用例在 Pixel_10 AVD 上 `maxHeap = 192 MiB` 时走 OOM 分支通过 ⇒ **同一用例在不同堆界设备上结论相反**。另经 `git diff --stat -- database/` 确认本批零触碰该模块，非回归。
- **背景**：该用例以「解压峰值下界 `≈ 2D`」为判据（`D = 128 MiB`，故 `2D = 256 MiB`）：`2D > M` 断言必须 OOM，`2D ≤ M` 断言必须成功。但 `2D` 只是**下界**——真实峰值还含 base64 解码中间态、`GZIPOutputStream` 解压缓冲与 `toByteArray()` 副本，故实际占用**严格大于 `2D`**。于是 `2D == M`（本机恰好 `256 MiB == 256 MiB`）时 else 分支要求成功，而真实行为必然 OOM ⇒ **判据在等号边界自相矛盾**。这不是环境噪声：`maxHeap = 256 MiB` 是 Android 常见堆界，凡命中该值的设备都会红；此前只在 192 MiB 的 AVD 上验证过，缺陷被掩盖。
- **涉及文件**：`database/src/androidTest/java/com/keepasskey/database/xml/InlineCompressedBinaryBudgetDeviceTest.kt`。
- **验收标准**：① 判据改为区分「`2D` 下界」与「实测峰值」，使 `2D ≤ M < 真实峰值` 区间不再自相矛盾（可按 `Assume` 标注该区间为环境不可判别，或改为断言「成功 ⇒ `M` 显著大于 `2D`」并写明余量依据）；② 须在 `maxHeap` 为 192 MiB 与 256 MiB 两类设备上各实跑一次绿（AVD + 真机），不得只在单台设备上验证；③ `:database:connectedDebugAndroidTest` 全绿且 `skipped == 0`；④ 结论回写 `docs/architecture/已知工程限界.md` §4.1 设备侧覆盖现状（`ISSUE-P2-200` 落点① 的量化结论依赖此判据）。

### ISSUE-P2-491：`ISSUE-P1-431` 移除 `keyFileBytes` 时漏更设备侧用例，`:app:androidTest` 编译失败已阻断第四层真机实跑（§446 发现 · 本批已修）

- **核实时间点**：2026-10-05（真机 `M332BF`）；**核实方式**：真机 `:app:connectedDebugAndroidTest` 报 `Kotlin compiler: NAMED_PARAMETER_NOT_FOUND / No parameter with name 'keyFileBytes' found`（`QuickUnlockSealDowngradeDeviceTest.kt:70`）；经 `git log -S keyFileBytes` 定位移除方为 `5c3005ea`（`ISSUE-P1-431` 封印载荷瘦身），经 `git diff --stat -- app/src/androidTest/` 确认本批零触碰该文件 ⇒ **先前批次遗留，非本批回归**。
- **背景**：`BiometricEnrollmentCoordinator` 的 `keyFileBytes` 形参已随封印载荷瘦身移除（密钥文件字节不再进 Keystore 载荷），但该设备侧用例的 `buildCoordinator` 仍传该具名实参 ⇒ `:app:androidTest` **编译期**即失败。后果不止一例红：AGENTS.md 测试资产纪律要求原生面改动须**四层 `connectedDebugAndroidTest` 真机实跑**方可入库，而 `:app:` 层编译不过即**根本无法实跑**，该义务被静默阻断——与 `ISSUE-P3-305`「闸门存在 ≠ 闸门被执行」同型，只是形态是「用例存在 ≠ 用例可编译」。本批已删除该陈旧具名实参（**只删一行、不删用例**）解除阻断。
- **涉及文件**：`app/src/androidTest/java/com/keepasskey/app/security/QuickUnlockSealDowngradeDeviceTest.kt`。
- **验收标准**：① `:app:connectedDebugAndroidTest` 编译通过并在真机实跑（用例不得被删除或 `Assume` 跳过）；② **补一条机检**：`*/src/androidTest/**` 与 `src/main` 的构造器具名实参一致性属编译期事实，但 `compileDebugAndroidTestKotlin` **不在 `test` 任务依赖图内** ⇒ 须新增把 `:app:compileDebugAndroidTestKotlin`（及余三层同任务）纳入 CI 的门禁条，避免同类遗漏再次静默阻断真机义务；③ 回写 `AGENTS.md` §5：设备侧「编译通过」亦不构成验证证据的补充说明（与 `:app:compileDebugScreenshotTestKotlin` 同款，须显式跑）。

### ISSUE-P2-492：`:app:` 层设备侧在 MIUI 真机上无法推进（系统 UID 冻结 + autofill 服务反复 bind/unbind，环境面欠账 · §446 登记）

- **核实时间点**：2026-10-05（真机 `M332BF` / Android 17 / API 37 / MIUI 定制系统）；**核实方式**：`:app:connectedDebugAndroidTest` 首轮运行 **40 分钟零结果**（无 `TestRunner` 日志、无宿主结果 XML），主动终止；按 CI 既定口径（`build.yml:482` 将 `AutofillAuthChainDeviceTest` 登记为「系统填充 UI 呈现」环境敏感面、对模拟器以 `notClass` 排除）排除该类重跑，推进 14 分钟仍未出结果，再次主动终止。`logcat` 取证：系统对被测进程反复 `freezeUid SUCCESS`（`reason=freeze_able` / `from system`），且 `KeePasskeyAutofillService` 持续 bind/unbind（`MiuiAutofillServiceHelper: initAutofillServicePackageName`）；`ps` 确认 `am instrument` 与被测进程均存活但无进展。
- **背景**：`:app:` 层设备侧共 24 个测试文件，含自动填充认证链路、系统 UI、生物识别等**深度依赖系统服务生命周期**的用例。在 MIUI 的 UID 冻结策略下，被测进程被系统冻结且 autofill 服务持续解绑重绑，设备侧无法推进。**性质判定为环境面**（厂商系统冻结策略），非产品缺陷、非回归——`git diff --stat -- app/src/` 确认本批在该模块只改协程调度器归属（`SettingsHealthController`），其验证面是宿主单测、不依赖设备层。须如实登记而非以「设备侧无新增义务」一笔带过：本条是 §446 四层实跑中**唯一未取得读数的一层**。
- **涉及文件**：`app/src/androidTest/**`（涉事用例集待定位到具体类）。
- **验收标准**：① 在**非 MIUI 设备或 AVD** 上跑通 `:app:connectedDebugAndroidTest` 并取得读数（须含 `skipped == 0`）；② 定位并登记具体受阻用例类/方法（当前只能确认「整层无法推进」，**未能定位到单个用例**——如实登记该取证缺口）；③ 评估该层是否需拆分「依赖系统服务生命周期的用例」与「纯应用内用例」两个 `notClass` 分组，使厂商系统差异不至于**整层不可测**；④ 结论回写 `docs/architecture/已知工程限界.md` §4.1 设备侧覆盖现状。

## P3 低危问题、特性接线与体验优化（8 项）

> 本批剩余 7 条出自 [`records/软件工程质量审查记录_2026-10-05.md`](records/软件工程质量审查记录_2026-10-05.md)（五维度静态审查 + 高风险面深审；严重度 low 映射 P3；`ISSUE-P3-481` / `482` / `483` / `484` / `485` / `486` / `487` 七条已于 §444 闭环）。
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


### ISSUE-P3-489：全量单测首轮红的失败证据无保留工序（§445 立规待办，流程）

- **核实时间点**：2026-10-05；**核实方式**：复盘 §445.6 ——首轮 `.\gradlew.bat test --rerun-tasks --max-workers=1` 报 `BUILD FAILED`（2m09s，93 tasks），但截取的末 8 行输出不含肇事用例名，且 `*/build/test-results/**/*.xml` 随即被次轮 `--rerun-tasks` 全量覆盖，肇事者永久不可考；次轮起连续两轮绿。
- **背景**：「闸门存在 ≠ 闸门被执行」已由 `gate_readings.py` 收口（ISSUE-P3-305）；其下一层缺口是「闸门红了 ≠ 红在哪可查」——首轮红若为真回归，现有工序下将无任何证据留存（输出截断 + XML 覆盖双重丢失），只能记为「未定位偶发」。§428 / §416-417 的偶发红有同型取证缺口（仅凭形态归类，无当轮肇事者名单）。
- **涉及文件**：暂无（工序性条目；落点待定，如 `tools/doc/` 取证脚本或批次文档 §3 取证口径）。
- **验收标准**：
  ① 全量 `test` 非零退出时，肇事用例清单（类名 + 用例名 + 失败信息摘要）必须落盘留痕后才允许重跑覆盖；
  ② 门禁 9/9 PASS（含本条落点自身的机检，若立脚本）。

