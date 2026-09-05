package com.keepasskey.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 整改回归：ProtectedString 内存加密驻留。
 * 验证：密文驻留不泄露明文、密封/解封往返一致、确定性映射保持 equals/hashCode 语义、
 * 清零后访问硬拒绝、非保护路径行为不变。
 */
class ProtectedStringMemoryEncryptionTest {

    private val secret = "P@ssw0rd-密码-🔐-TOTP/seed+私钥"

    // ================= InMemoryCipher 原语 =================

    @Test
    fun `密封产物不等于明文且长度保持`() {
        val plain = secret.toByteArray(Charsets.UTF_8)
        val sealed = InMemoryCipher.seal(plain)
        assertFalse("密文不得等于明文", sealed.data.contentEquals(plain))
        assertEquals("CTR 无填充：密文长度必须等于明文长度", plain.size, sealed.data.size)
        assertEquals("IV 长度必须为 16 字节", 16, sealed.iv.size)
    }

    @Test
    fun `密封与解封往返一致`() {
        for (sample in listOf(
            secret,
            "",
            "a",
            "多字节文本Τێχτ🚀",
            "x".repeat(4096)
        )) {
            val plain = sample.toByteArray(Charsets.UTF_8)
            val sealed = InMemoryCipher.seal(plain)
            val restored = InMemoryCipher.unseal(sealed.iv, sealed.data)
            assertArrayEquals("往返必须还原明文: ${sample.take(16)}", plain, restored)
        }
    }

    @Test
    fun `密封是确定性的-相同明文恒得相同产物`() {
        val plain = secret.toByteArray(Charsets.UTF_8)
        val first = InMemoryCipher.seal(plain)
        val second = InMemoryCipher.seal(plain)
        assertArrayEquals(first.iv, second.iv)
        assertArrayEquals(first.data, second.data)
    }

    @Test
    fun `不同明文产生不同 IV`() {
        val first = InMemoryCipher.seal("password-A".toByteArray())
        val second = InMemoryCipher.seal("password-B".toByteArray())
        assertFalse("不同明文必须使用不同密钥流", first.iv.contentEquals(second.iv))
    }

    // ================= ProtectedString 驻留语义 =================

    @Test
    fun `受保护值读取路径往返一致`() {
        val ps = ProtectedString(isProtected = true, bytes = secret.toByteArray(Charsets.UTF_8))
        assertEquals(secret, String(ps.readUtf8(), Charsets.UTF_8))
        assertEquals(secret, String(ps.readChars()))
        assertEquals(secret, ps.readString())
    }

    @Test
    fun `相等性经密文保持`() {
        val a = ProtectedString("same-master-password")
        val b = ProtectedString("same-master-password")
        assertEquals(a, b)
        assertEquals("确定性加密下 hashCode 必须一致", a.hashCode(), b.hashCode())
    }

    @Test
    fun `不同内容不相等`() {
        assertNotEquals(ProtectedString("password-1"), ProtectedString("password-2"))
    }

    @Test
    fun `保护标志不同的实例不相等`() {
        assertNotEquals(ProtectedString("value", isProtected = true), ProtectedString("value", isProtected = false))
    }

    @Test
    fun `clear 后一切读取被硬拒绝`() {
        val ps = ProtectedString("wipe-me-now")
        ps.clear()
        assertThrows(IllegalStateException::class.java) { ps.readUtf8() }
        assertThrows(IllegalStateException::class.java) { ps.readChars() }
        assertThrows(IllegalStateException::class.java) { ps.readString() }
    }

    @Test
    fun `清零后的实例彼此不相等`() {
        val a = ProtectedString("cleared")
        val b = ProtectedString("cleared")
        a.clear()
        b.clear()
        assertNotEquals(a, b)
    }

    @Test
    fun `空值与非保护值行为不变`() {
        val emptyProtected = ProtectedString(isProtected = true, bytes = ByteArray(0))
        assertTrue(emptyProtected.isEmpty)
        assertEquals("", emptyProtected.readString())

        val plainField = ProtectedString(isProtected = false, bytes = "Entry Title".toByteArray())
        assertEquals("Entry Title", plainField.readString())
        assertEquals(ProtectedString("Entry Title", isProtected = false), plainField)
    }

    @Test
    fun `useUtf8 安全闭包自动清零`() {
        val ps = ProtectedString("closure-secret")
        val collected = ps.useUtf8 { bytes -> String(bytes, Charsets.UTF_8) }
        assertEquals("closure-secret", collected)
    }
}
