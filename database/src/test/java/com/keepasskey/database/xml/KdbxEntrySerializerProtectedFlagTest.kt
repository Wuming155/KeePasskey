package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.MemoryProtectionConfig
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import com.keepasskey.database.file.InnerHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * 写侧 `Protected` 契约回归测试：
 * - 缺陷 D17（P2）：`Ref` 附件路径**不得**写 `Protected` 属性（官方 `KdbxFile.Write.cs:930-939`
 *   只写 `Ref`；`Protected` 仅用于内联值的 `SubWriteValue` 分支）；
 * - 缺陷 D7（P2）：标准五字段按**数据库级** `Meta/MemoryProtection` 配置回落判定
 *   （官方 `KdbxFile.Write.cs:838-854`）。
 */
class KdbxEntrySerializerProtectedFlagTest {

    private fun newCipher(): InnerRandomStreamCipher =
        InnerRandomStreamCipher(KdbxConstants.InnerRandomStream.CHACHA20, ByteArray(64))

    private fun serializeEntry(
        entry: KdbxEntry,
        memoryProtection: MemoryProtectionConfig = MemoryProtectionConfig(),
        binaryPoolSize: Int = 0,
        cipher: InnerRandomStreamCipher? = newCipher()
    ): String {
        val out = ByteArrayOutputStream()
        val writer = KdbxXmlStreamWriter(out)
        KdbxXmlEntrySerializer.serialize(
            writer,
            entry,
            cipher,
            memoryProtection = memoryProtection,
            binaryPoolSize = binaryPoolSize
        )
        writer.close()
        return out.toString(Charsets.UTF_8.name())
    }

    /** 抽取某个 `<Key>name</Key><Value ...>...</Value>` 的 Value 起始标签（含属性）。 */
    private fun valueStartTag(xml: String, key: String): String {
        val marker = "<Key>$key</Key><Value"
        val start = xml.indexOf(marker)
        assertTrue("未找到字段 $key 的 Value 标签", start >= 0)
        val tagStart = start + "<Key>$key</Key>".length
        val tagEnd = xml.indexOf('>', tagStart)
        return xml.substring(tagStart, tagEnd + 1)
    }

    private fun standardFields(): Map<String, ProtectedString> = linkedMapOf(
        KdbxConstants.Fields.TITLE to ProtectedString("T", isProtected = false),
        KdbxConstants.Fields.USER_NAME to ProtectedString("U", isProtected = false),
        KdbxConstants.Fields.PASSWORD to ProtectedString("P", isProtected = false),
        KdbxConstants.Fields.URL to ProtectedString("http://x", isProtected = false),
        KdbxConstants.Fields.NOTES to ProtectedString("N", isProtected = false)
    )

    // ---------------- 缺陷 D17：Ref 路径不得写 Protected ----------------

    @Test
    fun `池内 Ref 附件不得写出 Protected 属性`() {
        val xml = serializeEntry(
            KdbxEntry(
                fields = standardFields(),
                attachments = listOf(
                    KdbxAttachment(name = "a.bin", refIndex = 0, isProtected = true, data = ByteArray(0))
                )
            ),
            binaryPoolSize = 1
        )

        val tag = valueStartTag(xml, "a.bin")
        assertTrue("Ref 路径必须写出 Ref 属性: $tag", tag.contains("Ref=\"0\""))
        assertFalse("Ref 路径不得写 Protected 属性（缺陷 D17）: $tag", tag.contains("Protected"))
    }

    @Test
    fun `多个池内 Ref 附件均不写 Protected`() {
        val xml = serializeEntry(
            KdbxEntry(
                fields = standardFields(),
                attachments = listOf(
                    KdbxAttachment(name = "a.bin", refIndex = 0, isProtected = true),
                    KdbxAttachment(name = "b.bin", refIndex = 1, isProtected = true)
                )
            ),
            binaryPoolSize = 2
        )

        assertFalse(valueStartTag(xml, "a.bin").contains("Protected"))
        assertFalse(valueStartTag(xml, "b.bin").contains("Protected"))
    }

    @Test
    fun `池外索引附件回退内联 Base64 且不写悬空 Ref`() {
        val payload = "INLINE-BYTES".toByteArray()
        val xml = serializeEntry(
            KdbxEntry(
                fields = standardFields(),
                attachments = listOf(KdbxAttachment(name = "a.bin", refIndex = BinaryNode.INLINE_REF_INDEX, data = payload))
            ),
            binaryPoolSize = 1
        )

        val tag = valueStartTag(xml, "a.bin")
        assertFalse("池外索引不得写出 Ref（会指向无关池条目）: $tag", tag.contains("Ref="))
        assertTrue(
            "内联写出正文应为该附件字节的 Base64",
            xml.contains(Base64.getEncoder().encodeToString(payload))
        )
    }

    @Test
    fun `内联受保护附件写出规范 True 与密钥流密文`() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val xml = serializeEntry(
            KdbxEntry(
                fields = standardFields(),
                attachments = listOf(
                    KdbxAttachment(
                        name = "p.bin",
                        refIndex = BinaryNode.INLINE_REF_INDEX,
                        isProtected = true,
                        data = payload
                    )
                )
            ),
            // 库级关闭全部标准字段保护：否则默认配置下 ProtectPassword=true 会先消费密钥流，
            // 使附件密文落在流偏移 >0 处（本用例要断言的是"附件本身按密钥流加密"，故取全关配置）
            memoryProtection = MemoryProtectionConfig(
                protectTitle = false,
                protectUserName = false,
                protectPassword = false,
                protectUrl = false,
                protectNotes = false
            ),
            binaryPoolSize = 0
        )

        val tag = valueStartTag(xml, "p.bin")
        assertTrue("内联受保护附件必须写 Protected=\"True\": $tag", tag.contains("Protected=\"True\""))
        val expected = Base64.getEncoder().encodeToString(newCipher().processBytes(payload))
        assertTrue("内联受保护附件正文应为密钥流密文的 Base64", xml.contains(expected))
    }

    // ---------------- 缺陷 D7：标准五字段按库级配置写 Protected ----------------

    @Test
    fun `库级配置要求保护时标准字段按配置写出 Protected`() {
        // per-value 全为 false，受保护标志**完全**由库级配置决定
        // （官方 `KdbxFile.Write.cs:844-853`：标准字段 bProtected = MemoryProtection.ProtectXxx）
        val xml = serializeEntry(
            KdbxEntry(fields = standardFields()),
            memoryProtection = MemoryProtectionConfig(
                protectTitle = true,
                protectUserName = false,
                protectPassword = true,
                protectUrl = false,
                protectNotes = true
            )
        )

        assertTrue(valueStartTag(xml, KdbxConstants.Fields.TITLE).contains("Protected=\"True\""))
        assertTrue(valueStartTag(xml, KdbxConstants.Fields.PASSWORD).contains("Protected=\"True\""))
        assertTrue(valueStartTag(xml, KdbxConstants.Fields.NOTES).contains("Protected=\"True\""))
        assertFalse(valueStartTag(xml, KdbxConstants.Fields.USER_NAME).contains("Protected"))
        assertFalse(valueStartTag(xml, KdbxConstants.Fields.URL).contains("Protected"))
    }

    @Test
    fun `库级关闭时标准字段必须覆盖 per-value 不写 Protected`() {
        // 官方 `KdbxFile.Write.cs:844-853` 对标准五字段是**无条件覆盖**（`=` 而非 `|=`）：
        // 库级关闭 + per-value 开启 → 官方**不写** Protected（归一化导入遗留设置的既有行为）。
        // 本用例是官方语义的显式回归锁，禁止后人按直觉改回 OR。
        val fields = linkedMapOf(
            KdbxConstants.Fields.TITLE to ProtectedString("T", isProtected = true),
            KdbxConstants.Fields.PASSWORD to ProtectedString("P", isProtected = false)
        )
        val xml = serializeEntry(
            KdbxEntry(fields = fields),
            memoryProtection = MemoryProtectionConfig(
                protectTitle = false,
                protectPassword = false
            )
        )

        assertFalse(
            "标准字段以库级配置为准：库级关闭即不得写 Protected（即使 per-value 为 true）",
            valueStartTag(xml, KdbxConstants.Fields.TITLE).contains("Protected")
        )
        assertFalse(
            "库级与 per-value 均未启用时不得写 Protected",
            valueStartTag(xml, KdbxConstants.Fields.PASSWORD).contains("Protected")
        )
        // 值本身不得因标志归一化而丢失
        assertTrue(xml.contains("<Key>Title</Key><Value>T</Value>"))
    }

    @Test
    fun `库级关闭不抹掉自定义字段的 per-value 受保护标志`() {
        // 与上一例同库同配置下的对照：非标准字段走 `value.IsProtected` 分支（官方 bIsEntryString==false），
        // per-value 为 true 时**仍须**写 Protected —— 两类字段判定口径的分水岭。
        val xml = serializeEntry(
            KdbxEntry(
                fields = linkedMapOf(
                    KdbxConstants.Fields.TITLE to ProtectedString("T", isProtected = true)
                ),
                customFields = listOf(
                    com.keepasskey.core.model.KdbxCustomField(
                        key = "RecoveryCode",
                        value = ProtectedString("R", isProtected = true),
                        isProtected = true
                    )
                )
            ),
            memoryProtection = MemoryProtectionConfig(protectTitle = false)
        )

        assertFalse(
            "标准字段 Title 库级关闭 → 不写 Protected",
            valueStartTag(xml, KdbxConstants.Fields.TITLE).contains("Protected")
        )
        assertTrue(
            "自定义字段 per-value 为 true → 仍须写 Protected（官方保留 value.IsProtected）",
            valueStartTag(xml, "RecoveryCode").contains("Protected=\"True\"")
        )
    }

    @Test
    fun `库级开启时标准字段即使 per-value 为 false 也写 Protected`() {
        val xml = serializeEntry(
            KdbxEntry(fields = standardFields()),
            memoryProtection = MemoryProtectionConfig(protectPassword = true)
        )

        assertTrue(
            "库级开启即写 Protected（官方标准字段以库级配置为准）",
            valueStartTag(xml, KdbxConstants.Fields.PASSWORD).contains("Protected=\"True\"")
        )
        assertFalse(
            "库级未开启的其它标准字段不得被顺带保护",
            valueStartTag(xml, KdbxConstants.Fields.TITLE).contains("Protected")
        )
    }

    @Test
    fun `自定义字段不受库级配置影响`() {
        // 官方对非标准字段走 `bProtected = value.IsProtected` 分支（Write.cs:838 的初始值，
        // 因名称不匹配 PwDefs 五字段而**不**被库级配置覆盖）——库级五项全开亦不得作用于自定义字段。
        val xml = serializeEntry(
            KdbxEntry(
                customFields = listOf(
                    com.keepasskey.core.model.KdbxCustomField(
                        key = "APIKey",
                        value = ProtectedString("s", isProtected = false),
                        isProtected = false
                    )
                )
            ),
            memoryProtection = MemoryProtectionConfig(
                protectTitle = true,
                protectUserName = true,
                protectPassword = true,
                protectUrl = true,
                protectNotes = true
            )
        )

        assertFalse(
            "自定义字段即便库级五项全开也不得被保护（其标志只由 per-value 决定）",
            valueStartTag(xml, "APIKey").contains("Protected")
        )
    }

    @Test
    fun `写侧恒写规范字面量 True`() {
        val xml = serializeEntry(
            KdbxEntry(fields = standardFields()),
            memoryProtection = MemoryProtectionConfig(protectPassword = true)
        )

        val tag = valueStartTag(xml, KdbxConstants.Fields.PASSWORD)
        assertTrue("必须恰好是 True（读侧为大小写敏感精确比较）: $tag", tag.contains("Protected=\"True\""))
        assertFalse("不得写小写 true: $tag", tag.contains("Protected=\"true\""))
    }

    @Test
    fun `历史条目同样按库级配置判定`() {
        val historyEntry = KdbxEntry(
            fields = linkedMapOf(KdbxConstants.Fields.PASSWORD to ProtectedString("old", isProtected = false))
        )
        val xml = serializeEntry(
            KdbxEntry(fields = standardFields(), history = listOf(historyEntry)),
            memoryProtection = MemoryProtectionConfig(protectPassword = true)
        )

        // 历史条目位于 <History> 内，其 Password 亦须按库级配置写出 Protected
        val historyIndex = xml.indexOf("<History>")
        assertTrue("用例前提：应写出 History 子树", historyIndex > 0)
        val historyXml = xml.substring(historyIndex)
        assertTrue(
            "历史条目的标准字段同样适用库级 MemoryProtection",
            valueStartTag(historyXml, KdbxConstants.Fields.PASSWORD).contains("Protected=\"True\"")
        )
    }

    // ---------------- 既有结构契约不得破损 ----------------

    @Test
    fun `附件写出结构仍是 Binary-Key-Value`() {
        val xml = serializeEntry(
            KdbxEntry(
                fields = standardFields(),
                attachments = listOf(KdbxAttachment(name = "s.bin", refIndex = 0, data = ByteArray(2)))
            ),
            binaryPoolSize = 1
        )

        assertNotNull(xml)
        assertTrue(xml.contains("<Binary><Key>s.bin</Key>"))
        assertEquals(
            "Value 为空元素时应写作自闭合 <Value Ref=\"0\"/>",
            1,
            Regex("<Binary><Key>s\\.bin</Key><Value Ref=\"0\"/>").findAll(xml).count()
        )
    }

    @Test
    fun `池大小为零时任何非负索引都回退内联`() {
        val payload = byteArrayOf(3, 1, 4)
        val xml = serializeEntry(
            KdbxEntry(
                fields = standardFields(),
                attachments = listOf(KdbxAttachment(name = "z.bin", refIndex = 5, data = payload))
            ),
            binaryPoolSize = 0
        )

        val tag = valueStartTag(xml, "z.bin")
        assertFalse("池为空时不得写出任何 Ref: $tag", tag.contains("Ref="))
        assertTrue(xml.contains(Base64.getEncoder().encodeToString(payload)))
    }

    @Test
    fun `附件名中的特殊字符仍按 XML 转义`() {
        val xml = serializeEntry(
            KdbxEntry(
                fields = standardFields(),
                attachments = listOf(
                    KdbxAttachment(
                        name = "a&b<c.bin",
                        refIndex = 0,
                        data = ByteArray(0)
                    )
                )
            ),
            binaryPoolSize = 1
        )

        assertTrue(xml.contains("<Key>a&amp;b&lt;c.bin</Key>"))
    }

    @Test
    fun `额外校验 InnerHeader 池条目构造正常`() {
        // 保证测试用到的池条目构造方式与生产一致（避免用例前提漂移）
        val item = InnerHeader.BinaryItem(0, byteArrayOf(1))
        assertEquals(1, item.data.size)
    }
}
