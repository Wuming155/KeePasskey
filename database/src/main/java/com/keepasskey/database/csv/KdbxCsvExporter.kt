package com.keepasskey.database.csv

import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.io.Writer

/**
 * 通用 CSV 导出器（ISSUE-P3-73）。
 *
 * 输出为**明文** CSV（RFC 4180 引号语义），列固定为
 * `name,url,username,password,notes,group`，可被通用表格工具 / 其他密码管理器导入；
 * 分组列以 `\` 分层（与 [com.keepasskey.app.data.importer.BrowserCsvImporter] 的分组列互为往返）。
 *
 * 安全声明：本格式**不受主密码保护**——受保护字段（口令）的明文如实写出，明文风险由导出二次确认
 * 对话框显式告知（`ExportConfirmationPolicy.Risk.PLAINTEXT`），调用方必须经 SAF 写到用户选定位置。
 * 为减小明文驻留，逐行流式写出到目标缓冲，不构造整份明文字符串。
 */
object KdbxCsvExporter {

    /** CSV 表头（顺序即列序，供导入侧按表头名映射）。 */
    private val HEADER = listOf("name", "url", "username", "password", "notes", "group")

    private const val LINE_SEPARATOR = "\r\n"
    private const val FIELD_SEPARATOR = ','
    private const val QUOTE = '"'
    private const val GROUP_SEPARATOR = '\\'

    fun export(database: KdbxDatabase): ByteArray {
        val buffer = ByteArrayOutputStream()
        val writer = BufferedWriter(OutputStreamWriter(buffer, Charsets.UTF_8))
        try {
            writeRow(writer, HEADER)
            // 根分组自身的条目落至根（空路径）；子分组条目携带自顶向下（不含根分组名）的路径
            writeGroup(writer, database.rootGroup, emptyList())
            writer.flush()
        } finally {
            writer.close()
        }
        return buffer.toByteArray()
    }

    private fun writeGroup(writer: Writer, group: KdbxGroup, groupPath: List<String>) {
        group.entries.forEach { writeEntry(writer, it, groupPath) }
        group.subgroups.forEach { child ->
            writeGroup(writer, child, groupPath + child.name)
        }
    }

    private fun writeEntry(writer: Writer, entry: KdbxEntry, groupPath: List<String>) {
        writeRow(
            writer,
            listOf(
                entry.title,
                entry.url,
                entry.userName,
                entry.password?.readString().orEmpty(),
                entry.notes,
                groupPath.joinToString(GROUP_SEPARATOR.toString())
            )
        )
    }

    private fun writeRow(writer: Writer, fields: List<String>) {
        fields.forEachIndexed { index, field ->
            if (index > 0) writer.write(FIELD_SEPARATOR.code)
            writeField(writer, field)
        }
        writer.write(LINE_SEPARATOR)
    }

    /** RFC 4180：含分隔符 / 引号 / 换行的字段整体加引号，内部 `"` 双写转义。 */
    private fun writeField(writer: Writer, value: String) {
        val needsQuoting = value.any {
            it == FIELD_SEPARATOR || it == QUOTE || it == '\n' || it == '\r'
        }
        if (!needsQuoting) {
            writer.write(value)
            return
        }
        writer.write(QUOTE.code)
        for (ch in value) {
            if (ch == QUOTE) writer.write(QUOTE.code)
            writer.write(ch.code)
        }
        writer.write(QUOTE.code)
    }
}
