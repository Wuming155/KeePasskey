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
