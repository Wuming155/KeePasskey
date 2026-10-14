package com.keepasskey.app.ui.screens.settings

import androidx.fragment.app.FragmentActivity
import com.keepasskey.app.coroutines.guardedScope
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.security.AutoLockSessionGuard
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricCredentialStorage
import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.database.session.SessionCredentialRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 应用级轮换任务所有者；页面销毁只停止状态订阅，不终止已经受理的事务。 */
@Singleton
class CredentialRotationRuntime @Inject constructor(private val lockGuard: AutoLockSessionGuard) {
    val scope = guardedScope(Dispatchers.Main.immediate, "CredentialRotation")
    internal val state = MutableStateFlow(MasterKeyChangeTaskState())

    /** 挂锁在数据库写入与必要收尾期间成对持有，异常/取消也必须释放。 */
    suspend fun <T> run(block: suspend () -> T): T {
        lockGuard.beginLongTask()
        return try {
            block()
        } finally {
            lockGuard.endLongTask()
        }
    }
}

/** 提交后的同步事实与封印维护由应用依赖完成，不读取已销毁 ViewModel 的活动库跟踪。 */
@Singleton
class CredentialRotationCompletion @Inject constructor(
    private val settings: SettingsRepository,
    private val auth: BiometricAuthManager,
    private val storage: BiometricCredentialStorage,
    internal val session: DatabaseSession,
    private val sync: SyncCoordinator,
    private val strings: StringsProvider,
    private val debugLog: DebugLogBuffer
) {
    fun invalidate(databaseId: String?) {
        databaseId?.let { storage.clearCredential(it) }
    }

    fun captureTarget(): String? = sync.captureCredentialRotationTargetSnapshot()

    suspend fun complete(request: SessionCredentialRequest, databaseId: String?, activity: FragmentActivity?, password: CharArray?, syncTarget: String?): Boolean {
        val ticket = request.committedTicket ?: return false
        // 同步登记发生前先清陈旧封印，任何后续失败/取消/宿主消失均只留下待重新登记状态。
        var hadSeal = false
        withContext(NonCancellable) {
            withTimeoutOrNull(MARK_TIMEOUT_MILLIS) {
                session.useCredentialTicket(ticket) {
                    hadSeal = databaseId?.let { storage.hasEncryptedCredential(it) } == true
                    databaseId?.let(storage::clearCredential)
                }
            }
        }
        var markSucceeded = syncTarget == null
        try {
            // 本地持久标记的有界收尾；这里不触发网络同步，也不包生物识别等待。
            withContext(NonCancellable) {
                if (syncTarget != null) {
                    markSucceeded = withTimeoutOrNull(MARK_TIMEOUT_MILLIS) {
                        sync.markCredentialRotationTarget(syncTarget)
                    } ?: false
                }
            }
        } finally {
            if (databaseId != null && hadSeal && session.isCredentialTicketCurrent(ticket) &&
                activity != null && !activity.isDestroyed && !activity.isFinishing) {
                val coordinator = BiometricResealCoordinator(
                    settingsRepository = settings,
                    activeDbId = { databaseId },
                    sessionPasswordChars = { if (session.isCredentialTicketCurrent(ticket)) session.passwordSnapshot() else null },
                    biometricAuthManager = auth,
                    biometricCredentialStorage = storage,
                    strings = strings,
                    debugLog = debugLog,
                    publishSeal = { publish -> session.useCredentialTicket(ticket) { publish() } }
                )
                withTimeoutOrNull(RESEAL_TIMEOUT_MILLIS) {
                    coordinator.resealAfterMasterKeyChange(activity, password, allowInitialSeal = true)
                }
            }
        }
        return markSucceeded
    }

    private companion object {
        const val MARK_TIMEOUT_MILLIS = 10_000L
        const val RESEAL_TIMEOUT_MILLIS = 65_000L
    }
}
