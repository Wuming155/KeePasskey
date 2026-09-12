package com.keepasskey.app.data.importer

import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览器密码导出 CSV 解析器单测（ISSUE-P3-19 交付物 3）。
 *
 * 覆盖：按表头名映射（列序无关、大小写无关、别名列）、RFC 4180 引号语义（字段内逗号 /
 * 字段内换行 / `""` 转义）、CRLF 与 LF 混用、UTF-8 BOM 剥离、非法 UTF-8 替换告警，
 * 以及全部 fail-closed 分支（缺 `name` / `password` 列、空表头、UTF-16 编码、条目数超限）。
 *
 * **敏感数据断言纪律**：密码一律用长度或 `contentEquals` 判定，绝不把明文放进断言消息。
 */
class BrowserCsvImporterTest {

    private val importer = BrowserCsvImporter()

    @Test
    fun `声明数据源为浏览器 CSV 且仅接受 csv 扩展名`() {
        assertEquals(ImportSource.BROWSER_CSV, importer.source)
        assertEquals(setOf("csv"), importer.supportedExtensions)
    }

    @Test
    fun `按表头名映射字段且列序变化不影响结果`() = runTest {
        val csv = "password,url,username,name\n$FAKE_PASSWORD,https://example.com,user@example.com,示例站点\n"

        val batch = parseSuccess(csv)

        assertEquals(1, batch.report.parsed)
        val entry = batch.entries.single()
        assertEquals("示例站点", entry.title)
        assertEquals("user@example.com", entry.username)
        assertEquals("https://example.com", entry.url)
        assertEquals(FAKE_PASSWORD.length, entry.password.size)
        assertTrue(entry.password.contentEquals(FAKE_PASSWORD.toCharArray()))
        assertTrue(entry.groupPath.isEmpty())
    }

    @Test
    fun `表头大小写与空白不敏感且支持 note 别名列`() = runTest {
        val csv = " Name , URL , UserName , Password , Note \n" +
            "站点,https://a.example,user,$FAKE_PASSWORD,备注内容\n"

        val batch = parseSuccess(csv)

        val entry = batch.entries.single()
        assertEquals("站点", entry.title)
        assertEquals("备注内容", entry.notes)
        assertEquals(FAKE_PASSWORD.length, entry.password.size)
    }

    @Test
    fun `带引号字段内的逗号与转义双引号如实解析`() = runTest {
        // 第 1 列含逗号（必须整体加引号）；末列 "他说 ""你好""" 为 RFC 4180 双写引号转义
        val csv = "name,url,username,password,note\n" +
            "\"站点, 含逗号\",https://a.example,user,\"$FAKE_PASSWORD\",\"他说 \"\"你好\"\"\"\n"

        val batch = parseSuccess(csv)

        val entry = batch.entries.single()
        assertEquals("站点, 含逗号", entry.title)
        assertEquals("他说 \"你好\"", entry.notes)
        assertEquals(FAKE_PASSWORD.length, entry.password.size)
        assertTrue(entry.password.contentEquals(FAKE_PASSWORD.toCharArray()))
    }

    @Test
    fun `引号内换行不切分记录且换行原样保留在字段值中`() = runTest {
        val csv = "name,url,username,password,note\n" +
            "\"多行备注站点\",https://a.example,user,$FAKE_PASSWORD,\"第一行\n第二行\"\n"

        val batch = parseSuccess(csv)

        assertEquals(1, batch.report.parsed)
        assertEquals("第一行\n第二行", batch.entries.single().notes)
    }

    @Test
    fun `CRLF 与 LF 混用以及 UTF-8 BOM 前缀均可解析`() = runTest {
        val csv = "\uFEFFname,url,username,password\r\n" +
            "站点甲,https://a.example,user1,$FAKE_PASSWORD\n" +
            "站点乙,https://b.example,user2,$FAKE_PASSWORD\r\n"

        val batch = parseSuccess(csv)

        assertEquals(2, batch.report.parsed)
        assertEquals(listOf("站点甲", "站点乙"), batch.entries.map { it.title })
        assertEquals(USER_FIELD_PREFIX.length + 1, batch.entries[0].username.length)
    }

    @Test
    fun `真实 UTF-8 BOM 字节前缀被剥离且不污染首列表头`() = runTest {
        val bytes = UTF8_BOM + "name,url,username,password\n站点,https://a.example,user,$FAKE_PASSWORD\n"
            .toByteArray(Charsets.UTF_8)

        val batch = parseSuccessBytes(bytes)

        assertEquals(1, batch.report.parsed)
        assertEquals("站点", batch.entries.single().title)
    }

    @Test
    fun `缺 password 列时 fail-closed`() = runTest {
        val csv = "name,url,username\n站点,https://a.example,user\n"

        val failure = parseFailure(csv)

        assertEquals(
            ImportFailureReason.MISSING_REQUIRED_COLUMN,
            ImportFailureReason.classify(failure.error)
        )
    }

    @Test
    fun `缺 name 列时 fail-closed`() = runTest {
        val csv = "url,username,password\nhttps://a.example,user,$FAKE_PASSWORD\n"

        assertTrue(parseFailure(csv).isFailure)
    }

    @Test
    fun `空文件（无表头）时 fail-closed`() = runTest {
        assertTrue(parseFailure("").isFailure)
    }

    @Test
    fun `UTF-16 编码的文件 fail-closed`() = runTest {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x6E, 0x00, 0x61, 0x00)

        val failure = parseFailureBytes(bytes)

        assertEquals(ImportFailureReason.ENCODING, ImportFailureReason.classify(failure.error))
    }

    @Test
    fun `行列数与表头不一致时记警告但仍尽可能导入`() = runTest {
        val csv = "name,url,username,password\n" +
            "站点,https://a.example,user,$FAKE_PASSWORD,多余列\n"

        val batch = parseSuccess(csv)

        assertEquals(1, batch.report.parsed)
        assertTrue(
            batch.report.warnings.any { it.reason == ImportWarningReason.COLUMN_COUNT_MISMATCH.code }
        )
    }

    @Test
    fun `全空行与仅含空白的行不会被当作条目`() = runTest {
        val csv = "name,url,username,password\n" +
            "站点,https://a.example,user,$FAKE_PASSWORD\n" +
            "\n" +
            ",,,\n"

        val batch = parseSuccess(csv)

        assertEquals(1, batch.report.parsed)
        assertEquals(0, batch.report.skipped)
    }

    @Test
    fun `标题用户名密码全空的数据行计入 skipped 并记警告`() = runTest {
        val csv = "name,url,username,password\n" +
            ",https://a.example,,\n"

        val batch = parseSuccess(csv)

        assertEquals(0, batch.report.parsed)
        assertEquals(1, batch.report.skipped)
        assertTrue(batch.report.warnings.any { it.reason == ImportWarningReason.MISSING_REQUIRED_VALUE.code })
    }

    @Test
    fun `非法 UTF-8 字节按替换字符处理并记文档级警告`() = runTest {
        val prefix = "name,url,username,password\n站点,https://a.example/".toByteArray(Charsets.UTF_8)
        val suffix = "/x,user,$FAKE_PASSWORD\n".toByteArray(Charsets.UTF_8)
        val bytes = prefix + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + suffix

        val batch = parseSuccessBytes(bytes)

        assertEquals(1, batch.report.parsed)
        assertTrue(batch.report.warnings.any { it.reason == ImportWarningReason.INVALID_UTF8.code })
    }

    @Test
    fun `login_ 前缀别名列与 login_totp 列被正确映射`() = runTest {
        val csv = "name,login_uri,login_username,login_password,login_totp\n" +
            "站点,https://a.example,user,$FAKE_PASSWORD,$TOTP_SEED\n"

        val batch = parseSuccess(csv)

        val entry = batch.entries.single()
        assertEquals("https://a.example", entry.url)
        assertEquals("user", entry.username)
        val totp = requireNotNull(entry.totpSecret)
        assertEquals(TOTP_SEED.length, totp.size)
        assertTrue(totp.contentEquals(TOTP_SEED.toCharArray()))
    }

    @Test
    fun `未识别的多余列被忽略而不是错位映射`() = runTest {
        val csv = "name,ignored_col,url,username,password\n" +
            "站点,无关值,https://a.example,user,$FAKE_PASSWORD\n"

        val batch = parseSuccess(csv)

        val entry = batch.entries.single()
        assertEquals("站点", entry.title)
        assertEquals("https://a.example", entry.url)
        assertEquals(FAKE_PASSWORD.length, entry.password.size)
        assertTrue(entry.groupPath.isEmpty())
        assertEquals("", entry.notes)
    }

    @Test
    fun `LastPass 表头 extra 与 grouping 映射为备注与反斜杠分组路径`() = runTest {
        val csv = "url,username,password,extra,name,grouping,fav\n" +
            "https://a.example,user,$FAKE_PASSWORD,备注文本,站点甲,Social\\Facebook,0\n"

        val batch = parseSuccess(csv)

        val entry = batch.entries.single()
        assertEquals("站点甲", entry.title)
        assertEquals("备注文本", entry.notes)
        assertEquals(listOf("Social", "Facebook"), entry.groupPath)
        assertTrue(entry.password.contentEquals(FAKE_PASSWORD.toCharArray()))
    }

    @Test
    fun `分组列支持斜杠分层且空分组落至根分组`() = runTest {
        val csv = "name,url,username,password,group\n" +
            "站点甲,https://a.example,user,$FAKE_PASSWORD,Work\\Email\n" +
            "站点乙,https://b.example,user,$FAKE_PASSWORD,\n"

        val batch = parseSuccess(csv)

        assertEquals(listOf("Work", "Email"), batch.entries[0].groupPath)
        assertTrue(batch.entries[1].groupPath.isEmpty())
    }

    @Test
    fun `引号出现在非引号字段中按字面量处理且不崩溃`() = runTest {
        val csv = "name,url,username,password\n" +
            "站\"点,https://a.example,user,$FAKE_PASSWORD\n"

        val batch = parseSuccess(csv)

        assertEquals("站\"点", batch.entries.single().title)
    }

    @Test
    fun `条目数超出防御性上限时整批 fail-closed`() = runTest {
        val builder = StringBuilder(OVER_LIMIT_ROWS * ROW_ESTIMATED_CHARS)
        builder.append(HEADER_LINE)
        repeat(OVER_LIMIT_ROWS) { index -> builder.append("站点$index,https://a.example,user,$FAKE_PASSWORD\n") }

        val result = importer.parse(builder.toString().toByteArray(Charsets.UTF_8), FILE_NAME)

        assertTrue("超出条目上限必须 fail-closed", result.isFailure)
    }

    // ---------- 辅助 ----------

    private suspend fun parseSuccess(csv: String): ImportBatch =
        parseSuccessBytes(csv.toByteArray(Charsets.UTF_8))

    /**
     * 断言解析成功；失败时把**真实失败原因**（异常类名 + 消息）带进断言消息。
     *
     * 说明：解析器的异常消息恒为结构性描述（缺列 / 编码非法 / 超限…），
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

    private suspend fun parseFailure(csv: String): KdbxResult.Failure =
        parseFailureBytes(csv.toByteArray(Charsets.UTF_8))

    private suspend fun parseFailureBytes(bytes: ByteArray): KdbxResult.Failure {
        val result = importer.parse(bytes, FILE_NAME)
        assertTrue("期望 fail-closed 返回 Failure 但解析成功", result.isFailure)
        return result as KdbxResult.Failure
    }

    private companion object {
        const val FILE_NAME = "browser_passwords.csv"
        const val FAKE_PASSWORD = "fake-password-123"
        const val TOTP_SEED = "JBSWY3DPEHPK3PXP"
        const val USER_FIELD_PREFIX = "user"
        const val HEADER_LINE = "name,url,username,password\n"

        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        /** 恰好越过 `ImportLimits.MAX_ENTRIES_PER_IMPORT` 的数据行数。 */
        const val OVER_LIMIT_ROWS = 10_001
        const val ROW_ESTIMATED_CHARS = 80
    }
}
