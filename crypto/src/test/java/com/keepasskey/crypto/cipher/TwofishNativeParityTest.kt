package com.keepasskey.crypto.cipher

import org.bouncycastle.jce.provider.BouncyCastleProvider
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
 * Twofish 原生内核与 BouncyCastle 参考实现的**差分等价**用例（ISSUE-P3-35）。
 *
 * 验收要求「Kotlin 侧与 BC 实现差分等价：随机 key/IV × 多长度密文逐字节一致、往返一致」，
 * 此处以**固定合成输入**（非真实凭据）覆盖 0 / 1 / 15 / 16 / 17 / 31 / 32 / 1000 与跨缓冲边界长度。
 *
 * 降级语义：原生不可用时依赖原生的用例经 `Assume` 跳过；**BC 参考实现自身**的往返用例永不跳过。
 */
class TwofishNativeParityTest {

    private val engine = TwofishCipherEngine()

    /** BC 参考实现（整型路径）。 */
    private fun bcCipher(mode: Int, key: ByteArray, iv: ByteArray): Cipher {
        ChaCha20CipherEngine.ensureBouncyCastle()
        val cipher = Cipher.getInstance("Twofish/CBC/PKCS7PADDING", BouncyCastleProvider.PROVIDER_NAME)
        cipher.init(mode, SecretKeySpec(key, "Twofish"), IvParameterSpec(iv))
        return cipher
    }

    private fun bcEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        bcCipher(Cipher.ENCRYPT_MODE, key, iv).doFinal(data)

    private fun bcEncryptStream(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        CipherOutputStream(out, bcCipher(Cipher.ENCRYPT_MODE, key, iv)).use { it.write(data) }
        return out.toByteArray()
    }

    private fun bcDecryptStream(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        CipherInputStream(ByteArrayInputStream(data), bcCipher(Cipher.DECRYPT_MODE, key, iv)).use {
            it.copyTo(out)
        }
        return out.toByteArray()
    }

    private fun keyOf(len: Int, salt: Int): ByteArray =
        ByteArray(len) { ((it * 7 + salt) and 0xFF).toByte() }

    private fun ivOf(salt: Int): ByteArray = ByteArray(16) { ((it * 11 + salt) and 0xFF).toByte() }

    private fun plainOf(len: Int): ByteArray = ByteArray(len) { ((it * 31 + 5) and 0xFF).toByte() }

    /** 覆盖空、单字节、跨分组边界与跨流缓冲边界（64 KiB）的样本长度。 */
    private val lengths = listOf(0, 1, 15, 16, 17, 31, 32, 33, 1000, 65535, 65536, 65537, 131_075)

    // ==================== 整型路径 ====================

    @Test
    fun `BC 参考实现自身往返一致（永不跳过）`() {
        for (len in listOf(0, 1, 16, 100)) {
            val key = keyOf(32, 3)
            val iv = ivOf(1)
            val plain = plainOf(len)
            val cipherText = bcEncrypt(key, iv, plain)
            val recovered = bcCipher(Cipher.DECRYPT_MODE, key, iv).doFinal(cipherText)
            assertArrayEquals("len=$len", plain, recovered)
        }
    }

    @Test
    fun `原生整型加密与 BC 在多长度下逐字节一致`() {
        Assume.assumeTrue("原生内核不可用（未构建宿主库），跳过差分用例", NativeTwofish.available)
        for (len in lengths) {
            val key = keyOf(32, 5)
            val iv = ivOf(2)
            val plain = plainOf(len)
            val actual = engine.encrypt(key, iv, plain)
            val expected = bcEncrypt(key, iv, plain)
            assertArrayEquals("len=$len 原生与 BC 密文不一致", expected, actual)
            // 交叉解密：BC 密文必须能被原生引擎解回原文
            val crossDecrypted = runCatching { engine.decrypt(key, iv, expected) }
                .getOrElse { throw AssertionError("len=$len：原生解密 BC 密文抛异常", it) }
            assertArrayEquals("len=$len 原生解密 BC 密文失败", plain, crossDecrypted)
            // 自身往返
            assertArrayEquals("len=$len 原生往返失败", plain, engine.decrypt(key, iv, actual))
        }
    }

    @Test
    fun `密钥长度 16 - 24 - 32 全部与 BC 一致`() {
        Assume.assumeTrue("原生内核不可用（未构建宿主库），跳过差分用例", NativeTwofish.available)
        for (keyLen in listOf(16, 24, 32)) {
            val key = keyOf(keyLen, 9)
            val iv = ivOf(4)
            val plain = plainOf(257)
            assertArrayEquals(
                "keyLen=$keyLen",
                bcEncrypt(key, iv, plain),
                engine.encrypt(key, iv, plain)
            )
        }
    }

    // ==================== 流式路径 ====================

    @Test
    fun `原生流式加密与 BC 流式加密逐字节一致`() {
        Assume.assumeTrue("原生内核不可用（未构建宿主库），跳过差分用例", NativeTwofish.available)
        for (len in lengths) {
            val key = keyOf(32, 13)
            val iv = ivOf(6)
            val plain = plainOf(len)

            val nativeOut = ByteArrayOutputStream()
            engine.createEncryptingStream(nativeOut, key, iv).use { it.write(plain) }

            assertArrayEquals(
                "len=$len 原生流式密文与 BC 不一致",
                bcEncryptStream(key, iv, plain),
                nativeOut.toByteArray()
            )
        }
    }

    @Test
    fun `原生流式解密与 BC 流式解密产出同一明文`() {
        Assume.assumeTrue("原生内核不可用（未构建宿主库），跳过差分用例", NativeTwofish.available)
        for (len in lengths) {
            val key = keyOf(32, 17)
            val iv = ivOf(8)
            val plain = plainOf(len)
            val cipherText = bcEncryptStream(key, iv, plain)

            val nativeOut = ByteArrayOutputStream()
            engine.createDecryptingStream(ByteArrayInputStream(cipherText), key, iv).use {
                it.copyTo(nativeOut)
            }
            assertArrayEquals("len=$len 原生流式解密失败", plain, nativeOut.toByteArray())
            assertArrayEquals(
                "len=$len 原生流式解密与 BC 流式解密不一致",
                bcDecryptStream(key, iv, cipherText),
                nativeOut.toByteArray()
            )
        }
    }

    // ==================== 组装式写入（逐字节 write 也必须对齐） ====================

    @Test
    fun `逐字节写入与一次写入结果一致`() {
        Assume.assumeTrue("原生内核不可用（未构建宿主库），跳过差分用例", NativeTwofish.available)
        val key = keyOf(32, 19)
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

    // ==================== 异常语义 ====================

    @Test
    fun `整型解密对非分组整数倍密文 fail-closed`() {
        Assume.assumeTrue("原生内核不可用（未构建宿主库），跳过差分用例", NativeTwofish.available)
        val key = keyOf(32, 23)
        val iv = ivOf(12)
        for (bad in listOf(1, 15, 17, 31)) {
            val failure = runCatching { engine.decrypt(key, iv, ByteArray(bad)) }.exceptionOrNull()
            assertTrue("长度 $bad 应失败", failure != null)
        }
        // BC 参考实现对同样输入同样失败 → 语义对齐
        ChaCha20CipherEngine.ensureBouncyCastle()
        val bcFailure = runCatching {
            bcCipher(Cipher.DECRYPT_MODE, key, iv).doFinal(ByteArray(17))
        }.exceptionOrNull()
        assertTrue("BC 参考实现对 17 字节同样失败", bcFailure != null)
    }

    @Test
    fun `篡改填充导致整型解密失败`() {
        Assume.assumeTrue("原生内核不可用（未构建宿主库），跳过差分用例", NativeTwofish.available)
        val key = keyOf(32, 29)
        val iv = ivOf(14)
        val cipherText = bcEncrypt(key, iv, plainOf(48))
        // 篡改最后一个分组，使 PKCS#7 填充大概率非法
        cipherText[cipherText.size - 1] = (cipherText[cipherText.size - 1].toInt() xor 0xFF).toByte()
        val failure = runCatching { engine.decrypt(key, iv, cipherText) }.exceptionOrNull()
        assertTrue("填充被破坏后必须失败而非静默交付", failure != null)
    }

    @Test
    fun `IV 长度非法时整型与流式均 fail-closed`() {
        Assume.assumeTrue("原生内核不可用（未构建宿主库），跳过差分用例", NativeTwofish.available)
        val key = keyOf(32, 31)
        val failure = runCatching { engine.encrypt(key, ByteArray(8), plainOf(16)) }.exceptionOrNull()
        assertTrue("IV 长度非法应失败", failure != null)
        val streamFailure = runCatching {
            engine.createEncryptingStream(ByteArrayOutputStream(), key, ByteArray(8))
        }.exceptionOrNull()
        assertTrue("流式 IV 长度非法应失败", streamFailure != null)
    }

    @Test
    fun `探活块加密结果与 BC 一致`() {
        Assume.assumeTrue("原生内核不可用（未构建宿主库），跳过差分用例", NativeTwofish.available)
        val key = keyOf(32, 1)
        val iv = ivOf(1)
        val block = plainOf(16)

        ChaCha20CipherEngine.ensureBouncyCastle()
        val reference = Cipher.getInstance("Twofish/CBC/NoPadding", BouncyCastleProvider.PROVIDER_NAME).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "Twofish"), IvParameterSpec(iv))
        }.doFinal(block)

        assertArrayEquals(reference, NativeTwofish.cbcEncryptBlocks(key, iv.copyOf(), block))
    }
}
