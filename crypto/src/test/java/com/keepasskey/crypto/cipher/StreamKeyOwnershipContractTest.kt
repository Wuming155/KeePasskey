package com.keepasskey.crypto.cipher

import com.keepasskey.core.model.KdbxConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Arrays

/**
 * 「**创建流即捕获密钥材料**」契约的三引擎回归用例（§147 整改）。
 *
 * **背景（真实回归）**：`KdbxFile` / `KdbxCipherKeyResolver` 在建立解密流之后**立即擦除**
 * 调用方持有的旧派生密钥数组（见该类 KDoc 的擦除假设）。该假设对 JCE 路径成立
 * （`SecretKeySpec` 构造时克隆密钥），但**原生路径的分组变换是惰性读取调用方数组**——
 * 擦除后即以全零密钥解密，实测表现为末块 PKCS#7 校验失败 → `IOException`，
 * 使 `KdbxCompatibilityAndSecurityTest#testLegacyCipherKeyFallbackAndAutoMigration` 变红。
 *
 * 本用例把该时序固化为**契约**：三种 cipher 在「建立流 → 擦除调用方 key/iv → 读写流」的
 * 时序下都必须正确往返。任何把原生流改回「直接引用调用方数组」的改动都会在此变红。
 */
class StreamKeyOwnershipContractTest {

    private val cases = listOf(
        Triple("AES-256-CBC", KdbxConstants.Cipher.AES_256_CBC, 16),
        Triple("ChaCha20", KdbxConstants.Cipher.CHACHA20, 12),
        Triple("Twofish", KdbxConstants.Cipher.TWOFISH, 16)
    )

    @Test
    fun `建立流后擦除调用方密钥_三条路径仍须正确往返`() {
        assumeTrue(
            "宿主原生库未注入（Assume 跳过；真机由各 *DeviceTest 硬断言补位）",
            NativeAes.available && NativeChaCha20.available && NativeTwofish.available
        )

        cases.forEach { (label, uuid, ivLength) ->
            val engine = CipherFactory.getEngine(uuid)
            val plain = ByteArray(20_000) { ((it * 31 + 7) and 0xFF).toByte() }

            // ① 加密：建流后**立即**擦除调用方 key/iv（时序必须与 KdbxFile 一致，否则测不到契约）
            val keyForEnc = ByteArray(32) { ((it * 13 + 3) and 0xFF).toByte() }
            val ivForEnc = ByteArray(ivLength) { ((it * 7 + 1) and 0xFF).toByte() }
            val cipherOut = ByteArrayOutputStream()
            val encStream = engine.createEncryptingStream(cipherOut, keyForEnc, ivForEnc)
            Arrays.fill(keyForEnc, 0)
            Arrays.fill(ivForEnc, 0)
            encStream.use { it.write(plain) }
            val cipherBytes = cipherOut.toByteArray()
            assertTrue("[$label] 密文不得为空", cipherBytes.isNotEmpty())

            // ② 解密：同样在建流后**立即**擦除调用方数组，再读取
            val keyForDec = ByteArray(32) { ((it * 13 + 3) and 0xFF).toByte() }
            val ivForDec = ByteArray(ivLength) { ((it * 7 + 1) and 0xFF).toByte() }
            val restored = ByteArrayOutputStream()
            val decStream = engine.createDecryptingStream(ByteArrayInputStream(cipherBytes), keyForDec, ivForDec)
            Arrays.fill(keyForDec, 0)
            Arrays.fill(ivForDec, 0)
            decStream.use { it.copyTo(restored) }

            val restoredBytes = restored.toByteArray()
            try {
                assertArrayEquals("[$label] 擦除调用方密钥后流必须仍能正确解密", plain, restoredBytes)
            } finally {
                restoredBytes.fill(0)
                cipherBytes.fill(0)
                plain.fill(0)
            }
        }
    }
}
