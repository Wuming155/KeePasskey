package com.keepasskey.database.xml

import com.keepasskey.core.model.CustomIcon
import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.MemoryProtectionConfig
import java.time.Instant

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
    val customData: Map<String, String> = emptyMap()
)
