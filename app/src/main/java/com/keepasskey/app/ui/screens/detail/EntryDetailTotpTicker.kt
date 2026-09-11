package com.keepasskey.app.ui.screens.detail

import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.util.tickerFlow

/**
 * 详情页 TOTP 每秒倒计时驱动（断点6 整改）。
 *
 * ISSUE-P3-31 批次 C：由 `EntryDetailViewModel`（原 708 行）按**纯结构性拆分**搬出。
 * `previous` 状态机（含 `previous == -1` 首帧分支与「剩余秒数回跳即换码」判据）
 * 与 1 秒节拍**逐字保留**；无条目或条目无 TOTP 时按原逻辑跳过该拍。
 */
internal class EntryDetailTotpTicker(
    private val vaultRepository: VaultRepository
) {

    /**
     * 启动倒计时循环（永不正常返回，由调用方协程作用域取消）。
     *
     * @param currentEntry 当前条目快照读取器（返回 null 或无 TOTP 即跳过该拍）
     * @param onRemaining 剩余秒数下发
     * @param onLiveCode 周期翻转时的新验证码下发
     */
    suspend fun run(
        currentEntry: () -> UiVaultEntry?,
        onRemaining: (Int) -> Unit,
        onLiveCode: (String?) -> Unit
    ) {
        var previous = -1
        tickerFlow(TOTP_TICK_MS).collect {
            // ISSUE-P3-49：HOTP 无时间倒计时（码由持久化计数器决定），不由本节拍驱动，
            // 否则会在用户取码后一秒内把显示覆盖成「下一码」而与刚交付的码不一致。
            val snapshot = currentEntry()?.takeIf { it.totpCode != null && !it.isHotp } ?: return@collect
            val fresh = vaultRepository.calculateEntryTotp(snapshot.id)
            val period = fresh?.periodSeconds ?: snapshot.totpPeriod
            val remaining = if (fresh != null) {
                val nowSec = (System.currentTimeMillis() / 1000L).toInt()
                val r = period - (nowSec % period)
                if (r == 0) period else r
            } else {
                (snapshot.totpRemainingSeconds - 1).coerceAtLeast(0)
            }
            onRemaining(remaining)
            if (previous in 1..remaining) {
                // 剩余秒数回跳到满值 → 新周期开始，刷新验证码
                onLiveCode(fresh?.code)
            } else if (previous == -1 && fresh != null) {
                onLiveCode(fresh.code)
            }
            previous = remaining
        }
    }

    private companion object {
        private const val TOTP_TICK_MS = 1000L
    }
}
