package com.keepasskey.database.xml

import com.keepasskey.core.model.CustomIcon
import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.MemoryProtectionConfig
import java.time.Instant

/**
 * `<Meta><MasterKeyChangeForceOnce>` 元素名（官方 `ElemDbKeyChangeForceOnce`）。
 *
 * 说明：[com.keepasskey.core.model.KdbxConstants.Xml] 尚未收录该字段（属跨文件协调项），
 * 暂以本模块内常量承载；[KdbxXmlMetaReader] 与 [KdbxXmlMetaSerializer] 共用同一常量，
 * 待常量集中表补齐后可整体平移，行为不变。
 */
internal const val XML_MASTER_KEY_CHANGE_FORCE_ONCE = "MasterKeyChangeForceOnce"

/**
 * KDBX XML <Meta> 节点解析产物。
 * 字段与默认值对齐官方 KeePass 2.61.1（PwDatabase 初始值）。
 */
data class KdbxMetaData(
    val generator: String = "KeePasskey",
    val databaseName: String = "KeePass",
    val databaseNameChanged: Instant? = null,
    val databaseDescription: String = "",
    val databaseDescriptionChanged: Instant? = null,
    val defaultUserName: String = "",
    val defaultUserNameChanged: Instant? = null,
    /** 官方默认 365 天（历史维护周期） */
    val maintenanceHistoryDays: Int = 365,
    /** 库级颜色（官方为颜色名字符串，空串表示未设置） */
    val color: String = "",
    val masterKeyChanged: Instant? = null,
    /** 主密钥修改提醒（天）；-1 = 不提醒（官方默认） */
    val masterKeyChangeRec: Int = -1,
    /** 主密钥强制修改（天）；-1 = 不强制（官方默认） */
    val masterKeyChangeForce: Int = -1,
    /**
     * 仅强制修改一次主密钥（官方 KDBX 4.1 `<MasterKeyChangeForceOnce>`，`ReadBool(xr, false)`）。
     * 官方写出侧仅在为 `true` 时写出该元素，故缺省 `false`。
     */
    val masterKeyChangeForceOnce: Boolean = false,
    val settingsChanged: Instant? = null,
    val recycleBinEnabled: Boolean = true,
    val recycleBinUuid: KdbxUuid? = null,
    val recycleBinChanged: Instant? = null,
    val entryTemplatesGroup: KdbxUuid? = null,
    val entryTemplatesGroupChanged: Instant? = null,
    val historyMaxItems: Int = 10,
    val historyMaxSize: Long = 6 * 1024 * 1024L,
    val lastSelectedGroup: KdbxUuid? = null,
    val lastTopVisibleGroup: KdbxUuid? = null,
    val memoryProtection: MemoryProtectionConfig = MemoryProtectionConfig(),
    val customIcons: List<CustomIcon> = emptyList(),
    val deletedObjects: List<DeletedObject> = emptyList(),
    val customData: Map<String, String> = emptyMap(),
    /**
     * Meta 级 `<CustomData><Item>` 每项的 `<LastModificationTime>`
     * （官方 KDBX 4.1 `TCustomDataWithTimes`，读 `Read.Streamed.cs:352`／写 `Write.cs:673,821`）。
     *
     * 取舍说明：既有 [customData] 为 `Map<String, String>`，其消费方分布在 app/sync 模块，
     * 直接改类型会造成跨模块破坏性变更；故采用**并行字段**承载时间戳，
     * 键集合与 [customData] 对齐（键缺失表示该项无时间戳），原类型与签名保持不变。
     */
    val customDataTimes: Map<String, Instant> = emptyMap()
)
