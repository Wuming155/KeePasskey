package com.keepasskey.app.sync

import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.database.session.PreparedSerializedSave

/**
 * `ENG-20261028-31` / `ENG-20261028-19`：合并产物的**一次预备、两处使用**事务。
 *
 * 缺陷形态（整改前）：合并树先由 `SyncDatabaseCodec.serializeLocalDatabase` 序列化用于上传，
 * 上传成功后再调 `DatabaseSession.save()` 为本地落盘**再序列化一遍**——两次整库 XML + 压缩 +
 * KDF 派生；同时本地采纳发生在写盘之前（`adoptDatabaseIfUnchanged` → `save()`），写盘失败会留下
 * 「内存已合并、文件未合并」的半步状态，且用户裁决路径随即清空待决会话，裁决无法重试。
 *
 * 本事务把两件事收进同一条提交边界：
 * 1. [prepare] 在可取消段完成「修剪 + 序列化」（`DatabaseSession.prepareSerializedSave`），
 *    产物绑定会话票据、修剪结果与凭据代次；
 * 2. [upload] 只读借用**同一份密文**上传云端（[PreparedSerializedSave.borrowBytes]）；
 * 3. [commit] 由 `DatabaseSession.commitPreparedSave` 在同一会话锁内复检实例身份与基线修订，
 *    **写盘确认成功之后**才发布采纳并前移基线。
 *
 * 因此：上传与本地落盘是同一版本（不存在「云端是修剪前、本地是修剪后」的错配）；写盘失败 /
 * 取消时活动树、基线、磁盘保持提交前状态，待决材料仍可重试；成功路径只序列化一次。
 *
 * 所有权：本事务独占 [PreparedSerializedSave]；任何退出路径都必须 [wipe]（整库密文不得滞留）。
 * 借出的字节只在 [upload] 闭包内有效，禁止修改、清零、驻留或跨协程传递。
 */
internal class SyncMergedSaveTransaction private constructor(
    private val databaseSession: DatabaseSession,
    private val expectedSessionSnapshot: KdbxDatabase,
    private val mergedDb: KdbxDatabase,
    private val prepared: PreparedSerializedSave
) {

    /** 以**与本地落盘完全相同**的密文执行上传；上传结果由调用方分派。 */
    suspend fun <T> upload(block: suspend (ByteArray) -> T): T = prepared.borrowBytes(block)

    /**
     * 412 重入用的独立密文副本（调用方用毕清零）。
     *
     * 只在「上传遭 412、需以合并产物为本地侧重取最新远端」的分支调用：重入侧必须在本事务
     * [wipe] 之后仍能解析该版本，故这一分支付出一次整库拷贝，常规成功路径不产生额外副本。
     */
    fun copyUploadedBytesForReentry(): ByteArray = prepared.copyBytesForHandoff()

    /**
     * 提交本地持久化：写盘成功后才发布采纳，成功时返回**已发布的活动树实例**
     * （与本次上传密文严格对应的版本，含本次历史修剪结果）。
     *
     * 失败语义：
     * - 校验未通过（会话树 / 基线修订 / 票据代次变化、只读、无通道）⇒ `SAVE_SESSION_CHANGED` 等
     *   可处理错误码，活动树与磁盘零改动；
     * - 写盘抛异常 / 取消 ⇒ 原样传播（活动树、状态、基线、磁盘保持提交前状态）。
     */
    suspend fun commit(): KdbxResult<KdbxDatabase> =
        databaseSession.commitPreparedSave(expectedSessionSnapshot, mergedDb, prepared)

    fun wipe() = prepared.wipe()

    companion object {
        /**
         * 预备合并产物。
         *
         * @param expectedSessionSnapshot 合并窗口起点（或最近一次校验通过）的会话树实例；
         *   为 null 时以**调用时刻**的活动树为起点（与既有「无快照直采」路径同判据来源，
         *   但从此也受实例身份约束——窗口内出现编辑即如实中止）。
         */
        suspend fun prepare(
            databaseSession: DatabaseSession,
            expectedSessionSnapshot: KdbxDatabase?,
            mergedDb: KdbxDatabase
        ): KdbxResult<SyncMergedSaveTransaction> {
            val expected = expectedSessionSnapshot ?: databaseSession.databaseFlow.value
                ?: return KdbxResult.Failure(
                    IllegalStateException("活动数据库为空"),
                    code = KdbxError.SAVE_NO_DATABASE
                )
            // 上传前拦：合并产物先天不覆盖窗口内编辑时，不做无谓的整库序列化与上传
            if (databaseSession.databaseFlow.value !== expected) {
                return KdbxResult.Failure(
                    IllegalStateException("合并窗口内会话树已变化"),
                    code = KdbxError.SAVE_SESSION_CHANGED
                )
            }
            return when (val prepared = databaseSession.prepareSerializedSave(mergedDb)) {
                is KdbxResult.Success -> KdbxResult.Success(
                    SyncMergedSaveTransaction(databaseSession, expected, mergedDb, prepared.getOrThrow())
                )
                is KdbxResult.Failure -> prepared
            }
        }
    }
}
