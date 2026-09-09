package com.keepasskey.app.autofill

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.security.FlagSecureGuard
import com.keepasskey.app.ui.screens.unlock.UnlockScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 传统自动填充 (AutofillService) 链式解锁落地 Activity。
 *
 * 官方自动填充认证流程：锁库时数据集的认证 PendingIntent 指向本 Activity；
 * 解锁成功后立即 setResult(RESULT_OK) 并 finish()——自动填充框架收到成功结果后
 * 会自动重新发起 onFillRequest，此时密码库已解锁，服务端即可输出真实凭据候选。
 * （此前指向 MainActivity 且不结束，框架收不到认证完成事件，导致解锁后候选永远不出。）
 *
 * ISSUE-P0-01 (ZT-01)：本 Activity 属不经 MainActivity 的独立冷启动入口，
 * 防护与主入口同源——挂载 FlagSecureGuard 动态守卫（首帧同步生效，冷启动会话
 * 必为锁定态 → 强制遮蔽无条件成立）；熄屏熔断与后台超时锁定由进程级
 * AutoLockManager（MainApplication.onCreate 注册）统一覆盖。
 */
@AndroidEntryPoint
class AutofillUnlockActivity : FragmentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var flagSecureGuard: FlagSecureGuard

    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 与 MainActivity 同源的 FLAG_SECURE 动态守卫（用户开关 ∨ 会话锁定态并集，首帧同步生效）
        flagSecureGuard.attach(this, lifecycleScope)
        // 官方反 overlay 攻击加固：屏蔽其它应用悬浮窗覆盖解锁窗口
        window.setHideOverlayWindows(true)

        lifecycleScope.launch {
            val settings = settingsRepository.getSettings().first()
            setContent {
                UnlockScreen(
                    currentTheme = settings.themeMode,
                    onThemeToggle = { /* 自动填充解锁场景不提供主题切换 */ },
                    onUnlockSuccess = { completeAuthResult() },
                    onNavigateToDatabasePicker = { /* 自动填充解锁场景不提供库切换导航 */ }
                )
            }
        }
    }

    private fun completeAuthResult() {
        if (completed) return
        completed = true
        setResult(RESULT_OK)
        finish()
    }
}
