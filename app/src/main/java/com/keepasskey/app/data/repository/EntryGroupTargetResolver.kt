package com.keepasskey.app.data.repository

import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.database.file.KdbxDatabase

/**
 * 分组写入意图的**落点解析**（`ISSUE-P2-635`，纯函数，可在纯 JVM 上逐条断言）。
 *
 * 把「意图 + 当前树 → 目标父组」从写协调器里拆出来，理由与 `AttachmentMergePolicy` 相同：
 * 三态意图 + 目标校验是**可枚举的判定表**，不该埋在保存主流程里靠集成用例反推。
 *
 * 校验口径：
 * - `Root` 解析为**库根分组 id**，不是 `null`——`null` 只是「无父组」的内存表达，
 *   显式 root id 才能让「与既有父组是否相同」的判定有意义；
 * - `ToGroup` 必须可解析、在本树内，且**不在回收站子树内**（含回收站根）：
 *   移进回收站等于删除，按无效目标拒绝；
 * - `Keep` 原样沿用既有父组（新建条目为 `null` ⇒ 落树时规范化为根分组）。
 */
internal object EntryGroupTargetResolver {

    /** 解析结果：要么得到落点（`null` = 无父组，落树时归一到根），要么判定目标无效。 */
    sealed interface Resolution {
        class Resolved(val parentId: KdbxUuid?) : Resolution
        data object Invalid : Resolution
    }

    fun resolve(
        db: KdbxDatabase?,
        existingParentGroupId: KdbxUuid?,
        intent: EntryGroupIntent
    ): Resolution = when (intent) {
        EntryGroupIntent.Keep -> Resolution.Resolved(existingParentGroupId)
        EntryGroupIntent.Root -> Resolution.Resolved(db?.rootGroup?.id)
        is EntryGroupIntent.ToGroup -> resolveExplicitTarget(db, intent.groupId)
    }

    private fun resolveExplicitTarget(db: KdbxDatabase?, rawGroupId: String): Resolution {
        val uuid = parseKdbxUuidOrNull(rawGroupId)
        if (uuid == null || db == null) return Resolution.Invalid
        if (isInRecycleBinSubtree(db, uuid)) return Resolution.Invalid
        val group = db.rootGroup.findGroup(uuid) ?: return Resolution.Invalid
        return Resolution.Resolved(group.id)
    }

    /** 目标分组是否落在回收站子树内（含回收站根）：沿 `parentGroupId` 上溯，带环保护。 */
    private fun isInRecycleBinSubtree(db: KdbxDatabase, groupId: KdbxUuid): Boolean {
        val recycleBinId = db.recycleBinUuid ?: return false
        val visited = HashSet<KdbxUuid>()
        var cursor = db.rootGroup.findGroup(groupId)
        while (cursor != null && visited.add(cursor.id)) {
            if (cursor.id == recycleBinId) return true
            cursor = cursor.parentGroupId?.let { db.rootGroup.findGroup(it) }
        }
        return false
    }
}
