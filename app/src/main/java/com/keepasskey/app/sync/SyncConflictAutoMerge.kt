package com.keepasskey.app.sync

import com.keepasskey.app.R
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.engine.SyncCommitResult
import com.keepasskey.sync.engine.SyncEngine
import com.keepasskey.sync.engine.SyncResolveUploadResult
import com.keepasskey.sync.merge.MergeResult
import com.keepasskey.sync.model.cleanEtag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.keepasskey.app.ui.model.textArg

/**
 * ISSUE-P1-275 AC①：冲突上传 If 预条件期望值的**唯一判据**——冲突时刻 ETag 规范化后非空
 * 即原样透传，空（无 ETag 服务器 / 探测失败）才是唯一可走「无基线上传」的显式回退情形。
 * 用户裁决路径与自动合并路径共用本判据，禁止各自再写第二份；`cleanEtag` 幂等。
 */
internal fun conflictUploadExpectedEtag(conflictMomentEtag: String): String? =
    cleanEtag(conflictMomentEtag).ifEmpty { null }

/**
 * 无条目级冲突时的合并上传（§280 自 `SyncConflictController.autoMergeAndUpload` 拆出，纯结构性）。
 *
 * 职责边界：接收三方合并产物与双方树，**预备一份密文**（修剪 + 序列化）→ 乐观锁上传 →
 * 写盘确认后采纳，或 412 重入信号。身份集合判定擦除语义、ETag 唯一判据与深度界限与拆分前一致；
 * 调用方（`SyncConflictController.handleConflictMerge`）仍持有会话互斥锁。
 */

/** [autoMergeAndUpload] 的上传终态（供调用方分派 412 重入）。 */
internal sealed interface AutoMergeUploadResult {
    data class Completed(
        val outcome: SyncOutcome,
        val confirmedDatabase: KdbxDatabase? = null
    ) : AutoMergeUploadResult

    /**
     * 合并上传遭 412 且已取到最新远端：合并窗口内远端再次变更。
     * 四棵解析树与合并产物**已按身份集合判定擦除**（在取得最新远端之后、返回之前）；
     * [mergedBytes] 为合并产物密文的**独立副本**（`ENG-20261028-31`：本事务的预备密文随
     * 返回前清零，故仅在该分支付出一次整库拷贝），供重入侧全新解析；调用方用毕须清零。
     */
    data class Superseded(
        val mergedBytes: ByteArray,
        val freshRemoteBytes: ByteArray,
        val freshEtag: String
    ) : AutoMergeUploadResult
}

/**
 * 无条目级冲突：合并产物落库并上传远端。
 *
 * ISSUE-P1-275 AC①：上传必须携带冲突时刻 ETag（经 [conflictUploadExpectedEtag] 唯一判据，
 * 与用户裁决路径共用）——「探测远端 → 下载合并 → 上传」窗口含一次 KDF 级全库序列化与一次
 * 分钟级网络往返，撤除最后一步的乐观锁 = 他端窗口内写入被静默覆盖。
 *
 * AC③：上传遭 412（合并窗口内远端再次变更）时**不归一为一次性错误**——先以合并产物为本地侧
 * 取最新远端，交由调用方重新进入冲突流程（返回 [AutoMergeUploadResult.Superseded]）；
 * 远端若已回退到基线内容则本次上传成功，走采纳路径。失效树在取得最新远端之后按身份集合判定擦除。
 *
 * `ENG-20261028-31` / `ENG-20261028-19`：序列化只发生一次——合并产物经
 * [SyncMergedSaveTransaction] 预备为**一份**密文，上传借用它、本地提交复用它；本地提交
 * 在写盘确认成功之后才发布采纳。写盘失败时内存保持采纳前状态、基线不前移，并作废进程内
 * 同步基线（`ENG-20261028-09`：不得留下「内存已合并、文件未合并」的假已同步状态）。
 */
internal suspend fun autoMergeAndUpload(
    strings: StringsProvider,
    databaseSession: DatabaseSession,
    syncSession: SyncSessionState,
    syncEngine: SyncEngine,
    remotePath: String,
    localDb: KdbxDatabase,
    localDbOwned: Boolean,
    remoteDb: KdbxDatabase,
    trustedBase: KdbxDatabase?,
    mergeResult: MergeResult,
    conflictEtag: String,
    expectedSessionSnapshot: KdbxDatabase? = null
): AutoMergeUploadResult = withContext(Dispatchers.Default) {
    // ISSUE-P3-292：合并历史在序列化 / 上传 / 落库之前按库级 Meta 上限截断
    // （两条合并路径共用同一截断口径；上传字节与落库树取自同一产物）
    val mergedDb = truncateMergedHistory(
        localDb.copy(
            rootGroup = mergeResult.mergedRoot,
            deletedObjects = mergeResult.mergedDeletedObjects,
            // ISSUE-P2-280 AC①：合并图标池一并采用——只换树与墓碑会让远端新增图标丢失，
            // 条目 / 分组的 customIconId 沦为悬空引用（写出侧 AC② 起对此 fail-closed）
            customIcons = mergeResult.mergedCustomIcons
            // ISSUE-P2-659：库级 Meta 的合并产物一并采用。无 Meta 冲突时走自动合并，
            // 外部工具对库名 / 描述 / 默认用户名 / 自定义数据的改动由此真正落库。
        ).withMergedMeta(mergeResult.mergedMeta)
    )

    /** ISSUE-P2-278：守卫中止的统一出口——全部解析树与合并产物按身份集合判定擦除。 */
    fun abortDiverged(): AutoMergeUploadResult {
        eraseDiscardedParseResults(
            localDb, localDbOwned, remoteDb, trustedBase, mergedDb,
            databaseSession.databaseFlow.value
        )
        return AutoMergeUploadResult.Completed(
            SyncOutcome.Error(strings.get(R.string.sync_error_local_changed_during_sync))
        )
    }

    // ISSUE-P2-278：上传前先拦——合并产物先天不覆盖窗口内编辑时，不做无谓的序列化与上传
    // `ENG-20261028-31`：同一份预备密文（含本次历史修剪结果）既上传又落盘，二者不再各序列化一遍
    val transaction = when (
        val prepared = SyncMergedSaveTransaction.prepare(databaseSession, expectedSessionSnapshot, mergedDb)
    ) {
        is KdbxResult.Success -> prepared.getOrThrow()
        is KdbxResult.Failure -> {
            if (prepared.code == KdbxError.SAVE_SESSION_CHANGED) {
                return@withContext abortDiverged()
            }
            // ISSUE-P3-119：序列化 / 只读 / 无通道 / 凭据丢失即整体放弃本次合并（mergedDb / 双方树均不被采用），
            // 解析产物同批显式擦除（合并产物本身也在此丢弃，不存在共享引用者）。
            // ISSUE-P3-168：`localDb` 若来自调用方的内存快照则不属于本方法，跳过擦除
            eraseDiscardedParseResults(
                localDb, localDbOwned, remoteDb, trustedBase, mergedDb,
                databaseSession.databaseFlow.value
            )
            return@withContext AutoMergeUploadResult.Completed(
                SyncOutcome.Error(strings.get(R.string.sync_error_serialize_merged_failed))
            )
        }
    }
    try {
        /**
         * 上传成功后的本地提交与结算（常规成功、412 后远端回退两处共用）。
         *
         * 写盘失败 / 会话变化：结算 reject（基线不前移）、作废进程内同步基线，并如实返回错误；
         * 活动树、状态与磁盘保持提交前状态（待下轮按冲突收敛），绝不把未落盘的合并树当作已采纳。
         */
        suspend fun commitUploaded(
            settlement: com.keepasskey.sync.engine.RemoteAdoptionSettlement?
        ): Pair<SyncOutcome, KdbxDatabase?> {
            val commit = try {
                transaction.commit()
            } catch (cancelled: CancellationException) {
                // 取消：写盘未确认完成 ⇒ 不结算、不发布；引擎此前已把合并内容写入同步工作缓存，
                // 故与落盘失败同口径作废进程内同步基线，finally 仍会清零预备密文
                invalidateSyncedBaselineAfterLocalFailure(syncSession)
                throw cancelled
            } catch (writeFailure: Throwable) {
                settlement?.reject()
                invalidateSyncedBaselineAfterLocalFailure(syncSession)
                return SyncOutcome.Error(
                    strings.get(
                        R.string.sync_error_merged_upload_local_save_failed,
                        KdbxResult.Failure(writeFailure, code = KdbxError.SAVE_FAILED).textArg(strings)
                    )
                ) to null
            }
            if (commit is KdbxResult.Failure) {
                settlement?.reject()
                invalidateSyncedBaselineAfterLocalFailure(syncSession)
                // 会话树 / 基线修订在窗口内变化 ⇒ 合并产物已不覆盖窗口内编辑：与既有守卫同语义，
                // 如实中止本周期（下轮按冲突流程收敛），不把过期产物当成「保存失败」上报
                val outcome = if (commit.code == KdbxError.SAVE_SESSION_CHANGED) {
                    SyncOutcome.Error(strings.get(R.string.sync_error_local_changed_during_sync))
                } else {
                    SyncOutcome.Error(
                        strings.get(R.string.sync_error_merged_upload_local_save_failed, commit.textArg(strings))
                    )
                }
                return outcome to null
            }
            settlement?.accept()
            return SyncOutcome.MergedAndUploaded to commit.getOrThrow()
        }

        val uploadResult = transaction.upload { bytes ->
            syncEngine.markResolvedAndUpload(
                remotePath,
                bytes,
                expectedEtag = conflictUploadExpectedEtag(conflictEtag)
            )
        }
        when (uploadResult) {
            is SyncResolveUploadResult.Uploaded -> {
                val (outcome, confirmed) = commitUploaded(uploadResult.settlement)
                // ISSUE-P3-235 G2：合并树已被采用为会话库时是存活侧本身（传 null 不列入擦除面）；
                // 提交失败时合并树未被采用，须列入擦除面按身份判定定点释放。
                // `wipeDiscarded` 的「无存活别名」前提在此**不成立**（`KdbxMerger` 对单侧独有对象复用原实例），
                // 故一律走身份集合判定，且存活侧取**当前**活动树。
                eraseDiscardedParseResults(
                    localDb, localDbOwned, remoteDb, trustedBase,
                    mergedDb = mergedDb.takeIf { confirmed == null },
                    live = databaseSession.databaseFlow.value
                )
                AutoMergeUploadResult.Completed(
                    outcome,
                    confirmedDatabase = confirmed.takeIf { outcome.isInSyncWithRemote() }
                )
            }
            is SyncResolveUploadResult.Failed -> {
                val ex = uploadResult.error
                if (ex is com.keepasskey.sync.model.SyncException.ConflictError) {
                    // AC③：取最新远端（重放拒绝 / 不可达如实映射）；四棵树在确定失效后擦除。
                    // ISSUE-P3-119：借用同一份密文（擦树不影响其字节有效性）
                    val fresh: SyncCommitResult = try {
                        transaction.upload { bytes -> syncEngine.commitLocal(remotePath, bytes) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        eraseDiscardedParseResults(
                            localDb, localDbOwned, remoteDb, trustedBase, mergedDb,
                            databaseSession.databaseFlow.value
                        )
                        return@withContext AutoMergeUploadResult.Completed(
                            SyncOutcome.Error(
                                // ISSUE-P3-311 项 3：只透出异常类型名
                                strings.get(R.string.sync_error_upload_merged_failed, e.javaClass.simpleName)
                            )
                        )
                    }
                    return@withContext when (fresh) {
                        is SyncCommitResult.ConflictNeedsMerge -> {
                            eraseDiscardedParseResults(
                                localDb, localDbOwned, remoteDb, trustedBase, mergedDb,
                                databaseSession.databaseFlow.value
                            )
                            // 重入侧必须在本事务清零（finally）之后继续解析该版本 ⇒ 交付独立副本
                            AutoMergeUploadResult.Superseded(
                                transaction.copyUploadedBytesForReentry(),
                                fresh.remoteBytes,
                                fresh.remoteEtag
                            )
                        }
                        is SyncCommitResult.Uploaded -> {
                            // 远端已回退到基线内容：合并产物上传成功 ⇒ 与既有成功分支同语义采纳
                            val (outcome, confirmed) = commitUploaded(fresh.settlement)
                            eraseDiscardedParseResults(
                                localDb, localDbOwned, remoteDb, trustedBase,
                                mergedDb = mergedDb.takeIf { confirmed == null },
                                live = databaseSession.databaseFlow.value
                            )
                            AutoMergeUploadResult.Completed(
                                outcome,
                                confirmedDatabase = confirmed.takeIf { outcome.isInSyncWithRemote() }
                            )
                        }
                        is SyncCommitResult.RemoteUnreachable -> {
                            eraseDiscardedParseResults(
                                localDb, localDbOwned, remoteDb, trustedBase, mergedDb,
                                databaseSession.databaseFlow.value
                            )
                            AutoMergeUploadResult.Completed(SyncOutcome.Offline)
                        }
                        is SyncCommitResult.RemoteRejected -> {
                            // ISSUE-P2-548：合并产物上传被鉴权 / 协议 / 服务端拒绝——不是「离线」
                            eraseDiscardedParseResults(
                                localDb, localDbOwned, remoteDb, trustedBase, mergedDb,
                                databaseSession.databaseFlow.value
                            )
                            AutoMergeUploadResult.Completed(
                                strings.remoteFailureOutcome(fresh.cause)
                            )
                        }
                        is SyncCommitResult.RollbackRejected -> {
                            // ISSUE-P2-18：最新远端为设备侧曾接受过的历史版本（回放），拒绝其参与合并
                            eraseDiscardedParseResults(
                                localDb, localDbOwned, remoteDb, trustedBase, mergedDb,
                                databaseSession.databaseFlow.value
                            )
                            AutoMergeUploadResult.Completed(
                                SyncOutcome.Error(strings.get(R.string.sync_error_rollback_rejected))
                            )
                        }
                        SyncCommitResult.RecoveryRequired -> {
                            eraseDiscardedParseResults(
                                localDb, localDbOwned, remoteDb, trustedBase, mergedDb,
                                databaseSession.databaseFlow.value
                            )
                            AutoMergeUploadResult.Completed(
                                SyncOutcome.Error(strings.get(R.string.sync_error_recovery_required))
                            )
                        }
                    }
                }
                // ISSUE-P3-235 G2：上传失败 ⇒ 合并产物与本次判定用的三棵解析树**全部**失去持有者，
                // 按身份集合判定擦除未被活动会话树引用的实例（合并树按原实例复用来源树节点，裸擦会清空活动库）
                eraseDiscardedParseResults(
                    localDb, localDbOwned, remoteDb, trustedBase, mergedDb,
                    databaseSession.databaseFlow.value
                )
                AutoMergeUploadResult.Completed(
                    SyncOutcome.Error(
                        // ISSUE-P3-550（原 grep `\.message ?:` 漏计本处：`ex?.message` 不含 `?:`）：
                        // 上传失败的 `message` 内嵌端点 / 主机 / 协议细节且服务端可控 ⇒
                        // 只透出**状态码或异常类型名**
                        strings.get(R.string.sync_error_upload_merged_failed, remoteFailureDescriptor(ex))
                    )
                )
            }
            SyncResolveUploadResult.RecoveryRequired -> {
                eraseDiscardedParseResults(
                    localDb, localDbOwned, remoteDb, trustedBase, mergedDb,
                    databaseSession.databaseFlow.value
                )
                AutoMergeUploadResult.Completed(
                    SyncOutcome.Error(strings.get(R.string.sync_error_recovery_required))
                )
            }
        }
    } finally {
        // 预备密文即整库密文：成功 / 失败 / 取消的任何退出路径都必须清零
        // （commit 与结算均在本区内完成，结算消费字节的时点早于本行）
        transaction.wipe()
    }
}
