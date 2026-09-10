package com.keepasskey.app.ui.screens.unlock

/**
 * 解锁页「进入即自动唤起生物识别」的状态机（ISSUE-P3-01）。
 *
 * 为什么必须是状态机而非布尔量：真机故障的一个结构性根因是**缺少显式「已消费」建模**——
 * 触发条件原先写在 Composable 的 `LaunchedEffect` 里直接判断两个异步状态位，
 * 任何重组、切后台回前台（onResume 类钩子）或状态抖动都可能再次满足条件而重复弹窗，
 * 而「取消 → 再次自动弹出 → 再取消」正是死循环的来源。
 *
 * 本状态机把「已消费」建模为**终态**：[PENDING] 只能从 [IDLE] 抵达，
 * 一旦进入 [CONSUMED]，在本 ViewModel 生命周期内不可能再回到 [PENDING]，
 * 死循环在结构上不可达（无需依赖任何时序假设）。
 *
 * 可见性说明：本枚举经公开数据类 `UnlockUiState.biometricAutoPrompt` 暴露给 UI 层，
 * 故必须为 `public`（不能是 `internal`，否则公开属性暴露 internal 类型无法编译）。
 */
enum class BiometricAutoPrompt {
    /** 尚不满足自动唤起条件：开关关闭 / 无封印凭据 / 用户已显式选择主密码 / 无活动库 */
    IDLE,

    /** 已判定应自动唤起一次，等待 UI 通过一次性意图消费 */
    PENDING,

    /** 已被消费（已发起过一次唤起）；无论成功、取消或失败，本次进入解锁页都不再自动唤起 */
    CONSUMED
}

/**
 * [BiometricAutoPrompt] 的状态迁移规则（纯函数，无 Android 依赖，可直接单测）。
 */
internal object BiometricAutoPromptPolicy {

    /**
     * 计算重算后的自动唤起状态。
     *
     * 迁移规则：仅当当前为 [IDLE] 且四项条件（开关开启 / 存在封印凭据 / 有活动库 /
     * 快速解锁模式）同时成立时进入 [PENDING]；[PENDING] 与 [CONSUMED] 一律原样返回，
     * 保证「已消费」不可逆、待消费不被异步状态抖动重置。
     */
    fun next(
        current: BiometricAutoPrompt,
        biometricEnabled: Boolean,
        quickUnlockAvailable: Boolean,
        unlockMode: UnlockMode,
        hasDatabase: Boolean
    ): BiometricAutoPrompt = when {
        current != BiometricAutoPrompt.IDLE -> current
        biometricEnabled && quickUnlockAvailable && hasDatabase && unlockMode == UnlockMode.QUICK_UNLOCK ->
            BiometricAutoPrompt.PENDING
        else -> BiometricAutoPrompt.IDLE
    }
}
