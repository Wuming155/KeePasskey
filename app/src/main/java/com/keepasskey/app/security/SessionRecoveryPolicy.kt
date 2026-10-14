package com.keepasskey.app.security

/**
 * 自动锁定路径的**明确结果**（`ISSUE-P1-592` 步骤 1）。
 *
 * 锁定前若存在 DIRTY 状态，`AutoLockSessionGuard.triggerLock` 会先补存；本类型把该分支的
 * 结局如实回传，使「补存成功 / 恢复副本已提交 / 全部保全失败」三态**可被上层观察**，
 * 不再只有一行日志。锁定本身在任何分支都照常生效（安全事件不可被数据保全拖住）。
 */
sealed interface LockOutcome {

    /** 无未落盘修改，无需补存。 */
    data object NoPendingChanges : LockOutcome

    /** 补存成功，正式库已是最新。 */
    data object Saved : LockOutcome

    /** 补存失败，但独立加密恢复副本已提交，可下次冷启动恢复。 */
    data object RecoveryCommitted : LockOutcome

    /**
     * 补存失败且恢复副本也未能写出：未落盘修改**已丢失**。
     * [alertPersisted] = 「无法保全」告警是否已持久化（false 时当前界面必须当场明示，
     * 不可承诺进程重启后仍有记录）。
     */
    data class PreservationFailed(val alertPersisted: Boolean) : LockOutcome

    /** 长任务挂锁期暂存，本次未执行锁定（`ISSUE-P3-366`）。 */
    data object Deferred : LockOutcome
}

/**
 * 补存失败后的**保全策略**端口（`ISSUE-P1-592` 步骤 2 / 4）。
 *
 * 由 `AutoLockSessionGuard` 依赖倒置持有：生产绑定 [SessionRecoveryCoordinator]（写独立加密副本 +
 * 持久化告警）；纯 JVM 单测缺省用 [Disabled]（不写副本、登记未持久化告警），保持既有
 * 「补存失败只告警」的可观察性而不引入 Android 依赖。
 */
interface SessionRecoveryPolicy {

    /** 补存失败后尽力保全未落盘修改；返回明确结果。 */
    suspend fun onSaveFailedBeforeLock(): LockOutcome

    companion object {
        /** 缺省替身：不做恢复（仅登记「未持久化告警」）。 */
        val Disabled: SessionRecoveryPolicy = object : SessionRecoveryPolicy {
            override suspend fun onSaveFailedBeforeLock(): LockOutcome =
                LockOutcome.PreservationFailed(alertPersisted = false)
        }
    }
}
