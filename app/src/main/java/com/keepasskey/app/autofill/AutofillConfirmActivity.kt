package com.keepasskey.app.autofill

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.view.autofill.AutofillId
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.ApplyObscuredTouchFilter
import com.keepasskey.app.security.AutofillAuthBindingPolicy
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricResult
import com.keepasskey.app.security.BiometricStatus
import com.keepasskey.app.ui.localizedContextForAppLanguage
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

    // ISSUE-P3-390 AC①：应用内语言快照通道（确认页不经主外壳）
    @Inject
    lateinit var settingsRepository: SettingsRepository

    // ISSUE-P1-24 AC①：归属信息（签名证书 SHA-256）读取通道
    @Inject
    lateinit var autofillOriginResolver: AutofillOriginResolver

    // ISSUE-P1-24 AC②：调用方「首次绑定」信任存储
    @Inject
    lateinit var callerTrustStore: AutofillCallerTrustStore

    // ISSUE-P3-39：「上次填充」记忆写入点——用户确认填充即真实填充落点
    @Inject
    lateinit var autofillLastFilledStore: AutofillLastFilledStore

    // ISSUE-P3-528：调用方关联记忆（键＝包名 + 签名摘要）写入点——数据集路径的真实交付点是本页
    @Inject
    lateinit var autofillCallerEntryMemory: AutofillCallerEntryMemory

    // ISSUE-P2-384：认证回传前复检字段级屏蔽（与选择器同源策略）
    @Inject
    lateinit var autofillFieldBlocklistStore: AutofillFieldBlocklistStore

    // ISSUE-P2-88：确认后取回条目凭据的通道——与选择器复用同一 ViewModel，
    // 使「按条目取用户名 + 按需解密口令 + 字段引用展开」只有一份实现。
    // ISSUE-P3-148：本页**不**调用 [AutofillPickerViewModel.loadEntries]——确认路径只处理
    // EXTRA_ENTRY_ID 指向的单条，取数全走 `VaultRepository.getKdbxEntry` 单条查询，
    // 不触碰整库非敏感投影（VM 的整库装载已改由选择器页显式发起）。
    private val pickerViewModel: AutofillPickerViewModel by viewModels()

    private var completed = false

    /**
     * ISSUE-P1-24 AC① / ISSUE-P3-528：本次确认的调用方归属快照——关联记忆写入复用其主摘要，
     * 不在交付点二次读取调用方证书摘要（每次读取含一次 `getPackageInfo` + 逐签名者 SHA-256）。
     */
    private var callerAttribution: AutofillCallerAttribution? = null

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

        // ISSUE-P3-390 AC①：确认页不经主外壳——语言快照就绪后经 AppShellLocalization 派生本地化上下文，
        // 页面文案（含系统认证弹窗副标题）一律经它取用，不再回落系统语言。
        lifecycleScope.launch {
            val localizedContext = localizedContextForAppLanguage(this@AutofillConfirmActivity, settingsRepository)
            val credentialTitle = intent.getStringExtra(EXTRA_CREDENTIAL_TITLE).orEmpty()
            // ISSUE-P1-24 AC①：解析调用方归属（解析体拆至 resolveAutofillConfirmCallerAttribution）
            val callerAttribution = resolveAutofillConfirmCallerAttribution(
                intent, autofillOriginResolver, callerTrustStore
            )
            // ISSUE-P3-528：留存归属快照供交付点复用（关联记忆写入不再二次读取摘要）
            this@AutofillConfirmActivity.callerAttribution = callerAttribution
            val subtitle = buildString {
                append(localizedContext.getString(R.string.fill_confirm_biometric_subtitle, credentialTitle))
                // 已授权目标走系统认证弹窗时无法渲染归属块，把不可伪造锚点（包名）并入副标题
                callerAttribution?.let {
                    append('\n')
                    append(localizedContext.getString(R.string.autofill_confirm_caller_package, it.packageName))
                }
            }
            val manualHint = localizedContext.getString(R.string.autofill_confirm_manual_hint, credentialTitle)

            // ISSUE-P3-52：优先系统级认证（强生物识别，与快速解锁同一认证器集合——ISSUE-P1-08 收敛为不含锁屏凭据），
            // 并以 Keystore 认证绑定密钥的 Cipher 发起（CryptoObject），使本次放行与生物识别密码学绑定；
            // 认证成功但结果未携带绑定 Cipher → fail-closed 退化为受保护窗口内手动确认；
            // 设备无对应硬件/未录入/密钥不可用 → 同样退化为手动确认（既有退化策略不回归）。
            // ISSUE-P1-24 AC②：**首次出现**的调用方不进入系统认证弹窗——归属信息与显式授权
            // 只能在受保护窗口内的确认页展示与执行，故强制走手动确认路径。
            val authStatus = biometricAuthManager.canAuthenticate(
                this@AutofillConfirmActivity, BiometricAuthManager.UNLOCK_AUTHENTICATORS
            )
            val authCipher = if (authStatus == BiometricStatus.AVAILABLE) {
                biometricAuthManager.prepareAutofillAuthCipher()
            } else {
                null
            }
            if (authStatus == BiometricStatus.AVAILABLE && authCipher != null &&
                callerAttribution?.firstOccurrence == false
            ) {
                biometricAuthManager.authenticate(
                    activity = this@AutofillConfirmActivity,
                    title = localizedContext.getString(R.string.autofill_confirm_title),
                    subtitle = subtitle,
                    authenticators = BiometricAuthManager.UNLOCK_AUTHENTICATORS,
                    cipher = authCipher
                ) { result ->
                    when {
                        AutofillAuthBindingPolicy.isBound(result) -> completeAuthResult()
                        // 认证成功但未携带绑定 Cipher：不做无绑定放行，退化到受保护窗口内手动确认
                        result is BiometricResult.Success ->
                            showManualConfirm(manualHint, callerAttribution, localizedContext)
                        else -> finish()
                    }
                }
            } else {
                showManualConfirm(manualHint, callerAttribution, localizedContext)
            }
        }
    }

    /** 受保护窗口内的手动确认（无可用认证器 / 认证绑定不可用时的 fail-closed 退化路径） */
    private fun showManualConfirm(
        manualHint: String,
        attribution: AutofillCallerAttribution?,
        localizedContext: Context
    ) {
        setContent {
            AutofillConfirmManualScreen(
                manualHint = manualHint,
                attribution = attribution,
                callerTrustStore = callerTrustStore,
                onConfirm = { completeAuthResult() },
                onCancel = { finish() },
                localizedContext = localizedContext
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
        intent.getStringExtra(EXTRA_ENTRY_ID)?.takeIf { it.isNotBlank() }?.let { entryId ->
            autofillLastFilledStore.record(entryId)
            // ISSUE-P3-528：同点写入关联记忆（键＝包名 + 签名摘要；摘要取自归属快照，快照缺失即不写）
            callerAttribution?.let {
                autofillCallerEntryMemory.remember(it.packageName, it.certSha256Hex, entryId)
            }
        }

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
        val usernameId = intent.readAutofillId(EXTRA_TARGET_USERNAME_ID)
        val passwordId = intent.readAutofillId(EXTRA_TARGET_PASSWORD_ID)
        val otpId = intent.readAutofillId(EXTRA_TARGET_OTP_ID)
        // ISSUE-P3-375：结构化目标（读取实现拆至 AutofillConfirmStructuredTargets）
        val structured = readStructuredTargetsFrom(intent)
        if (usernameId == null && passwordId == null && structured.isEmpty()) return null

        // ISSUE-P2-384 AC①：认证回传构造 Dataset 前复检字段级屏蔽（fail-closed）。
        // 确认页是「再次确认」路径——屏蔽写入后系统缓存重放 / 用户再次确认时，
        // 被屏蔽字段不得再出现在回传数据集里（与选择器 deliver 同一策略对象）。
        // 域取表单**自报**域（与屏蔽写入键同源）；确认页仅有归属校验后域时回落之。
        val callingPackage = intent.getStringExtra(AutofillPickerActivity.EXTRA_CALLING_PACKAGE)
            ?: intent.getStringExtra(EXTRA_GRANT_PACKAGE)
            ?: packageName
        val formDomain = intent.getStringExtra(AutofillPickerActivity.EXTRA_WEB_DOMAIN)
            ?: intent.getStringExtra(EXTRA_GRANT_DOMAIN)?.takeIf { it.isNotBlank() }
        val deliverable = AutofillAuthDeliveryBlockPolicy.filter(
            usernameId = usernameId,
            passwordId = passwordId
        ) { role ->
            autofillFieldBlocklistStore.isBlocked(callingPackage, formDomain, role)
        }
        if (deliverable.blocksEntireForm && structured.isEmpty()) {
            AppLog.w(TAG, "确认页回传复检：字段已被屏蔽，按取消回传")
            return null
        }

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
        // ISSUE-P3-375 AC③：结构化字段值只在确认成功后按条目取回（实现拆至 AutofillConfirmStructuredTargets）
        val structuredValues: Map<AutofillId, String> = if (structured.isEmpty()) emptyMap() else {
            val entry = vaultRepository.getKdbxEntry(entryId) ?: return null
            buildStructuredFieldValues(structured, entry)
        }
        val dataset = AutofillConfirmAuthDelivery.build(
            packageName = packageName,
            // ISSUE-P3-330：用户名为空时标题行即条目标题，副行留空——避免两行同文
            menuTitle = credentials.username.ifBlank { credentialTitle },
            menuSubtitle = if (credentials.username.isNotBlank()) credentialTitle else "",
            username = credentials.username,
            password = credentials.password,
            deliverable = deliverable,
            otpId = otpId,
            otpCode = otpCode,
            structuredFields = structuredValues
        ) ?: return null
        // 只记录「哪些字段真的有值」，不含任何凭据内容 / 用户名 / 条目名 / 包名
        AutofillConfirmAuthDelivery.logDelivery(
            tag = TAG,
            usernameHasValue = credentials.username.isNotEmpty(),
            passwordHasValue = credentials.password.isNotEmpty(),
            otpHasValue = otpCode.isNotEmpty(),
            structuredCount = structuredValues.size,
            usernameIdPresent = deliverable.usernameId != null,
            passwordIdPresent = deliverable.passwordId != null,
            otpIdPresent = otpId != null
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
