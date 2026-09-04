package com.keepasskey.sync.merge

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.security.ProtectedString

/**
 * 条目冲突合并决策
 */
enum class ConflictResolutionChoice {
    KEEP_LOCAL,
    KEEP_REMOTE,
    DUPLICATE_BOTH
}

/**
 * 差异检测条目模型
 */
data class ConflictedEntryPair(
    val entryId: String,
    val localEntry: KdbxEntry,
    val remoteEntry: KdbxEntry,
    val modifiedFields: List<String>
)

/**
 * KDBX 三方同步合并引擎。
 * 针对云端多人并发协同或离线多端编辑场景：
 * 1. 快速计算两端条目增删；
 * 2. 依据条目 LastModificationTime（最后修改时间戳）自动合并无冲突条目；
 * 3. 对同时被修改的条目精确识别差异字段，生成待审冲突清单供用户决策；
 * 4. 支持复制副本 (DUPLICATE_BOTH) 或字段级覆盖合并。
 */
object KdbxMerger {

    /**
     * 比较本地数据库条目集合与云端远端条目集合
     * @return Pair<已自动合并条目列表, 存在冲突的条目对列表>
     */
    fun detectConflictsAndMergeAuto(
        localEntries: List<KdbxEntry>,
        remoteEntries: List<KdbxEntry>,
        lastSyncTimestamp: Long
    ): Pair<List<KdbxEntry>, List<ConflictedEntryPair>> {
        val localMap = localEntries.associateBy { it.id.toHexString() }
        val remoteMap = remoteEntries.associateBy { it.id.toHexString() }

        val allUuids = (localMap.keys + remoteMap.keys).toSet()
        val mergedList = mutableListOf<KdbxEntry>()
        val conflicts = mutableListOf<ConflictedEntryPair>()

        for (uuid in allUuids) {
            val local = localMap[uuid]
            val remote = remoteMap[uuid]

            when {
                // 仅本地存在（本地新增）
                local != null && remote == null -> {
                    mergedList.add(local)
                }
                // 仅远端存在（远端新增）
                local == null && remote != null -> {
                    mergedList.add(remote)
                }
                // 两端均存在：检查两端最后修改时间
                local != null && remote != null -> {
                    val localMod = local.times.lastModificationTime?.toEpochMilli() ?: 0L
                    val remoteMod = remote.times.lastModificationTime?.toEpochMilli() ?: 0L

                    val isLocalChanged = localMod > lastSyncTimestamp
                    val isRemoteChanged = remoteMod > lastSyncTimestamp

                    if (isLocalChanged && isRemoteChanged && !areEntriesIdentical(local, remote)) {
                        // 两端在上次同步后均发生编辑，且内容不一致 -> 触发冲突
                        val diffFields = findDifferentFields(local, remote)
                        conflicts.add(
                            ConflictedEntryPair(
                                entryId = uuid,
                                localEntry = local,
                                remoteEntry = remote,
                                modifiedFields = diffFields
                            )
                        )
                    } else if (remoteMod > localMod) {
                        // 远端更新，采纳远端
                        mergedList.add(remote)
                    } else {
                        // 本地更新或时间一致，采纳本地
                        mergedList.add(local)
                    }
                }
            }
        }

        return Pair(mergedList, conflicts)
    }

    /**
     * 根据用户在 ConflictResolutionScreen 中的选择解决冲突
     */
    fun resolveConflict(
        pair: ConflictedEntryPair,
        choice: ConflictResolutionChoice
    ): List<KdbxEntry> {
        return when (choice) {
            ConflictResolutionChoice.KEEP_LOCAL -> listOf(pair.localEntry)
            ConflictResolutionChoice.KEEP_REMOTE -> listOf(pair.remoteEntry)
            ConflictResolutionChoice.DUPLICATE_BOTH -> {
                val remoteDuplicate = pair.remoteEntry.withField(
                    KdbxConstants.Fields.TITLE,
                    ProtectedString("${pair.remoteEntry.title} (云端冲突副本)", isProtected = false)
                )
                listOf(pair.localEntry, remoteDuplicate)
            }
        }
    }

    private fun areEntriesIdentical(a: KdbxEntry, b: KdbxEntry): Boolean {
        return a.title == b.title &&
                a.userName == b.userName &&
                a.password?.readString() == b.password?.readString() &&
                a.url == b.url &&
                a.notes == b.notes
    }

    private fun findDifferentFields(a: KdbxEntry, b: KdbxEntry): List<String> {
        val diffs = mutableListOf<String>()
        if (a.title != b.title) diffs.add("标题 (Title)")
        if (a.userName != b.userName) diffs.add("用户名 (Username)")
        if (a.password?.readString() != b.password?.readString()) diffs.add("密码 (Password)")
        if (a.url != b.url) diffs.add("网址 (URL)")
        if (a.notes != b.notes) diffs.add("备注 (Notes)")
        return diffs
    }
}
