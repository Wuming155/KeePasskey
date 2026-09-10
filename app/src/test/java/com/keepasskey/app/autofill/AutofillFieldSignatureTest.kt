package com.keepasskey.app.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字段签名计算单元测试（ISSUE-P3-43 ②，ISSUE-P3-46 迁移至 HMAC 实现）。
 *
 * 保持整改前的同一断言面：确定性、域 / 包名归一化、角色与域的区分度、
 * 非法包名与密钥不可用的 fail-closed、定长格式、**不可逆性**（签名中不出现包名 / 域名原文）
 * 与**密钥敏感性**（不同密钥产出不同签名，杜绝跨设备关联）。
 *
 * 与 v1（随机盐 + SHA-256）的差异：密钥来源由 [HmacFieldSignatureSource] 注入，
 * 生产为 Keystore 不可导出 HMAC 密钥；本测试注入固定测试密钥，验证算法判据本身。
 */
class AutofillFieldSignatureTest {

    private val source = testHmacFieldSignatureSource()

    @Test
    fun `同输入同密钥产出确定且定长的签名`() {
        val a = AutofillFieldSignature.of(source, "com.example.bank", "accounts.example.com", AutofillFieldRole.PASSWORD)
        val b = AutofillFieldSignature.of(source, "com.example.bank", "accounts.example.com", AutofillFieldRole.PASSWORD)

        assertEquals(a, b)
        assertEquals(AutofillFieldSignature.SIGNATURE_HEX_LENGTH, a?.length)
        assertTrue("签名必须是 hex 字符", a?.all { it in "0123456789abcdef" } == true)
    }

    @Test
    fun `域名大小写与首尾空白与末尾根点归一`() {
        val base = AutofillFieldSignature.of(source, "com.example.bank", "accounts.example.com", AutofillFieldRole.PASSWORD)

        assertEquals(
            base,
            AutofillFieldSignature.of(source, "com.example.bank", "  ACCOUNTS.Example.COM.  ", AutofillFieldRole.PASSWORD)
        )
    }

    @Test
    fun `包名大小写与首尾空白归一`() {
        val base = AutofillFieldSignature.of(source, "com.example.bank", "accounts.example.com", AutofillFieldRole.PASSWORD)

        assertEquals(
            base,
            AutofillFieldSignature.of(source, "  Com.Example.Bank  ", "accounts.example.com", AutofillFieldRole.PASSWORD)
        )
    }

    @Test
    fun `角色不同则签名不同`() {
        val username = AutofillFieldSignature.of(source, "com.example.bank", "accounts.example.com", AutofillFieldRole.USERNAME)
        val password = AutofillFieldSignature.of(source, "com.example.bank", "accounts.example.com", AutofillFieldRole.PASSWORD)

        assertTrue(username != password)
    }

    @Test
    fun `域不同或无域则签名不同`() {
        val withDomain = AutofillFieldSignature.of(source, "com.example.bank", "accounts.example.com", AutofillFieldRole.PASSWORD)
        val otherDomain = AutofillFieldSignature.of(source, "com.example.bank", "login.example.org", AutofillFieldRole.PASSWORD)
        val noDomain = AutofillFieldSignature.of(source, "com.example.bank", null, AutofillFieldRole.PASSWORD)
        val emptyDomain = AutofillFieldSignature.of(source, "com.example.bank", "   ", AutofillFieldRole.PASSWORD)

        assertTrue(withDomain != otherDomain)
        assertTrue(withDomain != noDomain)
        // null 域与空白域等价（同一「纯 App 表单」语义）
        assertEquals(noDomain, emptyDomain)
    }

    @Test
    fun `非法包名 fail-closed 返回 null`() {
        val invalid = listOf(
            "",
            "   ",
            "bank",
            "1com.example.bank",
            "com-example-bank",
            "com.example.bank/login",
            "..",
            "com..example"
        )
        invalid.forEach { pkg ->
            assertNull("非法包名必须返回 null: $pkg", AutofillFieldSignature.of(source, pkg, "a.com", AutofillFieldRole.PASSWORD))
        }
    }

    @Test
    fun `密钥不可用视为非法返回 null`() {
        assertNull(
            AutofillFieldSignature.of(
                unavailableHmacFieldSignatureSource,
                "com.example.bank",
                "a.com",
                AutofillFieldRole.PASSWORD
            )
        )
    }

    @Test
    fun `签名不含包名或域名原文（不可逆性）`() {
        val signature = AutofillFieldSignature.of(source, "com.example.bank", "accounts.example.com", AutofillFieldRole.PASSWORD)!!

        assertFalse(signature.contains("com.example.bank"))
        assertFalse(signature.contains("accounts.example.com"))
    }

    @Test
    fun `不同密钥产出不同签名（跨设备隔离）`() {
        val otherSource = testHmacFieldSignatureSource(ByteArray(32) { (it * 7 + 1).toByte() })

        val a = AutofillFieldSignature.of(source, "com.example.bank", "accounts.example.com", AutofillFieldRole.PASSWORD)
        val b = AutofillFieldSignature.of(otherSource, "com.example.bank", "accounts.example.com", AutofillFieldRole.PASSWORD)

        assertTrue("不同设备的密钥必须产出不同签名", a != b)
    }
}
