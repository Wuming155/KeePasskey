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
     * ISSUE-P2-13 (ZT-18) 语义修正：先判「永不」档（autoLockTimeoutSeconds < 0）直接放行，
     * 修复旧实现 `timeoutMillis <= 0 → 立即锁定` 与 UI「从不」承诺完全相反的问题。
     * 三档语义由 [AutoLockTimeoutPolicy] 统一裁决；「后台到点即锁」由 [AutoLockManager]
     * 在 onStop 时调度的延迟任务负责，本方法只承担回前台的补偿判定。
     */
    suspend fun lockOnBackgroundResume(
        backgroundTimestamp: Long,
        now: Long = System.currentTimeMillis()
    ) {
        if (backgroundTimestamp == 0L) return
        val settings = settingsRepository.getSettings().first()
        if (!settings.autoLockBackground) return

        when (AutoLockTimeoutPolicy.modeOf(settings.autoLockTimeoutSeconds)) {
            // 「永不」档：真正的从不锁定，不得因 timeoutMillis <= 0 误判为立即锁定
            AutoLockTimeoutMode.NEVER -> return
            AutoLockTimeoutMode.IMMEDIATE -> triggerLock("后台立即锁定")
            AutoLockTimeoutMode.AFTER_SECONDS -> {
                val elapsedMillis = now - backgroundTimestamp
                if (AutoLockTimeoutPolicy.isExpired(settings.autoLockTimeoutSeconds, elapsedMillis)) {
                    val elapsedSeconds = elapsedMillis / AutoLockTimeoutPolicy.MILLIS_PER_SECOND
                    triggerLock("后台超时熔断 (已离开 ${elapsedSeconds} 秒)")
                }
            }
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

/**
 * ISSUE-P2-13 (ZT-18)：自动锁定超时的三档语义。
 *
 * 与设置页（SecuritySettingsScreen 的自动锁定倒计时选项）严格对齐：
 * - [NEVER]：`-1`（UI「从不」），真正的永不自动锁定——既不判超时，也不得启动任何定时器；
 * - [IMMEDIATE]：`0`（UI「立即锁定」），退至后台即视为超时；
 * - [AFTER_SECONDS]：`> 0`，退至后台满 N 秒后锁定。
 *
 * 旧实现将 `timeoutMillis <= 0` 一并判为立即锁定，导致「从不」档与 UI 承诺相反（ZT-18）。
 */
enum class AutoLockTimeoutMode {
    NEVER,
    IMMEDIATE,
    AFTER_SECONDS
}

/**
 * 自动锁定超时纯内核（无 Android 依赖，可在 JVM 单测中直接驱动三档语义）。
 */
object AutoLockTimeoutPolicy {

    /** 「永不」档取值：与设置页 sec_lock_never（-1）一致 */
    const val NEVER_SECONDS: Int = -1

    /** 「立即」档取值：与设置页 sec_lock_now（0）一致 */
    const val IMMEDIATE_SECONDS: Int = 0

    /** 秒 → 毫秒换算基准，避免在多处复制 1000 字面量 */
    const val MILLIS_PER_SECOND: Long = 1000L

    /** 将设置中的秒数映射为明确的三档语义 */
    fun modeOf(seconds: Int): AutoLockTimeoutMode = when {
        seconds < 0 -> AutoLockTimeoutMode.NEVER
        seconds == 0 -> AutoLockTimeoutMode.IMMEDIATE
        else -> AutoLockTimeoutMode.AFTER_SECONDS
    }

    /**
     * 后台延迟锁定任务应等待的毫秒数。
     * [AutoLockTimeoutMode.NEVER] 返回 null，调用方据此禁止启动定时器；
     * [AutoLockTimeoutMode.IMMEDIATE] 返回 0，表示退至后台即锁定。
     */
    fun delayMillis(seconds: Int): Long? = when (modeOf(seconds)) {
        AutoLockTimeoutMode.NEVER -> null
        AutoLockTimeoutMode.IMMEDIATE -> 0L
        AutoLockTimeoutMode.AFTER_SECONDS -> seconds * MILLIS_PER_SECOND
    }

    /** 退至后台 [elapsedMillis] 毫秒后是否已达超时；「永不」档恒为 false */
    fun isExpired(seconds: Int, elapsedMillis: Long): Boolean = when (modeOf(seconds)) {
        AutoLockTimeoutMode.NEVER -> false
        AutoLockTimeoutMode.IMMEDIATE -> true
        AutoLockTimeoutMode.AFTER_SECONDS -> elapsedMillis >= seconds * MILLIS_PER_SECOND
    }
}
