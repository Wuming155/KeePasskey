package com.keepasskey.app.ui.screens.unlock

import androidx.fragment.app.FragmentActivity
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.ThrottleGate
import com.keepasskey.app.security.UnlockThrottleManager
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 主密码解锁会话（ISSUE-P3-188 自 `UnlockViewModel` 结构性下沉，零行为变更）。
 *
 * 持有主密码 `CharArray` 缓冲区的全部生命周期（写入复制 / 各路径无条件清零），
 * 并承载「失败节流闸门 → 解锁尝试 → 成功收尾 / 失败分型」的完整流程。
 */
internal class MasterPasswordUnlockSession(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<UnlockUiState>,
    private val events: MutableSharedFlow<UnlockEvent>,
    private val vaultRepository: VaultRepository,
    private val unlockThrottleManager: UnlockThrottleManager?,
    private val keyFileSession: KeyFileSessionCoordinator,
    private val enrollment: BiometricEnrollmentCoordinator,
    private val debugLog: DebugLogBuffer,
    private val activeDbId: () -> String?
) {

    /**
     * 主密码敏感态：仅以 CharArray 驻留本会话内部（绝不进入 UiState/StateFlow）。
     * 更换内容与解锁完成后立即显式清零。
     */
    private var passwordChars = CharArray(0)

    /**
     * 主密码输入上行（来自 [com.keepasskey.app.ui.components.SecurePasswordField] 的 CharArray 桥接）。
     * 输入的数组仅在本次回调内有效，此处立即复制持有并清零上一份。
     */
    fun onPasswordChangeSecure(password: CharArray) {
        // F-25 同族整改（A6 报告「相邻发现」）：主密码**长度**亦属可导出调试缓冲的元数据，
        // 与库 id / 密钥文件长度同口径收敛，只记录事件本身
        debugLog.info(TAG, "onPasswordChangeSecure: input updated")
        passwordChars.fill('0')
        passwordChars = password.copyOf()
        uiState.update {
            it.copy(
                errorMessage = null,
                infoMessage = null,
                hasPassword = passwordChars.isNotEmpty()
            )
        }
    }

    /**
     * ISSUE-P3-117：离开解锁页（导航离开 / 页面进入后台）时的清理。
     *
     * 开关 `clearPasswordOnLeave`（设置页「离开密码页清空已输入字符」，默认关闭）**此前零行为
     * 消费方**——本方法即其唯一行为接线：开启时清空**未提交**的主密码缓冲区与输入框显示态，
     * 使「已输入但未提交」的主密码不再跨后台驻留（缩短明文驻留窗口）。
     *
     * @param clearOnLeave 偏好裁决结果（由调用方读取进程内共享快照后传入）
     */
    fun onScreenLeft(clearOnLeave: Boolean) {
        if (!clearOnLeave) return
        wipe()
        // 同步驱动输入组件擦除显示态（复用 ISSUE-P1-04 的令牌机制）
        uiState.update { it.copy(clearPasswordFieldToken = it.clearPasswordFieldToken + 1) }
    }

    /** 无条件擦除驻留的主密码字符数组（失败/成功/异常/锁定各路径共用） */
    fun wipe() {
        passwordChars.fill('0')
        passwordChars = CharArray(0)
        uiState.update { it.copy(hasPassword = false) }
    }

    /**
     * 主密码解锁（原 `UnlockViewModel.unlock`，逐行搬运）。
     *
     * @param activity 宿主 Activity。仅用于「首次登记生物识别凭据」时唤起 BiometricPrompt；
     *   为 null 时登记跳过（fail-closed），**不影响本次解锁**。
     */
    fun unlock(activity: FragmentActivity? = null) {
        scope.launch {
            val dbId = activeDbId()

            // ISSUE-P1-04：失败节流闸门——锁定期内 fail-closed 直接拒绝，绝不触碰 KDF/解密管线，
            // 并同步清零驻留主密码与输入框显示态（避免锁定期间明文滞留堆内存）
            val gate = dbId?.let { unlockThrottleManager?.gate(it) }
            if (gate is ThrottleGate.Locked) {
                wipe()
                uiState.update {
                    it.copy(
                        isLoading = false,
                        throttleFailureCount = gate.failureCount,
                        throttleLockoutRemainingMs = gate.remainingMs,
                        clearPasswordFieldToken = it.clearPasswordFieldToken + 1,
                        errorMessage = lockoutMessage(gate.remainingMs)
                    )
                }
                return@launch
            }

            // P1-10：已选择密钥文件时空密码合法（仅密钥文件解锁，对齐官方 KeePass
            // 解锁框对空密码不添加密码分量的语义）；密码与密钥文件均缺失才拦截
            if (passwordChars.isEmpty() && keyFileSession.keyFileData == null) {
                uiState.update { it.copy(errorMessage = UiMessage(R.string.unlock_error_empty_password)) }
                return@launch
            }
            uiState.update { it.copy(isLoading = true, errorMessage = null) }
            // ISSUE-P3-04：本次尝试是否携带密钥文件因子（决定失败语义分型与记忆写入）
            val usedKeyFile = keyFileSession.keyFileData != null

            try {
                when (val result = vaultRepository.unlockActiveDatabase(
                    passwordChars,
                    keyFileData = keyFileSession.keyFileData,
                    readOnly = uiState.value.openReadOnly
                )) {
                    is KdbxResult.Success -> onUnlockSuccess(activity, dbId, usedKeyFile)
                    is KdbxResult.Failure -> onUnlockFailure(dbId, usedKeyFile, result)
                }
            } finally {
                // ISSUE-P1-04：兜底无条件清零——原实现判据为 `if (_uiState.value.isLoading)`，
                // 而失败分支已先将 isLoading 置 false，导致失败态主密码永不清零、持续驻留堆内存。
                // 现改为无条件清零，异常/失败/成功各路径均不残留主密码明文。
                wipe()
            }
        }
    }

    /** 解锁成功收尾：节流复位 → 生物识别登记（须在擦除主密码前）→ 密钥文件记忆与擦除 → 发事件。 */
    private suspend fun onUnlockSuccess(activity: FragmentActivity?, dbId: String?, usedKeyFile: Boolean) {
        debugLog.info(TAG, "主密码解锁成功")
        // 成功解锁：清零失败计数与锁定状态（节流状态机复位）
        dbId?.let { unlockThrottleManager?.registerSuccess(it) }
        // 快速解锁凭据登记：必须在擦除主密码之前完成（登记需要明文主密码）
        enrollment.requestBiometricEnrollment(activity, passwordChars)
        // ISSUE-P3-04：解锁成功 → 按偏好记忆本次使用的密钥文件
        // （仅 Uri + 显示名；显示名须在下方状态复位前读取）
        keyFileSession.rememberKeyFileOnSuccess(usedKeyFile, uiState.value.keyFileName)
        // 解锁成功后立即擦除驻留的密钥文件字节（会话已克隆缓存供保存使用）
        keyFileSession.wipe()
        uiState.update {
            it.copy(
                isLoading = false,
                hasKeyFile = false,
                keyFileName = "",
                throttleFailureCount = 0,
                throttleLockoutRemainingMs = 0L,
                // ISSUE-P3-01：主密码解锁成功即用尽本实例的自动唤起机会，
                // 防止解锁后残留的状态重算把用户重新拉回快速解锁界面/再次弹窗
                biometricAutoPrompt = BiometricAutoPrompt.CONSUMED
            )
        }
        events.emit(UnlockEvent.UnlockSuccess)
    }

    /** 解锁失败分型：仅凭据错误计入节流；携带密钥文件时给出并列可行动提示（ISSUE-P3-04）。 */
    private fun onUnlockFailure(dbId: String?, usedKeyFile: Boolean, result: KdbxResult.Failure) {
        val invalidCredentials =
            result.error is com.keepasskey.database.exception.KdbxInvalidCredentialsException
        // F-25 整改：失败留痕口径收敛为「异常类名 + 布尔判定」——原实现把库 id
        // （activeDb）、密钥文件长度（keyFileLen）与异常原文 message 一并写进可导出的
        // 调试日志缓冲，泄漏「用户在解锁哪个库 / 是否携带密钥文件及其大小」。
        // 与仓内其它调用点同一口径（如 SafKeyFileAccess「仅留痕异常类名，不外传异常 message」）。
        debugLog.error(
            TAG,
            "主密码解锁失败: errType=${result.error.javaClass.simpleName}, " +
                "invalidCreds=$invalidCredentials"
        )
        // ISSUE-P1-04：仅「凭据错误」计入暴力破解节流；IO/文件损坏等非认证失败不计入，避免瞬时故障误锁
        val newGate = if (invalidCredentials) {
            dbId?.let { unlockThrottleManager?.registerFailure(it) }
        } else {
            null
        }
        val errorMsg = when {
            newGate is ThrottleGate.Locked -> lockoutMessage(newGate.remainingMs)
            // ISSUE-P3-04：携带密钥文件时的凭据失败——KDBX 复合密钥在一次
            // HMAC 校验中协议上无法判定具体是哪个因子错，故给出并列可行动提示
            // （不谎称「主密码错」，也不新造底层不存在的分型异常）
            invalidCredentials && usedKeyFile ->
                UiMessage(R.string.keyfile_or_password_mismatch)
            invalidCredentials -> UiMessage(R.string.unlock_error_invalid_password)
            else -> UiMessage(R.string.op_failed, listOf(result.message))
        }
        // ISSUE-P1-04：失败路径无条件清零主密码（不再保留错误密码驻留堆内存）
        wipe()
        uiState.update {
            it.copy(
                isLoading = false,
                errorMessage = errorMsg,
                // 通知输入组件同步擦除显示态，与 VM 清零保持一致（用户须重新输入后重试）
                clearPasswordFieldToken = it.clearPasswordFieldToken + 1,
                throttleFailureCount = newGate?.failureCount ?: it.throttleFailureCount,
                throttleLockoutRemainingMs =
                    (newGate as? ThrottleGate.Locked)?.remainingMs ?: 0L
            )
        }
    }

    /**
     * 将锁定剩余时长映射为本地化 [UiMessage]：≥1 分钟按分钟（向上取整）呈现，否则按秒。
     * 数值计算内联、文案交由字符串资源，杜绝硬编码文案泄漏到代码层。
     */
    private fun lockoutMessage(remainingMs: Long): UiMessage {
        val totalSeconds = (remainingMs + 999L) / 1000L
        return if (totalSeconds >= 60L) {
            val minutes = (totalSeconds + 59L) / 60L
            UiMessage(R.string.unlock_error_locked_out_minutes, listOf(minutes.toInt()))
        } else {
            UiMessage(R.string.unlock_error_locked_out_seconds, listOf(totalSeconds.toInt()))
        }
    }

    private companion object {
        private const val TAG = "Unlock"
    }
}
