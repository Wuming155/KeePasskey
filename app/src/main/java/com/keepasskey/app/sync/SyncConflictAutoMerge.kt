package com.keepasskey.app.sync

import com.keepasskey.app.R
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.engine.SyncCommitResult
import com.keepasskey.sync.engine.SyncEngine
import com.keepasskey.sync.merge.MergeResult
import com.keepasskey.sync.model.cleanEtag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * 职责边界：接收三方合并产物与双方树，序列化 → 乐观锁上传 → 采纳或 412 重入信号。
 * 身份集合判定擦除语义、ETag 唯一判据与深度界限与拆分前逐字一致；调用方
 * （`SyncConflictController.handleConflictMerge`）仍持有会话互斥锁。
 */

/** [autoMergeAndUpload] 的上传终态（供调用方分派 412 重入）。 */
internal sealed interface AutoMergeUploadResult {
    data class Completed(val outcome: SyncOutcome) : AutoMergeUploadResult

    /**
     * 合并上传遭 412 且已取到最新远端：合并窗口内远端再次变更。
     * 四棵解析树与合并产物**已按身份集合判定擦除**（在取得最新远端之后、返回之前），
     * [mergedBytes] 为合并产物的密文序列化（擦除不影响既有字节），供重入侧全新解析。
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
 */
internal suspend fun autoMergeAndUpload(
    codec: SyncDatabaseCodec,
    strings: StringsProvider,
    databaseSession: DatabaseSession,
    syncEngine: SyncEngine,
    remotePath: String,
    localDb: KdbxDatabase,
    localDbOwned: Boolean,
    remoteDb: KdbxDatabase,
    trustedBase: KdbxDatabase?,
    mergeResult: MergeResult,
    conflictEtag: String
): AutoMergeUploadResult = withContext(Dispatchers.Default) {
    val mergedDb = localDb.copy(
        rootGroup = mergeResult.mergedRoot,
        deletedObjects = mergeResult.mergedDeletedObjects
    )
    val mergedBytes = codec.serializeLocalDatabase(mergedDb)
    if (mergedBytes == null) {
        // ISSUE-P3-119：序列化失败即整体放弃本次合并（mergedDb / 双方树均不被采用），
        // 三棵解析产物同批显式擦除（合并产物本身也在此丢弃，不存在共享引用者）。
        // ISSUE-P3-168：`localDb` 若来自调用方的内存快照则不属于本方法，跳过擦除
        if (localDbOwned) wipeDiscarded(localDb)
        wipeDiscarded(remoteDb)
        wipeDiscarded(trustedBase)
        return@withContext AutoMergeUploadResult.Completed(
            SyncOutcome.Error(strings.get(R.string.sync_error_serialize_merged_failed))
        )
    }

    val uploadResult = syncEngine.markResolvedAndUpload(
        remotePath,
        mergedBytes,
        expectedEtag = conflictUploadExpectedEtag(conflictEtag)
    )
    if (!uploadResult.isSuccess) {
        val ex = uploadResult.exceptionOrNull()
        if (ex is com.keepasskey.sync.model.SyncException.ConflictError) {
            // AC③：取最新远端（重放拒绝 / 不可达如实映射）；四棵树在确定失效后擦除。
            // 注意 mergedBytes 已是密文序列化产物，擦树不影响其字节有效性
            val fresh: SyncCommitResult = try {
                syncEngine.commitLocal(remotePath, mergedBytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                eraseDiscardedParseResults(
                    localDb, localDbOwned, remoteDb, trustedBase, mergedDb,
                    databaseSession.databaseFlow.value
                )
                return@withContext AutoMergeUploadResult.Completed(
                    SyncOutcome.Error(
                        strings.get(R.string.sync_error_upload_merged_failed, e.message)
                    )
                )
            }
            return@withContext when (fresh) {
                is SyncCommitResult.ConflictNeedsMerge -> {
                    eraseDiscardedParseResults(
                        localDb, localDbOwned, remoteDb, trustedBase, mergedDb,
                        databaseSession.databaseFlow.value
                    )
                    AutoMergeUploadResult.Superseded(mergedBytes, fresh.remoteBytes, fresh.remoteEtag)
                }
                is SyncCommitResult.Uploaded -> {
                    // 远端已回退到基线内容：合并产物上传成功 ⇒ 与既有成功分支同语义采纳
                    databaseSession.updateDatabaseMeta { mergedDb }
                    val saveResult = databaseSession.save()
                    eraseDiscardedParseResults(
                        localDb, localDbOwned, remoteDb, trustedBase, mergedDb = null,
                        live = databaseSession.databaseFlow.value
                    )
                    AutoMergeUploadResult.Completed(
                        if (saveResult is KdbxResult.Failure) {
                            SyncOutcome.Error(
                                strings.get(R.string.sync_error_merged_upload_local_save_failed, saveResult.message)
                            )
                        } else {
                            SyncOutcome.MergedAndUploaded
                        }
                    )
                }
                is SyncCommitResult.RemoteUnreachable -> {
                    eraseDiscardedParseResults(
                        localDb, localDbOwned, remoteDb, trustedBase, mergedDb,
                        databaseSession.databaseFlow.value
                    )
                    AutoMergeUploadResult.Completed(SyncOutcome.Offline)
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
            }
        }
        // ISSUE-P3-235 G2：上传失败 ⇒ 合并产物与本次判定用的三棵解析树**全部**失去持有者，
        // 按身份集合判定擦除未被活动会话树引用的实例（合并树按原实例复用来源树节点，裸擦会清空活动库）
        eraseDiscardedParseResults(
            localDb, localDbOwned, remoteDb, trustedBase, mergedDb,
            databaseSession.databaseFlow.value
        )
        return@withContext AutoMergeUploadResult.Completed(
            SyncOutcome.Error(
                strings.get(R.string.sync_error_upload_merged_failed, ex?.message)
            )
        )
    }
    databaseSession.updateDatabaseMeta { mergedDb }
    val saveResult = databaseSession.save()
    // ISSUE-P3-235 G2：合并树已被采用为会话库（即存活侧本身，故传 null 不列入擦除面），
    // 三棵来源树中未被复用的实例按身份判定定点擦除、不留给 GC——`wipeDiscarded` 的
    // 「无存活别名」前提在此**不成立**（`KdbxMerger` 对单侧独有对象复用原实例）
    eraseDiscardedParseResults(
        localDb, localDbOwned, remoteDb, trustedBase, mergedDb = null,
        live = databaseSession.databaseFlow.value
    )
    AutoMergeUploadResult.Completed(
        if (saveResult is KdbxResult.Failure) {
            SyncOutcome.Error(
                strings.get(R.string.sync_error_merged_upload_local_save_failed, saveResult.message)
            )
        } else {
            SyncOutcome.MergedAndUploaded
        }
    )
}
