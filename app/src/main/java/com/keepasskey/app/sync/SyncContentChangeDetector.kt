package com.keepasskey.app.sync

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.database.file.KdbxDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地会话库「是否存在未同步内容变更」的判定（ISSUE-P3-25 拆分自 `SyncCoordinator`，纯搬运）。
 *
 * 基线来源：[SyncSessionState.lastSyncedDb]（进程内存基线），缺失时回落解析缓存快照；
 * 解析能力复用 [SyncDatabaseCodec]（与拆分前 `SyncCoordinator.parseKdbxBytes` 同一实现）。
 * 比较判据与拆分前逐字段一致（C1/F5/P1-8 既有整改语义不变）。
 */
@Singleton
class SyncContentChangeDetector @Inject constructor(
    private val codec: SyncDatabaseCodec,
    private val session: SyncSessionState
) {

    /**
     * 判定本地会话库是否存在未同步的内容变更（F5 修复）。
     *
     * - 进程内存基线（lastSyncedDb）可用时按全字段内容比较；
     * - 冷启动后内存基线缺失时，绝不能直接判定「有变化」并重序列化覆盖缓存——
     *   KDBX4 随机 IV 使重序列化字节必然漂移，刷新 version 后引擎将把「无修改」
     *   误判为「本地赢」，触发无意义重传并前移远端 ETag，成为多设备协同的噪音源。
     *   正确做法：把缓存快照解析为数据库后做内容级比较；
     * - 缓存解析失败（损坏）按「有变化」保守处理：以会话库重建缓存——
     *   会话库即本地真相，重建内容不会偏离用户数据。
     */
    suspend fun resolveLocalContentChanged(
        currentDb: KdbxDatabase,
        cachedSnapshotBytes: ByteArray?
    ): Boolean {
        val reference = session.lastSyncedDb
        if (reference != null) {
            return hasDatabaseContentChanged(currentDb, reference)
        }
        if (cachedSnapshotBytes != null) {
            val cachedDb = codec.parseKdbxBytes(cachedSnapshotBytes) ?: return true
            return hasDatabaseContentChanged(currentDb, cachedDb)
        }
        return true
    }

    /**
     * 全字段递归内容比较（C1 整改）。
     * 原实现仅比较 title/userName/password/url/notes 五项：仅修改 tags、自定义字段、
     * 附件、图标、分组结构等内容的编辑会被误判为"无变化"，导致复用过期缓存并把
     * 旧字节上传到云端（本地编辑与云端静默分叉）。
     * ProtectedString.equals 为字节数组内容比较，整字段比较不会物化明文密码。
     */
    private fun hasDatabaseContentChanged(current: KdbxDatabase, reference: KdbxDatabase?): Boolean {
        if (reference == null) return true
        if (current.deletedObjects != reference.deletedObjects) return true
        return isGroupContentChanged(current.rootGroup, reference.rootGroup)
    }

    private fun isGroupContentChanged(a: KdbxGroup, b: KdbxGroup): Boolean {
        if (a.id != b.id || a.name != b.name || a.notes != b.notes ||
            a.iconId != b.iconId || a.customIconId != b.customIconId ||
            a.parentGroupId != b.parentGroupId ||
            // P1-8 配套：分组 tags / customData 已为一等持久化字段，纳入变化检测，
            // 防止仅修改分组标签的编辑被误判为「无变化」而把旧字节上传云端
            a.tags != b.tags || a.customData != b.customData
        ) {
            return true
        }
        if (a.entries.size != b.entries.size) return true
        val bEntries = b.entries.associateBy { it.id }
        for (entry in a.entries) {
            val ref = bEntries[entry.id] ?: return true
            if (isEntryContentChanged(entry, ref)) return true
        }
        if (a.subgroups.size != b.subgroups.size) return true
        val bSubgroups = b.subgroups.associateBy { it.id }
        for (sub in a.subgroups) {
            val ref = bSubgroups[sub.id] ?: return true
            if (isGroupContentChanged(sub, ref)) return true
        }
        return false
    }

    private fun isEntryContentChanged(a: KdbxEntry, b: KdbxEntry): Boolean {
        return a.fields != b.fields ||
                a.customFields != b.customFields ||
                a.tags != b.tags ||
                a.attachments != b.attachments ||
                a.iconId != b.iconId ||
                a.customIconId != b.customIconId ||
                a.overrideUrl != b.overrideUrl ||
                a.qualityCheck != b.qualityCheck ||
                a.parentGroupId != b.parentGroupId ||
                a.previousParentGroup != b.previousParentGroup ||
                a.customData != b.customData ||
                a.autoType != b.autoType ||
                a.backgroundColor != b.backgroundColor ||
                a.foregroundColor != b.foregroundColor ||
                a.history.size != b.history.size
    }
}
