package com.keepasskey.app.autofill

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.view.autofill.AutofillId
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.passkey.CredentialFillConfirmScreen
import com.keepasskey.app.security.ApplyObscuredTouchFilter
import com.keepasskey.app.security.AutofillAuthBindingPolicy
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricResult
import com.keepasskey.app.security.BiometricStatus
import com.keepasskey.core.log.AppLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
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
 *
 * ISSUE-P2-88：确认成功后**必须回传真实 `Dataset`**（官方 `Dataset.Builder#setAuthentication` 契约：
 * 「If you provide a dataset in the result, it will replace the authenticated dataset and will be
 * immediately filled in」）。此前本页只回传 `RESULT_OK` + 空 extras，框架无值可写（留痕
 * `onAuthenticationResult(): empty intent`）⇒ 真机实测「确认后输入框仍为空」。
 * 现于确认成功后按认证 Intent 下发的目标框 id + `EXTRA_ENTRY_ID` 取回凭据，
 * 构造 `Dataset` 并经 `AutofillManager.EXTRA_AUTHENTICATION_RESULT` 回传；
 * 取不回凭据 / 无目标框 / 会话锁定一律如实回传取消，绝不构造空数据集谎报成功。
 */
@AndroidEntryPoint
class AutofillConfirmActivity : FragmentActivity() {

    @Inject
    lateinit var biometricAuthManager: BiometricAuthManager

    @Inject
    lateinit var vaultRepository: VaultRepository

    // ISSUE-P3-186：验证码通知与 TOTP 复制收敛进共用实现（与选择器路径同源一份）
    @Inject
    lateinit var totpPostFillActions: AutofillPostFillTotpActions

    @Inject
    lateinit var settingsStore: ExtendedSettingsStore

    // ISSUE-P1-24 AC①：归属信息（签名证书 SHA-256）读取通道
    @Inject
    lateinit var autofillOriginResolver: AutofillOriginResolver

    // ISSUE-P1-24 AC②：调用方「首次绑定」信任存储
    @Inject
    lateinit var callerTrustStore: AutofillCallerTrustStore

    // ISSUE-P3-39：「上次填充」记忆写入点——用户确认填充即真实填充落点
    @Inject
    lateinit var autofillLastFilledStore: AutofillLastFilledStore

    // ISSUE-P2-88：确认后取回条目凭据的通道——与选择器复用同一 ViewModel，
    // 使「按条目取用户名 + 按需解密口令 + 字段引用展开」只有一份实现。
    // ISSUE-P3-148：本页**不**调用 [AutofillPickerViewModel.loadEntries]——确认路径只处理
    // EXTRA_ENTRY_ID 指向的单条，取数全走 `VaultRepository.getKdbxEntry` 单条查询，
    // 不触碰整库非敏感投影（VM 的整库装载已改由选择器页显式发起）。
    private val pickerViewModel: AutofillPickerViewModel by viewModels()

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
        // 非调用方自报；该 PendingIntent 由本应用创建且只交给系统框架，第三方拿不到、无从改写。
        // ISSUE-P2-88 起该 PendingIntent 为 FLAG_MUTABLE——那是官方契约要求平台能注入认证参数的
        // 唯一原因，不改变「extra 只由本应用写入」这一事实）；
        // 域为本服务已通过归属校验的 webDomain（自报且未通过校验的域不会下发候选）；
        // 签名摘要经 PackageManager 现场读取（包可见性受限时为 null，展示侧如实标注）。
        val callerAttribution = resolveCallerAttribution()
        val subtitle = buildString {
            append(getString(R.string.fill_confirm_biometric_subtitle, credentialTitle))
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
     * ISSUE-P3-03 (43b) / ISSUE-P3-18：回传前按偏好执行 TOTP 复制 / 验证码通知——
     * ISSUE-P3-186 起该二次动作收敛为共用实现 [AutofillPostFillTotpActions]
     * （500ms 硬超时 + 双开关闸门 + 库锁定不触碰），与选择器路径同源一份。
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
            // ISSUE-P3-186：TOTP 二次动作收敛为共用实现（与选择器路径同源一份）；
            // 实现内部自带硬超时与异常兜底，不阻断填充回传
            totpPostFillActions.runAfterFill(intent.getStringExtra(EXTRA_ENTRY_ID).orEmpty())
            deliverAuthResult()
        }
    }

    /**
     * ISSUE-P2-88：把**真实数据集**回传给框架。
     *
     * 官方契约（`Dataset.Builder#setAuthentication` 原文）：认证流程结束后必须把
     * 「fully populated dataset」经 `AutofillManager.EXTRA_AUTHENTICATION_RESULT` 回传——
     * 「If you provide a dataset in the result, it will replace the authenticated dataset and
     * will be immediately filled in」。此前本页只回传 `RESULT_OK` + 空 extras（框架留痕
     * `onAuthenticationResult(): empty intent`），框架无值可写 ⇒ 真机实测恒不填充。
     *
     * 明文只在**显式确认已经成功**（生物识别绑定通过 / 受保护窗口点选）之后、于本方法内组装；
     * 取不到凭据 / 两个目标框 id 皆空 / 会话已锁定 ⇒ 如实回传取消，**绝不**构造空数据集谎报成功
     * （`Dataset.Builder#build()` 在没有任何 `setField` 时会抛异常）。
     */
    private suspend fun deliverAuthResult() {
        // ISSUE-P3-95：**回传前再次校验**——TOTP 二次动作（含超时等待）期间会话可能刚被
        // 自动锁定 / 手动锁定；锁定即丢弃未决响应，否则框架仍会把凭据值写入目标表单
        if (!AutofillAuthenticationPolicy.canDeliverAuthResult(vaultRepository.isLocked())) {
            AppLog.w(TAG, "会话在确认过程中被锁定，丢弃未决响应（不回传 RESULT_OK）")
            discardPendingResult()
            return
        }
        val resultIntent = resolveAuthResultIntent()
        if (resultIntent == null) {
            AppLog.w(TAG, "确认后无可交付字段，按取消回传（不构造空数据集）")
            setResult(RESULT_CANCELED, authenticationCanceledIntent())
            finish()
            return
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    /**
     * ISSUE-P2-88：按认证 Intent 下发的目标字段 id 与条目标识取回凭据，构造待回传数据集。
     *
     * 复用选择器同一条取数路径（[AutofillPickerViewModel.resolveCredentials]）与同一份载荷构造
     * （[buildAuthenticationResultDataset]）——这两处都是经真机验证可填充的形态。
     *
     * @return 无目标框 / 凭据不可用 / 无可写字段时返回 null（调用方按取消处置）
     */
    private suspend fun resolveAuthResultIntent(): Intent? {
        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID)?.takeIf { it.isNotBlank() } ?: return null
        val usernameId = readAutofillId(EXTRA_TARGET_USERNAME_ID)
        val passwordId = readAutofillId(EXTRA_TARGET_PASSWORD_ID)
        val otpId = readAutofillId(EXTRA_TARGET_OTP_ID)
        if (usernameId == null && passwordId == null) return null

        val credentials = pickerViewModel.resolveCredentials(entryId) ?: return null
        val credentialTitle = intent.getStringExtra(EXTRA_CREDENTIAL_TITLE).orEmpty()
        // ISSUE-P3-298 ⑤：回传时刻现算 TOTP（值新鲜度以交付时刻为准）；仅 TOTP 参与
        // 直填——HOTP 当前码不推进计数器，直填会给出与服务端不同步的旧值
        val otpCode = if (otpId != null) {
            vaultRepository.calculateEntryTotp(entryId)
                ?.takeIf { !it.isHotp }?.code.orEmpty()
        } else {
            ""
        }
        val dataset = buildAuthenticationResultDataset(
            packageName = packageName,
            // ISSUE-P3-330：用户名为空时标题行即条目标题，副行留空——避免两行同文
            menuTitle = credentials.username.ifBlank { credentialTitle },
            menuSubtitle = if (credentials.username.isNotBlank()) credentialTitle else "",
            username = credentials.username,
            password = credentials.password,
            usernameId = usernameId,
            passwordId = passwordId,
            otpId = otpId,
            otpCode = otpCode
        ) ?: return null
        // 只记录「哪些字段真的有值」，不含任何凭据内容 / 用户名 / 条目名 / 包名
        AppLog.d(
            TAG,
            "确认后回传数据集：用户名有值=${credentials.username.isNotEmpty()}" +
                " 口令有值=${credentials.password.isNotEmpty()}" +
                " 验证码有值=${otpCode.isNotEmpty()}" +
                " 用户名框=${usernameId != null} 密码框=${passwordId != null} 验证码框=${otpId != null}"
        )
        return authenticationResultIntent(dataset)
    }

    /**
     * ISSUE-P3-95：丢弃未决响应——显式以 `RESULT_CANCELED` 结束，令框架不写入任何凭据值。
     *
     * ISSUE-P2-88：与成功回传同口径走**双参**重载（extras 非空）——官方明文：Android 12 起
     * 认证结果 Intent 的 extras 为 null 会崩溃。
     */
    private fun discardPendingResult() {
        setResult(RESULT_CANCELED, authenticationCanceledIntent())
        finish()
    }

    /** 从认证 Intent 读取目标输入框 id（服务端下发；缺失表示本次请求未识别到该角色） */
    private fun readAutofillId(key: String): AutofillId? =
        intent.getParcelableExtra(key, AutofillId::class.java)

    companion object {
        private const val TAG = "AutofillConfirm"

        const val EXTRA_CREDENTIAL_TITLE = "com.keepasskey.app.autofill.EXTRA_CREDENTIAL_TITLE"

        /** ISSUE-P3-03 (43b)：被填充条目的标识，供确认后按条目取 TOTP */
        const val EXTRA_ENTRY_ID = "com.keepasskey.app.autofill.EXTRA_ENTRY_ID"

        /** ISSUE-P3-42：会话授权上下文——调用方包名（确认成功后写入授权） */
        const val EXTRA_GRANT_PACKAGE = "com.keepasskey.app.autofill.EXTRA_GRANT_PACKAGE"

        /** ISSUE-P3-42：会话授权上下文——目标域名（可为空串，表示纯按包名匹配） */
        const val EXTRA_GRANT_DOMAIN = "com.keepasskey.app.autofill.EXTRA_GRANT_DOMAIN"

        /**
         * ISSUE-P2-88：目标**用户名框** id（可为 null——纯密码表单），
         * 供确认页在用户确认后构造字段 id 正确的回传数据集。
         * 只传 `AutofillId`（系统结构树下的字段定位符，非敏感），**不**传任何凭据内容。
         */
        const val EXTRA_TARGET_USERNAME_ID = "com.keepasskey.app.autofill.EXTRA_CONFIRM_USERNAME_ID"

        /** ISSUE-P2-88：目标**密码框** id（可为 null——纯用户名表单） */
        const val EXTRA_TARGET_PASSWORD_ID = "com.keepasskey.app.autofill.EXTRA_CONFIRM_PASSWORD_ID"

        /** ISSUE-P3-298 ⑤：目标 **OTP 验证码框** id（可为 null——表单未显式声明时） */
        const val EXTRA_TARGET_OTP_ID = "com.keepasskey.app.autofill.EXTRA_CONFIRM_OTP_ID"
    }
}
