package com.keepasskey.app

import android.Manifest
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.notification.NotificationGate
import com.keepasskey.app.notification.NotificationPermissionDecision
import com.keepasskey.app.notification.NotificationPermissionPrompter
import com.keepasskey.app.security.AutoLockManager
import com.keepasskey.app.security.FlagSecureGuard
import com.keepasskey.app.ui.KeePasskeyApp
import com.keepasskey.database.session.DatabaseSession
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 应用主入口 Activity，承载 KeePasskeyApp 全局 Compose 导航与主题容器。
 * 继承 FragmentActivity 以支持 AndroidX BiometricPrompt 强生物识别硬件弹窗；
 * 挂载 FlagSecureGuard（防截屏/防多任务窥视）。
 *
 * TASK-22 整改：AutoLockManager 为 @Singleton（进程级生命周期，监听
 * ProcessLifecycleOwner + 熄屏广播），旋转/配置重建触发的 onDestroy 不得销毁它。
 * ISSUE-P0-01 (ZT-01)：其 initialize() 已下沉至 MainApplication.onCreate()（进程级一次），
 * 以覆盖 Autofill / Credential 链式解锁等不经本类的冷启动入口；本类仅保留字段
 * 供 KeePasskeyApp 经 LocalContext 取用（lockEvents 订阅 / triggerLock / onUnlockSuccess）。
 *
 * ISSUE-P3-18：通知运行时权限（`POST_NOTIFICATIONS`）的唯一请求入口——
 * 采用 ActivityResultContracts.RequestPermission 契约，且只在「库已解锁」这一语境下发问，
 * 用户拒绝后不再重复请求（详见 [requestNotificationPermissionAfterUnlock]）。
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var flagSecureGuard: FlagSecureGuard

    @Inject
    lateinit var autoLockManager: AutoLockManager

    @Inject
    lateinit var databaseSession: DatabaseSession

    @Inject
    lateinit var notificationPermissionPrompter: NotificationPermissionPrompter

    /** 通知权限请求契约（注册必须早于 onStart，故置于属性初始化处） */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationPermissionPrompter.onRequestResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 系统硬化：屏蔽第三方应用悬浮窗覆盖本窗口（官方反 overlay 攻击建议，
        // FLAG_SECURE 不覆盖该攻击面）
        window.setHideOverlayWindows(true)

        // 绑定 FLAG_SECURE 动态守卫（用户开关 ∨ 会话锁定态并集，首帧同步生效）
        flagSecureGuard.attach(this, lifecycleScope)

        requestNotificationPermissionAfterUnlock()

        setContent {
            KeePasskeyApp()
        }
    }

    /**
     * 通知权限请求（ISSUE-P3-18 验收标准 2）。
     *
     * 时机选择：等到密码库**首次解锁**之后才询问，而不是冷启动即弹窗——
     * 此时「已解锁状态通知 / 验证码通知」即将真实产生，请求语境成立。
     *
     * 是否请求完全交给 [NotificationPermissionPrompter.decide] 的三重闸门
     * （已授权 / 已询问过 / 需向用户解释），因此本方法：
     * - 用户拒绝过一次后**永不**再次弹窗（询问标志已持久化）；
     * - 已授权后不再打扰；
     * - 用户拒绝也不影响任何功能可用性：相关通知静默降级，绝不崩溃。
     */
    private fun requestNotificationPermissionAfterUnlock() {
        lifecycleScope.launch {
            databaseSession.state.first { NotificationGate.isUnlockedState(it) }
            val decision = notificationPermissionPrompter.decide(this@MainActivity)
            if (decision == NotificationPermissionDecision.REQUEST) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
