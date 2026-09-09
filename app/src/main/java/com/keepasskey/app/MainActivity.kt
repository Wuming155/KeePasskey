package com.keepasskey.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.security.AutoLockManager
import com.keepasskey.app.security.FlagSecureGuard
import com.keepasskey.app.ui.KeePasskeyApp
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

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
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var flagSecureGuard: FlagSecureGuard

    @Inject
    lateinit var autoLockManager: AutoLockManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 系统硬化：屏蔽第三方应用悬浮窗覆盖本窗口（官方反 overlay 攻击建议，
        // FLAG_SECURE 不覆盖该攻击面）
        window.setHideOverlayWindows(true)

        // 绑定 FLAG_SECURE 动态守卫（用户开关 ∨ 会话锁定态并集，首帧同步生效）
        flagSecureGuard.attach(this, lifecycleScope)

        setContent {
            KeePasskeyApp()
        }
    }
}
