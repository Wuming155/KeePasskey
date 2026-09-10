package com.keepasskey.app.ui.screens.unlock

import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.ui.model.UiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 密钥文件（复合密钥第二因子）会话协调器（ISSUE-P3-25 结构拆分，纯搬运零行为变更）。
 *
 * 承载原 `UnlockViewModel` 内 `keyFileData` 相关的全部逻辑：SAF 读取结果采纳、
 * 驻留字节的借用/克隆/清零、来源 Uri 登记、记忆写入与冷启动恢复。
 * 字段名、清零时机、协程作用域（`viewModelScope`）与 UiState 更新均与原实现逐字一致。
 *
 * 敏感数据铁律：密钥文件字节仅以 `ByteArray` 驻留本类内部（绝不进入 UiState/StateFlow/String）；
 * 覆盖采纳 / 取消选择 / 读取失败 / 解锁成功 / ViewModel 销毁五条路径均显式 `fill(0)` 清零。
 */
internal class KeyFileSessionCoordinator(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<UnlockUiState>,
    private val keyFileAccess: KeyFileAccess?,
    private val debugLog: DebugLogBuffer
) {

    /**
     * 密钥文件原始字节（修复虚假开关整改）：复合密钥「主密码 + 密钥文件」的第二因子。
     * 仅以 ByteArray 驻留本类内部（绝不进入 UiState/StateFlow/String），
     * 取消选择 / 解锁成功 / ViewModel 销毁时显式清零；会话成功后会自行克隆缓存供保存使用。
     */
    var keyFileData: ByteArray? = null
        private set

    /**
     * 密钥文件来源 SAF Uri（ISSUE-P3-04）：属**非密钥元数据**（可持久化、可比较），
     * 与 UiState 的 `keyFileName` 共同构成「记住上次成功解锁使用的密钥文件」的记录内容。
     * 为空表示本次尝试无可登记来源（未选择 / 字节由调用方直接提供 / 提供方不支持持久授权）
     * ——此时不写入记忆，也不覆盖既有记忆。
     */
    private var keyFileSourceUri: String? = null

    /**
     * 用户是否已在本会话**显式**选择/清除过密钥文件（ISSUE-P3-04）：
     * 记忆恢复为异步 IO 流程，若用户抢先手动选择，则丢弃恢复结果，尊重用户显式选择。
     */
    private var keyFileUserTouched = false

    /**
     * 密钥文件 SAF 选取结果上行（ISSUE-P3-04）：组合层只上传 Uri，
     * 读取上限、流关闭、缓冲擦除、持久化读授权与记忆登记全部由 [KeyFileAccess] 在 IO 线程承担
     * （原实现在 Composable 内自行读流，规则上属「Screen 写业务逻辑」）。
     */
    fun onKeyFileSelected(uri: String) {
        scope.launch {
            val access = keyFileAccess
            if (access == null) {
                debugLog.warn(TAG, "密钥文件读取通道不可用，拒绝以未读取的字节参与解锁")
                onKeyFileReadFailed()
                return@launch
            }
            when (val outcome = access.read(uri)) {
                is KeyFileReadResult.Success -> {
                    try {
                        adoptKeyFile(outcome.bytes, outcome.displayName)
                    } finally {
                        // 移交后立即擦除读取结果（VM 内部持独立副本）
                        outcome.bytes.fill(0)
                    }
                    keyFileUserTouched = true
                    trackKeyFileSource(uri)
                }
                // 「读不到」分型：空文件 / 超限 / 流异常一律显式反馈，绝不静默忽略
                KeyFileReadResult.Empty,
                KeyFileReadResult.TooLarge,
                KeyFileReadResult.Unreadable -> onKeyFileReadFailed()
            }
        }
    }

    /**
     * 密钥文件字节上行（来自解锁页 SAF 选择器，修复虚假开关整改）。
     * 字节在本回调内即被复制持有，调用方（Screen）侧临时数组用毕自行清零。
     * 本入口不携带来源 Uri（ISSUE-P3-04 记忆登记需经 [onKeyFileSelected] 的 Uri 通道）。
     */
    fun onKeyFileSelected(data: ByteArray, fileName: String) {
        adoptKeyFile(data, fileName)
        keyFileUserTouched = true
    }

    /** 采纳密钥文件字节：覆盖驻留副本（旧副本显式清零）并同步「已选择 + 显示名」语义 */
    private fun adoptKeyFile(data: ByteArray, fileName: String) {
        keyFileData?.fill(0)
        keyFileData = data.copyOf()
        uiState.update {
            it.copy(hasKeyFile = true, keyFileName = fileName, errorMessage = null)
        }
    }

    /**
     * 取消密钥文件：擦除驻留字节并复位开关状态。
     *
     * ISSUE-P3-04 语义裁决：取消仅作用于**本次解锁尝试**，不撤销已记忆的记录
     * （记忆的写入/清除以「成功解锁实际使用的因子」为准），故此处不清除偏好中的 Uri。
     */
    fun clearKeyFile() {
        keyFileData?.fill(0)
        keyFileData = null
        keyFileSourceUri = null
        keyFileUserTouched = true
        uiState.update { it.copy(hasKeyFile = false, keyFileName = "") }
    }

    /**
     * 密钥文件读取失败（SAF 流打开/读取异常或超出大小上限）：
     * 显式反馈用户，绝不静默忽略（禁止静默失败纪律）
     */
    fun onKeyFileReadFailed() {
        keyFileData?.fill(0)
        keyFileData = null
        keyFileSourceUri = null
        keyFileUserTouched = true
        uiState.update {
            it.copy(hasKeyFile = false, keyFileName = "", errorMessage = UiMessage(R.string.unlock_keyfile_read_failed))
        }
    }

    /**
     * 登记密钥文件来源并按偏好申请持久化读授权（ISSUE-P3-04）。
     *
     * - 偏好关闭：不申请持久授权、不登记来源（最小权限原则：不记忆就不扩大持久授权面）；
     * - 提供方不支持持久化授权（[KeyFileAccess.persistReadPermission] 返回 false）：
     *   优雅降级——本次解锁仍可用（字节已在内存），但不记忆并给出可理解提示
     *   （否则下次冷启动恢复必然失败，用户无从理解）。
     */
    private fun trackKeyFileSource(uri: String) {
        val access = keyFileAccess ?: return
        scope.launch {
            val rememberEnabled = access.isRememberEnabled()
            if (!KeyFileRememberPolicy.shouldTrackSource(rememberEnabled, uri)) {
                keyFileSourceUri = null
                return@launch
            }
            if (access.persistReadPermission(uri)) {
                keyFileSourceUri = uri
            } else {
                keyFileSourceUri = null
                uiState.update {
                    it.copy(infoMessage = UiMessage(R.string.keyfile_permission_not_persisted))
                }
            }
        }
    }

    /**
     * 恢复上次成功解锁记忆的密钥文件（ISSUE-P3-04）。
     *
     * 裁决链：[KeyFileRememberPolicy.canRestore]（偏好开启 + 记录完整 + 仍持有持久化读授权）
     * → 真实读取成功才落到 UiState。任一环节不满足即**静默降级为「未记住」**并清除记录：
     * 恢复由系统在进入解锁页时自动发起，用户未做任何操作，故不弹错误、不阻断解锁，
     * 仅记录**非敏感**日志（偏好/授权布尔值，不含 Uri 与显示名）。
     */
    suspend fun restoreRememberedKeyFile() {
        val access = keyFileAccess ?: return
        if (keyFileUserTouched) return
        val rememberEnabled = access.isRememberEnabled()
        val remembered = access.loadRemembered() ?: return
        val permissionValid = access.hasPersistedReadPermission(remembered.uri)
        if (!KeyFileRememberPolicy.canRestore(rememberEnabled, remembered, permissionValid)) {
            debugLog.info(
                TAG,
                "密钥文件记忆不可用（偏好=$rememberEnabled，持久授权有效=$permissionValid），静默降级为未记住"
            )
            access.forget()
            return
        }
        when (val outcome = access.read(remembered.uri)) {
            is KeyFileReadResult.Success -> {
                if (keyFileUserTouched) {
                    // 读取期间用户已显式选择其它密钥文件：丢弃恢复结果，尊重用户选择
                    outcome.bytes.fill(0)
                    return
                }
                val displayName = outcome.displayName.ifBlank { remembered.displayName }
                try {
                    adoptKeyFile(outcome.bytes, displayName)
                } finally {
                    outcome.bytes.fill(0)
                }
                keyFileSourceUri = remembered.uri
                uiState.update {
                    it.copy(
                        infoMessage = UiMessage(
                            R.string.keyfile_restored_from_memory,
                            listOf(displayName)
                        )
                    )
                }
            }
            else -> {
                debugLog.warn(TAG, "记忆的密钥文件已不可读（授权有效但读取失败），清除记录并降级为未记住")
                access.forget()
            }
        }
    }

    /**
     * 解锁成功后按偏好记忆密钥文件（ISSUE-P3-04）。
     *
     * - 偏好开启 + 本次使用且可定位来源的密钥文件 → 记住 Uri 与显示名（非密钥元数据）；
     * - 偏好开启 + 本次未使用密钥文件 → 清除旧记录：标准解锁在「未携带密钥文件」下成功，
     *   只可能是密码库本身不含密钥文件因子（携带不匹配的密钥文件必然凭据失败），
     *   此时旧记录归属其它库或已失效，留存会误导下次解锁；
     * - 偏好关闭 → 一并清除，不残留任何密钥文件元数据。
     *
     * 全程**不落任何密钥字节**：字节仍只驻留单次解锁尝试内，成功后立即清零。
     */
    suspend fun rememberKeyFileOnSuccess(usedKeyFile: Boolean, displayName: String) {
        val access = keyFileAccess ?: return
        if (!access.isRememberEnabled()) {
            access.forget()
            return
        }
        val sourceUri = keyFileSourceUri
        if (usedKeyFile && !sourceUri.isNullOrBlank()) {
            access.remember(sourceUri, displayName)
        } else if (!usedKeyFile) {
            access.forget()
        }
    }

    /**
     * 擦除驻留的密钥文件字节与来源引用。
     *
     * 解锁成功后（会话已克隆缓存供保存使用）与 ViewModel 销毁收尾两处调用；
     * 原实现在两处的语句完全一致，此处收敛为单一清零点，清零时机与顺序不变。
     * 注意：不重置 `keyFileUserTouched`（与原实现一致——用户显式选择在本会话内持续有效）。
     */
    fun wipe() {
        keyFileData?.fill(0)
        keyFileData = null
        keyFileSourceUri = null
    }

    companion object {
        private const val TAG = "Unlock"
    }
}
