# §146 · Passkey 签名内核下沉批次（ISSUE-P3-153 第二阶段 · 条目闭环）

> **批次主题**：Passkey 断言签名 ES256 / Ed25519 自 BC 纯 Java 下沉至 RustCrypto `p256` /
> `ed25519-dalek` 内核（32 字节原始私钥输入走原生；其余编码形态与 RS256 保持 BC）。
> **闭环条目**：`ISSUE-P3-153`（ChaCha20 已于 §145 闭环；本批收口 Passkey 签名；RS256 裁定不下沉）。
> **立项依据**：真机实测（记录 §2.2：ES256 sign 16.6× / Ed25519 sign 22.3×）+ 用户裁定。

---

## 1. 供应链闸门

- 新增依赖：`p256 = { version = "0.13", default-features = false, features = ["ecdsa", "alloc"] }`、
  `ed25519-dalek = { version = "2", default-features = false, features = ["alloc"] }`（均 RustCrypto 官方）；
- `cargo deny check` **全绿**（advisories / bans / licenses / sources 4 项 ok）。

## 2. 实现

- **Rust**：`src/passkey_sign.rs` 纯函数内核——`es256_sign_der`（P-256 标量 → ASN.1 DER，
  确定性 RFC 6979 + SHA-256；非法标量 d=0 / d≥n 由 `from_bytes` fail-closed 拒绝）、
  `ed25519_sign_raw`（种子 → 64 字节 raw，RFC 8032）。
- **JNI**：`NativePasskeySign_es256Sign` / `_ed25519Sign`（范式对齐既有桥）。
- **Kotlin**：[NativePasskeySign.kt](../../../crypto/src/main/java/com/keepasskey/crypto/passkey/NativePasskeySign.kt)
  ——探活 = RFC 6979 A.2.5 + RFC 8032 §7.1 TEST 1 双官方向量自测（自包含）；
  [PasskeyAssertionSigner.kt](../../../crypto/src/main/java/com/keepasskey/crypto/passkey/PasskeyAssertionSigner.kt)
  路由：`available && size == 32` 走原生，**原生返回 null 回落 BC**（保住既有的
  `InvalidKeyException` 校验语义，不引入异常类型漂移）；PKCS#8 等编码形态恒走 BC；RS256 未动。

## 3. 验证

| 层 | 内容 | 结果 |
|---|---|---|
| Rust | `src/tests/passkey_sign_tests.rs` 4 例：RFC 6979 A.2.5（SHA-256 "sample"）逐字节、RFC 8032 §7.1 TEST 1 逐字节、确定性、闸门负例 | 全绿（65 例总量） |
| 供应链 | `cargo deny check` | 4 项全 ok |
| 宿主 | `PasskeyNativeSignTest` 3 例：双官方向量复现 / ES256 原生 vs BC 逐字节一致 20 随机轮 + BC 验签 / Ed25519 同法 | 3/3 绿 |
| 宿主全量 | `test --rerun-tasks --max-workers=1` | **绿**，聚合 **`tests=2125 skipped=13 failures=0 errors=0`**（§145 基准 2122 + 3，吻合；含既有 `PasskeyCryptoEngineTest` 非法标量闸门 3 例回归通过） |
| 真机 | `PasskeyNativeDeviceTest` 2 例（Redmi 4X / arm64-v8a / Android 17）：探活硬断言 + 生产 `signAssertion` 路径复现 RFC 8032 TEST 1 | **2/2 绿、skipped=0** |

**对拍成立的根据**：两侧签名均为确定性（ES256 = RFC 6979；Ed25519 = RFC 8032），40 轮随机
(key, msg) 对拍全部逐字节一致——语义与编码零漂移。

## 4. 收益汇总

| 操作 | BC（真机实测） | Rust 内核（评估实测） | 加速比 |
|---|---|---|---|
| ES256 sign | 17.7 ms | 1.07 ms | ≈16.6× |
| Ed25519 sign | 3.8 ms | 0.17 ms | ≈22.3× |
| RS256（keygen / sign） | 993 ms / 10.9 ms | — | **裁定不下沉**（Rust 慢 1.6~2.1×） |

每次通行密钥断言（WebAuthn getAssertion）都要执行一次签名，属用户可见时延。

## 5. 过程缺陷与如实声明

- **RFC 6979 私钥误记一次**：初版用 x = CFCF…CF（混入他条向量）→ 红；下载 RFC 6979 原文
  核对 A.2.5（x = C9AFA9D8…F6721）后修正——再次验证「密码学向量必须对官方原文核对」。
- **DER 规范编码认知修正**：RustCrypto 输出 `02 21 00 <r>`（r 高位置位须补前导 0x00），
  首版手搭期望用了非法的 `02 20 <r>`；BC `BigInteger.toByteArray()` 同样补 0x00 ⇒ **生产对拍口径不受影响**。
- **异常类型漂移一次**：首版把原生 null 直接转 `CipherException`，既有非法标量闸门用例
  （期望 `InvalidKeyException`）3 例红 ⇒ 改为原生 null 回落 BC（校验语义保留）。
- 未跑 `lint` / `assembleRelease`；签名吞吐的本批前后对比为「评估探针数据 + 生产对拍」，
  未重跑真机签名计时探针（signAssertion 计时探针属评估期一次性数据，§146 后生产路径
  即评估中的 Rust 内核，耗时 ≈1 ms 量级）。

---

## 6. 批后补正：CI 原生门禁的 JNI 符号契约同步（2026-09-17）

### 6.1 必红的门禁（本批提交后才会暴露）

§145 / §146 各新增 JNI 导出（ChaCha20 ×1、Passkey ES256 / Ed25519 ×2）⇒ `.so` 的
`Java_com_keepasskey` 导出数由 **5 → 8**；而 CI `native-gate` 的
「断言原生用例零跳过 + 4 ABI 导出符号核对（ISSUE-P3-97）」步骤**硬断言恰好 5 个**。
**本地 `test` / `assembleDebug` / `connectedDebugAndroidTest` 均不会暴露该问题**——
唯有跑 CI 才会红（本批在补做构建面验证时发现）。

### 6.2 实测期望值（§73 立规：期望值必须实测而非引用）

本机 NDK 28.2.13676358 `llvm-nm --dynamic --defined-only`：**4 个 ABI 产物均为 8 个**，
且符号名跨 ABI **完全一致**（已逐名核对 arm64-v8a 与 x86）：

```text
Java_com_keepasskey_crypto_kdf_NativeArgon2_deriveKey
Java_com_keepasskey_crypto_kdf_NativeAesKdf_deriveKey
Java_com_keepasskey_crypto_cipher_NativeTwofish_cbcEncryptBlocks
Java_com_keepasskey_crypto_cipher_NativeTwofish_cbcDecryptBlocks
Java_com_keepasskey_crypto_cipher_NativeChaCha20_applyKeystream    ← §145 新增
Java_com_keepasskey_crypto_strength_NativePasswordStrength_estimate
Java_com_keepasskey_crypto_passkey_NativePasskeySign_es256Sign     ← §146 新增
Java_com_keepasskey_crypto_passkey_NativePasskeySign_ed25519Sign   ← §146 新增
```

### 6.3 处置：数量断言升级为逐名集合核对

`grep -o` 提取符号名 → python 集合比对（缺失 / 多余分别打印）。修期望值的同时**收严口径**：
堵住「数量对而符号错」这一数量断言拦不住的形态（如误删一个 `#[no_mangle]` 又误加另一个）。
`set -euo pipefail` 下 `grep` 无命中即失败（fail-closed，符号被整体剥离不会静默通过）。

### 6.4 验证与实测（含此前标注「未跑」的构建面）

| 项 | 结果 |
|---|---|
| CI 文件语法 | `yaml.safe_load` 解析通过（jobs：`fast-gate` / `native-gate` / `rust-supply-chain`） |
| 断言逻辑本机模拟 | 4 ABI 全部通过 |
| `assembleDebug` + `lint` | **绿**（3m 19s / 227 tasks） |
| `:app:assembleRelease`（R8 + lintVital） | **绿**（2m 28s / 204 tasks） |
| APK 四 ABI `.so` 入包 | debug 与 release 包**均为 4 条**；debug 尺寸 arm64-v8a 583,128 B / armeabi-v7a 451,712 B / x86 756,964 B / x86_64 687,136 B（较本批前约 **+105 KB**，来自 3 个新增 Rust 依赖） |
| **R8 保留面**（`apkanalyzer dex code`，**release 包**） | 三个新 native 方法的**类名与方法名均未被混淆 / 剥离**：`.method public final native applyKeystream([B[BJ[B)[B`、`.method public final native es256Sign([B[B)[B`、`.method public final native ed25519Sign([B[B)[B`（`mapping.txt` 亦为 `NativeChaCha20 -> NativeChaCha20`）⇒ JNI 按名查找在 release 包同样成立 |
| **完整设备侧套件**（§143~§146 全部改动的最终代码状态） | 三个模块合并 **`tests=74 skipped=0 failures=0 errors=0`**：`:crypto` 18（含 `ChaCha20NativeDeviceTest` 3、`PasskeyNativeDeviceTest` 2、`BcProviderDeviceTest` 3、`NativeArgon2InstrumentedTest` 7）+ `:database` 15（含 `RealKdbxCorpusUnlockTest` 2 = KeePassXC 官方语料端到端解锁、`SelfGeneratedRoundTripInstrumentedTest` 1 = 设备侧 KDBX 往返）+ `:app` **41**（含 `passkey.CredentialManagerBindingDeviceTest` 7 = **通行密钥 CM 通道**、`passkey.DomainMatcherAndroidRuntimeTest` 7、`data.session.DatabaseSessionAndroidRuntimeTest` 2）；三条命令各自 `BUILD SUCCESSFUL` |
| Rust 告警洁净度 | `cargo clean -p keepasskey_argon2` 后强制重建：**仅剩 1 条既有告警**（MSVC `linker_messages`：「正在创建库 …dll.lib 和对象 …dll.exp」，Windows cdylib 正常输出，与本批无关）；本批引入的 `unused import` 已消除，`cargo test` 65 例全绿 |

### 6.5 口径澄清（如实，避免误判）

`app/build/outputs/mapping/release/usage.txt` 会列出 `getAvailable()`、
`NativeTwofish.decryptBlocks()` / `encryptBlocks()` 一类成员「已移除」——那是 R8 的
**访问器内联 / 死代码消除**（死代码含本批改造后不再被调用的 `es256SignChecked` /
`ed25519SignChecked` 包装）结果，**不是** native 方法被剥离。native 方法存活以 **dex 实测**
为准（见上表最后一行），故**未**据此新增任何 `-keep` 规则。

> 该口径同时解释了为何本批改动**未**触及 `proguard-rules.pro`：AGP 默认的
> `-keepclasseswithmembernames class * { native <methods>; }` 已覆盖，实测予以确认。

### 6.6 顺带清理：本批引入的 Rust 告警

`passkey_sign.rs` 初版同时引入 `ed25519_dalek::Signer` 与 `p256::ecdsa::signature::Signer`——
**二者为同一个 trait**（`signature::Signer<S>` 被两个 crate 双重再导出），rustc 判后者为
`unused import`（该告警在 `cargoNdkBuild` 的 4 ABI 交叉编译输出中可见）。已合并为单次
`use ed25519_dalek::Signer as _;` 并加注说明同一 trait 覆盖两处 `sign` 调用；强制重建后
`cargo test` 仅剩既有的 MSVC linker 提示（与本批无关）。

---

## 归档索引行原文（无损迁移承接）

> 迁移前本批次的正文同时存在于三处：总索引行、分册级索引行、本文件。以下按「只搬迁、不改写」
> 原文照录两处索引行正文（更正须另加小节，不得就地改），自此两处索引只留一行指针。
> 承接批次：§154。

### 总索引行原文（§146）

Passkey 签名内核下沉批次（`ISSUE-P3-153` 第二阶段 · **条目闭环**）：新增 RustCrypto `p256 0.13`（ecdsa/alloc）+ `ed25519-dalek 2`（alloc）；`passkey_sign.rs` 纯函数内核（`es256_sign_der` 确定性 RFC 6979+SHA-256→规范 DER、非法标量 d=0/d≥n fail-closed；`ed25519_sign_raw` RFC 8032→64B raw）；JNI `NativePasskeySign`（探活 = RFC 6979 A.2.5 + RFC 8032 §7.1 TEST 1 双官方向量自测）；`PasskeyAssertionSigner` 路由：`available && size==32` 走原生、**原生 null 回落 BC**（保住既有 `InvalidKeyException` 校验语义，不引入异常类型漂移）、PKCS#8 形态与 RS256 恒走 BC。**供应链闸门**：`cargo deny check` 4 项全 ok。**对拍根据**：两侧签名均确定性（RFC 6979 / RFC 8032）⇒ 40 轮随机 (key,msg) 逐字节一致 + 互验签。**验证**：Rust 4 例（双官方向量/确定性/闸门）；宿主 `PasskeyNativeSignTest` 3 例；真机 `PasskeyNativeDeviceTest` 2/2 绿 skipped=0（Redmi 4X）；全量 `test --rerun-tasks` 绿聚合 **`tests=2125 skipped=13 failures=0 errors=0`**（§145 基准 2122 + 3 吻合）。**收益**：ES256 sign 17.7→~1ms（16.6×）、Ed25519 sign 3.8→~0.2ms（22.3×），每次 WebAuthn getAssertion 均受益；RS256 裁定不下沉（Rust 慢 1.6~2.1×）。**过程留痕**：① RFC 6979 私钥误记（CFCF…CF ≠ A.2.5 的 C9AFA9D8…）→ 下载 RFC 原文核对修正——**向量必须对官方原文核对**；② DER 规范编码认知修正（r 高位置位须 `02 21 00` 前导零——BC `BigInteger.toByteArray()` 同样补零，对拍口径不受影响）；③ 异常类型漂移一次（原生 null 直转 `CipherException` 使既有闸门 3 例红 → 改回落 BC）。**批后补正（批次文档 §6）**：本批新增 3 个 JNI 导出使 `.so` 符号数 **5 → 8**，而 CI `native-gate` **硬断言恰好 5 个** ⇒ **唯有跑 CI 才会红**（本地 `test`/`assembleDebug`/设备用例均不暴露）；已按 §73「期望值必须实测而非引用」实测 4 个 ABI（**均为 8 个、符号名跨 ABI 完全一致**），并将断言由「只比数量」**升级为逐名集合核对**（缺失/多余分别打印，`set -euo pipefail` 下无命中即失败 = fail-closed）；同批补跑此前标注「未跑」的构建面：`assembleDebug`+`lint` **绿**（3m19s/227 tasks）、`:app:assembleRelease`（R8 + lintVital）**绿**（2m28s/204 tasks），APK 四 ABI `.so` 入包核对通过（debug 尺寸 arm64-v8a 583,128 B / armeabi-v7a 451,712 B / x86 756,964 B / x86_64 687,136 B，较本批前约 +105 KB，来自 3 个新增 Rust 依赖），并以 `apkanalyzer dex code` 在 **release 包**实测三个新 native 方法**类名与方法名均未被混淆/剥离**（`.method public final native applyKeystream([B[BJ[B)[B` 等，JNI 按名查找成立）；`usage.txt` 中的 `getAvailable()` 一类「已移除」条目经核实为 R8 **访问器内联/死代码消除**（非 native 剥离），故**未**据此新增任何 `-keep`；并补跑**完整设备侧套件**（crypto 18 + database 15 + **app 41**，§143~§146 最终代码状态）合并 **`tests=74 skipped=0 failures=0 errors=0`**（含 `RealKdbxCorpusUnlockTest` 2 = KeePassXC 官方语料端到端解锁、`SelfGeneratedRoundTripInstrumentedTest` 1 = 设备侧 KDBX 往返、`ChaCha20NativeDeviceTest` 3、`PasskeyNativeDeviceTest` 2、**`passkey.CredentialManagerBindingDeviceTest` 7 = 通行密钥 CM 通道**等）；顺带清理本批引入的 Rust 告警（`ed25519_dalek::Signer` 与 `p256::ecdsa::signature::Signer` **为同一 trait**，重复引入被判 `unused import` ⇒ 合并为单次 `as _` 引入并加注；`cargo clean -p` 强制重建后仅剩既有 MSVC `linker_messages` 提示，与本批无关）
