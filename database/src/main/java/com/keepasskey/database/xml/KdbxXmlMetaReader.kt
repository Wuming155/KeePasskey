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
    private var settingsChanged: java.time.Instant? = null
    private var recycleBinEnabled: Boolean = true
    private var recycleBinUuid: KdbxUuid? = null
    private var recycleBinChanged: java.time.Instant? = null
    private var entryTemplatesGroup: KdbxUuid? = null
    private var entryTemplatesGroupChanged: java.time.Instant? = null
    private var historyMaxItems: Int = 10
    private var historyMaxSize: Long = 6 * 1024 * 1024L
    private var lastSelectedGroup: KdbxUuid? = null
    private var lastTopVisibleGroup: KdbxUuid? = null
    private var memoryProtection: MemoryProtectionConfig = MemoryProtectionConfig()
    private var customIcons: List<CustomIcon> = emptyList()
    private var deletedObjects: List<DeletedObject> = emptyList()
    private var customData: Map<String, String> = emptyMap()

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return when (name) {
            KdbxConstants.Xml.GENERATOR -> TextNode { generator = it }
            KdbxConstants.Xml.DATABASE_NAME -> TextNode { databaseName = it }
            KdbxConstants.Xml.DATABASE_NAME_CHANGED -> TextNode { databaseNameChanged = KdbxXmlTimeHelper.parseDate(it) }
            KdbxConstants.Xml.DATABASE_DESCRIPTION -> TextNode { databaseDescription = it }
            KdbxConstants.Xml.DATABASE_DESCRIPTION_CHANGED -> TextNode { databaseDescriptionChanged = KdbxXmlTimeHelper.parseDate(it) }
            KdbxConstants.Xml.DEFAULT_USER_NAME -> TextNode { defaultUserName = it }
            KdbxConstants.Xml.DEFAULT_USER_NAME_CHANGED -> TextNode { defaultUserNameChanged = KdbxXmlTimeHelper.parseDate(it) }
            KdbxConstants.Xml.MAINTENANCE_HISTORY_DAYS -> TextNode { maintenanceHistoryDays = it.trim().toIntOrNull() ?: 365 }
            KdbxConstants.Xml.COLOR -> TextNode { color = it }
            KdbxConstants.Xml.MASTER_KEY_CHANGED -> TextNode { masterKeyChanged = KdbxXmlTimeHelper.parseDate(it) }
            KdbxConstants.Xml.MASTER_KEY_CHANGE_REC -> TextNode { masterKeyChangeRec = it.trim().toIntOrNull() ?: -1 }
            KdbxConstants.Xml.MASTER_KEY_CHANGE_FORCE -> TextNode { masterKeyChangeForce = it.trim().toIntOrNull() ?: -1 }
            KdbxConstants.Xml.SETTINGS_CHANGED -> TextNode { settingsChanged = KdbxXmlTimeHelper.parseDate(it) }
            KdbxConstants.Xml.RECYCLE_BIN_ENABLED -> TextNode { recycleBinEnabled = it.lowercase() != "false" }
            KdbxConstants.Xml.RECYCLE_BIN_UUID -> TextNode { recycleBinUuid = KdbxXmlValueUtil.parseOptionalUuid(it) }
            KdbxConstants.Xml.RECYCLE_BIN_CHANGED -> TextNode { recycleBinChanged = KdbxXmlTimeHelper.parseDate(it) }
            KdbxConstants.Xml.ENTRY_TEMPLATES_GROUP -> TextNode { entryTemplatesGroup = KdbxXmlValueUtil.parseOptionalUuid(it) }
            KdbxConstants.Xml.ENTRY_TEMPLATES_GROUP_CHANGED -> TextNode { entryTemplatesGroupChanged = KdbxXmlTimeHelper.parseDate(it) }
            KdbxConstants.Xml.HISTORY_MAX_ITEMS -> TextNode { historyMaxItems = it.trim().toIntOrNull() ?: 10 }
            KdbxConstants.Xml.HISTORY_MAX_SIZE -> TextNode { historyMaxSize = it.trim().toLongOrNull() ?: (6 * 1024 * 1024L) }
            KdbxConstants.Xml.LAST_SELECTED_GROUP -> TextNode { lastSelectedGroup = KdbxXmlValueUtil.parseOptionalUuid(it) }
            KdbxConstants.Xml.LAST_TOP_VISIBLE_GROUP -> TextNode { lastTopVisibleGroup = KdbxXmlValueUtil.parseOptionalUuid(it) }
            KdbxConstants.Xml.MEMORY_PROTECTION -> MemoryProtectionNode { memoryProtection = it }
            KdbxConstants.Xml.CUSTOM_ICONS -> CustomIconsNode { customIcons = it }
            KdbxConstants.Xml.DELETED_OBJECTS -> DeletedObjectsNode { deletedObjects = it }
            KdbxConstants.Xml.CUSTOM_DATA -> CustomDataItemsNode { customData = it }
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
                memoryProtection = memoryProtection,
                customIcons = customIcons,
                deletedObjects = deletedObjects,
                customData = customData
            )
        )
    }
}

/**
 * <MemoryProtection> 子树。
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
            KdbxConstants.Xml.PROTECT_TITLE -> TextNode { protectTitle = it.lowercase() == "true" }
            KdbxConstants.Xml.PROTECT_USER_NAME -> TextNode { protectUserName = it.lowercase() == "true" }
            KdbxConstants.Xml.PROTECT_PASSWORD -> TextNode { protectPassword = it.lowercase() != "false" }
            KdbxConstants.Xml.PROTECT_URL -> TextNode { protectUrl = it.lowercase() == "true" }
            KdbxConstants.Xml.PROTECT_NOTES -> TextNode { protectNotes = it.lowercase() == "true" }
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
        return if (name == KdbxConstants.Xml.ICON) IconNode { icons.add(it) } else IgnoredNode()
    }

    override fun end() {
        onDone(icons.toList())
    }
}

private class IconNode(
    private val onDone: (CustomIcon) -> Unit
) : SaxNode() {

    private var iconUuid: KdbxUuid? = null
    private var dataText: String? = null

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return when (name) {
            KdbxConstants.Xml.UUID -> TextNode { iconUuid = KdbxXmlValueUtil.parseRequiredUuid(it, "CustomIcon") }
            KdbxConstants.Xml.DATA -> TextNode { dataText = it }
            else -> IgnoredNode()
        }
    }

    override fun end() {
        val uuid = requireUuid(iconUuid, "CustomIcon")
        val data = if (!dataText.isNullOrBlank()) {
            try {
                Base64.getDecoder().decode(dataText!!.trim())
            } catch (e: Exception) {
                throw KdbxCorruptFileException("CustomIcon Data Base64 损坏", e)
            }
        } else {
            ByteArray(0)
        }
        onDone(CustomIcon(uuid, data))
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
 * <CustomData> 子树（Meta 与 Entry 共用结构）。
 */
internal class CustomDataItemsNode(
    private val onDone: (Map<String, String>) -> Unit
) : SaxNode() {

    private val items = mutableMapOf<String, String>()

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return if (name == KdbxConstants.Xml.ITEM) ItemNode { (key, value) ->
            if (key.isNotEmpty()) {
                items[key] = value
            }
        } else IgnoredNode()
    }

    override fun end() {
        onDone(items.toMap())
    }
}

private class ItemNode(
    private val onDone: (Pair<String, String>) -> Unit
) : SaxNode() {

    private var key = ""
    private var value = ""

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return when (name) {
            KdbxConstants.Xml.KEY -> TextNode { key = it }
            KdbxConstants.Xml.VALUE -> TextNode { value = it }
            else -> IgnoredNode()
        }
    }

    override fun end() {
        onDone(Pair(key, value))
    }
}

internal fun requireUuid(value: KdbxUuid?, context: String): KdbxUuid {
    return value ?: throw KdbxCorruptFileException("缺少必需的 UUID 节点 ($context)")
}
