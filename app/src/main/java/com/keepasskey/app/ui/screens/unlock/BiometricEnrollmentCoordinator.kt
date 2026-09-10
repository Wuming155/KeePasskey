package com.keepasskey.app.ui.screens.unlock

import androidx.biometric.BiometricManager
import androidx.fragment.app.FragmentActivity
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricCredentialStorage
import com.keepasskey.app.security.BiometricResult
import com.keepasskey.app.security.UnlockAuthPolicy
import com.keepasskey.app.security.UnlockPasskeyManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
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
    private val hasKeyFile: () -> Boolean,
    private val biometricAuthManager: BiometricAuthManager?,
    private val biometricCredentialStorage: BiometricCredentialStorage?,
    private val unlockPasskeyManager: UnlockPasskeyManager?,
    private val debugLog: DebugLogBuffer
) {

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
     * 敏感数据设计考量与边界说明 (Wave 3-E P2-18)：
     * 消费 [CharArray]，经 CharBuffer 转为临时 UTF-8 字节并在 finally 块中立即显式清零，
     * 杜绝密码以持久明文字符串穿越硬件加密管线。
     *
     * 失败语义：登记失败（用户取消 / 硬件缺失 / 无宿主 Activity）一律 fail-safe——
     * 仅留痕日志，**不影响本次主密码解锁**。
     */
    suspend fun requestBiometricEnrollment(
        activity: FragmentActivity?,
        passwordChars: CharArray
    ) {
        // 修复虚假开关整改：复合密钥库（主密码 + 密钥文件）的密钥文件因子无法经
        // Keystore 封印还原，持久化凭据将永远无法独立完成解锁——直接不保存，fail-safe
        if (hasKeyFile()) return
        val dbId = activeDbId() ?: return
        val storage = biometricCredentialStorage ?: return
        val authManager = biometricAuthManager ?: return
        val settings = settingsRepository.getSettings().first()
        if (!settings.biometricEnabled) return
        // 已登记过则不再重复弹窗（仅首次 + 凭据被清除后重新登记）
        if (storage.hasEncryptedCredential(dbId)) return

        if (activity == null) {
            debugLog.warn(TAG, "生物识别凭据未登记：缺少宿主 Activity，本次跳过（fail-closed，不影响解锁）")
            return
        }

        // ISSUE-P1-08：封印闸门——设备须具备「硬件存在且已录入」的 Class 3 强生物识别，
        // 无强生物（含未录入）时禁用封印（fail-closed），不降级到弱锁屏凭据路径
        val biometricStatus = authManager.canAuthenticate(
            activity,
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        )
        if (!UnlockAuthPolicy.canSeal(biometricStatus)) {
            debugLog.warn(TAG, "生物识别凭据未登记：设备无可用强生物识别（$biometricStatus），禁用封印（fail-closed）")
            return
        }

        val encrypted = try {
            val cipher = authManager.prepareEncryptCipher(dbId)
            val charBuffer = CharBuffer.wrap(passwordChars)
            val byteBuffer = StandardCharsets.UTF_8.encode(charBuffer)
            val bytes = ByteArray(byteBuffer.remaining())
            byteBuffer.get(bytes)
            try {
                val authResult = awaitBiometricAuth(
                    authManager = authManager,
                    activity = activity,
                    cipher = cipher
                )
                when (authResult) {
                    is BiometricResult.Success -> {
                        val authedCipher = authResult.cipher
                        if (authedCipher == null) {
                            debugLog.warn(TAG, "生物识别登记未取得授权 Cipher，跳过封印")
                            null
                        } else {
                            authedCipher.doFinal(bytes)
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
                }?.let { cipher.iv to it }
            } finally {
                bytes.fill(0)
                byteBuffer.clear()
                if (byteBuffer.hasArray()) {
                    byteBuffer.array().fill(0)
                }
            }
        } catch (e: Exception) {
            // 禁止静默失败：任何异常一律留痕，绝不 catch(ignored)
            debugLog.warn(TAG, "生物识别凭据登记异常: ${e.javaClass.simpleName} - ${e.message}")
            null
        }

        if (encrypted != null) {
            storage.saveEncryptedCredential(dbId, encrypted.first, encrypted.second)
            // TASK-18：随快速解锁凭据登记设备绑定解锁通行密钥（best-effort，失败不影响本次解锁）
            if (unlockPasskeyManager?.enroll(dbId) == false) {
                debugLog.warn(TAG, "解锁通行密钥登记未成功，本次快速解锁回退为纯封印语义")
            }
            uiState.update { it.copy(isQuickUnlockAvailable = true) }
            debugLog.info(TAG, "生物识别凭据登记成功")
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
    }
}
