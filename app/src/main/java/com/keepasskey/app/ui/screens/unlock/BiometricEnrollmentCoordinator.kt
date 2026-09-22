package com.keepasskey.app.ui.screens.unlock

import androidx.biometric.BiometricManager
import androidx.fragment.app.FragmentActivity
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricCredentialStorage
import com.keepasskey.app.security.BiometricResult
import com.keepasskey.app.security.KeystoreManager
import com.keepasskey.app.security.UnlockAuthPolicy
import com.keepasskey.app.security.UnlockPasskeyManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import javax.crypto.Cipher

/**
 * 生物识别凭据登记（首次封印）协调器（ISSUE-P3-25 结构拆分，纯搬运零行为变更）。
 *
 * 承载原 `UnlockViewModel` 的 `requestBiometricEnrollment` 与 `awaitBiometricAuth`：
 * 主密码解锁成功后的一次性 BiometricPrompt 授权 → 封印主凭据入库。
 * 敏感数据路径（`CharArray` → 临时 UTF-8 字节 → `finally` 显式清零）与原实现逐字一致。
 *
 * 仅承载「登记」语义；解封与解锁结果处理见 [BiometricUnlockCoordinator]。
 */
internal class BiometricEnrollmentCoordinator(
    private val uiState: MutableStateFlow<UnlockUiState>,
    private val settingsRepository: SettingsRepository,
    private val activeDbId: () -> String?,
    // ISSUE-P2-23：本次解锁使用的密钥文件字节提供者（null = 未携带；封印前快照克隆）
    private val keyFileBytes: () -> ByteArray?,
    private val biometricAuthManager: BiometricAuthManager?,
    private val biometricCredentialStorage: BiometricCredentialStorage?,
    private val unlockPasskeyManager: UnlockPasskeyManager?,
    private val debugLog: DebugLogBuffer
) {

    /**
     * ISSUE-P1-22：封印密钥供给与落位探测替身（可空仅用于 JVM 单测注入假探测结果，
     * 经 [UnlockViewModel.installSealKeyProvisionForTest] 写入；生产恒 null → 走
     * [defaultSealKeyProvision] 真实供给）。在每次登记调用时求值，支持登记前注入。
     */
    internal var sealKeyProvisionOverride: ((String) -> SealedKeyProvision?)? = null

    /** 生产默认封印密钥供给：创建/复用密钥 + 探测实际落位，任何异常按不可用处理（fail-closed） */
    private val defaultSealKeyProvision: (String) -> SealedKeyProvision? = { dbId ->
        val authManager = biometricAuthManager
        try {
            if (authManager == null) {
                null
            } else {
                SealedKeyProvision(
                    cipher = authManager.prepareEncryptCipher(dbId),
                    securityLevel = authManager.getKeySecurityLevelForDatabase(dbId)
                )
            }
        } catch (e: Throwable) {
            debugLog.warn(TAG, "封印密钥不可用: ${e.javaClass.simpleName}")
            null
        }
    }

    // ISSUE-P1-22：降级确认弹窗的用户决定回调挂起点（同一时刻至多一个挂起中的确认）
    private var downgradeConsentDeferred: CompletableDeferred<Boolean>? = null

    /**
     * 若开启生物识别且尚未登记，请求一次 BiometricPrompt 授权后封印主凭据。
     *
     * 关键约束（ISSUE-P1-08）：快速解锁硬件密钥以
     * `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` 生成（**仅强生物识别**授权，
     * 设备锁屏凭据不再可解封）——**每次使用**（含加密）都必须先取得一次 Class 3 强生物识别授权。
     * 原实现直接对未授权 Cipher 调 `doFinal()`，
     * 真机必然抛 `UserNotAuthenticatedException` 并被 `catch (ignored)` 吞掉，
     * 导致「生物识别开关已开、凭据从未入库、下次冷启动无生物入口」的静默功能失效
     * （与 Wave 11 H4 QuickUnlock 同构故障）。
     * 故本方法改为：**先弹 BiometricPrompt 取得授权 Cipher，再在成功回调内执行封印**。
     * 封印前另经 [UnlockAuthPolicy.canSeal] 校验设备具备「硬件存在且已录入」的强生物识别，
     * 弱凭据设备禁用封印（fail-closed），绝不降级到锁屏凭据路径。
     *
     * ISSUE-P1-22 降级确认闸门：封印密钥落位为 SOFTWARE / UNKNOWN（无法证明硬件隔离）时，
     * 未经用户在风险提示弹窗中显式确认，**不建立封印**（fail-closed）；确认记录持久化
     * （[UserSettings.quickUnlockDowngradeAcknowledged]），确认后解锁页与安全设置页常驻声明
     * 「本机快速解锁降级为软件密钥，不提供硬件级保护」。硬件落位（TEE / StrongBox）不受影响。
     *
     * 敏感数据设计考量与边界说明 (Wave 3-E P2-18 / ISSUE-P2-23)：
     * 消费 [CharArray] 与可选密钥文件字节，经 [BiometricSealedPayloadCodec] 编为
     * 临时复合载荷明文并在 finally 块中立即显式清零，杜绝秘密以持久明文穿越硬件加密管线。
     *
     * 失败语义：登记失败（用户取消 / 硬件缺失 / 无宿主 Activity）一律 fail-safe——
     * 仅留痕日志，**不影响本次主密码解锁**。
     */
    suspend fun requestBiometricEnrollment(
        activity: FragmentActivity?,
        passwordChars: CharArray
    ) {
        // ISSUE-P2-23：复合密钥库（主密码 + 密钥文件）同样可登记快速解锁——
        // 封印载荷升级为版本化帧格式（[BiometricSealedPayloadCodec]），把主密码与
        // 密钥文件因子一并封印；解封后以两因子走既有解锁管线。密钥文件字节仅驻留
        // Keystore 密文（受与主密码同级的强生物识别授权门控），不落地为 String/明文。
        // 原实现「带密钥文件即整体跳过封印」使复合密钥库用户永久失去指纹解锁，已移除。
        val dbId = activeDbId() ?: return
        val settings = settingsRepository.getSettings().first()
        if (!settings.biometricEnabled) return
        // 已登记过则不再重复弹窗（仅首次 + 凭据被清除后重新登记）
        biometricCredentialStorage?.let { storage ->
            if (storage.hasEncryptedCredential(dbId)) return
        }

        // ISSUE-P1-22：封印密钥供给 + 实际落位探测（先建钥后探测，见类 KDoc 次序约束）
        val provision = (sealKeyProvisionOverride ?: defaultSealKeyProvision)(dbId)
        if (provision == null) {
            debugLog.warn(TAG, "生物识别凭据未登记：封印密钥不可用，跳过封印（fail-closed）")
            return
        }
        // ISSUE-P1-22 降级确认闸门：未放行即中止，绝不静默封印
        if (!acquireDowngradeConsent(provision, settings.quickUnlockDowngradeAcknowledged)) return
        val downgradedSeal = UnlockAuthPolicy.requiresDowngradeConsent(provision.securityLevel)

        val storage = biometricCredentialStorage ?: return
        val authManager = biometricAuthManager ?: return
        // 缺少宿主 Activity 亦 fail-closed（仅留痕，不影响本次解锁）
        val hostActivity = activity ?: run {
            debugLog.warn(TAG, "生物识别凭据未登记：缺少宿主 Activity，本次跳过（fail-closed，不影响解锁）")
            return
        }
        if (!hasStrongBiometric(authManager, hostActivity)) return

        val encrypted = sealCompositePayload(authManager, hostActivity, provision, passwordChars) ?: return
        // ISSUE-P2-253：封印弹窗挂起期间开关可能已被关闭（关闭 = 删除），落库前复核偏好，
        // 杜绝「撤销刚执行、陈旧封印又写回」的竞态（acquireDowngradeConsent 拒绝路径亦同批关闸）
        if (!settingsRepository.getSettings().first().biometricEnabled) {
            debugLog.info(TAG, "封印挂起期间生物识别开关已关闭，放弃落库")
            return
        }
        persistSealedCredential(storage, dbId, encrypted, downgradedSeal)
    }

    /**
     * ISSUE-P1-22：软件级降级确认闸门的裁决。
     *
     * @return true = 放行封印（硬件落位，或软件级降级已获用户确认并已留痕）；
     *   false = 中止本次封印（超时未决 / 用户拒绝，均 fail-closed）
     */
    private suspend fun acquireDowngradeConsent(
        provision: SealedKeyProvision,
        acknowledged: Boolean
    ): Boolean {
        if (!UnlockAuthPolicy.requiresDowngradeConsent(provision.securityLevel)) return true
        if (acknowledged) return true
        debugLog.warn(
            TAG,
            "封印密钥落位 ${provision.securityLevel}：请求用户显式降级确认（未确认不封印）"
        )
        return when (awaitDowngradeConsent()) {
            null -> {
                debugLog.info(TAG, "降级确认超时未决，本次跳过封印（fail-closed）")
                false
            }
            false -> {
                // 用户拒绝软件级降级路径：关闭生物识别开关（用户唯一可用路径已拒绝，
                // 关闭可避免后续每次解锁重复弹窗），不封印、不留确认记录
                debugLog.info(TAG, "用户拒绝软件级快速解锁，关闭生物识别并不封印")
                settingsRepository.setBiometricEnabled(false)
                uiState.update { it.copy(isBiometricEnabled = false) }
                // ISSUE-P2-253：关闭 = 删除——先落偏好关闸，再撤销各库既有封印数据
                biometricCredentialStorage?.revokeAllBiometricData()
                false
            }
            true -> {
                // 显式记录用户确认（AC②：建立封印前提示并留痕），后续解锁不再重复询问
                settingsRepository.setQuickUnlockDowngradeAcknowledged(true)
                true
            }
        }
    }

    /**
     * ISSUE-P1-08：封印闸门——设备须具备「硬件存在且已录入」的 Class 3 强生物识别，
     * 无强生物（含未录入）时禁用封印（fail-closed），不降级到弱锁屏凭据路径。
     */
    private fun hasStrongBiometric(authManager: BiometricAuthManager, activity: FragmentActivity): Boolean {
        val biometricStatus = authManager.canAuthenticate(
            activity,
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        )
        if (!UnlockAuthPolicy.canSeal(biometricStatus)) {
            debugLog.warn(TAG, "生物识别凭据未登记：设备无可用强生物识别（$biometricStatus），禁用封印（fail-closed）")
            return false
        }
        return true
    }

    /**
     * 封印复合载荷（主密码 + 可选密钥文件因子），敏感字节全程 `finally` 显式清零。
     *
     * @return `iv to 密文`；取消 / 未取得授权 Cipher / 异常时为 null（各分支均已留痕）
     */
    private suspend fun sealCompositePayload(
        authManager: BiometricAuthManager,
        activity: FragmentActivity,
        provision: SealedKeyProvision,
        passwordChars: CharArray
    ): Pair<ByteArray, ByteArray>? {
        val cipher = provision.cipher
        return try {
            // ISSUE-P2-23：密钥文件先快照克隆（登记跨 BiometricPrompt 挂起，原字节归会话所有），
            // 快照在载荷编码完成后立即清零；载荷明文在其自身 finally 中清零。
            val keyFileSnapshot = keyFileBytes()?.copyOf()
            val bytes = BiometricSealedPayloadCodec.encode(passwordChars, keyFileSnapshot)
            keyFileSnapshot?.fill(0)
            try {
                authorizeAndSeal(authManager, activity, cipher, bytes)
            } finally {
                bytes.fill(0)
            }
        } catch (e: Exception) {
            // 禁止静默失败：任何异常一律留痕，绝不 catch(ignored)
            debugLog.warn(TAG, "生物识别凭据登记异常: ${e.javaClass.simpleName}")
            null
        }
    }

    /** 取得一次 Class 3 授权后以授权 Cipher 密封 [payload]；四条结果路径均如实留痕。 */
    private suspend fun authorizeAndSeal(
        authManager: BiometricAuthManager,
        activity: FragmentActivity,
        cipher: Cipher,
        payload: ByteArray
    ): Pair<ByteArray, ByteArray>? {
        val sealed = when (val authResult = awaitBiometricAuth(authManager, activity, cipher)) {
            is BiometricResult.Success -> {
                val authedCipher = authResult.cipher
                if (authedCipher == null) {
                    debugLog.warn(TAG, "生物识别登记未取得授权 Cipher，跳过封印")
                    null
                } else {
                    authedCipher.doFinal(payload)
                }
            }
            is BiometricResult.Cancelled -> {
                debugLog.info(TAG, "用户取消生物识别登记")
                null
            }
            is BiometricResult.Error -> {
                debugLog.warn(TAG, "生物识别登记失败: ${authResult.errString}")
                null
            }
            is BiometricResult.Failed -> {
                debugLog.warn(TAG, "生物识别登记未通过")
                null
            }
        }
        return sealed?.let { cipher.iv to it }
    }

    /** 封印成功落库 + best-effort 登记解锁通行密钥（TASK-18）+ 置位快速解锁可用性。 */
    private fun persistSealedCredential(
        storage: BiometricCredentialStorage,
        dbId: String,
        encrypted: Pair<ByteArray, ByteArray>,
        downgradedSeal: Boolean
    ) {
        storage.saveEncryptedCredential(dbId, encrypted.first, encrypted.second)
        // TASK-18：随快速解锁凭据登记设备绑定解锁通行密钥（best-effort，失败不影响本次解锁）
        if (unlockPasskeyManager?.enroll(dbId) == false) {
            debugLog.warn(TAG, "解锁通行密钥登记未成功，本次快速解锁回退为纯封印语义")
        }
        uiState.update {
            it.copy(
                isQuickUnlockAvailable = true,
                // ISSUE-P1-22：软件密钥封印 → 常驻声明随本次登记即刻生效（冷启动由确认记录推导）
                quickUnlockDowngraded = downgradedSeal
            )
        }
        debugLog.info(TAG, "生物识别凭据登记成功")
    }

    /**
     * ISSUE-P1-22：解锁页对「软件级快速解锁降级」确认弹窗的用户决定上行
     * （true = 仍要启用；false = 不启用）。无挂起中的确认时幂等空操作。
     */
    fun completeDowngradeConsent(confirmed: Boolean) {
        downgradeConsentDeferred?.complete(confirmed)
    }

    /**
     * ISSUE-P1-22：挂起等待用户对软件级降级的显式决定。
     * 置位 `quickUnlockDowngradeConsentPending` 驱动解锁页渲染确认弹窗；
     * 返回 null = 超时未决（fail-closed，跳过封印）。
     */
    private suspend fun awaitDowngradeConsent(): Boolean? {
        val deferred = CompletableDeferred<Boolean>()
        downgradeConsentDeferred = deferred
        uiState.update { it.copy(quickUnlockDowngradeConsentPending = true) }
        return try {
            withTimeoutOrNull(DOWNGRADE_CONSENT_TIMEOUT_MS) { deferred.await() }
        } finally {
            downgradeConsentDeferred = null
            uiState.update { it.copy(quickUnlockDowngradeConsentPending = false) }
        }
    }

    /**
     * 唤起 BiometricPrompt 并挂起等待一次性结果。
     *
     * 超时保护：宿主 Activity 正在销毁等极端情形下 BiometricPrompt 可能不回调，
     * 超时后按失败处理并解除挂起，杜绝协程永久悬挂导致解锁流程卡死。
     */
    private suspend fun awaitBiometricAuth(
        authManager: BiometricAuthManager,
        activity: FragmentActivity,
        cipher: Cipher
    ): BiometricResult {
        val deferred = CompletableDeferred<BiometricResult>()
        authManager.authenticate(
            activity = activity,
            title = activity.getString(R.string.unlock_biometric_enroll_title),
            subtitle = activity.getString(R.string.unlock_biometric_enroll_subtitle),
            authenticators = BiometricAuthManager.UNLOCK_AUTHENTICATORS,
            cipher = cipher
        ) { result -> deferred.complete(result) }

        return withTimeoutOrNull(BIOMETRIC_ENROLL_TIMEOUT_MS) { deferred.await() }
            // ISSUE-P3-14：超时结果同样只承载「错误码 + 内部诊断标识」，
            // 用户可见文案一律由消费侧按错误码映射资源，不在此写死任何语言文案
            ?: BiometricResult.Error(
                BiometricAuthManager.ERROR_AUTH_TIMEOUT,
                BiometricAuthManager.AUTH_TIMEOUT_DIAGNOSTIC
            )
    }

    companion object {
        private const val TAG = "Unlock"
        // 生物识别登记弹窗挂起等待上限：超时按失败处理，避免协程永久悬挂卡死解锁流程
        private const val BIOMETRIC_ENROLL_TIMEOUT_MS = 60_000L
        // ISSUE-P1-22：降级确认弹窗挂起等待上限（与登记弹窗同一量级；超时按未决 fail-closed 处理，
        // 避免确认弹窗长期驻留导致主密码明文滞留窗口无界）
        private const val DOWNGRADE_CONSENT_TIMEOUT_MS = 60_000L
    }
}

/**
 * ISSUE-P1-22：封印密钥供给结果——授权用加密 Cipher 与封印密钥**实际硬件落位等级**。
 *
 * 等级探测必须在封印密钥就绪之后执行（`prepareEncryptCipher` 按需建钥；
 * 密钥不存在时探测恒返回 UNKNOWN，无法反映真实落位）。
 * 注：因出现在公开的 [UnlockViewModel] 构造参数类型中，本类须为 public（仅数据承载，无行为面）。
 */
class SealedKeyProvision(
    val cipher: Cipher,
    val securityLevel: KeystoreManager.KeySecurityLevel
)
