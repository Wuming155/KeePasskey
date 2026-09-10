package com.keepasskey.app.data.importer

import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/**
 * 1Password 1PUX 解析器（ISSUE-P3-19 交付物 2：[ImportSource.ONEPASSWORD_1PUX]）。
 *
 * ### 容器与条目
 * `.1pux` 实为 ZIP 容器；本解析器经 [PuxArchiveReader] 只取其中 `export.data`（JSON），
 * 其余条目（`export.attributes`、`files/` 下的附件）一概不解压。
 *
 * ### 编码
 * `export.data` 必须为 UTF-8（允许首部 BOM）；非法字节序列 / UTF-16/32 BOM / NUL 伪文本一律
 * fail-closed（统一经 `ImportUtf8Decoder.decodeStrictUtf8`），**绝不**静默替换为 U+FFFD。
 *
 * ### 类别与分组
 * 只导入 1Password **LOGIN** 类别（`categoryUuid == "001"`，取自官方 1PUX 文档示例）；其余类别一律
 * [ImportReport.skipped] + 警告——不猜测类别，避免把银行卡 / 身份 / 笔记误当登录条目导入。
 * `groupPath` 取 **`[账户名, 保险库名]`** 两级（对应官方 `export.data` 的账户 / 保险库 `attrs` 语义）；
 * 任一级缺失自动降为单级，两级皆空则落根分组并记警告。
 *
 * ### 字段映射（源 → [ImportedEntry]）
 * `overview.title` → title；`overview.url`（空则回退 `overview.urls[0].url`）→ url；
 * `details.loginFields[designation=username].value` → username；
 * `details.loginFields[designation=password].value` → password（**第一现场**转 [CharArray]）；
 * `details.loginFields[designation=totp]` 或 `details.sections[].fields[]` 中 designation 为 totp、
 * 或值以 `otpauth://` 开头者 → totpSecret；`details.notesPlain` → notes；
 * `state == "deleted"` → deleted（官方文档仅列出 active / archived，其余取值一律视为未删除）。
 *
 * ### 防御性上限（与框架其余数据源同界，集中定义于 `ImportLimits`）
 * 条目数取 `ImportLimits.MAX_ENTRIES_PER_IMPORT`、归档自身体积取 `ImportLimits.MAX_IMPORT_BYTES`、
 * 警告按 `ImportLimits.MAX_WARNINGS` 折叠（溢出折叠为一条 `WARNINGS_TRUNCATED` 汇总项，不静默丢弃）；
 * ZIP 侧另有条目数 / 单条目体积 / 解压总量三道上限，见 [PuxArchiveReader]。
 *
 * ### 敏感数据残余面（如实登记）
 * 与 Bitwarden 解析器同源：JSON 文本必须先整体解码为 String 才能做语法分析，密码 / TOTP 在解析
 * 阶段不可避免地以 String 形态短暂驻留；本类在字段映射的第一现场即 `toCharArray()`，
 * 此后不再参与拼接、比较、日志与异常消息。
 */
class OnePasswordPuxImporter @Inject constructor() : EntryImporter {

    override val source: ImportSource = ImportSource.ONEPASSWORD_1PUX

    override val supportedExtensions: Set<String> = setOf(EXTENSION_1PUX)

    /** 测试注入点：归档四道闸门的上限（生产恒为 [PuxArchiveReader.Limits] 默认值）。 */
    @Volatile
    internal var archiveLimits: PuxArchiveReader.Limits = PuxArchiveReader.Limits()

    /**
     * 解析 [bytes]；[fileName] 仅供来源提示，不参与 IO。
     * 解包与 JSON 语法分析均为内存内 CPU 密集操作，故固定落 [Dispatchers.Default]。
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
            } catch (io: IOException) {
                // ZipInputStream 对损坏归档抛 IOException；原始消息可能含条目名/路径，绝不上浮
                KdbxResult.Failure(ImportFormatException(MESSAGE_BROKEN_ARCHIVE), MESSAGE_BROKEN_ARCHIVE)
            } catch (unexpected: Throwable) {
                // 契约要求：不允许裸异常越过接口（含 Error），统一归一为 Failure
                val wrapper = ImportFormatException(MESSAGE_UNEXPECTED)
                wrapper.initCause(unexpected)
                KdbxResult.Failure(wrapper, MESSAGE_UNEXPECTED)
            }
        }

    /** 解析主体：结构性非法一律抛 [ImportFormatException] 派生异常，由 [parse] 归一为 Failure。 */
    private fun parseOrThrow(bytes: ByteArray): ImportBatch {
        val exportData = PuxArchiveReader.readEntry(bytes, ENTRY_EXPORT_DATA, archiveLimits)
        // 文本解码统一交给同批的严格 UTF-8 解码器（剥 BOM、拒绝 UTF-16/32 与 NUL 伪文本），
        // 非法字节序列直接 fail-closed（绝不替换为 U+FFFD——那会静默篡改密码原文）
        val root = ImportJson.asObject(ImportJson.parse(ImportUtf8Decoder.decodeStrictUtf8(exportData)))
            ?: throw ImportFormatException("1PUX 的 $ENTRY_EXPORT_DATA 根节点不是 JSON 对象")
        val accounts = ImportJson.asArray(root[KEY_ACCOUNTS])
            ?: throw ImportFormatException("1PUX 的 $ENTRY_EXPORT_DATA 缺少 accounts 数组")
        val accumulator = Accumulator()
        try {
            accounts.forEachIndexed { index, rawAccount ->
                readAccount(rawAccount, "$LOCATION_ACCOUNTS[$index]", accumulator)
            }
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

    /** 账户节点：取账户名后下钻保险库；节点形态非法时记警告并跳过整棵子树。 */
    private fun readAccount(rawAccount: Any?, location: String, accumulator: Accumulator) {
        val account = ImportJson.asObject(rawAccount)
        if (account == null) {
            accumulator.warn(location, "账户节点不是 JSON 对象，已跳过")
            return
        }
        val accountName = readAccountName(account)
        val vaults = ImportJson.asArray(account[KEY_VAULTS]) ?: return
        vaults.forEachIndexed { index, rawVault ->
            readVault(rawVault, accountName, "$location$SEGMENT_VAULTS[$index]", accumulator)
        }
    }

    /** 保险库节点：先确定本保险库条目共用的分组路径，再逐条映射。 */
    private fun readVault(rawVault: Any?, accountName: String, location: String, accumulator: Accumulator) {
        val vault = ImportJson.asObject(rawVault)
        if (vault == null) {
            accumulator.warn(location, "保险库节点不是 JSON 对象，已跳过")
            return
        }
        val groupPath = buildGroupPath(accountName, readVaultName(vault))
        if (groupPath.isEmpty()) accumulator.warn(location, "账户名与保险库名均为空，条目落至根分组")
        val items = ImportJson.asArray(vault[KEY_ITEMS]) ?: return
        items.forEachIndexed { index, rawItem ->
            mapItem(rawItem, "$location$SEGMENT_ITEMS[$index]", groupPath, accumulator)
        }
    }

    /** 映射单条 `items` 元素；跳过原因一律显式计入 [ImportReport.skipped] 并附警告。 */
    private fun mapItem(rawItem: Any?, location: String, groupPath: List<String>, accumulator: Accumulator) {
        accumulator.itemSeen()
        val item = ImportJson.asObject(rawItem)
        if (item == null) {
            accumulator.skip(location, "条目节点不是 JSON 对象")
            return
        }
        val categoryUuid = ImportJson.asString(item[KEY_CATEGORY_UUID]).orEmpty()
        if (categoryUuid.lowercase() !in LOGIN_CATEGORY_ALIASES) {
            accumulator.skip(location, "非登录类别条目（categoryUuid=$categoryUuid），已跳过")
            return
        }
        val details = ImportJson.asObject(item[KEY_DETAILS])
        val overview = ImportJson.asObject(item[KEY_OVERVIEW])
        val loginFields = ImportJson.asArray(details?.get(KEY_LOGIN_FIELDS))
        val rawTitle = ImportJson.asString(overview?.get(KEY_TITLE)).orEmpty()
        if (rawTitle.isBlank()) accumulator.warn(location, "条目标题为空，将由落库层回退为默认名")
        accumulator.entries += ImportedEntry(
            title = if (rawTitle.isBlank()) "" else rawTitle,
            username = loginFieldValue(loginFields, DESIGNATION_USERNAME).orEmpty(),
            password = loginFieldValue(loginFields, DESIGNATION_PASSWORD)?.toCharArray() ?: CharArray(0),
            url = overviewUrl(overview),
            notes = ImportJson.asString(details?.get(KEY_NOTES_PLAIN)).orEmpty(),
            groupPath = groupPath,
            totpSecret = findTotp(details)?.toCharArray(),
            deleted = ImportJson.asString(item[KEY_STATE]) == STATE_DELETED
        )
    }

    /** 账户名：`attrs.accountName` 优先，缺失时回退 `attrs.name`（官方文档两键并存）。 */
    private fun readAccountName(account: Map<*, *>): String {
        val attrs = ImportJson.asObject(account[KEY_ATTRS]) ?: return ""
        val accountName = ImportJson.asString(attrs[KEY_ACCOUNT_NAME]).orEmpty()
        return accountName.ifBlank { ImportJson.asString(attrs[KEY_NAME]).orEmpty() }
    }

    /** 保险库名：`vaults[].attrs.name`。 */
    private fun readVaultName(vault: Map<*, *>): String =
        ImportJson.asString(ImportJson.asObject(vault[KEY_ATTRS])?.get(KEY_NAME)).orEmpty()

    /** 分组路径 = `[账户名, 保险库名]`（自顶向下）；任一级为空则跳过该级，全空即落根分组。 */
    private fun buildGroupPath(accountName: String, vaultName: String): List<String> =
        listOf(accountName, vaultName).filter { it.isNotBlank() }

    /** 从 `details.loginFields` 取指定 designation 的字段值（官方文档：designation 仅 username / password）。 */
    private fun loginFieldValue(fields: List<*>?, designation: String): String? {
        if (fields == null) return null
        return fields.asSequence()
            .mapNotNull { ImportJson.asObject(it) }
            .firstOrNull { ImportJson.asString(it[KEY_DESIGNATION]) == designation }
            ?.let { fieldText(it[KEY_VALUE]) }
    }

    /**
     * TOTP 原文：先取 designation == totp 的字段，再回退扫描 `sections` 内所有 `otpauth://` 值。
     *
     * `sections[].fields[]` 属**可选**字段：其中形态非对象的元素（畸形输入）一律跳过、不参与 TOTP
     * 匹配，但**不影响**该条目的用户名 / 密码导入——缺 TOTP 不是结构性非法，故此处**不**返回 Failure。
     * 所有节点一律经 `asObject` 判定后使用，全程无裸强转（畸形输入不会抛 ClassCastException）。
     */
    private fun findTotp(details: Map<*, *>?): String? {
        if (details == null) return null
        val designated = loginFieldValue(ImportJson.asArray(details[KEY_LOGIN_FIELDS]), DESIGNATION_TOTP)
        if (designated != null) return designated
        val sections = ImportJson.asArray(details[KEY_SECTIONS]) ?: return null
        return sections.asSequence()
            .mapNotNull { ImportJson.asObject(it) }
            .flatMap { section -> sectionFields(section).asSequence() }
            .mapNotNull { ImportJson.asObject(it) }
            .mapNotNull { field -> totpCandidate(field) }
            .firstOrNull()
    }

    /** section 的 `fields` 数组（形态非法时按空列表降级）。 */
    private fun sectionFields(section: Map<*, *>): List<*> =
        ImportJson.asArray(section[KEY_FIELDS]) ?: emptyList<Any?>()

    /** designation 为 totp，或字段值以 `otpauth://` 开头 → 返回 TOTP 原文；否则 null。 */
    private fun totpCandidate(field: Map<*, *>): String? {
        val text = fieldText(field[KEY_VALUE]) ?: return null
        val designated = ImportJson.asString(field[KEY_DESIGNATION]) == DESIGNATION_TOTP
        return text.takeIf { designated || it.startsWith(OTPAUTH_SCHEME) }
    }

    /**
     * 归一字段值为字符串：1PUX 的 `value` 既可能是字符串，也可能是形如 `{"concealed": "..."}`
     * 或 `{"totp": "otpauth://..."}` 的对象（官方文档示例即含后者）；
     * 对象按 [MAPPED_FIELD_VALUE_KEYS] 顺序取首个字符串值，均无则 null。
     */
    private fun fieldText(value: Any?): String? {
        ImportJson.asString(value)?.let { return it }
        val mapped = ImportJson.asObject(value) ?: return null
        return MAPPED_FIELD_VALUE_KEYS.firstNotNullOfOrNull { ImportJson.asString(mapped[it]) }
    }

    /** 主 URL：`overview.url` 优先，为空时回退 `overview.urls[0].url`。 */
    private fun overviewUrl(overview: Map<*, *>?): String {
        val primary = ImportJson.asString(overview?.get(KEY_URL)).orEmpty()
        if (primary.isNotBlank()) return primary
        val urls = ImportJson.asArray(overview?.get(KEY_URLS)) ?: return ""
        val first = ImportJson.asObject(urls.firstOrNull()) ?: return ""
        return ImportJson.asString(first[KEY_URL]).orEmpty()
    }

    /** 一次解析的可变累积状态：条目、跳过计数与警告集中于此，并承担条目数闸门。 */
    private class Accumulator {
        val entries = mutableListOf<ImportedEntry>()
        private val warnings = ImportWarningSink()
        var skipped = 0
            private set

        private var itemsSeen = 0

        /**
         * 条目计数闸门：越过 `ImportLimits.MAX_ENTRIES_PER_IMPORT` 立即 fail-closed，
         * 不再继续逐条映射（计的是**已遍历条目数**，含将跳过的非登录类别条目）。
         */
        fun itemSeen() {
            itemsSeen++
            if (itemsSeen > ImportLimits.MAX_ENTRIES_PER_IMPORT) {
                throw ImportLimitExceededException(
                    "1PUX 条目数超出上限（${ImportLimits.MAX_ENTRIES_PER_IMPORT}）"
                )
            }
        }

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
        internal const val EXTENSION_1PUX = "1pux"

        /** 1PUX 归档内的数据条目名（官方文件结构：`export.attributes` / `export.data` / `files/`）。 */
        private const val ENTRY_EXPORT_DATA = "export.data"

        /** 1Password LOGIN 类别标识（官方 1PUX 文档示例：`"categoryUuid": "001"`）。 */
        private const val LOGIN_CATEGORY_UUID = "001"

        /** 兼容以类别名书写的导出（大小写不敏感）；未知类别一律跳过，不猜测。 */
        private val LOGIN_CATEGORY_ALIASES = setOf(LOGIN_CATEGORY_UUID, "login")

        private const val DESIGNATION_USERNAME = "username"
        private const val DESIGNATION_PASSWORD = "password"
        private const val DESIGNATION_TOTP = "totp"

        /** TOTP 原文前缀（otpauth URI；Base32 裸种子不在此列，须靠 designation 判定）。 */
        private const val OTPAUTH_SCHEME = "otpauth://"

        /** 官方文档中条目 `state` 的删除态取值（active / archived 均视为未删除）。 */
        private const val STATE_DELETED = "deleted"

        /** 对象形态字段值的取值键顺序（官方示例：`{"concealed": "..."}` / `{"totp": "..."}`）。 */
        private val MAPPED_FIELD_VALUE_KEYS = listOf("totp", "concealed", "string", "url")

        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_VAULTS = "vaults"
        private const val KEY_ITEMS = "items"
        private const val KEY_ATTRS = "attrs"
        private const val KEY_NAME = "name"
        private const val KEY_ACCOUNT_NAME = "accountName"
        private const val KEY_CATEGORY_UUID = "categoryUuid"
        private const val KEY_STATE = "state"
        private const val KEY_OVERVIEW = "overview"
        private const val KEY_TITLE = "title"
        private const val KEY_URL = "url"
        private const val KEY_URLS = "urls"
        private const val KEY_DETAILS = "details"
        private const val KEY_LOGIN_FIELDS = "loginFields"
        private const val KEY_NOTES_PLAIN = "notesPlain"
        private const val KEY_SECTIONS = "sections"
        private const val KEY_FIELDS = "fields"
        private const val KEY_DESIGNATION = "designation"
        private const val KEY_VALUE = "value"

        private const val LOCATION_ACCOUNTS = "accounts"
        private const val SEGMENT_VAULTS = ".vaults"
        private const val SEGMENT_ITEMS = ".items"

        private const val MESSAGE_BROKEN_ARCHIVE = "1PUX 归档无法读取：ZIP 结构已损坏"
        private const val MESSAGE_UNEXPECTED = "1PUX 解析失败：文件已损坏或格式不受支持"
    }
}
