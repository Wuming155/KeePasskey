package com.keepasskey.app.security

/**
 * 剪贴板纯裁决逻辑（自 [ClipboardSecurityManager] 抽出以便**纯 JVM 单测**——Android
 * `ClipboardManager` / `Context` 在宿主 JVM 单测中不可用）。两处职责与各自条目一一对应：
 *
 * 1. [resolveScheduledTimeoutSeconds]（ISSUE-P3-144）：本次敏感复制**是否调度**擦除、
 *    以及用几秒——含「用户关闭自动擦除时显式 `customTimeoutSeconds` 同样被否决」的语义；
 * 2. [shouldClearOnBackgroundSwitch]（ENG-20261028-03）：应用切到后台时是否立即清空；
 * 3. [shouldClearOnUnreadableClipboard]（ISSUE-P2-51 AC②）：读不到剪贴板时的清空裁决。
 *
 * ## ENG-20261028-03：后台不得早于设置清空
 *
 * 原实现在进程进入后台（`ProcessLifecycleOwner` ON_STOP）时无条件物理清空，而「复制口令 → 切到
 * 目标应用粘贴」是本功能唯一的使用路径：用户配置的保留时长被后台切换架空，粘贴时剪贴板已空。
 * 现按 [shouldClearOnBackgroundSwitch] 裁决——已调度定时擦除时后台不清空，粘贴窗口由设置决定。
 *
 * ## 安全条件清理策略（ENG03）：后台不可读时不得物理清空
 *
 * Android 10+（API 29+）后台读不到主剪贴板（官方文档：`getPrimaryClip()` 在非默认输入法且无输入焦点时
 * 返回 `null`）。此前曾假设后台 listener 仍能接收覆盖写变更；但 AOSP 官方实现
 * （`com.android.server.clipboard.ClipboardService.sendClipChangedBroadcast`）在派发
 * `dispatchPrimaryClipChanged` 前显式校验 `clipboardAccessAllowed(OP_READ_CLIPBOARD)`，
 * 缺少窗口焦点的后台应用一律被抑制通知。
 *
 * 故后台在剪贴板不可读时无法证明剪贴板当前内容仍属本应用写入；若按陈旧摘要或无覆盖证据盲目清空，
 * 会误删其它应用或用户新写入的内容。因此在剪贴板不可读时严禁物理清空，待清标记与截止期延后至回到
 * 前台时核验。
 */
internal object ClipboardClearPolicy {

    /**
     * ISSUE-P3-144：解析**本次敏感复制应当调度的擦除秒数**，`null` 表示**完全不调度**。
     *
     * 判断顺序是本函数契约的一部分（与 `armScheduledClear` 原实现逐条同义）：
     * 1. `autoClearClipboard == false` ⇒ **立即**返回 null——**先于**任何对
     *    [customTimeoutSeconds] 的读取。因此用户关闭「自动擦除剪贴板」时，显式传入的
     *    非 null 自定义秒数被**静默否决**（`ISSUE-P3-144` 登记的语义）。这正是
     *    「**假加固**」陷阱的来源：若照本现状实现「敏感路径强制不可关闭的短擦除」，
     *    实际结果仍是「仍然可关闭」；
     * 2. 否则取 `customTimeoutSeconds ?: settingsTimeoutSeconds`；
     * 3. 结果 `<= 0`（含 `-1`、`0` 两个「不清空」口径）⇒ 返回 null，不调度。
     *
     * ⚠ 若未来采纳「强制擦除」，须把本顺序改为「敏感路径**无条件**调度，仅把用户设置作为
     * **上限**（`min`）而非**开关**」，并同步更新 `ClipboardSecurityManager` 三个入口的 KDoc。
     *
     * 顺序与语义由 `ClipboardSecurityManagerScheduledClearTest` 的**行为用例**锁定：把第 1 步
     * 改成「与秒数共同裁决」（开关不再否决非空 `customTimeoutSeconds`）后，用例即以
     * `expected:<null> but was:<...>` 变红。纯语句调序（校验仍能否决、只是晚于秒数计算）不改变
     * 结果，本仓不设源码文本守卫，由本契约与评审维持。
     *
     * @param customTimeoutSeconds 调用方显式指定的秒数（非 null 仍受第 1 步否决）
     * @param autoClearClipboard 用户设置：是否启用剪贴板自动擦除
     * @param settingsTimeoutSeconds 用户设置：默认擦除秒数
     * @return 应调度的秒数；null 表示不调度清除
     */
    fun resolveScheduledTimeoutSeconds(
        customTimeoutSeconds: Int?,
        autoClearClipboard: Boolean,
        settingsTimeoutSeconds: Int
    ): Int? {
        if (!autoClearClipboard) return null
        val timeoutSec = customTimeoutSeconds ?: settingsTimeoutSeconds
        if (timeoutSec <= 0) return null
        return timeoutSec
    }

    /**
     * ENG-20261028-03：应用切到后台时**是否**立即物理清空待清敏感值。
     *
     * - 本次敏感复制已调度定时擦除（用户配置了正数保留时长）⇒ 返回 false：可用的跨应用粘贴窗口
     *   必须与设置一致，后台切换**不得**提前清空；到期清空由定时器负责，熄屏 / 会话锁定 /
     *   冷启动对账仍按各自语义独立生效；
     * - 本次敏感复制**没有**定时擦除计划（关闭「自动擦除剪贴板」，或保留时长选择「不清空」/ ≤0）
     *   ⇒ 返回 true：沿用 ISSUE-P3-84 AC② 的补偿控制「敏感值只在前台窗口驻留」，与设置页
     *   `sec_clipboard_risk_notice` 向用户告知的清空时机一致。
     *
     * @param pasteWindowScheduled 本次敏感复制是否已调度定时擦除
     * @return 是否应在切到后台时立即清空
     */
    fun shouldClearOnBackgroundSwitch(pasteWindowScheduled: Boolean): Boolean = !pasteWindowScheduled

    /**
     * 安全条件清理策略（ENG03）：剪贴板不可读时是否允许物理清空。
     *
     * 当应用退至后台且无输入焦点时（Android 10+），`getPrimaryClip()` 读回 `null`。
     * 同时 AOSP `ClipboardService` 对无焦点的后台应用抑制剪贴板变更广播，应用无法掌握外部是否已写入新值。
     * 无法证明当前剪贴板值仍属本应用时，绝不得物理清空剪贴板（避免误删他处内容）；
     * 待清标记与截止期必须保留，延后至回到前台核验。
     *
     * @return 恒为 false：不可读即不可证明，严禁物理清空
     */
    fun shouldClearOnUnreadableClipboard(): Boolean = false
}
