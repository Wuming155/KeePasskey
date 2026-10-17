package com.keepasskey.app.sync

import android.content.Context
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.engine.SyncRollbackGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 同步恢复状态的只读查询（`ISSUE-P2-645`：自 `SyncCoordinator` 拆分）。
 *
 * 只读 filesDir / SharedPreferences / 探测记录元数据；不解锁凭据、不读 KDBX 明文、不发网络请求。
 * 查询失败一律按「存在待恢复状态」上浮（宁可在界面上多提示一次，也不静默吞掉恢复入口）。
 */
internal class SyncRecoveryQueries(
    private val context: Context,
    private val databaseSession: DatabaseSession,
    private val syncCredentialsStore: SyncCredentialsStore,
    private val debugLog: DebugLogBuffer,
    /** 惰性取用：构造期不得触达（无 `filesDir` 的测试上下文在构造期取用会抛错）。 */
    private val pendingUploads: () -> PeriodicSyncPendingUploadStore,
    private val probeStore: PeriodicSyncProbeStore?,
    private val rollbackStateDirectory: () -> File,
    private val currentVaultIdentity: () -> String?
) {

    /** 指定库是否有持久化的周期同步恢复状态（锁屏页与同步设置页共用）。 */
    suspend fun hasPendingUserRecovery(vaultIdentity: String?): Boolean = withContext(Dispatchers.IO) {
        val identity = vaultIdentity?.takeIf(String::isNotBlank) ?: return@withContext false
        try {
            if (pendingUploads().hasEntry(identity)) return@withContext true
            if (SyncRollbackGuard.hasPendingAcceptanceForVault(rollbackStateDirectory(), identity)) {
                return@withContext true
            }

            val record = when (val stored = probeStore?.read(identity)) {
                null, PeriodicSyncProbeRead.Missing -> null
                PeriodicSyncProbeRead.Corrupt -> return@withContext true
                is PeriodicSyncProbeRead.Stored -> stored.record
            } ?: return@withContext false
            if (record.needsUnlock) return@withContext true

            val rollbackScope = record.rollbackVaultScope ?: return@withContext false
            val remotePath = syncCredentialsStore.captureRotationRemotePath(
                identity,
                SyncProbeSupport.defaultFileName(databaseSession, identity)
            ) ?: return@withContext false
            SyncRollbackGuard(File(context.filesDir, SyncRollbackGuard.STATE_DIR_NAME), rollbackScope)
                .hasPendingAcceptance(remotePath)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            debugLog.warn(SYNC_LOG_TAG, "读取同步恢复状态失败: ${failure.javaClass.simpleName}")
            true
        }
    }

    /** 当前活动库的锁态恢复提示；冷启动页面不能依赖 DatabaseSession 的解锁态路径。 */
    suspend fun hasPendingUserRecoveryForActiveVault(): Boolean =
        hasPendingUserRecovery(currentVaultIdentity())

    /** True only for an adoption quarantine with an explicit, conditional local-upload recovery path. */
    suspend fun hasPendingAdoptionRecoveryForActiveVault(): Boolean =
        withContext(Dispatchers.IO) { hasPendingAdoptionRecovery(currentVaultIdentity()) }

    private suspend fun hasPendingAdoptionRecovery(vaultIdentity: String?): Boolean {
        val identity = vaultIdentity?.takeIf(String::isNotBlank) ?: return false
        return try {
            val rollbackDir = rollbackStateDirectory()
            if (SyncRollbackGuard.hasPendingAcceptanceForVault(rollbackDir, identity)) return true
            val record = (probeStore?.read(identity) as? PeriodicSyncProbeRead.Stored)?.record
                ?: return false
            val rollbackScope = record.rollbackVaultScope ?: return false
            val remotePath = syncCredentialsStore.captureRotationRemotePath(
                identity,
                SyncProbeSupport.defaultFileName(databaseSession, identity)
            ) ?: return false
            SyncRollbackGuard(rollbackDir, rollbackScope, ownerVaultIdentity = identity)
                .hasPendingAcceptance(remotePath)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }
}
