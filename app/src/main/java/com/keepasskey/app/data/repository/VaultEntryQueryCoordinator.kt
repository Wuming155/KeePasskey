package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.database.fieldref.FieldReferenceEngine
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
     */
    fun entriesFlow(): Flow<List<UiVaultEntry>> {
        return databaseSession.databaseFlow.map { db ->
            if (db == null) {
                emptyList()
            } else {
                db.rootGroup.allEntries().map { kdbxEntry ->
                    entryMapper.mapKdbxEntryToUi(kdbxEntry)
                }
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
     */
    fun entryFlow(id: String): Flow<UiVaultEntry?> {
        val targetUuid = parseKdbxUuidOrNull(id)
        return databaseSession.databaseFlow.map { db ->
            val entry = if (targetUuid == null) null else db?.rootGroup?.findEntry(targetUuid)
            entry?.let { entryMapper.mapKdbxEntryToUi(it) }
        }
    }

    /** 一次性快照直出全部条目（不再订阅会话流）。 */
    suspend fun allEntries(): List<KdbxEntry> {
        val db = databaseSession.databaseFlow.first() ?: return emptyList()
        return db.rootGroup.allEntries()
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
     * 解析 [rawText] 中的 KeePass 字段引用 `{REF:...}`（TASK-17）。
     * 仅在取值消费点调用（详情复制 / 自动填充下发），投影层不展开——
     * 引用指向的密码明文不得提前物化进 UI 状态流。
     *
     * [consumerField] 为**消费点面白名单**（ISSUE-P0-08）：调用方必须显式声明解析结果
     * 将进入哪个字段通道（`P`=口令通道 / 其余=非口令通道）。非口令通道命中
     * 受保护字段（取值面或检索面为 `P`）时输出掩码占位，绝不物化口令明文。
     * 条目或库会话不可用时返回 null（调用方回退原文）。
     */
    suspend fun resolveFieldReferences(
        entryId: String,
        rawText: String,
        consumerField: FieldReferenceEngine.RefField
    ): String? {
        if (!FieldReferenceEngine.containsReference(rawText)) {
            return rawText
        }
        val uuid = parseKdbxUuidOrNull(entryId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        // 条目存在性校验：引用解析仅对库内真实条目开放
        if (currentDb.rootGroup.allEntries().none { it.id == uuid }) return null
        return FieldReferenceEngine.resolve(rawText, currentDb.rootGroup, consumerField)
    }
}
