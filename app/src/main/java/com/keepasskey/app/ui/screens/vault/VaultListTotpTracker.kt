package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.util.tickerFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 密码库列表页的 **TOTP 实时倒计时与跨周期验证码重算**（ISSUE-P3-29：自 `VaultListViewModel.kt` 拆出）。
 *
 * 原实现逐字迁移：秒级 tick 由官方 [tickerFlow] 冷流驱动（P1 整改，与详情页 / 验证器页共用单一
 * tick 源），剩余秒数回跳判定新周期并触发带 TOTP 条目重算。种子解析与验证码计算全在数据层完成，
 * 本类只持有验证码结果，**绝不接触种子**。
 */
internal class VaultListTotpTracker(
    private val vaultRepository: VaultRepository,
    private val scope: CoroutineScope,
    /** 当前列表的根库条目（用于跨周期重算；由 ViewModel 提供其 uiState 快照） */
    private val currentEntries: () -> List<UiVaultEntry>
) {

    private val remainingSecondsFlow = MutableStateFlow(calculateCurrentRemainingSeconds())
    private val liveCodesFlow = MutableStateFlow<Map<String, String>>(emptyMap())

    /** 记录上一秒的剩余秒数，用于检测 TOTP 周期翻转 */
    private var previousRemaining = -1

    /** 当前 TOTP 周期剩余秒数 */
    val remainingSeconds: StateFlow<Int> = remainingSecondsFlow

    /** entryId → 本周期实时验证码（仅在周期翻转时按需重算） */
    val liveCodes: StateFlow<Map<String, String>> = liveCodesFlow

    /** 启动秒级 tick 循环（由 ViewModel init 调用一次） */
    fun start() {
        scope.launch(Dispatchers.Default) {
            tickerFlow(TOTP_TICK_INTERVAL_MS)
                .map { calculateCurrentRemainingSeconds() }
                .distinctUntilChanged()
                .collect { remaining ->
                    remainingSecondsFlow.value = remaining
                    // 剩余秒数回跳到更大值 → 新周期开始，对本组带 TOTP 的条目按需重算验证码
                    if (previousRemaining in 1..remaining || previousRemaining == -1) {
                        refreshLiveCodes()
                    }
                    previousRemaining = remaining
                }
        }
    }

    /** 对当前列表中带 TOTP 的条目按需重算实时验证码（种子在数据层内解析，绝不外泄） */
    private fun refreshLiveCodes() {
        scope.launch(Dispatchers.Default) {
            val updated = mutableMapOf<String, String>()
            for (entry in currentEntries()) {
                if (entry.totpCode == null) continue
                val snapshot = vaultRepository.calculateEntryTotp(entry.id) ?: continue
                updated[entry.id] = snapshot.code
            }
            liveCodesFlow.value = updated
        }
    }

    private fun calculateCurrentRemainingSeconds(): Int {
        val nowSec = (System.currentTimeMillis() / MILLIS_PER_SECOND).toInt()
        val remainder = nowSec % TOTP_PERIOD_SECONDS
        return TOTP_PERIOD_SECONDS - remainder
    }

    private companion object {
        const val TOTP_PERIOD_SECONDS = 30
        const val MILLIS_PER_SECOND = 1000L
        const val TOTP_TICK_INTERVAL_MS = 1000L
    }
}
