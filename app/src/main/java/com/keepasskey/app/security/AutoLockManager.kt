package com.keepasskey.app.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.database.session.DatabaseSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动锁定 (Auto-Lock) 熔断调度器。
 * 遵循安全防护硬约束：
 * 1. 监听 ProcessLifecycleOwner 进程退至后台事件，基于后台停留时长执行超时熔断锁定；
 * 2. 注册 Intent.ACTION_SCREEN_OFF 屏幕熄灭广播，设备熄屏瞬间立即触发熔断；
 * 3. 熔断时立即触发 DatabaseSession 内存密码擦除 (clear) 与会话锁定，通知 UI 导航强退至 UnlockScreen。
 */
@Singleton
class AutoLockManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val databaseSession: DatabaseSession,
    private val settingsRepository: SettingsRepository,
    private val debugLog: DebugLogBuffer
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private val _lockEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val lockEvents: SharedFlow<Unit> = _lockEvents.asSharedFlow()

    private var backgroundTimestamp: Long = 0L
    private var isInitialized = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                scope.launch {
                    val settings = settingsRepository.getSettings().first()
                    if (settings.lockWhenScreenOff || settings.autoLockBackground) {
                        triggerLock("设备屏幕熄灭")
                    }
                }
            }
        }
    }

    /**
     * 初始化监听进程生命周期与锁屏广播
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
        // 应用由后台重新切回前台
        if (backgroundTimestamp == 0L) return

        scope.launch {
            val settings = settingsRepository.getSettings().first()
            if (settings.autoLockBackground) {
                val elapsedMillis = System.currentTimeMillis() - backgroundTimestamp
                val timeoutMillis = settings.autoLockTimeoutSeconds * 1000L

                if (timeoutMillis <= 0 || elapsedMillis >= timeoutMillis) {
                    triggerLock("后台超时熔断 (已离开 ${elapsedMillis / 1000} 秒)")
                }
            }
        }
    }

    /**
     * 触发锁定：擦除内存数据库敏感状态，发出锁定事件。
     * H3 整改：锁库前若存在未落盘修改（DIRTY），先做一次 best-effort 补存——
     * 锁库会销毁内存树与主密码缓存，跳过补存将使未落盘修改永久丢失（对齐 KP2A 锁库守卫语义）。
     */
    fun triggerLock(reason: String = "安全锁定") {
        scope.launch {
            val state = databaseSession.state.value
            if (state == DatabaseSession.SessionState.DIRTY) {
                val saveResult = databaseSession.save()
                if (saveResult is com.keepasskey.core.result.KdbxResult.Failure) {
                    debugLog.error(TAG, "锁库前补存失败: ${saveResult.message}")
                }
            }
            databaseSession.lock()
            _isLocked.value = true
            _lockEvents.tryEmit(Unit)
        }
    }

    /**
     * 用户重新成功解锁后调用，重置锁定标记
     */
    fun onUnlockSuccess() {
        _isLocked.value = false
        backgroundTimestamp = 0L
    }

    companion object {
        private const val TAG = "AutoLockManager"
    }
}
