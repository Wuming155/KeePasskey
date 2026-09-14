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
    val databaseDescriptionChanged: Instant? = null,
    // 官方 Meta 字段（KeePass 2.61.1 PwDatabase 初始值，P1-8 补齐）
    val defaultUserName: String = "",
    val defaultUserNameChanged: Instant? = null,
    val maintenanceHistoryDays: Int = 365,
    val color: String = "",
    val masterKeyChanged: Instant? = null,
    val masterKeyChangeRec: Int = -1,
    val masterKeyChangeForce: Int = -1,
    val settingsChanged: Instant? = null,
    /**
     * 仅强制修改一次主密钥（官方 KDBX 4.1 `<MasterKeyChangeForceOnce>`）。
     *
     * 注意：本字段已就位，但 `KdbxFile.buildDatabase` 的透传赋值属跨文件协调项
     * （`database/src/main/java/com/keepasskey/database/file/KdbxFile.kt`），
     * 未接线前经 `KdbxFile` 往返会保持缺省值。
     */
    val masterKeyChangeForceOnce: Boolean = false,
    /**
     * Meta 级 `<CustomData><Item>` 的时间戳（官方 KDBX 4.1 `TCustomDataWithTimes`），
     * 键集合与 [customData] 对齐；[customData] 的 `Map<String, String>` 类型保持不变以兼容 app/sync 消费方。
     *
     * 注意：与 [masterKeyChangeForceOnce] 相同，`KdbxFile.buildDatabase` 的透传赋值未在本批次接线
     * （跨文件协调项）。
     */
    val customDataTimes: Map<String, Instant> = emptyMap()
) {
    /**
     * 擦除库内全部敏感驻留：明文条目树 + 外层头部的 KDF secret `K`。
     *
     * ISSUE-P2-60（审计 RUST-06）：`header.kdfParameters`（Argon2）的 `secretKey`
     * 原先全仓无清零点，会话锁定 / 关闭后仍以普通 `ByteArray` 滞留至 GC。
     * 本方法即其**统一收口**——`DatabaseSession.lock()` / `close()` / 换库前置释放
     * 与子库只读投影均经此处触发，擦除时机契约见 `KdfParameters.clearSensitive` KDoc
     * （仅限会话终止路径：此后 `database = null`，不可能再以该头部发起保存派生）。
     */
    fun clearSensitiveData() {
        rootGroup.clearSensitiveData()
        header.kdfParameters.clearSensitive()
    }
}
