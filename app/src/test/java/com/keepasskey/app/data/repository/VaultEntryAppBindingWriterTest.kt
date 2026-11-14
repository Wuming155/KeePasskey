package com.keepasskey.app.data.repository

import com.keepasskey.app.autofill.AndroidAppBindingFields
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用关联**字段表变换**的 JVM 契约（[VaultEntryAppBindingWriter] 的纯函数部分）：
 * 连续编号追加、幂等去重、签名受保护位、无签名形态、其它字段与 `URL` 不受影响。
 *
 * 落盘 / 会话受理由真实协调器链路承担，本类不建模（工程规则「在能够证明目标行为的最低成本层验证」）。
 */
class VaultEntryAppBindingWriterTest {

    private val fingerprint = "A1B2C3D4E5F60718293A4B5C6D7E8F90A1B2C3D4E5F60718293A4B5C6D7E8F90"
    private val fingerprintColon = fingerprint.chunked(2).joinToString(":")

    private fun values(fields: List<KdbxCustomField>): Map<String, String> =
        VaultEntryAppBindingWriter.bindingFieldValues(fields) ?: error("绑定键可读")

    private fun add(
        existing: List<KdbxCustomField>,
        packageName: String,
        fingerprintHex: String = fingerprint
    ): List<KdbxCustomField>? = VaultEntryAppBindingWriter.mergedBindingFields(
        existing, values(existing), packageName, fingerprintHex
    )

    @Test
    fun `空条目写第0组_包名明文_签名受保护且为冒号hex`() {
        val merged = add(emptyList(), "com.tencent.mobileqq")!!
        val (appKey, sigKey) = AndroidAppBindingFields.keysFor(0)

        assertEquals(listOf(appKey, sigKey), merged.map { it.key })
        assertEquals("com.tencent.mobileqq", merged[0].value.readString())
        assertFalse("包名键必须明文", merged[0].isProtected || merged[0].value.isProtected)
        assertEquals(fingerprintColon, merged[1].value.readString())
        assertTrue("签名字段必须受保护", merged[1].isProtected && merged[1].value.isProtected)
    }

    @Test
    fun `第二第三个包名按连续编号追加_同编号签名成对`() {
        var fields = add(emptyList(), "com.a.app")!!
        fields = add(fields, "com.b.app", "AABB")!!
        fields = add(fields, "com.c.app", "CCDD")!!

        assertEquals(
            listOf(
                "AndroidApp", "AndroidApp Signature",
                "AndroidApp_1", "AndroidApp Signature_1",
                "AndroidApp_2", "AndroidApp Signature_2"
            ),
            fields.map { it.key }
        )
        assertEquals("com.b.app", fields[2].value.readString())
        assertEquals("AA:BB", fields[3].value.readString())
        assertTrue(fields[3].value.isProtected)
        assertEquals("com.c.app", fields[4].value.readString())
        assertEquals("CC:DD", fields[5].value.readString())
    }

    @Test
    fun `包名已存在返回null_不重复写`() {
        val fields = add(emptyList(), "com.a.app")!!
        assertNull(add(fields, "com.a.app"))
        assertNull(add(fields, "COM.A.APP", "0011"))
    }

    @Test
    fun `签名摘要为空时只写包名键_不留孤立签名键`() {
        val fields = add(emptyList(), "com.a.app", "")!!
        assertEquals(listOf("AndroidApp"), fields.map { it.key })
        assertEquals("com.a.app", fields.single().value.readString())
        assertFalse(fields.single().value.isProtected)
    }

    @Test
    fun `其它自定义字段与URL字段原样保留`() {
        val entry = KdbxEntry(
            fields = mapOf(
                com.keepasskey.core.model.KdbxConstants.Fields.URL to
                    ProtectedString("https://example.com", isProtected = false)
            ),
            customFields = listOf(
                KdbxCustomField("PIN 备用码", ProtectedString("891204", isProtected = true)),
                KdbxCustomField("备注", ProtectedString("keep", isProtected = false))
            )
        )
        val merged = add(entry.customFields, "com.a.app")!!

        assertEquals(
            listOf("PIN 备用码", "备注", "AndroidApp", "AndroidApp Signature"),
            merged.map { it.key }
        )
        assertEquals("891204", merged[0].value.readString())
        assertTrue(merged[0].value.isProtected)
        assertEquals("keep", merged[1].value.readString())
        assertEquals("URL 标准字段不在自定义字段表里，写入链无从触碰", "https://example.com", entry.url)
    }

    @Test
    fun `bindingFieldValues只取绑定键_不物化其它受保护字段`() {
        val fields = listOf(
            KdbxCustomField("AndroidApp", ProtectedString("com.a.app", isProtected = false)),
            KdbxCustomField("AndroidApp Signature", ProtectedString(fingerprintColon, isProtected = true)),
            KdbxCustomField("PIN 备用码", ProtectedString("891204", isProtected = true))
        )
        val values = values(fields)

        assertEquals(2, values.size)
        assertFalse("其它受保护字段不得进入值表", values.containsKey("PIN 备用码"))
        assertEquals("com.a.app", values["AndroidApp"])
        assertEquals(fingerprintColon, values["AndroidApp Signature"])
    }

    @Test
    fun `严格键名_同名前缀的用户字段不被当绑定序列`() {
        val fields = listOf(
            KdbxCustomField("AndroidApp", ProtectedString("com.a.app", isProtected = false)),
            KdbxCustomField("AndroidAppExtra", ProtectedString("not-a-binding", isProtected = false)),
            KdbxCustomField("AndroidApp Signature_x", ProtectedString("not-a-binding", isProtected = true))
        )
        val values = values(fields)

        assertEquals(1, values.size)
        assertEquals(1, AndroidAppBindingFields.parse(values).size)
        assertEquals("com.a.app", AndroidAppBindingFields.parse(values).single().packageName)
    }
}
