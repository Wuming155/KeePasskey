package com.keepasskey.app.security

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动锁定核心判定与会话熔断执行器（纯 Kotlin，无 Android 框架依赖）。
 *
 * ISSUE-P0-01 (ZT-01)：从 [AutoLockManager] 拆分的可单测内核。进程存在
 * AutofillUnlockActivity / CredentialUnlockActivity 两条不经 MainActivity 的独立冷启动入口，
 * 熄屏熔断与后台超时判定必须覆盖全进程所有入口；将判定与熔断逻辑同 Android 注册管道
 * （ProcessLifecycleOwner / 熄屏广播接收器）解耦后，本类仅依赖会话与设置仓库，
 * 可在 JVM 单测中直接驱动「熄屏 → 会话锁定」全链路（无需启动任何 Activity）。
 */
@Singleton
class AutoLockSessionGuard @Inject constructor(
    private val databaseSession: DatabaseSession,
    private val settingsRepository: SettingsRepository,
    private val debugLog: DebugLogBuffer
) {

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private val _lockEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val lockEvents: SharedFlow<Unit> = _lockEvents.asSharedFlow()

    /**
     * 熄屏事件熔断：熄屏立即锁（lockWhenScreenOff）或后台锁定（autoLockBackground）
     * 任一开关开启即触发。对应系统 ACTION_SCREEN_OFF 广播，覆盖全部冷启动入口。
     */
    suspend fun lockOnScreenOff() {
        val settings = settingsRepository.getSettings().first()
        if (settings.lockWhenScreenOff || settings.autoLockBackground) {
            triggerLock("设备屏幕熄灭")
        }
    }

    /**
     * 后台停留超时熔断：应用由后台切回前台时按「后台停留时长 ≥ 配置超时」判定。
     * [backgroundTimestamp] 为 0 表示进程从未退至后台，直接放行；
     * [now] 为可注入的当前时刻，供 JVM 单测消除时间依赖。
     *
     * 注意：超时 ≤ 0 即锁定的「永不」档语义偏差由 ZT-18（ISSUE-P2-13）独立跟踪整改，本类保持既往行为。
     */
    suspend fun lockOnBackgroundResume(
        backgroundTimestamp: Long,
        now: Long = System.currentTimeMillis()
    ) {
        if (backgroundTimestamp == 0L) return
        val settings = settingsRepository.getSettings().first()
        if (!settings.autoLockBackground) return

        val elapsedMillis = now - backgroundTimestamp
        val timeoutMillis = settings.autoLockTimeoutSeconds * 1000L

        if (timeoutMillis <= 0 || elapsedMillis >= timeoutMillis) {
            triggerLock("后台超时熔断 (已离开 ${elapsedMillis / 1000} 秒)")
        }
    }

    /**
     * 触发锁定：擦除内存数据库敏感状态，发出锁定事件。
     * H3 整改：锁库前若存在未落盘修改（DIRTY），先做一次 best-effort 补存——
     * 锁库会销毁内存树与主密码缓存，跳过补存将使未落盘修改永久丢失（对齐 KP2A 锁库守卫语义）。
     */
    suspend fun triggerLock(reason: String = "安全锁定") {
        val state = databaseSession.state.value
        if (state == DatabaseSession.SessionState.DIRTY) {
            val saveResult = databaseSession.save()
            if (saveResult is KdbxResult.Failure) {
                debugLog.error(TAG, "锁库前补存失败: ${saveResult.message}")
            }
        }
        databaseSession.lock()
        _isLocked.value = true
        _lockEvents.tryEmit(Unit)
    }

    /**
     * 用户重新成功解锁后调用，重置锁定标记
     */
    fun onUnlockSuccess() {
        _isLocked.value = false
    }

    companion object {
        private const val TAG = "AutoLockSessionGuard"
    }
}
