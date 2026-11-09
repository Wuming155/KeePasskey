package com.keepasskey.app.sync

import com.keepasskey.database.file.KdbxDatabase

/**
 * `ISSUE-P1-591`：本地写路径（上传 / 两端一致 / 强制覆盖）完成后的**基线结算**结论。
 *
 * 版本判据用**实例身份**（KdbxDatabase 走 copy-on-write，每次编辑 / 保存都产出新实例，
 * 故身份即「内容修订号」的进程内代理，与 `adoptDatabaseIfUnchanged(expectedSnapshot)` 同尺）。
 */
internal enum class UploadSettlement {
    /** 会话活动树仍是本次实际上传 / 已确认的**同一实例** ⇒ 基线前移到它，本轮与远端收敛。 */
    CONVERGED,

    /**
     * 上传（或下载比对）窗口内用户已保存出新树（B）⇒ **不前移基线**：远端虽已接受 A，
     * 但 B 仍属未同步，须在下一轮被上传 / 合并。调用方据此回报「本地仍有待同步修改」，
     * 不得回显「已同步」。
     */
    LOCAL_DIVERGED
}

/**
 * 把基线（[SyncSessionState.lastSyncedDb]）结算到**本次实际发送 / 已确认的版本** [uploaded]，
 * 而**不是**读取结算时刻的 `databaseSession.databaseFlow.value`（`AUD-03` 根因：
 * 上传 A 的网络等待期间用户保存 B，旧实现把 B 记为 A 的基线，下一轮便不再上传 B）。
 *
 * 判据＝「会话活动树 [active] 与 [uploaded] 是否同一实例」：
 * - 同一实例（无窗口内编辑 / 无换库）⇒ 前移基线并回报 [UploadSettlement.CONVERGED]；
 * - 不同实例 ⇒ **不动基线**（B 保留待同步）、不持有非活动树的引用（敏感擦除仍由活动树那一次
 *   `clearSensitiveData()` 承担，见 [SyncSessionState] 的 G2 口径），回报 [UploadSettlement.LOCAL_DIVERGED]。
 *
 * @param uploaded 本轮实际上传 / 已确认与远端一致的本地版本（周期起点快照 `currentDb`）。
 * @param active 结算时刻的会话活动树（`databaseSession.databaseFlow.value`）。
 */
internal fun settleSyncedVersion(
    session: SyncSessionState,
    uploaded: KdbxDatabase,
    active: KdbxDatabase?
): UploadSettlement {
    if (active === uploaded) {
        session.lastSyncedDb = uploaded
        session.checkpointDatabase = uploaded
        return UploadSettlement.CONVERGED
    }
    return UploadSettlement.LOCAL_DIVERGED
}

/**
 * `ENG-20261028-09` / `ENG-20261028-31`：合并产物**已上传云端、但本地落盘失败**时的进程内基线收口。
 *
 * 为什么必须作废而不是留着：上传成功后引擎已把合并内容写入同步工作缓存
 * （`SyncEngine.markResolvedAndUpload` 的 `cache.writeCache`），而本地会话树与正式文件仍是
 * 采纳前版本。此时若保留旧的进程内基线（`lastSyncedDb`），下一轮装配会据「本地内容未变」
 * 直接复用缓存字节充当本地侧，并在与远端内容的下一次比对中判为「两端一致」
 * ⇒ 云端已合并的版本被记为已收敛，却从未落到本地；用户之后的一次普通编辑再上传时，
 * 会以缺少该合并内容的本地树整库覆盖远端（对端改动静默丢失）。
 *
 * 置空后 `SyncContentChangeDetector` 退回「解析缓存快照做内容比较」的保守路径：
 * 会话树与缓存内容不同即判有变化，下一轮重新按冲突合并收敛，不产生假的已同步结论。
 * `checkpointDatabase` 一并清空（该身份的本地文件检查点同样不成立）。
 */
internal fun invalidateSyncedBaselineAfterLocalFailure(session: SyncSessionState) {
    session.lastSyncedDb = null
    session.checkpointDatabase = null
}
