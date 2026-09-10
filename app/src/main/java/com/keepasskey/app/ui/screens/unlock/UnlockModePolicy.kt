package com.keepasskey.app.ui.screens.unlock

/**
 * 解锁模式推导策略（ISSUE-P3-01 根因修复的纯函数落点）。
 *
 * 背景：`unlockMode` 由「生物识别解锁开关」与「当前库是否存在已封印快速解锁凭据」共同决定，
 * 而这两个状态分别来自设置流与数据库流**两条相互独立的异步源**，抵达顺序不确定。
 * 原实现仅在设置流抵达时用「那一刻的」可用性计算一次模式：若设置先到（凭据状态尚未就绪），
 * 模式被永久定格为 [UnlockMode.STANDARD]，之后再无任何重算时机——
 * 真机表现即「开关已开、封印凭据已在，第二次打开应用仍停留在主密码界面」。
 *
 * 现把推导收敛为本纯函数，任一异步源变化后统一重算，结果与抵达顺序**无关**。
 * 纯函数无 Android 依赖，可直接单测（含顺序颠倒的竞态回归锁）。
 */
internal object UnlockModePolicy {

    /**
     * 解析当前应呈现的解锁模式。
     *
     * @param biometricEnabled 设置中的「生物识别解锁」开关
     * @param quickUnlockAvailable 当前活动库是否存在已封印的快速解锁凭据
     * @param explicitSelection 用户或系统显式选择的模式（如手动切换、生物识别失败回落）；
     *   非空时**优先返回**，不再被后续异步状态重算覆盖
     * @return 仅当开关开启且封印凭据存在时返回 [UnlockMode.QUICK_UNLOCK]，否则返回
     *   [UnlockMode.STANDARD]（fail-safe：宁可用主密码，也不呈现无凭据可用的快捷入口）
     */
    fun resolve(
        biometricEnabled: Boolean,
        quickUnlockAvailable: Boolean,
        explicitSelection: UnlockMode?
    ): UnlockMode = explicitSelection
        ?: if (biometricEnabled && quickUnlockAvailable) {
            UnlockMode.QUICK_UNLOCK
        } else {
            UnlockMode.STANDARD
        }
}
