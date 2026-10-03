package com.keepasskey.app.ui.screens.unlock

import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.ui.model.UiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 密钥文件（复合密钥第二因子）会话协调器（ISSUE-P3-25 结构拆分，纯搬运零行为变更）。
 *
 * 承载原 `UnlockViewModel` 内 `keyFileData` 相关的全部逻辑：SAF 读取结果采纳、
 * 驻留字节的借用/克隆/清零、来源 Uri 登记、记忆写入与恢复编排。
 * 字段名、清零时机、协程作用域（`viewModelScope`）与 UiState 更新均与原实现逐字一致。
 *
 * `ISSUE-P3-466`（§420）结构拆分：**恢复裁决链**（分支判定 + 探针 + 副本 / 按库记忆两级
 * 恢复）移入同包协作者 [KeyFileRestoreChain]，本类经 [KeyFileRestoreHost] 只读 / 写会话态；
 * 「何时发起恢复」（活动库切换 / 回前台）与在途任务取消留在本类（它才持有 [scope]）。
 *
 * 敏感数据铁律：密钥文件字节仅以 `ByteArray` 驻留本类内部（绝不进入 UiState/StateFlow/String）；
 * 覆盖采纳 / 取消选择 / 读取失败 / 解锁成功 / ViewModel 销毁五条路径均显式 `fill(0)` 清零。
 */
internal class KeyFileSessionCoordinator(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<UnlockUiState>,
    private val keyFileAccess: KeyFileAccess?,
    private val debugLog: DebugLogBuffer,
    /**
     * §411（ISSUE-P3-448）：应用私有目录收编副本通道（可空 = 单测未装配）。
     * 冷启动恢复**优先消费副本**（不依赖 SAF 持久授权），解锁成功且使用密钥文件时收编字节。
     */
    private val vaultCopyStore: com.keepasskey.app.security.KeyFileVaultCopyStore? = null,
    /** 活动库 id 副本键（可空 = 单测未装配；null 时副本通道整体旁路，行为与既有一致） */
    private val activeDbId: () -> String? = { null }
) : KeyFileRestoreHost {

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
     * 经 [KeyFileRestoreHost.keyFileUserTouched] 只读暴露给恢复链（所有权仍在本类）。
     */
    private var userTouched = false

    /** 恢复裁决链（ISSUE-P3-466 拆出）：本类只提供会话态读写与发起时机 */
    private val restoreChain = KeyFileRestoreChain(
        host = this,
        uiState = uiState,
        keyFileAccess = keyFileAccess,
        vaultCopyStore = vaultCopyStore,
        debugLog = debugLog
    )

    /** 在途恢复任务（活动库切换 / 回前台重新发起时取消旧任务，防并发恢复互踩） */
    private var restoreJob: Job? = null

    // ===== KeyFileRestoreHost：恢复链对会话态的读写契约 =====

    override val keyFileUserTouched: Boolean
        get() = userTouched

    override fun currentDatabaseId(): String? = activeDbId()

    override fun adoptRestoredKeyFile(bytes: ByteArray, displayName: String) =
        adoptKeyFile(bytes, displayName)

    override fun setKeyFileSourceUri(uri: String?) {
        keyFileSourceUri = uri
    }

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
            // ISSUE-P3-437 AC①：SAF 现读期间呈现「正在读取密钥文件…」阶段文案（终态统一撤下）
            uiState.update { it.copy(loadStage = UnlockStage.READING_KEY_FILE) }
            try {
                when (val outcome = access.read(uri)) {
                    is KeyFileReadResult.Success -> {
                        try {
                            adoptKeyFile(outcome.bytes, outcome.displayName)
                        } finally {
                            // 移交后立即擦除读取结果（VM 内部持独立副本）
                            outcome.bytes.fill(0)
                        }
                        userTouched = true
                        trackKeyFileSource(uri)
                    }
                    // 「读不到」分型：空文件 / 流异常一律显式反馈，绝不静默忽略
                    KeyFileReadResult.Empty,
                    KeyFileReadResult.Unreadable -> onKeyFileReadFailed()
                }
            } finally {
                uiState.update { it.copy(loadStage = null) }
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
        userTouched = true
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
        userTouched = true
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
        userTouched = true
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

    /** 恢复本库记忆的密钥文件（裁决链与探针见 [KeyFileRestoreChain.restoreRememberedKeyFile]） */
    suspend fun restoreRememberedKeyFile() = restoreChain.restoreRememberedKeyFile()

    /**
     * ISSUE-P3-466 ③：回前台是否应发起恢复（判据见 [KeyFileRestoreChain.shouldRestoreOnResume]）。
     * 与 [restoreOnResumeIfIdle] 同一判据的**可测出口**——后者是即发即弃，单测无法等待其结果。
     */
    fun shouldRestoreOnResume(): Boolean = restoreChain.shouldRestoreOnResume()

    /**
     * 活动库切换后的恢复发起（ISSUE-P3-464 ②）：复位表单态后按**新库**记录恢复，
     * 取消在途恢复任务防并发互踩（提示名与加载名恒同源）。
     */
    fun resetForActiveVaultAndRestore() {
        onActiveVaultChanged()
        restoreJob?.cancel()
        restoreJob = scope.launch { restoreChain.restoreRememberedKeyFile() }
    }

    /**
     * ISSUE-P3-466 ③：解锁页回到前台时的恢复发起——判据见
     * [KeyFileRestoreChain.shouldRestoreOnResume]（表单为空 + 非用户显式清除 + 有活动库 + 无在途读取）。
     *
     * 立此入口的缘由：解锁成功即 `wipe()` 清空表单，而锁库回到解锁页时**同库同 VM 不满足
     * 「活动库切换」条件**，此前无处触发恢复 ⇒ 表现为「选择没保存」（用户走查回执）。
     */
    fun restoreOnResumeIfIdle() {
        if (!restoreChain.shouldRestoreOnResume()) return
        debugLog.info(TAG, "解锁页回前台：按当前库记录再恢复密钥文件")
        restoreJob?.cancel()
        restoreJob = scope.launch { restoreChain.restoreRememberedKeyFile() }
    }

    /**
     * 解锁成功后按偏好记忆密钥文件（ISSUE-P3-04；§411 扩展收编副本；ISSUE-P2-460 按库归属）。
     *
     * - 偏好开启 + 本次使用密钥文件 → 记住 Uri 与显示名到**本库名下**（AC①：dbId 摘要键，
     *   不再写全局槽；活动库 id 缺失时放弃登记并留痕，绝不退回全局槽），
     *   **并将字节收编进应用私有目录副本**（ISSUE-P3-448 AC②：无论来源 Uri 是否取得
     *   持久化授权，副本都让下次冷启动恢复不再依赖授权——这正是收编的价值）；
     * - 偏好开启 + 本次未使用密钥文件 → 清除本库旧记录与旧副本：标准解锁在「未携带密钥文件」
     *   下成功，只可能是密码库本身不含密钥文件因子（携带不匹配的密钥文件必然凭据失败），
     *   此时旧记录已失效，留存会误导下次解锁；
     * - 偏好关闭 → 清记忆（本库记录 + 旧版全局槽）；**副本保留**（§411 走查裁决：副本归
     *   「导入密钥文件」功能管，用户显式导入/改绑产生的副本不随偏好清除，仅停用「解锁自动收编」）。
     *
     * 全程**不落任何密钥字节**到 UiState：字节仍只驻留单次解锁尝试内（本方法在
     * `MasterPasswordUnlockSession` 的擦除点**之前**调用，会话驻留字节此刻仍存活）。
     */
    suspend fun rememberKeyFileOnSuccess(usedKeyFile: Boolean, displayName: String) {
        val access = keyFileAccess ?: return
        val dbId = activeDbId()
        val rememberEnabled = access.isRememberEnabled()
        if (!rememberEnabled) {
            // 偏好关闭：只清记忆（本库记录 + 旧版全局槽），副本归导入功能（不被解锁动作清除）
            access.forget(dbId)
            return
        }
        if (usedKeyFile) {
            val sourceUri = keyFileSourceUri
            if (!sourceUri.isNullOrBlank()) {
                if (dbId != null) {
                    access.remember(dbId, sourceUri, displayName)
                } else {
                    // ISSUE-P2-460 AC①：无库维度就无归属——宁可不记，绝不退回全局槽
                    debugLog.warn(TAG, "活动库 id 缺失，密钥文件记忆放弃登记（不落全局槽）")
                }
            }
            // §411（ISSUE-P3-448 AC②）：收编字节进私有目录（副本密钥经 Keystore 封 DEK + 软件层加密）
            val bytes = keyFileData
            if (dbId != null && bytes != null && vaultCopyStore != null) {
                val saved = vaultCopyStore.save(dbId, bytes, displayName)
                debugLog.info(TAG, "密钥文件副本收编: $saved")
            }
        } else {
            access.forget(dbId)
            // 库无密钥文件因子却存在副本 ⇒ 副本必属陈旧因子（或其它库），清除
            if (dbId != null) vaultCopyStore?.clear(dbId)
        }
    }

    /**
     * 活动库切换：密钥文件**表单态整体复位**（ISSUE-P3-464 ②，KeePassDX 对齐——每库一份记忆，
     * 切库后不得残留上一库的驻留字节与「已自动载入」提示）。
     *
     * 清零驻留字节、复位来源与「用户显式选择」标记（否则会挡住新库的记忆恢复）、
     * 清空 UiState 的密钥文件行与一次性提示；随后由 [resetForActiveVaultAndRestore] 按
     * **新库**记录重新恢复，恢复提示名与实际加载名恒同源。
     */
    fun onActiveVaultChanged() {
        keyFileData?.fill(0)
        keyFileData = null
        keyFileSourceUri = null
        userTouched = false
        uiState.update {
            it.copy(hasKeyFile = false, keyFileName = "", infoMessage = null, errorMessage = null)
        }
        debugLog.info(TAG, "活动库切换：密钥文件表单态已复位（驻留字节清零）")
    }

    /**
     * ISSUE-P3-466 ③：**解锁成功即消费本次选择**——复位「用户显式选择」标记。
     *
     * 该标记的语义是「本会话内用户已表达过意图」，它同时会挡住恢复链。解锁成功意味着意图
     * 已被消费（因子已用于解锁、字节已由 [wipe] 擦除），故此处复位，使随后锁库回到解锁页时
     * 能按记录重新恢复（[KeyFileRestoreChain.shouldRestoreOnResume] 判据之一）。
     * 显式**清除**（[clearKeyFile] / [onKeyFileReadFailed]）不在此列——那仍需挡住恢复。
     */
    fun consumeSelectionAfterUnlock() {
        userTouched = false
    }

    /**
     * 擦除驻留的密钥文件字节与来源引用。
     *
     * 解锁成功后（会话已克隆缓存供保存使用）与 ViewModel 销毁收尾两处调用；
     * 原实现在两处的语句完全一致，此处收敛为单一清零点，清零时机与顺序不变。
     * 注意：不重置「用户显式选择」标记（与原实现一致——用户显式选择在本会话内持续有效）；
     * 解锁成功的「消费选择」由 [consumeSelectionAfterUnlock] 单独承担，
     * 以便销毁路径仍保持原语义。
     */
    fun wipe() {
        keyFileData?.fill(0)
        keyFileData = null
        keyFileSourceUri = null
    }

    private companion object {
        const val TAG = "Unlock"
    }
}
