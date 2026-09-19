package com.keepasskey.app.ui.model

import com.keepasskey.core.log.AppLog
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.database.fieldref.FieldReferenceEngine
import kotlin.coroutines.cancellation.CancellationException

/**
 * 条目 Notes / URL 的展示文案投影（ISSUE-P3-02 / TASK-49，承接 TASK-17 残余）。
 * 引用已在状态层展开：仅公开字段，受保护字段为掩码占位——本模型不含任何受保护值。
 */
data class EntryTextDisplay(
    val notes: String,
    val url: String
) {
    companion object {
        /** 无展示装饰时的语义空值（调用方回退条目原文，不用于覆盖） */
        val EMPTY = EntryTextDisplay(notes = "", url = "")
    }
}

/**
 * Notes / URL 展示侧的字段引用解析通道（ISSUE-P3-02）。
 *
 * 为什么不在投影层展开：M1 整改约束「密码明文不得随投影物化进 UI 状态流」。
 * 本通道因此只走引擎的**展示模式**（[FieldReferenceEngine.resolveForDisplay]）：
 * 公开字段（T/U/A/N/I）正常展开，受保护字段（P，无论取值面还是检索面）输出掩码占位，
 * 任意递归深度都不物化明文；循环引用与超深引用由引擎 [FieldReferenceEngine.MAX_DEPTH] 兜底。
 *
 * 引用检索只需要扁平的条目集合，故以合成根分组承载 [loadEntries] 的结果——
 * 与真实根分组 `allEntries()` 的检索语义等价（引擎只经 `root.allEntries()` 检索）。
 */
class EntryReferenceDisplayResolver(
    private val loadEntries: suspend () -> List<KdbxEntry>,
    private val protectedPlaceholder: String = FieldReferenceEngine.PROTECTED_PLACEHOLDER
) {

    /** 单段文本的展示解析；不含引用时零开销直返原文 */
    suspend fun display(rawText: String): String {
        if (!FieldReferenceEngine.containsReference(rawText)) return rawText
        val entries = loadEntries()
        if (entries.isEmpty()) return rawText
        return displaySafely(rawText, rootOf(entries))
    }

    /**
     * 单条目 Notes + URL 展示解析（详情页）：两段文本共用一次条目快照，
     * 均不含引用时零开销直返原文（含受保护字段时输出掩码，不物化明文）。
     */
    suspend fun display(notes: String, url: String): EntryTextDisplay {
        val notesHasReference = FieldReferenceEngine.containsReference(notes)
        val urlHasReference = FieldReferenceEngine.containsReference(url)
        if (!notesHasReference && !urlHasReference) return EntryTextDisplay(notes = notes, url = url)
        val snapshot = loadEntries()
        if (snapshot.isEmpty()) return EntryTextDisplay(notes = notes, url = url)
        val root = rootOf(snapshot)
        return EntryTextDisplay(notes = displaySafely(notes, root), url = displaySafely(url, root))
    }

    /**
     * 批量解析（列表页）：仅对确实含引用的条目解析，且整批共用一次条目快照；
     * 返回值只包含需要替换展示文案的条目 id，调用方按缺失回退条目原文。
     *
     * ISSUE-P2-200 AC③：逐条目、逐字段的失败**归一为该字段回退原文**（见 [displaySafely]），
     * 单条坏数据不会拖垮整批渲染——列表页是「每次 emission 全库展开」的热路径，
     * 任一未捕获的 `Throwable` 都会经 `SupervisorJob` 落到主线程 uncaughtExceptionHandler
     * 变成进程崩溃（且每次开库复现）。
     */
    suspend fun present(entries: List<UiVaultEntry>): Map<String, EntryTextDisplay> {
        val referencing = entries.filter { entry ->
            FieldReferenceEngine.containsReference(entry.notes) ||
                FieldReferenceEngine.containsReference(entry.url)
        }
        if (referencing.isEmpty()) return emptyMap()
        val snapshot = loadEntries()
        if (snapshot.isEmpty()) return emptyMap()
        val root = rootOf(snapshot)
        return referencing.associate { entry ->
            entry.id to EntryTextDisplay(
                notes = displaySafely(entry.notes, root),
                url = displaySafely(entry.url, root)
            )
        }
    }

    /**
     * 展示解析 + **Throwable 兜底归一**（ISSUE-P2-200 AC③）。
     *
     * 解析失败（含 [Error] 级：递归过深、内存压力等）一律回退该字段原文——原文本身就是
     * 引用占位符文本，既不泄露额外信息也不丢用户数据，同时阻断「列表渲染即崩」的准持久 DoS。
     * 协程取消信号必须原样上抛，否则会吞掉结构化并发的取消（`runCatching` 的经典陷阱）。
     */
    private fun displaySafely(rawText: String, root: KdbxGroup): String {
        if (!FieldReferenceEngine.containsReference(rawText)) return rawText
        return try {
            FieldReferenceEngine.resolveForDisplay(rawText, root, protectedPlaceholder)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            AppLog.w(TAG, "字段引用展示解析失败，该字段回退原文", t)
            rawText
        }
    }

    private fun rootOf(entries: List<KdbxEntry>): KdbxGroup =
        KdbxGroup(name = REFERENCE_ROOT_NAME, entries = entries)

    private companion object {
        const val TAG = "EntryRefDisplayResolver"

        /** 合成检索根的名称（引擎不读取分组名，仅为可读性） */
        const val REFERENCE_ROOT_NAME = "reference-search-root"
    }
}
