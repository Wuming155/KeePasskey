package com.keepasskey.app.ui.screens.detail

import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 详情页「按需揭示 / 遮掩」的行为控制器。
 *
 * ISSUE-P3-31 批次 C：由 `EntryDetailViewModel`（原 708 行）按**纯结构性拆分**搬出。
 * 与 [EntryDetailSecrets]（状态持有）配对：本类只负责**何时解密、何时撤回**，
 * 状态字段仍由 [secrets] 唯一持有，因此清零点没有被复制到第二处。
 *
 * 安全语义逐条保留：
 * - 遮掩态存的是**用户显式意图**，偏好快照刷新不覆盖已有意图（ISSUE-P3-17）；
 * - 偏好为「默认遮掩」时**不做任何解密**（绝不无授权预解密）；
 * - 收起即撤回明文与由明文派生的熵读数。
 */
internal class EntryDetailRevealController(
    private val vaultRepository: VaultRepository,
    private val scope: CoroutineScope,
    private val secrets: EntryDetailSecrets,
    private val settingsSnapshot: () -> ExtendedSettings,
    private val currentEntryId: () -> String?,
    private val currentEntry: () -> UiVaultEntry?
) {

    private val passwordStrengthBitsFlow = MutableStateFlow<Int?>(null)

    /**
     * TASK-32 / ISSUE-P3-46：按需解密估算的真实密码熵（bit）。
     * null = 无密码 / 未揭示 / 评估不可用；明文仅在计算期间以 CharArray 副本瞬时存在。
     */
    val passwordStrengthBits: StateFlow<Int?> = passwordStrengthBitsFlow

    /**
     * 擦除全部按需解密明文与由其派生的熵读数。
     *
     * ISSUE-P3-46：熵由明文派生，明文擦除时必须一并失效，
     * 否则切换条目后强度条会留在上一条目的读数（一种跨条目信息残留）。
     */
    fun clearAll() {
        secrets.clearAll()
        passwordStrengthBitsFlow.value = null
    }

    /**
     * ISSUE-P3-17：`maskPasswordsDefault = false` 是用户以偏好形式给出的**明文查看指令**，
     * 此时进入详情页即按需解密当前条目，使「默认不遮掩」在 UI 上真实可见（而非空字段）。
     *
     * 安全边界不变：仍是单条、仍在屏幕生命周期内驻留（离开即清零），
     * 且偏好为「默认遮掩」（生产默认值 true）时**不做任何解密**。
     * TOTP 无需此路径：验证码由投影/countdown 流按周期下发，不涉及额外解密。
     */
    fun revealPasswordIfVisibleByDefault() {
        if (secrets.revealedPassword.value != null) return
        val masked = maskStateOf(
            defaultMasked = settingsSnapshot().maskPasswordsDefault,
            override = secrets.passwordMaskOverride.value
        )
        if (!masked) decryptPasswordForDisplay()
    }

    /**
     * 切换密码可见性。展开时按需解密当前条目密码，收起时立即置空驻留明文。
     *
     * ISSUE-P3-17：切换写入的是**用户显式遮掩意图**（override），
     * 因此后续任何偏好快照刷新都不会把手动展开的密码重新盖上。
     */
    fun togglePasswordVisibility() {
        val currentlyMasked = maskStateOf(
            defaultMasked = settingsSnapshot().maskPasswordsDefault,
            override = secrets.passwordMaskOverride.value
        )
        secrets.passwordMaskOverride.value = !currentlyMasked
        if (currentlyMasked) {
            decryptPasswordForDisplay()
        } else {
            secrets.revealedPassword.value = null
            // ISSUE-P3-46：收起密码即撤回强度读数，避免遮掩态下仍暴露口令强度这一侧信道
            passwordStrengthBitsFlow.value = null
        }
    }

    /**
     * ISSUE-P3-17：切换 TOTP 验证码可见性（默认态来自 `maskTotpDefault`）。
     * 与密码同构：只翻转用户显式意图，验证码本身仍由倒计时流驱动，不因遮掩而停算。
     */
    fun toggleTotpVisibility() {
        val currentlyMasked = maskStateOf(
            defaultMasked = settingsSnapshot().maskTotpDefault,
            override = secrets.totpMaskOverride.value
        )
        secrets.totpMaskOverride.value = !currentlyMasked
    }

    /**
     * 切换受保护自定义字段可见性（F2 整改）。
     * 展开时经仓库按需单条解密该字段明文，收起时立即从驻留状态中移除。
     */
    fun toggleCustomFieldVisibility(fieldId: String) {
        val becomingVisible = secrets.protectedVisibility.value[fieldId] != true
        secrets.protectedVisibility.update { current -> current + (fieldId to becomingVisible) }
        if (!becomingVisible) {
            secrets.revealedProtectedFields.update { it - fieldId }
            return
        }
        val entry = currentEntry() ?: return
        val field = entry.customFields.firstOrNull { it.id == fieldId } ?: return
        scope.launch {
            // TASK-10：仓库读取改走 CharArray 独占副本；展示用 String 为 UI 显示边界
            // （与 getEntryPassword 同一边界语义），副本即时清零
            val chars = vaultRepository.getEntryProtectedFieldChars(entry.id, field.key)
            val value = if (chars != null) {
                val revealed = String(chars)
                chars.fill('0')
                revealed
            } else ""
            secrets.revealedProtectedFields.update { it + (fieldId to value) }
        }
    }

    /**
     * 展开分支：按需解密（ISSUE-P2-15 CharArray 借用通道，String 物化收敛在展示边界）。
     *
     * ISSUE-P3-46：真实熵在**明文副本清零之前**由 [PasswordEntropyEstimator] 就地估算
     * （评估内部不物化 String、自行清零其 UTF-8 副本）；此前 `passwordStrengthBits`
     * 无任何写入方，导致详情页强度条恒不渲染。
     */
    private fun decryptPasswordForDisplay() {
        val entryId = currentEntryId() ?: return
        scope.launch {
            val chars = vaultRepository.getEntryPasswordChars(entryId)
            passwordStrengthBitsFlow.value = PasswordEntropyEstimator.estimateBits(chars)
            secrets.revealedPassword.value = chars.toDisplayString()
        }
    }

    private fun maskStateOf(defaultMasked: Boolean, override: Boolean?): Boolean =
        FieldMaskPolicy.initialMaskState(defaultMasked, override)
}
