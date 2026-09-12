package com.keepasskey.database.xml

import com.keepasskey.core.model.CustomIcon
import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.MemoryProtectionConfig
import com.keepasskey.database.exception.KdbxCorruptFileException
import org.xml.sax.Attributes
import java.util.Base64

/**
 * KDBX XML <Meta> 节点流式解析节点。
 * 字段缺省语义与原 DOM 解析逐一对齐。
 */
internal class MetaNode(
    private val onDone: (KdbxMetaData) -> Unit
) : SaxNode() {

    private var generator: String = "KeePasskey"
    private var databaseName: String = ""
    private var databaseNameChanged: java.time.Instant? = null
    private var databaseDescription: String = ""
    private var databaseDescriptionChanged: java.time.Instant? = null
    private var defaultUserName: String = ""
    private var defaultUserNameChanged: java.time.Instant? = null
    private var maintenanceHistoryDays: Int = 365
    private var color: String = ""
    private var masterKeyChanged: java.time.Instant? = null
    private var masterKeyChangeRec: Int = -1
    private var masterKeyChangeForce: Int = -1
    private var masterKeyChangeForceOnce: Boolean = false
    private var settingsChanged: java.time.Instant? = null
    private var recycleBinEnabled: Boolean = true
    private var recycleBinUuid: KdbxUuid? = null
    private var recycleBinChanged: java.time.Instant? = null
    private var entryTemplatesGroup: KdbxUuid? = null
    private var entryTemplatesGroupChanged: java.time.Instant? = null
    private var historyMaxItems: Int = DEFAULT_HISTORY_MAX_ITEMS
    private var historyMaxSize: Long = DEFAULT_HISTORY_MAX_SIZE
    private var lastSelectedGroup: KdbxUuid? = null
    private var lastTopVisibleGroup: KdbxUuid? = null

    /** 第一阶段逐项解析结果；装载收尾按官方语义整体弃用，见 [officialMemoryProtectionReset]。 */
    private var parsedMemoryProtection: MemoryProtectionConfig = MemoryProtectionConfig()
    private var customIcons: List<CustomIcon> = emptyList()
    private var deletedObjects: List<DeletedObject> = emptyList()
    private var customData: Map<String, String> = emptyMap()
    private var customDataTimes: Map<String, java.time.Instant> = emptyMap()

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return when (name) {
            KdbxConstants.Xml.GENERATOR -> TextNode { generator = it }
            KdbxConstants.Xml.DATABASE_NAME -> TextNode { databaseName = it }
            KdbxConstants.Xml.DATABASE_NAME_CHANGED -> TextNode { databaseNameChanged = KdbxXmlTimeHelper.parseDate(it) }
            KdbxConstants.Xml.DATABASE_DESCRIPTION -> TextNode { databaseDescription = it }
            KdbxConstants.Xml.DATABASE_DESCRIPTION_CHANGED -> TextNode { databaseDescriptionChanged = KdbxXmlTimeHelper.parseDate(it) }
            KdbxConstants.Xml.DEFAULT_USER_NAME -> TextNode { defaultUserName = it }
            KdbxConstants.Xml.DEFAULT_USER_NAME_CHANGED -> TextNode { defaultUserNameChanged = KdbxXmlTimeHelper.parseDate(it) }
            KdbxConstants.Xml.MAINTENANCE_HISTORY_DAYS -> TextNode { maintenanceHistoryDays = it.trim().toIntOrNull() ?: DEFAULT_MAINTENANCE_HISTORY_DAYS }
            KdbxConstants.Xml.COLOR -> TextNode { color = it }
            KdbxConstants.Xml.MASTER_KEY_CHANGED -> TextNode { masterKeyChanged = KdbxXmlTimeHelper.parseDate(it) }
            KdbxConstants.Xml.MASTER_KEY_CHANGE_REC -> TextNode { masterKeyChangeRec = it.trim().toIntOrNull() ?: DEFAULT_MASTER_KEY_CHANGE }
            KdbxConstants.Xml.MASTER_KEY_CHANGE_FORCE -> TextNode { masterKeyChangeForce = it.trim().toIntOrNull() ?: DEFAULT_MASTER_KEY_CHANGE }
            // 官方 ReadBool(xr, false)（Read.Streamed.cs:250）：精确匹配，非法值回落 false
            XML_MASTER_KEY_CHANGE_FORCE_ONCE -> TextNode { masterKeyChangeForceOnce = KdbxXmlScalarParsers.parseBool(it, false) }
            KdbxConstants.Xml.SETTINGS_CHANGED -> TextNode { settingsChanged = KdbxXmlTimeHelper.parseDate(it) }
            // 官方 ReadBool(xr, true)（Read.Streamed.cs:256）：回收站默认启用，
            // 非法值（含 "FALSE"/"1" 等非规范写法）回落 true，不得被当作 false
            KdbxConstants.Xml.RECYCLE_BIN_ENABLED -> TextNode { recycleBinEnabled = KdbxXmlScalarParsers.parseBool(it, true) }
            KdbxConstants.Xml.RECYCLE_BIN_UUID -> TextNode { recycleBinUuid = KdbxXmlValueUtil.parseOptionalUuid(it) }
            KdbxConstants.Xml.RECYCLE_BIN_CHANGED -> TextNode { recycleBinChanged = KdbxXmlTimeHelper.parseDate(it) }
            KdbxConstants.Xml.ENTRY_TEMPLATES_GROUP -> TextNode { entryTemplatesGroup = KdbxXmlValueUtil.parseOptionalUuid(it) }
            KdbxConstants.Xml.ENTRY_TEMPLATES_GROUP_CHANGED -> TextNode { entryTemplatesGroupChanged = KdbxXmlTimeHelper.parseDate(it) }
            KdbxConstants.Xml.HISTORY_MAX_ITEMS -> TextNode { historyMaxItems = it.trim().toIntOrNull() ?: DEFAULT_HISTORY_MAX_ITEMS }
            KdbxConstants.Xml.HISTORY_MAX_SIZE -> TextNode { historyMaxSize = it.trim().toLongOrNull() ?: DEFAULT_HISTORY_MAX_SIZE }
            KdbxConstants.Xml.LAST_SELECTED_GROUP -> TextNode { lastSelectedGroup = KdbxXmlValueUtil.parseOptionalUuid(it) }
            KdbxConstants.Xml.LAST_TOP_VISIBLE_GROUP -> TextNode { lastTopVisibleGroup = KdbxXmlValueUtil.parseOptionalUuid(it) }
            KdbxConstants.Xml.MEMORY_PROTECTION -> MemoryProtectionNode { parsedMemoryProtection = it }
            KdbxConstants.Xml.CUSTOM_ICONS -> CustomIconsNode { customIcons = it }
            // 官方把 <DeletedObjects> 写在 <Root> 作用域（KdbxFile.Write.cs:430 /
            // Read.Streamed.cs:369,747），本分支仅为**兼容历史产物**保留：本仓旧版本曾把它写在
            // <Meta> 内，读侧两处均接收以保证旧库墓碑不丢失；写侧只在 Root 写出
            // （见 KdbxXmlSerializer / KdbxXmlMetaSerializer.serializeDeletedObjects）。
            KdbxConstants.Xml.DELETED_OBJECTS -> DeletedObjectsNode { deletedObjects = it }
            KdbxConstants.Xml.CUSTOM_DATA -> MetaCustomDataNode { values, times ->
                customData = values
                customDataTimes = times
            }
            else -> IgnoredNode()
        }
    }

    override fun end() {
        onDone(
            KdbxMetaData(
                generator = generator,
                databaseName = databaseName,
                databaseNameChanged = databaseNameChanged,
                databaseDescription = databaseDescription,
                databaseDescriptionChanged = databaseDescriptionChanged,
                defaultUserName = defaultUserName,
                defaultUserNameChanged = defaultUserNameChanged,
                maintenanceHistoryDays = maintenanceHistoryDays,
                color = color,
                masterKeyChanged = masterKeyChanged,
                masterKeyChangeRec = masterKeyChangeRec,
                masterKeyChangeForce = masterKeyChangeForce,
                masterKeyChangeForceOnce = masterKeyChangeForceOnce,
                settingsChanged = settingsChanged,
                recycleBinEnabled = recycleBinEnabled,
                recycleBinUuid = recycleBinUuid,
                recycleBinChanged = recycleBinChanged,
                entryTemplatesGroup = entryTemplatesGroup,
                entryTemplatesGroupChanged = entryTemplatesGroupChanged,
                historyMaxItems = historyMaxItems,
                historyMaxSize = historyMaxSize,
                lastSelectedGroup = lastSelectedGroup,
                lastTopVisibleGroup = lastTopVisibleGroup,
                memoryProtection = officialMemoryProtectionReset(parsedMemoryProtection),
                customIcons = customIcons,
                deletedObjects = deletedObjects,
                customData = customData,
                customDataTimes = customDataTimes
            )
        )
    }

    /**
     * 官方装载收尾的内存保护重置（KeePass 2.61.1 `KdbxFile.Read.cs:246-248`，注释
     * "Reset memory protection settings (to always use reasonable defaults)"）：
     * 无论文件里写了什么组合，一律改用 [MemoryProtectionConfig] 的合理默认值，
     * 防止恶意/畸形库把 Password 等字段的驻留加密整体关闭。
     *
     * 两段式实现与官方一致：第一阶段由 [MemoryProtectionNode] 逐项解析（ProtectPassword 缺省 `true`、
     * 其余缺省 `false`，见 `Read.Streamed.cs:282-291`），保证「文件可读性」判定与官方同源；
     * 第二阶段（本方法）按官方语义整体弃用该解析结果。
     *
     * @param parsed 第一阶段解析结果，按官方语义在此丢弃。
     */
    private fun officialMemoryProtectionReset(
        @Suppress("UNUSED_PARAMETER") parsed: MemoryProtectionConfig
    ): MemoryProtectionConfig = MemoryProtectionConfig()

    private companion object {
        /** `<MaintenanceHistoryDays>` 缺省：官方 `ReadUInt(xr, 365)` */
        const val DEFAULT_MAINTENANCE_HISTORY_DAYS = 365

        /**
         * `<HistoryMaxItems>` 元素缺失时的缺省值：保持本仓既有 `10` 不变。
         *
         * 存疑项：官方此处为 `ReadInt(xr, -1)`（`Read.Streamed.cs:266`，-1 = 不限制数量），
         * 与本仓缺省不一致；该差异不在本批次缺陷范围内，已在交付报告中单列，未擅自扩大改动。
         */
        const val DEFAULT_HISTORY_MAX_ITEMS = 10

        /**
         * `<HistoryMaxSize>` 元素缺失时的缺省值：官方 `ReadLong(xr, -1)`（`Read.Streamed.cs:267-268`），
         * -1 表示「不限制体积」。本仓 [com.keepasskey.database.history.HistoryManager] 对负值同样按
         * 「不限制」处理，故语义一致。
         */
        const val DEFAULT_HISTORY_MAX_SIZE = -1L

        /** 主密钥提醒/强制修改天数缺省：官方 `ReadLong(xr, -1)`（-1 = 不提醒/不强制） */
        const val DEFAULT_MASTER_KEY_CHANGE = -1
    }
}

/**
 * <MemoryProtection> 子树。
 *
 * 逐项缺省值严格对齐官方 `Read.Streamed.cs:282-291`：ProtectPassword 缺省 `true`，其余缺省 `false`。
 * 注意解析结果会被装载收尾的官方重置语义整体弃用（见 [MetaNode.officialMemoryProtectionReset]）。
 */
internal class MemoryProtectionNode(
    private val onDone: (MemoryProtectionConfig) -> Unit
) : SaxNode() {

    private var protectTitle = false
    private var protectUserName = false
    private var protectPassword = true
    private var protectUrl = false
    private var protectNotes = false

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return when (name) {
            // 官方逐项 ReadBool（Read.Streamed.cs:282-291）：精确匹配 + **各字段各自的默认值**
            // （ProtectPassword 默认 true，其余四项默认 false）。注意本节点解析结果在装载收尾会被
            // 整体重置为默认（KdbxFile.Read.cs:246-248），此处对齐语义以保持与官方逐字段一致。
            KdbxConstants.Xml.PROTECT_TITLE -> TextNode { protectTitle = KdbxXmlScalarParsers.parseBool(it, false) }
            KdbxConstants.Xml.PROTECT_USER_NAME -> TextNode { protectUserName = KdbxXmlScalarParsers.parseBool(it, false) }
            KdbxConstants.Xml.PROTECT_PASSWORD -> TextNode { protectPassword = KdbxXmlScalarParsers.parseBool(it, true) }
            KdbxConstants.Xml.PROTECT_URL -> TextNode { protectUrl = KdbxXmlScalarParsers.parseBool(it, false) }
            KdbxConstants.Xml.PROTECT_NOTES -> TextNode { protectNotes = KdbxXmlScalarParsers.parseBool(it, false) }
            else -> IgnoredNode()
        }
    }

    override fun end() {
        onDone(
            MemoryProtectionConfig(
                protectTitle = protectTitle,
                protectUserName = protectUserName,
                protectPassword = protectPassword,
                protectUrl = protectUrl,
                protectNotes = protectNotes
            )
        )
    }
}

/**
 * <CustomIcons> 子树。
 */
internal class CustomIconsNode(
    private val onDone: (List<CustomIcon>) -> Unit
) : SaxNode() {

    private val icons = mutableListOf<CustomIcon>()

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return if (name == KdbxConstants.Xml.ICON) {
            IconNode { icon -> if (icon != null) icons.add(icon) }
        } else {
            IgnoredNode()
        }
    }

    override fun end() {
        onDone(icons.toList())
    }
}

/**
 * `<CustomIcons><Icon>` 单图标节点。
 *
 * 按官方语义（`KdbxFile.Read.Streamed.cs:613-623`）**丢弃**零 UUID 或缺少有效 `<Data>` 的图标：
 * 官方仅在 `!uuid.IsZero && data != null` 时才把图标加入库图标池，其余情况仅断言后跳过。
 * 回调参数为 `null` 表示该图标被丢弃。
 *
 * [name] / [lastModificationTime] 为官方 KDBX 4.1 追加字段（`Read.Streamed.cs:312-315`），
 * 元素缺失时分别为空串与 `null`。
 */
private class IconNode(
    private val onDone: (CustomIcon?) -> Unit
) : SaxNode() {

    private var iconUuid: KdbxUuid? = null
    private var dataText: String? = null
    private var iconName = ""
    private var lastModificationTime: java.time.Instant? = null

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return when (name) {
            // 官方 ReadUuid 对空内容返回 Zero（Read.Streamed.cs:855-858），故此处用可选解析：
            // 元素缺失/空文本 → null → 该图标被丢弃，而非抛错（官方同为静默跳过）。
            KdbxConstants.Xml.UUID -> TextNode { iconUuid = KdbxXmlValueUtil.parseOptionalUuid(it) }
            KdbxConstants.Xml.DATA -> TextNode { dataText = it }
            KdbxConstants.Xml.NAME -> TextNode { iconName = it }
            KdbxConstants.Xml.LAST_MODIFICATION_TIME -> TextNode { lastModificationTime = KdbxXmlTimeHelper.parseDate(it) }
            else -> IgnoredNode()
        }
    }

    override fun end() {
        val uuid = iconUuid
        // 官方：Data 为空串时不置 m_pbCustomIconData（Read.Streamed.cs:308-310），等同于「无 data」
        val encodedData = dataText?.trim()?.takeIf { it.isNotEmpty() }
        if (uuid == null || uuid == KdbxUuid.ZERO || encodedData == null) {
            onDone(null)
            return
        }
        val data = try {
            Base64.getDecoder().decode(encodedData)
        } catch (e: Exception) {
            throw KdbxCorruptFileException("CustomIcon Data Base64 损坏", e)
        }
        onDone(CustomIcon(uuid, data, iconName, lastModificationTime))
    }
}

/**
 * <DeletedObjects> 子树。
 */
internal class DeletedObjectsNode(
    private val onDone: (List<DeletedObject>) -> Unit
) : SaxNode() {

    private val deletedObjects = mutableListOf<DeletedObject>()

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return if (name == KdbxConstants.Xml.DELETED_OBJECT) DeletedObjectNode { deletedObjects.add(it) } else IgnoredNode()
    }

    override fun end() {
        onDone(deletedObjects.toList())
    }
}

private class DeletedObjectNode(
    private val onDone: (DeletedObject) -> Unit
) : SaxNode() {

    private var uuid: KdbxUuid? = null
    private var deletionTimeText: String? = null

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return when (name) {
            KdbxConstants.Xml.UUID -> TextNode { uuid = KdbxXmlValueUtil.parseRequiredUuid(it, "DeletedObject") }
            KdbxConstants.Xml.DELETION_TIME -> TextNode { deletionTimeText = it }
            else -> IgnoredNode()
        }
    }

    override fun end() {
        onDone(DeletedObject(requireUuid(uuid, "DeletedObject"), KdbxXmlTimeHelper.parseDate(deletionTimeText)))
    }
}

/**
 * `<CustomData><Item>` 单条目解析产物（官方 KDBX 4.1 `TCustomDataWithTimes`）。
 *
 * @property lastModificationTime Meta 级条目才携带；Entry/Group 级 `<CustomData>` 无该字段（官方写侧同样不写）
 */
internal data class CustomDataItem(
    val key: String,
    val value: String,
    val lastModificationTime: java.time.Instant?
)

/**
 * `<CustomData>` 子树（Entry / Group 级：无时间戳，官方 Entry/Group 写侧不写 LastModificationTime）。
 */
internal class CustomDataItemsNode(
    private val onDone: (Map<String, String>) -> Unit
) : SaxNode() {

    private val items = mutableMapOf<String, String>()

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return if (name == KdbxConstants.Xml.ITEM) ItemNode { item ->
            if (item.key.isNotEmpty()) {
                items[item.key] = item.value
            }
        } else IgnoredNode()
    }

    override fun end() {
        onDone(items.toMap())
    }
}

/**
 * Meta 级 `<CustomData>` 子树：在键值之外额外保留每项的 `<LastModificationTime>`
 * （官方 `Read.Streamed.cs:352`）。
 *
 * 单独成类而非扩展 [CustomDataItemsNode]：Entry/Group 级 `<CustomData>` 共用同一节点类型，
 * 保持其既有构造签名可避免波及条目/分组解析路径。
 *
 * @param onDone 参数依次为「键值映射」与「键 → 最后修改时间」（后者仅含带时间戳的项）
 */
internal class MetaCustomDataNode(
    private val onDone: (Map<String, String>, Map<String, java.time.Instant>) -> Unit
) : SaxNode() {

    private val items = mutableMapOf<String, String>()
    private val times = mutableMapOf<String, java.time.Instant>()

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return if (name == KdbxConstants.Xml.ITEM) ItemNode { item ->
            if (item.key.isNotEmpty()) {
                items[item.key] = item.value
                val lastMod = item.lastModificationTime
                if (lastMod != null) {
                    times[item.key] = lastMod
                }
            }
        } else IgnoredNode()
    }

    override fun end() {
        onDone(items.toMap(), times.toMap())
    }
}

private class ItemNode(
    private val onDone: (CustomDataItem) -> Unit
) : SaxNode() {

    private var key = ""
    private var value = ""
    private var lastModificationTime: java.time.Instant? = null

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return when (name) {
            KdbxConstants.Xml.KEY -> TextNode { key = it }
            KdbxConstants.Xml.VALUE -> TextNode { value = it }
            KdbxConstants.Xml.LAST_MODIFICATION_TIME -> TextNode { lastModificationTime = KdbxXmlTimeHelper.parseDate(it) }
            else -> IgnoredNode()
        }
    }

    override fun end() {
        onDone(CustomDataItem(key, value, lastModificationTime))
    }
}

internal fun requireUuid(value: KdbxUuid?, context: String): KdbxUuid {
    return value ?: throw KdbxCorruptFileException("缺少必需的 UUID 节点 ($context)")
}
