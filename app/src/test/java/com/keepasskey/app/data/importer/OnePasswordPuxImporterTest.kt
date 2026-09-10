package com.keepasskey.app.data.importer

import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 1Password 1PUX 解析器单测（ISSUE-P3-19 交付物 2 + 交付物 3）。
 *
 * ZIP 容器在测试内用 [ZipOutputStream] **内存生成**（不引入任何外部测试资源文件）。覆盖：字段映射、
 * 账户/保险库两级分组降级、TOTP 三种来源、非登录类别与非法节点跳过、编码（BOM / 非 ASCII /
 * 非法 UTF-8）、以及 Zip Slip / Zip 炸弹四道闸门 / 结构损坏等全部 fail-closed 分支。
 *
 * **敏感数据断言纪律**：密码 / TOTP 一律用长度或 `contentEquals` 判断，
 * 绝不把明文放进断言消息（失败信息里不得出现密码）。
 */
class OnePasswordPuxImporterTest {

    private val importer = OnePasswordPuxImporter()

    @After
    fun tearDown() {
        // 注入的小上限只作用于单个用例，用完立即恢复生产闸门
        importer.archiveLimits = PuxArchiveReader.Limits()
    }

    @Test
    fun `声明数据源为 1PUX 且仅接受 1pux 扩展名`() {
        assertEquals(ImportSource.ONEPASSWORD_1PUX, importer.source)
        assertEquals(setOf("1pux"), importer.supportedExtensions)
    }

    @Test
    fun `登录条目完整映射字段且分组为账户与保险库两级`() = runTest {
        val bytes = archive(exportEntry(exportJson(puxItem())))

        val batch = parseSuccess(bytes)

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
        assertEquals(listOf("示例账户", "私人"), entry.groupPath)
        assertNull(entry.totpSecret)
        assertFalse(entry.deleted)
    }

    @Test
    fun `loginFields 分区字段与对象形态字段三处 TOTP 均被保留`() = runTest {
        val otpauth = "otpauth://totp/Example:user?secret=JBSWY3DPEHPK3PXP&issuer=Example"
        val objectValuedItem = "{" + quote("categoryUuid") + ": " + quote("001") + ", " +
            quote("overview") + ": {" + quote("title") + ": " + quote("对象字段条目") + "}, " +
            quote("details") + ": {" + quote("sections") + ": [{" + quote("fields") + ": [{" +
            quote("value") + ": {" + quote("totp") + ": " + quote(otpauth) + "}}]}]}}"
        val items = puxItem(title = "行内字段", totp = otpauth) + ", " +
            puxItem(title = "分区字段", sectionTotp = otpauth) + ", " + objectValuedItem

        val batch = parseSuccess(archive(exportEntry(exportJson(items))))

        assertEquals(3, batch.report.parsed)
        batch.entries.forEach { entry ->
            val totp = requireNotNull(entry.totpSecret)
            assertTrue(totp.contentEquals(otpauth.toCharArray()))
        }
    }

    @Test
    fun `非登录类别与非法条目节点均被跳过且计数与警告一一对应`() = runTest {
        val items = listOf(
            puxItem(title = "银行卡", categoryUuid = "002"),
            puxItem(title = "安全笔记", categoryUuid = "003"),
            "\"文本条目\"",
            puxItem(title = "登录条目")
        ).joinToString(", ")

        val batch = parseSuccess(archive(exportEntry(exportJson(items))))

        assertEquals(1, batch.report.parsed)
        assertEquals(3, batch.report.skipped)
        assertEquals(3, batch.report.warnings.size)
        assertEquals("accounts[0].vaults[0].items[0]", batch.report.warnings[0].location)
        assertEquals("accounts[0].vaults[0].items[2]", batch.report.warnings[2].location)
        assertEquals("登录条目", batch.entries.single().title)
    }

    @Test
    fun `缺少 export data 时 fail-closed`() = runTest {
        assertMalformed(archive(ENTRY_ATTRIBUTES to "{\"version\": 3}".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `ZIP 结构损坏时 fail-closed`() = runTest {
        assertMalformed(ByteArray(128) { 0x41 })
    }

    @Test
    fun `export data 结构非法时 fail-closed`() = runTest {
        assertMalformed(archive(exportEntry("{\"accounts\": [")))
        assertMalformed(archive(exportEntry("{\"version\": 3}")))
    }

    @Test
    fun `export data 非 UTF-8 字节序列时 fail-closed 而非静默替换`() = runTest {
        // `{"x":"<0xC3>"}`：0xC3 是截断的双字节序列起始，严格解码必失败
        val truncatedSequence = byteArrayOf(0x7B, 0x22, 0x78, 0x22, 0x3A, 0x22, 0xC3.toByte(), 0x22, 0x7D)

        val failure = parseFailure(archive(ENTRY_EXPORT_DATA to truncatedSequence))

        assertTrue(failure.error is ImportEncodingException)
    }

    @Test
    fun `路径穿越与绝对路径条目名均导致整包拒绝`() = runTest {
        assertMalformed(
            archive(
                "../escape.txt" to "evil".toByteArray(Charsets.UTF_8),
                exportEntry(exportJson(puxItem()))
            )
        )
        assertMalformed(
            archive(
                "/abs/escape.txt" to "evil".toByteArray(Charsets.UTF_8),
                exportEntry(exportJson(puxItem()))
            )
        )
        assertMalformed(
            archive(
                "C:\\escape.txt" to "evil".toByteArray(Charsets.UTF_8),
                exportEntry(exportJson(puxItem()))
            )
        )
    }

    @Test
    fun `条目名合法性判定放行 1PUX 合法条目并拒绝穿越名`() {
        assertTrue(PuxArchiveReader.isSafeEntryName("export.data"))
        assertTrue(PuxArchiveReader.isSafeEntryName("export.attributes"))
        assertTrue(PuxArchiveReader.isSafeEntryName("files/ab12cd.png"))
        assertFalse(PuxArchiveReader.isSafeEntryName("../escape"))
        assertFalse(PuxArchiveReader.isSafeEntryName("files/../../escape"))
        assertFalse(PuxArchiveReader.isSafeEntryName("/abs/escape"))
        assertFalse(PuxArchiveReader.isSafeEntryName("\\abs\\escape"))
        assertFalse(PuxArchiveReader.isSafeEntryName("C:/drive"))
        assertFalse(PuxArchiveReader.isSafeEntryName(""))
        assertFalse(PuxArchiveReader.isSafeEntryName("a\u0000b"))
    }

    @Test
    fun `归档条目数超上限时 fail-closed`() = runTest {
        importer.archiveLimits = PuxArchiveReader.Limits(maxEntries = 2)

        assertLimitExceeded(
            archive(
                "files/a" to ByteArray(8) { 1 },
                "files/b" to ByteArray(8) { 2 },
                exportEntry(exportJson(puxItem()))
            )
        )
    }

    @Test
    fun `单条目体积超上限时 fail-closed`() = runTest {
        importer.archiveLimits = PuxArchiveReader.Limits(maxEntryBytes = 16)

        assertLimitExceeded(archive(exportEntry(exportJson(puxItem()))))
    }

    @Test
    fun `解压总量超上限时 fail-closed`() = runTest {
        importer.archiveLimits = PuxArchiveReader.Limits(maxTotalUncompressedBytes = 16)

        assertLimitExceeded(
            archive(
                "files/big" to ByteArray(1024) { 7 },
                exportEntry(exportJson(puxItem()))
            )
        )
    }

    @Test
    fun `归档自身体积超上限时 fail-closed`() = runTest {
        importer.archiveLimits = PuxArchiveReader.Limits(maxArchiveBytes = 8)

        assertLimitExceeded(archive(exportEntry(exportJson(puxItem()))))
    }

    @Test
    fun `归档内条目数超上限时以上限异常归一为失败`() = runTest {
        val items = List(ImportLimits.MAX_ENTRIES_PER_IMPORT + 1) { "{}" }.joinToString(",")

        assertLimitExceeded(archive(exportEntry(exportJson(items))))
    }

    @Test
    fun `警告数量超上限时折叠为一条截断汇总项且跳过计数不受影响`() = runTest {
        val skippedCount = ImportLimits.MAX_WARNINGS * 2
        val items = List(skippedCount) { puxItem(title = "非登录", categoryUuid = "002") }.joinToString(",")

        val batch = parseSuccess(archive(exportEntry(exportJson(items))))

        assertEquals(0, batch.report.parsed)
        assertEquals(skippedCount, batch.report.skipped)
        assertEquals(ImportLimits.MAX_WARNINGS + 1, batch.report.warnings.size)
        val summary = batch.report.warnings.last()
        assertEquals(ImportWarningLocation.DOCUMENT, summary.location)
        assertEquals(ImportWarningReason.WARNINGS_TRUNCATED.code, summary.reason)
    }

    @Test
    fun `中文与 emoji 字段原样保留且分组取自中文账户与保险库名`() = runTest {
        val password = "密码🔒-Ω"
        // 备注中的换行按 JSON 规范写作两字符转义 `\n`（真实导出即如此），解析后还原为单个换行
        val item = puxItem(
            title = "邮箱😀",
            username = "用户@例子.中国",
            password = password,
            notes = "第一行\\n第二行·备注"
        )
        val json = exportJson(item, accountName = "张三", vaultName = "个人库")

        val batch = parseSuccess(archive(exportEntry(json)))

        val entry = batch.entries.single()
        assertEquals("邮箱😀", entry.title)
        assertEquals("用户@例子.中国", entry.username)
        assertEquals(password.length, entry.password.size)
        assertTrue(entry.password.contentEquals(password.toCharArray()))
        assertEquals("第一行\n第二行·备注", entry.notes)
        assertEquals(listOf("张三", "个人库"), entry.groupPath)
    }

    @Test
    fun `state 为 deleted 时标记为已删除而其他取值不标记`() = runTest {
        val items = puxItem(title = "已删除", state = "deleted") + ", " + puxItem(title = "已归档", state = "archived")

        val batch = parseSuccess(archive(exportEntry(exportJson(items))))

        assertTrue(batch.entries[0].deleted)
        assertFalse(batch.entries[1].deleted)
    }

    @Test
    fun `账户名与保险库名均为空时落根分组并记警告`() = runTest {
        val bytes = archive(exportEntry(exportJson(puxItem(), accountName = "", vaultName = "")))

        val batch = parseSuccess(bytes)

        assertTrue(batch.entries.single().groupPath.isEmpty())
        assertEquals(0, batch.report.skipped)
        assertEquals(1, batch.report.warnings.size)
    }

    @Test
    fun `保险库名缺失时分组降级为账户名单级`() = runTest {
        val bytes = archive(exportEntry(exportJson(puxItem(), vaultName = "")))

        val batch = parseSuccess(bytes)

        assertEquals(listOf("示例账户"), batch.entries.single().groupPath)
        assertEquals(0, batch.report.warnings.size)
    }

    @Test
    fun `登录条目缺少 loginFields 时以空凭据导入且不失败`() = runTest {
        val item = "{" + quote("categoryUuid") + ": " + quote("001") + ", " +
            quote("overview") + ": {" + quote("title") + ": " + quote("无字段条目") + "}}"

        val batch = parseSuccess(archive(exportEntry(exportJson(item))))

        val entry = batch.entries.single()
        assertEquals("无字段条目", entry.title)
        assertEquals("", entry.username)
        assertEquals(0, entry.password.size)
        assertEquals("", entry.url)
        assertNull(entry.totpSecret)
        assertEquals(0, batch.report.warnings.size)
    }

    @Test
    fun `标题为空白时归一为空串并记警告`() = runTest {
        val batch = parseSuccess(archive(exportEntry(exportJson(puxItem(title = "   ")))))

        assertEquals("", batch.entries.single().title)
        assertEquals(1, batch.report.warnings.size)
    }

    @Test
    fun `URL 为空时回退到 overview 的 urls 数组首项`() = runTest {
        val item = "{" + quote("categoryUuid") + ": " + quote("001") + ", " +
            quote("overview") + ": {" + quote("title") + ": " + quote("URL 回退") + ", " +
            quote("urls") + ": [{" + quote("url") + ": " + quote("https://fallback.example") + "}]}, " +
            quote("details") + ": {}}"

        val batch = parseSuccess(archive(exportEntry(exportJson(item))))

        assertEquals("https://fallback.example", batch.entries.single().url)
    }

    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, payload) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(payload)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun exportEntry(json: String): Pair<String, ByteArray> =
        ENTRY_EXPORT_DATA to json.toByteArray(Charsets.UTF_8)

    private suspend fun parseSuccess(bytes: ByteArray): ImportBatch {
        val result = importer.parse(bytes, FILE_NAME)
        assertTrue("期望解析成功但返回了 Failure", result.isSuccess)
        return result.getOrThrow()
    }

    private suspend fun parseFailure(bytes: ByteArray): KdbxResult.Failure {
        val result = importer.parse(bytes, FILE_NAME)
        assertTrue("期望 fail-closed 返回 Failure 但解析成功", result.isFailure)
        return result as KdbxResult.Failure
    }

    /** 断言结构性非法：必须是 [ImportFormatException] 系（归入 MALFORMED）。 */
    private suspend fun assertMalformed(bytes: ByteArray) {
        assertTrue("期望 MALFORMED 类失败", parseFailure(bytes).error is ImportFormatException)
    }

    /** 断言超出防御性上限：必须是 [ImportLimitExceededException]（归入 LIMIT_EXCEEDED）。 */
    private suspend fun assertLimitExceeded(bytes: ByteArray) {
        assertTrue("期望 LIMIT_EXCEEDED 类失败", parseFailure(bytes).error is ImportLimitExceededException)
    }

    private companion object {
        const val FILE_NAME = "export.1pux"
        const val ENTRY_EXPORT_DATA = "export.data"
        const val ENTRY_ATTRIBUTES = "export.attributes"
        const val FAKE_PASSWORD = "fake-password-123"

        /** JSON 字符串字面量包裹（测试取值均不含 `"` 与 `\`）。 */
        fun quote(value: String): String = "\"" + value + "\""

        /** 组装 `export.data`：账户 → 保险库 → 条目（官方 1PUX 三层结构）。 */
        fun exportJson(items: String, accountName: String = "示例账户", vaultName: String = "私人"): String =
            "{" + quote("accounts") + ": [{" + quote("attrs") + ": {" +
                quote("accountName") + ": " + quote(accountName) + ", " +
                quote("name") + ": " + quote(accountName) + "}, " +
                quote("vaults") + ": [{" + quote("attrs") + ": {" + quote("name") + ": " + quote(vaultName) + "}, " +
                quote("items") + ": [" + items + "]}]}]}"

        /** 组装一条 1PUX 条目（默认 LOGIN 类别 `001`）。 */
        fun puxItem(
            title: String = "示例站点",
            username: String = "user@example.com",
            password: String = FAKE_PASSWORD,
            totp: String? = null,
            sectionTotp: String? = null,
            url: String = "https://example.com",
            notes: String = "备注内容",
            categoryUuid: String = "001",
            state: String = "active"
        ): String {
            val loginFields = mutableListOf(
                field("username", username),
                field("password", password)
            )
            if (totp != null) loginFields += field("totp", totp)
            val details = buildString {
                append("{" + quote("loginFields") + ": [" + loginFields.joinToString(", ") + "]")
                if (sectionTotp != null) {
                    append(", " + quote("sections") + ": [{" + quote("name") + ": " + quote("Section_1") + ", " +
                        quote("fields") + ": [{" + quote("designation") + ": " + quote("totp") + ", " +
                        quote("value") + ": " + quote(sectionTotp) + "}]}]")
                }
                append(", " + quote("notesPlain") + ": " + quote(notes) + "}")
            }
            return "{" + quote("categoryUuid") + ": " + quote(categoryUuid) + ", " +
                quote("state") + ": " + quote(state) + ", " +
                quote("overview") + ": {" + quote("title") + ": " + quote(title) + ", " +
                quote("url") + ": " + quote(url) + "}, " +
                quote("details") + ": " + details + "}"
        }

        /** 单个 `loginFields` 元素（designation + value）。 */
        fun field(designation: String, value: String): String =
            "{" + quote("designation") + ": " + quote(designation) + ", " + quote("value") + ": " + quote(value) + "}"
    }
}
