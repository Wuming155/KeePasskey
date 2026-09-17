package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.util.tickerFlow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * 密码库列表页的 **TOTP 实时倒计时与跨周期验证码重算**（ISSUE-P3-29：自 `VaultListViewModel.kt` 拆出）。
 *
 * 原实现逐字迁移：秒级 tick 由官方 [tickerFlow] 冷流驱动（P1 整改，与详情页 / 验证器页共用单一
 * tick 源），剩余秒数回跳判定新周期并触发带 TOTP 条目重算。种子解析与验证码计算全在数据层完成，
 * 本类只持有验证码结果，**绝不接触种子**。
 *
 * ## ISSUE-P2-89：两条通道均改为**订阅驱动**，且不再并入整页状态
 *
 * 原实现的两个问题：
 * 1. `start()` 在 ViewModel `init` 里以 `scope.launch` **常驻**启动，列表页不在前台
 *    （ViewModel 仍存活于返回栈）时照样每秒 tick；
 * 2. [remainingSeconds] 被并入 `VaultListViewModel` 的最外层 `combine`，于是**每秒**
 *    都重跑一次列表页整页投影（过滤 / 排序 / 全量 `copy`），且运行在 Main 线程。
 *
 * 现改为：节拍由 [remainingSeconds] 自身的订阅（UI 侧 `collectAsStateWithLifecycle`）
 * 驱动——`WhileSubscribed` 保证「无人订阅即停表」，**不需要也不存在显式 `start()`**；
 * 秒级与周期间的 TOTP 变化只经 [remainingSeconds] / [liveCodes] 两条窄通道下发，
 * 由列表行内的徽标按需读取，**整页状态不再随秒数或周期重建**。
 *
 * ## ISSUE-P2-90：跨周期重算走批量通道
 *
 * 跨周期重算一次覆盖当前列表全部带 TOTP 的条目，经
 * [VaultRepository.calculateEntryTotps] 批量取码（一次会话读取 + 一次条目索引），
 * 收敛原「逐条 `calculateEntryTotp`」的 O(T×N)。周期内的重复读取则由数据层缓存吸收。
 */
internal class VaultListTotpTracker(
    private val vaultRepository: VaultRepository,
    private val scope: CoroutineScope,
    /** 当前列表的根库条目（用于跨周期重算；由 ViewModel 提供其 uiState 快照） */
    private val currentEntries: () -> List<UiVaultEntry>,
    /**
     * 节拍上游（含验证码重算）的调度器——生产为 [Dispatchers.Default]，
     * 单测注入测试调度器以获得确定性的虚拟时钟推进。
     */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * 墙钟读取器——TOTP 周期边界依赖绝对时间。
     * 生产为 [System.currentTimeMillis]；单测注入可控时钟，使「跨周期重算」可被确定性地触发。
     */
    private val nowMillis: () -> Long = System::currentTimeMillis
) {

    private val liveCodesFlow = MutableStateFlow<Map<String, String>>(emptyMap())

    /** 记录上一秒的剩余秒数，用于检测 TOTP 周期翻转（仅在节拍上游内访问，单收集者） */
    private var previousRemaining = -1

    /**
     * 当前 TOTP 周期剩余秒数。
     *
     * **本 StateFlow 即节拍本体**：其上游（`tickerFlow`）由 `WhileSubscribed` 控制——
     * 有人订阅才计时（列表页可见时由行内徽标订阅），无人订阅即停，故不需要显式启动/停止 API。
     */
    val remainingSeconds: StateFlow<Int> = tickerFlow(TOTP_TICK_INTERVAL_MS)
        .map { calculateCurrentRemainingSeconds() }
        .distinctUntilChanged()
        // 每次（重新）订阅都按首帧处理：必然刷新一次验证码，避免泊位期间跨过的周期留下旧码
        .onStart { previousRemaining = -1 }
        .onEach { remaining ->
            // 剩余秒数回跳到更大值 → 新周期开始，对本组带 TOTP 的条目批量重算验证码
            if (previousRemaining == -1 || previousRemaining in 1..remaining) {
                refreshLiveCodes()
            }
            previousRemaining = remaining
        }
        // 验证码重算含会话读取与 HMAC：整条上游（含 onEach 副作用）必须离开 Main
        .flowOn(dispatcher)
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(TOTP_SUBSCRIPTION_GRACE_MS),
            initialValue = calculateCurrentRemainingSeconds()
        )

    /** entryId → 本周期实时验证码（仅在周期翻转时按需重算） */
    val liveCodes: StateFlow<Map<String, String>> = liveCodesFlow

    /** 对当前列表中带 TOTP 的条目批量重算实时验证码（种子在数据层内解析，绝不外泄） */
    private suspend fun refreshLiveCodes() {
        val totpEntryIds = currentEntries().filter { it.totpCode != null }.map { it.id }
        if (totpEntryIds.isEmpty()) {
            liveCodesFlow.value = emptyMap()
            return
        }
        val snapshots = vaultRepository.calculateEntryTotps(totpEntryIds)
        liveCodesFlow.value = snapshots.mapValues { (_, snapshot) -> snapshot.code }
    }

    private fun calculateCurrentRemainingSeconds(): Int {
        val nowSec = (nowMillis() / MILLIS_PER_SECOND).toInt()
        val remainder = nowSec % TOTP_PERIOD_SECONDS
        return TOTP_PERIOD_SECONDS - remainder
    }

    private companion object {
        const val TOTP_PERIOD_SECONDS = 30
        const val MILLIS_PER_SECOND = 1000L
        const val TOTP_TICK_INTERVAL_MS = 1000L

        /** 订阅宽限期（毫秒）：与列表页 `uiState` 的 `WhileSubscribed` 同值，短暂离开不重算 */
        const val TOTP_SUBSCRIPTION_GRACE_MS = 5_000L
    }
}
