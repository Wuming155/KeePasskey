package com.keepasskey.app.data.importer

import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bitwarden 明文 JSON 解析器单测（ISSUE-P3-19 交付物 1）。
 *
 * 覆盖：字段映射（含 `uris[0]` / `totp` / `folderId`）、多分组与悬空分组、非登录类型跳过、
 * 名称兜底语义、编码（UTF-8 BOM / CRLF / 非 ASCII / JSON 转义），以及全部 fail-closed 分支。
 *
 * **敏感数据断言纪律**：密码 / TOTP 一律用长度或 `contentEquals` 判断，
 * 绝不把明文放进断言消息（失败信息里不得出现密码）。
 */
class BitwardenJsonImporterTest {

    private val importer = BitwardenJsonImporter()

    @Test
    fun `声明数据源为 Bitwarden JSON 且仅接受 json 扩展名`() {
        assertEquals(ImportSource.BITWARDEN_JSON, importer.source)
        assertEquals(setOf("json"), importer.supportedExtensions)
    }

    @Test
    fun `登录条目完整映射标题用户名密码URL备注与单级分组`() = runTest {
        val json = bitwardenJson(
            folders = "[{\"id\": \"f-1\", \"name\": \"工作\"}]",
            items = loginItem(folderId = "f-1")
        )

        val batch = parseSuccess(json)

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
        assertEquals(listOf("工作"), entry.groupPath)
        assertFalse(entry.deleted)
    }

    @Test
    fun `TOTP 同时支持 otpauth URI 与 Base32 裸种子原文`() = runTest {
        val otpauth = "otpauth://totp/Example:user?secret=JBSWY3DPEHPK3PXP&issuer=Example"
        val seed = "JBSWY3DPEHPK3PXP"
        val json = bitwardenJson(
            items = loginItem(name = "OTP 条目", totp = otpauth) + "," + loginItem(name = "种子条目", totp = seed)
        )

        val batch = parseSuccess(json)

        assertEquals(2, batch.report.parsed)
        val first = requireNotNull(batch.entries[0].totpSecret)
        assertTrue(first.contentEquals(otpauth.toCharArray()))
        val second = requireNotNull(batch.entries[1].totpSecret)
        assertEquals(seed.length, second.size)
        assertTrue(second.contentEquals(seed.toCharArray()))
    }

    @Test
    fun `多分组与未被引用的空分组互不串扰`() = runTest {
        val json = bitwardenJson(
            folders = "[{\"id\": \"f-1\", \"name\": \"工作\"}, " +
                "{\"id\": \"f-2\", \"name\": \"个人\"}, " +
                "{\"id\": \"f-3\", \"name\": \"未使用\"}]",
            items = loginItem(name = "A", folderId = "f-1") + "," + loginItem(name = "B", folderId = "f-2")
        )

        val batch = parseSuccess(json)

        assertEquals(2, batch.report.parsed)
        assertEquals(listOf(listOf("工作"), listOf("个人")), batch.entries.map { it.groupPath })
        assertEquals(0, batch.report.warnings.size)
    }

    @Test
    fun `folderId 悬空时落根分组并记警告`() = runTest {
        val json = bitwardenJson(
            folders = "[{\"id\": \"f-1\", \"name\": \"工作\"}]",
            items = loginItem(folderId = "missing-id")
        )

        val batch = parseSuccess(json)

        val entry = batch.entries.single()
        assertTrue(entry.groupPath.isEmpty())
        assertEquals(0, batch.report.skipped)
        assertEquals(1, batch.report.warnings.size)
        assertEquals("items[0]", batch.report.warnings.single().location)
    }

    @Test
    fun `非登录类型条目被跳过且计数与警告一一对应`() = runTest {
        val json = bitwardenJson(
            items = listOf(
                "{\"type\": 2, \"name\": \"安全笔记\"}",
                "{\"type\": 3, \"name\": \"银行卡\"}",
                "{\"type\": 4, \"name\": \"身份\"}",
                loginItem(name = "登录条目")
            ).joinToString(",")
        )

        val batch = parseSuccess(json)

        assertEquals(1, batch.report.parsed)
        assertEquals(3, batch.report.skipped)
        assertEquals(3, batch.report.warnings.size)
        assertEquals("items[0]", batch.report.warnings[0].location)
        assertEquals("items[2]", batch.report.warnings[2].location)
        assertEquals("登录条目", batch.entries.single().title)
    }

    @Test
    fun `缺少 login 对象时以空凭据导入名称与备注并记警告`() = runTest {
        val json = bitwardenJson(items = "{\"type\": 1, \"name\": \"无凭据条目\", \"notes\": \"保留备注\"}")

        val batch = parseSuccess(json)

        assertEquals(1, batch.report.parsed)
        assertEquals(0, batch.report.skipped)
        val entry = batch.entries.single()
        assertEquals("无凭据条目", entry.title)
        assertEquals("", entry.username)
        assertEquals(0, entry.password.size)
        assertEquals("", entry.url)
        assertNull(entry.totpSecret)
        assertEquals("保留备注", entry.notes)
        assertEquals(1, batch.report.warnings.size)
    }

    @Test
    fun `名称为空白时归一为空串并记警告交由落库层回退`() = runTest {
        val json = bitwardenJson(items = loginItem(name = "   "))

        val batch = parseSuccess(json)

        assertEquals("", batch.entries.single().title)
        assertEquals(1, batch.report.warnings.size)
    }

    @Test
    fun `deletedDate 非空标记为已删除而缺失时不标记`() = runTest {
        val json = bitwardenJson(
            items = loginItem(name = "已删除", deletedDate = "2025-01-01T00:00:00.000Z") + "," +
                loginItem(name = "正常")
        )

        val batch = parseSuccess(json)

        assertTrue(batch.entries[0].deleted)
        assertFalse(batch.entries[1].deleted)
    }

    @Test
    fun `加密导出 encrypted 为 true 时 fail-closed`() = runTest {
        val json = bitwardenJson(
            items = loginItem(),
            encrypted = true
        )

        val failure = parseFailure(json)

        assertTrue(failure.error is ImportFormatException)
    }

    @Test
    fun `根节点不是对象或缺少 items 数组时 fail-closed`() = runTest {
        assertTrue(parseFailure("[]").error is ImportFormatException)
        assertTrue(parseFailure("{\"encrypted\": false, \"folders\": []}").error is ImportFormatException)
        assertTrue(parseFailure("{\"items\": null}").error is ImportFormatException)
        assertTrue(parseFailure("\"文本\"").error is ImportFormatException)
    }

    @Test
    fun `JSON 语法损坏时 fail-closed`() = runTest {
        assertTrue(parseFailure("{\"items\": [{\"type\": 1, }]}").error is ImportFormatException)
        assertTrue(parseFailure("{\"items\": [").error is ImportFormatException)
        assertTrue(parseFailure("{\"items\": []} trailing").error is ImportFormatException)
        assertTrue(parseFailure("{\"items\": [{\"name\": \"未闭合}]").error is ImportFormatException)
    }

    @Test
    fun `空输入 fail-closed`() = runTest {
        val result = importer.parse(ByteArray(0), "empty.json")

        assertTrue(result.isFailure)
        assertTrue((result as KdbxResult.Failure).error is ImportFormatException)
    }

    @Test
    fun `条目数超上限时以上限异常归一为失败`() = runTest {
        val itemCount = ImportLimits.MAX_ENTRIES_PER_IMPORT + 1
        val items = List(itemCount) { "{\"type\": 1}" }.joinToString(",")

        val failure = parseFailure(bitwardenJson(items = items))

        assertTrue(failure.error is ImportLimitExceededException)
    }

    @Test
    fun `输入体积超上限时以上限异常归一为失败`() = runTest {
        val oversized = ByteArray(ImportLimits.MAX_IMPORT_BYTES + 1)

        val result = importer.parse(oversized, "huge.json")

        assertTrue(result.isFailure)
        assertTrue((result as KdbxResult.Failure).error is ImportLimitExceededException)
    }

    @Test
    fun `警告数量超上限时折叠为一条截断汇总项且跳过计数不受影响`() = runTest {
        val skippedCount = ImportLimits.MAX_WARNINGS * 2
        val items = List(skippedCount) { "{\"type\": 2}" }.joinToString(",")

        val batch = parseSuccess(bitwardenJson(items = items))

        assertEquals(0, batch.report.parsed)
        assertEquals(skippedCount, batch.report.skipped)
        assertEquals(ImportLimits.MAX_WARNINGS + 1, batch.report.warnings.size)
        val summary = batch.report.warnings.last()
        assertEquals(ImportWarningLocation.DOCUMENT, summary.location)
        assertEquals(ImportWarningReason.WARNINGS_TRUNCATED.code, summary.reason)
    }

    @Test
    fun `非 UTF-8 字节序列或 UTF-16 BOM 时 fail-closed 而非静默替换`() = runTest {
        // `{"x":"<0xC3>"}`：0xC3 是截断的双字节序列起始，严格解码必失败
        val truncatedSequence = byteArrayOf(0x7B, 0x22, 0x78, 0x22, 0x3A, 0x22, 0xC3.toByte(), 0x22, 0x7D)
        val utf16Bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            bitwardenJson(items = loginItem()).toByteArray(Charsets.UTF_8)

        assertTrue(parseFailureBytes(truncatedSequence).error is ImportEncodingException)
        assertTrue(parseFailureBytes(utf16Bom).error is ImportEncodingException)
    }

    @Test
    fun `UTF-8 BOM 与 CRLF 换行可正常解析`() = runTest {
        val json = "\uFEFF{\r\n  \"encrypted\": false,\r\n" +
            "  \"folders\": [{\"id\": \"f-1\", \"name\": \"工作\"}],\r\n" +
            "  \"items\": [" + loginItem(folderId = "f-1") + "]\r\n}"

        val batch = parseSuccess(json)

        assertEquals(1, batch.report.parsed)
        assertEquals(listOf("工作"), batch.entries.single().groupPath)
    }

    @Test
    fun `中文 emoji 与非 ASCII 字段原样保留`() = runTest {
        val password = "密码🔒-Ω"
        // 备注中的换行按 JSON 规范写作两字符转义 `\n`（真实导出即如此），解析后还原为单个换行
        val json = bitwardenJson(
            items = loginItem(
                name = "邮箱😀",
                username = "用户@例子.中国",
                password = password,
                notes = "第一行\\n第二行·备注"
            )
        )

        val batch = parseSuccess(json)

        val entry = batch.entries.single()
        assertEquals("邮箱😀", entry.title)
        assertEquals("用户@例子.中国", entry.username)
        assertEquals(password.length, entry.password.size)
        assertTrue(entry.password.contentEquals(password.toCharArray()))
        assertEquals("第一行\n第二行·备注", entry.notes)
    }

    @Test
    fun `JSON 转义序列与 Unicode 转义被正确解码`() = runTest {
        // 原始 JSON：name 用 \uXXXX（含代理对）书写，password 含 \" 与 \\ 两种转义
        val json = "{\"encrypted\": false, \"items\": [{\"type\": 1, " +
            "\"name\": \"\\u4e2d\\u6587\\uD83D\\uDE00\", " +
            "\"login\": {\"username\": \"u\", \"password\": \"p\\\"w\\\\x\"}}]}"
        val expectedPassword = "p\"w\\x"

        val batch = parseSuccess(json)

        val entry = batch.entries.single()
        assertEquals("中文😀", entry.title)
        assertEquals(expectedPassword.length, entry.password.size)
        assertTrue(entry.password.contentEquals(expectedPassword.toCharArray()))
    }

    private suspend fun parseSuccess(json: String): ImportBatch {
        val result = importer.parse(json.toByteArray(Charsets.UTF_8), FILE_NAME)
        assertTrue("期望解析成功但返回了 Failure", result.isSuccess)
        return result.getOrThrow()
    }

    private suspend fun parseFailure(json: String): KdbxResult.Failure =
        parseFailureBytes(json.toByteArray(Charsets.UTF_8))

    private suspend fun parseFailureBytes(bytes: ByteArray): KdbxResult.Failure {
        val result = importer.parse(bytes, FILE_NAME)
        assertTrue("期望 fail-closed 返回 Failure 但解析成功", result.isFailure)
        return result as KdbxResult.Failure
    }

    private companion object {
        const val FILE_NAME = "bitwarden_export.json"
        const val FAKE_PASSWORD = "fake-password-123"

        /** JSON 字符串字面量包裹（测试取值均不含 `"` 与 `\`；转义场景另有显式用例）。 */
        fun quote(value: String): String = "\"" + value + "\""

        fun bitwardenJson(items: String, folders: String = "[]", encrypted: Boolean = false): String =
            "{" + quote("encrypted") + ": " + encrypted + ", " + quote("folders") + ": " + folders +
                ", " + quote("items") + ": [" + items + "]}"

        /** 组装一条 `type == 1` 的登录条目。 */
        fun loginItem(
            name: String = "示例站点",
            username: String = "user@example.com",
            password: String = FAKE_PASSWORD,
            totp: String? = null,
            uri: String? = "https://example.com",
            notes: String = "备注内容",
            folderId: String? = null,
            deletedDate: String? = null
        ): String {
            val loginFields = mutableListOf(
                quote("username") + ": " + quote(username),
                quote("password") + ": " + quote(password)
            )
            if (totp != null) loginFields += quote("totp") + ": " + quote(totp)
            if (uri != null) loginFields += quote("uris") + ": [{" + quote("uri") + ": " + quote(uri) + "}]"
            val itemFields = mutableListOf(
                quote("type") + ": 1",
                quote("name") + ": " + quote(name),
                quote("notes") + ": " + quote(notes),
                quote("login") + ": {" + loginFields.joinToString(", ") + "}"
            )
            if (folderId != null) itemFields += quote("folderId") + ": " + quote(folderId)
            if (deletedDate != null) itemFields += quote("deletedDate") + ": " + quote(deletedDate)
            return "{" + itemFields.joinToString(", ") + "}"
        }
    }
}
