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
        val iv = ByteArray(16) { it.toByte() }
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
}
