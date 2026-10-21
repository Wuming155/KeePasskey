package com.keepasskey.app.security

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.session.DatabaseSession
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 补存失败后的独立加密恢复实现（`ISSUE-P1-592` 步骤 2 / 4）。
 *
 * 语义（方案边界）：锁定继续按安全事件生效；补存失败**不静默销毁**未提交版本——
 * 用**会话当前复合凭据**把内存树重新序列化成标准 KDBX 密文，落到应用私有非缓存目录
 * （[VaultRecoveryStore]）；主密码与密钥文件绝不随副本落盘。恢复副本完成或明确失败后，
 * 调用方（`AutoLockSessionGuard`）照常锁库擦除会话。
 *
 * 调度：序列化走 Default（[DatabaseSession.exportToBytes] 内部已 `withContext(Default)`），
 * 落盘走 IO（[VaultRecoveryStore.write]）。整体包 [NonCancellable]——锁定往往发生在进程即将
 * 退后台 / 熄屏的时刻，恢复副本必须跑完这段受控收尾，不得被调用方协程取消打断（半截副本由
 * 存储层的原子提交与完整性校验兜底，不自动采纳）。
 */
@Singleton
class SessionRecoveryCoordinator @Inject constructor(
    private val databaseSession: DatabaseSession,
    private val store: VaultRecoveryStore,
    private val alertStore: RecoveryAlertStore,
    private val debugLog: DebugLogBuffer
) : SessionRecoveryPolicy {

    override suspend fun onSaveFailedBeforeLock(): LockOutcome = withContext(NonCancellable) {
        val exported = databaseSession.captureEncryptedSnapshot()
        if (exported is KdbxResult.Failure) {
            debugLog.error(TAG, "恢复副本序列化失败: ${exported.error.javaClass.simpleName}")
            return@withContext registerPreservationFailure(UNKNOWN_VAULT_ID)
        }
        val snapshot = exported.getOrThrow()
        val committed = try {
            val staged = snapshot.borrowEncryptedBytes { bytes ->
                store.stage(snapshot.pathIdentifier, snapshot.vaultName, snapshot.pathIdentifier, bytes)
            }
            if (staged == null) false else try {
                var saved = false
                withContext(Dispatchers.IO) {
                    databaseSession.useCredentialTicket(snapshot.ticket) { saved = store.commit(staged) }
                }
                saved
            } finally { staged.close() }
        } finally { snapshot.wipe() }

        if (committed) {
            debugLog.info(TAG, "补存失败，已写独立加密恢复副本（下次冷启动可恢复）")
            LockOutcome.RecoveryCommitted
        } else {
            registerPreservationFailure(snapshot.pathIdentifier)
        }
    }

    /** 「无法保全」告警：尽力持久化供下次进入应用查看；返回是否已确认写入。 */
    private suspend fun registerPreservationFailure(vaultId: String): LockOutcome {
        val persisted = withContext(Dispatchers.IO) {
            alertStore.persist(
                RecoveryAlertStore.Alert(
                    vaultId = vaultId,
                    atEpochMs = System.currentTimeMillis(),
                    recoveryWritten = false
                )
            )
        }
        debugLog.error(TAG, "未能保全未落盘修改（告警已持久化=$persisted）")
        return LockOutcome.PreservationFailed(alertPersisted = persisted)
    }

    private companion object {
        const val TAG = "SessionRecovery"
        const val UNKNOWN_VAULT_ID = ""
    }
}
