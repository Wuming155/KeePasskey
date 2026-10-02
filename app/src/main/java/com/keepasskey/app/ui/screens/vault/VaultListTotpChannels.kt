package com.keepasskey.app.ui.screens.vault

import kotlinx.coroutines.flow.StateFlow

/**
 * VaultListViewModel 的 TOTP 窄通道（ISSUE-P2-89 / ISSUE-P3-158）。
 *
 * §280 规模门禁同批从 [VaultListViewModel] **逐字迁出**（结构性拆分，公开 API 与语义零变化）：
 * 本类不持有任何状态，只把 [VaultListViewModel.totpTracker] 的两条输出以扩展属性暴露。
 */

/**
 * 列表行 TOTP 徽标的**秒级刻度**（窄通道）。
 *
 * 本流即列表页的秒级节拍本体（`WhileSubscribed` 驱动，见 [TotpCountdownTracker]）：
 * UI 侧只在渲染徽标处用 `collectAsStateWithLifecycle` 读取，**读取作用域只有徽标本身**，
 * 故每秒的重组面不再扩散到整页状态与全部列表行；页面不可见时无人订阅，节拍自动停止。
 *
 * 下发**刻度**而非「剩余秒数」：倒计时需按各条目自身周期换算，若在下游只给一个
 * 剩余秒数，等于把「全局 30 秒」这一错误前提固化进通道（ISSUE-P3-158）。
 */
val VaultListViewModel.totpNowSeconds: StateFlow<Long> get() = totpTracker.nowSeconds

/**
 * 列表行 TOTP 徽标的**本周期实时验证码**（窄通道，`entryId → 验证码`）。
 *
 * 仅在周期翻转时更新（订阅驱动）。徽标取 `totpLiveCodes[entry.id] ?: entry.totpCode`：
 * 前者是本周期之码，后者是投影层即时计算的兜底值（条目刚出现、尚未等到下一拍刷新时）。
 */
val VaultListViewModel.totpLiveCodes: StateFlow<Map<String, String>> get() = totpTracker.liveCodes
