package com.keepasskey.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 「已解锁」常驻通知的复制快捷动作接收器（`ISSUE-P3-440` AC②）。
 *
 * 走**广播接收器**而非 Activity 是刻意的：动作点击后不展开任何明文 UI（不开条目详情、
 * 不开编辑器），复制在后台完成后只给 Snackbar / Toast 反馈。
 *
 * `goAsync()`：取值 + 写剪贴板是挂起操作，需延长接收器的存活窗口直到
 * [UnlockedNotificationCopyAction.perform] 结束（动作完成即 `finish()`）。
 *
 * 清单注册 `exported=false`（与 [VaultLockActionReceiver] 同一口径：仅本应用的
 * PendingIntent 可投递）。
 */
@AndroidEntryPoint
class UnlockedNotificationCopyReceiver : BroadcastReceiver() {

    @Inject
    lateinit var copyAction: UnlockedNotificationCopyAction

    override fun onReceive(context: Context?, intent: Intent?) {
        val field = UnlockedNotificationCopyField.fromExtra(intent?.getStringExtra(EXTRA_COPY_FIELD))
            ?: return
        val pendingResult = goAsync()
        copyAction.perform(field) { pendingResult.finish() }
    }

    companion object {
        /** 动作字段载荷键（值取自 [UnlockedNotificationCopyField.extraValue]，不含任何用户数据） */
        const val EXTRA_COPY_FIELD = "com.keepasskey.app.extra.NOTIFICATION_COPY_FIELD"
    }
}
