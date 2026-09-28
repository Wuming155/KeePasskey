package com.keepasskey.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * ISSUE-P3-358 AC①：触觉反馈开关的全站收口点。
 *
 * 设置项 `hapticFeedbackEnabled`（`SettingsUiState`）此前只有开关 UI 自己读写，
 * 9+ 处 `performHapticFeedback` 全部裸调——用户关闭「触觉反馈」后仍震动。
 * 生产环境由 `KeePasskeyApp` 的 `CompositionLocalProvider` 注入真实设置值；
 * 缺省 `true` 保证 Preview / 局部组合在无提供者时维持既有默认行为。
 */
val HapticsEnabled = staticCompositionLocalOf { true }

/**
 * 返回一个受 [HapticsEnabled] 开关约束的触觉反馈回调（ISSUE-P3-358 AC① 唯一入口）。
 *
 * 点击回调（`onClick` lambda）不是组合作用域，无法在其中直接读 CompositionLocal，
 * 故在组合期把「开关值 + 平台 HapticFeedback」捕获为闭包——开关切换后回调随重组更新。
 * 所有 `performHapticFeedback` 调用点一律经此收口，禁止再裸调 `LocalHapticFeedback`。
 */
@Composable
fun rememberMaybeHaptic(): (HapticFeedbackType) -> Unit {
    val haptic = LocalHapticFeedback.current
    val enabled = HapticsEnabled.current
    return remember(haptic, enabled) {
        { type -> if (enabled) haptic.performHapticFeedback(type) }
    }
}
