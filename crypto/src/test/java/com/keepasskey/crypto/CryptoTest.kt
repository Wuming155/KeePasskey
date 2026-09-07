package com.keepasskey.crypto

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.crypto.cipher.AesCipherEngine
import com.keepasskey.crypto.cipher.ChaCha20CipherEngine
import com.keepasskey.crypto.cipher.CipherFactory
import com.keepasskey.crypto.cipher.TwofishCipherEngine
import com.keepasskey.crypto.hash.HashUtil
import com.keepasskey.crypto.kdf.AesKdfEngine
import com.keepasskey.crypto.kdf.Argon2KdfEngine
import com.keepasskey.crypto.kdf.KdfFactory
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class CryptoTest {

    @Test
    fun testSha256StandardVector() {
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        val input = "abc".toByteArray(StandardCharsets.UTF_8)
        val hash = HashUtil.sha256(input)
        val hex = hash.joinToString("") { "%02x".format(it) }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex)
    }

    @Test
    fun testHmacSha256() {
        val key = "secret-key".toByteArray(StandardCharsets.UTF_8)
        val data = "hello world".toByteArray(StandardCharsets.UTF_8)
        val mac1 = HashUtil.hmacSha256(key, data)
        val mac2 = HashUtil.hmacSha256(key, data)
        assertEquals(32, mac1.size)
        assertArrayEquals(mac1, mac2)
    }

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** RFC 4231 Test Case 1：HMAC-SHA256/512 已知答案 */
    @Test
    fun testHmacRfc4231TestCase1() {
        val key = ByteArray(20) { 0x0b }
        val data = "Hi There".toByteArray(StandardCharsets.US_ASCII)
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            toHex(HashUtil.hmacSha256(key, data))
        )
        assertEquals(
            "87aa7cdea5ef619d4ff0b4241a1d6cb02379f4e2ce4ec2787ad0b30545e17cdedaa833b7d6b8a702038b274eaea3f4e4be9d914eeb61f1702e696c203a126854",
            toHex(HashUtil.hmacSha512(key, data))
        )
    }

    /** RFC 4231 Test Case 2：HMAC-SHA256/512 已知答案 */
    @Test
    fun testHmacRfc4231TestCase2() {
        val key = "Jefe".toByteArray(StandardCharsets.US_ASCII)
        val data = "what do ya want for nothing?".toByteArray(StandardCharsets.US_ASCII)
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            toHex(HashUtil.hmacSha256(key, data))
        )
        assertEquals(
            "164b7a7bfcf819e2e395fbe73b56e0a387bd64222e831fd610270cd7ea2505549758bf75c05a994a6d034f65f8f0e6fdcaeab1a34d4a6b4b636e070a38bce737",
            toHex(HashUtil.hmacSha512(key, data))
        )
    }

    /** RFC 4231 Test Case 6：超过分组长度（64 字节）的 131 字节长密钥，锁定 RFC 2104「先哈希密钥」语义 */
    @Test
    fun testHmacRfc4231TestCase6LongKey() {
        val key = ByteArray(131) { 0xaa.toByte() }
        val data = "Test Using Larger Than Block-Size Key - Hash Key First".toByteArray(StandardCharsets.US_ASCII)
        assertEquals(
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            toHex(HashUtil.hmacSha256(key, data))
        )
        assertEquals(
            "80b24263c7c1a3ebb71493c1dd7be8b49b46d1f41b4aeec1121b013783f8f3526b56d037e05f2598bd0fd2215d6a1e5295e64f73f63f0aec8b915a985d786598",
            toHex(HashUtil.hmacSha512(key, data))
        )
    }

    /** vararg 分块 update 与单数组一次性计算结果一致（KDBX HMAC 块流依赖分块路径） */
    @Test
    fun testHmacVarargChunksConsistency() {
        val key = ByteArray(32) { (it * 7).toByte() }
        val chunkA = "KeePasskey".toByteArray(StandardCharsets.UTF_8)
        val chunkB = ByteArray(1000) { (it % 251).toByte() }
        val chunkC = "HMAC".toByteArray(StandardCharsets.UTF_8)
        val merged = chunkA + chunkB + chunkC
        assertArrayEquals(HashUtil.hmacSha256(key, merged), HashUtil.hmacSha256(key, chunkA, chunkB, chunkC))
    }

    /** 输出长度：SHA256=32 字节、SHA512=64 字节 */
    @Test
    fun testHmacOutputLength() {
        val key = ByteArray(16) { 0x11 }
        val data = "length".toByteArray(StandardCharsets.UTF_8)
        assertEquals(32, HashUtil.hmacSha256(key, data).size)
        assertEquals(64, HashUtil.hmacSha512(key, data).size)
    }

    @Test
    fun testAesCipherRoundtrip() {
        val engine = AesCipherEngine()
        val key = ByteArray(32) { it.toByte() }
        val iv = ByteArray(16) { (it * 2).toByte() }
        val plaintext = "KeePasskey AES-256 secure database encryption test vector".toByteArray(StandardCharsets.UTF_8)

        val encrypted = engine.encrypt(key, iv, plaintext)
        val decrypted = engine.decrypt(key, iv, encrypted)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun testChaCha20CipherRoundtrip() {
        val engine = ChaCha20CipherEngine()
        val key = ByteArray(32) { (it + 5).toByte() }
        // RFC 7539 / KDBX4：ChaCha20 nonce 恒为 12 字节（引擎已按官方规范硬校验）
        val iv = ByteArray(12) { it.toByte() }
        val plaintext = "ChaCha20 RFC 7539 KeePass v4 encryption test".toByteArray(StandardCharsets.UTF_8)

        val encrypted = engine.encrypt(key, iv, plaintext)
        val decrypted = engine.decrypt(key, iv, encrypted)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun testTwofishCipherRoundtrip() {
        val engine = TwofishCipherEngine()
        val key = ByteArray(32) { (it + 10).toByte() }
        val iv = ByteArray(16) { (it + 3).toByte() }
        val plaintext = "Twofish CBC PKCS7Padding encryption test".toByteArray(StandardCharsets.UTF_8)

        val encrypted = engine.encrypt(key, iv, plaintext)
        val decrypted = engine.decrypt(key, iv, encrypted)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun testCipherFactory() {
        val aes = CipherFactory.getEngine(KdbxConstants.Cipher.AES_256_CBC)
        val chacha = CipherFactory.getEngine(KdbxConstants.Cipher.CHACHA20)
        val twofish = CipherFactory.getEngine(KdbxConstants.Cipher.TWOFISH)

        assertNotNull(aes)
        assertNotNull(chacha)
        assertNotNull(twofish)
    }

    @Test
    fun testAesKdf() {
        val engine = AesKdfEngine()
        val compositeKey = ByteArray(32) { 1 }
        val seed = ByteArray(32) { 2 }
        val params = KdfParameters.Aes(seed = seed, rounds = 100L)

        val derived1 = engine.transform(compositeKey, params)
        val derived2 = engine.transform(compositeKey, params)

        assertEquals(32, derived1.size)
        assertArrayEquals(derived1, derived2)
    }

    @Test
    fun testArgon2idKdf() {
        val engine = Argon2KdfEngine(KdfParameters.Argon2.Argon2Type.ARGON2ID)
        val compositeKey = ByteArray(32) { 0x55.toByte() }
        val salt = ByteArray(32) { 0xAA.toByte() }
        val params = KdfParameters.Argon2(
            type = KdfParameters.Argon2.Argon2Type.ARGON2ID,
            salt = salt,
            parallelism = 1,
            memoryInBytes = 1024L * 1024L, // 1 MB for fast test
            iterations = 2L
        )

        val derived1 = engine.transform(compositeKey, params)
        val derived2 = engine.transform(compositeKey, params)

        assertEquals(32, derived1.size)
        assertArrayEquals(derived1, derived2)
    }

    @Test
    fun testArgon2dKdf() {
        val engine = Argon2KdfEngine(KdfParameters.Argon2.Argon2Type.ARGON2D)
        val compositeKey = ByteArray(32) { 0x12.toByte() }
        val salt = ByteArray(32) { 0x34.toByte() }
        val params = KdfParameters.Argon2(
            type = KdfParameters.Argon2.Argon2Type.ARGON2D,
            salt = salt,
            parallelism = 1,
            memoryInBytes = 1024L * 1024L,
            iterations = 2L
        )

        val derived1 = engine.transform(compositeKey, params)
        assertEquals(32, derived1.size)
    }

    @Test
    fun testKdfFactory() {
        val aesKdf = KdfFactory.getEngine(KdbxConstants.Kdf.AES_KDF)
        val argon2d = KdfFactory.getEngine(KdbxConstants.Kdf.ARGON2D)
        val argon2id = KdfFactory.getEngine(KdbxConstants.Kdf.ARGON2ID)

        assertNotNull(aesKdf)
        assertNotNull(argon2d)
        assertNotNull(argon2id)
    }

    @Test
    fun testInnerRandomStreamSymmetry() {
        val key = ByteArray(64) { (it * 3).toByte() }
        val stream = InnerRandomStreamCipher(KdbxConstants.InnerRandomStream.CHACHA20, key)
        val data = "MySecretPassword123!".toByteArray(StandardCharsets.UTF_8)

        val encrypted = stream.processBytes(data)
        // Reset stream to decrypt with same key
        val decryptStream = InnerRandomStreamCipher(KdbxConstants.InnerRandomStream.CHACHA20, key)
        val decrypted = decryptStream.processBytes(encrypted)

        assertArrayEquals(data, decrypted)
    }
    /**
     * P0-4 回归锁：nonce 长度非 12 字节必须立即失败（IllegalArgumentException）。
     * 旧行为（>12 字节静默截断为前 12 字节）曾掩盖 KdbxFile 恒写 16 字节 IV 的上游缺陷，
     * 产出官方客户端打不开的文件——若回退到静默截断，本用例必须失败。
     */
    @Test
    fun testChaCha20RejectsNon12ByteNonce() {
        val engine = ChaCha20CipherEngine()
        val key = ByteArray(32) { (it + 5).toByte() }
        val data = "nonce length validation".toByteArray(StandardCharsets.UTF_8)

        for (badNonce in listOf(ByteArray(16) { it.toByte() }, ByteArray(8) { it.toByte() })) {
            try {
                engine.encrypt(key, badNonce, data)
                org.junit.Assert.fail("nonce 为 ${badNonce.size} 字节时 encrypt 必须抛出 IllegalArgumentException")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message?.contains("12") == true)
                assertTrue(expected.message?.contains("${badNonce.size}") == true)
            }
            try {
                engine.decrypt(key, badNonce, data)
                org.junit.Assert.fail("nonce 为 ${badNonce.size} 字节时 decrypt 必须抛出 IllegalArgumentException")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message?.contains("${badNonce.size}") == true)
            }
        }

        // 流式入口（保存/读取管线实际使用的路径）
        try {
            engine.createEncryptingStream(java.io.ByteArrayOutputStream(), key, ByteArray(16) { it.toByte() })
            org.junit.Assert.fail("nonce 为 16 字节时 createEncryptingStream 必须抛出 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("16") == true)
        }
        try {
            engine.createDecryptingStream(java.io.ByteArrayInputStream(ByteArray(32)), key, ByteArray(16) { it.toByte() })
            org.junit.Assert.fail("nonce 为 16 字节时 createDecryptingStream 必须抛出 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("16") == true)
        }
    }

    /**
     * P3-1 回归锁：InnerRandomStreamID = 0 (None) 为合法流类型（无内层加密）——
     * processBytes 直通透传（返回输入副本）、不消费任何密钥流状态。
     */
    @Test
    fun testInnerRandomStreamNonePassthrough() {
        val key = ByteArray(64) { (it * 3).toByte() }
        val stream = InnerRandomStreamCipher(KdbxConstants.InnerRandomStream.NONE, key)
        val data = "PlainProtectedValue!123".toByteArray(StandardCharsets.UTF_8)

        // 直通：输出等于输入（内容拷贝，非同一实例）
        val output = stream.processBytes(data)
        assertArrayEquals(data, output)
        assertTrue(output !== data)

        // 无状态：连续两次变换均返回原文（真实流密码第二次会得到乱码）
        val output2 = stream.processBytes(data)
        assertArrayEquals(data, output2)

        // 密钥流恒为零（受保护字段 Base64 解码后即明文，无需 XOR）
        val randomBytes = stream.getRandomBytes(16)
        assertArrayEquals(ByteArray(16), randomBytes)
    }
}
