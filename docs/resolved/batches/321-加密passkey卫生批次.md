# §321 加密 / passkey 卫生批次（`ISSUE-P3-311` 四项闭环）

> **批次性质**：加密与 passkey 卫生整改（P3 并表条目，四项全部「成立」结论）。每项均为
> fail-closed 收口或敏感面收窄，不改变任何合法输入的处理结果。

---

## 1. 整改对象

| 条目 | 判据来源 | 本批处置 |
|---|---|---|
| `ISSUE-P3-311` 加密/passkey 卫生批量登记（4 项） | 2026-09-24 同步/加密/passkey 安全审计 | **四项全部闭环**（见 §2），P3 4 → 3 |

## 2. 四项整改

### 2.1 项 1：内层随机流密钥字段缺失兜底全零、无类型化诊断

`InnerHeaderReader` 增 `streamKeySeen` 标记，`END` 字段处断言：流算法为 Salsa20 / ChaCha20
时密钥字段必须出现过，缺失即抛 `KdbxCorruptFileException`（类型化诊断），不再静默回落全零密钥
解密受保护字段。算法标识为 `None` 时密钥字段可缺省（合法形态不误拒）。
**跨实现等价对拍（AC）**：AVD Pixel_10 上 `:database:connectedDebugAndroidTest` 17/17 全绿
（0 跳过），其中含真实 KeePassXC `.kdbx` 语料端到端解锁用例——官方实现恒随流算法写出密钥字段，
新断言对其零误拒（`database/build/outputs/androidTest-results/connected/debug/TEST-Pixel_10(AVD) - 16-_database-.xml`）。
测试 4 例（`InnerHeaderStreamKeyPresenceTest`）：ChaCha20 / Salsa20 缺字段抛异常、含字段逐字节一致、
None 缺省合法。

### 2.2 项 2：v1 legacy 私钥 DER 分支钉死曲线

`PasskeyKeyCodec.parseEcPrivateKey` 的 DER 分支此前 `PrivateKeyFactory.createKey(bytes)` 后
仅以 P-256 的 `n` 判界——DER 自带域参数，非 P-256 域私钥会被错误地放进 ES256 签名运算。
现钉死曲线：非 `ECPrivateKeyParameters`（含 RSA/Ed25519 误配）或域参数非 P-256 一律
fail-closed 抛 `CryptoException.InvalidKeyException`。测试 5 例中的 2 例覆盖
（P-256 DER 照常解析 / secp256k1 DER 被拒绝）。

### 2.3 项 3：私钥物化为不可擦 String → 逐字节解析

64 字节 hex 文本分支与 DER 失败回退分支的 `BigInteger(String(bytes, UTF_8), 16)` 改为
`hexTextToBigInteger(bytes)`：逐字节校验 [0-9a-fA-F] 并拼装，中间副本 finally 清零——
私钥文本不再物化为**不可擦的 String**。严格口径下带符号 / 空白 / 奇长等宽松形态交由调用方
既有回退路径（原始标量重试）承接，合法密钥 hex 文本语义不变（测试断言与旧口径同值）。

### 2.4 项 4：`.kdbx.bak` 数据块补 fsync

`AtomicFileWriter.backupStableVersion` 在 `Files.copy` 之后、目录项 fsync（钩子①）之前补
`FileChannel.open(bak, READ).force(true)`——此前只固化目录项，数据块未同步：delayed writeback
语义下断电可能留下空洞 / 零页 `.bak`，KDoc 承诺的「可自备份恢复」落空。force 失败按
「备份不可用」如实返回 false，由主流程与降级分支既有口径承接。
**如实声明**：AC 所述「fsync / delayed-allocation 语义需 AVD 实验确认」**未执行**——
该风险窗仅在**断电**时可观测，`kill -9` / 进程重启不丢失页缓存，测试夹具无法构造该故障注入；
本批以 POSIX fsync 语义（同 SQLite / 既有 tmp 写盘基线）作为正确性依据，实验残余不阻断本项闭环。

## 3. 验证读数（原样粘贴）

```
xml=411 tests=2771 failures=0 errors=0 skipped=13
已排除非 JVM 单测 XML：{'debug': 5, 'updateDebugScreenshotTest': 1}（这些目录里的结果是**上一批遗留**，不随 `test` 重跑，不得计入本计数）
```

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/7] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=34  budget=37
[2/7] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/7] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/7] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 319 份；分册登记 321 条；全量索引 321 条；最大 §321）
[5/7] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 474 个测试文件
[6/7] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/7] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=13  unregistered_manager_util_helper_common=0
=== 汇总：7/7 PASS ===
```

> 用例计数：`tests=2762`（§320）→ **2771**（+9 = 项 1 守卫 4 例 + 项 2/3 守卫 5 例）。
> 设备层：`:database:connectedDebugAndroidTest`（AVD Pixel_10）17/17 全绿 0 跳过。

## 4. 涉及文件

生产：`database/file/InnerHeaderReader.kt`（项 1 存在性断言）、
`crypto/passkey/PasskeyKeyCodec.kt`（项 2 钉曲线 / 项 3 逐字节 hex 解析）、
`database/session/AtomicFileWriter.kt`（项 4 `.bak` 数据块 fsync）。
测试：`InnerHeaderStreamKeyPresenceTest.kt`（4 例）、`PasskeyKeyCodecCurveAndHexGuardTest.kt`（5 例）。
