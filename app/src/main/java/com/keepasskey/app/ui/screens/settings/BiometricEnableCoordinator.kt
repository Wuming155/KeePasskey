package com.keepasskey.app.ui.screens.settings

import androidx.fragment.app.FragmentActivity
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricCredentialStorage
import com.keepasskey.app.security.BiometricResult
import com.keepasskey.app.security.BiometricStatus
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.screens.unlock.BiometricFailureMessagePolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.crypto.Cipher

/**
 * ISSUE-P2-212：生物识别开关的即时 UI 状态。
 *
 * 拆出独立状态类型（而非并入 [SettingsUiState] 的语义字段）以便经投影层单点合流：
 * 本状态只由 [BiometricEnableCoordinator] 写入，与设置仓库的持久化值（`biometricEnabled`）
 * 正交——**开关的选中态只看持久化值**，本状态只表达「正在验证」与「一次性反馈」。
 */
internal data class BiometricToggleUiState(
    /** 正在等待生物识别验证（开关禁用 + UI 呈现进度） */
    val verifying: Boolean = false,
    /** 一次性反馈文案；null = 无待呈现提示 */
    val notice: UiMessage? = null
)

/**
 * 生物识别开关「开启前当场验证」协调器（ISSUE-P2-212）。
 *
 * ## 背景（用户报告的缺陷）
 * 原实现把 `biometricEnabled` 的出厂默认写成 `true`，但封印凭据只在**主密码解锁成功后**
 * 登记（见 `BiometricEnrollmentCoordinator`）——于是新装设备上出现「设置里开关显示已开、
 * 解锁页却没有任何生物识别入口」的静默失效。用户侧的期望是：
 * **开关默认关闭，由用户手动打开；打开的那一刻当场验证生物识别，验证通过才真正启用。**
 *
 * ## 本协调器的职责边界
 * - 关闭开关：落偏好（无需验证）并**撤销全部生物识别数据**——各库封印凭据、解锁通行密钥
 *   登记记录及其 Keystore 密钥一并删除（ISSUE-P2-253「关闭开关 = 删除」），同时清除残留提示；
 *   次序固定为**先落偏好关闸、再删数据**（偏好先为 false 则并发登记路径被入口拦截）；
 * - 打开开关：先校验设备具备可用的 Class 3 强生物识别（fail-closed），再发起一次
 *   BiometricPrompt 验证，**验证通过后才写入偏好**；取消 / 失败 / 系统错误一律不写入
 *   （开关受控，视觉自动回到关闭态），并给出对应提示。
 *
 * 若目标库已存在封印凭据，验证以该凭据的**解密 Cipher** 发起（`CryptoObject` 绑定）——
 * 既证明用户身份，也顺带证明凭据仍可解封；Cipher 准备失败（生物录入变更导致密钥吊销、
 * Keystore 不可达等）时清除陈旧凭据并退化为纯身份验证，登记交由下次主密码解锁完成。
 *
 * ## 敏感数据与安全约束
 * - 不承载任何主密码 / 密钥材料：本流程只做「身份确认」，封印载荷的生成仍只发生在
 *   主密码解锁后的 `BiometricEnrollmentCoordinator`（那里才有明文主密码可用）；
 * - 无强生物识别（含未录入）时**拒绝开启**，绝不因为「用户想要」而降级到锁屏凭据；
 * - 单次比对未通过（[BiometricResult.Failed]）不是终态：系统弹窗仍驻留等待重试，
 *   故本协调器忽略该回调继续挂起，避免「用户最终验证成功、开关却没开」的错位；
 *   [VERIFY_TIMEOUT_MS] 为兜底（宿主 Activity 销毁等极端情形下系统不回调）。
 *
 * ## 可测性
 * Android `BiometricPrompt` 与 `FragmentActivity` 无法在 JVM 单测中构造，故两处 Android
 * 触点以 `*Override` 属性开放替身（与 `BiometricEnrollmentCoordinator.sealKeyProvisionOverride`
 * 同一约定）；生产恒 null → 走真实实现。
 */
internal class BiometricEnableCoordinator(
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val activeDbId: () -> String?,
    private val biometricAuthManager: BiometricAuthManager?,
    private val biometricCredentialStorage: BiometricCredentialStorage?,
    private val strings: StringsProvider,
    private val debugLog: DebugLogBuffer,
    private val state: MutableStateFlow<BiometricToggleUiState> = MutableStateFlow(BiometricToggleUiState())
) {

    /** 测试替身：替代真实「设备是否具备可用强生物识别」探测（生产恒 null） */
    internal var strongBiometricAvailableOverride: ((FragmentActivity?) -> Boolean)? = null

    /** 测试替身：替代真实 BiometricPrompt 发起（生产恒 null）。回调语义与系统一致。 */
    internal var promptOverride: ((FragmentActivity?, Cipher?, (BiometricResult) -> Unit) -> Unit)? = null

    /**
     * 开关切换入口。
     *
     * @param enabled 目标态；false 落偏好并撤销全部生物识别数据（无需验证），true 须先通过一次强生物识别验证
     * @param activity 宿主 Activity（发起 BiometricPrompt 必需；生产由设置页透传）
     */
    fun setEnabled(enabled: Boolean, activity: FragmentActivity?) {
        if (!enabled) {
            state.update { it.copy(verifying = false, notice = null) }
            scope.launch {
                // ISSUE-P2-253：先落偏好关闸（阻断并发登记），再撤销全部封印数据（关闭 = 删除）
                settingsRepository.setBiometricEnabled(false)
                biometricCredentialStorage?.revokeAllBiometricData()
            }
            return
        }
        // 验证进行中的重复点击为幂等空操作（避免并发发起多个 BiometricPrompt）
        if (state.value.verifying) return
        scope.launch { runEnableFlow(activity) }
    }

    /**
     * 开启流程：能力校验 → 可选凭据 Cipher 准备 → 生物识别验证 → 通过才落偏好。
     * 任何一条未通过路径都**不写偏好**（fail-closed），并给出可行动提示。
     */
    private suspend fun runEnableFlow(activity: FragmentActivity?) {
        state.update { it.copy(verifying = true, notice = null) }
        try {
            if (!isStrongBiometricAvailable(activity)) {
                debugLog.warn(TAG, "启用生物识别解锁被拒：设备无可用强生物识别（fail-closed，不写偏好）")
                state.update { it.copy(notice = UiMessage(R.string.sec_biometric_enable_unavailable)) }
                return
            }
            val dbId = activeDbId()
            val storage = biometricCredentialStorage
            val sealedIv = if (dbId != null && storage != null) {
                storage.getEncryptedCredential(dbId)?.first
            } else {
                null
            }
            val cipher = if (dbId != null && storage != null && sealedIv != null) {
                prepareSealCipher(storage, dbId, sealedIv)
            } else {
                null
            }

            when (val result = awaitVerification(activity, cipher)) {
                is BiometricResult.Success -> onVerified(cipher != null)
                is BiometricResult.Cancelled -> {
                    debugLog.info(TAG, "用户取消生物识别验证，开关保持关闭")
                    state.update { it.copy(notice = UiMessage(R.string.sec_biometric_auth_failed)) }
                }
                is BiometricResult.Error -> {
                    debugLog.warn(TAG, "生物识别验证失败: code=${result.errorCode}")
                    state.update { it.copy(notice = BiometricFailureMessagePolicy.of(result)) }
                }
                // Failed 不是终态（系统弹窗驻留等待重试），awaitVerification 已忽略该回调
                is BiometricResult.Failed -> state.update { it.copy(notice = UiMessage(R.string.sec_biometric_auth_failed)) }
            }
        } finally {
            state.update { it.copy(verifying = false) }
        }
    }

    /** 验证通过：落偏好；凭据未就绪时告知用户「下次主密码解锁后完成登记」。 */
    private suspend fun onVerified(sealReady: Boolean) {
        settingsRepository.setBiometricEnabled(true)
        state.update {
            it.copy(notice = if (sealReady) null else UiMessage(R.string.sec_biometric_enable_pending_seal))
        }
        debugLog.info(TAG, "生物识别解锁已启用（封印凭据就绪=$sealReady）")
    }

    /**
     * 设备是否具备「硬件存在且已录入」的 Class 3 强生物识别。
     * 未注入管理器 / 无宿主 Activity 一律按不可用处理（fail-closed）。
     */
    private fun isStrongBiometricAvailable(activity: FragmentActivity?): Boolean {
        strongBiometricAvailableOverride?.let { return it(activity) }
        val manager = biometricAuthManager ?: return false
        val host = activity ?: return false
        return manager.canAuthenticate(host, BiometricAuthManager.UNLOCK_AUTHENTICATORS) ==
            BiometricStatus.AVAILABLE
    }

    /**
     * 准备目标库封印凭据的解密 Cipher。
     *
     * 准备失败（密钥被生物录入变更吊销 / Keystore 不可达）意味着该凭据**永远解不开**：
     * 清除陈旧凭据并返回 null（本次退化为纯身份验证，登记交给下次主密码解锁），
     * 避免「开关开着、凭据却注定失败」的死循环态（与解锁侧同一处置口径）。
     */
    private fun prepareSealCipher(
        storage: BiometricCredentialStorage,
        databaseId: String,
        iv: ByteArray
    ): Cipher? = try {
        biometricAuthManager?.prepareDecryptCipher(databaseId, iv)
    } catch (e: Exception) {
        debugLog.warn(TAG, "封印凭据不可用（${e.javaClass.simpleName}），清除陈旧凭据并退化为纯身份验证")
        storage.clearCredential(databaseId)
        null
    }

    /**
     * 发起一次验证并挂起等待终态结果。
     *
     * [BiometricResult.Failed]（单次比对未通过）**不**完成挂起——系统弹窗仍驻留等待重试；
     * 超时（[VERIFY_TIMEOUT_MS]）按系统错误处理，杜绝协程永久悬挂。
     */
    private suspend fun awaitVerification(activity: FragmentActivity?, cipher: Cipher?): BiometricResult {
        val deferred = CompletableDeferred<BiometricResult>()
        val consume: (BiometricResult) -> Unit = { result ->
            if (result !is BiometricResult.Failed) deferred.complete(result)
        }
        val override = promptOverride
        if (override != null) {
            override(activity, cipher, consume)
        } else {
            launchPrompt(activity, cipher, consume)
        }
        return withTimeoutOrNull(VERIFY_TIMEOUT_MS) { deferred.await() }
            ?: BiometricResult.Error(
                BiometricAuthManager.ERROR_AUTH_TIMEOUT,
                BiometricAuthManager.AUTH_TIMEOUT_DIAGNOSTIC
            )
    }

    /** 生产发起路径：缺宿主 Activity / 管理器时以显式错误结果回落（绝不静默悬挂）。 */
    private fun launchPrompt(
        activity: FragmentActivity?,
        cipher: Cipher?,
        onResult: (BiometricResult) -> Unit
    ) {
        val manager = biometricAuthManager
        if (activity == null || manager == null) {
            onResult(
                BiometricResult.Error(
                    BiometricAuthManager.ERROR_NO_HOST_ACTIVITY,
                    BiometricAuthManager.NO_HOST_ACTIVITY_DIAGNOSTIC
                )
            )
            return
        }
        manager.authenticate(
            activity = activity,
            title = strings.get(R.string.sec_biometric_verify_title),
            subtitle = strings.get(R.string.sec_biometric_verify_subtitle),
            authenticators = BiometricAuthManager.UNLOCK_AUTHENTICATORS,
            cipher = cipher,
            negativeButtonText = strings.get(R.string.btn_cancel),
            onResult = onResult
        )
    }

    private companion object {
        private const val TAG = "Settings"

        /** 验证挂起等待上限：超时按失败处理，避免协程永久悬挂（与登记路径同一量级） */
        private const val VERIFY_TIMEOUT_MS = 60_000L
    }
}
