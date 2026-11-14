package com.keepasskey.app.autofill

import com.keepasskey.app.security.CallerCertDigests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KeePassDX 口径应用关联字段的纯策略用例：键名 / 连续编号 / 去重 / 签名配对 / 格式归一。
 *
 * 锁定的契约来自 `参考项目/KeePassDX/.../AppOriginEntryField.kt` 与 `EntryInfo.suffixFieldNamePosition`；
 * 这里只验输入输出，不锁源码文本（工程规则「测试」段）。
 */
class AndroidAppBindingFieldsTest {

    private val fp1 = "A1B2C3D4E5F60718293A4B5C6D7E8F90A1B2C3D4E5F60718293A4B5C6D7E8F90"
    private val fp1Colon = fp1.chunked(2).joinToString(":")
    private val fp2 = "00112233445566778899AABBCCDDEEFF00112233445566778899AABBCCDDEEFF"

    // ---------- keysFor ----------

    @Test
    fun `第0组无后缀_后续组用下划线编号`() {
        assertEquals(
            "AndroidApp" to "AndroidApp Signature",
            AndroidAppBindingFields.keysFor(0)
        )
        assertEquals(
            "AndroidApp_1" to "AndroidApp Signature_1",
            AndroidAppBindingFields.keysFor(1)
        )
        assertEquals(
            "AndroidApp_2" to "AndroidApp Signature_2",
            AndroidAppBindingFields.keysFor(2)
        )
    }

    // ---------- parse ----------

    @Test
    fun `连续编号的包名与签名成对解析`() {
        val bindings = AndroidAppBindingFields.parse(
            mapOf(
                "AndroidApp" to "com.tencent.mobileqq",
                "AndroidApp Signature" to fp1Colon,
                "AndroidApp_1" to "com.example.app",
                "AndroidApp Signature_1" to fp2.chunked(2).joinToString(":"),
                "URL" to "https://example.com"
            )
        )

        assertEquals(2, bindings.size)
        assertEquals("com.tencent.mobileqq", bindings[0].packageName)
        assertEquals(fp1Colon, bindings[0].signatureHex)
        assertEquals("com.example.app", bindings[1].packageName)
        assertEquals(fp2.chunked(2).joinToString(":"), bindings[1].signatureHex)
    }

    @Test
    fun `遇编号缺失即停_后续孤立键不得被收进序列`() {
        val bindings = AndroidAppBindingFields.parse(
            mapOf(
                "AndroidApp" to "com.a.app",
                "AndroidApp_2" to "com.c.app"
            )
        )

        assertEquals("编号 1 缺失 ⇒ 只解析到第 0 组", 1, bindings.size)
        assertEquals("com.a.app", bindings.single().packageName)
    }

    @Test
    fun `首个包名键缺失或空白即停`() {
        assertTrue(AndroidAppBindingFields.parse(emptyMap()).isEmpty())
        assertTrue(AndroidAppBindingFields.parse(mapOf("AndroidApp" to "   ")).isEmpty())
        assertTrue(
            AndroidAppBindingFields.parse(mapOf("AndroidApp_1" to "com.a.app")).isEmpty()
        )
    }

    @Test
    fun `签名键缺失或空白记为未声明签名`() {
        val bindings = AndroidAppBindingFields.parse(
            mapOf(
                "AndroidApp" to "com.a.app",
                "AndroidApp Signature" to "",
                "AndroidApp_1" to "com.b.app",
                "AndroidApp Signature_1" to "  ",
                "AndroidApp_2" to "com.c.app"
            )
        )

        assertEquals(3, bindings.size)
        assertNull(bindings[0].signatureHex)
        assertNull(bindings[1].signatureHex)
        assertNull(bindings[2].signatureHex)
    }

    // ---------- nextPosition ----------

    @Test
    fun `包名已存在返回null_不再重复写入或询问`() {
        val existing = AndroidAppBindingFields.parse(
            mapOf(
                "AndroidApp" to "com.a.app",
                "AndroidApp_1" to "com.b.app"
            )
        )
        assertNull(AndroidAppBindingFields.nextPosition(existing, "com.a.app"))
        assertNull(AndroidAppBindingFields.nextPosition(existing, "COM.B.App"))
    }

    @Test
    fun `新包名取第一个连续空位`() {
        assertEquals(0, AndroidAppBindingFields.nextPosition(emptyList(), "com.a.app"))
        val existing = AndroidAppBindingFields.parse(
            mapOf("AndroidApp" to "com.a.app", "AndroidApp_1" to "com.b.app")
        )
        assertEquals(2, AndroidAppBindingFields.nextPosition(existing, "com.c.app"))
    }

    // ---------- 签名格式 ----------

    @Test
    fun `连续大写hex转冒号分隔_与KeePassDX同款`() {
        assertEquals(fp1Colon, AndroidAppBindingFields.toColonHex(fp1))
        assertEquals("AA:BB", AndroidAppBindingFields.toColonHex("aabb"))
        assertEquals("", AndroidAppBindingFields.toColonHex(""))
        assertEquals("", AndroidAppBindingFields.toColonHex("::  -- "))
    }

    @Test
    fun `归一忽略大小写冒号空白连字符并按分隔符取首段`() {
        assertEquals(fp1, AndroidAppBindingFields.normalizeHex(fp1Colon.lowercase()))
        assertEquals(fp1, AndroidAppBindingFields.normalizeHex(" $fp1Colon "))
        assertEquals(
            fp1,
            AndroidAppBindingFields.normalizeHex("$fp1Colon##SIG##${fp2.chunked(2).joinToString(":")}")
        )
        assertEquals(fp1, AndroidAppBindingFields.normalizeHex(fp1))
    }

    // ---------- matches ----------

    @Test
    fun `包名相等且签名任一摘要命中即匹配`() {
        val bindings = AndroidAppBindingFields.parse(
            mapOf(
                "AndroidApp" to "com.example.app",
                "AndroidApp Signature" to fp1Colon
            )
        )
        val digests = CallerCertDigests.of(listOf(fp2, fp1Colon.lowercase()))
        assertTrue(AndroidAppBindingFields.matches(bindings, "com.example.app", digests))
        assertTrue(AndroidAppBindingFields.matches(bindings, "COM.EXAMPLE.APP", digests))
    }

    @Test
    fun `条目未声明签名时按包名匹配_但仍由调用方把P2_46门`() {
        val bindings = AndroidAppBindingFields.parse(mapOf("AndroidApp" to "com.example.app"))
        assertTrue(
            AndroidAppBindingFields.matches(bindings, "com.example.app", CallerCertDigests.EMPTY)
        )
    }

    @Test
    fun `签名不匹配或包名不同不得匹配`() {
        val bindings = AndroidAppBindingFields.parse(
            mapOf(
                "AndroidApp" to "com.example.app",
                "AndroidApp Signature" to fp1Colon
            )
        )
        assertFalse(
            AndroidAppBindingFields.matches(
                bindings, "com.example.app", CallerCertDigests.ofSingle(fp2)
            )
        )
        assertFalse(
            AndroidAppBindingFields.matches(
                bindings, "com.example.app.evil", CallerCertDigests.ofSingle(fp1)
            )
        )
        assertFalse(
            AndroidAppBindingFields.matches(bindings, "", CallerCertDigests.ofSingle(fp1))
        )
    }
}
