package com.keepasskey.app.data.importer

import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ISSUE-P3-19 交付物 3：浏览器密码导出 CSV 解析器（`ImportSource.BROWSER_CSV`）。
 *
 * 输入形态：Chrome / Edge 的「密码 → 导出」CSV，表头 `name,url,username,password`（可能带 `note`），
 * 同时容忍同名近义表头（见 [NAME_HEADERS] 等别名集，含 Bitwarden CSV 的 `login_*` 命名）。
 *
 * 映射规则（要求：大小写与列序不固定 → **按表头名映射，不按位置硬编码**）：
 * - 表头名去空白、剥离 BOM 后小写比较；
 * - **缺少 `name` 或 `password` 列 → fail-closed**（[ImportRequiredColumnMissingException]，
 *   无法安全映射，绝不猜列位）；
 * - 同一规范角色重复出现时取**首个**（宽容处理手工编辑过的表头，取首列语义确定）；
 * - 未识别的列一律忽略并清零（不猜测、不落库）。
 *
 * 编码：UTF-8（含 BOM 剥离）；非法字节序列按替换字符处理并记 [ImportWarningReason.INVALID_UTF8]
 * 警告；UTF-16/32 或含 NUL 的伪文本 fail-closed（[ImportEncodingException]）。
 *
 * 敏感数据：CSV 表头（非敏感）转为 `String`；数据行字段由 [CsvRecordReader] 以 `CharArray`
 * 交出，密码列**直接转交** [ImportedEntry.password]（不经 `String`），其余列才转 `String`。
 *
 * 线程：解析为 CPU 密集，强制 `Dispatchers.Default`。
 */
@Singleton
class BrowserCsvImporter @Inject constructor() : EntryImporter {

    override val source: ImportSource = ImportSource.BROWSER_CSV

    override val supportedExtensions: Set<String> = setOf(EXTENSION_CSV)

    override suspend fun parse(bytes: ByteArray, fileName: String): KdbxResult<ImportBatch> =
        withContext(Dispatchers.Default) {
            val warnings = ImportWarningCollector()
            val produced = mutableListOf<ImportedEntry>()
            try {
                ImportParseGuard.requireFileSize(bytes.size)
                val (text, replaced) = ImportTextDecoder.decodeLenientUtf8(bytes)
                if (replaced) {
                    warnings.add(ImportWarningLocation.DOCUMENT, ImportWarningReason.INVALID_UTF8)
                }
                KdbxResult.Success(scan(text, warnings, produced))
            } catch (t: Throwable) {
                ImportParseGuard.failure(ImportParseGuard.unwrap(t), produced)
            }
        }

    /** 扫描表头 + 数据行，产出批次（表头必须先于数据行映射）。 */
    private fun scan(
        text: String,
        warnings: ImportWarningCollector,
        produced: MutableList<ImportedEntry>
    ): ImportBatch {
        val state = CsvScanState()
        // BOM_CHAR 为 Char，而 String.removePrefix 只接受 CharSequence → 显式 toString()
        //（与本文件 :164 的既有写法保持一致；剥 BOM 语义不变）
        CsvRecordReader(text.removePrefix(BOM_CHAR.toString())).forEachRow { recordIndex, fields ->
            val mapping = state.mapping
            if (mapping == null) {
                state.mapping = buildMapping(fields)
                state.headerColumns = fields.size
            } else {
                processRow(recordIndex, fields, mapping, state, warnings, produced)
            }
        }
        state.mapping ?: throw ImportRequiredColumnMissingException(EMPTY_HEADER)
        return ImportBatch(
            report = ImportReport(
                source = source,
                parsed = produced.size,
                skipped = state.skipped,
                warnings = warnings.snapshot()
            ),
            entries = produced.toList()
        )
    }

    /** 由表头行构建「列序号 → 规范角色」映射；缺必需列即 fail-closed。 */
    private fun buildMapping(fields: List<CharArray>): Map<Int, CsvColumnRole> {
        val mapping = mutableMapOf<Int, CsvColumnRole>()
        fields.forEachIndexed { index, field ->
            val headerName = field.takeHeaderName()
            val role = roleOf(headerName) ?: return@forEachIndexed
            if (mapping.values.none { it == role }) mapping[index] = role
        }
        val roles = mapping.values.toSet()
        if (CsvColumnRole.NAME !in roles || CsvColumnRole.PASSWORD !in roles) {
            throw ImportRequiredColumnMissingException(MISSING_REQUIRED_COLUMN)
        }
        return mapping
    }

    /** 处理一条数据行：抽取字段 → 交出新条目；全空行静默忽略，无有效字段行计入 skipped。 */
    private fun processRow(
        recordIndex: Int,
        fields: List<CharArray>,
        mapping: Map<Int, CsvColumnRole>,
        state: CsvScanState,
        warnings: ImportWarningCollector,
        produced: MutableList<ImportedEntry>
    ) {
        if (fields.all { it.isBlankField() }) {
            fields.forEach { it.fill(NUL_CHAR) }
            return
        }
        if (fields.size != state.headerColumns) {
            warnings.add(ImportWarningLocation.row(recordIndex + 1), ImportWarningReason.COLUMN_COUNT_MISMATCH)
        }
        val cells = extractCells(fields, mapping)
        val password = cells.password ?: CharArray(0)
        if (cells.title.isBlank() && cells.username.isBlank() && password.isEmpty()) {
            state.skipped++
            warnings.add(ImportWarningLocation.row(recordIndex + 1), ImportWarningReason.MISSING_REQUIRED_VALUE)
            password.fill(NUL_CHAR)
            cells.totp?.fill(NUL_CHAR)
            return
        }
        produced += ImportedEntry(
            title = cells.title,
            username = cells.username,
            password = password,
            url = cells.url,
            notes = cells.notes,
            // 浏览器 CSV 不含分组信息，一律落至根分组
            groupPath = emptyList(),
            totpSecret = cells.totp
        )
        ImportParseGuard.requireEntryCapacity(produced.size)
    }

    /** 按角色抽取一行各列（密码 / TOTP 保留 `CharArray`，其余转 `String` 后立即清零）。 */
    private fun extractCells(fields: List<CharArray>, mapping: Map<Int, CsvColumnRole>): CsvCells {
        val cells = CsvCells()
        for (index in fields.indices) {
            val field = fields[index]
            when (mapping[index]) {
                CsvColumnRole.NAME -> cells.title = field.takeText()
                CsvColumnRole.USERNAME -> cells.username = field.takeText()
                CsvColumnRole.URL -> cells.url = field.takeText()
                CsvColumnRole.NOTE -> cells.notes = field.takeText()
                CsvColumnRole.PASSWORD -> cells.password = field
                CsvColumnRole.TOTP -> cells.totp = field
                null -> field.fill(NUL_CHAR)
            }
        }
        return cells
    }

    private fun roleOf(headerName: String): CsvColumnRole? = when (headerName) {
        in NAME_HEADERS -> CsvColumnRole.NAME
        in USERNAME_HEADERS -> CsvColumnRole.USERNAME
        in PASSWORD_HEADERS -> CsvColumnRole.PASSWORD
        in URL_HEADERS -> CsvColumnRole.URL
        in NOTE_HEADERS -> CsvColumnRole.NOTE
        in TOTP_HEADERS -> CsvColumnRole.TOTP
        else -> null
    }

    /** 表头单元格 → 规范化名称（去空白、剥离 BOM、小写），并清零原数组。 */
    private fun CharArray.takeHeaderName(): String {
        val name = String(this).trim().removePrefix(BOM_CHAR.toString()).lowercase()
        fill(NUL_CHAR)
        return name
    }

    /** 非敏感单元格 → `String`（去首尾空白），并清零原数组。 */
    private fun CharArray.takeText(): String {
        val value = String(this).trim()
        fill(NUL_CHAR)
        return value
    }

    private fun CharArray.isBlankField(): Boolean = all { it.isWhitespace() }

    /** 单次扫描的可变状态（刻意不做成字段：解析器是 `@Singleton`，不得持有解析态）。 */
    private class CsvScanState {
        var mapping: Map<Int, CsvColumnRole>? = null
        var headerColumns: Int = 0
        var skipped: Int = 0
    }

    /** 一行抽取结果：敏感列以 `CharArray` 承载，其余为 `String`。 */
    private class CsvCells {
        var title: String = ""
        var username: String = ""
        var url: String = ""
        var notes: String = ""
        var password: CharArray? = null
        var totp: CharArray? = null
    }

    /** CSV 规范列角色（不以列位传状态）。 */
    private enum class CsvColumnRole { NAME, USERNAME, PASSWORD, URL, NOTE, TOTP }

    private companion object {
        const val EXTENSION_CSV = "csv"
        const val BOM_CHAR = '\uFEFF'
        const val NUL_CHAR = '\u0000'
        const val EMPTY_HEADER = "CSV 文件为空，缺少表头行"
        const val MISSING_REQUIRED_COLUMN = "CSV 表头缺少必需列（name 或 password）"

        val NAME_HEADERS = setOf("name", "title")
        val USERNAME_HEADERS = setOf("username", "login_username", "user")
        val PASSWORD_HEADERS = setOf("password", "login_password")
        val URL_HEADERS = setOf("url", "login_uri", "website", "uri")
        val NOTE_HEADERS = setOf("note", "notes", "comment")
        val TOTP_HEADERS = setOf("totp", "login_totp", "otp")
    }
}
