package com.keepasskey.app.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.keepasskey.app.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动锁定 (Auto-Lock) Android 注册管道。
 * 遵循安全防护硬约束：
 * 1. 监听 ProcessLifecycleOwner 进程退至后台事件，按配置的自动锁定超时执行熔断；
 * 2. 注册 Intent.ACTION_SCREEN_OFF 屏幕熄灭广播，设备熄屏瞬间立即触发熔断；
 * 3. 熔断时立即触发 DatabaseSession 内存密码擦除 (clear) 与会话锁定，通知 UI 导航强退至 UnlockScreen。
 *
 * ISSUE-P0-01 (ZT-01)：判定与会话熔断内核拆分至 [AutoLockSessionGuard]（纯 Kotlin 可单测），
 * 本类仅保留 Android 侧注册管道（生命周期观察者 + 熄屏广播）并对内核做 API 委托。
 *
 * ISSUE-P2-13 (ZT-19)：修复「后台期间无定时器」缺陷——onStop 时按当前超时值调度一个延迟锁定
 * 任务，到点即锁（而非等回前台才判）；onStart / 解锁成功 / 超时设置变更时取消或重排该任务。
 * 定时器统一绑定本类受控 [scope]（SupervisorJob + Main，进程级单例生命周期），禁止裸 GlobalScope；
 * 「永不」档（-1）解析为 null，绝不启动定时器。
 */
@Singleton
class AutoLockManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionGuard: AutoLockSessionGuard,
    private val settingsRepository: SettingsRepository
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val isLocked: StateFlow<Boolean> get() = sessionGuard.isLocked

    val lockEvents: SharedFlow<Unit> get() = sessionGuard.lockEvents

    private var backgroundTimestamp: Long = 0L
    private var isInBackground: Boolean = false
    private var isInitialized = false

    /** 后台延迟锁定任务；到点即锁，取消路径覆盖 onStart / onUnlockSuccess / triggerLock / 设置变更 */
    private var backgroundLockJob: Job? = null

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                scope.launch {
                    sessionGuard.lockOnScreenOff()
                }
            }
        }
    }

    /**
     * 初始化监听进程生命周期与锁屏广播。
     *
     * ISSUE-P0-01 (ZT-01)：调用点下沉至 MainApplication.onCreate()（进程级唯一冷启动点）——
     * 应用存在 AutofillUnlockActivity / CredentialUnlockActivity 两条不经 MainActivity 的
     * 独立冷启动入口，守护必须在进程创建时注册，保证任意入口冷启动后
     * 熄屏熔断与后台超时锁定均全程生效。幂等守卫保留，重复调用无害。
     *
     * ISSUE-P2-13 (ZT-19)：同时观察设置流，后台期间超时值（或后台锁定开关）变更时响应式重排定时器。
     */
    fun initialize() {
        if (isInitialized) return
        isInitialized = true

        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        // P0 整改：官方自 androidx.core 1.9.0 起要求 context-registered receiver 显式声明导出标志。
        // ACTION_SCREEN_OFF 属「仅本应用 + 系统 UID」来源，按官方规则标记 RECEIVER_NOT_EXPORTED，
        // 杜绝其它应用向本接收器投递伪造熄屏广播触发主动锁库（拒绝服务 / 会话劫持面）。
        ContextCompat.registerReceiver(
            context,
            screenOffReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        scope.launch {
            settingsRepository.getSettings()
                .map { AutoLockSchedule(it.autoLockBackground, it.autoLockTimeoutSeconds) }
                .distinctUntilChanged()
                .collect {
                    // 仅后台期间才需要按新超时值重排；前台态由回前台补偿判定负责
                    if (isInBackground) scheduleBackgroundLock()
                }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        // 应用整体退至后台：记录起始时刻并按当前超时值启动延迟锁定任务
        isInBackground = true
        backgroundTimestamp = System.currentTimeMillis()
        scheduleBackgroundLock()
    }

    override fun onStart(owner: LifecycleOwner) {
        // 应用由后台重新切回前台：先撤销后台定时器，再做「已离开时长」补偿判定
        // （backgroundTimestamp == 0 表示从未退至后台，内核内直接放行）
        isInBackground = false
        cancelBackgroundLock()
        scope.launch {
            sessionGuard.lockOnBackgroundResume(backgroundTimestamp)
        }
    }

    /**
     * 按当前超时设置调度后台延迟锁定任务。
     * 「永不」档（-1）不启动任何定时器；「立即」档（0）退至后台即锁；> 0 档到点即锁。
     * 重复调用先取消旧任务，保证任意时刻至多一个存活定时器。
     */
    private fun scheduleBackgroundLock() {
        cancelBackgroundLock()
        backgroundLockJob = scope.launch {
            val settings = settingsRepository.getSettings().first()
            if (!settings.autoLockBackground) return@launch
            val delayMillis = AutoLockTimeoutPolicy.delayMillis(settings.autoLockTimeoutSeconds)
                ?: return@launch // 「永不」档：禁止启动定时器
            delay(delayMillis)
            // 到点即锁：无需等待用户切回前台
            backgroundTimestamp = 0L
            sessionGuard.triggerLock("后台超时自动锁定 (${settings.autoLockTimeoutSeconds} 秒)")
        }
    }

    private fun cancelBackgroundLock() {
        backgroundLockJob?.cancel()
        backgroundLockJob = null
    }

    /**
     * 触发锁定：擦除内存数据库敏感状态，发出锁定事件（委托内核执行）。
     */
    fun triggerLock(reason: String = "安全锁定") {
        cancelBackgroundLock()
        scope.launch {
            sessionGuard.triggerLock(reason)
        }
    }

    /**
     * 用户重新成功解锁后调用，重置锁定标记与后台定时器
     */
    fun onUnlockSuccess() {
        backgroundTimestamp = 0L
        cancelBackgroundLock()
        sessionGuard.onUnlockSuccess()
    }

    /** 设置流观察用的调度键：仅在「后台锁定开关 + 超时秒数」变化时触发重排 */
    private data class AutoLockSchedule(
        val backgroundLockEnabled: Boolean,
        val timeoutSeconds: Int
    )
}
