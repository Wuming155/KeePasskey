package com.keepasskey.database.xml

import com.keepasskey.core.model.CustomIcon
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/**
 * `ISSUE-P2-283` 守护用例：自定义图标 `<Data>` 必须走宽松 Base64 解码
 * （[KdbxXmlValueUtil.decodeBase64LenientWhitespace]），与 D19 受保护串 / 内联附件同口径。
 *
 * 背景：官方 .NET `Convert.FromBase64String` 容忍内部空白与换行（第三方写入者常按
 * 76 列折行，`.NET XmlWriter.WriteBase64` 亦按块折行）；严格 `Base64.getDecoder()`
 * 会把合法库整库判损坏。D19 只修了受保护串与内联附件，本面此前漏网。
 */
class KdbxCustomIconBase64LenientTest {

    private val iconBytes = ByteArray(90) { (it * 3 + 1).toByte() }
    private val iconUuid = KdbxUuid.random()
    private val tightBase64 = Base64.getEncoder().encodeToString(iconBytes)

    // ---------------------------------------------------------------- AC② 打开成功 + 可渲染 + 往返

    @Test
    fun `76 列折行的图标 Data 可打开且字节与渲染输入一致`() {
        val wrapped = wrapBase64(tightBase64, columns = 76, indent = "                    ")
        assertTrue("样本必须真的含内部换行", wrapped.contains('\n'))

        val parsed = parseWithIconData(wrapped)

        val icon = parsed.meta.customIcons.single()
        assertEquals(iconUuid, icon.uuid)
        // 「图标可渲染」在 XML 层的可测形态 = 交付给渲染器的字节与源字节一致
        assertArrayEquals(iconBytes, icon.data)
    }

    @Test
    fun `折行图标数据经序列化写出再读回字节一致`() {
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root"),
            customIcons = listOf(CustomIcon(iconUuid, iconBytes, name = "折行图标"))
        )
        val bos = ByteArrayOutputStream()
        KdbxXmlSerializer(null).serialize(bos, db)
        val tightXml = bos.toString(Charsets.UTF_8)

        // 模拟第三方写入者 / .NET XmlWriter.WriteBase64 的 76 列折行形态
        val wrappedXml = wrapDataElement(tightXml, columns = 76)
        assertTrue("样本必须真的含内部换行", wrappedXml.contains('\n'))

        val parsed = KdbxXmlParser(null).parse(wrappedXml.byteInputStream())
        val icon = parsed.meta.customIcons.single()

        assertEquals(iconUuid, icon.uuid)
        assertArrayEquals(iconBytes, icon.data)
    }

    @Test
    fun `含空格与混合换行的图标 Data 同样按官方语义解码`() {
        val messy = "\n  " + tightBase64.chunked(12).joinToString(" \r\n  ") + "\n"

        val icon = parseWithIconData(messy).meta.customIcons.single()

        assertArrayEquals(iconBytes, icon.data)
    }

    @Test
    fun `非法 Base64 仍拒绝为损坏文件且不静默丢弃`() {
        val ex = assertThrows(KdbxCorruptFileException::class.java) {
            parseWithIconData("!!!NOT_VALID_BASE64@@@")
        }
        assertTrue("异常须指明 CustomIcon", ex.message!!.contains("CustomIcon"))
    }

    // ---------------------------------------------------------------- AC① 单点解码器接线

    @Test
    fun `MetaReader 必须复用宽松解码器且生产代码不得再调严格 getDecoder 解码`() {
        val source = readSource("KdbxXmlMetaReader.kt")
        assertTrue(
            "KdbxXmlMetaReader 必须调用 decodeBase64LenientWhitespace（ISSUE-P2-283 AC①）",
            source.contains("KdbxXmlValueUtil.decodeBase64LenientWhitespace")
        )
        // 去掉行注释与块注释后，生产代码不得再出现严格解码调用（D19 同族误拒面）
        val production = source
            .lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        assertEquals(
            "生产代码不得再以 Base64.getDecoder() 解码（应走宽松解码器）",
            0,
            Regex("""Base64\.getDecoder\s*\(\s*\)""").findAll(production).count()
        )
    }

    // ---------------------------------------------------------------- 辅助

    private fun parseWithIconData(dataText: String): KdbxXmlParser.ParseResult {
        val xml = """
            <KeePassFile>
                <Meta>
                    <CustomIcons>
                        <Icon>
                            <UUID>${Base64.getEncoder().encodeToString(iconUuid.toByteArray())}</UUID>
                            <Data>$dataText</Data>
                            <Name>折行图标</Name>
                        </Icon>
                    </CustomIcons>
                </Meta>
                <Root>
                    <Group>
                        <UUID>${Base64.getEncoder().encodeToString(KdbxUuid.random().toByteArray())}</UUID>
                        <Name>Root</Name>
                    </Group>
                </Root>
            </KeePassFile>
        """.trimIndent()
        return KdbxXmlParser(null).parse(xml.byteInputStream())
    }

    private fun wrapBase64(base64: String, columns: Int, indent: String): String =
        base64.chunked(columns).joinToString("\n$indent")

    /** 把 `<Data>…</Data>`（图标）内的 Base64 按 [columns] 列折行，模拟第三方写入者形态。 */
    private fun wrapDataElement(xml: String, columns: Int): String {
        val open = "<${KdbxConstants.Xml.DATA}>"
        val close = "</${KdbxConstants.Xml.DATA}>"
        val start = xml.indexOf(open)
        val end = xml.indexOf(close)
        require(start >= 0 && end > start) { "序列化结果缺少 <Data> 元素" }
        val body = xml.substring(start + open.length, end)
        val wrapped = body.chunked(columns).joinToString("\n")
        return xml.substring(0, start + open.length) + wrapped + xml.substring(end)
    }

    private fun readSource(fileName: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "database/src/main/java/com/keepasskey/database/xml/$fileName")
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        error("找不到源文件 $fileName（接线守卫需要读取生产源码）")
    }
}
