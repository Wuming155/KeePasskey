package com.keepasskey.crypto.cipher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-CBC 原生内核与平台 JCE 参考实现的**差分等价**用例（ISSUE-P3-155 追问 / §147）。
 *
 * 与 [TwofishNativeParityTest] 同构：原生路径只做分组变换，PKCS#7 与流式语义由 Kotlin 侧
 * （[Pkcs7] / [CbcEncryptingOutputStream] / [CbcDecryptingInputStream]）承担；因此「与参考实现
 * 逐字节一致」必须由**整型与流式两条路径分别**证明，并由错误语义用例锁定 fail-closed 行为。
 *
 * 参考实现取**平台 JCE**（`AES/CBC/PKCS5Padding`）——AES 在 Android / 宿主 JVM 上均由
 * JCE/Conscrypt 提供，无需经 BouncyCastle（与 Twofish 不同：后者无平台实现，故取 BC）。
 *
 * 降级语义：依赖原生的用例经 `Assume` 跳过（宿主未注入 cdylib / 个别机型缺 ABI）；
 * **参考实现自身**的往返与错误语义用例永不跳过。
 */
class AesNativeParityTest {

    private val engine = AesCipherEngine()

    // ==================== 参考实现（平台 JCE） ====================

    private fun jceCipher(mode: Int, key: ByteArray, iv: ByteArray, transformation: String): Cipher {
        val cipher = Cipher.getInstance(transformation)
        cipher.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher
    }

    private fun jceEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        jceCipher(Cipher.ENCRYPT_MODE, key, iv, TRANSFORMATION).doFinal(data)

    private fun jceDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        jceCipher(Cipher.DECRYPT_MODE, key, iv, TRANSFORMATION).doFinal(data)

    private fun jceEncryptStream(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        CipherOutputStream(out, jceCipher(Cipher.ENCRYPT_MODE, key, iv, TRANSFORMATION)).use {
            it.write(data)
        }
        return out.toByteArray()
    }

    private fun jceDecryptStream(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        CipherInputStream(ByteArrayInputStream(data), jceCipher(Cipher.DECRYPT_MODE, key, iv, TRANSFORMATION)).use {
            it.copyTo(out)
        }
        return out.toByteArray()
    }

    private fun keyOf(salt: Int): ByteArray = ByteArray(32) { ((it * 7 + salt) and 0xFF).toByte() }

    private fun ivOf(salt: Int): ByteArray = ByteArray(16) { ((it * 11 + salt) and 0xFF).toByte() }

    private fun plainOf(len: Int): ByteArray = ByteArray(len) { ((it * 31 + 5) and 0xFF).toByte() }

    /** 覆盖空、单字节、跨分组边界与跨流缓冲边界（64 KiB）的样本长度。 */
    private val lengths = listOf(0, 1, 15, 16, 17, 31, 32, 33, 1000, 65535, 65536, 65537, 131_075)

    /** NIST CBC-AES256 官方向量（SP 800-38A F.2.5 / F.2.6）；与 `aes_cbc_tests.rs` 同源。 */
    private val nistKey = "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4".hexToByteArray()
    private val nistIv = "000102030405060708090a0b0c0d0e0f".hexToByteArray()
    private val nistPlain = (
        "6bc1bee22e409f96e93d7e117393172a" +
            "ae2d8a571e03ac9c9eb76fac45af8e51" +
            "30c81c46a35ce411e5fbc1191a0a52ef" +
            "f69f2445df4f9b17ad2b417be66c3710"
        ).hexToByteArray()
    private val nistCipher = (
        "f58c4c04d6e5f1ba779eabfb5f7bfbd6" +
            "9cfc4e967edb808d679f777bc6702c7d" +
            "39f23369a9d9bacfa530e26304231461" +
            "b2eb05e2c39be9fcda6c19078c6a9d1b"
        ).hexToByteArray()

    // ==================== 参考实现自证（永不跳过） ====================

    @Test
    fun `JCE 参考实现自身往返一致`() {
        for (len in listOf(0, 1, 16, 100)) {
            val key = keyOf(3)
            val iv = ivOf(1)
            val plain = plainOf(len)
            assertArrayEquals("len=$len", plain, jceDecrypt(key, iv, jceEncrypt(key, iv, plain)))
        }
    }

    @Test
    fun `JCE 参考实现对非法 PKCS7 填充 fail-closed`() {
        // 确定性构造：用 NoPadding 加密一组「末字节声称填充 5 字节但前面不是」的明文，
        // 使 PKCS#7 校验必然失败（不依赖随机篡改的 1/256 偶然通过）。
        val key = keyOf(21)
        val iv = ivOf(9)
        val badPlain = ByteArray(16) { 0x41 }.also { it[15] = 0x05 }
        val cipherText = jceCipher(Cipher.ENCRYPT_MODE, key, iv, TRANSFORMATION_NO_PADDING).doFinal(badPlain)

        val failure = runCatching { jceDecrypt(key, iv, cipherText) }.exceptionOrNull()
        assertTrue("参考实现对非法填充必须抛异常，而非静默交付", failure != null)
    }

    // ==================== NIST 官方向量（原生块接口） ====================

    @Test
    fun `原生内核复现 NIST CBC-AES256 官方向量且 IV 原地演进`() {
        Assume.assumeTrue("原生内核不可用（未构建宿主库），跳过差分用例", NativeAes.available)

        val encIv = nistIv.copyOf()
        val cipherText = NativeAes.cbcEncryptBlocks(nistKey, encIv, nistPlain)
            ?: throw AssertionError("官方向量加密返回 null")
        assertArrayEquals("密文必须与 NIST CBC-AES256 向量逐字节一致", nistCipher, cipherText)
        assertArrayEquals(
            "加密后 IV 必须演进为最后一组密文（流式分段的直接依据）",
            nistCipher.copyOfRange(nistCipher.size - NativeAes.BLOCK_SIZE, nistCipher.size),
            encIv
        )

        val decIv = nistIv.copyOf()
        val plain = NativeAes.cbcDecryptBlocks(nistKey, decIv, nistCipher)
            ?: throw AssertionError("官方向量解密返回 null")
        assertArrayEquals("明文必须与 NIST CBC-AES256 向量逐字节一致", nistPlain, plain)
    }

    // ==================== 整型路径 ====================

    @Test
    fun `原生整型加密与 JCE 在多长度下逐字节一致`() {
        Assume.assumeTrue("原生内核不可用（未构建宿主库），跳过差分用例", NativeAes.available)
        for (len in lengths) {
            val key = keyOf(5)
            val iv = ivOf(2)
            val plain = plainOf(len)
            val actual = engine.encrypt(key, iv, plain)
            val expected = jceEncrypt(key, iv, plain)
            assertArrayEquals("len=$len 原生与 JCE 密文不一致", expected, actual)
            // 交叉解密：JCE 密文必须能被原生引擎解回原文
            assertArrayEquals("len=$len 原生解密 JCE 密文失败", plain, engine.decrypt(key, iv, expected))
            // 自身往返
            assertArrayEquals("len=$len 原生往返失败", plain, engine.decrypt(key, iv, actual))
        }
    }

    // ==================== 流式路径 ====================

    @Test
    fun `原生流式加密与 JCE 流式加密逐字节一致`() {
        Assume.assumeTrue("原生内核不可用（未构建宿主库），跳过差分用例", NativeAes.available)
        for (len in lengths) {
            val key = keyOf(13)
            val iv = ivOf(6)
            val plain = plainOf(len)

            val nativeOut = ByteArrayOutputStream()
            engine.createEncryptingStream(nativeOut, key, iv).use { it.write(plain) }

            assertArrayEquals(
                "len=$len 原生流式密文与 JCE 流式不一致",
                jceEncryptStream(key, iv, plain),
                nativeOut.toByteArray()
            )
        }
    }

    @Test
    fun `原生流式解密与 JCE 流式解密产出同一明文`() {
        Assume.assumeTrue("原生内核不可用（未构建宿主库），跳过差分用例", NativeAes.available)
        for (len in lengths) {
            val key = keyOf(17)
            val iv = ivOf(8)
            val plain = plainOf(len)
            val cipherText = jceEncryptStream(key, iv, plain)

            val nativeOut = ByteArrayOutputStream()
            engine.createDecryptingStream(ByteArrayInputStream(cipherText), key, iv).use {
                it.copyTo(nativeOut)
            }
            val restored = nativeOut.toByteArray()
            assertArrayEquals("len=$len 原生流式解密失败", plain, restored)
            assertArrayEquals(
                "len=$len 原生流式解密与 JCE 流式解密不一致",
                jceDecryptStream(key, iv, cipherText),
                restored
            )
        }
    }

    @Test
    fun `逐字节写入与一次写入结果一致`() {
        Assume.assumeTrue("原生内核不可用（未构建宿主库），跳过差分用例", NativeAes.available)
        val key = keyOf(19)
        val iv = ivOf(10)
        val plain = plainOf(1234)

        val bulk = ByteArrayOutputStream()
        engine.createEncryptingStream(bulk, key, iv).use { it.write(plain) }

        val perByte = ByteArrayOutputStream()
        engine.createEncryptingStream(perByte, key, iv).use { stream ->
            for (b in plain) stream.write(b.toInt())
        }

        assertArrayEquals(bulk.toByteArray(), perByte.toByteArray())
    }

    // ==================== 契约：调用方 IV 不被污染 ====================

    @Test
    fun `调用方 IV 数组在整型与流式路径均不被改写`() {
        Assume.assumeTrue("原生内核不可用（未构建宿主库），跳过差分用例", NativeAes.available)
        val key = keyOf(23)
        val plain = plainOf(2000)

        // 整型路径：原生侧把 IV 原地演化为末组密文，故必须传入副本；调用方数组须保持原值
        val bulkIv = ivOf(12)
        val bulkIvExpected = bulkIv.copyOf()
        val cipherText = engine.encrypt(key, bulkIv, plain)
        assertArrayEquals("整型加密不得改写调用方 IV", bulkIvExpected, bulkIv)
        engine.decrypt(key, bulkIv, cipherText)
        assertArrayEquals("整型解密不得改写调用方 IV", bulkIvExpected, bulkIv)

        // 流式路径：链值由流实例自持（构造时 copyOf），调用方数组同样不受影响
        val streamIv = ivOf(12)
        val streamIvExpected = streamIv.copyOf()
        val out = ByteArrayOutputStream()
        engine.createEncryptingStream(out, key, streamIv).use { it.write(plain) }
        assertArrayEquals("流式加密不得改写调用方 IV", streamIvExpected, streamIv)
        assertArrayEquals("流式密文必须与整型密文一致", cipherText, out.toByteArray())
    }

    // ==================== 异常语义 ====================

    @Test
    fun `整型解密对非分组整数倍密文 fail-closed`() {
        val key = keyOf(29)
        val iv = ivOf(14)
        // 参考实现对同样输入同样失败 → 语义对齐（该断言不依赖原生可用性）
        for (bad in listOf(1, 15, 17, 31)) {
            assertTrue(
                "JCE 参考实现对长度 $bad 的密文必须失败",
                runCatching { jceDecrypt(key, iv, ByteArray(bad)) }.exceptionOrNull() != null
            )
        }
        Assume.assumeTrue("原生内核不可用（未构建宿主库），跳过差分用例", NativeAes.available)
        for (bad in listOf(1, 15, 17, 31)) {
            assertTrue(
                "原生路径对长度 $bad 的密文必须失败",
                runCatching { engine.decrypt(key, iv, ByteArray(bad)) }.exceptionOrNull() != null
            )
        }
    }

    @Test
    fun `非法 PKCS7 填充在原生路径与 JCE 语义一致`() {
        Assume.assumeTrue("原生内核不可用（未构建宿主库），跳过差分用例", NativeAes.available)
        val key = keyOf(31)
        val iv = ivOf(16)

        // 正例（防「一律失败」的假绿）：末字节为 0x01 ⇒ 合法填充 1 字节，解出 15 字节明文
        val goodBlock = ByteArray(16) { 0x41 }.also { it[15] = 0x01 }
        val goodCipher = jceCipher(Cipher.ENCRYPT_MODE, key, iv, TRANSFORMATION_NO_PADDING).doFinal(goodBlock)
        assertArrayEquals(
            "合法单字节填充必须解出 15 字节明文",
            ByteArray(15) { 0x41 },
            engine.decrypt(key, iv, goodCipher)
        )

        // 负例：末字节声称 5 字节填充但前面不匹配 ⇒ 原生与 JCE 均必须失败
        val badBlock = ByteArray(16) { 0x41 }.also { it[15] = 0x05 }
        val badCipher = jceCipher(Cipher.ENCRYPT_MODE, key, iv, TRANSFORMATION_NO_PADDING).doFinal(badBlock)
        assertTrue(
            "原生路径对非法填充必须抛异常",
            runCatching { engine.decrypt(key, iv, badCipher) }.exceptionOrNull() != null
        )
        assertTrue(
            "JCE 对同一密文同样失败（语义对齐）",
            runCatching { jceDecrypt(key, iv, badCipher) }.exceptionOrNull() != null
        )
    }

    @Test
    fun `IV 长度非法时整型与流式均 fail-closed`() {
        Assume.assumeTrue("原生内核不可用（未构建宿主库），跳过差分用例", NativeAes.available)
        val key = keyOf(33)
        val shortIv = ByteArray(8)
        assertTrue(
            "整型加密：IV 长度非法应失败",
            runCatching { engine.encrypt(key, shortIv, plainOf(16)) }.exceptionOrNull() != null
        )
        assertTrue(
            "整型解密：IV 长度非法应失败",
            runCatching { engine.decrypt(key, shortIv, plainOf(16)) }.exceptionOrNull() != null
        )
        assertTrue(
            "流式加密：IV 长度非法应失败",
            runCatching { engine.createEncryptingStream(ByteArrayOutputStream(), key, shortIv) }
                .exceptionOrNull() != null
        )
    }

    private companion object {
        const val TRANSFORMATION = "AES/CBC/PKCS5Padding"

        /** 与生产流式解密同一变换（见 `AesCipherEngine.TRANSFORMATION_NO_PADDING`），用于构造确定性填充样本。 */
        const val TRANSFORMATION_NO_PADDING = "AES/CBC/NoPadding"
    }
}
