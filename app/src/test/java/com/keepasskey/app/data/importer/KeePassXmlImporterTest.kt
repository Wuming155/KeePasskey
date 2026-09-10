package com.keepasskey.app.data.importer

import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KeePass 2.x 明文 XML 解析器单测（ISSUE-P3-19 交付物 2）。
 *
 * 覆盖：字段映射（Title/UserName/Password/URL/Notes/otp）、分组树递归与 `groupPath` 语义、
 * 回收站子树 → `deleted`、XML 预定义实体、历史修订与自定义字段的声明式跳过，
 * 以及全部 fail-closed 分支（根元素不符、非 XML 文本、DTD/外部实体、非 UTF-8、超限）。
 *
 * **敏感数据断言纪律**：密码 / TOTP 一律用长度或 `contentEquals` 判定，
 * 绝不把明文放进断言消息（失败信息里不得出现密码）。
 */
class KeePassXmlImporterTest {

    private val importer = KeePassXmlImporter()

    @Test
    fun `声明数据源为 KeePass XML 且仅接受 xml 扩展名`() {
        assertEquals(ImportSource.KEEPASS_XML, importer.source)
        assertEquals(setOf("xml"), importer.supportedExtensions)
    }

    @Test
    fun `根分组下条目完整映射标题用户名密码URL备注`() = runTest {
        val xml = document(
            groupBody = entryXml(
                title = "示例站点",
                username = "user@example.com",
                password = FAKE_PASSWORD,
                url = "https://example.com",
                notes = "备注内容"
            )
        )

        val batch = parseSuccess(xml)

        assertEquals(1, batch.report.parsed)
        assertEquals(0, batch.report.skipped)
        assertEquals(0, batch.report.warnings.size)
        val entry = batch.entries.single()
        assertEquals("示例站点", entry.title)
        assertEquals("user@example.com", entry.username)
        assertEquals(FAKE_PASSWORD.length, entry.password.size)
        assertTrue(entry.password.contentEquals(FAKE_PASSWORD.toCharArray()))
        assertEquals("https://example.com", entry.url)
        assertEquals("备注内容", entry.notes)
        // 根分组名（Root）不计入分组路径：契约要求 groupPath 不含根分组
        assertTrue(entry.groupPath.isEmpty())
        assertFalse(entry.deleted)
        assertNull(entry.totpSecret)
    }

    @Test
    fun `嵌套分组树递归产出不含根分组名的分组路径`() = runTest {
        val inner = groupXml(uuid = UUID_GROUP_INNER, name = "邮箱", body = entryXml(title = "深层条目"))
        val outer = groupXml(uuid = UUID_GROUP_OUTER, name = "工作", body = inner)

        val batch = parseSuccess(document(groupBody = outer))

        assertEquals(listOf("工作", "邮箱"), batch.entries.single().groupPath)
    }

    @Test
    fun `回收站分组子树内条目标记为已删除且同级条目不受影响`() = runTest {
        val binGroup = groupXml(
            uuid = UUID_RECYCLE_BIN,
            name = "Recycle Bin",
            body = entryXml(title = "已删除条目")
        )
        val xml = document(
            groupBody = binGroup + entryXml(title = "正常条目"),
            metaBody = "<RecycleBinUUID>$UUID_RECYCLE_BIN</RecycleBinUUID>"
        )

        val batch = parseSuccess(xml)

        assertEquals(2, batch.report.parsed)
        val deleted = batch.entries.first { it.title == "已删除条目" }
        val kept = batch.entries.first { it.title == "正常条目" }
        assertTrue(deleted.deleted)
        assertFalse(kept.deleted)
    }

    @Test
    fun `otp 字段映射为 TOTP 配置原文且大小写不敏感`() = runTest {
        // XML 中的 & 必须写作 &amp;（顺带验证预定义实体在禁用 DTD 的前提下仍被解析）
        val otpauthXml = "otpauth://totp/Example:user?secret=JBSWY3DPEHPK3PXP&amp;issuer=Example"
        val otpauthText = "otpauth://totp/Example:user?secret=JBSWY3DPEHPK3PXP&issuer=Example"
        val xml = document(
            groupBody = entryXml(title = "标准 otp", otp = otpauthXml, otpKey = "otp") +
                entryXml(title = "大写 OTP", otp = TOTP_SEED, otpKey = "OTP")
        )

        val batch = parseSuccess(xml)

        assertEquals(2, batch.report.parsed)
        val first = requireNotNull(batch.entries[0].totpSecret)
        assertEquals(otpauthText.length, first.size)
        assertTrue(first.contentEquals(otpauthText.toCharArray()))
        val second = requireNotNull(batch.entries[1].totpSecret)
        assertEquals(TOTP_SEED.length, second.size)
        assertTrue(second.contentEquals(TOTP_SEED.toCharArray()))
    }

    @Test
    fun `预定义实体被正常解析且不会因禁用 DTD 而失效`() = runTest {
        val xml = document(
            groupBody = entryXml(
                title = "A &amp; B",
                password = "fake&amp;pass",
                notes = "1 &lt; 2 &amp;&amp; 3 &gt; 2"
            )
        )

        val batch = parseSuccess(xml)

        val entry = batch.entries.single()
        assertEquals("A & B", entry.title)
        assertTrue(entry.password.contentEquals(FAKE_ESCAPED_PASSWORD.toCharArray()))
        assertEquals("1 < 2 && 3 > 2", entry.notes)
    }

    @Test
    fun `DOCTYPE 声明 fail-closed 拒绝`() = runTest {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE KeePassFile>
            <KeePassFile><Meta/><Root><Group><Name>Root</Name></Group></Root></KeePassFile>
        """.trimIndent()

        assertTrue("含 DOCTYPE 的输入必须 fail-closed", parseFailure(xml).isFailure)
    }

    @Test
    fun `外部实体引用 fail-closed 拒绝且不泄露实体内容`() = runTest {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE KeePassFile [<!ENTITY xxe SYSTEM "file:///etc/hostname">]>
            <KeePassFile><Meta/><Root><Group><Name>Root</Name>
            ${entryXml(title = "x", notes = "&xxe;")}
            </Group></Root></KeePassFile>
        """.trimIndent()

        val result = importer.parse(xml.toByteArray(Charsets.UTF_8), FILE_NAME)

        // 实现语义为「拒绝」：DTD 声明与外部实体解析均在 handler 侧 fail-closed
        assertTrue("含外部实体声明的输入必须 fail-closed", result.isFailure)
    }

    @Test
    fun `根元素不是 KeePassFile 时 fail-closed`() = runTest {
        val xml = """<?xml version="1.0"?><NotKeePassFile><Root/></NotKeePassFile>"""

        assertTrue(parseFailure(xml).isFailure)
    }

    @Test
    fun `非 XML 文本（JSON 内容改扩展名）fail-closed`() = runTest {
        assertTrue(parseFailure("""{"items":[{"name":"x"}]}""").isFailure)
    }

    @Test
    fun `XML 语法损坏时 fail-closed`() = runTest {
        val xml = document(groupBody = entryXml(title = "未闭合")).replace("</Entry>", "")

        assertTrue(parseFailure(xml).isFailure)
    }

    @Test
    fun `非法 UTF-8 字节序列 fail-closed`() = runTest {
        val bytes = byteArrayOf(
            0x3C, 0x4B, // "<K"
            0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte()
        )

        assertTrue(parseFailureBytes(bytes).isFailure)
    }

    @Test
    fun `UTF-16 BOM 输入 fail-closed`() = runTest {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x3C, 0x00, 0x4B, 0x00)

        assertTrue(parseFailureBytes(bytes).isFailure)
    }

    @Test
    fun `受保护值 Protected 为 True 的字段被跳过并记警告但条目仍导入`() = runTest {
        val xml = document(
            groupBody = entryXml(
                title = "受保护条目",
                username = "user",
                password = CIPHER_TEXT,
                passwordProtected = true
            )
        )

        val batch = parseSuccess(xml)

        assertEquals(1, batch.report.parsed)
        val entry = batch.entries.single()
        assertEquals("受保护条目", entry.title)
        assertEquals(0, entry.password.size)
        assertTrue(
            batch.report.warnings.any { it.reason == ImportWarningReason.PROTECTED_VALUE_SKIPPED.code }
        )
    }

    @Test
    fun `历史修订子树不导入且记一条文档级警告`() = runTest {
        val history = "<History>${entryXml(title = "历史版本", password = "old")}</History>"
        val xml = document(groupBody = entryXml(title = "当前版本").replace("</Entry>", "$history</Entry>"))

        val batch = parseSuccess(xml)

        assertEquals(1, batch.report.parsed)
        assertEquals("当前版本", batch.entries.single().title)
        assertTrue(batch.report.warnings.any { it.reason == ImportWarningReason.HISTORY_IGNORED.code })
    }

    @Test
    fun `自定义字段不导入且记一条文档级警告`() = runTest {
        val customField = stringXml(key = "自定义字段", value = "自定义值")
        val xml = document(groupBody = entryXml(title = "带自定义字段").replace("</Entry>", "$customField</Entry>"))

        val batch = parseSuccess(xml)

        assertEquals(1, batch.report.parsed)
        assertTrue(batch.report.warnings.any { it.reason == ImportWarningReason.CUSTOM_FIELD_DROPPED.code })
    }

    @Test
    fun `标题用户名密码全空的条目计入 skipped 并记警告`() = runTest {
        val xml = document(groupBody = entryXml(title = "", username = "", password = ""))

        val batch = parseSuccess(xml)

        assertEquals(0, batch.report.parsed)
        assertEquals(1, batch.report.skipped)
        assertTrue(batch.report.warnings.any { it.reason == ImportWarningReason.MISSING_REQUIRED_VALUE.code })
    }

    @Test
    fun `空文档解析为零产出而非失败`() = runTest {
        val xml = """<?xml version="1.0" encoding="utf-8"?><KeePassFile><Meta/><Root/></KeePassFile>"""

        val batch = parseSuccess(xml)

        assertEquals(0, batch.report.parsed)
        assertTrue(batch.report.isEmpty)
    }

    @Test
    fun `条目数超出防御性上限时整批 fail-closed`() = runTest {
        val body = StringBuilder(OVER_LIMIT_ENTRIES * ENTRY_XML_ESTIMATED_CHARS)
        repeat(OVER_LIMIT_ENTRIES) { index -> body.append(entryXml(title = "批量 $index")) }

        val result = importer.parse(document(groupBody = body.toString()).toByteArray(Charsets.UTF_8), FILE_NAME)

        assertTrue("超出条目上限必须 fail-closed", result.isFailure)
    }

    @Test
    fun `分组嵌套深度超出上限时 fail-closed`() = runTest {
        val deepBody = buildString {
            repeat(OVER_LIMIT_DEPTH) { append("<Group><Name>g</Name>") }
            repeat(OVER_LIMIT_DEPTH) { append("</Group>") }
        }

        val result = importer.parse(document(groupBody = deepBody).toByteArray(Charsets.UTF_8), FILE_NAME)

        assertTrue("超出嵌套深度上限必须 fail-closed", result.isFailure)
    }

    @Test
    fun `UTF-8 BOM 前缀不影响解析`() = runTest {
        val bytes = UTF8_BOM + document(groupBody = entryXml(title = "BOM 条目")).toByteArray(Charsets.UTF_8)

        val batch = parseSuccessBytes(bytes)

        assertEquals("BOM 条目", batch.entries.single().title)
    }

    // ---------- 辅助 ----------

    private suspend fun parseSuccess(xml: String): ImportBatch =
        parseSuccessBytes(xml.toByteArray(Charsets.UTF_8))

    /**
     * 断言解析成功；失败时把**真实失败原因**（异常类名 + 消息）带进断言消息。
     *
     * 说明：解析器的异常消息恒为结构性描述（根元素不符 / 编码非法 / 超限…），
     * 绝不携带条目字段明文，故可安全进断言消息；密码断言本身仍只用长度与 `contentEquals`。
     */
    private suspend fun parseSuccessBytes(bytes: ByteArray): ImportBatch {
        val result = importer.parse(bytes, FILE_NAME)
        if (result is KdbxResult.Failure) {
            throw AssertionError(
                "期望解析成功但返回了 Failure: " +
                    "${result.error.javaClass.name} / ${result.error.message}"
            )
        }
        return (result as KdbxResult.Success).data
    }

    private suspend fun parseFailure(xml: String): KdbxResult.Failure =
        parseFailureBytes(xml.toByteArray(Charsets.UTF_8))

    private suspend fun parseFailureBytes(bytes: ByteArray): KdbxResult.Failure {
        val result = importer.parse(bytes, FILE_NAME)
        assertTrue("期望 fail-closed 返回 Failure 但解析成功", result.isFailure)
        return result as KdbxResult.Failure
    }

    private companion object {
        const val FILE_NAME = "keepass_export.xml"
        const val FAKE_PASSWORD = "fake-password-123"
        const val FAKE_ESCAPED_PASSWORD = "fake&pass"
        const val CIPHER_TEXT = "0Yb1kzZ9c2l0Q2hhcg=="
        const val TOTP_SEED = "JBSWY3DPEHPK3PXP"

        const val UUID_ROOT = "AAAAAAAAAAAAAAAAAAAAAA=="
        const val UUID_GROUP_OUTER = "AQEBAQEBAQEBAQEBAQEBAQ=="
        const val UUID_GROUP_INNER = "AgICAgICAgICAgICAgICAg=="
        const val UUID_RECYCLE_BIN = "AwMDAwMDAwMDAwMDAwMDAw=="

        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        /** 恰好越过 `ImportLimits.MAX_ENTRIES_PER_IMPORT` 的条目数。 */
        const val OVER_LIMIT_ENTRIES = 10_001
        const val ENTRY_XML_ESTIMATED_CHARS = 160

        /** 恰好越过 `ImportLimits.MAX_XML_DEPTH` 的嵌套层数。 */
        const val OVER_LIMIT_DEPTH = 70

        /**
         * 完整文档：根分组固定为 `Root`（其名称不入 groupPath）。
         *
         * `trimStart()` 是必需的：模板插入了多行 `groupBody` 时会拉低共同缩进，
         * 使 `trimIndent()` 退化为空操作，导致 `<?xml ...?>` 声明前残留空白——
         * XML 规范要求声明必须位于文档起始处，否则解析器直接拒绝。
         */
        fun document(groupBody: String, metaBody: String = ""): String = """
            <?xml version="1.0" encoding="utf-8"?>
            <KeePassFile>
              <Meta>$metaBody</Meta>
              <Root>
                <Group>
                  <UUID>$UUID_ROOT</UUID>
                  <Name>Root</Name>
                  $groupBody
                </Group>
              </Root>
            </KeePassFile>
        """.trimIndent().trimStart()

        fun groupXml(uuid: String, name: String, body: String): String = """
            <Group>
              <UUID>$uuid</UUID>
              <Name>$name</Name>
              $body
            </Group>
        """.trimIndent().trimStart()

        fun stringXml(key: String, value: String, protectedValue: Boolean = false): String {
            val attribute = if (protectedValue) """ Protected="True"""" else ""
            return "<String><Key>$key</Key><Value$attribute>$value</Value></String>"
        }

        fun entryXml(
            title: String? = null,
            username: String? = null,
            password: String? = null,
            url: String? = null,
            notes: String? = null,
            otp: String? = null,
            otpKey: String = "otp",
            passwordProtected: Boolean = false
        ): String {
            val builder = StringBuilder("<Entry>")
            title?.let { builder.append(stringXml("Title", it)) }
            username?.let { builder.append(stringXml("UserName", it)) }
            password?.let { builder.append(stringXml("Password", it, passwordProtected)) }
            url?.let { builder.append(stringXml("URL", it)) }
            notes?.let { builder.append(stringXml("Notes", it)) }
            otp?.let { builder.append(stringXml(otpKey, it)) }
            builder.append("</Entry>")
            return builder.toString()
        }
    }
}
