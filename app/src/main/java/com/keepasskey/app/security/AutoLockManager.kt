package com.keepasskey.app.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动锁定 (Auto-Lock) Android 注册管道。
 * 遵循安全防护硬约束：
 * 1. 监听 ProcessLifecycleOwner 进程退至后台事件，基于后台停留时长执行超时熔断锁定；
 * 2. 注册 Intent.ACTION_SCREEN_OFF 屏幕熄灭广播，设备熄屏瞬间立即触发熔断；
 * 3. 熔断时立即触发 DatabaseSession 内存密码擦除 (clear) 与会话锁定，通知 UI 导航强退至 UnlockScreen。
 *
 * ISSUE-P0-01 (ZT-01)：判定与会话熔断内核拆分至 [AutoLockSessionGuard]（纯 Kotlin 可单测），
 * 本类仅保留 Android 侧注册管道（生命周期观察者 + 熄屏广播）并对内核做 API 委托。
 */
@Singleton
class AutoLockManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionGuard: AutoLockSessionGuard
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val isLocked: StateFlow<Boolean> get() = sessionGuard.isLocked

    val lockEvents: SharedFlow<Unit> get() = sessionGuard.lockEvents

    private var backgroundTimestamp: Long = 0L
    private var isInitialized = false

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
    }

    override fun onStop(owner: LifecycleOwner) {
        // 应用整体退至后台
        backgroundTimestamp = System.currentTimeMillis()
    }

    override fun onStart(owner: LifecycleOwner) {
        // 应用由后台重新切回前台（backgroundTimestamp == 0 表示从未退至后台，内核内直接放行）
        scope.launch {
            sessionGuard.lockOnBackgroundResume(backgroundTimestamp)
        }
    }

    /**
     * 触发锁定：擦除内存数据库敏感状态，发出锁定事件（委托内核执行）。
     */
    fun triggerLock(reason: String = "安全锁定") {
        scope.launch {
            sessionGuard.triggerLock(reason)
        }
    }

    /**
     * 用户重新成功解锁后调用，重置锁定标记
     */
    fun onUnlockSuccess() {
        backgroundTimestamp = 0L
        sessionGuard.onUnlockSuccess()
    }
}
