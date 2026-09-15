package com.keepasskey.app.ui.screens.vault

/**
 * 「彻底退出应用」入口的可见性判定与终止动作（ISSUE-P3-17；清理步骤见 ISSUE-P3-116）。
 *
 * 可见性抽为纯函数：偏好开启**且**宿主可终止时才呈现入口——缺少 Activity 上下文时
 * 如实不呈现，绝不做一个点了没反应的假入口。
 * [terminate] 把「解除任务栈亲和性 → 清理易失缓存 → 终止进程」收敛为固定顺序，
 * 便于单测断言，也避免两处调用各写一遍顺序。
 */
object AppTerminationPolicy {

    /** 进程正常退出码（0 = 正常退出，非崩溃） */
    const val EXIT_CODE_NORMAL = 0

    /**
     * 是否呈现「彻底退出应用」入口。
     *
     * @param enabled 用户偏好（`ExtendedSettings.showKillAppOption`）
     * @param hostAvailable 宿主 Activity 可用（不可终止时不呈现）
     */
    fun showsEntry(enabled: Boolean, hostAvailable: Boolean): Boolean = enabled && hostAvailable

    /**
     * 真实终止应用：**解除任务栈亲和性 → 清理易失缓存 → 终止进程**（[exitProcess] 对应
     * `exitProcess(0)`，[detachTask] 对应 `activity.finishAffinity()`）。
     *
     * 顺序不可颠倒：
     * - 先退栈再退出，避免最近任务中出现残影；
     * - **清理必须早于** [exitProcess]——进程一旦终止，其后任何代码都不再有机会执行
     *   （ISSUE-P3-116：此前该入口只做 `finishAffinity + exitProcess`，用户以为「退出即不留痕」，
     *   而 `cacheDir/attachments` 的**附件明文**与 `cacheDir/sync` 的**密文快照**仍留在磁盘上，
     *   直至下次冷启动或会话锁定才被清理）。
     *
     * [purgeCaches] 须为**同步且不自抛**的实现：退出是用户显式动作，清理失败也不得阻断退出
     * （失败由实现侧落脱敏日志）。
     */
    fun terminate(
        detachTask: () -> Unit,
        purgeCaches: () -> Unit,
        exitProcess: (Int) -> Unit
    ) {
        detachTask()
        purgeCaches()
        exitProcess(EXIT_CODE_NORMAL)
    }
}
