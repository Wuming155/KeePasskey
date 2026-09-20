package com.keepasskey.app.passkey

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import com.keepasskey.app.R
import com.keepasskey.app.security.ApplyObscuredTouchFilter

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

    /** 拒绝页是否**已呈现**：防重复渲染致后一次覆盖前一次的原因（ISSUE-P3-221） */
    private var rejectionPresented = false

    /** 是否**已收尾**：防重复 `finish()`（ISSUE-P3-221） */
    private var rejectionSettled = false

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

    /**
     * 幂等收尾（ISSUE-P3-221）：首次调用执行 [failAndFinish] 并返回 `true`，
     * 之后一律返回 `false`，避免重复收尾。
     */
    private fun settleRejection(): Boolean {
        if (rejectionSettled) return false
        rejectionSettled = true
        failAndFinish()
        return true
    }

    /**
     * fail-closed 拒绝收尾（ISSUE-P2-220）：回传契约与 [failAndFinish] 完全一致
     * （仍是 `RESULT_CANCELED`，绝不谎报成功），但**先在受保护窗口内呈现拒绝原因**，
     * 用户确认后才收尾——整改前各拒绝分支直接静默 `finish()`，用户视角是「点了继续就断」，
     * 无法区分「功能坏了」与「被安全门控拒绝」。
     *
     * 文案一律取 [reason] 携带的预定义字符串资源（ISSUE-P1-10），不得插入 rpId / 包名等。
     * 必须在主线程调用（内部 [setContent]）。
     *
     * **ISSUE-P3-221**：调用方可在确认 [reason] 确有用户可执行的解法（
     * [CredentialRejectionAction.forReason] 非空）且调用方资格成立后，随 [remedy] 提供
     * **就地补救**（当前窗口内完成授权，不跳转任何界面）。**本次**被拒请求仍以 `RESULT_CANCELED`
     * 结束：授权只影响**下一次**发起（产品裁决 PD-12）。[remedy] 为 `null` 时布局与
     * ISSUE-P2-220 原状逐字一致。
     */
    internal fun rejectAndFinish(
        reason: CredentialRejectionReason,
        remedy: CredentialRejectionRemedy? = null
    ) {
        if (rejectionPresented) return
        rejectionPresented = true
        setContent {
            // 遮挡触摸过滤（ISSUE-P2-09 / P3-12）
            ApplyObscuredTouchFilter()
            CredentialRejectionScreen(
                title = getString(R.string.cred_reject_title),
                message = getString(reason.messageRes),
                confirmText = getString(
                    if (remedy != null) R.string.cred_reject_exit else R.string.cred_reject_confirm
                ),
                onConfirm = { settleRejection() },
                remedy = remedy
            )
        }
    }
}