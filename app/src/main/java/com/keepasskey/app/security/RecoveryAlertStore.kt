package com.keepasskey.app.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动锁定「无法保全」告警的持久化（`ISSUE-P1-592` 步骤 1 / 4）。
 *
 * 锁定前补存失败、且独立恢复副本也写不成（磁盘满 / 存储不可写）时，本类**尽力**把「上次锁定
 * 未能保全未落盘修改」这一事实写入持久层，供**下次进入应用**查看。告警**只含非敏感信息**
 * （库身份、时刻、是否写出过恢复副本），不含任何明文口令或内容。
 *
 * 写入用 [android.content.SharedPreferences.Editor.commit]（同步、**有真实成败信号**）——
 * `apply()` 无返回，会让「连告警都写不进去」这一情形被伪报为已留痕；调用方须在 IO 调度器上调用。
 */
@Singleton
class RecoveryAlertStore @Inject constructor(
    // 允许为 null 仅用于纯 JVM 单测（退化为进程内存态）；生产 DI 注入 @ApplicationContext
    @ApplicationContext context: Context?
) {

    /** 一次「未能保全」告警（非敏感）。[recoveryWritten] = 是否写成了恢复副本。 */
    data class Alert(
        val vaultId: String,
        val atEpochMs: Long,
        val recoveryWritten: Boolean
    )

    private val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 无持久层（单测）时的进程内存态替身 */
    private var memory: Alert? = null

    /** 尽力持久化告警；返回 true = 已确认写入（或单测内存态）。 */
    fun persist(alert: Alert): Boolean {
        memory = alert
        val store = prefs ?: return true
        return try {
            store.edit()
                .putString(KEY_VAULT_ID, alert.vaultId)
                .putLong(KEY_AT_MS, alert.atEpochMs)
                .putBoolean(KEY_RECOVERY_WRITTEN, alert.recoveryWritten)
                .commit()
        } catch (e: Exception) { // cancel-n/a: 保护段为阻塞式 prefs 提交（无挂起点）
            false
        }
    }

    /** 一次性消费：「上次未能保全」事实（消费后即清，第二次返回 null）。 */
    fun consume(): Alert? {
        val store = prefs ?: return memory.also { memory = null }
        val vaultId = store.getString(KEY_VAULT_ID, null) ?: return null
        val alert = Alert(
            vaultId = vaultId,
            atEpochMs = store.getLong(KEY_AT_MS, 0L),
            recoveryWritten = store.getBoolean(KEY_RECOVERY_WRITTEN, false)
        )
        store.edit().clear().commit()
        return alert
    }

    /** 清除待消费告警（用户确认后）。 */
    fun clear() {
        memory = null
        prefs?.edit()?.clear()?.commit()
    }

    private companion object {
        const val PREFS_NAME = "vault_recovery_alert"
        const val KEY_VAULT_ID = "vault_id"
        const val KEY_AT_MS = "at_epoch_ms"
        const val KEY_RECOVERY_WRITTEN = "recovery_written"
    }
}
