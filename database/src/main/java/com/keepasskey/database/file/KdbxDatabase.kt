package com.keepasskey.database.file

import com.keepasskey.core.model.CustomIcon
import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.MemoryProtectionConfig
import java.time.Instant

/**
 * 内存中的已打开 KDBX 数据库实体。
 * 包含外层头、元数据、根分组、二进制池、回收站设置与删除墓碑等完整 KDBX4 规范字段。
 */
data class KdbxDatabase(
    val header: KdbxHeader,
    val databaseName: String = "KeePass",
    val databaseDescription: String = "",
    val rootGroup: KdbxGroup,
    val binaries: List<InnerHeader.BinaryItem> = emptyList(),
    val recycleBinUuid: KdbxUuid? = null,
    val recycleBinEnabled: Boolean = true,
    val recycleBinChanged: Instant? = null,
    val entryTemplatesGroup: KdbxUuid? = null,
    val entryTemplatesGroupChanged: Instant? = null,
    val customIcons: List<CustomIcon> = emptyList(),
    val deletedObjects: List<DeletedObject> = emptyList(),
    val memoryProtection: MemoryProtectionConfig = MemoryProtectionConfig(),
    val customData: Map<String, String> = emptyMap(),
    val historyMaxItems: Int = 10,
    val historyMaxSize: Long = 6 * 1024 * 1024L,
    val lastSelectedGroup: KdbxUuid? = null,
    val lastTopVisibleGroup: KdbxUuid? = null,
    val generator: String = "KeePasskey",
    val databaseNameChanged: Instant? = null,
    val databaseDescriptionChanged: Instant? = null
) {
    fun clearSensitiveData() {
        rootGroup.clearSensitiveData()
    }
}
