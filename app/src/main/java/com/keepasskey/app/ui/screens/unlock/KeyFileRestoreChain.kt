package com.keepasskey.app.ui.screens.unlock

import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.security.KeyFileVaultCopyStore
import com.keepasskey.app.ui.model.UiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 恢复链对「密钥文件会话态」的读写契约（ISSUE-P3-466 结构拆分）。
 *
 * 把恢复裁决链从 [KeyFileSessionCoordinator] 抽出为同包协作者时，只经本接口触碰会话态，
 * 避免两个类共享可变字段（可读性 + 单一职责；字段所有权仍只属协调器）。
 */
internal interface KeyFileRestoreHost {

    /** 用户是否已在本会话**显式**选择 / 清除过密钥文件（恢复的早退与丢弃判据） */
    val keyFileUserTouched: Boolean

    /** 活动库 id（按库记忆的命名空间；null = 无库维度，不恢复） */
    fun currentDatabaseId(): String?

    /**
     * 采纳恢复出的密钥文件字节（写驻留副本 + 「已选择 + 显示名」语义）。
     *
     * [sourcePath]（§433，ISSUE-P3-448 走查续）＝本次恢复的**来源路径**（副本绝对路径 / SAF Uri），
     * 仅用于解锁页呈现「从哪载入」，非密钥材料。
     */
    fun adoptRestoredKeyFile(bytes: ByteArray, displayName: String, sourcePath: String?)

    /** 登记恢复来源 Uri（非密钥元数据） */
    fun setKeyFileSourceUri(uri: String?)
}

/**
 * 密钥文件**恢复裁决链**（ISSUE-P3-466 自 [KeyFileSessionCoordinator] 拆出；§280 单文件阈值）。
 *
 * 裁决顺序：私有目录收编副本（§411）→ 本库按库 SAF 记忆（ISSUE-P2-460，dbId 摘要键，
 * 结构上不可能读到其它库的记录）→ 本库未登记时旧版全局槽**只作一句话提示、绝不载入**。
 * [KeyFileRememberPolicy.canRestore]（偏好开启 + 记录完整 + 仍持有持久化读授权）不满足即
 * **静默降级为「未记住」**并清除记录：恢复由系统自动发起，用户未做任何操作，故不弹错误、
 * 不阻断解锁，仅记录**非敏感**日志（偏好 / 授权布尔值与 dbId 摘要，不含 Uri 与显示名）。
 */
internal class KeyFileRestoreChain(
    private val host: KeyFileRestoreHost,
    private val uiState: MutableStateFlow<UnlockUiState>,
    private val keyFileAccess: KeyFileAccess?,
    private val vaultCopyStore: KeyFileVaultCopyStore?,
    private val debugLog: DebugLogBuffer
) {

    /**
     * 恢复本库记忆的密钥文件（ISSUE-P3-04；ISSUE-P2-460 起记忆**按库归属**）。
     *
     * 每次调用都经 [probeRestore] 落一条「走了哪个分支 + dbId 摘要短哈希」的读数。
     */
    suspend fun restoreRememberedKeyFile() {
        val access = keyFileAccess
        if (access == null) {
            probeRestore("access_unavailable", null)
            return
        }
        if (host.keyFileUserTouched) {
            probeRestore("user_touched", host.currentDatabaseId())
            return
        }
        val rememberEnabled = access.isRememberEnabled()
        // ISSUE-P2-460 AC①：记忆按库归属——记录键含 dbId 摘要，本库记录只可能是本库的
        val dbId = host.currentDatabaseId()
        val remembered = dbId?.let { access.loadRemembered(it) }
        // 副本优先载入（不依赖 SAF 持久授权；缺失 / 损坏时回落按库 SAF Uri 记忆通道）
        if (dbId != null && vaultCopyStore != null &&
            restoreFromCopy(access, dbId, remembered, rememberEnabled)
        ) {
            return
        }
        if (!rememberEnabled) {
            // 偏好关闭且无副本：清记忆（按库记录 + 旧版全局槽），静默降级为未记住
            probeRestore("disabled_without_copy", dbId)
            access.forget(dbId)
            return
        }
        if (remembered == null) {
            // ISSUE-P2-460 AC①：本库未登记记忆——旧版全局槽只作一句话提示，绝不载入
            // （旧版记录无法归属到具体库，自动套用即跨库串因子）
            probeRestore("no_record", dbId)
            showLegacyHintIfAny(access)
            return
        }
        restoreFromMemory(access, dbId, remembered, rememberEnabled)
    }

    /**
     * ISSUE-P3-466 ③：解锁页**回到前台**时是否需要按当前库记录再恢复密钥文件。
     *
     * 判据（全部满足才恢复）：表单为空（`!hasKeyFile`）且**非用户显式清除**
     * （`!keyFileUserTouched`）且存在活动库（无库维度就无归属，宁可不恢复）且当前无在途读取。
     *
     * 立此判据的缘由：解锁成功后表单被 `wipe()` 清空，而锁库回到解锁页时**同库同 VM 不满足
     * 「活动库切换」条件**，此前无处触发恢复 ⇒ 表现为「选择没保存」（用户走查回执）。
     */
    fun shouldRestoreOnResume(): Boolean =
        !host.keyFileUserTouched && !uiState.value.hasKeyFile &&
            uiState.value.loadStage == null && host.currentDatabaseId() != null

    /**
     * ISSUE-P3-466 ①：**恢复决策探针**——每次恢复只记「走了哪个分支 + dbId 摘要短哈希」。
     *
     * 立此探针的缘由：此前恢复链无任何分支读数，用户报「恢复出好久以前的密钥文件」时
     * 无法从日志终判走的是「副本」还是「按库 SAF 记忆」，也就无法定位来源。
     * 探针**不落**库 id 原文、Uri、显示名或任何密钥材料（沿用日志脱敏纪律），
     * dbId 只以 SHA-256 前 4 字节十六进制呈现。
     */
    private fun probeRestore(branch: String, dbId: String?) {
        debugLog.info(TAG, "密钥文件恢复探针: branch=$branch, dbIdHash=${dbId?.let(::shortDigest) ?: "-"}")
    }

    /** dbId 摘要短哈希（非可逆、非密钥元数据；仅用于把同一库的多条探针串起来） */
    private fun shortDigest(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(4)
            .joinToString("") { "%02x".format(it) }

    /**
     * 恢复链第一级：私有目录收编副本（§411；ISSUE-P2-460 起按键即本库）。
     * @return true = 已按副本完成恢复（含用户抢先选择的丢弃分支），调用方不必再走后续通道
     */
    private suspend fun restoreFromCopy(
        access: KeyFileAccess,
        dbId: String,
        remembered: RememberedKeyFile?,
        rememberEnabled: Boolean
    ): Boolean {
        val copy = vaultCopyStore?.load(dbId)
        if (copy == null) {
            probeRestore("copy_missing", dbId)
            return false
        }
        if (host.keyFileUserTouched) {
            // 读取期间用户已显式选择其它密钥文件：丢弃恢复结果，尊重用户选择
            copy.bytes.fill(0)
            probeRestore("copy_discarded_user_selected", dbId)
            return true
        }
        try {
            // §433（P3-448 走查续）：来源＝私有目录副本**真实绝对路径**（供解锁页呈现「从哪载入」）
            host.adoptRestoredKeyFile(copy.bytes, copy.displayName, vaultCopyStore?.copyPathFor(dbId))
        } finally {
            copy.bytes.fill(0)
        }
        probeRestore("copy", dbId)
        // 偏好关闭：即便走副本载入，Uri 记忆仍须清除（副本归导入功能，不受影响）
        if (!rememberEnabled) {
            access.forget(dbId)
        }
        host.setKeyFileSourceUri(remembered?.uri)
        uiState.update {
            it.copy(
                infoMessage = UiMessage(
                    R.string.keyfile_restored_from_memory,
                    listOf(
                        copy.displayName.ifBlank { remembered?.displayName.orEmpty() }
                    )
                )
            )
        }
        return true
    }

    /** 旧版全局槽的一句话提示（ISSUE-P2-460 AC①：只提示、绝不载入） */
    private suspend fun showLegacyHintIfAny(access: KeyFileAccess) {
        val legacy = access.loadLegacyGlobalHint() ?: return
        uiState.update {
            it.copy(
                infoMessage = UiMessage(
                    R.string.keyfile_legacy_memory_hint,
                    listOf(legacy.displayName)
                )
            )
        }
    }

    /**
     * 恢复链第二级：本库按库 SAF 记忆现读。
     * [KeyFileRememberPolicy.canRestore]（记录完整 + 仍持有持久化读授权）不满足即静默降级
     * 为「未记住」并清除记录：恢复由系统自动发起，不弹错误、不阻断解锁，仅记非敏感日志。
     */
    private suspend fun restoreFromMemory(
        access: KeyFileAccess,
        dbId: String?,
        remembered: RememberedKeyFile,
        rememberEnabled: Boolean
    ) {
        val permissionValid = access.hasPersistedReadPermission(remembered.uri)
        if (!KeyFileRememberPolicy.canRestore(rememberEnabled, remembered, permissionValid)) {
            debugLog.info(
                TAG,
                "密钥文件记忆不可用（偏好=$rememberEnabled，持久授权有效=$permissionValid），静默降级为未记住"
            )
            probeRestore("memory_unusable", dbId)
            access.forget(dbId)
            return
        }
        // ISSUE-P3-437 AC①：记忆现读期间呈现「正在读取密钥文件…」阶段文案
        // （冷启动恢复不在 isLoading 窗口内、渲染层自然不挂出；生物识别解封后的现读在窗口内可见）
        uiState.update { it.copy(loadStage = UnlockStage.READING_KEY_FILE) }
        try {
            when (val outcome = access.read(remembered.uri)) {
                is KeyFileReadResult.Success -> {
                    if (host.keyFileUserTouched) {
                        // 读取期间用户已显式选择其它密钥文件：丢弃恢复结果，尊重用户选择
                        outcome.bytes.fill(0)
                        return
                    }
                    val displayName = outcome.displayName.ifBlank { remembered.displayName }
                    try {
                        // §433（P3-448 走查续）：来源＝本库按库记忆的 SAF Uri（授权来源，非密钥材料）
                        host.adoptRestoredKeyFile(outcome.bytes, displayName, remembered.uri)
                    } finally {
                        outcome.bytes.fill(0)
                    }
                    host.setKeyFileSourceUri(remembered.uri)
                    probeRestore("memory", dbId)
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
                    probeRestore("memory_unreadable", dbId)
                    access.forget(dbId)
                }
            }
        } finally {
            uiState.update { it.copy(loadStage = null) }
        }
    }

    private companion object {
        const val TAG = "Unlock"
    }
}
