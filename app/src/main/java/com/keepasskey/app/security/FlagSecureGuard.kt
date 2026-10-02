package com.keepasskey.app.security

import android.app.Activity
import android.view.WindowManager
import com.keepasskey.app.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 窗口防截屏与多任务侧漏守卫 (FLAG_SECURE)（ISSUE-P2-09 / ZT-14）。
 *
 * 裁决内核见纯函数 [FlagSecurePolicy]：**FLAG_SECURE 只由用户开关决定**
 * （2026-10-02 用户裁决——原「锁定态无条件强制遮蔽」使关闭开关后解锁页仍无法截屏）。
 *
 * 同时施加**窗口级遮挡触摸过滤**（`setFilterTouchesWhenObscured`），
 * 阻止被其它窗口遮挡时的触摸注入（点击劫持）。
 *
 * UI 交互（关闭开关时弹出风险确认对话框）由设置页承载：确认后才写入偏好。
 */
@Singleton
class FlagSecureGuard @Inject constructor(
    private val settingsRepository: SettingsRepository
) {

    /**
     * 将守卫与目标 Activity 窗口及协程生命周期绑定。
     *
     * 首帧收口：attach 于 onCreate 调用，先**无条件强制遮蔽**一次，再进入 Flow 订阅按策略修正——
     * 彻底消除「onCreate → 首次 Flow 发射」间 Recents 预览截获明文的空窗（fail-closed）；
     * 用户开关为关闭时由 Flow 真实解除。
     */
    fun attach(activity: Activity, coroutineScope: CoroutineScope) {
        // 首帧强制遮蔽（保守方向）；若用户开关为关闭，随后由 Flow 解除
        applyFlagSecure(activity, true)
        // 窗口级遮挡触摸过滤（点击劫持防护）
        applyObscuredTouchFilter(activity)

        coroutineScope.launch {
            settingsRepository.getSettings()
                .map { FlagSecurePolicy.shouldApplySecure(it.flagSecureEnabled) }
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
}
