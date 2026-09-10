package com.keepasskey.sync.merge

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry

/**
 * 冲突解决策略（sync 域模型，ISSUE-P3-03 43a）。
 *
 * 依赖倒置：本枚举属 `sync` 领域语言，app 层（设置页 `ConflictResolution`）负责映射，
 * `sync` 模块不反向依赖 app 层 UI 类型。
 */
enum class SyncConflictStrategy {
    /** 字段级三方自动合并；出现同字段分歧时转用户决策（默认） */
    AUTO_MERGE,

    /** 每次询问：只要条目被双方各自修改过，即交用户逐条决定保留哪一方 */
    PROMPT_USER,

    /** 以云端为准：冲突时直接采用远端版本，本地未同步修改被放弃 */
    KEEP_REMOTE,

    /** 以本地为准：冲突时本地版本强制覆盖云端 */
    KEEP_LOCAL
}

/**
 * 冲突处置分支（纯数据，供编排器 `when` 穷尽匹配）。
 */
sealed class ConflictDisposition {
    /** 执行三方自动合并 */
    data object AutoMerge : ConflictDisposition()

    /** 扩充决策清单后交用户决策 */
    data object PromptUser : ConflictDisposition()

    /** 采用远端内容覆盖本地会话 */
    data object TakeRemote : ConflictDisposition()

    /** 本地内容强制覆盖远端 */
    data object TakeLocal : ConflictDisposition()
}

/**
 * 策略 → 处置分支的纯决策函数（无 IO、无副作用，可直接单测）。
 */
object ConflictStrategyPolicy {

    fun dispositionOf(strategy: SyncConflictStrategy): ConflictDisposition = when (strategy) {
        SyncConflictStrategy.AUTO_MERGE -> ConflictDisposition.AutoMerge
        SyncConflictStrategy.PROMPT_USER -> ConflictDisposition.PromptUser
        SyncConflictStrategy.KEEP_REMOTE -> ConflictDisposition.TakeRemote
        SyncConflictStrategy.KEEP_LOCAL -> ConflictDisposition.TakeLocal
    }

    /**
     * 是否需要在进入冲突处置时跳过三方合并（[ConflictDisposition.TakeRemote] /
     * [ConflictDisposition.TakeLocal] 为单方强制策略，不做合并）。
     */
    fun skipsMerge(strategy: SyncConflictStrategy): Boolean = when (strategy) {
        SyncConflictStrategy.KEEP_REMOTE, SyncConflictStrategy.KEEP_LOCAL -> true
        SyncConflictStrategy.AUTO_MERGE, SyncConflictStrategy.PROMPT_USER -> false
    }
}

/**
 * 「每次询问」策略的决策清单扩充（ISSUE-P3-03 43a）。
 *
 * 三方合并只把**同字段分歧**列为冲突；`AUTO_MERGE` 下双方修改不同字段的条目会被静默合并。
 * `PROMPT_USER` 的语义是「由你决定保留哪一方」，因此把**双方各自修改过的全部条目**
 * 纳入决策清单（合并冲突清单的超集），使该策略与自动合并在行为上真实可分。
 *
 * base 可信性裁决：base 缺失（首次同步）或已被本地工作副本污染时，`isEntryModified`
 * 对任何条目都返回「已修改」，按本规则扩充会把全库条目变成询问项。故 base 不可信时
 * **不扩充**，退回合并引擎给出的冲突清单（宁可少问，不可把整库变成决策项）。
 */
object BothModifiedEntryCollector {

    fun collect(
        trustedBase: KdbxDatabaseLite?,
        local: KdbxDatabaseLite,
        remote: KdbxDatabaseLite,
        alreadyConflicted: List<ConflictedEntryPair>
    ): List<ConflictedEntryPair> {
        // base 不可信（缺失 / 被本地副本污染）时不扩充：详见类注释的可信性裁决
        if (trustedBase == null) return alreadyConflicted

        val baseEntries = trustedBase.rootGroup.allEntries().associateBy { it.id }
        val localEntries = local.rootGroup.allEntries().associateBy { it.id }
        val remoteEntries = remote.rootGroup.allEntries().associateBy { it.id }
        val known = alreadyConflicted.mapTo(mutableSetOf()) { it.entryId }

        val extra = mutableListOf<ConflictedEntryPair>()
        for ((id, localEntry) in localEntries) {
            // 与 KdbxMerger 的条目标识约定保持一致（toHexString），决策键必须同源
            val entryId = id.toHexString()
            if (entryId in known) continue
            val remoteEntry = remoteEntries[id] ?: continue
            val baseEntry = baseEntries[id]
            val bothModified = KdbxMerger.isEntryModified(baseEntry, localEntry) &&
                KdbxMerger.isEntryModified(baseEntry, remoteEntry)
            if (!bothModified) continue
            extra += ConflictedEntryPair(
                entryId = entryId,
                localEntry = localEntry,
                remoteEntry = remoteEntry,
                modifiedFields = differingStandardFields(localEntry, remoteEntry)
            )
        }
        return if (extra.isEmpty()) alreadyConflicted else alreadyConflicted + extra
    }

    /**
     * 双方标准字段差异键（与冲突界面 `ConflictedField` 的字段键同源，
     * 均为 [KdbxConstants.Fields] 标准键），空列表表示差异仅在非标准字段。
     */
    private fun differingStandardFields(local: KdbxEntry, remote: KdbxEntry): List<String> = buildList {
        if (local.title != remote.title) add(KdbxConstants.Fields.TITLE)
        if (local.userName != remote.userName) add(KdbxConstants.Fields.USER_NAME)
        if (local.password != remote.password) add(KdbxConstants.Fields.PASSWORD)
        if (local.url != remote.url) add(KdbxConstants.Fields.URL)
        if (local.notes != remote.notes) add(KdbxConstants.Fields.NOTES)
    }
}
