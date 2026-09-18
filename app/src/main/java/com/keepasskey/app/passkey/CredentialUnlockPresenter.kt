package com.keepasskey.app.passkey

import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.ApplyObscuredTouchFilter
import com.keepasskey.app.ui.screens.unlock.UnlockScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **创建链路**的库锁定引导（`ISSUE`：锁库时创建凭据无解锁入口）。
 *
 * ## 背景
 *
 * `androidx.credentials:1.6.0` 的 [androidx.credentials.provider.PendingIntentHandler]
 * **没有** `setBeginCreateCredentialResponse`（已核对该版本 AAR 的公开方法表），因此
 * 「先回传解锁 Action、解锁后再重新呈现创建条目」的链式解锁在创建方向**不可用**。
 *
 * 替代方案（本实现）：创建条目照常下发，用户点选后由落地 Activity 在**同一受保护窗口内**
 * 呈现解锁页；解锁成功再继续原创建流程。语义与 [CredentialUnlockActivity]（Get 链式解锁）
 * 对齐：一次解锁直达，且失败路径（用户放弃解锁）保持既有 `RESULT_CANCELED`。
 *
 * ## 为何不放在基类里
 *
 * 各创建 Activity 已各自注入 `VaultRepository`；在基类重复声明同名 `@Inject` 字段会造成
 * 字段遮蔽，故收敛为可注入的协作单元，由需要者显式调用。
 */
@Singleton
class CredentialUnlockPresenter @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val settingsRepository: SettingsRepository
) {

    /**
     * 保证密码库已解锁后执行 [onUnlocked]：
     * - 已解锁 → 立即执行（无 UI 闪动）；
     * - 锁定 → 在 [activity] 上渲染解锁页，解锁成功后执行 [onUnlocked]。
     *
     * 必须在主线程调用（内部涉及 `setContent`）。
     */
    fun requireUnlocked(activity: FragmentActivity, onUnlocked: () -> Unit) {
        activity.lifecycleScope.launch {
            if (!vaultRepository.isLocked()) {
                onUnlocked()
                return@launch
            }
            val settings = settingsRepository.getSettings().first()
            activity.setContent {
                // 遮挡触摸过滤（ISSUE-P2-09 / P3-12）
                ApplyObscuredTouchFilter()
                UnlockScreen(
                    currentTheme = settings.themeMode,
                    onThemeToggle = { /* 凭据创建窗口不提供主题切换 */ },
                    onUnlockSuccess = { onUnlocked() },
                    onNavigateToDatabasePicker = { /* 凭据创建窗口不提供库切换导航 */ }
                )
            }
        }
    }
}
