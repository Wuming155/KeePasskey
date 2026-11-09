package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.security.ProtectedStringReclamation
import com.keepasskey.database.fieldref.FieldReferenceEngine
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * 条目查询与投影协调器（ISSUE-P3-305 自 `RealVaultRepository` 按职责拆出，逐行搬运）。
 *
 * 职责单一：条目的**只读**读取面——整库 / 单条的 UI 投影流、一次性整份 / 单条
 * `KdbxEntry` 快照，以及 `{REF:...}` 字段引用的消费点解析。
 *
 * 调度边界沿用 ISSUE-P3-154：投影（逐字段解密 + 时间格式化）经注入的
 * [projectionDispatcher]（生产 `Dispatchers.Default`）执行，**不落在收集上下文**；
 * 单条投影走 `KdbxGroup.findEntry`（深度优先短路），不物化整库（ISSUE-P3-148/149）。
 *
 * ## `ENG-20261028-11`：读租约覆盖整个使用期
 *
 * 本类是本仓「共享受保护实例的回收」与「消费面读取」之间唯一的协调点，故一律经
 * [ProtectedStringReclamation] 登记读租约：
 * - 两个投影流的**投影体**整体处于租约内——投影期间会话替换整树，下线实例延到投影结束才擦除；
 * - 需要把快照交回调用方的读口（[allEntries] / [usableEntries] / [entryById]）另设**作用域读口**
 *   （[withAllEntries] / [withUsableEntries] / [withEntryById]），把「取回快照 → 读它的字段」的
 *   整个使用期放进同一个租约；裸读口保留给既有调用点与测试替身，本身不含租约；
 * - [resolveFieldReferences] 的解析全程在租约内（返回值是一次性 String，不存在租约越界问题）。
 *
 * 如实声明：租约把竞争窗口从「整棵树的消费期间」收窄为**未按协议登记的迟到读取**
 * （快照已分发、投影体 / 读口尚未进入）。这类读取仍由 [com.keepasskey.core.security.ProtectedString]
 * 的 fail-fast 判据与展示面降级兜底，绝不返回垃圾明文。
 */
internal class VaultEntryQueryCoordinator(
    private val databaseSession: DatabaseSession,
    private val entryMapper: VaultEntryMapper,
    private val projectionDispatcher: CoroutineDispatcher
) {

    /**
     * 全部条目的 UI 投影流。
     *
     * ISSUE-P3-154：投影（`KdbxEntry` → `UiVaultEntry`，逐字段解密 + 时间格式化）经
     * [projectionDispatcher] 执行，**不落在收集上下文**——列表页 / 自动填充 / 子库各自的
     * 收集上下文（`viewModelScope`、服务协程）一律不再承担这段 CPU 工作。
     *
     * 语义零变更：上游 `databaseFlow` 本就是 `StateFlow`（已合流），`flowOn` 只搬移执行线程。
     * `ENG-20261028-11`：每次快照的投影体在一条读租约内完成（§473 崩溃面即在此窗口）。
     */
    fun entriesFlow(): Flow<List<UiVaultEntry>> {
        return databaseSession.databaseFlow.map { _ ->
            // ENG-20261028-11：消除先取旧 db 再登记的交接窗口——先登记读租约，
            // 并在租约保护下重新读取当前会话状态引用，确保投影期间引用的受保护实例不会在读取前被擦除。
            val lease = ProtectedStringReclamation.acquireReadLease()
            try {
                val db = databaseSession.databaseFlow.value
                if (db == null) {
                    emptyList()
                } else {
                    // ISSUE-P2-341：每快照算一次回收站子树（O(组数)），逐条标注 isRecycled。
                    // **只标记不过滤** —— 列表页进回收站仍要看得见、能还原；过滤由各凭据供给面负责。
                    val binGroupIds = recycleBinGroupIdsOf(db)
                    db.rootGroup.allEntries().map { kdbxEntry ->
                        entryMapper.mapKdbxEntryToUi(kdbxEntry).copy(
                            isRecycled = kdbxEntry.parentGroupId in binGroupIds
                        )
                    }
                }
            } finally {
                lease.close()
            }
        }.flowOn(projectionDispatcher)
    }

    /**
     * 单条条目投影流。
     *
     * ISSUE-P3-149：**不再以「整库投影 + `find`」实现**——原实现为一个条目付出
     * O(N × 字段数) 的全库映射（详情页一次组合挂了 3 条这样的链），本实现改为
     * `KdbxGroup.findEntry`（深度优先短路）只映射命中的那一条。
     *
     * 语义与旧实现等价（同为深度优先首命中，未命中返回 null）；差异仅在容错面上更宽：
     * 传入**小写** hex id 时旧实现因与 `toHexString()`（大写）字符串不等而落空，
     * 本实现按 UUID 字节比较可正常命中（调用方恒传大写，属放宽而非行为变更）。
     * `ENG-20261028-11`：单条投影同样在一条读租约内，且在租约内重新读取当前会话状态引用。
     */
    fun entryFlow(id: String): Flow<UiVaultEntry?> {
        val targetUuid = parseKdbxUuidOrNull(id)
        return databaseSession.databaseFlow.map { _ ->
            // ENG-20261028-11：单条投影同样在一条读租约内，且在租约内重新读取当前会话状态引用。
            val lease = ProtectedStringReclamation.acquireReadLease()
            try {
                val db = databaseSession.databaseFlow.value
                val entry = if (targetUuid == null) null else db?.rootGroup?.findEntry(targetUuid)
                entry?.let { entryMapper.mapKdbxEntryToUi(it) }?.copy(
                    // ISSUE-P2-341：与 entriesFlow 同口径（详情页据此可如实呈现"该条目已在回收站内"）
                    isRecycled = db?.let { entry.parentGroupId in recycleBinGroupIdsOf(it) } == true
                )
            } finally {
                lease.close()
            }
        }.flowOn(projectionDispatcher)
    }

    /** 一次性快照直出全部条目（不再订阅会话流）。**含回收站子树**（`{REF:}` 展开与回收站内详情要靠它）。 */
    suspend fun allEntries(): List<KdbxEntry> {
        val db = databaseSession.databaseFlow.first() ?: return emptyList()
        return db.rootGroup.allEntries()
    }

    /**
     * 一次性快照直出**可用**条目（排除回收站子树，含其任意深度子组）。
     *
     * `ISSUE-P2-341` 立规的调用点契约：**凡"把凭据交给用户或交给系统"的路径一律走本方法**——
     * 验证器列表、CM 通行密钥候选、autofill 候选、断言与填充执行侧。
     * 与之相对，[allEntries] 保留整树语义：同步合并、`{REF:...}` 引用展开、回收站内的详情与还原
     * 都必须能看见已删条目，走错读口会**直接弄坏这些功能**。
     */
    suspend fun usableEntries(): List<KdbxEntry> {
        val db = databaseSession.databaseFlow.first() ?: return emptyList()
        return usableEntriesOf(db)
    }

    /**
     * ISSUE-P3-148：单条查询走 [com.keepasskey.core.model.KdbxGroup.findEntry]（深度优先短路），
     * **不**物化整份条目列表——自动填充确认路径每次只处理一条，付不起与库规模成正比的装载成本。
     */
    suspend fun entryById(entryId: String): KdbxEntry? {
        val uuid = parseKdbxUuidOrNull(entryId) ?: return null
        val db = databaseSession.databaseFlow.first() ?: return null
        return db.rootGroup.findEntry(uuid)
    }

    /**
     * [allEntries] 的租约作用域读口：快照在 [block] 内受读租约保护（块结束即释放）。
     * 调用方**必须**在块内完成对快照的读取（跨块持有实例等于无租约）。
     */
    suspend fun <R> withAllEntries(block: suspend (List<KdbxEntry>) -> R): R {
        val lease = ProtectedStringReclamation.acquireReadLease()
        try {
            val db = databaseSession.databaseFlow.value
            return block(db?.rootGroup?.allEntries() ?: emptyList())
        } finally {
            lease.close()
        }
    }

    /** [usableEntries] 的租约作用域读口（排除回收站子树；块内完成读取）。 */
    suspend fun <R> withUsableEntries(block: suspend (List<KdbxEntry>) -> R): R {
        val lease = ProtectedStringReclamation.acquireReadLease()
        try {
            val db = databaseSession.databaseFlow.value
            return block(if (db == null) emptyList() else usableEntriesOf(db))
        } finally {
            lease.close()
        }
    }

    /** [entryById] 的租约作用域读口（块内完成读取）。 */
    suspend fun <R> withEntryById(entryId: String, block: suspend (KdbxEntry?) -> R): R {
        val uuid = parseKdbxUuidOrNull(entryId)
        if (uuid == null) {
            return block(null)
        }
        val lease = ProtectedStringReclamation.acquireReadLease()
        try {
            val db = databaseSession.databaseFlow.value
            return block(if (db == null) null else db.rootGroup.findEntry(uuid))
        } finally {
            lease.close()
        }
    }

    /**
     * 解析 [rawText] 中的 KeePass 字段引用 `{REF:...}`（TASK-17）。
     * 仅在取值消费点调用（详情复制 / 自动填充下发），投影层不展开——
     * 引用指向的密码明文不得提前物化进 UI 状态流。
     *
     * [consumerField] 为**消费点面白名单**（ISSUE-P0-08）：调用方必须显式声明解析结果
     * 将进入哪个字段通道（`P`=口令通道 / 其余=非口令通道）。非口令通道命中
     * 受保护字段（取值面或检索面为 `P`）时输出掩码占位，绝不物化口令明文。
     * 条目或库会话不可用时返回 null（调用方回退原文）。
     *
     * `ENG-20261028-11`：定位与解析整体在一条读租约内完成——引用指向的条目字段（标题 / 备注 /
     * 口令）同样是被替换下线的候选，解析期间不得被回收。
     */
    suspend fun resolveFieldReferences(
        entryId: String,
        rawText: String,
        consumerField: FieldReferenceEngine.RefField
    ): String? {
        if (!FieldReferenceEngine.containsReference(rawText)) {
            return rawText
        }
        // ENG-20261028-11：定位与解析整体在一条读租约内完成（显式 try/finally，避免 inline 与 suspend 叠加）
        val lease = ProtectedStringReclamation.acquireReadLease()
        try {
            val uuid = parseKdbxUuidOrNull(entryId) ?: return null
            val currentDb = databaseSession.databaseFlow.value ?: return null
            // 条目存在性校验：引用解析仅对库内真实条目开放
            if (currentDb.rootGroup.allEntries().none { it.id == uuid }) return null
            return FieldReferenceEngine.resolve(rawText, currentDb.rootGroup, consumerField)
        } finally {
            lease.close()
        }
    }

    /** 可用条目（排除回收站子树）的唯一取数实现：裸读口与租约读口共用，避免两份过滤逻辑漂移。 */
    private fun usableEntriesOf(db: KdbxDatabase): List<KdbxEntry> {
        val binGroupIds = recycleBinGroupIdsOf(db)
        return db.rootGroup.allEntries().filterNot { it.parentGroupId in binGroupIds }
    }
}
