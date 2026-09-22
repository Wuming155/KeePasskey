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
     * 擦除库内全部敏感驻留：明文条目树 + 外层头部 KDF secret `K` + **内层二进制池**
     * （`ISSUE-P3-258` / 契约 Step 4 起，池内 ≤ 落盘阈值的附件明文一并就地清零，
     * 不再仅随引用丢弃等待 GC——限界 §1.6 的解除）。
     *
     * ISSUE-P2-60（审计 RUST-06）：`header.kdfParameters`（Argon2）的 `secretKey`
     * 原先全仓无清零点，会话锁定 / 关闭后仍以普通 `ByteArray` 滞留至 GC。
     * 本方法即其**统一收口**——`DatabaseSession.lock()` / `close()` / 换库前置释放
     * 与子库只读投影均经此处触发，擦除时机契约见 `KdfParameters.clearSensitive` KDoc
     * （仅限会话终止路径：此后 `database = null`，不可能再以该头部发起保存派生）。
     *
     * **池擦除的存活侧 = ∅（准入①复核结论，见 `ISSUE-P3-258`）**：本方法的全部生产调用点
     * 均满足「无跨越本调用存活的池别名」——
     * ① 会话终止三事件（lock / close / 换库前置）里仍在场的 `lastSyncedDb` / `pendingLocalDb`
     *    别名与本库为**同一实例（或 `copy` 共享同一池列表的实例）**，随本事件在同一临界区内
     *    先后置空，属被本擦除**终结**的别名而非存活侧；异实例别名（`pendingRemoteDb`）持有
     *    **不相交**的独立解析池，不经本方法擦除（其收口在 `eraseDiscardedDatabase`）；
     * ② 丢弃路径（`wipeDiscarded` / 缓存快照比较的 `finally` / 子库投影）的前置条件即
     *    「无任何存活别名」（各调用点 KDoc 已声明）。
     * 可能存在存活别名的收口点**不得**调用本方法，必须走 [clearBinaryPool] 的身份集合判定
     * （`SyncConflictController.eraseDiscardedDatabase` 即此形态）。
     */
    fun clearSensitiveData() {
        rootGroup.clearSensitiveData()
        header.kdfParameters.clearSensitive()
        clearBinaryPool(emptyList())
    }

    /**
     * 池内擦除的**身份集合判定**入口（`ISSUE-P3-258` 准入②；契约 §6.2 / §7 Step 4）。
     *
     * 以 [liveBinaries] 为存活侧收集 `BinaryItem` 的**实例身份**（引用相等，IdentityHashMap
     * 支撑——`BinaryItem.equals` 是内容相等，不可用于本判定），只清零本库 [binaries] 中
     * **不被存活侧以同一实例引用**的条目；[liveBinaries] 为空即全量擦除。
     *
     * **禁止**把本方法退化为无存活侧参数的 `binaries.forEach { it.clear() }` 裸擦：
     * `KdbxDatabase.copy()` 会共享同一池列表（合并 / copy-on-write 常态发生），
     * 裸擦会静默清空存活库仍在引用的附件（`SECURITY_RECHECK_2026-09.md` §9.6 #3 同型）。
     * 契约用例：`KdbxBinaryPoolErasureTest`（database 侧原语与收口点）、
     * `SyncPendingTreeErasureTest`（app 侧 `eraseDiscardedDatabase` 链路）。
     *
     * 落盘条目的清零为 no-op（字节归 `BinaryStore`，随会话终止统一收口）。
     */
    fun clearBinaryPool(liveBinaries: Collection<InnerHeader.BinaryItem>) {
        if (binaries.isEmpty()) return
        val live = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<InnerHeader.BinaryItem, Boolean>()
        )
        live.addAll(liveBinaries)
        for (item in binaries) {
            if (!live.contains(item)) item.clear()
        }
    }
}
