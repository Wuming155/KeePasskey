package com.keepasskey.app.autofill

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.data.repository.SettingsRepository
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
 */
@AndroidEntryPoint
class AutofillUnlockActivity : FragmentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

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
