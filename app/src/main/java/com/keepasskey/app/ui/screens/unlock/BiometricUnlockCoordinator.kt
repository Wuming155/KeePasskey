package com.keepasskey.app.ui.screens.unlock

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.fragment.app.FragmentActivity
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricCredentialStorage
import com.keepasskey.app.security.BiometricResult
import com.keepasskey.app.security.UnlockPasskeyGate
import com.keepasskey.app.security.UnlockPasskeyManager
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.crypto.Cipher

/**
 * 生物识别解锁协调器（ISSUE-P3-25 结构拆分，纯搬运零行为变更）。
 *
 * 承载原 `UnlockViewModel` 内生物识别解锁状态机：`BiometricAutoPrompt` 的
 * `IDLE→PENDING→CONSUMED` 生命周期、[UnlockModePolicy] 派生的解锁模式重算、
 * 解封与解锁收尾。字段名、清零时机、协程作用域（`viewModelScope`）与顺序均与原实现逐字一致。
 *
 * 另承载两处跨文件入口（原先为 `UnlockViewModel` 的可测入口，可见性语义不变）：
 * [handleBiometricResult] 与 [completeBiometricUnlock]。
 */
internal class BiometricUnlockCoordinator(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<UnlockUiState>,
    private val events: MutableSharedFlow<UnlockEvent>,
    private val vaultRepository: VaultRepository,
    private val activeDbId: () -> String?,
    private val biometricAuthManager: BiometricAuthManager?,
    private val biometricCredentialStorage: BiometricCredentialStorage?,
    private val unlockPasskeyManager: UnlockPasskeyManager?,
    private val debugLog: DebugLogBuffer
) {

    /**
     * 本次进入解锁页**显式选择**的解锁模式（null = 尚未显式选择，由可用性推导）。
     *
     * ISSUE-P3-01 根因修复：`unlockMode` 的推导依赖「生物识别开关」（设置流）与
     * 「封印凭据存在性」（数据库流）两条独立异步源，抵达顺序不定。原实现在设置流抵达时
     * 用当时的可用性计算一次即定终态，先到者决定结果 → 真机出现「开关已开仍停在主密码界面」。
     * 现改为：本字段记录用户/系统的显式选择（非空即优先），其余情况由
     * [UnlockModePolicy] 在任一异步源变化后统一重算。
     */
    private var unlockModeSelection: UnlockMode? = null

    /**
     * 依据「生物识别开关 + 封印凭据 + 活动库」重算解锁模式与自动唤起判定（ISSUE-P3-01）。
     *
     * 关键点：设置流与数据库流是两条独立异步源，抵达顺序不确定；本方法在**任一路径抵达后**
     * 统一调用，使解锁模式与自动唤起判定只取决于两者的最终取值，而非抵达先后。
     * 自动唤起判定另经 [BiometricAutoPromptPolicy] 状态机，`CONSUMED` 为不可逆终态。
     */
    fun refreshUnlockModeAndAutoPrompt() {
        val state = uiState.value
        val mode = UnlockModePolicy.resolve(
            biometricEnabled = state.isBiometricEnabled,
            quickUnlockAvailable = state.isQuickUnlockAvailable,
            explicitSelection = unlockModeSelection
        )
        val prompt = BiometricAutoPromptPolicy.next(
            current = state.biometricAutoPrompt,
            biometricEnabled = state.isBiometricEnabled,
            quickUnlockAvailable = state.isQuickUnlockAvailable,
            unlockMode = mode,
            hasDatabase = state.hasDatabase
        )
        uiState.update { it.copy(unlockMode = mode, biometricAutoPrompt = prompt) }
    }

    /**
     * 回落主密码输入模式（生物识别取消 / 系统错误 / 解封密钥失效等）。
     *
     * 同时把模式登记为**显式选择**，使后续异步状态重算不会把用户重新拖回快速解锁界面；
     * 该「显式选择」不影响一次性自动唤起状态机——后者在消费时已进入 `CONSUMED` 终态，
     * 因此「取消 → 回落主密码 → 再次自动弹出」这条死循环链路在结构上不可达（ISSUE-P3-01 验收 3）。
     */
    private fun fallbackToMasterPasswordMode() {
        unlockModeSelection = UnlockMode.STANDARD
        uiState.update { it.copy(unlockMode = UnlockMode.STANDARD) }
    }

    /** 手动切换解锁模式：登记为显式选择，后续异步状态重算不再覆盖（ISSUE-P3-01） */
    fun switchUnlockMode(mode: UnlockMode) {
        unlockModeSelection = mode
        uiState.update { it.copy(unlockMode = mode, errorMessage = null, infoMessage = null) }
    }

    /**
     * 解锁页一次性意图：消费「应自动唤起生物识别」的判定，并立即发起一次认证（ISSUE-P3-01）。
     *
     * Screen 只在 `LaunchedEffect(uiState.biometricAutoPrompt)` 中**透传**本调用，不做任何条件判断；
     * 判定条件（开关 / 封印凭据 / 快速解锁模式 / 活动库）与「已消费」守卫全部收在本协调器：
     * - 仅当状态机处于 [BiometricAutoPrompt.PENDING] 时真正发起 → 每次进入解锁页**至多一次**；
     * - 发起前先推进为 [BiometricAutoPrompt.CONSUMED]（不可逆终态）→ 取消 / 失败 / 重组 /
     *   切后台回前台都不可能再次自动弹窗（原实现在 Composable 内直接判断条件，
     *   任何状态抖动都会重复满足条件，这正是重复弹窗/死循环的结构性来源）。
     *
     * 无论本次认证是否最终成功，调用返回后自动唤起机会即已用尽；之后只能由用户显式点击按钮触发。
     *
     * @return true 表示本次调用确实发起了一次自动唤起；false 表示无待消费判定（幂等空操作）
     */
    fun onBiometricAutoPromptRequested(activity: FragmentActivity?): Boolean {
        if (uiState.value.biometricAutoPrompt != BiometricAutoPrompt.PENDING) return false
        uiState.update { it.copy(biometricAutoPrompt = BiometricAutoPrompt.CONSUMED) }
        unlockWithBiometric(activity)
        return true
    }

    /**
     * 解锁通行密钥断言验证（TASK-18 / ISSUE-P1-09 fail-closed 化）。
     *
     * 每次快速解锁生成一次性随机 challenge，要求硬件私钥（已绑定强生物识别认证
     * 时间窗）对 AuthenticatorData || SHA-256(clientDataJSON) 签名，并本地复核
     * clientDataJSON 规范性（type/challenge/origin）、rpIdHash 归属与 signCount
     * 严格单调。
     *
     * 未登记（记录被删/被篡改/未登记，[UnlockPasskeyGate.NotEnrolled]）与硬件
     * 签名失败一律 fail-closed——清除封印凭据与通行密钥登记（视为凭据被克隆/
     * 篡改），引导用户以主密码完整解锁后重新登记。原「旧凭据兼容通道」（未登记
     * 即跳过断言并后台补登记）已移除：任何能写应用私有数据者删除 3 个 key 即可
     * 一步绕过反克隆断言，静默放行语义不可接受。
     */
    private suspend fun verifyUnlockPasskey(
        storage: BiometricCredentialStorage,
        dbId: String
    ): Boolean {
        val passkeyManager = unlockPasskeyManager ?: return true
        val challenge = passkeyManager.newChallenge()
        val gate = passkeyManager.assertUnlock(dbId, challenge)
        val assertion = (gate as? UnlockPasskeyGate.AssertionReady)?.assertion
        if (assertion == null || !passkeyManager.verifyAndCommit(dbId, assertion, challenge)) {
            debugLog.warn(TAG, "解锁通行密钥断言不可用/未通过（gate=$gate），fail-closed 拒绝快速解锁")
            storage.clearCredential(dbId)
            passkeyManager.clear(dbId)
            uiState.update {
                it.copy(
                    isLoading = false,
                    isQuickUnlockAvailable = false,
                    errorMessage = UiMessage(R.string.unlock_passkey_verify_failed)
                )
            }
            fallbackToMasterPasswordMode()
            return false
        }
        return true
    }

    /**
     * 生物识别解锁：结合 AndroidX Biometric 与硬件 Keystore 解封。
     * 缺少宿主 Activity / 硬件依赖 / 活动数据库时一律 fail-closed（不假解锁、不发成功事件）。
     *
     * 本方法只负责「发起」认证（Android 依赖仅存于此）；结果处理统一收敛到
     * [handleBiometricResult]，以便 JVM 单测直接驱动成功/取消/失败三条路径。
     * 自动唤起与手动点击共用本方法，**不新造任何解密/解锁通道**。
     */
    fun unlockWithBiometric(activity: FragmentActivity? = null) {
        if (uiState.value.isLoading) return

        val storage = biometricCredentialStorage
        val authManager = biometricAuthManager
        val dbId = activeDbId()

        if (activity == null || storage == null || authManager == null || dbId == null) {
            // fail-closed：无真实生物识别上下文时不得伪造解锁成功
            uiState.update {
                it.copy(isLoading = false, errorMessage = UiMessage(R.string.sec_biometric_auth_failed))
            }
            fallbackToMasterPasswordMode()
            return
        }

        val cred = storage.getEncryptedCredential(dbId)
        if (cred == null) {
            uiState.update {
                it.copy(
                    isQuickUnlockAvailable = false,
                    errorMessage = UiMessage(R.string.unlock_error_empty_password)
                )
            }
            fallbackToMasterPasswordMode()
            return
        }

        uiState.update { it.copy(isLoading = true, errorMessage = null) }

        try {
            val decryptCipher = authManager.prepareDecryptCipher(dbId, cred.first)
            authManager.authenticate(
                activity = activity,
                title = activity.getString(R.string.unlock_biometric_title),
                subtitle = activity.getString(R.string.unlock_biometric_subtitle),
                authenticators = BiometricAuthManager.UNLOCK_AUTHENTICATORS,
                cipher = decryptCipher
            ) { result -> handleBiometricResult(result, storage, dbId, cred.second) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            // 系统指纹增删导致密钥作废：清空失效凭据与硬件密钥别名，提示用户使用主密码重新验证
            storage.clearCredential(dbId)
            unlockPasskeyManager?.clear(dbId)
            authManager.deleteKeyForDatabase(dbId)
            uiState.update {
                it.copy(
                    isLoading = false,
                    isQuickUnlockAvailable = false,
                    errorMessage = UiMessage(R.string.sec_biometric_key_invalidated)
                )
            }
            fallbackToMasterPasswordMode()
        } catch (e: Exception) {
            // 禁止静默失败：留痕异常类型（不外传裸异常 message），并由结果路径统一提示
            debugLog.warn(TAG, "生物识别解锁启动异常: ${e.javaClass.simpleName}")
            uiState.update {
                it.copy(isLoading = false, errorMessage = UiMessage(R.string.sec_biometric_auth_failed))
            }
            fallbackToMasterPasswordMode()
        }
    }

    /**
     * 生物识别结果统一处理入口（ISSUE-P3-01 可测性拆分）：
     * 与 Android `BiometricPrompt` 回调解耦，使「成功 / 用户取消 / 单次比对未通过 / 系统错误」
     * 四条结果路径可在 JVM 单测中直接驱动（硬件与 Activity 依赖仅保留在 [unlockWithBiometric] 启动侧）。
     *
     * 回退语义（ISSUE-P3-01 验收 3）：
     * - 用户主动取消 → 清除提示并回落主密码输入框（不再自动重试）；
     * - 系统错误（含完整性风险态）→ 按错误码映射资源文案后回落主密码输入框；
     * - 单次比对未通过（[BiometricResult.Failed]）→ 系统弹窗仍驻留等待重试，
     *   故保留快速解锁界面，仅置失败提示（自动唤起机会已用尽，不会重复弹窗）。
     */
    internal fun handleBiometricResult(
        result: BiometricResult,
        storage: BiometricCredentialStorage,
        dbId: String,
        sealedCiphertext: ByteArray
    ) {
        when (result) {
            is BiometricResult.Success -> startBiometricUnlock(result.cipher, storage, dbId, sealedCiphertext)
            is BiometricResult.Cancelled -> {
                uiState.update { it.copy(isLoading = false, errorMessage = null, infoMessage = null) }
                fallbackToMasterPasswordMode()
            }
            is BiometricResult.Error -> {
                // ISSUE-P3-14：errString 为内部诊断标识，仅落日志；用户可见文案经错误码映射到已资源化字符串
                debugLog.warn(TAG, "生物识别认证失败: code=${result.errorCode}")
                uiState.update {
                    it.copy(isLoading = false, errorMessage = BiometricFailureMessagePolicy.of(result))
                }
                fallbackToMasterPasswordMode()
            }
            is BiometricResult.Failed -> {
                uiState.update {
                    it.copy(isLoading = false, errorMessage = UiMessage(R.string.sec_biometric_auth_failed))
                }
            }
        }
    }

    /**
     * 生物识别授权通过后的解封阶段：以授权 Cipher 解封封印凭据，再交 [completeBiometricUnlock] 走既有解锁管线。
     * 解封失败（密钥被生物录入变更吊销 / 旧密钥迁移重建等）一律清除陈旧凭据并回落主密码，
     * 杜绝「永远解不开又永不重登记」的死循环态。
     */
    private fun startBiometricUnlock(
        authedCipher: Cipher?,
        storage: BiometricCredentialStorage,
        dbId: String,
        sealedCiphertext: ByteArray
    ) {
        if (authedCipher == null) {
            uiState.update { it.copy(isLoading = false) }
            return
        }
        scope.launch {
            try {
                val decryptedBytes = authedCipher.doFinal(sealedCiphertext)
                // TASK-18 / ISSUE-P1-09：生物识别门控通过后，执行解锁通行密钥断言
                // （随机 challenge + clientDataJSON + 认证绑定私钥签名 + signCount
                // 严格单调防克隆；记录缺失/被删与签名失败均 fail-closed）
                if (verifyUnlockPasskey(storage, dbId)) {
                    completeBiometricUnlock(decryptedBytes, storage, dbId)
                }
            } catch (e: Exception) {
                // ISSUE-P1-08 迁移容错：密钥经生物录入变更吊销（KeyPermanentlyInvalidated）
                // 或旧「BIOMETRIC_STRONG|DEVICE_CREDENTIAL」密钥迁移重建后，
                // 旧封印密文解密必然失败（AEADBadTagException 等）——清除陈旧凭据，
                // 下次主密码解锁自动重新封印，杜绝「永远解不开又永不重登记」的死循环态
                debugLog.warn(TAG, "生物识别解封失败: ${e.javaClass.simpleName}")
                storage.clearCredential(dbId)
                unlockPasskeyManager?.clear(dbId)
                uiState.update {
                    it.copy(
                        isLoading = false,
                        isQuickUnlockAvailable = false,
                        errorMessage = UiMessage(R.string.unlock_error_invalid_password)
                    )
                }
            }
        }
    }

    /**
     * 解封出凭据后的解锁收尾（ISSUE-P3-01 可测性拆分 / ISSUE-P2-23 复合载荷）：
     * 载荷经 [BiometricSealedPayloadCodec] 解析（v1 复合帧 = 主密码 + 可选密钥文件；
     * 不带魔数回落历史格式 = 纯主密码）→ 既有 [VaultRepository.unlockActiveDatabase]
     * 管线（两因子原样送达），成功即发解锁事件。全程 `ByteArray` / `CharArray` 承载，
     * 用毕在 `finally` 中显式清零，绝不落地为 `String`。
     * 帧结构损坏（异常抛出）由调用方按「凭据陈旧」清除并引导重新封印。
     */
    internal suspend fun completeBiometricUnlock(
        decryptedBytes: ByteArray,
        storage: BiometricCredentialStorage,
        dbId: String
    ) {
        val payload = BiometricSealedPayloadCodec.decode(decryptedBytes)
        try {
            when (val unlockResult = vaultRepository.unlockActiveDatabase(
                payload.passwordChars,
                keyFileData = payload.keyFileData
            )) {
                is KdbxResult.Success -> {
                    uiState.update {
                        it.copy(
                            isLoading = false,
                            // ISSUE-P3-01：生物识别解锁成功同样用尽自动唤起机会（终态不可逆）
                            biometricAutoPrompt = BiometricAutoPrompt.CONSUMED
                        )
                    }
                    events.emit(UnlockEvent.UnlockSuccess)
                }
                is KdbxResult.Failure -> {
                    // 生物识别已授权且密文成功解密，却解库失败：
                    // 极可能是主密码已变更（或密钥文件因子更换）导致入库凭据陈旧（死循环态）。
                    // 清除陈旧凭据，下次主密码解锁将自动重新登记。
                    storage.clearCredential(dbId)
                    uiState.update {
                        it.copy(
                            isLoading = false,
                            isQuickUnlockAvailable = false,
                            errorMessage = UiMessage(R.string.unlock_error_invalid_password)
                        )
                    }
                }
            }
        } finally {
            payload.wipe()
            decryptedBytes.fill(0)
        }
    }

    companion object {
        private const val TAG = "Unlock"
    }
}
