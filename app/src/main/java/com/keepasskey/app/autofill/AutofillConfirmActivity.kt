package com.keepasskey.app.autofill

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.notification.TotpNotificationPublisher
import com.keepasskey.app.passkey.CredentialFillConfirmScreen
import com.keepasskey.app.security.ApplyObscuredTouchFilter
import com.keepasskey.app.security.AutofillAuthBindingPolicy
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricResult
import com.keepasskey.app.security.BiometricStatus
import com.keepasskey.app.security.ClipboardSecurityManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
 *
 * ISSUE-P3-03 (43b)：确认通过后按 `autofillCopyTotp` 偏好把该条目的 TOTP 动态码
 * 写入受保护剪贴板（`EXTRA_IS_SENSITIVE` + 定时自动擦除），兑现设置页
 * 「填充后自动将 TOTP 动态码复制到剪贴板」承诺。
 *
 * ISSUE-P3-18：同一落点按 `autofillShowTotpNotification` 偏好发出验证码通知——本类是
 * 「自动填充命中并真正下发凭据」的落点，通知只含验证码与剩余秒数，不含任何条目标识。
 */
@AndroidEntryPoint
class AutofillConfirmActivity : FragmentActivity() {

    @Inject
    lateinit var biometricAuthManager: BiometricAuthManager

    @Inject
    lateinit var vaultRepository: VaultRepository

    @Inject
    lateinit var clipboardSecurityManager: ClipboardSecurityManager

    @Inject
    lateinit var settingsStore: ExtendedSettingsStore

    // ISSUE-P3-18：验证码通知发布器（受 autofillShowTotpNotification 偏好与通知权限双闸门约束）
    @Inject
    lateinit var totpNotificationPublisher: TotpNotificationPublisher

    // ISSUE-P3-39：「上次填充」记忆写入点——用户确认填充即真实填充落点
    @Inject
    lateinit var autofillLastFilledStore: AutofillLastFilledStore

    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        // 官方反 overlay 攻击加固：屏蔽其它应用悬浮窗覆盖确认窗口
        window.setHideOverlayWindows(true)
        // ISSUE-P2-09：遮挡触摸过滤（View 层；decorView 子树在窗口被遮挡时统一丢弃触摸，点击劫持防护）
        window.decorView.filterTouchesWhenObscured = true

        val credentialTitle = intent.getStringExtra(EXTRA_CREDENTIAL_TITLE).orEmpty()
        val subtitle = getString(R.string.autofill_confirm_biometric_subtitle, credentialTitle)
        val manualHint = getString(R.string.autofill_confirm_manual_hint, credentialTitle)

        // ISSUE-P3-52：优先系统级认证（强生物识别，与快速解锁同一认证器集合——ISSUE-P1-08 收敛为不含锁屏凭据），
        // 并以 Keystore 认证绑定密钥的 Cipher 发起（CryptoObject），使本次放行与生物识别密码学绑定；
        // 认证成功但结果未携带绑定 Cipher → fail-closed 退化为受保护窗口内手动确认；
        // 设备无对应硬件/未录入/密钥不可用 → 同样退化为手动确认（既有退化策略不回归）。
        val authStatus = biometricAuthManager.canAuthenticate(this, BiometricAuthManager.UNLOCK_AUTHENTICATORS)
        val authCipher = if (authStatus == BiometricStatus.AVAILABLE) {
            biometricAuthManager.prepareAutofillAuthCipher()
        } else {
            null
        }
        if (authStatus == BiometricStatus.AVAILABLE && authCipher != null) {
            biometricAuthManager.authenticate(
                activity = this,
                title = getString(R.string.autofill_confirm_title),
                subtitle = subtitle,
                authenticators = BiometricAuthManager.UNLOCK_AUTHENTICATORS,
                cipher = authCipher
            ) { result ->
                when {
                    AutofillAuthBindingPolicy.isBound(result) -> completeAuthResult()
                    // 认证成功但未携带绑定 Cipher：不做无绑定放行，退化到受保护窗口内手动确认
                    result is BiometricResult.Success -> showManualConfirm(manualHint)
                    else -> finish()
                }
            }
        } else {
            showManualConfirm(manualHint)
        }
    }

    /** 受保护窗口内的手动确认（无可用认证器 / 认证绑定不可用时的 fail-closed 退化路径） */
    private fun showManualConfirm(manualHint: String) {
        setContent {
            // ISSUE-P2-09：Compose 侧遮挡触摸过滤（点击劫持防护）
            ApplyObscuredTouchFilter()
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

    /**
     * 完成认证并回传结果。
     *
     * ISSUE-P3-03 (43b)：先按偏好尝试复制 TOTP 动态码，再回传 RESULT_OK。
     * ISSUE-P3-18：同一落点按 `autofillShowTotpNotification` 偏好补发验证码通知。
     * 两个动作均带硬超时兜底（[TOTP_ACTION_TIMEOUT_MS]），任何异常/超时都不阻断填充；
     * 开关关闭、条目无 TOTP、库已锁定时不触碰剪贴板也不发通知。
     */
    private fun completeAuthResult() {
        if (completed) return
        completed = true
        // ISSUE-P3-39：记录本次确认填充的条目，供下次同站点/应用填充时置顶
        // （仅影响候选排序，不改变任何匹配与放行判定）
        intent.getStringExtra(EXTRA_ENTRY_ID)?.takeIf { it.isNotBlank() }
            ?.let { autofillLastFilledStore.record(it) }

        // ISSUE-P3-42：开启会话授权宽限时，记录本次确认的「包名 + 域」，
        // 使 30 秒内对同一站点/应用的重复填充免二次确认（库锁定态不适用）。
        if (settingsStore.isAutofillSessionGrantEnabled()) {
            intent.getStringExtra(EXTRA_GRANT_PACKAGE)?.takeIf { it.isNotBlank() }?.let { pkg ->
                AutofillSessionGrants.grant(
                    AutofillGrantContext(pkg, intent.getStringExtra(EXTRA_GRANT_DOMAIN))
                )
            }
        }
        lifecycleScope.launch {
            try {
                handleTotpAfterConfirm()
            } finally {
                // 官方认证数据集语义：RESULT_OK 后框架才会把该数据集的值写入目标表单
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    /**
     * 确认后的 TOTP 二次动作：复制到受保护剪贴板 与/或 发送验证码通知。
     *
     * 两个偏好相互独立（`autofillCopyTotp` / `autofillShowTotpNotification`），任一开启都会
     * 触发一次 TOTP 计算；两者皆关时**不触达仓库**（零开销、零副作用）。
     * `autofillCopyTotp` 沿用既有单键读取接口；`autofillShowTotpNotification` 暂无单键接口，
     * 经整体读取取得——本路径每次用户确认仅执行一次，全量读取成本可忽略。
     */
    private suspend fun handleTotpAfterConfirm() {
        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID)?.takeIf { it.isNotBlank() } ?: return
        val copyEnabled = settingsStore.isAutofillCopyTotpEnabled()
        val notifyEnabled = settingsStore.load().autofillShowTotpNotification
        if (!copyEnabled && !notifyEnabled) return

        // 硬超时：TOTP 计算属纯 HMAC 运算（毫秒级），超时即放弃本次二次动作，绝不拖住填充回传；
        // 取消异常必须继续上抛（不得被结果兜底吞掉，否则协程取消语义被破坏）
        val snapshot = withTimeoutOrNull(TOTP_ACTION_TIMEOUT_MS) {
            try {
                vaultRepository.calculateEntryTotp(entryId)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                null
            }
        } ?: return

        if (AutofillTotpCopyPolicy.shouldCopy(copyTotpEnabled = copyEnabled, snapshot = snapshot)) {
            clipboardSecurityManager.copySensitiveText(
                label = getString(R.string.autofill_totp_clip_label),
                text = snapshot.code
            )
        }
        if (notifyEnabled) {
            // 通知只含验证码与剩余秒数，不含任何条目标识（详见 TotpNotificationPublisher 注释）
            totpNotificationPublisher.publish(
                code = snapshot.code,
                periodSeconds = snapshot.periodSeconds
            )
        }
    }

    companion object {
        const val EXTRA_CREDENTIAL_TITLE = "com.keepasskey.app.autofill.EXTRA_CREDENTIAL_TITLE"

        /** ISSUE-P3-03 (43b)：被填充条目的标识，供确认后按条目取 TOTP */
        const val EXTRA_ENTRY_ID = "com.keepasskey.app.autofill.EXTRA_ENTRY_ID"

        /** ISSUE-P3-42：会话授权上下文——调用方包名（确认成功后写入授权） */
        const val EXTRA_GRANT_PACKAGE = "com.keepasskey.app.autofill.EXTRA_GRANT_PACKAGE"

        /** ISSUE-P3-42：会话授权上下文——目标域名（可为空串，表示纯按包名匹配） */
        const val EXTRA_GRANT_DOMAIN = "com.keepasskey.app.autofill.EXTRA_GRANT_DOMAIN"

        /** TOTP 二次动作（复制 / 通知）的硬超时预算：超出即放弃，保证填充回传不被拖慢 */
        private const val TOTP_ACTION_TIMEOUT_MS = 500L
    }
}
