package com.keepasskey.app.security

/**
 * 剪贴板纯裁决逻辑（自 [ClipboardSecurityManager] 抽出以便**纯 JVM 单测**——Android
 * `ClipboardManager` / `Context` 在宿主 JVM 单测中不可用）。两处职责与各自条目一一对应：
 *
 * 1. [resolveScheduledTimeoutSeconds]（ISSUE-P3-144）：本次敏感复制**是否调度**擦除、
 *    以及用几秒——含「用户关闭自动擦除时显式 `customTimeoutSeconds` 同样被否决」的语义；
 * 2. [shouldClearOnUnreadableClipboard]（ISSUE-P2-51 AC②）：后台读不到剪贴板时的清空裁决。
 *
 * ## ISSUE-P2-51 AC②：空读分支不得误清
 *
 * Android 10+ 后台读不到主剪贴板（`primaryClip == null`），原实现仅凭「记录摘要 == 计划摘要」
 * 即 fail-safe 清空；但若期间已发生覆盖写（如 `copyPlainText`），该匹配命中的是**陈旧摘要**，
 * 清空会误伤他处内容。故此处显式要求「未发生覆盖写」。
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
     * 顺序与语义均由 `ClipboardSecurityManagerScheduledClearTest` 锁定：该用例对第 1 步的
     * **提前返回**做变异验证——把本行改为「与秒数共同裁决」（即用户开关不再否决非空
     * `customTimeoutSeconds`）后，行为层用例即以
     * `expected null, but was:<7>` 变红；仅把本行**移到**第 2 步之后（纯语句调序）则由该用例的
     * 顺序守卫（`autoClearClipboard` 必须早于 `customTimeoutSeconds`）变红。
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
     * @param recordedHash 记录的最后敏感值摘要（null=无待清敏感值）
     * @param expectedHash 本次计划要清空的摘要
     * @param superseded 自本次敏感复制以来是否已发生可观测的覆盖写
     * @return 是否应执行清空
     */
    fun shouldClearOnUnreadableClipboard(
        recordedHash: ByteArray?,
        expectedHash: ByteArray,
        superseded: Boolean
    ): Boolean {
        if (superseded) return false
        if (recordedHash == null) return false
        return java.security.MessageDigest.isEqual(recordedHash, expectedHash)
    }
}
