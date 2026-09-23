package com.keepasskey.database.csv

import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.io.WipableByteArrayOutputStream
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import java.io.BufferedWriter
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
 *
 * ISSUE-P3-296：目标缓冲为 [WipableByteArrayOutputStream]（整份明文 CSV 字节的第二副本），
 * `toByteArray()` 交出**复制**交付后于 `finally` 显式 [WipableByteArrayOutputStream.wipe]——
 * 成功与失败路径均擦。所有权与擦除钩子登记于
 * `docs/architecture/敏感缓冲所有权契约.md` §4。
 */
object KdbxCsvExporter {

    /** CSV 表头（顺序即列序，供导入侧按表头名映射）。 */
    private val HEADER = listOf("name", "url", "username", "password", "notes", "group")

    private const val LINE_SEPARATOR = "\r\n"
    private const val FIELD_SEPARATOR = ','
    private const val QUOTE = '"'
    private const val GROUP_SEPARATOR = '\\'

    fun export(database: KdbxDatabase): ByteArray {
        val buffer = WipableByteArrayOutputStream()
        return try {
            val writer = BufferedWriter(OutputStreamWriter(buffer, Charsets.UTF_8))
            try {
                writeRow(writer, HEADER)
                // 根分组自身的条目落至根（空路径）；子分组条目携带自顶向下（不含根分组名）的路径
                writeGroup(writer, database.rootGroup, ArrayDeque())
                writer.flush()
            } finally {
                writer.close()
            }
            buffer.toByteArray()
        } finally {
            buffer.wipe()
        }
    }

    /**
     * 递归写出分组：当前路径以**栈**维护（进组 `addLast`、出组 `removeLast`），并在**组级**拼一次
     * 路径字符串供本组全部条目复用（ISSUE-P3-181）。
     *
     * 原实现每下钻一层就 `groupPath + child.name` 复制一份父路径，`writeEntry` 又对每条目
     * `joinToString(SEPARATOR)` 重拼一次 ⇒ O(条目数 × 深度) 次字符复制；改为路径栈后每层只 push/pop
     * 一个分组名，路径拼装次数与分组数同阶；组内条目的路径字符串按引用共享（[String] 不可变但可复用）。
     *
     * 产物逐字节等价：根分组仍以**空栈**进入（空栈 join 得空串，与原 `emptyList()` 一致，根级条目分组列为空），
     * 子分组路径仍是自顶向下、不含根分组名、以 [GROUP_SEPARATOR] 拼接——分隔符常量与 `joinToString`
     * 的拼装规则均未改动，仅减少调用次数。
     */
    private fun writeGroup(writer: Writer, group: KdbxGroup, path: ArrayDeque<String>) {
        val groupPath = path.joinToString(GROUP_SEPARATOR.toString())
        group.entries.forEach { writeEntry(writer, it, groupPath) }
        group.subgroups.forEach { child ->
            path.addLast(child.name)
            writeGroup(writer, child, path)
            path.removeLast()
        }
    }

    /** ISSUE-P3-181：路径字符串由 [writeGroup] 在组级拼好传入，此处不再逐条目重拼。 */
    private fun writeEntry(writer: Writer, entry: KdbxEntry, groupPath: String) {
        writeRow(
            writer,
            listOf(
                entry.title,
                entry.url,
                entry.userName,
                entry.password?.readString().orEmpty(),
                entry.notes,
                groupPath
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
