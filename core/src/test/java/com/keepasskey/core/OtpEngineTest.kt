package com.keepasskey.core.otp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OtpEngine 单元测试（TASK-46 ByteArray 化改造后回归）：
 * - RFC 4226 HOTP 官方测试向量（Appendix D 全量 10 组）
 * - RFC 6238 TOTP 官方测试向量（Appendix B：SHA-1 / SHA-256 / SHA-512，8 位码全量 6 组）
 * - RFC 4648 Base32 官方测试向量（§10）
 * - TASK-46 敏感数据铁律断言：引擎不篡改调用方密钥字节、种子用毕 `fill(0)` 擦除后
 *   重解码重算结果一致（无内部缓存驻留）、解码产物为调用方独占新数组
 */
class OtpEngineTest {

    // RFC 4226 / 6238 官方测试密钥（ASCII "12345678901234567890"，20 字节）
    private val rfcSecretBytes = "12345678901234567890".toByteArray(Charsets.US_ASCII)

    // RFC 6238 官方 SHA-256 测试密钥（ASCII，32 字节）
    private val rfcSecretSha256 = "12345678901234567890123456789012".toByteArray(Charsets.US_ASCII)

    // RFC 6238 官方 SHA-512 测试密钥（ASCII，64 字节）
    private val rfcSecretSha512 =
        "1234567890123456789012345678901234567890123456789012345678901234".toByteArray(Charsets.US_ASCII)

    // RFC 6238 官方 SHA-1 密钥的 Base32 编码
    private val rfcSecretBase32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test
    fun `测试 HOTP RFC 4226 官方向量全量验证`() {
        // RFC 4226 Appendix D：6 位十进制 HOTP 值（counter 0..9）
        val expected = listOf(
            "755224", "287082", "359152", "969429", "338314",
            "254676", "287922", "162583", "399871", "520489"
        )
        expected.forEachIndexed { counter, code ->
            assertEquals(code, OtpEngine.calculateHotp(rfcSecretBytes, counter.toLong(), digits = 6))
        }
    }

    @Test
    fun `测试 TOTP RFC 6238 SHA-1 官方向量`() {
        // RFC 6238 Appendix B：T（秒）→ 8 位 TOTP（SHA-1）
        val vectors = mapOf(
            59L to "94287082",
            1111111109L to "07081804",
            1111111111L to "14050471",
            1234567890L to "89005924",
            2000000000L to "69279037",
            20000000000L to "65353130"
        )
        vectors.forEach { (t, code) ->
            assertEquals(code, OtpEngine.calculateTotp(rfcSecretBytes, timestampMillis = t * 1000L, digits = 8))
        }
    }

    @Test
    fun `测试 TOTP RFC 6238 SHA-256 官方向量`() {
        val vectors = mapOf(
            59L to "46119246",
            1111111109L to "68084774",
            1111111111L to "67062674",
            1234567890L to "91819424",
            2000000000L to "90698825",
            20000000000L to "77737706"
        )
        vectors.forEach { (t, code) ->
            assertEquals(
                code,
                OtpEngine.calculateTotp(
                    rfcSecretSha256, timestampMillis = t * 1000L, digits = 8,
                    algorithm = OtpEngine.HashAlgorithm.SHA256
                )
            )
        }
    }

    @Test
    fun `测试 TOTP RFC 6238 SHA-512 官方向量`() {
        val vectors = mapOf(
            59L to "90693936",
            1111111109L to "25091201",
            1111111111L to "99943326",
            1234567890L to "93441116",
            2000000000L to "38618901",
            20000000000L to "47863826"
        )
        vectors.forEach { (t, code) ->
            assertEquals(
                code,
                OtpEngine.calculateTotp(
                    rfcSecretSha512, timestampMillis = t * 1000L, digits = 8,
                    algorithm = OtpEngine.HashAlgorithm.SHA512
                )
            )
        }
    }

    @Test
    fun `测试 TOTP Base32 解码路径与 6 位数字格式`() {
        // RFC 6238 官方密钥 Base32 编码；t=59s、步长 30s → counter=1 → HOTP 6 位截断 287082
        val secretBytes = Base32Decoder.decode(rfcSecretBase32)
        try {
            val code = OtpEngine.calculateTotp(secretBytes, timestampMillis = 59_000L)
            assertEquals("287082", code)
            assertEquals(6, code.length)
            assertTrue(code.all { it.isDigit() })
        } finally {
            // TASK-46：解码产物用毕显式擦除（借用语义）
            secretBytes.fill(0)
        }
    }

    @Test
    fun `测试引擎不篡改调用方密钥字节`() {
        // 契约：OtpEngine 不持有、不复制、不篡改调用方密钥（擦除时机完全由调用方掌控）
        val secretBytes = rfcSecretBytes.copyOf()
        OtpEngine.calculateTotp(secretBytes, timestampMillis = 59_000L)
        OtpEngine.calculateHotp(secretBytes, counter = 3L)
        assertArrayEquals(rfcSecretBytes, secretBytes)
    }

    @Test
    fun `测试种子用毕擦除后重解码重算结果一致`() {
        // 断言 OtpEngine / Base32Decoder 无内部密钥缓存驻留：
        // 调用方按借用语义 fill(0) 擦除解码产物后，重新解码计算结果不变
        val first = Base32Decoder.decode(rfcSecretBase32)
        val code1 = OtpEngine.calculateHotp(first, counter = 1L)
        first.fill(0)
        assertTrue(first.all { it == 0.toByte() })

        val second = Base32Decoder.decode(rfcSecretBase32)
        try {
            val code2 = OtpEngine.calculateHotp(second, counter = 1L)
            assertEquals(code1, code2)
            assertEquals("287082", code2)
        } finally {
            second.fill(0)
        }
    }

    @Test
    fun `测试 Base32 RFC 4648 官方向量`() {
        // RFC 4648 §10 测试向量（解码器宽容剥离 `=` 填充）
        val vectors = mapOf(
            "" to "",
            "MY======" to "f",
            "MZXQ====" to "fo",
            "MZXW6===" to "foo",
            "MZXW6YQ=" to "foob",
            "MZXW6YTB" to "fooba",
            "MZXW6YTBOI======" to "foobar"
        )
        vectors.forEach { (base32, expected) ->
            assertArrayEquals(
                expected.toByteArray(Charsets.US_ASCII),
                Base32Decoder.decode(base32)
            )
        }
    }

    @Test
    fun `测试 Base32 解码返回调用方独占新数组`() {
        // 借用语义：每次解码返回全新数组，无共享/缓存实例（调用方擦除互不干扰）
        val first = Base32Decoder.decode("MZXW6YTB")
        val second = Base32Decoder.decode("MZXW6YTB")
        assertNotSame(first, second)
        assertArrayEquals(first, second)
    }
}
