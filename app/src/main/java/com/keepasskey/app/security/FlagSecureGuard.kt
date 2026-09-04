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
 * 窗口防截屏与多任务侧漏守卫 (FLAG_SECURE)。
 * 响应式监听 UserSettings.flagSecureEnabled：
 * - 启用时动态注入 WindowManager.LayoutParams.FLAG_SECURE，阻断系统截屏、录屏投屏与多任务卡片预览泄露凭据明文；
 * - 禁用时清除 FLAG_SECURE 标志，允许截屏与常规展示。
 */
@Singleton
class FlagSecureGuard @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    /**
     * 将守卫与目标 Activity 窗口及协程生命周期绑定
     */
    fun attach(activity: Activity, coroutineScope: CoroutineScope) {
        coroutineScope.launch {
            settingsRepository.getSettings()
                .map { it.flagSecureEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    applyFlagSecure(activity, enabled)
                }
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
}
