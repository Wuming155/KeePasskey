package com.keepasskey.app.autofill

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import com.keepasskey.core.log.AppLog
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
 *
 * ISSUE-P1-24：确认页强制展示**不可伪造**的调用方归属（包名 + 签名证书 SHA-256 + 归属
 * 校验后的域；label / icon 可自声明，不作为归属依据）。**首次出现**的调用方（未获显式授权）
 * 不进入系统认证弹窗，一律走受保护窗口内的手动确认，并要求显式勾选「记住此应用」授权后
 * 方可确认（[AutofillCallerTrustStore] 以「包名 + 签名摘要」持久化，同包名换签名重新视为首次）；
 * 已授权目标走系统认证弹窗时，把包名并入副标题展示。
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

    // ISSUE-P1-24 AC①：归属信息（签名证书 SHA-256）读取通道
    @Inject
    lateinit var autofillOriginResolver: AutofillOriginResolver

    // ISSUE-P1-24 AC②：调用方「首次绑定」信任存储
    @Inject
    lateinit var callerTrustStore: AutofillCallerTrustStore

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
        // ISSUE-P1-24 AC①：解析调用方归属——包名取自本服务写入的 extra（源自系统结构树，
        // 非调用方自报，且确认 PendingIntent 为本应用构建的 FLAG_IMMUTABLE，extra 不可被第三方改写）；
        // 域为本服务已通过归属校验的 webDomain（自报且未通过校验的域不会下发候选）；
        // 签名摘要经 PackageManager 现场读取（包可见性受限时为 null，展示侧如实标注）。
        val callerAttribution = resolveCallerAttribution()
        val subtitle = buildString {
            append(getString(R.string.autofill_confirm_biometric_subtitle, credentialTitle))
            // 已授权目标走系统认证弹窗时无法渲染归属块，把不可伪造锚点（包名）并入副标题
            callerAttribution?.let {
                append('\n')
                append(getString(R.string.autofill_confirm_caller_package, it.packageName))
            }
        }
        val manualHint = getString(R.string.autofill_confirm_manual_hint, credentialTitle)

        // ISSUE-P3-52：优先系统级认证（强生物识别，与快速解锁同一认证器集合——ISSUE-P1-08 收敛为不含锁屏凭据），
        // 并以 Keystore 认证绑定密钥的 Cipher 发起（CryptoObject），使本次放行与生物识别密码学绑定；
        // 认证成功但结果未携带绑定 Cipher → fail-closed 退化为受保护窗口内手动确认；
        // 设备无对应硬件/未录入/密钥不可用 → 同样退化为手动确认（既有退化策略不回归）。
        // ISSUE-P1-24 AC②：**首次出现**的调用方不进入系统认证弹窗——归属信息与显式授权
        // 只能在受保护窗口内的确认页展示与执行，故强制走手动确认路径。
        val authStatus = biometricAuthManager.canAuthenticate(this, BiometricAuthManager.UNLOCK_AUTHENTICATORS)
        val authCipher = if (authStatus == BiometricStatus.AVAILABLE) {
            biometricAuthManager.prepareAutofillAuthCipher()
        } else {
            null
        }
        if (authStatus == BiometricStatus.AVAILABLE && authCipher != null &&
            callerAttribution?.firstOccurrence == false
        ) {
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
                    result is BiometricResult.Success -> showManualConfirm(manualHint, callerAttribution)
                    else -> finish()
                }
            }
        } else {
            showManualConfirm(manualHint, callerAttribution)
        }
    }

    /**
     * 解析调用方归属（ISSUE-P1-24 AC①/AC②）。extra 缺失（异常启动路径）返回 null，
     * 此时保持既有确认流程，但**不**写会话授权与信任记录。
     */
    private fun resolveCallerAttribution(): AutofillCallerAttribution? {
        val callerPackage = intent.getStringExtra(EXTRA_GRANT_PACKAGE)?.takeIf { it.isNotBlank() }
            ?: return null
        val callerDomain = intent.getStringExtra(EXTRA_GRANT_DOMAIN)?.takeIf { it.isNotBlank() }
        // ISSUE-P3-93：读取**全部**签名摘要参与判定；展示与信任记录仍用主摘要
        val certDigests = autofillOriginResolver.callingAppCertDigests(callerPackage)
        val firstOccurrence = !callerTrustStore.isTrusted(callerPackage, certDigests)
        return AutofillCallerAttribution(
            packageName = callerPackage,
            // 展示与信任记录用主摘要（不可读时为 null，展示侧如实标注「不可读」）
            certSha256Hex = certDigests.primary,
            webDomain = callerDomain,
            firstOccurrence = firstOccurrence
        )
    }

    /** 受保护窗口内的手动确认（无可用认证器 / 认证绑定不可用时的 fail-closed 退化路径） */
    private fun showManualConfirm(manualHint: String, attribution: AutofillCallerAttribution?) {
        setContent {
            // ISSUE-P2-09：Compose 侧遮挡触摸过滤（点击劫持防护）
            ApplyObscuredTouchFilter()
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
            CredentialFillConfirmScreen(
                title = getString(R.string.autofill_confirm_title),
                hint = manualHint,
                confirmText = getString(R.string.autofill_confirm_ok),
                cancelText = getString(R.string.autofill_confirm_cancel),
                confirmEnabled = !requiresExplicitAuthorization || trustChecked,
                attributionContent = attributionContent,
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
        // ISSUE-P3-95：库已锁定 → **丢弃**未决响应（绝不回传 RESULT_OK），
        // 且不写入「上次填充条目」记忆、不记录会话授权宽限（锁定后这些副作用均不应发生）
        if (!AutofillAuthenticationPolicy.canDeliverAuthResult(vaultRepository.isLocked())) {
            AppLog.w(TAG, "会话已锁定，丢弃本次自动填充确认响应")
            discardPendingResult()
            return
        }
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
                // ISSUE-P3-95：**回传前再次校验**——TOTP 二次动作（含超时等待）期间会话可能刚被
                // 自动锁定 / 手动锁定；锁定即丢弃未决响应，否则框架仍会把凭据值写入目标表单
                if (AutofillAuthenticationPolicy.canDeliverAuthResult(vaultRepository.isLocked())) {
                    // 官方认证数据集语义：RESULT_OK 后框架才会把该数据集的值写入目标表单。
                    // ISSUE-P2-73 AC①：**必须**双参 `setResult(int, Intent)` 且 extras 非空——
                    // 官方 `FillResponse.Builder#setAuthentication` 明文要求 Android 12 起
                    // extras 为 null 会**崩溃**，并点名不得使用单参 `setResult(int)`。
                    // 本路径不自行构造 Dataset（框架侧已缓存该数据集），故按官方给的等价做法
                    // 取 Bundle.EMPTY，保持「回传成功、不改动载荷」的既有语义。
                    setResult(RESULT_OK, Intent().putExtras(Bundle.EMPTY))
                } else {
                    AppLog.w(TAG, "会话在确认过程中被锁定，丢弃未决响应（不回传 RESULT_OK）")
                    setResult(RESULT_CANCELED)
                }
                finish()
            }
        }
    }

    /**
     * ISSUE-P3-95：丢弃未决响应——显式以 `RESULT_CANCELED` 结束，令框架不写入任何凭据值。
     */
    private fun discardPendingResult() {
        setResult(RESULT_CANCELED)
        finish()
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
        private const val TAG = "AutofillConfirm"

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

/**
 * 确认页的调用方归属信息块（ISSUE-P1-24 AC①）。
 *
 * 强制展示不可伪造锚点：调用方包名（系统结构树提供）+ 签名证书 SHA-256（本应用读取；
 * 不可读时如实标注，不以占位冒充）+ 归属校验后的目标域。首次出现的目标（AC②）另附
 * 显式授权勾选——勾选前确认按钮不可用（[trustChecked] 由宿主门控 [CredentialFillConfirmScreen]
 * 的 confirmEnabled）。
 */
@Composable
private fun AutofillCallerAttributionBlock(
    attribution: AutofillCallerAttribution,
    showTrustCheckbox: Boolean,
    trustChecked: Boolean,
    onTrustCheckedChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.autofill_confirm_caller_package, attribution.packageName),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = attribution.certSha256Hex
                ?.let { stringResource(R.string.autofill_confirm_caller_cert, it) }
                ?: stringResource(R.string.autofill_confirm_cert_unreadable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = attribution.webDomain
                ?.let { stringResource(R.string.autofill_confirm_caller_domain, it) }
                ?: stringResource(R.string.autofill_confirm_domain_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (showTrustCheckbox) {
            Text(
                text = stringResource(R.string.autofill_confirm_first_occurrence),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Checkbox(checked = trustChecked, onCheckedChange = onTrustCheckedChange)
                Text(
                    text = stringResource(R.string.autofill_confirm_remember_trust),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        } else {
            Text(
                text = stringResource(R.string.autofill_confirm_already_trusted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
