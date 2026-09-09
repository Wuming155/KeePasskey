package com.keepasskey.app.security

import android.app.Activity
import android.view.WindowManager
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 窗口防截屏与多任务侧漏守卫 (FLAG_SECURE)。
 *
 * 遮蔽判定取「用户开关 ∨ 会话锁定态」并集（纵深防御）：
 * - 会话处于 [DatabaseSession.SessionState.CLOSED] / [DatabaseSession.SessionState.LOCKED]
 *   （冷启动、锁库后）时无条件强制遮蔽——主密码输入界面与锁定态 Recents 任务预览
 *   绝不允许落入截屏、录屏投屏或后台快照；
 * - 会话解锁（OPENED / DIRTY）后交还用户开关（UserSettings.flagSecureEnabled）裁决；
 * - 响应式监听设置与会话状态任一变化，实时同步窗口标志。
 */
@Singleton
class FlagSecureGuard @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val databaseSession: DatabaseSession
) {
    /**
     * 将守卫与目标 Activity 窗口及协程生命周期绑定。
     *
     * 首帧空窗收口：attach 于 onCreate 调用，先按当前会话状态同步应用一次遮蔽
     * （冷启动会话必为 CLOSED/LOCKED → 强制遮蔽无条件成立），再进入 Flow 订阅
     * 按「用户开关 ∨ 锁定态」并集修正——消除「onCreate → 首次 Flow 发射」间
     * Recents 预览截获明文的理论空窗。
     */
    fun attach(activity: Activity, coroutineScope: CoroutineScope) {
        // 同步首帧遮蔽：以真实会话状态为准，冷启动必为锁定态
        applyFlagSecure(activity, isSessionLocked(databaseSession.state.value))

        coroutineScope.launch {
            combine(
                settingsRepository.getSettings().map { it.flagSecureEnabled },
                databaseSession.state.map { isSessionLocked(it) }
            ) { userEnabled, sessionLocked -> userEnabled || sessionLocked }
                .distinctUntilChanged()
                .collect { enabled -> applyFlagSecure(activity, enabled) }
        }
    }

    /**
     * 直接对目标窗口设置或清除 FLAG_SECURE
     */
    fun applyFlagSecure(activity: Activity, enabled: Boolean) {
        if (enabled) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /**
     * 会话未解锁（从未打开 / 已锁定）即视为需要强制遮蔽
     */
    private fun isSessionLocked(state: DatabaseSession.SessionState): Boolean =
        state == DatabaseSession.SessionState.CLOSED || state == DatabaseSession.SessionState.LOCKED
}
