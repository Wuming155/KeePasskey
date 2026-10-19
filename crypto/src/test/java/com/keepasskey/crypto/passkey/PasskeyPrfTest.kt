package com.keepasskey.crypto.passkey

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * WebAuthn PRF 扩展（`prf`）取值单测。
 *
 * 本仓此前**完全未实现 PRF**（`clientExtensionResults` 恒为空对象），依赖 prf 的站点
 * （如以 prf 输出派生凭据加密密钥的场景）无法使用通行密钥。本文件锁定：
 * 1. 客户端侧处理 = `SHA-256("WebAuthn PRF" || 0x00 || input)`（独立实现交叉验证）；
 * 2. 取值 = `HMAC-SHA-256(secret, 客户端侧处理结果)`（与 KeePassDX 同口径，用 JDK Mac 独立复算）；
 * 3. 同输入恒同输出（确定性），秘密文本非法一律 fail-closed。
 */
class PasskeyPrfTest {

    @Test
    fun `生成 32 字节秘密且隐藏文本可被还原`() {
        val secret = PasskeyPrf.newSecretProtected()
        val bytes = PasskeyPrf.secretBytesForTest(secret)
        try {
            assertEquals(PasskeyPrf.SECRET_BYTES, bytes.size)
        } finally {
            bytes.fill(0)
        }
    }

    @Test
    fun `客户端侧处理等价于规范定义的域分隔哈希`() {
        val input = ByteArray(32) { (it + 7).toByte() }
        val expected = MessageDigest.getInstance("SHA-256").digest(
            "WebAuthn PRF".toByteArray(Charsets.UTF_8) + byteArrayOf(0x00) + input
        )
        assertArrayEquals(expected, PasskeyPrf.clientSideProcess(input))
    }

    @Test
    fun `PRF 取值等价于 HMAC-SHA-256 秘密对域分隔盐`() {
        val secretBytes = ByteArray(PasskeyPrf.SECRET_BYTES) { (it * 3 + 1).toByte() }
        val secret = PasskeyPrf.secretFromBytesForTest(secretBytes)
        val evalInput = "user-salt".toByteArray(Charsets.UTF_8)

        val expected = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(secretBytes, "HmacSHA256"))
        }.doFinal(PasskeyPrf.clientSideProcess(evalInput))

        val actual = PasskeyPrf.computeValue(secret, evalInput)
        assertArrayEquals(expected, actual)
        assertEquals(32, actual.size)

        // 确定性：同输入两次取值必须一致（RP 侧据此稳定派生密钥）
        assertArrayEquals(actual, PasskeyPrf.computeValue(secret, evalInput))
    }

    @Test
    fun `不同输入的取值互不相同`() {
        val secret = PasskeyPrf.newSecretProtected()
        val first = PasskeyPrf.computeValue(secret, ByteArray(32) { 1 })
        val second = PasskeyPrf.computeValue(secret, ByteArray(32) { 2 })
        assertTrue("不同 eval 输入必须得到不同 PRF 输出", !first.contentEquals(second))
    }

    @Test
    fun `秘密文本非法时 fail-closed 抛出`() {
        val notBase64 = PasskeyPrf.secretFromBytesForTest(ByteArray(PasskeyPrf.SECRET_BYTES))
        val broken = com.keepasskey.core.security.ProtectedString("!!!not-base64!!!", isProtected = true)
        assertThrows(IllegalArgumentException::class.java) {
            PasskeyPrf.computeValue(broken, ByteArray(32))
        }
        assertThrows(IllegalArgumentException::class.java) {
            // 合法 Base64 但长度不足 32 字节
            PasskeyPrf.computeValue(
                com.keepasskey.core.security.ProtectedString(
                    Base64.getEncoder().encodeToString(ByteArray(16)),
                    isProtected = true
                ),
                ByteArray(32)
            )
        }
        // 正常秘密不受影响
        assertArrayEquals(
            PasskeyPrf.computeValue(notBase64, ByteArray(32) { 5 }),
            PasskeyPrf.computeValue(notBase64, ByteArray(32) { 5 })
        )
    }

    @Test
    fun `PRF Base64字符工作副本清零且受保护输出仍可还原`() {
        val secretBytes = ByteArray(PasskeyPrf.SECRET_BYTES) { (it * 5 + 3).toByte() }
        var temporary: CharArray? = null
        val protected = PasskeyPrf.secretToProtectedForTest(secretBytes) { temporary = it }

        assertTrue("Base64字符临时数组应可观测", temporary != null)
        assertTrue("构造完成后字符数组必须清零", temporary!!.all { it == '\u0000' })
        val restored = PasskeyPrf.secretBytesForTest(protected)
        try {
            assertArrayEquals("交付给 ProtectedString 的内容仍须有效", secretBytes, restored)
        } finally {
            restored.fill(0)
            secretBytes.fill(0)
            protected.close()
        }
    }

    @Test
    fun `Base64空白缩短旧数组清零且独立解码副本有效`() {
        val source = "QUJD\n REVG\t".toByteArray(Charsets.US_ASCII)
        var work: ByteArray? = null
        val compact = PasskeyPrf.stripWhitespaceForTest(source) { work = it }
        try {
            assertTrue("含空白应触发缩短并观察旧工作数组", work != null)
            assertArrayEquals(ByteArray(work!!.size), work)
            assertArrayEquals("QUJDREVG".toByteArray(Charsets.US_ASCII), compact)
            assertArrayEquals("输入是借用缓冲，不能被改写", "QUJD\n REVG\t".toByteArray(Charsets.US_ASCII), source)
        } finally {
            compact.fill(0)
        }
    }
}
