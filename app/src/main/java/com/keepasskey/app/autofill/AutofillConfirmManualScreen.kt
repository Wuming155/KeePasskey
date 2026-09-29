package com.keepasskey.app.autofill

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.keepasskey.app.R
import com.keepasskey.app.passkey.CredentialFillConfirmScreen
import com.keepasskey.app.security.ApplyObscuredTouchFilter

/**
 * 确认页受保护窗口内的手动确认界面（ISSUE-P1-24 AC② / ISSUE-P3-360 AC④）。
 *
 * 自 [AutofillConfirmActivity.showManualConfirm] 纯结构性拆出（规模闸门回压）：
 * 无可用认证器 / 认证绑定不可用时的 fail-closed 退化路径。
 * 首次出现的调用方须显式勾选「记住此应用」授权后方可确认。
 */
@Composable
fun AutofillConfirmManualScreen(
    manualHint: String,
    attribution: AutofillCallerAttribution?,
    callerTrustStore: AutofillCallerTrustStore,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    localizedContext: Context
) {
    // ISSUE-P2-09：Compose 侧遮挡触摸过滤（点击劫持防护）
    ApplyObscuredTouchFilter()
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedContext.resources.configuration
    ) {
        // ISSUE-P1-24 AC②：首次出现的目标在确认前必须显式勾选「记住此应用」授权
        var trustChecked by remember { mutableStateOf(false) }
        val requiresExplicitAuthorization = attribution?.firstOccurrence == true
        val attributionContent: (@Composable () -> Unit)? = attribution?.let { attr ->
            {
                AutofillCallerAttributionBlock(
                    attribution = attr,
                    showTrustCheckbox = requiresExplicitAuthorization,
                    trustChecked = trustChecked,
                    onTrustCheckedChange = { checked ->
                        trustChecked = checked
                        if (checked) {
                            callerTrustStore.trust(attr.packageName, attr.certSha256Hex)
                        } else {
                            callerTrustStore.untrust(attr.packageName, attr.certSha256Hex)
                        }
                    }
                )
            }
        }
        // ISSUE-P3-360 AC④（局部）：确认钮禁用时在 hint 位就地给出原因——
        // 首现调用方未勾选授权前 confirmEnabled=false，此前界面无任何解释；
        // 勾选状态翻转即重组，原因文案随之消失（与按钮门控同一条件，不会漂移）
        val confirmEnabled = !requiresExplicitAuthorization || trustChecked
        CredentialFillConfirmScreen(
            title = localizedContext.getString(R.string.autofill_confirm_title),
            hint = if (confirmEnabled) {
                manualHint
            } else {
                "$manualHint\n${localizedContext.getString(R.string.autofill_confirm_disabled_reason)}"
            },
            confirmText = localizedContext.getString(R.string.autofill_confirm_ok),
            cancelText = localizedContext.getString(R.string.autofill_confirm_cancel),
            confirmEnabled = confirmEnabled,
            attributionContent = attributionContent,
            onConfirm = onConfirm,
            onCancel = onCancel
        )
    }
}
