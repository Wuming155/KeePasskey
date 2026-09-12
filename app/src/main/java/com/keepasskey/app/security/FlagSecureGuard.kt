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
 * 窗口防截屏与多任务侧漏守卫 (FLAG_SECURE)（ISSUE-P2-09 / ZT-14；2026-09-12 语义修订）。
 *
 * 裁决内核见纯函数 [FlagSecurePolicy]（**开关即生效**模型，JVM 可测）：
 * - 会话 CLOSED / LOCKED：无条件强制遮蔽（主密码输入 / Recents 预览绝不泄露）；
 * - 会话解锁：用户开关开启 → 强制遮蔽；开关关闭 → **真实解除**。
 *   （原「临时豁免」模型在开关关闭后仍恒强制，且 UI 风险确认从未接通豁免入口，
 *   构成「关闭无效」的假开关——用户报告后经裁决改为开关即生效。）
 *
 * 同时施加**窗口级遮挡触摸过滤**（`setFilterTouchesWhenObscured`），
 * 阻止被其它窗口遮挡时的触摸注入（点击劫持）。
 *
 * UI 交互（关闭开关时弹出风险确认对话框）由设置页承载：确认后才写入偏好。
 */
@Singleton
class FlagSecureGuard @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val databaseSession: DatabaseSession
) {

    /**
     * 将守卫与目标 Activity 窗口及协程生命周期绑定。
     *
     * 首帧收口：attach 于 onCreate 调用，先**无条件强制遮蔽**一次，再进入 Flow 订阅按策略修正——
     * 彻底消除「onCreate → 首次 Flow 发射」间 Recents 预览截获明文的空窗（fail-closed）。
     */
    fun attach(activity: Activity, coroutineScope: CoroutineScope) {
        // 首帧强制遮蔽（保守方向）；若用户开关为关闭，随后由 Flow 解除
        applyFlagSecure(activity, true)
        // 窗口级遮挡触摸过滤（点击劫持防护）
        applyObscuredTouchFilter(activity)

        coroutineScope.launch {
            combine(
                settingsRepository.getSettings().map { it.flagSecureEnabled },
                databaseSession.state.map { isSessionLocked(it) }
            ) { userEnabled, sessionLocked ->
                FlagSecurePolicy.shouldApplySecure(
                    sessionLocked = sessionLocked,
                    userEnabled = userEnabled
                )
            }
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
     * 窗口级遮挡触摸过滤：窗口被其它窗口部分/完全遮挡时丢弃触摸事件（点击劫持防护）。
     *
     * 正确入口是 **View 层** [android.view.View.setFilterTouchesWhenObscured]（compileSdk 37
     * 的 android.jar 中 `android.view.Window` 并**无**该方法）——对 `decorView` 开启后，
     * 其整棵视图子树在遮挡态下统一丢弃触摸。
     */
    fun applyObscuredTouchFilter(activity: Activity) {
        activity.window.decorView.filterTouchesWhenObscured = true
    }

    /**
     * 会话未解锁（从未打开 / 已锁定）即视为需要强制遮蔽
     */
    private fun isSessionLocked(state: DatabaseSession.SessionState): Boolean =
        state == DatabaseSession.SessionState.CLOSED || state == DatabaseSession.SessionState.LOCKED
}
