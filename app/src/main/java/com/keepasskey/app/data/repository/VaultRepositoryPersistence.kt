package com.keepasskey.app.data.repository

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.session.DatabaseSession

/**
 * 仓库的落盘 / 漂移检查 / 活动库身份出口（`ISSUE-P2-645`：自 `RealVaultRepository` 拆分）。
 *
 * 三者都是「所有写路径共用的同一把尺子」：协调器只通过这里的函数回灌落盘，
 * 不允许各自构造 `VaultWriteBoundary`。行为与拆分前逐字一致（含失败日志的脱敏口径：
 * 只记异常类名）。
 */
internal class VaultRepositoryPersistence(
    private val writeBoundary: VaultWriteBoundary,
    private val databaseSession: DatabaseSession,
    private val debugLog: DebugLogBuffer,
    private val logTag: String
) {

    /** ISSUE-P2-378 / P1-601：写前外部修改漂移检查（全库保存与换密 / 改绑同源）。 */
    fun abortOnDrift(): KdbxResult.Failure? = writeBoundary.abortOnDrift()

    /** H3：会话落盘的唯一出口（ISSUE-P1-601 起实现收敛到 [VaultWriteBoundary.persist]）。 */
    suspend fun persist(): KdbxResult<Unit> {
        val result = writeBoundary.persist()
        if (result is KdbxResult.Failure) {
            debugLog.error(logTag, "保存中止或失败：${result.error.javaClass.simpleName}")
        }
        return result
    }

    /** ISSUE-P1-601：库级写入统一提交边界（先漂移检查 → 再应用候选 → 再落盘）。 */
    suspend fun commitMeta(transform: (KdbxDatabase) -> KdbxDatabase): KdbxResult<Unit> {
        val result = writeBoundary.commitMeta(transform)
        if (result is KdbxResult.Failure) {
            debugLog.error(logTag, "库设置提交失败：${result.error.javaClass.simpleName}")
        }
        return result
    }

    /** ISSUE-P1-592 步骤 6：当前活动库身份（路径标识优先，回退活动文件绝对路径）；无会话返回 null。 */
    fun activeVaultId(): String? =
        databaseSession.currentPathIdentifier ?: databaseSession.currentFile?.absolutePath
}
