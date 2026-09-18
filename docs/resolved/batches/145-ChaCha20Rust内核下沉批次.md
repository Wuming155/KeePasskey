# §145 · ChaCha20 Rust 内核下沉批次（ISSUE-P3-153 第一阶段）

> **批次主题**：ChaCha20（RFC 8439 / KDBX 可选外层 cipher）自 BC 纯 Java 下沉至 RustCrypto `chacha20`
> Rust 内核（原生优先 + BC 回退双路径）。
> **闭环范围**：`ISSUE-P3-153` 的 ChaCha20 候选；ES256 / Ed25519 签名内核为后续批次（条目保持开放）。
> **立项依据**：真机实测（[`records/真机吞吐实测记录_2026-09-17.md`](../../records/真机吞吐实测记录_2026-09-17.md) §2.1
> BC 2.6~2.7 MB/s vs Rust 候选 ≈118 MB/s，≈44×）+ 用户 2026-09-17 立项裁定。

---

## 1. 供应链闸门（条目风险提示的前置义务）

- 新增依赖：`chacha20 = { version = "0.9.1", default-features = false, features = ["zeroize"] }`
  （RustCrypto 官方 crate，与既有 aes / twofish / sha2 同族同线）；
- `cargo deny check`（0.20.2）**全绿**：`advisories ok, bans ok, licenses ok, sources ok`
  —— `wildcards = "deny"` 下零违例、依赖树无重复密码学实现、RUSTSEC 公告无命中；
- `default-features = false` + `zeroize`：密钥调度随对象析构归零，与既有内核同纪律。

## 2. 实现

### Rust 侧

- 新增 `src/chacha20_stream.rs`：**纯函数无状态内核** `apply_keystream_at(key, nonce, byte_offset, data)`
  ——密钥流是 (key, nonce, 偏移) 的纯函数，按字节偏移定位（crate `StreamCipherSeek` 契约即
  「position in bytes」，已实证），**无需跨 JNI 持有流对象生命周期**；任意字节偏移的块内部分
  由 wrapper 内部缓冲消化，调用方无需对齐。
- 参数闸门：key=32 / nonce=12 长度、`byte_offset + data.len()` 不越过密钥流上界
  `2^32 × 64` 字节（RFC 8439 u32 块计数器，checked 防溢出）——违例 fail-closed 返回 `None`。
- JNI 导出 `Java_com_keepasskey_crypto_cipher_NativeChaCha20_applyKeystream`
  （`jni_bridge_ext.rs`，范式逐条对齐既有桥：有符号闸门先行 / `Zeroizing` 全路径 / panic 归一为 null）。

### Kotlin 侧

- 新增 [NativeChaCha20.kt](../../../crypto/src/main/java/com/keepasskey/crypto/cipher/NativeChaCha20.kt)：
  `external fun applyKeystream(...)` + `applyKeystreamChecked`（null → `CryptoException` fail-closed）；
  探活 = **RFC 8439 §2.4.2 官方向量自测**（自包含，不依赖 BC 对照）。
- [ChaCha20CipherEngine.kt](../../../crypto/src/main/java/com/keepasskey/crypto/cipher/ChaCha20CipherEngine.kt)
  改双路径分派（对齐 TwofishCipherEngine 先例）：
  - 整块 encrypt / decrypt：`NativeChaCha20.available` 时走原生，否则 BC `doFinal`（兜底语义逐字节一致）；
  - 流：新增引擎私有的无填充分块流 `NativeEncryptingOutputStream` / `NativeDecryptingInputStream`
    （64 KiB 实例级复用缓冲、按字节偏移推进、ChaCha20 无填充故无收尾校验语义；
    缓冲内明文 close 时清零——**不自作主张清零调用方的 key/nonce 数组**，对齐 CbcStreams 先例）；
    原生不可用时回退 `CipherInputStream/OutputStream`（§143 解耦后的 BC 持有实例）。

## 3. 验证

| 层 | 内容 | 结果 |
|---|---|---|
| Rust 内核 | `cargo test`（新增 `src/tests/chacha20_stream_tests.rs` 4 例：RFC 8439 §2.4.2 官方向量、任意字节粒度分块=整段、非零偏移链式、参数闸门负例） | 全绿（61 例总量） |
| 供应链 | `cargo deny check` | 4 项全 ok |
| 宿主 | `ChaCha20NativeEngineTest` 3 例：整块对 BC 逐字节一致（16 长度）/ 流对 BC 流逐字节一致 + 往返 / RFC 官方向量复现 | 3/3 绿 |
| 宿主全量 | `test --rerun-tasks --max-workers=1` | **绿**，聚合 **`tests=2122 skipped=13 failures=0 errors=0`**（§144 基准 2119 + 3，吻合） |
| 真机 | `ChaCha20NativeDeviceTest` 3 例（Redmi 4X / arm64-v8a / Android 17）：探活硬断言 / RFC 官方向量复现 / 整块+流路径往返 | **3/3 绿、skipped=0** |

## 4. 真机吞吐（采纳后，同机同探针）

| 路径 | median | 吞吐 | vs BC 基线 2.7 MB/s |
|---|---|---|---|
| 生产引擎 · 整块 encrypt（10 MiB） | 150.6 ms | **66.4 MB/s** | **≈24×** |
| 生产引擎 · 流 encrypt（10 MiB） | 184.7 ms | **54.1 MB/s** | **≈20×** |
| BC 一次性 doFinal（对照，未变） | 3705.8 ms | 2.7 MB/s | — |

**如实声明**：生产路径 54~66 MB/s 低于评估探针的裸 Rust 二进制 118 MB/s（≈44×）——差距来自
JNI 边界的两次数组拷贝（`convert_byte_array` 入参拷贝 + `SetByteArrayRegion` 出参拷贝，安全范式
的固有代价）与 Kotlin 流包装的中转拷贝；本批未做 JNI 零拷贝（`GetPrimitiveArrayCritical` 类
原地写回）优化——那是独立的高风险面改动，不在本批范围。

## 5. 过程缺陷与如实声明

- **seek 粒度误判一次**：初版把 `StreamCipherSeek.seek` 误按「64 字节块」传入（实为字节，
  cipher 0.5.2 `stream.rs` §StreamCipherSeek 明文契约），官方向量 + 分块等价用例双双红；
  实证修正为直接传字节偏移后全绿（用例先行抓住设计错误，正是测试的意义）。
- **官方向量凭记忆拼接一次**：RFC 8439 §2.4.2 期望密文首版抄写截断（130B ≠ 114B）→ 红；
  改以 chacha20 crate 内嵌的 RFC 8439 向量逐字对照后绿。教训：**密码学向量不得手打**。
- Kotlin 侧两次字符串拼接 + `hexToByteArray()` 优先级错误（编译期抓获，未入库）。
- ES256 / Ed25519 签名内核（`ISSUE-P3-153` 第二阶段）**未在本批**；RS256 已被裁定否定下沉。
- 未跑 `lint` / `assembleRelease`；ChaCha20 为可选 cipher，默认 AES 路径零改动。

---

## 归档索引行原文（无损迁移承接）

> 迁移前本批次的正文同时存在于三处：总索引行、分册级索引行、本文件。以下按「只搬迁、不改写」
> 原文照录两处索引行正文（更正须另加小节，不得就地改），自此两处索引只留一行指针。
> 承接批次：§154。

### 总索引行原文（§145）

ChaCha20 Rust 内核下沉批次（`ISSUE-P3-153` 第一阶段，用户 2026-09-17 立项）：新增 RustCrypto `chacha20 0.9.1`（default-features=false + zeroize，与 aes/twofish 同纪律）；**纯函数无状态内核** `chacha20_stream::apply_keystream_at`（密钥流按字节偏移定位——实证 `StreamCipherSeek` 契约即「position in bytes」，无需跨 JNI 持有流对象；闸门含 RFC 8439 u32 块计数器上界 2^32×64 字节 fail-closed）；JNI 导出 `NativeChaCha20.applyKeystream`（范式对齐既有桥）；`ChaCha20CipherEngine` 改**原生优先 + BC 回退**双路径（对齐 TwofishCipherEngine 先例；新增 ChaCha20 专用无填充分块流，64KiB 复用缓冲 + 明文清零，不自作主张清零调用方 key/nonce）。**供应链闸门**：`cargo deny check` 4 项全 ok（wildcards=deny 零违例 / 无重复密码学实现 / RUSTSEC 无命中）。**真机吞吐（同机同探针）**：生产路径整块 66.4 MB/s / 流 54.1 MB/s（BC 2.7 MB/s ⇒ **≈20~24×**）；低于评估裸二进制 118 MB/s 的差距 = JNI 两次数组拷贝（安全范式固有代价）+ Kotlin 流中转，JNI 零拷贝优化**不在本批**。**验证**：Rust 4 例（RFC 8439 §2.4.2 官方向量 / 任意字节粒度=整段 / 非零偏移链式 / 闸门负例）；宿主 `ChaCha20NativeEngineTest` 3 例（对 BC 逐字节对拍 + 官方向量）；真机 `ChaCha20NativeDeviceTest` 3/3 绿 skipped=0；全量 `test --rerun-tasks` 绿聚合 **`tests=2122 skipped=13 failures=0 errors=0`**（§144 基准 2119 + 3 吻合）。**过程留痕**：① seek 粒度误判一次（块 vs 字节——用例先行抓住，实证 cipher 0.5.2 契约后修正）；② 官方向量凭记忆拼接截断一次（130B≠114B）→ 改以 crate 内嵌 RFC 8439 向量逐字对照——**密码学向量不得手打**；③ Kotlin 字符串拼接+`hexToByteArray()` 优先级错误两次（编译期抓获）。ES256/Ed25519 签名内核为后续批次，RS256 裁定否定下沉
