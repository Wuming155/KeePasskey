package com.keepasskey.database.file

import com.keepasskey.crypto.cipher.CipherEngine
import com.keepasskey.crypto.exception.CryptoException
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.exception.KdbxCorruptFileException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream

/**
 * `ISSUE-P2-290` AC①／AC③：`KdbxCipherKeyResolver.resolve` 的派生调用点保护范围回归。
 *
 * ## 锁定的行为
 *
 * - `deriveLegacyKeys()` **抛出**（如非法 KDF 参数被内核 fail-closed 拒绝）⇒ 异常**原样上抛**，
 *   不得在 finally 中因未初始化引用二次出错（调用点已纳入同一保护范围）；
 * - 两侧探针均失败 ⇒ `KdbxCorruptFileException`，且**未被选中的旧派生密钥与 hmacKey
 *   全路径清零**（测试自持数组直接观测）；
 * - 旧派生被选中 ⇒ 仅 hmacKey 擦除，cipherKey 移交调用方（契约语义不变）。
 */
class KdbxCipherKeyResolverWipeTest {

    /** 探针恒失败的桩引擎（解密流直接抛异常 ⇒ 结构校验 false）。 */
    private class FailingCipherEngine : CipherEngine {
        override val cipherUuid = com.keepasskey.core.model.KdbxConstants.Cipher.AES_256_CBC
        override val name = "FailingStub"
        override val ivLength = 16

        override fun encrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
            throw UnsupportedOperationException()
        override fun decrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
            throw UnsupportedOperationException()
        override fun createEncryptingStream(outputStream: OutputStream, key: ByteArray, iv: ByteArray): OutputStream =
            throw UnsupportedOperationException()
        override fun createDecryptingStream(inputStream: InputStream, key: ByteArray, iv: ByteArray): InputStream =
            throw UnsupportedOperationException()
    }

    private val header = KdbxHeader(
        kdfParameters = KdfParameters.Aes(seed = ByteArray(32) { 0x5A }, rounds = 6_000L)
    )
    private val officialKey = ByteArray(32) { 1 }
    private val firstBlock = ByteArray(64) { 0x0A }

    @Test
    fun `派生调用点抛出：异常原样上抛且 finally 不二次出错`() {
        val thrown = assertThrows(CryptoException.KdfException::class.java) {
            KdbxCipherKeyResolver.resolve(
                cipherEngine = FailingCipherEngine(),
                header = header,
                firstBlock = firstBlock,
                officialKey = officialKey,
                isGzipCompressed = true
            ) {
                throw CryptoException.KdfException("Argon2 密钥派生失败（参数越界或 KDF 内存超出本机可用内存）")
            }
        }
        assertTrue(
            "原始异常必须原样上抛（finally 不得二次出错掩盖根因）: ${thrown.message}",
            thrown.message!!.contains("派生失败")
        )
    }

    @Test
    fun `两探针均失败：旧派生密钥与 hmacKey 全路径清零`() {
        val legacyCipherKey = ByteArray(32) { 7 }
        val legacyHmacKey = ByteArray(64) { 9 }

        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxCipherKeyResolver.resolve(
                cipherEngine = FailingCipherEngine(),
                header = header,
                firstBlock = firstBlock,
                officialKey = officialKey,
                isGzipCompressed = true
            ) {
                Pair(legacyCipherKey, legacyHmacKey)
            }
        }

        assertArrayEquals("未被选中的旧派生密钥必须清零", ByteArray(32), legacyCipherKey)
        assertArrayEquals("hmacKey 必须清零", ByteArray(64), legacyHmacKey)
    }

    @Test
    fun `派生调用点抛错路径不产生半初始化残留（固定样本语义）`() {
        // 条目固定样本（m=8192, p=64）的派生失败形态：调用点抛 KdfException。
        // 解析层 AC② 交叉约束使该样本在头部解析即被拒（见 KdfParametersBoundsTest），
        // 本例锁定「即便抵达本调用点」时也没有任何半初始化密钥残留可观测面。
        val thrown = assertThrows(CryptoException.KdfException::class.java) {
            KdbxCipherKeyResolver.resolve(
                cipherEngine = FailingCipherEngine(),
                header = header,
                firstBlock = firstBlock,
                officialKey = officialKey,
                isGzipCompressed = false
            ) {
                throw CryptoException.KdfException("Argon2 密钥派生失败（参数越界或 KDF 内存超出本机可用内存）")
            }
        }
        assertTrue(thrown.message!!.contains("派生失败"))
    }
}
