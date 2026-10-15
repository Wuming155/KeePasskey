package com.keepasskey.app.data.export

import com.keepasskey.app.data.importer.BrowserCsvImporter
import com.keepasskey.app.data.importer.ImportWarningReason
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.csv.KdbxCsvExporter
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

class SpreadsheetTextWorkbookExporterTest {

    @Test
    fun `危险前缀作为文本单元格原样保留且不生成公式或外链`() {
        val passwordValues = listOf(
            "=1+1",
            "+1+1",
            "-1+1",
            "@SUM(1,1)",
            "\t=1+1",
            "\r=1+1",
            "'=1+1",
            "  =1+1"
        )
        val expected = listOf(
            listOf("Name", "Password", "Note"),
            *passwordValues.mapIndexed { index, value ->
                listOf("sample-$index", value, "原样保留")
            }.toTypedArray()
        )
        val csv = encodeCsv(expected).toByteArray(Charsets.UTF_8)

        val xlsx = SpreadsheetTextWorkbookExporter.exportCsv(csv)
        val workbook = readWorkbook(xlsx)

        assertEquals(expected, workbook.rows)
        assertEquals("XLSX 转换不得改写、擦除或给输入 CSV 增加前缀", encodeCsv(expected), csv.toString(Charsets.UTF_8))
        assertEquals(
            setOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels",
                "xl/worksheets/sheet1.xml"
            ),
            workbook.entryNames
        )
        assertTrue("每个导出单元格必须显式为 inline string", workbook.allCellsAreInlineStrings)
        assertEquals("工作表不得含公式节点", 0, workbook.formulaCount)
        assertEquals("工作表不得含超链接节点", 0, workbook.hyperlinkCount)
        assertFalse("工作簿关系不得指向外部资源", workbook.relationshipsXml.contains("TargetMode=\"External\""))
        assertFalse("工作簿不得含宏或外部链接包部件", workbook.entryNames.any {
            it.contains("externalLinks") || it.contains("vbaProject")
        })
    }

    @Test
    fun `RFC4180转义Unicode空值和前导零按原值写为文本`() {
        val expected = listOf(
            listOf("name", "password", "note", "leading"),
            listOf("中文,站点", "comma, quote \" and newline\nnext", "", "0000123400"),
            listOf("另一个", "雪☃", "line1\rline2", "001")
        )
        val workbook = readWorkbook(
            SpreadsheetTextWorkbookExporter.exportCsv(encodeCsv(expected).toByteArray(Charsets.UTF_8))
        )

        assertEquals(expected, workbook.rows)
        assertTrue(workbook.allCellsAreInlineStrings)
    }

    @Test
    fun `实际原值CSV公式密码经导入往返保持原值且XLSX仍为文本`() = runTest {
        val originalPassword = "=1+1"
        val database = KdbxDatabase(
            header = KdbxHeader.createDefault(),
            rootGroup = KdbxGroup(
                name = "Root",
                entries = listOf(
                    KdbxEntry(
                        fields = mapOf(
                            KdbxConstants.Fields.TITLE to ProtectedString("sample", isProtected = false),
                            KdbxConstants.Fields.URL to ProtectedString("https://example.invalid", isProtected = false),
                            KdbxConstants.Fields.USER_NAME to ProtectedString("fake-user", isProtected = false),
                            KdbxConstants.Fields.PASSWORD to ProtectedString(originalPassword, isProtected = true),
                            KdbxConstants.Fields.NOTES to ProtectedString("test note", isProtected = false)
                        )
                    )
                )
            )
        )
        val rawCsv = KdbxCsvExporter.export(database)
        var xlsx: ByteArray? = null
        try {
            val rawCsvText = rawCsv.toString(Charsets.UTF_8)
            assertTrue(
                "原值交换 CSV 必须不改写公式前缀密码",
                rawCsvText.contains("sample,https://example.invalid,fake-user,$originalPassword,test note,")
            )

            xlsx = SpreadsheetTextWorkbookExporter.exportCsv(rawCsv)
            val workbook = readWorkbook(xlsx)
            assertEquals(originalPassword, workbook.rows[1][3])
            assertTrue(workbook.allCellsAreInlineStrings)
            assertEquals(0, workbook.formulaCount)

            val imported = BrowserCsvImporter().parse(rawCsv, "roundtrip.csv")
            assertTrue("导出 CSV 必须仍可被现有浏览器 CSV 导入器读取", imported is KdbxResult.Success)
            val batch = (imported as KdbxResult.Success).data
            assertTrue(
                "原值 CSV 导入应保留公式风险告警",
                batch.report.warnings.any { it.reason == ImportWarningReason.FORMULA_INJECTION_RISK.code }
            )
            val importedPassword = batch.entries.single().password
            try {
                assertTrue(importedPassword.contentEquals(originalPassword.toCharArray()))
            } finally {
                importedPassword.fill('\u0000')
            }
        } finally {
            rawCsv.fill(0)
            xlsx?.fill(0)
        }
    }

    @Test
    fun `超出表格能力或不符合CSV语法时失败关闭`() {
        val oversizedCell = ("a".repeat(32_768) + "\n").toByteArray(Charsets.UTF_8)
        val malformedCsv = "name,password\n\"unterminated".toByteArray(Charsets.UTF_8)
        val invalidUtf8 = byteArrayOf(0xC3.toByte(), 0x28)

        assertIllegalArgument { SpreadsheetTextWorkbookExporter.exportCsv(oversizedCell) }
        assertIllegalArgument { SpreadsheetTextWorkbookExporter.exportCsv(malformedCsv) }
        assertIllegalArgument { SpreadsheetTextWorkbookExporter.exportCsv(invalidUtf8) }
        assertIllegalArgument { SpreadsheetTextWorkbookExporter.exportCsv(byteArrayOf()) }
    }

    private fun readWorkbook(bytes: ByteArray): WorkbookSnapshot {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val content = ByteArrayOutputStream()
                zip.copyTo(content)
                entries[entry.name] = content.toByteArray()
                zip.closeEntry()
            }
        }

        val sheet = parseXml(entries.getValue("xl/worksheets/sheet1.xml"))
        val rows = sheet.getElementsByTagNameNS(SPREADSHEET_NS, "row")
        val values = buildList {
            for (rowIndex in 0 until rows.length) {
                val cells = (rows.item(rowIndex) as Element).getElementsByTagNameNS(SPREADSHEET_NS, "c")
                add(buildList {
                    for (cellIndex in 0 until cells.length) {
                        val cell = cells.item(cellIndex) as Element
                        val text = cell.getElementsByTagNameNS(SPREADSHEET_NS, "t")
                        add(text.item(0)?.textContent.orEmpty())
                    }
                })
            }
        }
        val allCellsAreInlineStrings = sheet.getElementsByTagNameNS(SPREADSHEET_NS, "c").let { cells ->
            (0 until cells.length).all { (cells.item(it) as Element).getAttribute("t") == "inlineStr" }
        }
        val formulaCount = sheet.getElementsByTagNameNS(SPREADSHEET_NS, "f").length
        val hyperlinkCount = sheet.getElementsByTagNameNS(SPREADSHEET_NS, "hyperlink").length
        val relationshipsXml = entries.getValue("xl/_rels/workbook.xml.rels").toString(Charsets.UTF_8)

        return WorkbookSnapshot(
            rows = values,
            allCellsAreInlineStrings = allCellsAreInlineStrings,
            formulaCount = formulaCount,
            hyperlinkCount = hyperlinkCount,
            relationshipsXml = relationshipsXml,
            entryNames = entries.keys
        )
    }

    private fun parseXml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
        setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
    }.newDocumentBuilder().parse(InputSource(ByteArrayInputStream(bytes)))

    private fun encodeCsv(rows: List<List<String>>): String = rows.joinToString("\r\n") { row ->
        row.joinToString(",") { value ->
            if (value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
                "\"${value.replace("\"", "\"\"")}\""
            } else {
                value
            }
        }
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("期望导出拒绝不受支持或格式错误的输入")
        } catch (_: IllegalArgumentException) {
            // fail-closed
        }
    }

    private data class WorkbookSnapshot(
        val rows: List<List<String>>,
        val allCellsAreInlineStrings: Boolean,
        val formulaCount: Int,
        val hyperlinkCount: Int,
        val relationshipsXml: String,
        val entryNames: Set<String>
    )

    private companion object {
        const val SPREADSHEET_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    }
}
