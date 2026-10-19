package com.keepasskey.app.security

import com.keepasskey.app.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 前台闲置锁定单一计时任务；代次检查覆盖取消与设置读取/定时唤醒之间的竞态。 */
internal class ForegroundIdleLockTimer(
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val isInBackground: () -> Boolean,
    private val isLocked: () -> Boolean,
    private val lastInteractionMillis: () -> Long,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val triggerLock: suspend (String) -> Unit
) {
    private var generation = 0L
    private var job: Job? = null

    fun schedule() {
        val token = ++generation
        job?.cancel()
        job = scope.launch {
            val settings = settingsRepository.getSettings().first()
            if (!isCurrent(token) || !settings.autoLockForegroundEnabled) return@launch

            when (val decision = ForegroundIdleLockPolicy.decide(
                idleTimeoutSeconds = settings.autoLockForegroundTimeoutSeconds,
                lastInteractionMillis = lastInteractionMillis(),
                nowMillis = nowMillis()
            )) {
                is ForegroundIdleLockPolicy.IdleDecision.LockNow -> {
                    if (isCurrent(token)) triggerLock(decision.reason)
                }
                ForegroundIdleLockPolicy.IdleDecision.NeverIdleLock -> Unit
                ForegroundIdleLockPolicy.IdleDecision.KeepAlive -> {
                    val lockAt = ForegroundIdleLockPolicy.nextLockAtMillis(
                        settings.autoLockForegroundTimeoutSeconds,
                        lastInteractionMillis()
                    ) ?: return@launch
                    delay((lockAt - nowMillis()).coerceAtLeast(0L))

                    val latest = settingsRepository.getSettings().first()
                    if (!isCurrent(token) || !latest.autoLockForegroundEnabled) return@launch
                    when (val recheck = ForegroundIdleLockPolicy.decide(
                        idleTimeoutSeconds = latest.autoLockForegroundTimeoutSeconds,
                        lastInteractionMillis = lastInteractionMillis(),
                        nowMillis = nowMillis()
                    )) {
                        is ForegroundIdleLockPolicy.IdleDecision.LockNow -> {
                            if (isCurrent(token)) triggerLock(recheck.reason)
                        }
                        ForegroundIdleLockPolicy.IdleDecision.NeverIdleLock -> Unit
                        ForegroundIdleLockPolicy.IdleDecision.KeepAlive -> schedule()
                    }
                }
            }
        }
    }

    fun cancel() {
        generation++
        job?.cancel()
        job = null
    }

    private fun isCurrent(token: Long): Boolean =
        token == generation && !isInBackground() && !isLocked()
}
