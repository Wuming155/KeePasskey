package com.keepasskey.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.keepasskey.app.security.AutoLockManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 「库已解锁」常驻通知「立即锁定」快捷动作（ISSUE-P3-386）。
 *
 * 与自动锁**同收口**：委托 [AutoLockManager.triggerLock]（内部走 [AutoLockSessionGuard.triggerLock]
 * 的擦除 + 会话锁定 + 锁定事件），不另起第二条锁定路径。
 *
 * 清单注册为 `exported=false`（本应用内通知动作专用，外部不可伪造投递）。
 */
@AndroidEntryPoint
class VaultLockActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var autoLockManager: AutoLockManager

    override fun onReceive(context: Context?, intent: Intent?) {
        autoLockManager.triggerLock("通知立即锁定")
    }
}
