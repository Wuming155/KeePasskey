package com.keepasskey.crypto.cipher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * **兜底分支常态化回归**（§147 追问的后续整改，2026-09-18）。
 *
 * **存在理由**：三个引擎的兜底路径（JCE / BouncyCastle）原本**只在「原生库不可用」时才执行**
 * ——即最需要它正确的时刻，恰是它最缺回归的时刻；而原生可用时（CI 装了 cargo、本地开发机、
 * 全部真机）宿主与设备侧用例**一律走原生**，兜底分支几乎无常态覆盖。
 * 本类经 `internal constructor(forceBcFallback = true)` 强制走兜底，把「两条路径逐字节等价」
 * 从**注释里的承诺**变成**每次构建都执行的断言**。
 *
 * **两类断言**：
 * 1. **无条件运行**：兜底路径自身的整型 / 流式往返正确（不依赖原生是否存在）；
 * 2. **原生可用时**（`Assume`）：兜底产物与**原生产物逐字节一致**——这是「两路径等价」的
 *    直接证据；原生缺失时该组诚实跳过（因为不存在可比对象），不影响第 1 类。
 *
 * 输入为固定合成数据（非真实凭据），长度覆盖空 / 单字节 / 跨分组 / 跨 64 KiB 流缓冲边界。
 */
class CipherFallbackParityTest {

    private val aesKey = keyOf(32, 3)
    private val aesIv = ivOf(16, 1)
    private val twofishKey = keyOf(32, 5)
    private val twofishIv = ivOf(16, 2)
    private val chachaKey = keyOf(32, 7)
    private val chachaNonce = ivOf(12, 3)

    /** 覆盖空、单字节、跨分组边界与跨流缓冲边界（64 KiB）的样本长度。 */
    private val lengths = listOf(0, 1, 15, 16, 17, 1000, 65537)

    // ==================== AES-256-CBC ====================

    @Test
    fun `AES 兜底路径整型与流式往返正确`() {
        val engine = AesCipherEngine(forceJceFallback = true)
        for (len in lengths) {
            val plain = plainOf(len)
            val cipherText = engine.encrypt(aesKey, aesIv, plain)
            assertArrayEquals("len=$len 兜底整型往返失败", plain, engine.decrypt(aesKey, aesIv, cipherText))

            val sink = ByteArrayOutputStream()
            engine.createEncryptingStream(sink, aesKey, aesIv).use { it.write(plain) }
            val restored = ByteArrayOutputStream()
            engine.createDecryptingStream(ByteArrayInputStream(sink.toByteArray()), aesKey, aesIv).use {
                it.copyTo(restored)
            }
            assertArrayEquals("len=$len 兜底流式往返失败", plain, restored.toByteArray())
        }
    }

    @Test
    fun `AES 兜底与原生路径逐字节一致`() {
        Assume.assumeTrue("原生内核不可用（未构建宿主库），无可比对象，跳过等价断言", NativeAes.available)
        val fallback = AesCipherEngine(forceJceFallback = true)
        val native = AesCipherEngine()
        for (len in lengths) {
            val plain = plainOf(len)
            assertArrayEquals(
                "len=$len 兜底整型密文与原生不一致",
                native.encrypt(aesKey, aesIv, plain),
                fallback.encrypt(aesKey, aesIv, plain)
            )

            val fallbackSink = ByteArrayOutputStream()
            fallback.createEncryptingStream(fallbackSink, aesKey, aesIv).use { it.write(plain) }
            val nativeSink = ByteArrayOutputStream()
            native.createEncryptingStream(nativeSink, aesKey, aesIv).use { it.write(plain) }
            assertArrayEquals(
                "len=$len 兜底流式密文与原生不一致",
                nativeSink.toByteArray(),
                fallbackSink.toByteArray()
            )
        }
    }

    @Test
    fun `AES 非 32 字节密钥在兜底路径同样 fail-closed`() {
        // 闸门在四条入口、两条路径同口径（消除「原生拒 16/24 字节、兜底静默按 AES-128/192 加密」的分歧）。
        // 该断言不依赖原生是否存在：兜底路径亦须拒绝。
        val engine = AesCipherEngine(forceJceFallback = true)
        for (badLen in listOf(0, 15, 16, 24, 31, 33)) {
            val badKey = ByteArray(badLen)
            assertTrue(
                "兜底路径对 $badLen 字节密钥必须抛异常",
                runCatching { engine.encrypt(badKey, aesIv, plainOf(16)) }.exceptionOrNull() != null
            )
            assertTrue(
                "兜底路径流式对 $badLen 字节密钥必须抛异常",
                runCatching { engine.createEncryptingStream(ByteArrayOutputStream(), badKey, aesIv) }
                    .exceptionOrNull() != null
            )
        }
    }

    // ==================== Twofish-CBC ====================

    @Test
    fun `Twofish 兜底路径整型与流式往返正确`() {
        val engine = TwofishCipherEngine(forceBcFallback = true)
        for (len in lengths) {
            val plain = plainOf(len)
            val cipherText = engine.encrypt(twofishKey, twofishIv, plain)
            assertArrayEquals(
                "len=$len 兜底整型往返失败",
                plain,
                engine.decrypt(twofishKey, twofishIv, cipherText)
            )

            val sink = ByteArrayOutputStream()
            engine.createEncryptingStream(sink, twofishKey, twofishIv).use { it.write(plain) }
            val restored = ByteArrayOutputStream()
            engine.createDecryptingStream(ByteArrayInputStream(sink.toByteArray()), twofishKey, twofishIv).use {
                it.copyTo(restored)
            }
            assertArrayEquals("len=$len 兜底流式往返失败", plain, restored.toByteArray())
        }
    }

    @Test
    fun `Twofish 兜底与原生路径逐字节一致`() {
        Assume.assumeTrue("原生内核不可用（未构建宿主库），无可比对象，跳过等价断言", NativeTwofish.available)
        val fallback = TwofishCipherEngine(forceBcFallback = true)
        val native = TwofishCipherEngine()
        for (len in lengths) {
            val plain = plainOf(len)
            assertArrayEquals(
                "len=$len 兜底整型密文与原生不一致",
                native.encrypt(twofishKey, twofishIv, plain),
                fallback.encrypt(twofishKey, twofishIv, plain)
            )

            val fallbackSink = ByteArrayOutputStream()
            fallback.createEncryptingStream(fallbackSink, twofishKey, twofishIv).use { it.write(plain) }
            val nativeSink = ByteArrayOutputStream()
            native.createEncryptingStream(nativeSink, twofishKey, twofishIv).use { it.write(plain) }
            assertArrayEquals(
                "len=$len 兜底流式密文与原生不一致",
                nativeSink.toByteArray(),
                fallbackSink.toByteArray()
            )
        }
    }

    // ==================== ChaCha20 ====================

    @Test
    fun `ChaCha20 兜底路径整型与流式往返正确`() {
        val engine = ChaCha20CipherEngine(forceBcFallback = true)
        for (len in lengths) {
            val plain = plainOf(len)
            val cipherText = engine.encrypt(chachaKey, chachaNonce, plain)
            assertArrayEquals(
                "len=$len 兜底整型往返失败",
                plain,
                engine.decrypt(chachaKey, chachaNonce, cipherText)
            )

            val sink = ByteArrayOutputStream()
            engine.createEncryptingStream(sink, chachaKey, chachaNonce).use { it.write(plain) }
            val restored = ByteArrayOutputStream()
            engine.createDecryptingStream(ByteArrayInputStream(sink.toByteArray()), chachaKey, chachaNonce).use {
                it.copyTo(restored)
            }
            assertArrayEquals("len=$len 兜底流式往返失败", plain, restored.toByteArray())
        }
    }

    @Test
    fun `ChaCha20 兜底与原生路径逐字节一致`() {
        Assume.assumeTrue("原生内核不可用（未构建宿主库），无可比对象，跳过等价断言", NativeChaCha20.available)
        val fallback = ChaCha20CipherEngine(forceBcFallback = true)
        val native = ChaCha20CipherEngine()
        for (len in lengths) {
            val plain = plainOf(len)
            assertArrayEquals(
                "len=$len 兜底整型密文与原生不一致",
                native.encrypt(chachaKey, chachaNonce, plain),
                fallback.encrypt(chachaKey, chachaNonce, plain)
            )

            val fallbackSink = ByteArrayOutputStream()
            fallback.createEncryptingStream(fallbackSink, chachaKey, chachaNonce).use { it.write(plain) }
            val nativeSink = ByteArrayOutputStream()
            native.createEncryptingStream(nativeSink, chachaKey, chachaNonce).use { it.write(plain) }
            assertArrayEquals(
                "len=$len 兜底流式密文与原生不一致",
                nativeSink.toByteArray(),
                fallbackSink.toByteArray()
            )
        }
    }

    // ==================== 样本构造 ====================

    private fun keyOf(len: Int, salt: Int): ByteArray =
        ByteArray(len) { ((it * 7 + salt) and 0xFF).toByte() }

    private fun ivOf(len: Int, salt: Int): ByteArray =
        ByteArray(len) { ((it * 11 + salt) and 0xFF).toByte() }

    private fun plainOf(len: Int): ByteArray =
        ByteArray(len) { ((it * 31 + 5) and 0xFF).toByte() }
}
