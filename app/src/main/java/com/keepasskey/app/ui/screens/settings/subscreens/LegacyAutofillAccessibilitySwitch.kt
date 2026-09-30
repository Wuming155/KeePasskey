package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.keepasskey.app.R
import com.keepasskey.app.autofill.LegacyAccessibilitySystemState
import com.keepasskey.app.ui.components.SystemSettingsNavigation
import com.keepasskey.app.ui.screens.settings.SettingsUiState

/**
 * 旧版无障碍自动填充通道开关行（`ISSUE-P2-405`）。
 *
 * 行为契约（真机反馈收口）：
 * 1. 系统侧本应用无障碍服务**已启用**时，点开关直接写应用内开关；
 * 2. **未启用**时：只跳系统无障碍授权页 + 记 pending，**不写**应用内开关（杜绝假开）；
 * 3. 从系统页返回 `ON_RESUME`：按系统真实状态同步——未启用保持关闭，已启用才置开；
 * 4. 系统侧被关掉后，应用内开关强制回落。
 */
@Composable
internal fun LegacyAutofillAccessibilitySwitchRow(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // 用户点开开关后等待系统授权的意图标记
    var pendingEnable by remember { mutableStateOf(false) }
    val latestEnabled by rememberUpdatedState(enabled)
    val latestOnEnabledChange by rememberUpdatedState(onEnabledChange)
    val latestPending by rememberUpdatedState(pendingEnable)

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            val systemEnabled = LegacyAccessibilitySystemState.isEnabled(context)
            if (latestPending) {
                pendingEnable = false
                latestOnEnabledChange(systemEnabled)
            } else if (latestEnabled && !systemEnabled) {
                latestOnEnabledChange(false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AutofillSwitchRow(
        icon = Icons.Default.Accessibility,
        title = stringResource(R.string.autofill_legacy_accessibility_title),
        subtitle = stringResource(R.string.autofill_legacy_accessibility_sub),
        checked = enabled,
        onCheckedChange = { want ->
            if (want) {
                val systemEnabled = LegacyAccessibilitySystemState.isEnabled(context)
                if (systemEnabled) {
                    pendingEnable = false
                    onEnabledChange(true)
                } else {
                    pendingEnable = true
                    SystemSettingsNavigation.openLegacyAccessibilitySettings(context)
                }
            } else {
                pendingEnable = false
                onEnabledChange(false)
            }
        }
    )
}
