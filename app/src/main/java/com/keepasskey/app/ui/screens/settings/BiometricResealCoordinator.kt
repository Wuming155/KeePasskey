package com.keepasskey.app.ui.screens.settings

import androidx.fragment.app.FragmentActivity
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricCredentialStorage
import com.keepasskey.app.security.BiometricResult
import com.keepasskey.app.security.UnlockAuthPolicy
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.screens.unlock.BiometricSealedPayloadCodec
import com.keepasskey.app.ui.screens.unlock.SealedKeyProvision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.crypto.Cipher

/**
 * 改密后生物识别封印凭据重封印协调器（ISSUE-P2-398）。
 *
 * ## 缺陷背景
 * 快速解锁封印载荷（[BiometricSealedPayloadCodec] v1 帧 = 主密码 + 可选密钥文件因子）
 * 只在**主密码解锁成功后**由 `BiometricEnrollmentCoordinator` 登记；更换主密码链路
 * （`SettingsMasterKeyChangeController.submit → DatabaseSession.changeCredentials`）不触碰
 * 封印凭据——旧封印载荷内的密码已随改密失效。冷启动指纹解锁解封出旧密码，解库必然失败，
 * 走「清陈旧凭据 → 用户重输新密码 → 自动重新封印」恢复通道：用户表现为**改密后指纹失效，
 * 必须重输一次新密码**。
 *
 * ## 参考口径（KeePassDX）
 * 改密保存时以 BiometricPrompt 取授权 Cipher 把**新凭据重新加密写回**存储
 * （`DeviceUnlockViewModel.encryptCredential` → `CipherEncryptDatabase` 覆盖旧封印），
 * 指纹解锁随即对新凭据可用。本协调器即该语义在封印体系下的落地。
 *
 * ## 流程与失败语义
 * 前置：生物识别开关已开 **且** 活动库已有封印凭据（未登记时不动，下次主密码解锁自然登记新密码）。
 * 顺序：**先摘除陈旧封印**（内含旧密码，已确定失效；摘除后任何失败路径都不会把旧封印留成
 * 「注定失败态」）→ 供给封印密钥 → 强生物识别校验 → 以新密码 + 会话密钥文件因子编码载荷 →
 * 一次 BiometricPrompt 授权加密 → 落库。
 *
 * 任何失败路径（用户取消 / 授权失败 / 密钥供给失败 / 无宿主 Activity / 无强生物识别）
 * 一律 fail-safe：仅留痕日志，**不影响已成功的改密**；遗留恢复通道（下次主密码解锁自动
 * 重新封印）兜底。软件级降级未获确认（[UnlockAuthPolicy.requiresDowngradeConsent] 且无
 * 确认记录）时不建立封印，交由登记路径的确认闸门处理。
 *
 * ## 敏感数据铁律
 * 新密码以 `CharArray` 快照进入载荷编码，载荷明文与密钥文件快照均在 `finally` 中
 * `fill(0)` 显式清零；不落地为 `String`，不写日志明文。
 *
 * Android `BiometricPrompt` 与真实 Keystore 无法在 JVM 单测中构造，两处触点以
 * `*Override` 替身开放（与 `BiometricEnableCoordinator` 同一约定）；生产恒 null → 走真实实现。
 */
internal class BiometricResealCoordinator(
    private val settingsRepository: SettingsRepository,
    private val activeDbId: () -> String?,
    /** 会话绑定的密钥文件因子提供者（null = 未携带）；封印前快照克隆、用毕清零 */
    private val sessionKeyFileBytes: () -> ByteArray?,
    /**
     * 会话当前主密码快照提供者（ISSUE-P3-430：[resealAfterMasterKeyChange] 入参为 null＝
     * 密码分量未变，从这里取克隆快照封印）；调用方（本协调器）用毕清零。仅纯 JVM 单测可缺省。
     */
    private val sessionPasswordChars: () -> CharArray? = { null },
    private val biometricAuthManager: BiometricAuthManager?,
    private val biometricCredentialStorage: BiometricCredentialStorage?,
    private val strings: StringsProvider,
    private val debugLog: DebugLogBuffer,
    /** ISSUE-P1-429：重封印 keystore 密算与供给的调度器（生产恒 IO；单测注入 TestDispatcher 保确定性） */
    private val cryptoDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO
) {

    /** 测试替身：替代真实封印密钥供给（生产恒 null → [defaultSealKeyProvision]） */
    internal var sealKeyProvisionOverride: ((String) -> SealedKeyProvision?)? = null

    /** 测试替身：替代真实「设备是否具备可用强生物识别」探测（生产恒 null → [UnlockAuthPolicy.canSeal] 判定） */
    internal var strongBiometricCheckOverride: ((FragmentActivity?) -> Boolean)? = null

    /** 测试替身：替代真实 BiometricPrompt 发起（生产恒 null）。回调语义与系统一致。 */
    internal var promptOverride: ((FragmentActivity?, Cipher, (BiometricResult) -> Unit) -> Unit)? = null

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
            debugLog.warn(TAG, "重封印密钥不可用: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * 凭据变更成功后以新凭据重封印活动库的快速解锁凭据（ISSUE-P3-430 语义扩展）。
     *
     * @param newPasswordChars 改密后的新主密码；**null = 密码分量未变**（仅改绑密钥文件，
     *   ISSUE-P3-430）——此时从 [sessionPasswordChars] 取会话当前主密码快照封印，
     *   该快照归本方法所有、用毕 `finally` 清零。取不到（仅密钥文件会话等）时留痕跳过，
     *   交由下次主密码解锁的登记路径自然重封印。
     * 幂等安全：任意前置不满足即空操作；绝不抛出（调用方为改密任务收尾段）。
     */
    suspend fun resealAfterMasterKeyChange(activity: FragmentActivity?, newPasswordChars: CharArray?) {
        val dbId = activeDbId() ?: return
        val storage = biometricCredentialStorage ?: return
        val settings = settingsRepository.getSettings().first()
        if (!settings.biometricEnabled) return
        // 未登记过封印（指纹解锁本就未启用在库）→ 无陈旧载荷，交由下次解锁自然登记
        if (!storage.hasEncryptedCredential(dbId)) return

        // 先摘除陈旧封印：内含旧密码，已确定失效；此后任何失败都不残留「注定失败态」
        storage.clearCredential(dbId)
        debugLog.info(TAG, "改密成功，摘除陈旧封印凭据并尝试重封印")

        // ISSUE-P1-429：供给含 keystore binder 调用（建钥 / 落位探测），移 IO 线程执行
        val provision = withContext(cryptoDispatcher) {
            (sealKeyProvisionOverride ?: defaultSealKeyProvision)(dbId)
        }
        if (provision == null) {
            debugLog.warn(TAG, "重封印中止：封印密钥供给失败（下次主密码解锁后自动重新封印）")
            return
        }
        // 软件级降级未经显式确认时不建立封印：登记路径的确认闸门未走过，
        // 此处静默降级等于绕过 ISSUE-P1-22 闸门；留痕后交由下次解锁登记流程处理
        if (UnlockAuthPolicy.requiresDowngradeConsent(provision.securityLevel) &&
            !settings.quickUnlockDowngradeAcknowledged
        ) {
            debugLog.warn(TAG, "重封印中止：封印密钥落位 ${provision.securityLevel} 未经降级确认")
            return
        }
        val authManager = biometricAuthManager ?: return
        val biometricOk = strongBiometricCheckOverride?.invoke(activity) ?: run {
            // 生产路径：无宿主 Activity 一律按不可用处理（fail-closed，与登记路径同一口径）
            val host = activity ?: return
            UnlockAuthPolicy.canSeal(authManager.canAuthenticate(host, BiometricAuthManager.UNLOCK_AUTHENTICATORS))
        }
        if (!biometricOk) {
            debugLog.warn(TAG, "重封印中止：设备无可用强生物识别（fail-closed）")
            return
        }

        // ISSUE-P3-430：入参 null = 密码分量未变（仅改绑密钥文件），从会话取当前主密码
        // 快照封印；快照归本方法所有，sealCompositePayload 返回后（成败皆然）就地清零。
        // 取不到（仅密钥文件会话等）时留痕跳过，交由下次主密码解锁的登记路径自然重封印。
        val passwordChars: CharArray
        val erasePassword: Boolean
        if (newPasswordChars != null) {
            passwordChars = newPasswordChars
            erasePassword = false
        } else {
            val snapshot = sessionPasswordChars()
            if (snapshot == null) {
                debugLog.warn(TAG, "重封印中止：密码分量未变但取不到会话主密码快照")
                return
            }
            passwordChars = snapshot
            erasePassword = true
        }
        try {
            sealCompositePayload(authManager, activity, provision, passwordChars)?.let { sealed ->
                // 弹窗挂起期间开关可能已被关闭（关闭 = 删除）：落库前复核偏好，
                // 杜绝「撤销刚执行、重封印又写回」的竞态（与登记路径 ISSUE-P2-253 同一批关闸）
                if (!settingsRepository.getSettings().first().biometricEnabled) {
                    debugLog.info(TAG, "重封印授权挂起期间生物识别开关已关闭，放弃落库")
                    return
                }
                storage.saveEncryptedCredential(dbId, sealed.first, sealed.second)
                debugLog.info(TAG, "生物识别封印凭据已随改密重封印")
            }
        } finally {
            if (erasePassword) passwordChars.fill('0')
        }
    }

    /**
     * 编码并授权密封新密码载荷（敏感字节全程 `finally` 显式清零）。
     * @return `iv to 密文`；取消 / 授权失败 / 异常时为 null（各分支均已留痕）
     */
    private suspend fun sealCompositePayload(
        authManager: BiometricAuthManager,
        activity: FragmentActivity?,
        provision: SealedKeyProvision,
        newPasswordChars: CharArray
    ): Pair<ByteArray, ByteArray>? {
        return try {
            val keyFileSnapshot = sessionKeyFileBytes()?.copyOf()
            val bytes = BiometricSealedPayloadCodec.encode(newPasswordChars, keyFileSnapshot)
            keyFileSnapshot?.fill(0)
            try {
                authorizeAndSeal(authManager, activity, provision.cipher, bytes)
            } finally {
                bytes.fill(0)
            }
        } catch (e: Exception) {
            debugLog.warn(TAG, "生物识别重封印异常: ${e.javaClass.simpleName}")
            null
        }
    }

    /** 取得一次 Class 3 授权后以授权 Cipher 密封 [payload]；四条结果路径均如实留痕。 */
    private suspend fun authorizeAndSeal(
        authManager: BiometricAuthManager,
        activity: FragmentActivity?,
        cipher: Cipher,
        payload: ByteArray
    ): Pair<ByteArray, ByteArray>? {
        val sealed = when (val authResult = awaitBiometricAuth(authManager, activity, cipher)) {
            is BiometricResult.Success -> {
                val authedCipher = authResult.cipher
                if (authedCipher == null) {
                    debugLog.warn(TAG, "重封印未取得授权 Cipher，跳过")
                    null
                } else {
                    // ISSUE-P1-429：授权 Cipher 的 keystore 密算（StrongBox 分块加密可达秒级）
                    // 绝不上主线程——载荷含密钥文件字节时最大 1 MiB
                    withContext(cryptoDispatcher) { authedCipher.doFinal(payload) }
                }
            }
            is BiometricResult.Cancelled -> {
                debugLog.info(TAG, "用户取消重封印（下次主密码解锁后自动重新封印）")
                null
            }
            is BiometricResult.Error -> {
                debugLog.warn(TAG, "重封印授权失败: ${authResult.errString}")
                null
            }
            is BiometricResult.Failed -> {
                debugLog.warn(TAG, "重封印授权未通过")
                null
            }
        }
        return sealed?.let { cipher.iv to it }
    }

    /**
     * 唤起 BiometricPrompt 并挂起等待一次性结果；超时按失败处理，
     * 杜绝协程永久悬挂卡死改密任务收尾（与登记路径同一量级）。
     */
    private suspend fun awaitBiometricAuth(
        authManager: BiometricAuthManager,
        activity: FragmentActivity?,
        cipher: Cipher
    ): BiometricResult {
        val deferred = CompletableDeferred<BiometricResult>()
        val override = promptOverride
        if (override != null) {
            override(activity, cipher) { result -> deferred.complete(result) }
        } else {
            // 生产路径必须有宿主 Activity（触发侧已由强生物识别闸门保证非空；此处兜底防误用）
            val host = activity
            if (host == null) {
                deferred.complete(
                    BiometricResult.Error(
                        BiometricAuthManager.ERROR_NO_HOST_ACTIVITY,
                        BiometricAuthManager.NO_HOST_ACTIVITY_DIAGNOSTIC
                    )
                )
            } else {
                authManager.authenticate(
                    activity = host,
                    title = strings.get(R.string.sec_biometric_reseal_title),
                    subtitle = strings.get(R.string.sec_biometric_reseal_subtitle),
                    authenticators = BiometricAuthManager.UNLOCK_AUTHENTICATORS,
                    cipher = cipher
                ) { result -> deferred.complete(result) }
            }
        }
        return withTimeoutOrNull(RESEAL_TIMEOUT_MS) { deferred.await() }
            ?: BiometricResult.Error(
                BiometricAuthManager.ERROR_AUTH_TIMEOUT,
                BiometricAuthManager.AUTH_TIMEOUT_DIAGNOSTIC
            )
    }

    companion object {
        private const val TAG = "Settings"

        /** 重封印授权弹窗挂起等待上限（与登记路径同一量级；超时按失败 fail-closed 处理） */
        private const val RESEAL_TIMEOUT_MS = 60_000L
    }
}
