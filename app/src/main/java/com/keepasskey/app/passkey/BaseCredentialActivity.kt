package com.keepasskey.app.passkey

import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity

/**
 * 凭据交互 Activity 公共抽象基类。
 * 强制应用 FLAG_SECURE 保护窗口，杜绝凭据明文被截屏、录屏或在多任务概览中泄露；
 * 并屏蔽第三方应用悬浮窗覆盖（HIDE_OVERLAY_WINDOWS），防御 overlay 点击劫持。
 *
 * 以 [FragmentActivity] 为基类：ISSUE-P0-02 起填充类 Activity 需在内部拉起系统级
 * BiometricPrompt（[androidx.biometric.BiometricPrompt] 要求 FragmentActivity 宿主），
 * 使「下发前用户验证」在同一受保护窗口内闭环完成。
 */
abstract class BaseCredentialActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        // 官方反 overlay 攻击加固：屏蔽其它应用悬浮窗覆盖凭据窗口
        window.setHideOverlayWindows(true)
    }

    /**
     * 统一失败收尾：回传 RESULT_CANCELED 并结束。
     * ISSUE-P1-10：收尾不携带任何消息文本——失败原因只允许经 [com.keepasskey.core.log.AppLog]
     * 脱敏记录，绝不透传异常 message（防敏感标识外泄）。
     */
    protected fun failAndFinish() {
        setResult(RESULT_CANCELED)
        finish()
    }
}
