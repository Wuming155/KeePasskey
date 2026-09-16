package com.keepasskey.app.data.importer

import com.keepasskey.app.data.repository.RealVaultRepository
import com.keepasskey.core.model.KdbxConstants
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.ext.DefaultHandler2

/**
 * KeePass 2.x 明文 XML 的流式（SAX）导入处理器。
 *
 * 结构对齐库内既有的 `KeePassXmlExporter` / `KdbxXmlParser`（`KeePassFile > Meta + Root > Group/Entry`）：
 * - 分组树递归下钻，产出 `groupPath`（**不含根分组名**，与 `ImportContracts.kt` 语义一致）；
 * - 回收站子树（`Meta/RecycleBinUUID` 命中，或第三方导出器的 `IsRecycleBin` 元素，
 *   或与库内既有语义一致的同名分组）下的条目置 `deleted = true`；
 * - 字段取自 `<Entry><String><Key>K</Key><Value>V</Value></String>`，键名大小写不敏感。
 *
 * 安全与敏感数据：
 * - `startDTD` 直接拒绝 DOCTYPE（封死 XXE 与内部实体展开炸弹，不依赖平台特性支持）；
 * - `resolveEntity` 拒绝一切外部实体解析；元素嵌套深度封顶（[ImportLimits.MAX_XML_DEPTH]）；
 * - 密码 / TOTP 等敏感值经 [SensitiveTextBuffer] 在 `characters` 第一现场收集，
 *   **全程不产生 String**；非敏感值才使用 `StringBuilder`。
 *
 * 已知取舍（如实登记）：
 * - `<History>` 历史修订子树不导入（共享契约 `ImportedEntry` 无历史字段），整份文档首次遇到时记一条警告；
 * - 自定义字段不导入（同上契约限制），首次遇到非空自定义字段时记一条警告；
 * - `Protected="True"` 的值在没有解密随机流时无法还原，该字段跳过并记警告（条目仍导入，不静默丢整条）。
 */
internal class KeePassXmlImportHandler(
    private val warnings: ImportWarningCollector
) : DefaultHandler2() {

    private val elementStack = ArrayDeque<String>()
    private val groupFrames = ArrayDeque<GroupFrame>()
    private val produced = mutableListOf<ImportedEntry>()

    private var skippedEntries = 0
    private var entrySeq = 0
    private var rootGroupSeen = false
    private var recycleBinUuid: String? = null
    private var historyDepth = NO_DEPTH
    private var activeEntry: EntryBuilder? = null
    private var historyWarningEmitted = false
    private var customFieldWarningEmitted = false

    /** 文档中是否出现过 `<History>` 子树（用于报告一条「历史修订未导入」警告）。 */
    private var historySeen: Boolean = false

    private var captureKind = CaptureKind.NONE
    private var captureOwnerDepth = NO_DEPTH
    private var captureSensitive = false
    private var protectedValue = false
    private val captureText = StringBuilder()
    private var captureSecret: SensitiveTextBuffer? = null

    private var pendingKey: String? = null
    private var pendingValueSensitive = false

    // ---------- SAX 回调 ----------

    override fun startDTD(name: String?, publicId: String?, systemId: String?) {
        // KeePass XML 规范不含 DOCTYPE（KeePass / KeePassXC / KeePassDX 产物皆无），
        // 声明即拒绝：该路径与平台解析器是否支持 disallow-doctype-decl 无关，属硬 fail-closed。
        throw ImportFormatException(DTD_REJECTED)
    }

    override fun resolveEntity(publicId: String?, systemId: String?): InputSource {
        throw ImportFormatException(EXTERNAL_ENTITY_REJECTED)
    }

    override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
        if (elementStack.isEmpty() && qName != KdbxConstants.Xml.ROOT) {
            throw ImportFormatException("根元素必须是 <${KdbxConstants.Xml.ROOT}>")
        }
        if (elementStack.size >= ImportLimits.MAX_XML_DEPTH) {
            throw ImportLimitExceededException("XML 嵌套深度超出上限")
        }
        elementStack.addLast(qName)
        when (qName) {
            KdbxConstants.Xml.GROUP -> startGroup()
            KdbxConstants.Xml.ENTRY -> startEntry()
            KdbxConstants.Xml.HISTORY -> startHistory()
            KdbxConstants.Xml.STRING -> if (!insideHistory()) clearPendingField()
            KdbxConstants.Xml.KEY -> beginKeyCapture()
            KdbxConstants.Xml.VALUE -> beginValueCapture(attributes)
            KdbxConstants.Xml.NAME -> beginTextCaptureIf(CaptureKind.GROUP_NAME, parentIs(KdbxConstants.Xml.GROUP))
            KdbxConstants.Xml.UUID -> beginTextCaptureIf(CaptureKind.GROUP_UUID, parentIs(KdbxConstants.Xml.GROUP))
            // IsRecycleBin 非官方元素：容忍第三方导出器（官方以 Meta/RecycleBinUUID 表达回收站）
            IS_RECYCLE_BIN_ELEMENT ->
                beginTextCaptureIf(CaptureKind.RECYCLE_FLAG, parentIs(KdbxConstants.Xml.GROUP))
            KdbxConstants.Xml.RECYCLE_BIN_UUID ->
                beginTextCaptureIf(CaptureKind.META_RECYCLE_UUID, parentIs(KdbxConstants.Xml.META))
            else -> Unit
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (captureKind == CaptureKind.NONE || length <= 0) return
        if (captureSensitive) {
            secretBuffer().append(ch, start, length)
        } else {
            if (captureText.length + length > ImportLimits.MAX_FIELD_CHARS) {
                throw ImportLimitExceededException("XML 文本节点超出长度上限")
            }
            captureText.append(ch, start, length)
        }
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        if (captureKind != CaptureKind.NONE && elementStack.size == captureOwnerDepth) finishCapture()
        when (qName) {
            KdbxConstants.Xml.ENTRY -> endEntry()
            KdbxConstants.Xml.GROUP -> endGroup()
            KdbxConstants.Xml.HISTORY -> if (elementStack.size == historyDepth) historyDepth = NO_DEPTH
            KdbxConstants.Xml.STRING -> if (!insideHistory()) clearPendingField()
            else -> Unit
        }
        if (elementStack.isNotEmpty()) elementStack.removeLast()
    }

    // ---------- 结构生命周期 ----------

    private fun startGroup() {
        val isRoot = !rootGroupSeen && parentIs(KdbxConstants.Xml.ROOT_GROUP)
        if (isRoot) rootGroupSeen = true
        groupFrames.addLast(GroupFrame(isRoot = isRoot))
    }

    private fun endGroup() {
        if (groupFrames.isNotEmpty()) groupFrames.removeLast()
    }

    private fun startEntry() {
        if (insideHistory()) return
        entrySeq++
        activeEntry = EntryBuilder()
    }

    private fun endEntry() {
        val builder = activeEntry
        if (insideHistory() || builder == null) return
        activeEntry = null
        finalizeEntry(builder)
    }

    /** 落入结果集合；必要字段全空视为跳过（计入 skipped 并记警告）。 */
    private fun finalizeEntry(builder: EntryBuilder) {
        val password = builder.password ?: CharArray(0)
        if (builder.title.isBlank() && builder.username.isBlank() && password.isEmpty()) {
            skippedEntries++
            builder.clearSensitive()
            password.fill(NUL_CHAR)
            warnings.add(
                ImportWarningLocation.entry(entrySeq),
                ImportWarningReason.MISSING_REQUIRED_VALUE
            )
            return
        }
        produced += ImportedEntry(
            title = builder.title,
            username = builder.username,
            password = password,
            url = builder.url,
            notes = builder.notes,
            groupPath = currentGroupPath(),
            totpSecret = builder.totp,
            deleted = insideRecycleBin()
        )
        ImportParseGuard.requireEntryCapacity(produced.size)
    }

    private fun currentGroupPath(): List<String> =
        groupFrames.filterNot { it.isRoot }.map { it.name }.filter { it.isNotEmpty() }

    private fun insideRecycleBin(): Boolean = groupFrames.any { it.isRecycleBin }

    private fun startHistory() {
        historyDepth = elementStack.size
        historySeen = true
    }

    private fun insideHistory(): Boolean = historyDepth != NO_DEPTH && elementStack.size >= historyDepth

    private fun parentIs(name: String): Boolean =
        elementStack.size >= PARENT_OFFSET && elementStack.elementAt(elementStack.size - PARENT_OFFSET) == name

    // ---------- 文本捕获 ----------

    private fun beginKeyCapture() {
        if (!insideHistory() && parentIs(KdbxConstants.Xml.STRING)) {
            beginCapture(CaptureKind.KEY, sensitive = false)
        }
    }

    private fun beginValueCapture(attributes: Attributes) {
        if (insideHistory() || !parentIs(KdbxConstants.Xml.STRING)) return
        protectedValue = attributes.getValue(KdbxConstants.Xml.PROTECTED)?.trim()
            ?.equals(TRUE_LITERAL, ignoreCase = true) == true
        beginCapture(CaptureKind.VALUE, sensitive = pendingValueSensitive && !protectedValue)
    }

    private fun beginTextCaptureIf(kind: CaptureKind, condition: Boolean) {
        if (condition && !insideHistory()) beginCapture(kind, sensitive = false)
    }

    private fun beginCapture(kind: CaptureKind, sensitive: Boolean) {
        captureKind = kind
        captureOwnerDepth = elementStack.size
        captureSensitive = sensitive
        captureText.setLength(0)
        captureSecret?.clear()
        captureSecret = null
    }

    private fun secretBuffer(): SensitiveTextBuffer =
        captureSecret ?: SensitiveTextBuffer().also { captureSecret = it }

    private fun finishCapture() {
        val kind = captureKind
        captureKind = CaptureKind.NONE
        captureOwnerDepth = NO_DEPTH
        when (kind) {
            CaptureKind.KEY -> onKeyCaptured()
            CaptureKind.VALUE -> onValueCaptured()
            CaptureKind.GROUP_NAME -> onGroupNameCaptured()
            CaptureKind.GROUP_UUID -> onGroupUuidCaptured()
            CaptureKind.RECYCLE_FLAG -> onRecycleFlagCaptured()
            CaptureKind.META_RECYCLE_UUID -> recycleBinUuid = captureText.toString().trim()
            CaptureKind.NONE -> Unit
        }
        captureText.setLength(0)
        captureSecret?.clear()
        captureSecret = null
    }

    private fun onKeyCaptured() {
        val key = captureText.toString().trim()
        pendingKey = key
        pendingValueSensitive = isSensitiveKey(key.lowercase())
    }

    private fun onValueCaptured() {
        val builder = activeEntry
        val key = pendingKey
        if (builder == null || key == null) return
        if (protectedValue) {
            // 受保护值需要内层随机流才能解密，明文导入通道没有该流：跳过该字段但保留条目
            captureSecret?.clear()
            warnings.add(ImportWarningLocation.entry(entrySeq), ImportWarningReason.PROTECTED_VALUE_SKIPPED)
            return
        }
        when (roleOf(key.lowercase())) {
            FieldRole.PASSWORD -> builder.password = takeSecret()
            FieldRole.TOTP -> builder.totp = takeSecret()
            FieldRole.TITLE -> builder.title = captureText.toString()
            FieldRole.USERNAME -> builder.username = captureText.toString()
            FieldRole.URL -> builder.url = captureText.toString()
            FieldRole.NOTES -> builder.notes = captureText.toString()
            FieldRole.IGNORED -> onIgnoredField()
        }
    }

    private fun onIgnoredField() {
        val hasValue = captureText.isNotBlank() ||
            (captureSecret?.let { it.toCharArrayAndClear().isNotEmpty() } == true)
        if (hasValue && !customFieldWarningEmitted) {
            customFieldWarningEmitted = true
            warnings.add(ImportWarningLocation.DOCUMENT, ImportWarningReason.CUSTOM_FIELD_DROPPED)
        }
    }

    private fun onGroupUuidCaptured() {
        val text = captureText.toString().trim()
        val target = groupFrames.lastOrNull() ?: return
        if (recycleBinUuid != null && text == recycleBinUuid) target.isRecycleBin = true
    }

    private fun onGroupNameCaptured() {
        val name = captureText.toString().trim()
        val target = groupFrames.lastOrNull() ?: return
        target.name = name
        // 名称兜底：库内 RealVaultRepository.getGroups() 亦以「回收站 / Recycle Bin」识别回收站，
        // 覆盖未回填 Meta/RecycleBinUUID 的外部导出文件
        if (name.lowercase() in RECYCLE_BIN_NAMES) target.isRecycleBin = true
    }

    private fun onRecycleFlagCaptured() {
        if (!captureText.toString().trim().equals(TRUE_LITERAL, ignoreCase = true)) return
        groupFrames.lastOrNull()?.isRecycleBin = true
    }

    /** 交出敏感值独占副本（工作缓冲随即清零）；无缓冲时返回空数组。 */
    private fun takeSecret(): CharArray = captureSecret?.toCharArrayAndClear() ?: CharArray(0)

    private fun clearPendingField() {
        pendingKey = null
        pendingValueSensitive = false
        protectedValue = false
    }

    /** 记录文档级「历史修订已忽略」警告（每份文档至多一条）。 */
    private fun noteHistoryIfNeeded() {
        if (!historyWarningEmitted && historySeen) {
            historyWarningEmitted = true
            warnings.add(ImportWarningLocation.DOCUMENT, ImportWarningReason.HISTORY_IGNORED)
        }
    }

    // ---------- 结果 ----------

    /** 已产出条目（失败路径用于清零敏感数组）。 */
    fun producedEntries(): List<ImportedEntry> = produced

    /** 组装解析报告（统计全部非敏感）。 */
    fun buildBatch(source: ImportSource): ImportBatch {
        noteHistoryIfNeeded()
        return ImportBatch(
            report = ImportReport(
                source = source,
                parsed = produced.size,
                skipped = skippedEntries,
                warnings = warnings.snapshot()
            ),
            entries = produced.toList()
        )
    }

    // ---------- 内部类型 ----------

    private enum class CaptureKind { NONE, KEY, VALUE, GROUP_NAME, GROUP_UUID, RECYCLE_FLAG, META_RECYCLE_UUID }

    private enum class FieldRole { TITLE, USERNAME, PASSWORD, URL, NOTES, TOTP, IGNORED }

    private class GroupFrame(val isRoot: Boolean) {
        var name: String = ""
        var isRecycleBin: Boolean = false
    }

    private class EntryBuilder {
        var title: String = ""
        var username: String = ""
        var url: String = ""
        var notes: String = ""
        var password: CharArray? = null
        var totp: CharArray? = null

        fun clearSensitive() {
            password?.fill(NUL_CHAR)
            totp?.fill(NUL_CHAR)
            password = null
            totp = null
        }
    }

    private companion object {
        const val NO_DEPTH = -1
        const val PARENT_OFFSET = 2
        const val NUL_CHAR = '\u0000'
        const val TRUE_LITERAL = "true"

        /** 回收站分组的兼容名称：与 `RealVaultRepository.getGroups()` 的既有语义保持一致。 */
        val RECYCLE_BIN_NAMES = setOf(RealVaultRepository.RECYCLE_BIN_NAME.lowercase(), "recycle bin")

        val TITLE_KEY = KdbxConstants.Fields.TITLE.lowercase()
        val USERNAME_KEY = KdbxConstants.Fields.USER_NAME.lowercase()
        val PASSWORD_KEY = KdbxConstants.Fields.PASSWORD.lowercase()
        val URL_KEY = KdbxConstants.Fields.URL.lowercase()
        val NOTES_KEY = KdbxConstants.Fields.NOTES.lowercase()
        val OTP_KEY = KdbxConstants.Fields.OTP.lowercase()
        const val TOTP_KEY_PREFIX = "totp"

        /** 敏感键的保守判据：宁可多判（只多走 CharArray，无副作用），不可漏判。 */
        val SENSITIVE_KEY_MARKERS = listOf("secret", "seed", "private", "recovery")

        const val DTD_REJECTED = "KeePass XML 不允许包含 DTD 声明（DOCTYPE）"
        const val EXTERNAL_ENTITY_REJECTED = "KeePass XML 不允许包含外部实体引用"

        /** 非官方扩展元素：部分第三方导出器以此表达回收站组（官方用 Meta/RecycleBinUUID）。 */
        const val IS_RECYCLE_BIN_ELEMENT = "IsRecycleBin"

        fun roleOf(normalizedKey: String): FieldRole = when (normalizedKey) {
            TITLE_KEY -> FieldRole.TITLE
            USERNAME_KEY -> FieldRole.USERNAME
            PASSWORD_KEY -> FieldRole.PASSWORD
            URL_KEY -> FieldRole.URL
            NOTES_KEY -> FieldRole.NOTES
            else -> if (isTotpKey(normalizedKey)) FieldRole.TOTP else FieldRole.IGNORED
        }

        fun isTotpKey(normalizedKey: String): Boolean =
            normalizedKey == OTP_KEY || normalizedKey.startsWith(TOTP_KEY_PREFIX)

        fun isSensitiveKey(normalizedKey: String): Boolean =
            normalizedKey == PASSWORD_KEY ||
                normalizedKey.startsWith(OTP_KEY) ||
                normalizedKey.startsWith(TOTP_KEY_PREFIX) ||
                SENSITIVE_KEY_MARKERS.any { normalizedKey.contains(it) }
    }
}
