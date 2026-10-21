package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.crypto.cipher.CipherEngine
import com.keepasskey.crypto.exception.CryptoException
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.exception.KdbxCorruptFileException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * `ISSUE-P3-646`：cipherKey 变体**解密探针**的中间缓冲必须确定性擦除。
 *
 * ## 被锁定的缓冲
 *
 * 探针（[KdbxCipherKeyResolver] → `isPlausibleInnerHeaderPrefix`）在每次尝试时分配：
 * 1. `PROBE_READ_BUFFER_SIZE` 的读缓冲（明文块）；
 * 2. `WipableByteArrayOutputStream` 的每一代内部缓冲（已解出的明文前缀）；
 * 3. `toByteArray()` 交出的前缀副本；
 * 4. GZip 魔数校验的 3 字节副本。
 *
 * 四者都在 `finally` 内被擦除，但此前没有任何用例直接持有这些数组的**引用**：
 * 「擦除」与「丢引用等 GC」在可观测层面无法区分（敏感缓冲所有权契约 R3）。
 *
 * ## 覆盖路径
 *
 * - 官方派生命中（GZip 魔数 / 非压缩字段序列两种）；
 * - 官方派生失败 → 旧派生命中（密钥所有权转移，**不得**被擦除）；
 * - 两侧均失败（抛 `KdbxCorruptFileException`）；
 * - `deriveLegacyKeys` 抛错；
 * - 首块过短（不进入探针，观察面必须为空——证明断言不是空转）。
 */
class KdbxCipherKeyResolverBufferWipeTest {

    private val observed = mutableListOf<ByteArray>()
    private val snapshotAtObservation = mutableListOf<ByteArray>()

    private val observer: (ByteArray) -> Unit = { buffer ->
        observed += buffer
        snapshotAtObservation += buffer.copyOf()
    }

    /**
     * 可控解密引擎：把「密文」原样当作明文吐出（探针只看解出前缀的结构，不看密码学正确性），
     * 因此可以精确构造「合法 GZip 魔数」「合法内层字段序列」「非法前缀」三类首块。
     */
    private class PassthroughCipherEngine : CipherEngine {
        override val cipherUuid = KdbxConstants.Cipher.AES_256_CBC
        override val name = "PassthroughStub"
        override val ivLength = 16

        override fun encrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
            throw UnsupportedOperationException()

        override fun decrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
            throw UnsupportedOperationException()

        override fun createEncryptingStream(
            outputStream: OutputStream,
            key: ByteArray,
            iv: ByteArray
        ): OutputStream = throw UnsupportedOperationException()

        override fun createDecryptingStream(
            inputStream: InputStream,
            key: ByteArray,
            iv: ByteArray
        ): InputStream = inputStream
    }

    /** 按 key 决定行为：`officialKey` 解不出合法前缀，`legacyKey` 解出合法前缀。 */
    private class VariantCipherEngine(
        private val officialKey: ByteArray,
        private val legacyKey: ByteArray,
        private val officialPlain: ByteArray,
        private val legacyPlain: ByteArray
    ) : CipherEngine {
        override val cipherUuid = KdbxConstants.Cipher.AES_256_CBC
        override val name = "VariantStub"
        override val ivLength = 16

        override fun encrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
            throw UnsupportedOperationException()

        override fun decrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
            throw UnsupportedOperationException()

        override fun createEncryptingStream(
            outputStream: OutputStream,
            key: ByteArray,
            iv: ByteArray
        ): OutputStream = throw UnsupportedOperationException()

        override fun createDecryptingStream(
            inputStream: InputStream,
            key: ByteArray,
            iv: ByteArray
        ): InputStream = plainFor(key).let { ByteArrayInputStream(it) }

        private fun plainFor(key: ByteArray): ByteArray = when {
            key.contentEquals(officialKey) -> officialPlain
            key.contentEquals(legacyKey) -> legacyPlain
            else -> ByteArray(0)
        }
    }

    private val header = KdbxHeader(
        kdfParameters = KdfParameters.Aes(seed = ByteArray(32) { 0x5A }, rounds = 6_000L)
    )
    private val officialKey = ByteArray(32) { 1 }
    private val firstBlock = ByteArray(64) { 0x0A }

    // ==================== 断言工具 ====================

    /**
     * @param expectSensitiveContent 是否要求「至少一个被观察缓冲曾承载非零数据」。仅当被测路径
     *   在产生任何明文之前就失败（例如 GZip 头本身非法，`GZIPInputStream` 构造即抛错）时才为 false；
     *   此时仍要求观察面非空（证明清理域确实存在），只是不允许伪造非零快照。
     */
    private fun assertAllWiped(label: String, expectSensitiveContent: Boolean = true) {
        assertTrue("[$label] 用例必须真的观察到探针工作缓冲（否则断言空转）", observed.isNotEmpty())
        if (expectSensitiveContent) {
            assertTrue(
                "[$label] 观察到的缓冲中至少有一个原本承载非空明文前缀",
                snapshotAtObservation.any { snapshot -> snapshot.any { it != 0.toByte() } }
            )
        }
        observed.forEachIndexed { index, buffer ->
            assertTrue(
                "[$label] 第 ${index + 1} 个探针缓冲（size=${buffer.size}）在 resolve 返回/抛出后必须逐字节清零，" +
                    "实际残留 ${buffer.count { it != 0.toByte() }} 字节",
                buffer.all { it == 0.toByte() }
            )
        }
    }

    // ==================== 官方派生命中 ====================

    @Test
    fun `GZip魔数命中时探针读缓冲与收集缓冲全部清零`() {
        // 解出的前缀以 RFC 1952 GZIP 魔数开头 ⇒ 官方派生被选中
        val plainPrefix = byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00, 0x11, 0x22, 0x33, 0x44)
        val resolution = KdbxCipherKeyResolver.resolve(
            cipherEngine = VariantCipherEngine(officialKey, ByteArray(32) { 2 }, plainPrefix, ByteArray(0)),
            header = header,
            firstBlock = firstBlock,
            officialKey = officialKey,
            isGzipCompressed = true,
            observeBuffer = observer,
            deriveLegacyKeys = { error("官方派生命中时不得再派生旧密钥（否则多一份无谓的密钥计算与残留面）") }
        )

        assertAllWiped("GZip 魔数命中")
        assertSame("必须选中官方派生密钥", officialKey, resolution.activeKey)
        assertNull("官方派生命中时不得声明待擦除的旧密钥", resolution.legacyKeyToWipe)
    }

    @Test
    fun `非压缩库的合法内层字段序列命中时同样全部清零`() {
        // 内层 Header：INNER_RANDOM_STREAM_ID(id=1, len=4) + END(id=0, len=0)
        val plainPrefix = byteArrayOf(
            0x01, 0x04, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00
        )
        val resolution = KdbxCipherKeyResolver.resolve(
            cipherEngine = VariantCipherEngine(officialKey, ByteArray(32) { 2 }, plainPrefix, ByteArray(0)),
            header = header,
            firstBlock = firstBlock,
            officialKey = officialKey,
            isGzipCompressed = false,
            observeBuffer = observer,
            deriveLegacyKeys = { error("官方派生命中时不得再派生旧密钥") }
        )

        assertAllWiped("内层字段序列命中")
        assertSame(officialKey, resolution.activeKey)
    }

    // ==================== 旧派生命中（所有权转移） ====================

    @Test
    fun `旧派生命中时探针缓冲清零且被选中的旧密钥不再被擦除（所有权转移）`() {
        val legacyCipherKey = ByteArray(32) { 7 }
        val legacyHmacKey = ByteArray(64) { 9 }
        val legacyPlain = byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x55, 0x66)

        val resolution = KdbxCipherKeyResolver.resolve(
            cipherEngine = VariantCipherEngine(
                officialKey = officialKey,
                legacyKey = legacyCipherKey,
                officialPlain = ByteArray(8) { 0xEE.toByte() }, // 非法前缀
                legacyPlain = legacyPlain
            ),
            header = header,
            firstBlock = firstBlock,
            officialKey = officialKey,
            isGzipCompressed = true,
            observeBuffer = observer,
            deriveLegacyKeys = { Pair(legacyCipherKey, legacyHmacKey) }
        )

        assertAllWiped("旧派生命中")
        assertSame("被选中的旧派生密钥必须原样移交调用方", legacyCipherKey, resolution.activeKey)
        assertSame("同时须声明该数组由调用方擦除", legacyCipherKey, resolution.legacyKeyToWipe)
        assertTrue(
            "被选中的 cipherKey 是 R2 的「转移」，resolve 内部不得清零",
            legacyCipherKey.any { it != 0.toByte() }
        )
        assertArrayEquals("hmacKey 属派生物，无论是否选中都必须立即擦除", ByteArray(64), legacyHmacKey)
    }

    // ==================== 两侧均失败 / 派生抛错 ====================

    @Test
    fun `两侧探针均失败时探针缓冲清零且旧密钥被擦除`() {
        val legacyCipherKey = ByteArray(32) { 3 }
        val legacyHmacKey = ByteArray(64) { 4 }

        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxCipherKeyResolver.resolve(
                cipherEngine = VariantCipherEngine(
                    officialKey = officialKey,
                    legacyKey = legacyCipherKey,
                    officialPlain = ByteArray(8) { 0xEE.toByte() },
                    legacyPlain = ByteArray(8) { 0xDD.toByte() }
                ),
                header = header,
                firstBlock = firstBlock,
                officialKey = officialKey,
                isGzipCompressed = true,
                observeBuffer = observer,
                deriveLegacyKeys = { Pair(legacyCipherKey, legacyHmacKey) }
            )
        }

        assertAllWiped("两侧均失败")
        assertArrayEquals("未选中的旧派生密钥必须清零", ByteArray(32), legacyCipherKey)
        assertArrayEquals("hmacKey 必须清零", ByteArray(64), legacyHmacKey)
    }

    @Test
    fun `派生调用点抛错时探针缓冲已清零`() {
        assertThrows(CryptoException.KdfException::class.java) {
            KdbxCipherKeyResolver.resolve(
                cipherEngine = VariantCipherEngine(
                    officialKey = officialKey,
                    legacyKey = ByteArray(32) { 5 },
                    officialPlain = ByteArray(8) { 0xEE.toByte() },
                    legacyPlain = ByteArray(0)
                ),
                header = header,
                firstBlock = firstBlock,
                officialKey = officialKey,
                isGzipCompressed = true,
                observeBuffer = observer,
                deriveLegacyKeys = { throw CryptoException.KdfException("Argon2 密钥派生失败（测试注入）") }
            )
        }

        assertAllWiped("派生抛错")
    }

    // ==================== 短首块：不进入探针 ====================

    @Test
    fun `首块过短时不进入探针且不产生任何待擦缓冲`() {
        val resolution = KdbxCipherKeyResolver.resolve(
            cipherEngine = PassthroughCipherEngine(),
            header = header,
            firstBlock = ByteArray(8),
            officialKey = officialKey,
            isGzipCompressed = true,
            observeBuffer = observer,
            deriveLegacyKeys = { error("首块过短时不得派生旧密钥") }
        )

        assertSame(officialKey, resolution.activeKey)
        assertTrue(
            "短首块路径不得分配探针缓冲（观察面必须为空，证明前面的断言确实依赖观察回调）",
            observed.isEmpty()
        )
    }

    @Test
    fun `首块为null时直接按官方派生继续`() {
        val resolution = KdbxCipherKeyResolver.resolve(
            cipherEngine = PassthroughCipherEngine(),
            header = header,
            firstBlock = null,
            officialKey = officialKey,
            isGzipCompressed = false,
            observeBuffer = observer,
            deriveLegacyKeys = { error("首块缺失时不得派生旧密钥") }
        )
        assertSame(officialKey, resolution.activeKey)
        assertEquals(0, observed.size)
    }

    // ==================== 探针读取分块（跨多次扩容） ====================

    @Test
    fun `前缀跨多代输出缓冲时每一代都清零`() {
        // 解出 20 KiB 明文前缀（> 默认 32 B 初始容量，跨多代扩容），仍以 GZip 魔数开头
        val plainPrefix = ByteArray(20 * 1024).also {
            it[0] = 0x1F
            it[1] = 0x8B.toByte()
            it[2] = 0x08
        }
        val resolution = KdbxCipherKeyResolver.resolve(
            cipherEngine = VariantCipherEngine(officialKey, ByteArray(32) { 2 }, plainPrefix, ByteArray(0)),
            header = header,
            firstBlock = firstBlock,
            officialKey = officialKey,
            isGzipCompressed = true,
            observeBuffer = observer,
            deriveLegacyKeys = { error("官方派生命中时不得再派生旧密钥") }
        )

        assertAllWiped("跨多代输出缓冲")
        assertSame(officialKey, resolution.activeKey)
        plainPrefix.fill(0)
    }
}
