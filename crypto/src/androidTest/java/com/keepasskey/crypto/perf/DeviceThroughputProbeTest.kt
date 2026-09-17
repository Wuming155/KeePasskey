package com.keepasskey.crypto.perf

import com.keepasskey.core.model.PasskeyData
import com.keepasskey.crypto.cipher.AesCipherEngine
import com.keepasskey.crypto.cipher.ChaCha20CipherEngine
import com.keepasskey.crypto.cipher.NativeAes
import com.keepasskey.crypto.cipher.NativeChaCha20
import com.keepasskey.crypto.passkey.PasskeyCryptoEngine
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import org.bouncycastle.crypto.signers.RSADigestSigner
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 真机吞吐探针（ISSUE-P3-153 / ISSUE-P3-155 的**评估测量**，非回归闸门用例）。
 *
 * 登记口径（须与 docs/records/ 的实测记录配套阅读）：
 * - **P3-155**：AES-256-CBC 外层流「现状」（生产 [AesCipherEngine] 流包装，
 *   `CipherInputStream`/`CipherOutputStream` 内部 512 B 缓冲）vs「候选」（64 KiB 分块直投
 *   `Cipher.update`，即拟复用 `CbcStreams` 骨架后的等价投递粒度）在**同一设备、同一语料**
 *   下的加解密吞吐对比，并断言两条路径字节级一致（现状 vs 候选密文相等 + 往返明文相等）。
 * - **P3-153（BC 基线半边）**：BC `ChaCha7539` 整库流吞吐与 Passkey 三算法（ES256/Ed25519/RS256）
 *   的密钥生成 / 签名单次耗时。Rust 候选半边由独立基准二进制另行实测，方法学差异在记录文档声明。
 *
 * 方法学（对齐 `NativeArgon2InstrumentedTest` 先例）：先预热、再多轮采样，报中位数 + 最小/最大
 * （离散度）。本用例**只做正确性断言 + 数据输出**（输出前缀 `PERF-PROBE|`，经 logcat 落盘于
 * `crypto/build/outputs/androidTest-results/connected/debug/<设备名>/logcat-*.txt`），
 * 不设性能阈值断言——评估数据由记录文档裁定。
 */
class DeviceThroughputProbeTest {

    private companion object {
        const val PREFIX = "PERF-PROBE|"
        val RANDOM = SecureRandom()

        const val PAYLOAD_BYTES = 10 * 1024 * 1024 // 10 MiB（典型 .kdbx 载荷量级）
        const val CHUNK_64K = 64 * 1024
        const val WARMUP = 1
        const val SAMPLES = 5
        const val OPS_PER_SAMPLE = 50          // ES256/Ed25519 每采样操作数
        const val RSA_KEYGEN_SAMPLES = 5       // RSA-2048 keygen 单次已达数百 ms，仅 5 样本
        const val RSA_SIGN_OPS_PER_SAMPLE = 20
    }

    // ================= P3-155：AES-256-CBC 现状 512B vs 候选 64KiB =================

    @Test
    fun probeAesCbc_现状512B流_vs_候选64KiB分块() {
        val payload = ByteArray(PAYLOAD_BYTES).also { RANDOM.nextBytes(it) }
        val key = ByteArray(32).also { RANDOM.nextBytes(it) }
        val iv = ByteArray(16).also { RANDOM.nextBytes(it) }
        val engine = AesCipherEngine()

        // ---- 正确性前置：两条路径字节级一致（这也是 AC「往返字节级一致」的设备侧先证）----
        val cipherBefore = encryptViaProductionStream(engine, payload, key, iv)
        val cipherAfter = encryptViaChunkedUpdate(payload, key, iv)
        assertTrue(
            "现状流与候选分块路径的密文必须逐字节一致",
            Arrays.equals(cipherBefore, cipherAfter)
        )
        val plainBefore = decryptViaProductionStream(engine, cipherBefore, key, iv)
        val plainAfter = decryptViaChunkedUpdate(cipherAfter, key, iv)
        assertTrue("现状流解密往返必须逐字节还原明文", Arrays.equals(plainBefore, payload))
        assertTrue("候选分块路径解密往返必须逐字节还原明文", Arrays.equals(plainAfter, payload))
        Arrays.fill(plainBefore, 0); Arrays.fill(plainAfter, 0)

        // ---- AES：**生产路径保持平台 JCE**（§147 评估：Rust 下沉因 JNI 边界代价实测倒退，
        //      已回退，见 `architecture/已知工程限界.md` §17）。此处保留前后对照基线，供重估时复用。
        measure("AES-CBC加密", "生产-CipherOutputStream") { encryptViaProductionStream(engine, payload, key, iv) }
        measure("AES-CBC加密", "参照-JCE一次性doFinal") {
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            c.doFinal(payload)
        }
        measure("AES-CBC加密", "参照-JCE 64KiB分块update") { encryptViaChunkedUpdate(payload, key, iv) }
        measure("AES-CBC解密", "生产-CbcDecryptingInputStream(64KiB)") { decryptViaProductionStream(engine, cipherBefore, key, iv) }
        measure("AES-CBC解密", "参照-JCE 64KiB分块update") { decryptViaChunkedUpdate(cipherBefore, key, iv) }

        Arrays.fill(cipherBefore, 0); Arrays.fill(cipherAfter, 0)
    }

    /** 生产路径的流包装（§147 起：原生可用时为 `CbcEncryptingOutputStream` + 原生变换）。 */
    private fun encryptViaProductionStream(
        engine: AesCipherEngine,
        payload: ByteArray,
        key: ByteArray,
        iv: ByteArray
    ): ByteArray {
        val sink = ByteArrayOutputStream(payload.size + 32)
        engine.createEncryptingStream(sink, key, iv).use { it.write(payload) }
        return sink.toByteArray()
    }

    private fun decryptViaProductionStream(
        engine: AesCipherEngine,
        cipherBytes: ByteArray,
        key: ByteArray,
        iv: ByteArray
    ): ByteArray {
        val out = ByteArrayOutputStream(cipherBytes.size)
        val source = ByteArrayInputStream(cipherBytes)
        engine.createDecryptingStream(source, key, iv).use { stream ->
            val buf = ByteArray(CHUNK_64K)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            Arrays.fill(buf, 0)
        }
        return out.toByteArray()
    }

    /** 候选路径：64 KiB 分块直投 `Cipher.update`，收尾 `doFinal`（拟复用 CbcStreams 骨架的等价投递粒度）。 */
    private fun encryptViaChunkedUpdate(payload: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val out = ByteArrayOutputStream(payload.size + 32)
        var offset = 0
        while (offset + CHUNK_64K <= payload.size) {
            out.write(cipher.update(payload, offset, CHUNK_64K))
            offset += CHUNK_64K
        }
        if (offset < payload.size) out.write(cipher.update(payload, offset, payload.size - offset))
        out.write(cipher.doFinal())
        return out.toByteArray()
    }

    private fun decryptViaChunkedUpdate(cipherBytes: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val out = ByteArrayOutputStream(cipherBytes.size)
        var offset = 0
        while (offset + CHUNK_64K <= cipherBytes.size) {
            cipher.update(cipherBytes, offset, CHUNK_64K)?.let { out.write(it) }
            offset += CHUNK_64K
        }
        if (offset < cipherBytes.size) cipher.update(cipherBytes, offset, cipherBytes.size - offset)?.let { out.write(it) }
        out.write(cipher.doFinal())
        return out.toByteArray()
    }

    // ================= P3-153（BC 基线半边）：ChaCha20 与 Passkey 三算法 =================

    @Test
    fun probeChaCha20_BC基线吞吐() {
        val payload = ByteArray(PAYLOAD_BYTES).also { RANDOM.nextBytes(it) }
        val key = ByteArray(32).also { RANDOM.nextBytes(it) }
        val nonce = ByteArray(12).also { RANDOM.nextBytes(it) }

        fun newCipher(mode: Int): Cipher {
            // 注意：真机上 `Security.getProvider("BC")` 是平台剥离版 BC（无 ChaCha7539），
            // 实测 `Provider BC does not provide ChaCha7539`（2026-09-17，Redmi 4X）。
            // 此处**直接传入完整 BouncyCastleProvider 实例**以测得完整 BC 的真实吞吐；
            // 生产 `ChaCha20CipherEngine.bouncyCastleProvider()` 的同名抢占缺陷 ISSUE-P2-92
            // （已于 §143 闭环：生产侧改为持有完整 BC 实例，与注册表解耦）。
            val c = Cipher.getInstance("ChaCha7539", org.bouncycastle.jce.provider.BouncyCastleProvider())
            c.init(mode, SecretKeySpec(key, "ChaCha7539"), IvParameterSpec(nonce))
            return c
        }

        // 正确性前置
        val encrypted = newCipher(Cipher.ENCRYPT_MODE).doFinal(payload)
        assertTrue("ChaCha20 往返必须还原明文", Arrays.equals(newCipher(Cipher.DECRYPT_MODE).doFinal(encrypted), payload))

        // 生产引擎路径（§145 后为原生内核；若原生不可用则与 BC 回退等价）
        val chachaEngine = ChaCha20CipherEngine()
        measure("ChaCha20(生产引擎)", "整块encrypt(10MiB)") { chachaEngine.encrypt(key, nonce, payload) }
        measure("ChaCha20(生产引擎)", "流encrypt(10MiB)") {
            val sink = ByteArrayOutputStream(payload.size)
            chachaEngine.createEncryptingStream(sink, key, nonce).use { it.write(payload) }
            sink.toByteArray()
        }

        val bulk = measure("ChaCha20(BC)加密", "一次性doFinal(10MiB)") { newCipher(Cipher.ENCRYPT_MODE).doFinal(payload) }
        val chunked = measure("ChaCha20(BC)加密", "64KiB分块update") {
            val c = newCipher(Cipher.ENCRYPT_MODE)
            val out = ByteArrayOutputStream(payload.size)
            var offset = 0
            while (offset + CHUNK_64K <= payload.size) {
                out.write(c.update(payload, offset, CHUNK_64K))
                offset += CHUNK_64K
            }
            out.write(c.doFinal())
            out.toByteArray()
        }
        Arrays.fill(encrypted, 0)
        reportComparison("ChaCha20(BC)加密", PAYLOAD_BYTES, bulk, chunked, null)
    }

    @Test
    fun probePasskey_三算法密钥生成与签名耗时() {
        val ecParams: X9ECParameters = SECNamedCurves.getByName("secp256r1")
        val domain = ECDomainParameters(ecParams.curve, ecParams.g, ecParams.n, ecParams.h)
        val dataToSign = ByteArray(64).also { RANDOM.nextBytes(it) } // authenticatorData||clientDataHash 量级

        // ---- ES256 ----
        val esPriv = run {
            val gen = ECKeyPairGenerator()
            gen.init(ECKeyGenerationParameters(domain, RANDOM))
            (gen.generateKeyPair().private as ECPrivateKeyParameters)
        }
        val esKeyBytes = scalarTo32Bytes(esPriv.d)
        val esGen = measurePerOp("ES256", "keygen", WARMUP, SAMPLES, OPS_PER_SAMPLE) {
            val gen = ECKeyPairGenerator()
            gen.init(ECKeyGenerationParameters(domain, RANDOM))
            gen.generateKeyPair()
        }
        val esSign = measurePerOp("ES256", "sign(生产signAssertion路径)", WARMUP, SAMPLES, OPS_PER_SAMPLE) {
            PasskeyCryptoEngine.signAssertion(PasskeyData.ALGORITHM_ES256, esKeyBytes, dataToSign)
        }

        // ---- Ed25519 ----
        val edPriv = run {
            val gen = Ed25519KeyPairGenerator()
            gen.init(Ed25519KeyGenerationParameters(RANDOM))
            (gen.generateKeyPair().private as Ed25519PrivateKeyParameters)
        }
        val edKeyBytes = edPriv.encoded
        val edGen = measurePerOp("Ed25519", "keygen", WARMUP, SAMPLES, OPS_PER_SAMPLE) {
            val gen = Ed25519KeyPairGenerator()
            gen.init(Ed25519KeyGenerationParameters(RANDOM))
            gen.generateKeyPair()
        }
        val edSign = measurePerOp("Ed25519", "sign(生产signAssertion路径)", WARMUP, SAMPLES, OPS_PER_SAMPLE) {
            PasskeyCryptoEngine.signAssertion(PasskeyData.ALGORITHM_ED25519, edKeyBytes, dataToSign)
        }

        // ---- RS256（2048 位，certainty=80 与生产一致）----
        val rsaPriv = run {
            val gen = RSAKeyPairGenerator()
            gen.init(RSAKeyGenerationParameters(BigInteger.valueOf(65537), RANDOM, 2048, 80))
            (gen.generateKeyPair().private as RSAPrivateCrtKeyParameters)
        }
        val rsaKeyBytes = PrivateKeyInfoFactory.createPrivateKeyInfo(rsaPriv).encoded
        val rsaGen = measurePerOp("RS256", "keygen(2048,certainty=80)", 1, RSA_KEYGEN_SAMPLES, 1) {
            val gen = RSAKeyPairGenerator()
            gen.init(RSAKeyGenerationParameters(BigInteger.valueOf(65537), RANDOM, 2048, 80))
            gen.generateKeyPair()
        }
        val rsaSign = measurePerOp("RS256", "sign(生产signAssertion路径)", 1, SAMPLES, RSA_SIGN_OPS_PER_SAMPLE) {
            PasskeyCryptoEngine.signAssertion(PasskeyData.ALGORITHM_RS256, rsaKeyBytes, dataToSign)
        }
        // 口径界定（2026-09-17 追问「是否考虑 JNI 开销」）：Rust 侧探针是「解析一次 key、复用它
        // 反复签名」，而生产 `signRs256` 每次调用都 `PrivateKeyFactory.createKey(der)`。此变体剔除
        // 解析开销，用于判定「BC 10.9ms 里有多少是解析」以及该不对称的影响方向。
        val rsaParsedKey = PrivateKeyFactory.createKey(rsaKeyBytes)
        val rsaSignPreParsed = measurePerOp("RS256", "sign(BC，key解析一次)", 1, SAMPLES, RSA_SIGN_OPS_PER_SAMPLE) {
            val signer = RSADigestSigner(SHA256Digest())
            signer.init(true, rsaParsedKey)
            signer.update(dataToSign, 0, dataToSign.size)
            signer.generateSignature()
        }

        Arrays.fill(esKeyBytes, 0)
        Arrays.fill(edKeyBytes, 0)
        Arrays.fill(rsaKeyBytes, 0)
        Arrays.fill(dataToSign, 0)

        reportPerOp("ES256", "keygen", esGen)
        reportPerOp("ES256", "sign", esSign)
        reportPerOp("Ed25519", "keygen", edGen)
        reportPerOp("Ed25519", "sign", edSign)
        reportPerOp("RS256", "keygen", rsaGen)
        reportPerOp("RS256", "sign(生产路径，含每次解析)", rsaSign)
        reportPerOp("RS256", "sign(BC，解析一次)", rsaSignPreParsed)
    }

    /**
     * AES-256-CBC 生产形态对照 · **连续 10 轮**（2026-09-17：单轮/单次不作结论）。
     *
     * 每轮对 6 个变体各取 5 次采样中位，按轮打印**一行**（便于外部逐轮汇总与看离散度）：
     * 生产引擎（整块/流式 × 加/解）× JCE 参照（一次性 doFinal / 64 KiB 分块）。
     * 结论只允许由 10 轮的**分布**得出，不得由任一轮单独得出。
     */
    @Test
    fun probeAes生产形态对照_连续10轮() {
        val payload = ByteArray(PAYLOAD_BYTES) { ((it * 31 + 11) and 0xFF).toByte() }
        val key = ByteArray(32) { ((it * 13 + 3) and 0xFF).toByte() }
        val iv = ByteArray(16) { ((it * 7 + 1) and 0xFF).toByte() }
        val engine = AesCipherEngine()

        // 生成一份参考密文（JCE，用于解密侧对照）
        val referenceCipher = run {
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            c.doFinal(payload)
        }

        fun medianMs(block: () -> ByteArray): Double {
            block() // 预热 1 次
            val samples = (0 until SAMPLES).map {
                val start = System.nanoTime()
                val out = block()
                val ms = (System.nanoTime() - start) / 1_000_000.0
                Arrays.fill(out, 0)
                ms
            }
            return samples.sorted()[samples.size / 2]
        }

        fun jceEncryptBulk(): ByteArray {
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return c.doFinal(payload)
        }

        repeat(10) { index ->
            val encBulk = medianMs { engine.encrypt(key, iv, payload) }
            val encStream = medianMs {
                val sink = ByteArrayOutputStream(payload.size + 32)
                engine.createEncryptingStream(sink, key, iv).use { it.write(payload) }
                sink.toByteArray()
            }
            val decBulk = medianMs { engine.decrypt(key, iv, referenceCipher) }
            val decStream = medianMs {
                val out = ByteArrayOutputStream(referenceCipher.size)
                engine.createDecryptingStream(
                    ByteArrayInputStream(referenceCipher), key, iv
                ).use { it.copyTo(out) }
                out.toByteArray()
            }
            val jceBulk = medianMs { jceEncryptBulk() }
            val jceChunk = medianMs { encryptViaChunkedUpdate(payload, key, iv) }

            println(
                "$PREFIX AES-10轮|round=${index + 1}|" +
                    "生产整块加密=${fmt(encBulk)}ms|生产流式加密=${fmt(encStream)}ms|" +
                    "生产整块解密=${fmt(decBulk)}ms|生产流式解密=${fmt(decStream)}ms|" +
                    "JCE-doFinal=${fmt(jceBulk)}ms|JCE-64KiB=${fmt(jceChunk)}ms"
            )
        }

        Arrays.fill(payload, 0); Arrays.fill(key, 0); Arrays.fill(iv, 0); Arrays.fill(referenceCipher, 0)
    }

    /**
     * JNI 边界单次开销实测（2026-09-17 追问「RS256 结论是否考虑 JNI 开销」的量级证据）。
     *
     * 用既有内核测「一次跨边界调用 + 两次数组拷贝」的**固定开销**：
     * - 16 B 载荷 ≈ 纯边界开销（内核计算量可忽略）；
     * - 64 KiB 载荷 = 生产分块粒度下的单次调用成本（含真实计算）。
     *
     * 有了该数字即可判定：RS256 每次签名只跨边界 1 次、keygen 2 次，
     * JNI 开销相对 17.8 ms / 2.1 s 的占比是否可能解释 1.6~2.1× 的差距。
     */
    @Test
    fun probeJni边界单次开销() {
        val key = ByteArray(32) { (it * 7 + 3).toByte() }
        val nonce = ByteArray(12) { (it * 3 + 1).toByte() }
        val tiny = ByteArray(16) { (it * 11 + 2).toByte() }
        val chunk = ByteArray(64 * 1024) { (it * 13 + 5).toByte() }

        fun perCallMicros(label: String, payload: ByteArray) {
            val ops = 2000
            repeat(100) { NativeChaCha20.applyKeystreamChecked(key, nonce, 0, payload) }
            val samples = (0 until SAMPLES).map {
                val start = System.nanoTime()
                repeat(ops) { NativeChaCha20.applyKeystreamChecked(key, nonce, 0, payload) }
                (System.nanoTime() - start) / 1000.0 / ops
            }
            val sorted = samples.sorted()
            println(
                "$PREFIX JNI边界|$label(${payload.size}B)|n=${sorted.size}轮均摊|" +
                    "median=${fmt3(sorted[sorted.size / 2])}us|min=${fmt3(sorted.min())}us|max=${fmt3(sorted.max())}us"
            )
        }

        perCallMicros("NativeChaCha20.applyKeystream", tiny)
        perCallMicros("NativeChaCha20.applyKeystream", chunk)
        Arrays.fill(key, 0); Arrays.fill(nonce, 0); Arrays.fill(tiny, 0); Arrays.fill(chunk, 0)
    }

    /**
     * JNI 边界成本分解 · **连续 10 轮**（2026-09-18，回答「AES 是否算法/库的问题」）。
     *
     * 用**同一份 10 MiB 载荷**把「算法」与「边界」拆开：
     * - `Java 数组拷贝`：单遍纯 Java 10 MiB 拷贝（`Arrays.copyOf`，分配 + memcpy）——该机拷贝能力的直接标尺；
     * - `ChaCha20 单次 10 MiB`：内核无 JNI 实测 ≈85 ms（§2.1，118 MB/s，流密码无串行块依赖）；
     * - `AES 单次 10 MiB`：内核无 JNI 实测 ≈63 ms（§7.3，158.7 MB/s，CBC 加密串行依赖）；
     * - `AES 生产形态`：即 [AesCipherEngine.encrypt]（= 上一条 + PKCS#7 垫片 + 明文擦除）。
     *
     * 若「ChaCha20/AES 单次 10 MiB」远大于各自内核值，则差额即**边界 + 数组拷贝**成本，
     * 与所用算法无关——这才是判定下沉是否划算的依据。
     */
    @Test
    fun probeJni边界_10MiB成本分解_连续10轮() {
        val chachaKey = ByteArray(32) { ((it * 13 + 3) and 0xFF).toByte() }
        val nonce = ByteArray(12) { ((it * 7 + 1) and 0xFF).toByte() }
        val aesKey = ByteArray(32) { ((it * 11 + 5) and 0xFF).toByte() }
        val aesIv = ByteArray(16) { ((it * 3 + 2) and 0xFF).toByte() }
        // 10 MiB 恰为 16 的整数倍 ⇒ 可直接投原生整块接口（与生产路径的算法段逐字等价）
        val payload = ByteArray(PAYLOAD_BYTES) { ((it * 31 + 11) and 0xFF).toByte() }
        val engine = AesCipherEngine()

        fun medianMs(block: () -> ByteArray): Double {
            block()
            val samples = (0 until SAMPLES).map {
                val start = System.nanoTime()
                val out = block()
                val ms = (System.nanoTime() - start) / 1_000_000.0
                Arrays.fill(out, 0)
                ms
            }
            return samples.sorted()[samples.size / 2]
        }

        repeat(10) { index ->
            val javaCopy = medianMs { Arrays.copyOf(payload, payload.size) }
            val chachaJni = medianMs { NativeChaCha20.applyKeystreamChecked(chachaKey, nonce, 0, payload) }
            val aesJni = medianMs { NativeAes.encryptBlocks(aesKey, aesIv.copyOf(), payload) }
            val aesProduction = medianMs { engine.encrypt(aesKey, aesIv, payload) }
            println(
                "$PREFIX JNI分解-10轮|round=${index + 1}|" +
                    "Java拷贝10MiB=${fmt(javaCopy)}ms|ChaCha20单次10MiB=${fmt(chachaJni)}ms|" +
                    "AES单次10MiB=${fmt(aesJni)}ms|AES生产形态=${fmt(aesProduction)}ms"
            )
        }

        Arrays.fill(payload, 0); Arrays.fill(chachaKey, 0); Arrays.fill(nonce, 0)
        Arrays.fill(aesKey, 0); Arrays.fill(aesIv, 0)
    }

    // ================= 采样与输出基建 =================

    /** 吞吐测量：预热 [WARMUP] 次后采样 [SAMPLES] 次，返回毫秒列表。 */
    private fun measure(label: String, variant: String, block: () -> ByteArray): List<Double> {
        repeat(WARMUP) { block() }
        val samples = ArrayList<Double>(SAMPLES)
        repeat(SAMPLES) {
            val start = System.nanoTime()
            val result = block()
            val ms = (System.nanoTime() - start) / 1_000_000.0
            samples.add(ms)
            Arrays.fill(result, 0)
        }
        val median = samples.sorted()[samples.size / 2]
        println(
            "$PREFIX$label|$variant|payload=${PAYLOAD_BYTES / (1024 * 1024)}MiB|" +
                "median=${fmt(median)}ms|min=${fmt(samples.min())}ms|max=${fmt(samples.max())}ms|" +
                "throughput=${fmt(PAYLOAD_BYTES / 1024.0 / 1024.0 / (median / 1000.0))}MB/s"
        )
        return samples
    }

    private fun reportComparison(
        label: String,
        payloadBytes: Int,
        before: List<Double>,
        after: List<Double>,
        extra: List<Double>?
    ) {
        val beforeMedian = before.sorted()[before.size / 2]
        val afterMedian = after.sorted()[after.size / 2]
        println(
            "$PREFIX$label|对比汇总|现状=${fmt(beforeMedian)}ms|候选=${fmt(afterMedian)}ms|" +
                "加速比=${fmt(beforeMedian / afterMedian)}x"
        )
        if (extra != null) {
            val extraMedian = extra.sorted()[extra.size / 2]
            println("$PREFIX$label|参照一次性doFinal=${fmt(extraMedian)}ms")
        }
    }

    /** 单操作微基准：每采样执行 [ops] 次取均摊，共 [samples] 轮；返回每轮均摊毫秒列表。 */
    private fun measurePerOp(
        algo: String,
        op: String,
        warmupRounds: Int,
        samples: Int,
        ops: Int,
        block: () -> Any
    ): List<Double> {
        repeat(warmupRounds) { block() }
        val perOp = ArrayList<Double>(samples)
        repeat(samples) {
            val start = System.nanoTime()
            repeat(ops) { block() }
            perOp.add((System.nanoTime() - start) / 1_000_000.0 / ops)
        }
        println("$PREFIX$algo|$op|采样中（本轮均摊）" + perOp.joinToString(",") { fmt(it) })
        return perOp
    }

    private fun reportPerOp(algo: String, op: String, perOp: List<Double>) {
        val sorted = perOp.sorted()
        val median = sorted[sorted.size / 2]
        println(
            "$PREFIX$algo|$op|n=${sorted.size}轮均摊|median=${fmt3(median)}ms|" +
                "min=${fmt3(sorted.min())}ms|max=${fmt3(sorted.max())}ms"
        )
    }

    private fun fmt(v: Double) = String.format("%.1f", v)
    private fun fmt3(v: Double) = String.format("%.3f", v)

    /** ES256 私钥标量 → 定长 32 字节无符号数组（与 PasskeyKeyCodec 导出契约一致）。 */
    private fun scalarTo32Bytes(d: BigInteger): ByteArray {
        val raw = d.toByteArray()
        val out = ByteArray(32)
        if (raw.size == 32) {
            System.arraycopy(raw, 0, out, 0, 32)
        } else if (raw.size == 33) {
            // 符号位填充情形：去掉前导 0x00
            System.arraycopy(raw, 1, out, 0, 32)
        } else {
            System.arraycopy(raw, if (raw.size > 32) raw.size - 32 else 0, out, 32 - minOf(32, raw.size), minOf(32, raw.size))
        }
        Arrays.fill(raw, 0)
        return out
    }
}
