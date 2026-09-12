package com.keepasskey.database.csv

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 通用 CSV 导出器单测（ISSUE-P3-73 交付物 ②）。
 *
 * 覆盖：表头列序、RFC 4180 引号转义（逗号 / 双引号 / 换行）、根与子分组路径（`\` 分层）、
 * 明文口令如实写出。断言仅比对结构化文本，不额外扩散明文。
 */
class KdbxCsvExporterTest {

    @Test
    fun `表头固定为通用六列且根条目分组列为空`() {
        val root = KdbxGroup(
            name = "Root",
            entries = listOf(entry(title = "站点", url = "https://a.example", userName = "user", password = "pw"))
        )

        val text = exportText(root)

        assertEquals(
            "name,url,username,password,notes,group\r\n" +
                "站点,https://a.example,user,pw,,\r\n",
            text
        )
    }

    @Test
    fun `含逗号引号与换行的字段按 RFC 4180 引号化转义`() {
        val root = KdbxGroup(
            name = "Root",
            entries = listOf(
                entry(
                    title = "A,B",
                    url = "https://x.example",
                    userName = "user",
                    password = "pw",
                    notes = "He said \"hi\"\nsecond line"
                )
            )
        )

        val text = exportText(root)

        assertEquals(
            "name,url,username,password,notes,group\r\n" +
                "\"A,B\",https://x.example,user,pw,\"He said \"\"hi\"\"\nsecond line\",\r\n",
            text
        )
    }

    @Test
    fun `子分组条目携带反斜杠分层的分组路径`() {
        val nested = KdbxGroup(
            name = "Facebook",
            entries = listOf(entry(title = "深层", url = "https://f.example", userName = "u", password = "pw"))
        )
        val social = KdbxGroup(
            name = "Social",
            entries = listOf(entry(title = "浅层", url = "https://s.example", userName = "u", password = "pw")),
            subgroups = listOf(nested)
        )
        val root = KdbxGroup(name = "Root", subgroups = listOf(social))

        val text = exportText(root)

        assertEquals(
            "name,url,username,password,notes,group\r\n" +
                "浅层,https://s.example,u,pw,,Social\r\n" +
                "深层,https://f.example,u,pw,,Social\\Facebook\r\n",
            text
        )
    }

    @Test
    fun `无分组条目仍写出完整行且口令明文如实导出`() {
        val root = KdbxGroup(
            name = "Root",
            entries = listOf(entry(title = "t", url = "", userName = "", password = "secret-value"))
        )

        val text = exportText(root)

        assertEquals("name,url,username,password,notes,group\r\n" +
            "t,,,secret-value,,\r\n", text)
    }

    private fun exportText(root: KdbxGroup): String =
        KdbxCsvExporter.export(KdbxDatabase(header = KdbxHeader.createDefault(), rootGroup = root))
            .toString(Charsets.UTF_8)

    private fun entry(
        title: String,
        url: String,
        userName: String,
        password: String,
        notes: String = ""
    ): KdbxEntry = KdbxEntry(
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(title, isProtected = false),
            KdbxConstants.Fields.URL to ProtectedString(url, isProtected = false),
            KdbxConstants.Fields.USER_NAME to ProtectedString(userName, isProtected = false),
            KdbxConstants.Fields.PASSWORD to ProtectedString(password, isProtected = true),
            KdbxConstants.Fields.NOTES to ProtectedString(notes, isProtected = false)
        )
    )
}
