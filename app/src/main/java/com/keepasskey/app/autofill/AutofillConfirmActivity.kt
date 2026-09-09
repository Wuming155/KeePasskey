package com.keepasskey.app.autofill

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import com.keepasskey.app.R
import com.keepasskey.app.passkey.CredentialFillConfirmScreen
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricResult
import com.keepasskey.app.security.BiometricStatus
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 传统自动填充「已解锁分支」二次确认落地 Activity (TASK-11 / 审核报告 P2-24)。
 *
 * 安全动机：库解锁后 AutofillService 直接下发明文密码数据集，任何能在前台拉起自动填充
 * 的应用都可以在用户无感知的情况下点选候选完成填充。现要求每个已解锁数据集携带
 * setAuthentication 指向本 Activity——用户点选数据集时由系统拉起，仅当用户完成
 * 二次确认（优先系统级生物识别/锁屏凭据，无硬件时退化为受保护窗口内手动确认）后
 * 返回 RESULT_OK，自动填充框架才会将该数据集的值真正写入目标表单。
 *
 * 安全窗口加固（对齐 AutofillUnlockActivity）：FLAG_SECURE 防截屏录屏 +
 * setHideOverlayWindows 屏蔽悬浮窗覆盖（反 overlay 攻击）。
 */
@AndroidEntryPoint
class AutofillConfirmActivity : FragmentActivity() {

    @Inject
    lateinit var biometricAuthManager: BiometricAuthManager

    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        // 官方反 overlay 攻击加固：屏蔽其它应用悬浮窗覆盖确认窗口
        window.setHideOverlayWindows(true)

        val credentialTitle = intent.getStringExtra(EXTRA_CREDENTIAL_TITLE).orEmpty()
        val subtitle = getString(R.string.autofill_confirm_biometric_subtitle, credentialTitle)
        val manualHint = getString(R.string.autofill_confirm_manual_hint, credentialTitle)

        // 优先系统级认证（强生物识别 或 设备锁屏凭据，与快速解锁同一认证器集合）；
        // 设备无对应硬件/未录入时退化为受保护窗口内的手动确认（单次明确点选）
        when (biometricAuthManager.canAuthenticate(this, BiometricAuthManager.UNLOCK_AUTHENTICATORS)) {
            BiometricStatus.AVAILABLE -> {
                biometricAuthManager.authenticate(
                    activity = this,
                    title = getString(R.string.autofill_confirm_title),
                    subtitle = subtitle,
                    authenticators = BiometricAuthManager.UNLOCK_AUTHENTICATORS
                ) { result ->
                    when (result) {
                        is BiometricResult.Success -> completeAuthResult()
                        else -> finish()
                    }
                }
            }
            else -> {
                setContent {
                    CredentialFillConfirmScreen(
                        title = getString(R.string.autofill_confirm_title),
                        hint = manualHint,
                        confirmText = getString(R.string.autofill_confirm_ok),
                        cancelText = getString(R.string.autofill_confirm_cancel),
                        onConfirm = { completeAuthResult() },
                        onCancel = { finish() }
                    )
                }
            }
        }
    }

    private fun completeAuthResult() {
        if (completed) return
        completed = true
        // 官方认证数据集语义：RESULT_OK 后框架才会把该数据集的值写入目标表单
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        const val EXTRA_CREDENTIAL_TITLE = "com.keepasskey.app.autofill.EXTRA_CREDENTIAL_TITLE"
    }
}
