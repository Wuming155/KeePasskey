package com.keepasskey.app.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字段屏蔽目标键构造单元测试（ISSUE-P3-43 ②；ISSUE-P3-328 改明文目标键后重写）。
 *
 * 保持同一断言面：确定性、域 / 包名归一化、角色与域的区分度、非法包名 fail-closed、
 * 版本前缀格式与读取侧脏条目过滤。
 * （ISSUE-P3-328：原「不可逆性」「密钥敏感性」两组断言随 Keystore HMAC 层移除而删除，
 * 登记于批次 §2.4——明文目标键**有意**含包名 / 域名原文，安全口径见
 * [AutofillFieldSignature] KDoc。）
 */
class AutofillFieldSignatureTest {

    @Test
    fun `同输入产出确定的目标键`() {
        val a = AutofillFieldSignature.of("com.example.bank", "accounts.example.com", AutofillFieldRole.PASSWORD)
        val b = AutofillFieldSignature.of("com.example.bank", "accounts.example.com", AutofillFieldRole.PASSWORD)

        assertEquals(a, b)
    }

    @Test
    fun `域名大小写与首尾空白与末尾根点归一`() {
        val base = AutofillFieldSignature.of("com.example.bank", "accounts.example.com", AutofillFieldRole.PASSWORD)

        assertEquals(
            base,
            AutofillFieldSignature.of("com.example.bank", "  ACCOUNTS.Example.COM.  ", AutofillFieldRole.PASSWORD)
        )
    }

    @Test
    fun `包名大小写与首尾空白归一`() {
        val base = AutofillFieldSignature.of("com.example.bank", "accounts.example.com", AutofillFieldRole.PASSWORD)

        assertEquals(
            base,
            AutofillFieldSignature.of("  Com.Example.Bank  ", "accounts.example.com", AutofillFieldRole.PASSWORD)
        )
    }

    @Test
    fun `角色不同则目标键不同`() {
        val username = AutofillFieldSignature.of("com.example.bank", "accounts.example.com", AutofillFieldRole.USERNAME)
        val password = AutofillFieldSignature.of("com.example.bank", "accounts.example.com", AutofillFieldRole.PASSWORD)

        assertTrue(username != password)
    }

    @Test
    fun `域不同或无域则目标键不同`() {
        val withDomain = AutofillFieldSignature.of("com.example.bank", "accounts.example.com", AutofillFieldRole.PASSWORD)
        val otherDomain = AutofillFieldSignature.of("com.example.bank", "login.example.org", AutofillFieldRole.PASSWORD)
        val noDomain = AutofillFieldSignature.of("com.example.bank", null, AutofillFieldRole.PASSWORD)
        val emptyDomain = AutofillFieldSignature.of("com.example.bank", "   ", AutofillFieldRole.PASSWORD)

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
            assertNull("非法包名必须返回 null: $pkg", AutofillFieldSignature.of(pkg, "a.com", AutofillFieldRole.PASSWORD))
        }
    }

    @Test
    fun `目标键为当前版本前缀格式且被读取侧判为良构`() {
        val target = AutofillFieldSignature.of("com.example.bank", "accounts.example.com", AutofillFieldRole.PASSWORD)!!

        assertTrue(
            "目标键必须以当前 SCHEMA_VERSION 前缀开头",
            target.startsWith("${AutofillFieldSignature.SCHEMA_VERSION}|")
        )
        assertTrue(AutofillFieldSignature.isWellFormedTarget(target))
    }

    @Test
    fun `旧版本残留条目被判为非良构`() {
        // v2 hex 签名与 v1 盐形态残留：不得命中本版本前缀判据（否则迁移后仍可能混入计数）
        assertFalse(AutofillFieldSignature.isWellFormedTarget("0123456789abcdef0123456789abcdef"))
        assertFalse(AutofillFieldSignature.isWellFormedTarget("v2|com.example.bank|a.com|p"))
        assertFalse(AutofillFieldSignature.isWellFormedTarget(""))
    }
}
