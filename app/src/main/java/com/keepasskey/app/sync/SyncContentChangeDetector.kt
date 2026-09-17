package com.keepasskey.app.sync

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
            // ISSUE-P3-119：该解析产物**仅用于内容比较**，比较结束即被丢弃——必须显式擦除，
            // 否则整棵解密树（含 ≤ 落盘阈值的附件内联明文）只能静默等待 GC 回收。
            // 此处的安全性依据：比较函数只读遍历，不向任何存活对象转移引用。
            return try {
                hasDatabaseContentChanged(currentDb, cachedDb)
            } finally {
                cachedDb.clearSensitiveData()
            }
        }
        return true
    }

    /**
     * 全字段递归内容比较（C1 整改；`ISSUE-P2-91` 起判定逻辑抽至 [KdbxContentComparator]
     * 以便脱离 DI 直测——该类此前**零用例覆盖**，而它是「本地编辑被静默丢弃」类缺陷的唯一判据）。
     *
     * 判定的 KDoc 与口径（含「刻意不比 `times`」及其理由）随逻辑一并迁移；
     * 本函数只保留「基线缺失即按有变化保守处理」这一条与比较无关的分支。
     */
    private fun hasDatabaseContentChanged(current: KdbxDatabase, reference: KdbxDatabase?): Boolean {
        if (reference == null) return true
        return KdbxContentComparator.changed(current, reference)
    }
}
