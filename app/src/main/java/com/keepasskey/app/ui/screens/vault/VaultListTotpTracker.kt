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
 * 2. [nowSeconds] 被并入 `VaultListViewModel` 的最外层 `combine`，于是**每秒**
 *    都重跑一次列表页整页投影（过滤 / 排序 / 全量 `copy`），且运行在 Main 线程。
 *
 * 现改为：节拍由 [nowSeconds] 自身的订阅（UI 侧 `collectAsStateWithLifecycle`）
 * 驱动——`WhileSubscribed` 保证「无人订阅即停表」，**不需要也不存在显式 `start()`**；
 * 秒级与周期间的 TOTP 变化只经 [nowSeconds] / [liveCodes] 两条窄通道下发，
 * 由列表行内的徽标按需读取，**整页状态不再随秒数或周期重建**。
 *
 * ## ISSUE-P2-90：跨周期重算走批量通道
 *
 * 跨周期重算一次覆盖当前列表全部带 TOTP 的条目，经
 * [VaultRepository.calculateEntryTotps] 批量取码（一次会话读取 + 一次条目索引），
 * 收敛原「逐条 `calculateEntryTotp`」的 O(T×N)。周期内的重复读取则由数据层缓存吸收。
 *
 * ## ISSUE-P3-158：周期口径按**条目自身**取值，不再假定 30 秒
 *
 * 原实现以全局 30 秒为唯一口径：[nowSeconds]（原 `remainingSeconds`）只下发「距下一个
 * 30 秒网格边界的秒数」，跨周期重算亦只在 30 秒边界触发。对 `period != 30` 的条目：
 * - 倒计时/进度环显示错误（分母与相位都不对）；
 * - `period < 30` 且不整除 30 时（如 45 / 20），**验证码会滞留到下一个 30 秒边界**——
 *   列表显示的是**过期验证码**（这是正确性缺陷，不只是观感）。
 *
 * 现改为下发**秒级刻度** [nowSeconds]，倒计时由订阅方按条目自身 [UiVaultEntry.totpPeriod]
 * 经 `OtpEngine.getRemainingSeconds` 换算；重算触发改为「**任一条目自身周期的序号变化**」。
 * 全 30 秒的库与本改动之前逐拍等价（周期内零重算、跨周期必重算）。
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

    /** 记录上一拍的**秒级刻度**（仅在节拍上游内访问，单收集者） */
    private var previousTickSecond = NO_TICK

    /**
     * 当前时刻的**秒级刻度**（自 1970 起的整秒，取自注入的 [nowMillis]）。
     *
     * **本 StateFlow 即节拍本体**：其上游（`tickerFlow`）由 `WhileSubscribed` 控制——
     * 有人订阅才计时（列表页可见时由行内徽标订阅），无人订阅即停，故不需要显式启动/停止 API。
     *
     * 为什么下发**刻度**而不是「剩余秒数」：倒计时必须按**各条目自身周期**换算
     * （`period != 30` 的条目在 30 秒网格下会显示错误值），换算只需刻度与周期两个输入，
     * 由订阅方用 `OtpEngine.getRemainingSeconds` 现算即可；下发单一剩余秒数等于把「全局 30 秒」
     * 这个错误前提固化进通道（ISSUE-P3-158 的缺陷形态）。
     */
    val nowSeconds: StateFlow<Long> = tickerFlow(TOTP_TICK_INTERVAL_MS)
        .map { nowMillis() / MILLIS_PER_SECOND }
        .distinctUntilChanged()
        // 每次（重新）订阅都按首帧处理：必然刷新一次验证码，避免泊位期间跨过的周期留下旧码
        .onStart { previousTickSecond = NO_TICK }
        .onEach { second ->
            // 跨过**任一条目自身周期**的边界 → 批量重算验证码；周期内的重复 tick 零重算
            if (crossedAnyEntryPeriodBoundary(second)) {
                refreshLiveCodes()
            }
            previousTickSecond = second
        }
        // 验证码重算含会话读取与 HMAC：整条上游（含 onEach 副作用）必须离开 Main
        .flowOn(dispatcher)
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(TOTP_SUBSCRIPTION_GRACE_MS),
            initialValue = nowMillis() / MILLIS_PER_SECOND
        )

    /** entryId → 本周期实时验证码（仅在周期翻转时按需重算） */
    val liveCodes: StateFlow<Map<String, String>> = liveCodesFlow

    /**
     * 本拍是否跨过了**当前列表中任一带 TOTP 条目自身周期**的边界（是则必须重算验证码）。
     *
     * 判据是「周期序号是否变化」而非「剩余秒数是否回跳」：后者只在全局 30 秒网格上成立，
     * 对 `period != 30` 的条目会漏掉边界（`period < 30` 时显示过期验证码）。
     * 首拍（[NO_TICK]）恒视为跨周期——重新订阅时可能已跨过周期，否则会留下旧码。
     */
    private fun crossedAnyEntryPeriodBoundary(second: Long): Boolean {
        val previous = previousTickSecond
        if (previous == NO_TICK) return true
        return entryPeriods().any { period -> previous / period != second / period }
    }

    /** 当前列表带 TOTP 条目的**去重周期**（`<= 0` 的异常值按缺省周期兜底，避免除零） */
    private fun entryPeriods(): Set<Int> =
        currentEntries()
            .filter { it.totpCode != null }
            .map { if (it.totpPeriod > 0) it.totpPeriod else TOTP_PERIOD_SECONDS }
            .toSet()

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

    private companion object {
        const val TOTP_PERIOD_SECONDS = 30

        /** 尚未收到任何一拍（`-1` 不可能是合法的自 1970 起秒数） */
        const val NO_TICK = -1L
        const val MILLIS_PER_SECOND = 1000L
        const val TOTP_TICK_INTERVAL_MS = 1000L

        /** 订阅宽限期（毫秒）：与列表页 `uiState` 的 `WhileSubscribed` 同值，短暂离开不重算 */
        const val TOTP_SUBSCRIPTION_GRACE_MS = 5_000L
    }
}
