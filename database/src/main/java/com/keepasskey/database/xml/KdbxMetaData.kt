package com.keepasskey.database.xml

import com.keepasskey.core.model.CustomIcon
import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.MemoryProtectionConfig
import java.time.Instant

/**
 * KDBX XML <Meta> 节点解析产物。
 */
data class KdbxMetaData(
    val generator: String = "KeePasskey",
    val databaseName: String = "KeePass",
    val databaseNameChanged: Instant? = null,
    val databaseDescription: String = "",
    val databaseDescriptionChanged: Instant? = null,
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
