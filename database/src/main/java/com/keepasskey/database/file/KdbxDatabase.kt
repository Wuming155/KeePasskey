package com.keepasskey.database.file

import com.keepasskey.core.model.CustomIcon
import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxEntry
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
     * 不再仅随引用丢弃等待 GC——限界 §1.6 的解除）+ **Meta 级自定义图标池**
     * （`ISSUE-P3-471` 起，`customIcons` 的 PNG 字节与二进制池同点、同判据清零）。
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
        clearCustomIconPool(emptyList())
    }

    /**
     * 池内擦除的**身份集合判定**入口（`ISSUE-P3-258` 准入②；契约 §6.2 / §7 Step 4）。
     *
     * 以内存 `BinaryItem` 的实例身份及落盘条目的「底层 store 身份 + key」建立存活侧集合
     * （`BinaryItem.equals` 是内容相等，不可用于本判定）。完整数据库重载还会收集活动树中
     * 所有附件来源，含历史条目；然后清零本库 [binaries] 的非存活内存项，并释放本库池或树中
     * 不再被存活侧引用的事务-owned spill key。
     *
     * **禁止**把本方法退化为无存活侧参数的 `binaries.forEach { it.clear() }` 裸擦：
     * `KdbxDatabase.copy()` 会共享同一池列表（合并 / copy-on-write 常态发生），
     * 裸擦会静默清空存活库仍在引用的附件（`SECURITY_RECHECK_2026-09.md` §9.6 #3 同型）。
     * 契约用例：`KdbxBinaryPoolErasureTest`（database 侧原语与收口点）、
     * `SyncPendingTreeErasureTest`（app 侧 `eraseDiscardedDatabase` 链路）。
     *
     * 旧的非事务加载调用方仍由会话锁定 / 关闭与冷启动清扫兜底。
     */
    fun clearBinaryPool(liveBinaries: Collection<InnerHeader.BinaryItem>) {
        clearBinaryPool(liveBinaries, liveRoot = null)
    }

    /**
     * 存活侧为完整数据库时，连同根树及 history 的附件 source 一并参与 spill key 判定。
     */
    fun clearBinaryPool(liveDatabase: KdbxDatabase?) {
        clearBinaryPool(
            liveBinaries = liveDatabase?.binaries ?: emptyList(),
            liveRoot = liveDatabase?.rootGroup
        )
    }

    private fun clearBinaryPool(
        liveBinaries: Collection<InnerHeader.BinaryItem>,
        liveRoot: KdbxGroup?
    ) {
        val ownSpillItems = rootGroup.collectSpillItems()
        if (binaries.isEmpty() && ownSpillItems.isEmpty()) return
        val live = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<InnerHeader.BinaryItem, Boolean>()
        )
        live.addAll(liveBinaries)
        val liveSpillKeys = java.util.IdentityHashMap<Any, MutableSet<String>>()
        liveBinaries.forEach { item ->
            val key = item.spillKey
            val storeIdentity = item.spillStoreIdentity
            if (key != null && storeIdentity != null) {
                liveSpillKeys.getOrPut(storeIdentity) { HashSet() }.add(key)
            }
        }
        liveRoot?.collectSpillKeys(liveSpillKeys)
        for (item in binaries) {
            if (!live.contains(item)) item.clear()
        }
        val releasedSpillKeys = java.util.IdentityHashMap<Any, MutableSet<String>>()
        fun releaseIfUnreferenced(item: InnerHeader.BinaryItem) {
            val key = item.spillKey ?: return
            val storeIdentity = item.spillStoreIdentity ?: return
            if (liveSpillKeys[storeIdentity]?.contains(key) == true) return
            if (releasedSpillKeys.getOrPut(storeIdentity) { HashSet() }.add(key)) {
                item.releaseOwnedSpillKey()
            }
        }
        ownSpillItems.forEach { releaseIfUnreferenced(it) }
        binaries.forEach { releaseIfUnreferenced(it) }
    }

    private fun KdbxGroup.collectSpillKeys(into: java.util.IdentityHashMap<Any, MutableSet<String>>) {
        collectSpillItems().forEach { item ->
            val key = item.spillKey ?: return@forEach
            val storeIdentity = item.spillStoreIdentity ?: return@forEach
            into.getOrPut(storeIdentity) { HashSet() }.add(key)
        }
    }

    private fun KdbxGroup.collectSpillItems(): List<InnerHeader.BinaryItem> = mutableListOf<InnerHeader.BinaryItem>()
        .also { into -> collectSpillItems(this, into) }

    private fun collectSpillItems(group: KdbxGroup, into: MutableList<InnerHeader.BinaryItem>) {
        group.entries.forEach { entry ->
            entry.attachments.forEach { attachment ->
                (attachment.binarySource() as? InnerHeader.BinaryItem)?.let(into::add)
            }
            entry.history.forEach { history -> collectSpillItems(history, into) }
        }
        group.subgroups.forEach { subgroup -> collectSpillItems(subgroup, into) }
    }

    private fun collectSpillItems(entry: KdbxEntry, into: MutableList<InnerHeader.BinaryItem>) {
        entry.attachments.forEach { attachment ->
            (attachment.binarySource() as? InnerHeader.BinaryItem)?.let(into::add)
        }
        entry.history.forEach { history -> collectSpillItems(history, into) }
    }

    /**
     * 图标池内擦除的**身份集合判定**入口（`ISSUE-P3-471`；判据与 [clearBinaryPool] 逐条对齐）。
     *
     * 以 [liveIcons] 为存活侧收集 `CustomIcon` 的**实例身份**（引用相等，IdentityHashMap 支撑——
     * `CustomIcon.equals` 是内容相等，含全部字段，不可用于本判定），只清零本库 [customIcons] 中
     * **不被存活侧以同一实例引用**的条目字节；[liveIcons] 为空即全量擦除。
     *
     * **禁止**把本方法退化为无存活侧参数的 `customIcons.forEach { it.data.fill(0) }` 裸擦：
     * `KdbxMerger.mergeCustomIcons` 对「单侧独有 / 胜出的图标」**复用原 `CustomIcon` 实例**
     * （`sync/src/main/java/com/keepasskey/sync/merge/KdbxMerger.kt`），故合并 / 漂移合并采用后的
     * 会话库与「待丢弃的解析树」会同时可达同一实例（`SECURITY_RECHECK_2026-09.md` §9.6 #19 同型）；
     * 裸擦会静默清空活动库的图标字节（并在下次保存时把全零字节写回库文件）。
     * 契约用例：`KdbxCustomIconPoolErasureTest`（database 侧原语与收口点）、
     * `SyncPendingTreeErasureTest`（app 侧 `eraseDiscardedDatabase` 链路）。
     */
    fun clearCustomIconPool(liveIcons: Collection<CustomIcon>) {
        if (customIcons.isEmpty()) return
        val live = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<CustomIcon, Boolean>()
        )
        live.addAll(liveIcons)
        for (icon in customIcons) {
            if (!live.contains(icon)) icon.data.fill(0)
        }
    }
}
