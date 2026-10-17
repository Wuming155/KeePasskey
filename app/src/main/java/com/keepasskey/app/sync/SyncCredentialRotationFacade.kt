package com.keepasskey.app.sync

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 主凭据轮换后的「云端副本待替换」标记面（`ISSUE-P3-528`；`ISSUE-P2-645` 自协调器拆分）。
 *
 * 只做本地非秘密标记：不触发网络、不重读活动库。`store == null`（手工装配路径 / 既有单测）
 * 时整条机制旁路，与拆分前行为一致。
 */
internal class SyncCredentialRotationFacade(
    private val databaseSession: DatabaseSession,
    private val syncCredentialsStore: SyncCredentialsStore,
    private val session: SyncSessionState,
    private val store: SyncCredentialRotationStore?,
    private val debugLog: DebugLogBuffer
) {

    /** 受理时同步捕获当前凭据票据对应的非秘密远端路径快照。 */
    fun captureTargetSnapshot(): String? {
        if (store == null) return null
        val path = databaseSession.currentPathIdentifier ?: return null
        val fileName = if (path.startsWith(SyncProbeSupport.CONTENT_URI_PREFIX)) null else File(path).name
        val defaultName = fileName?.takeIf(String::isNotBlank)
            ?: databaseSession.databaseFlow.value?.let { "${it.databaseName}.kdbx" }
            ?: return null
        return session.testRemotePath
            ?: syncCredentialsStore.captureRotationRemotePath(path, defaultName)
    }

    /** 轮换前固定远端身份，提交后不能按已切换的活动库登记同步事实。 */
    suspend fun captureTarget(): String? = withContext(Dispatchers.IO) {
        captureTargetSnapshot()
    }

    /** 仅写入已捕获目标的本地标记，不触发网络或重读活动库。 */
    suspend fun markTarget(remotePath: String?): Boolean = withContext(Dispatchers.IO) {
        val store = store ?: return@withContext false
        if (remotePath == null) return@withContext false
        try {
            store.markRecrypted(remotePath)
            debugLog.info(SYNC_LOG_TAG, "主凭据已轮换：已登记云端副本待替换")
            true
        } catch (e: Exception) { // cancel-n/a: 非挂起偏好登记
            debugLog.warn(SYNC_LOG_TAG, "登记云端副本待替换失败: ${e.javaClass.simpleName}")
            false
        }
    }

    /** 成功换密后登记本地重加密事实；未配置同步则空操作，不触发网络。 */
    suspend fun markLocalVaultRecrypted(): Boolean = markTarget(captureTarget())
}
