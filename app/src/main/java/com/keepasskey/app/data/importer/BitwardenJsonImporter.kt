package com.keepasskey.app.data.importer

import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import javax.inject.Inject

/**
 * Bitwarden 明文 JSON 导出解析器（ISSUE-P3-19 交付物 1：[ImportSource.BITWARDEN_JSON]）。
 *
 * ### 接受与拒绝
 * 仅接受 Bitwarden「导出密码库 → `.json`（未加密）」产物；`"encrypted": true` 的加密导出
 * **fail-closed 返回 Failure**（本地无从解出密文，绝不能假装成功）。
 *
 * ### 编码
 * 仅接受 UTF-8（允许首部 BOM）；非法字节序列 / UTF-16/32 BOM / NUL 伪文本一律 fail-closed
 * （统一经 `ImportUtf8Decoder.decodeStrictUtf8`），**绝不**静默替换为 U+FFFD——那会篡改密码原文。
 *
 * ### 分组语义
 * Bitwarden `folders` 是**扁平列表**（无层级），故 `folderId` 映射为**单级** [ImportedEntry.groupPath]
 * （`listOf(分组名)`）：id 为空 → 根分组；id 悬空或分组名为空 → 根分组 + 警告。
 *
 * ### 兜底语义（与 [ImportWarning] 一一对应，均可测）
 * - 名称空/缺失 → `title` 归一为空串 + 警告（落库编排器按契约回退为「未命名条目」）；
 * - 用户名 / 备注 / URL 空或缺失 → 空串，属合法状态，**不**记警告；
 * - 密码缺失或为空 → 空 [CharArray]（仅用户名条目真实存在，不记警告）；
 * - 非登录类型（`type != 1`）与非法条目节点 → [ImportReport.skipped] 计数 + 警告；
 * - 缺少 `login` 对象 → 以空凭据导入 + 警告（名称/备注仍有价值）。
 *
 * ### 防御性上限（与框架其余数据源同界，集中定义于 `ImportLimits`）
 * 文件体积取 `ImportLimits.MAX_IMPORT_BYTES`、条目数取 `ImportLimits.MAX_ENTRIES_PER_IMPORT`、
 * 警告按 `ImportLimits.MAX_WARNINGS` 折叠（溢出折叠为一条 `WARNINGS_TRUNCATED` 汇总项，不静默丢弃）；
 * 任一上限被突破一律抛 `ImportLimitExceededException` → Failure（fail-closed）。
 *
 * ### 敏感数据残余面（如实登记）
 * JSON 语法分析前必须把整份文件解码为 String，故密码 / TOTP 在**解析阶段**不可避免地以 String
 * 形态短暂驻留（Java 字符串不可擦除）；本类在字段映射的第一现场即 `toCharArray()`，此后不再参与
 * 任何拼接、比较、日志与异常消息。此为「不新增依赖 + 无流式定制解析器」取舍下的已知残余面。
 * JSON 文本读取器为同包 `PuxArchiveReader.kt` 内的 `ImportJson`（两个 JSON 系解析器共享）。
 */
class BitwardenJsonImporter @Inject constructor() : EntryImporter {

    override val source: ImportSource = ImportSource.BITWARDEN_JSON

    override val supportedExtensions: Set<String> = setOf(EXTENSION_JSON)

    /**
     * 解析 [bytes]；[fileName] 仅供来源提示，不参与 IO。
     * 整份文本语法分析属 CPU 密集，故固定落 [Dispatchers.Default]。
     */
    override suspend fun parse(bytes: ByteArray, fileName: String): KdbxResult<ImportBatch> =
        withContext(Dispatchers.Default) {
            try {
                KdbxResult.Success(parseOrThrow(bytes))
            } catch (cancellation: CancellationException) {
                // 协程取消必须原样上抛，绝不能被归一为「解析失败」
                throw cancellation
            } catch (format: ImportFormatException) {
                KdbxResult.Failure(format, format.message)
            } catch (unexpected: Throwable) {
                // 契约要求：不允许裸异常越过接口（含 Error），统一归一为 Failure。
                // cause 仅保留给开发者诊断，绝不并入 userMessage（后者才会上浮 UI）。
                val wrapper = ImportFormatException(MESSAGE_UNEXPECTED)
                wrapper.initCause(unexpected)
                KdbxResult.Failure(wrapper, MESSAGE_UNEXPECTED)
            }
        }

    /** 解析主体：结构性非法一律抛 [ImportFormatException] 派生异常，由 [parse] 归一为 Failure。 */
    private fun parseOrThrow(bytes: ByteArray): ImportBatch {
        if (bytes.isEmpty()) throw ImportFormatException("Bitwarden 导出为空文件")
        if (bytes.size > ImportLimits.MAX_IMPORT_BYTES) {
            throw ImportLimitExceededException("Bitwarden 导出体积超出上限（${ImportLimits.MAX_IMPORT_BYTES} 字节）")
        }
        // 文本解码统一交给同批的严格 UTF-8 解码器（剥 BOM、拒绝 UTF-16/32 与 NUL 伪文本），
        // 非法字节序列直接 fail-closed（绝不替换为 U+FFFD——那会静默篡改密码原文）
        val root = ImportJson.asObject(ImportJson.parse(ImportUtf8Decoder.decodeStrictUtf8(bytes)))
            ?: throw ImportFormatException("Bitwarden 导出根节点不是 JSON 对象")
        if (ImportJson.asBoolean(root[KEY_ENCRYPTED]) == true) {
            throw ImportFormatException("Bitwarden 导出为加密格式（encrypted=true），仅支持未加密导出")
        }
        val items = ImportJson.asArray(root[KEY_ITEMS])
            ?: throw ImportFormatException("Bitwarden 导出缺少 items 数组")
        if (items.size > ImportLimits.MAX_ENTRIES_PER_IMPORT) {
            throw ImportLimitExceededException(
                "Bitwarden 导出条目数超出上限（${ImportLimits.MAX_ENTRIES_PER_IMPORT}）"
            )
        }
        val accumulator = Accumulator()
        val folderNames = readFolderNames(root, accumulator)
        try {
            items.forEachIndexed { index, raw -> mapItem(raw, index, folderNames, accumulator) }
        } catch (failure: Throwable) {
            // 失败路径：已解析出的敏感序列立即清零，避免半成品明文继续驻留堆上
            accumulator.clearEntries()
            throw failure
        }
        return ImportBatch(
            report = ImportReport(
                source = source,
                parsed = accumulator.entries.size,
                skipped = accumulator.skipped,
                warnings = accumulator.warningsSnapshot()
            ),
            entries = accumulator.entries
        )
    }

    /** `folders[].id → folders[].name`；数组缺失按「无分组」处理，形态非法时记警告后同样降级。 */
    private fun readFolderNames(root: Map<*, *>, accumulator: Accumulator): Map<String, String> {
        val rawFolders = root[KEY_FOLDERS] ?: return emptyMap()
        val folders = ImportJson.asArray(rawFolders)
        if (folders == null) {
            accumulator.warn(LOCATION_FOLDERS, "folders 不是数组，全部条目落至根分组")
            return emptyMap()
        }
        val names = LinkedHashMap<String, String>()
        folders.forEach { rawFolder ->
            val folder = ImportJson.asObject(rawFolder) ?: return@forEach
            val id = ImportJson.asString(folder[KEY_ID])?.takeIf { it.isNotBlank() } ?: return@forEach
            val name = ImportJson.asString(folder[KEY_NAME])?.takeIf { it.isNotBlank() } ?: return@forEach
            names[id] = name
        }
        return names
    }

    /** 映射单条 `items` 元素；跳过原因一律显式计入 [ImportReport.skipped] 并附警告。 */
    private fun mapItem(raw: Any?, index: Int, folderNames: Map<String, String>, accumulator: Accumulator) {
        val location = "$LOCATION_ITEM_PREFIX[$index]"
        val item = ImportJson.asObject(raw)
        if (item == null) {
            accumulator.skip(location, "条目节点不是 JSON 对象")
            return
        }
        if (ImportJson.asLong(item[KEY_TYPE]) != TYPE_LOGIN) {
            accumulator.skip(location, "非登录类型条目（type != $TYPE_LOGIN），已跳过")
            return
        }
        val login = ImportJson.asObject(item[KEY_LOGIN])
        if (login == null) accumulator.warn(location, "缺少 login 对象，用户名/密码/URL/TOTP 均为空")
        val rawTitle = ImportJson.asString(item[KEY_NAME]).orEmpty()
        if (rawTitle.isBlank()) accumulator.warn(location, "条目名称为空，将由落库层回退为默认名")
        accumulator.entries += ImportedEntry(
            title = if (rawTitle.isBlank()) "" else rawTitle,
            username = ImportJson.asString(login?.get(KEY_USERNAME)).orEmpty(),
            password = secretChars(login, KEY_PASSWORD, location, accumulator),
            url = firstUri(login, location, accumulator),
            notes = ImportJson.asString(item[KEY_NOTES]).orEmpty(),
            groupPath = groupPath(item, folderNames, location, accumulator),
            totpSecret = secretChars(login, KEY_TOTP, location, accumulator).takeIf { it.isNotEmpty() },
            deleted = isDeleted(item)
        )
    }

    /**
     * 取敏感字段并**在字段映射的第一现场**转为 [CharArray]：返回后不再参与拼接 / 比较 / 日志。
     * 键缺失或值为 null → 空数组；键存在但类型非字符串 → 空数组 + 警告（fail-closed，不猜编码）。
     */
    private fun secretChars(
        container: Map<*, *>?,
        key: String,
        location: String,
        accumulator: Accumulator
    ): CharArray {
        val value = container?.get(key) ?: return CharArray(0)
        val text = ImportJson.asString(value)
        if (text == null) {
            accumulator.warn(location, "字段 $key 的类型不是字符串，已按空值处理")
            return CharArray(0)
        }
        return text.toCharArray()
    }

    /** `login.uris[0].uri → url`；URL 属可选字段，缺失按空串处理，形态非法另记警告。 */
    private fun firstUri(login: Map<*, *>?, location: String, accumulator: Accumulator): String {
        val rawUris = login?.get(KEY_URIS) ?: return ""
        val uris = ImportJson.asArray(rawUris)
        if (uris == null) {
            accumulator.warn(location, "login.uris 不是数组，已按无 URL 处理")
            return ""
        }
        val first = ImportJson.asObject(uris.firstOrNull()) ?: return ""
        return ImportJson.asString(first[KEY_URI]).orEmpty()
    }

    /** `folderId → folders 表 → 单级分组`；悬空 id 落根分组 + 警告。 */
    private fun groupPath(
        item: Map<*, *>,
        folderNames: Map<String, String>,
        location: String,
        accumulator: Accumulator
    ): List<String> {
        val folderId = ImportJson.asString(item[KEY_FOLDER_ID])?.takeIf { it.isNotBlank() } ?: return emptyList()
        val name = folderNames[folderId]
        if (name == null) {
            accumulator.warn(location, "folderId 未匹配到任何分组，已落至根分组")
            return emptyList()
        }
        return listOf(name)
    }

    /** `deletedDate` 非空即视为源端已删除（落至回收站）。 */
    private fun isDeleted(item: Map<*, *>): Boolean =
        ImportJson.asString(item[KEY_DELETED_DATE])?.isNotBlank() == true

    /** 一次解析的可变累积状态：条目、跳过计数与警告集中于此，避免多层函数透传多个集合。 */
    private class Accumulator {
        val entries = mutableListOf<ImportedEntry>()
        private val warnings = ImportWarningSink()
        var skipped = 0
            private set

        /** 跳过一条不可导入的条目：计数与警告必须同时发生（部分成功必须显式声明）。 */
        fun skip(location: String, reason: String) {
            skipped++
            warn(location, reason)
        }

        fun warn(location: String, reason: String) {
            warnings.add(location, reason)
        }

        /** 不可变警告快照（含溢出折叠项），交给 [ImportReport] 后调用方无法再改动内部列表。 */
        fun warningsSnapshot(): List<ImportWarning> = warnings.snapshot()

        /** 失败路径清零：逐条擦除已解析出的敏感序列并清空集合（敏感数据铁律的硬要求）。 */
        fun clearEntries() {
            entries.forEach { it.clear() }
            entries.clear()
        }
    }

    companion object {
        /** 受支持扩展名（小写、不含点）。 */
        internal const val EXTENSION_JSON = "json"

        /** Bitwarden 条目类型：1 = 登录（2 安全笔记 / 3 银行卡 / 4 身份 一律跳过）。 */
        private const val TYPE_LOGIN = 1L

        private const val KEY_ENCRYPTED = "encrypted"
        private const val KEY_FOLDERS = "folders"
        private const val KEY_ITEMS = "items"
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_TYPE = "type"
        private const val KEY_LOGIN = "login"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_TOTP = "totp"
        private const val KEY_URIS = "uris"
        private const val KEY_URI = "uri"
        private const val KEY_NOTES = "notes"
        private const val KEY_FOLDER_ID = "folderId"
        private const val KEY_DELETED_DATE = "deletedDate"

        private const val LOCATION_ITEM_PREFIX = "items"
        private const val LOCATION_FOLDERS = "folders"

        private const val MESSAGE_UNEXPECTED = "Bitwarden 导出解析失败：文件已损坏或格式不受支持"
    }
}

/**
 * 有上限的导入警告收集器（本批 Bitwarden 与 1PUX 两个解析器共享）。
 *
 * 与框架 `ImportWarningCollector` **同构**（上限 [ImportLimits.MAX_WARNINGS]，溢出部分折叠为一条
 * [ImportWarningReason.WARNINGS_TRUNCATED] 汇总项，绝不静默丢弃），差别仅在于本类接受
 * **自由文本技术描述**而非 `ImportWarningReason` 编码：本批两个 JSON 系数据源的跳过原因
 * （非登录类型 / 非登录类别 / 悬空分组等）在框架既有枚举中暂无对应编码，
 * 待框架侧扩充枚举编码后可再行收敛。
 */
internal class ImportWarningSink(private val max: Int = ImportLimits.MAX_WARNINGS) {

    private val collected = mutableListOf<ImportWarning>()
    private var overflow = 0

    fun add(location: String, reason: String) {
        if (collected.size < max) {
            collected += ImportWarning(location = location, reason = reason)
        } else {
            overflow++
        }
    }

    /** 不可变快照；存在溢出时追加一条汇总警告（可重复调用，不改动内部状态）。 */
    fun snapshot(): List<ImportWarning> {
        val snapshot = collected.toList()
        if (overflow == 0) return snapshot
        return snapshot + ImportWarning(
            location = ImportWarningLocation.DOCUMENT,
            reason = ImportWarningReason.WARNINGS_TRUNCATED.code
        )
    }
}

/**
 * 严格 UTF-8 文本解码（本批 Bitwarden 与 1PUX 解析器共享；字节 → 文本的唯一入口）。
 *
 * ### 契约（与框架 `ImportTextDecoder.decodeStrictUtf8` 完全一致）
 * 仅接受 UTF-8：剥离首部 UTF-8 BOM；非法字节序列、UTF-16/32 BOM、含 NUL 的伪文本一律抛
 * [ImportEncodingException]（→ Failure，归入 ENCODING）。**绝不**把非法字节替换为 U+FFFD，
 * 否则畸形输入会被静默「洗白」，密码原文被篡改而用户毫不知情。
 *
 * ### 为何本批自带一份（如实登记的临时重复）
 * 框架实现 `ImportTextDecoder.decode()` 对三段式 `CharsetDecoder.decode(in, out, endOfInput=true)`
 * 的返回值直接调用 `Result.throwException()`；而该调用在**正常收尾**时返回的正是 UNDERFLOW，
 * `throwException()` 会把它抛成 `BufferUnderflowException`（不属于其 catch 的
 * `CharacterCodingException`），故框架解码器当前对**任意**输入都抛异常。本批不得修改他批文件，
 * 故按同一契约就地实现；框架修复后此处可删除并改回一行调用（调用点已收敛为单一表达式）。
 */
internal object ImportUtf8Decoder {

    /** 严格解码；失败抛 [ImportEncodingException]（消息为固定技术描述，不含任何输入片段）。 */
    fun decodeStrictUtf8(bytes: ByteArray): String {
        rejectUnsupportedBom(bytes)
        val offset = utf8BodyOffset(bytes)
        val text = decodeOrNull(bytes, offset) ?: throw ImportEncodingException(MESSAGE_NOT_UTF8)
        if (text.indexOf(NUL_CHAR) >= 0) throw ImportEncodingException(MESSAGE_NUL_PADDED)
        return text
    }

    /**
     * 执行一次完整解码。
     *
     * 注意：**不可**改用单参便捷重载 `CharsetDecoder.decode(ByteBuffer)`——按 JDK 规范它恒以
     * 替换语义处理非法输入，会绕过 `CodingErrorAction.REPORT`；必须走三段式
     * `decode(in, out, endOfInput=true)`，并且**只**在 `isError` 时判失败
     * （`isUnderflow` 是正常收尾，`isOverflow` 在「输出容量 ≥ 输入字节数」时不可能出现）。
     */
    private fun decodeOrNull(bytes: ByteArray, offset: Int): String? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        // UTF-8 最坏情况 1 字符/字节（ASCII）；4 字节序列产出代理对，故按字节数分配必然足够
        val output = CharBuffer.allocate(bytes.size - offset + OUTPUT_SLACK)
        val result = decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset), output, true)
        if (result.isError || result.isOverflow) return null
        val flushed = decoder.flush(output)
        if (flushed.isError || flushed.isOverflow) return null
        output.flip()
        return output.toString()
    }

    /** UTF-8 BOM 之后的正文偏移；无 BOM 时为 0。 */
    private fun utf8BodyOffset(bytes: ByteArray): Int = if (hasUtf8Bom(bytes)) UTF8_BOM_SIZE else 0

    private fun hasUtf8Bom(bytes: ByteArray): Boolean =
        bytes.size >= UTF8_BOM_SIZE &&
            bytes[0] == UTF8_BOM[0] && bytes[1] == UTF8_BOM[1] && bytes[2] == UTF8_BOM[2]

    /** 拒绝 UTF-16/32 BOM：其字节流在 UTF-8 视角下多数仍「合法」，不显式拒绝就会导入整篇乱码。 */
    private fun rejectUnsupportedBom(bytes: ByteArray) {
        if (bytes.size < BOM_PROBE_BYTES) return
        val b0 = bytes[0].toInt() and BYTE_MASK
        val b1 = bytes[1].toInt() and BYTE_MASK
        val b2 = bytes[2].toInt() and BYTE_MASK
        val b3 = bytes[3].toInt() and BYTE_MASK
        val utf16 = (b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)
        val utf32 = (b0 == 0x00 && b1 == 0x00 && b2 == 0xFE && b3 == 0xFF) ||
            (b0 == 0xFF && b1 == 0xFE && b2 == 0x00 && b3 == 0x00)
        if (utf16 || utf32) throw ImportEncodingException(MESSAGE_UNSUPPORTED_BOM)
    }

    private const val UTF8_BOM_SIZE = 3
    private const val BOM_PROBE_BYTES = 4
    private const val BYTE_MASK = 0xFF
    private const val OUTPUT_SLACK = 1
    private const val NUL_CHAR = '\u0000'
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /** 诊断用技术描述（绝不作为用户可见文案，也不含输入片段）。 */
    private const val MESSAGE_NOT_UTF8 = "导入文件不是合法的 UTF-8 文本"
    private const val MESSAGE_UNSUPPORTED_BOM = "导入文件使用了 UTF-16/UTF-32 编码，仅支持 UTF-8"
    private const val MESSAGE_NUL_PADDED = "导入文件含 NUL 字符，不是合法的 UTF-8 文本"
}
