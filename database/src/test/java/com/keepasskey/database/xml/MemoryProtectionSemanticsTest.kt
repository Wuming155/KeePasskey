package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.MemoryProtectionConfig
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * ISSUE-P2-64（审计 M1）：**「内存密封」与「写出标志」是两条互不相干的独立口径**。
 *
 * 审计记录的原始表述把两者混为一谈（"库级 `MemoryProtectionConfig` 不影响内存密封"被当作缺陷）。
 * 本用例把两条口径各自的真实行为与**不可降级**的安全不变式固定下来：
 *
 * 1. **内存密封**由字段自身的 `ProtectedString.isProtected` 决定（读侧取自 XML `Protected` 属性），
 *    **与库级配置无关**——库级 `protectUserName = true` 不会让内存中的 `UserName` 变密封，
 *    库级关闭也不会让 per-value 密封失效；
 * 2. **写出标志**由库级配置经 `KdbxXmlEntrySerializer.resolveProtectedFlag` **无条件覆盖**
 *    标准五字段（官方 `KdbxFile.Write.cs:844-853` 的 `=` 语义）；
 * 3. **安全不变式（AC③ 的可证部分）**：恶意库把 `<ProtectPassword>False</ProtectPassword>`
 *    写进文件，**不能**让口令失去驻留密封，也**不能**让本仓产物把口令降级为未保护。
 */
class MemoryProtectionSemanticsTest {

    @Test
    fun `恶意库关闭 ProtectPassword 不得削弱口令密封与写出标志`() {
        val parsed = parse(
            keepassFileXml(
                metaProtection = """
                    <ProtectTitle>True</ProtectTitle>
                    <ProtectUserName>True</ProtectUserName>
                    <ProtectPassword>False</ProtectPassword>
                    <ProtectURL>True</ProtectURL>
                    <ProtectNotes>True</ProtectNotes>
                """.trimIndent(),
                // per-value 明确为 True：即便库级配置（恶意）关闭，内存密封也必须保持
                entryBody = stringElement("Password", "secret", protected = true)
            )
        )

        // 读侧：官方装载收尾整对象重置 —— 文件里的组合一律不采用
        assertEquals(
            "文件里的 ProtectPassword=False 与其余 True 必须被整体弃用",
            MemoryProtectionConfig(),
            parsed.meta.memoryProtection
        )
        collectedSealedFields(parsed).forEach { (key, isProtected) ->
            assertTrue("字段 $key 的 per-value 密封不得被库级配置改写", isProtected)
        }

        // 写侧：产物不得把口令降级为未保护
        val rewritten = serialize(parsed)
        assertTrue(
            "重写产物必须仍声明 ProtectPassword=True: $rewritten",
            rewritten.contains("<${KdbxConstants.Xml.PROTECT_PASSWORD}>True</${KdbxConstants.Xml.PROTECT_PASSWORD}>")
        )
        assertFalse(
            "重写产物不得出现 ProtectPassword=False（口令密封被降级）",
            rewritten.contains("<${KdbxConstants.Xml.PROTECT_PASSWORD}>False</${KdbxConstants.Xml.PROTECT_PASSWORD}>")
        )
        assertTrue(
            "口令字段必须重新带上 Protected=\"True\"",
            valueTag(rewritten, KdbxConstants.Fields.PASSWORD).contains("Protected=\"True\"")
        )
    }

    @Test
    fun `内存密封由字段属性决定而与库级配置无关`() {
        // 库级两个方向都试：ProtectUserName 显式 True（文件里）与完全缺失，
        // 而 UserName 字段自身的 Protected 属性恒为 True → 内存密封恒为 true
        val withLibraryFlag = parse(
            keepassFileXml(
                metaProtection = "<ProtectUserName>True</ProtectUserName>",
                entryBody = stringElement(KdbxConstants.Fields.USER_NAME, "alice", protected = true)
            )
        )
        val withoutLibraryFlag = parse(
            keepassFileXml(
                metaProtection = "",
                entryBody = stringElement(KdbxConstants.Fields.USER_NAME, "alice", protected = true)
            )
        )

        // 库级配置两侧都被重置为默认（protectUserName=false）——本配置不承载内存密封
        assertFalse(withLibraryFlag.meta.memoryProtection.protectUserName)
        assertFalse(withoutLibraryFlag.meta.memoryProtection.protectUserName)
        // 而 per-value 密封在两种情况下都保持
        assertEquals(true, userNameSealed(withLibraryFlag))
        assertEquals(true, userNameSealed(withoutLibraryFlag))
    }

    /**
     * 如实声明的**官方归一化**行为（非缺陷、不得被"修好"）：
     * 标准字段的 `Protected` 属性按库级配置无条件覆盖，而库级配置读侧恒为默认
     * （`protectUserName=false`）→ 来自文件的 `UserName Protected="True"` 在重写时**不写回**。
     * 该行为与既有回归锁 `库级关闭时标准字段必须覆盖 per-value 不写 Protected` 同源，
     * 由官方 `KdbxFile.Write.cs:844-853` 裁决（`AGENTS.md` §3.3：官方实现为格式裁决者）。
     * 口令不受影响：库级默认 `protectPassword=true` 使口令**恒为受保护**（上一个用例）。
     */
    @Test
    fun `标准字段按官方语义归一化其 Protected 属性`() {
        val parsed = parse(
            keepassFileXml(
                metaProtection = "<ProtectUserName>True</ProtectUserName>",
                entryBody = stringElement(KdbxConstants.Fields.USER_NAME, "alice", protected = true)
            )
        )

        assertEquals("读入即密封（内存不变）", true, userNameSealed(parsed))
        val rewritten = serialize(parsed)
        assertFalse(
            "官方归一化：库级 ProtectUserName 恒为 false，故重写不写回 per-value Protected",
            valueTag(rewritten, KdbxConstants.Fields.USER_NAME).contains("Protected")
        )
    }

    // ---- 辅助 ----

    private fun parse(xml: String): KdbxXmlParser.ParseResult =
        KdbxXmlParser(null).parse(xml.byteInputStream())

    private fun serialize(parsed: KdbxXmlParser.ParseResult): String {
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = parsed.rootGroup,
            memoryProtection = parsed.meta.memoryProtection
        )
        val bos = ByteArrayOutputStream()
        KdbxXmlSerializer(null).serialize(bos, db)
        return String(bos.toByteArray(), Charsets.UTF_8)
    }

    private fun firstEntry(parsed: KdbxXmlParser.ParseResult): KdbxEntry =
        parsed.rootGroup.entries.firstOrNull() ?: error("解析结果缺少条目")

    private fun userNameSealed(parsed: KdbxXmlParser.ParseResult): Boolean? =
        firstEntry(parsed).fields[KdbxConstants.Fields.USER_NAME]?.isProtected

    /** 收集条目内全部字段的 per-value 密封标记（供"不得被库级配置改写"断言）。 */
    private fun collectedSealedFields(parsed: KdbxXmlParser.ParseResult): List<Pair<String, Boolean>> =
        firstEntry(parsed).fields.map { (key, value) -> key to value.isProtected }

    /** `<Value ...>…</Value>` 起始标签（含属性）。 */
    private fun valueTag(xml: String, key: String): String {
        val keyStart = xml.indexOf("<Key>$key</Key>")
        assertTrue("产物中未找到字段 $key", keyStart >= 0)
        val valueStart = xml.indexOf("<Value", keyStart)
        val valueEnd = xml.indexOf(">", valueStart)
        return xml.substring(valueStart, valueEnd + 1)
    }

    private fun stringElement(key: String, value: String, protected: Boolean): String {
        val attr = if (protected) " Protected=\"True\"" else ""
        return "<String><Key>$key</Key><Value$attr>$value</Value></String>"
    }

    private fun keepassFileXml(metaProtection: String, entryBody: String): String = """
        <KeePassFile>
            <Meta>
                <MemoryProtection>$metaProtection</MemoryProtection>
            </Meta>
            <Root>
                <Group>
                    <UUID>${uuidBase64()}</UUID>
                    <Name>Root</Name>
                    <Entry>
                        <UUID>${uuidBase64()}</UUID>
                        $entryBody
                    </Entry>
                </Group>
            </Root>
        </KeePassFile>
    """.trimIndent()

    private fun uuidBase64(): String = Base64.getEncoder().encodeToString(KdbxUuid.random().toByteArray())
}
