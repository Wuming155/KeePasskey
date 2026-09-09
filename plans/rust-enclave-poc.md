# Rust 秘密飞地 PoC 计划（Argon2 KDF 迁移）

> **文档定位**：本文件是一份 **PoC（可行性验证）计划**，目标是把 `crypto` 模块里手写的 C + JNI Argon2 内核，替换为 RustCrypto 实现的 Rust 内核，并借此解决 JVM 天生做不到的**秘密确定性清零 / 抗堆扫描**。
>
> **与 AGENTS.md 的约定偏差说明（重要）**：AGENTS.md §3.5 规定"严禁创建冗余 plan 文档，任务应在 `docs/ACTIVE_ISSUES.md` 自包含维护"。本文件是应用户显式要求、并遵循用户既定偏好（大型整改先出 `plans/` 分批计划）而创建的 **PoC 探索计划**，属于"尚未立项的新架构方向"，非冗余任务追踪文档。**一旦决定执行，每一批次都必须在 `docs/ACTIVE_ISSUES.md` 登记为对应 ISSUE**（建议 P2/P3），执行闭环仍走 ACTIVE_ISSUES → RESOLVED_LOG 的项目纪律。
>
> **闭环纪律**：每批 = 一次独立 `git commit` + `git push`；每批完成时 `./gradlew.bat test` 全绿（Rust 侧另加 `cargo test`）方可进入下一批。

> **✅ PoC 状态：已完成并归档（2026-09-09）**。Batch 0~5 全部闭环，四项成功判据（正确性 / 性能 /
> 安全增益 / 构建）均达成；完整整改记录与代码证据见
> [`docs/RESOLVED_LOG.md` §2.11](docs/RESOLVED_LOG.md)（ISSUE-P2-14）。
> 与计划的**两处受控偏差**：①Batch 4 因本机无设备/模拟器，真机 `androidTest` 改为**宿主侧 JNI 运行时验证**
> （`cargoHostBuild` + `-Djava.library.path`），真机 arm64 复测外置为 ISSUE-P3-11；②R2 触发
> `Argon2KdfEngine` 增加 AD>32B 路由 BC 的 Kotlin 守卫（对「Kotlin 零改动」的最小偏差）。

---

## 0. PoC 范围与非目标

### 0.1 范围（只做这一件事）
把 **Argon2 KDF 的原生实现** 从 `crypto/src/main/cpp/`（vendored PHC C 参考实现 + 手写 JNI 桥）迁移到 **Rust**（RustCrypto `argon2` + `zeroize` + `jni`），产出同名 `.so`，做到对 Kotlin 层**零改动的 drop-in 替换**。

### 0.2 非目标（PoC 阶段明确不碰）
- ❌ 分组加密（AES/ChaCha20/Twofish）——继续走 JCA/BouncyCastle（有 AES-NI 硬件加速，Rust 重写是负收益）。
- ❌ SHA/HMAC——继续走 JCA。
- ❌ 内层随机流加密（InnerRandomStreamCipher）——PoC 成功后再评估是否纳入飞地。
- ❌ CBOR/COSE 手写解析器——独立议题（建议换审计过的库），不混入本 PoC。
- ❌ KDBX/XML 解析、同步、合并、UI——JVM 已内存安全，无理由迁移。

### 0.3 成功判据（PoC 通过 = 同时满足）
1. **正确性**：Rust 内核对 KDBX4 全参数域（Argon2d/id × version 0x10/0x13 × 含/不含 secret+AD）的输出，与现 BouncyCastle 实现**逐字节一致**；真实 KeePass 2.61.1 / KeePassXC 生成的 `.kdbx` 能正常解锁。
2. **性能**：并行 KDF（`parallel`/rayon feature）解锁延迟相对 Batch 0 基线**无可接受阈值外的回退**。
3. **安全增益**：密码/盐/secret/AD/派生密钥缓冲在 Rust 侧经 `zeroize` 于所有路径（含错误路径）确定性擦除；C 侧手动 `malloc/free/wipe` 面被消除。
4. **构建**：`assembleDebug` + `assembleRelease`(R8) 通过，APK 含 4 ABI 的 Rust `.so`，全程从**源码构建**（保持"零二进制信任根"哲学）。

---

## 1. 现状锚点（迁移必须对齐的既有接口）

| 位置 | 内容 | 迁移约束 |
|---|---|---|
| `crypto/src/main/cpp/keepasskey_argon2_jni.c` | 手写 JNI 桥：`malloc`+`GetByteArrayRegion` 拷入，`argon2_ctx` 派生，`kp_wipe`+`free` 擦除 | 用 Rust `jni` crate 重写，**导出符号名必须完全一致** |
| JNI 符号 | `Java_com_keepasskey_crypto_kdf_NativeArgon2_deriveKey` | Rust 侧函数名/签名逐字对齐 |
| `crypto/src/main/java/.../kdf/NativeArgon2.kt` | `external fun deriveKey(password, salt, secret?, associatedData?, iterations, memoryKib, parallelism, version, type): ByteArray?`；`System.loadLibrary("keepasskey_argon2")`；懒加载探活 | **Kotlin 侧零改动**（库名、签名、null 语义全保持） |
| `crypto/src/main/java/.../kdf/Argon2KdfEngine.kt` | `NativeArgon2.available && versionSupported` 走原生，否则 `transformJvm`（BouncyCastle） | **不改**；JVM 兜底路径保留（桌面单测无 `.so` 时依赖它） |
| 参数闸门（C） | `type∈{0,2}`；`version∈{0x10,0x13}`；`iterations≥1`；`parallelism≥1`；`memoryKib≥8×parallelism`；非法返回 NULL | Rust 侧**逐条复刻**，返回 `None`→JNI null |
| `crypto/build.gradle.kts` | `externalNativeBuild.cmake` 指向 `src/main/cpp/CMakeLists.txt`；`ndkVersion=28.2.13676358` | 退役 CMake-argon2，改接 cargo-ndk（见 Batch 3） |
| `crypto/src/main/cpp/argon2/` | vendored PHC 官方 C（1814 行，CC0/Apache-2.0） | Batch 5 移除（git 受控，可回溯） |

**RustCrypto 事实（Batch 1 已核实并裁定）**：
- **版本选型**：采用 `argon2 = 0.6.0`（**非** 0.5.3）。0.5.3 **无** `parallel`/`rayon` feature（lanes 单线程计算）；0.6.0 新增 `parallel`（= `dep:rayon`）与 `kdf` feature，启用后多线程计算 lanes，**保住 p=2/p=4 多核收益**（对齐 C 侧 `threads=parallelism`），缓解 R1。实测：parallel 开启后输出与 BC 冻结向量**逐字节一致**（线程化不改变结果）。
- **依赖特性**：`default-features = false, features = ["alloc", "kdf", "parallel", "zeroize"]`——关闭 `password-hash`/`rand`，最小化供应链面。实际编译树含 rayon/crossbeam 系（R1 代价），均为 RustCrypto/主流可信 crate。
- **secret(K)**：走 `Argon2::new_with_secret(secret, alg, ver, params)` 后 `hash_password_into(pwd, salt, out)`（**注意**：无 `hash_password_into_with_secret` 这一 API，原计划表述有误，已订正）。`MAX_SECRET_LEN = 0xFFFFFFFF`，与 C/BC 任意长度一致。
- **AD(X)**：走 `ParamsBuilder::data(AssociatedData::new(ad)?)`，注入 H0 的 X 段；`keyid` **不进入 H0**（仅 PHC 字符串字段），故迁移**不得**设置 keyid。
- **R2 裁定（AD 长度上限）**：`AssociatedData::MAX_LEN = Params::MAX_DATA_LEN = 32` 字节，**0.5.3 与 0.6.0 均如此**——硬上限，无法通过公开 API 表达 > 32B 的 AD。而 C/BC 接受任意长度 AD。
  - **现实影响**：KeePass 2.61.1 / KeePassXC 生成 KDBX4 时**不设置** Argon2 KDF 的 `A`（associatedData）字段（VariantDictionary 可选、实务恒空），故真实互操作风险 ≈ 0。
  - **fail-closed 策略**：Rust `derive()` 对 AD > 32B 返回 `None`（等价 C 的非法/失败 NULL）。为避免相对 C 路径的行为回退，**Batch 3 需在 `Argon2KdfEngine` 增加一处最小 Kotlin 守卫**：`associatedData != null && size > 32` 时走 BC 兜底路径（此为对「Kotlin 零改动」目标的**受控偏差**，由 R2 触发，Batch 3 决策）。
- **H0 逐字段核对**：argon2 0.6.0 `initial_hash` 顺序 = `p‖outlen‖m‖t‖ver‖type‖len(pwd)‖pwd‖len(salt)‖salt‖len(secret)‖secret‖len(ad)‖ad`，与 Argon2 参考实现 / BC / C 桥完全一致 → AD≤32、任意 secret 场景 Rust≡BC≡C。

---

## 2. 分批实施计划

### Batch 0 — 立项、基线冻结与语料准备（文档/测试，无生产代码）
**目标**：把"迁移前的正确性与性能基准"钉死，作为后续每一批的对照。

**动作**：
1. 在 `docs/ACTIVE_ISSUES.md` 登记本 PoC 为 ISSUE（建议 P2，标题：`Argon2 原生内核 C→Rust 迁移 PoC`），自包含背景/依据/验收（引用本计划）。
2. 采集**互操作语料**放入 `crypto/src/test/resources/argon2-interop/`（或既有测试资源目录）：
   - KeePass 2.61.1 生成的 Argon2id `.kdbx`（version 0x13）；
   - KeePassXC 生成的 Argon2d `.kdbx`（version 0x10 与 0x13 各一）；
   - 若可获取：含 KDF secret / associatedData 的库（罕见，尽量造）。
3. 记录**性能基线**：用现有 `crypto/.../kdf/KdfBenchmark.kt` 在目标真机跑当前 C 原生路径，记录 t=2/m=64MB/p=2 与 p=4 的解锁派生耗时（写入本 ISSUE 备注）。
4. 冻结对照向量：新增一个桌面 JVM 单测，用 BouncyCastle `Argon2BytesGenerator` 对一组固定参数（覆盖 d/id × 0x10/0x13 × 有/无 secret+AD）派生，导出为 `argon2-bc-vectors.json` 测试资源。

**验收**：
- ISSUE 已登记；语料与 `argon2-bc-vectors.json` 入库；基线数字记录在案。
- `./gradlew.bat test` 全绿（新增向量导出测试通过）。

**提交**：`test(ISSUE-Px-xx): 冻结 Argon2 BC 对照向量与互操作语料（Rust 迁移 PoC Batch 0）`

---

### Batch 1 — Rust crate 骨架 + PHC 官方向量 + 跨实现等价（纯 Rust，暂不接 Gradle）
**目标**：在不触碰 Android 构建链的前提下，证明 RustCrypto `argon2` 与官方/BC 输出逐字节一致。

**动作**：
1. 新建 `crypto/src/main/rust/`（cargo 工程）：
   - `Cargo.toml`：`crate-type = ["cdylib", "rlib"]`，`name = "keepasskey_argon2"`；依赖 `argon2`（启用 `parallel` feature → rayon）、`zeroize`、`jni`（Batch 2 用）；`edition = "2021"`。
   - `src/lib.rs`：暴露纯函数 `derive(password, salt, secret, ad, t, m_kib, p, version, type) -> Option<[u8;32]>`，内部参数闸门复刻 C 逻辑。
2. Rust 单测 `#[cfg(test)]`：
   - **PHC 官方 argon2 测试向量**（argon2id/d，0x10/0x13）；
   - 读取 Batch 0 的 `argon2-bc-vectors.json`，断言 Rust 输出 == BC 输出（含 secret+AD 组合）。
3. 验证 **AD 长度上限**（R2）：确认 RustCrypto `Params.data()` 接受 KDBX4 语料中的 AD 长度；若被限制在 32B 而语料更长，记录并评估影响。

**验收**：
- `cargo test` 全绿；PHC 向量 + BC 等价全部通过；AD 长度结论明确记录。
- Kotlin 侧无任何改动，`./gradlew.bat test` 仍全绿。

**提交**：`feat(ISSUE-Px-xx): Rust Argon2 内核骨架 + PHC/BC 跨实现等价测试（PoC Batch 1）`

---

### Batch 2 — JNI 边界（jni crate）+ zeroize 确定性擦除
**目标**：产出与 C 桥**符号与语义完全一致**的 Rust JNI 导出，并把秘密擦除做成"编译期/RAII 保证"。

**动作**：
1. `src/jni_bridge.rs` 实现 `#[no_mangle] pub extern "system" fn Java_com_keepasskey_crypto_kdf_NativeArgon2_deriveKey(...)`：
   - 参数类型与 C 逐一对齐（`jbyteArray password/salt/secret/associatedData`，`jint iterations/memoryKib/parallelism/version/type`）；
   - null/越界闸门复刻；非法或分配失败 → 返回 `std::ptr::null_mut()`（对应 Kotlin `null`）。
2. **秘密擦除**：password/salt/secret/ad 拷入 `Zeroizing<Vec<u8>>`；派生输出用 `Zeroizing<[u8;32]>`，`SetByteArrayRegion` 后立即归零；错误路径经 RAII 自动擦除（消除 C 侧"忘记 wipe"隐患）。
3. 主机侧可测性：`cargo build` 产出宿主 `cdylib`，新增一个 JVM 测试（或 Rust 集成测试）加载并调用导出符号，断言与 Batch 1 纯函数结果一致（若宿主 JNI 测试成本高，则本批仅静态断言符号名，运行时验证放 Batch 4）。

**验收**：
- 导出符号名与签名与既有 C 完全一致（用 `nm`/`objdump` 核对）；
- `cargo test` 全绿；秘密缓冲全路径 zeroize（代码评审 + 若有 valgrind/ASAN 则跑一遍）。

**提交**：`feat(ISSUE-Px-xx): Rust JNI 桥 + zeroize 秘密确定性擦除（PoC Batch 2）`

---

### Batch 3 — 构建链接入（cargo-ndk + Gradle），退役 CMake-argon2
**目标**：让 Gradle 从**源码**交叉编译 Rust，产出 4 ABI `.so`，`System.loadLibrary("keepasskey_argon2")` 无感切换。

**动作**：
1. 工具链：CI/本地安装 Rust + `cargo-ndk` + 4 个 target（`aarch64-linux-android` `armv7-linux-androideabi` `x86_64-linux-android` `i686-linux-android`）+ 既有 NDK 28.2.13676358。
2. Gradle 接入（`crypto/build.gradle.kts`）：
   - 新增 task `cargoNdkBuild`：`cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -t x86 -o <buildDir>/rust/jniLibs build --release`；
   - `android.sourceSets["main"].jniLibs.srcDirs += "<buildDir>/rust/jniLibs"`；
   - 让 `preBuild`/`merge*JniLibFolders` 依赖 `cargoNdkBuild`；
   - **移除** `externalNativeBuild.cmake`（argon2 部分）。
3. 保留 Kotlin `NativeArgon2` 与 `Argon2KdfEngine` 不动（库名一致，drop-in）。
4. 供应链：新增 `cargo deny`（`deny.toml`）校验 argon2/zeroize/jni/rayon 许可证与 advisory；Rust 源码全量入库（对齐"零二进制信任根"）。

**验收**：
- `./gradlew.bat assembleDebug` 与 `assembleRelease` 通过；APK `lib/<abi>/libkeepasskey_argon2.so` 为 Rust 产物（`strings`/`readelf` 佐证）；
- 记录 APK 体积增量（Rust vs C）；
- `cargo deny check` 通过；桌面 `./gradlew.bat test` 全绿（走 BC 兜底，未受影响）。

**提交**：`build(ISSUE-Px-xx): 接入 cargo-ndk 交叉编译 Rust Argon2，退役 CMake C 内核（PoC Batch 3）`

---

### Batch 4 — 设备/模拟器互操作 + 性能回归（决策闸门）
**目标**：在真机/模拟器验证 Rust 原生路径端到端正确且性能可接受。

**动作**：
1. `crypto/src/androidTest/`（或 app 层）新增 instrumented 测试：加载 `.so`，用 Batch 0 语料 `.kdbx` 端到端解锁（Argon2d/id × 0x10/0x13），断言成功且派生密钥与 BC 一致。
2. 性能回归：在 Batch 0 同一真机跑 `KdfBenchmark`，对比 C 基线；重点看 `parallel`(rayon) 是否保住 p=2/p=4 的多核收益。
3. **决策闸门**：
   - 若解锁延迟回退在预算内（建议 ≤10%，或绝对值仍 < 用户可感知阈值）→ 通过，进入 Batch 5；
   - 若回退显著 → 记录数据，评估选项（调 rayon 线程数绑核 / 保留 C 仅并行段 / 暂缓迁移），**不强行推进**。

**验收**：
- 互操作语料 100% 解锁通过；
- 性能对照数据入档，决策闸门有明确结论。

**提交**：`test(ISSUE-Px-xx): Rust Argon2 设备端互操作与性能回归验证（PoC Batch 4）`

---

### Batch 5 — 清理、文档流转、基线更新（收尾）
**目标**：移除 C 遗留，更新项目文档与基线，闭环归档。

**动作**：
1. `git rm` vendored C：`crypto/src/main/cpp/argon2/` 与 `keepasskey_argon2_jni.c`（git 受控，豁免备份铁律，可回溯）。
2. 更新 `AGENTS.md`：
   - §1 基线：测试用例数（新增 Rust/androidTest 计数）、原生内核描述改为 Rust；
   - §3 硬约束：敏感数据铁律补充"KDF 秘密在 Rust 侧 zeroize 强制擦除"；
   - §5 构建命令：新增 Rust 工具链前置（`rustup` target + `cargo-ndk` + `cargo deny`）；
   - §6 已知限界：更新"ProtectedString/堆扫描"条——原生 KDF 秘密现已确定性擦除；补充 rayon 并行说明。
3. 将 ISSUE 从 `ACTIVE_ISSUES.md` **剪切**移入 `RESOLVED_LOG.md`，附代码证据（Rust 源、等价测试、性能数据）。
4. PoC 复盘：明确下一步（是否把内层流加密 / CBOR-COSE 纳入飞地），另立新 ISSUE。

**验收**：
- 全模块 `./gradlew.bat test` + `cargo test` 全绿；`assembleDebug`/`assembleRelease` 通过；
- 文档与代码**同一次提交**并 `git push`。

**提交**：`refactor(ISSUE-Px-xx): 移除 C Argon2 遗留，文档流转归档（PoC Batch 5）`

---

## 3. 风险登记册

| 编号 | 风险 | 影响 | 缓解 |
|---|---|---|---|
| **R1** | 并行性能回退（C 现用 `threads=parallelism` 真多核） | 解锁变慢，用户可感知 | **Batch 1 缓解**：改用 `argon2 0.6.0` + `parallel`(rayon) feature（0.5.3 无此能力），lanes 多线程计算，输出经 BC 向量验证逐字节一致；Batch 4 真机设决策闸门，超预算不强行推进 |
| **R2** | RustCrypto `AssociatedData` 长度上限可能 < KDBX4 语料 | 含长 AD 的库无法解锁 | **Batch 1 裁定**：上限恒为 **32B**（0.5.3/0.6.0 同），C/BC 无此限。真实 KeePass/KeePassXC 不设 `A` 字段→风险≈0；Rust `derive()` 对 AD>32 fail-closed 返回 None；Batch 3 在 `Argon2KdfEngine` 加最小守卫将 AD>32 路由 BC 兜底（受控偏差） |
| **R3** | secret/AD 罕见路径与 KeePass 官方语义偏差 | 少数库互操作失败 | Batch 1 用 BC 等价向量 + Batch 4 真机语料双重覆盖 |
| **R4** | "零二进制信任根"哲学 vs 引入 Rust 依赖树 | 供应链信任面扩大 | Rust 源码 + 依赖全量入库；`cargo deny` 锁许可证/advisory；可复现构建 |
| **R5** | cargo-ndk 在 CI/Windows 构建链不稳定 | 构建失败 | 优先 Git Bash（项目已用 `MSYS_NO_PATHCONV=1 ./gradlew.bat`）；CI 固定 Rust/NDK 版本并缓存 |
| **R6** | 桌面单测无 `.so`，原生路径不被 584 单测覆盖 | 回归漏网 | 跨实现等价用 BC 向量在桌面覆盖；原生运行时验证放 androidTest（Batch 4） |

---

## 4. 关键设计取舍（一句话结论）

- **边界最窄化**：只暴露 `deriveKey` 一个符号，其余逻辑留 Kotlin/JVM——FFI 面越小，风险越低。
- **drop-in 替换**：库名 + JNI 符号 + Kotlin 签名全不动，把"迁移"降维成"换 `.so` 实现"，可回退（切回 CMake C 内核即恢复）。
- **真正的收益点**：不是"Rust 比 Kotlin 安全"（JVM 已内存安全），而是 **C 手动内存管理面被消除 + 秘密确定性 zeroize**——这正是 AGENTS.md §6 承认的"抗堆扫描/崩溃转储"短板的原生解法。
- **PoC 先证明再扩张**：Argon2 一条路径跑通构建链 + 互操作 + 性能 + zeroize 后，再决定是否把内层流 / CBOR-COSE 纳入飞地，避免一次性大改。
