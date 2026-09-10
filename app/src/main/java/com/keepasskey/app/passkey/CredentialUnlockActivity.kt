package com.keepasskey.app.passkey

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.FlagSecureGuard
import com.keepasskey.app.ui.screens.unlock.UnlockScreen
import com.keepasskey.core.log.AppLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Credential Manager 链式解锁落地 Activity（锁库 UX v2）。
 *
 * 官方推荐流程：当 [KeePasskeyCredentialProviderService] 因密码库锁定而返回「解锁 Action」时，
 * 用户点选后由系统拉起本 Activity；解锁成功后取回原始 BeginGetCredentialRequest，
 * 重新组装凭据候选列表，经 [PendingIntentHandler.setBeginGetCredentialResponse] 设置活动结果并结束，
 * 系统 Credential Manager 随即继续呈现凭据候选——用户一次解锁直达填充，无需手动二次发起。
 *
 * 链路：锁库 Action → 本 Activity 解锁 → setResult(BeginGetCredentialResponse) → 系统呈现候选。
 *
 * ISSUE-P1-01：原始请求由**系统**经 fillIn Intent 注入，故上游 [AuthenticationAction] 的
 * PendingIntent 必须以 `FLAG_MUTABLE` 创建（见 [CredentialPendingIntents.ENTRY_FLAGS]）；
 * 若误用 `FLAG_IMMUTABLE`，[PendingIntentHandler.retrieveBeginGetCredentialRequest] 恒为 null，
 * 本 Activity 只能 `RESULT_CANCELED`——表现为「解锁成功却永不出现凭据候选」。
 *
 * ISSUE-P0-01 (ZT-01)：本 Activity 属不经 MainActivity 的独立冷启动入口，
 * 防护与主入口同源——挂载 FlagSecureGuard 动态守卫（首帧同步生效，冷启动会话
 * 必为锁定态 → 强制遮蔽无条件成立）；熄屏熔断与后台超时锁定由进程级
 * AutoLockManager（MainApplication.onCreate 注册）统一覆盖。
 */
@AndroidEntryPoint
class CredentialUnlockActivity : FragmentActivity() {

    @Inject
    lateinit var vaultRepository: VaultRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var responseAssembler: CredentialResponseAssembler

    @Inject
    lateinit var flagSecureGuard: FlagSecureGuard

    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 与 MainActivity 同源的 FLAG_SECURE 动态守卫（用户开关 ∨ 会话锁定态并集，首帧同步生效）
        flagSecureGuard.attach(this, lifecycleScope)
        // 官方反 overlay 攻击加固：屏蔽其它应用悬浮窗覆盖解锁窗口
        window.setHideOverlayWindows(true)

        val originalRequest = try {
            PendingIntentHandler.retrieveBeginGetCredentialRequest(intent)
        } catch (t: Throwable) {
            AppLog.w(TAG, "解析原始 BeginGetCredentialRequest 失败", t)
            null
        }

        lifecycleScope.launch {
            // 密码库已处于解锁状态（例如用户在其他入口刚解锁过）时直接回传候选，跳过解锁页
            if (!vaultRepository.isLocked()) {
                completeChainedResponse(originalRequest)
            }
        }

        // ISSUE-P1-01 加固：lifecycleScope 为 Dispatchers.Main.immediate，上述 launch 已在
        // 主线程同步执行完毕；若已完成回传则不渲染解锁页，杜绝解锁后闪屏再 finish。
        if (!completed) renderUnlockScreen(originalRequest)
    }

    private fun renderUnlockScreen(originalRequest: BeginGetCredentialRequest?) {
        lifecycleScope.launch {
            if (completed) return@launch
            val settings = settingsRepository.getSettings().first()
            setContent {
                UnlockScreen(
                    currentTheme = settings.themeMode,
                    onThemeToggle = { /* 链式解锁场景不提供主题切换 */ },
                    onUnlockSuccess = { completeChainedResponse(originalRequest) },
                    onNavigateToDatabasePicker = { /* 链式解锁场景不提供库切换导航 */ }
                )
            }
        }
    }

    /**
     * 解锁成功后的链式收尾：重新组装凭据候选并作为 Activity 结果回传给系统 Credential Manager。
     */
    private fun completeChainedResponse(originalRequest: BeginGetCredentialRequest?) {
        if (completed) return
        completed = true

        lifecycleScope.launch {
            try {
                if (originalRequest == null) {
                    // 唯一成因：上游 AuthenticationAction 的 PendingIntent 未以 FLAG_MUTABLE 创建，
                    // 系统注入的 fillIn extras 被丢弃（ISSUE-P1-01）。fail-closed，绝不伪造候选。
                    AppLog.w(TAG, "缺少原始凭据请求（AuthenticationAction PendingIntent 需 FLAG_MUTABLE），无法链式回传候选")
                    failAndFinish()
                    return@launch
                }

                val response: BeginGetCredentialResponse =
                    responseAssembler.buildUnlockedGetResponse(originalRequest)

                val resultIntent = Intent()
                PendingIntentHandler.setBeginGetCredentialResponse(resultIntent, response)
                setResult(RESULT_OK, resultIntent)
                finish()
            } catch (t: Throwable) {
                AppLog.e(TAG, "链式解锁回传候选失败", t)
                failAndFinish()
            }
        }
    }

    private fun failAndFinish() {
        setResult(RESULT_CANCELED)
        finish()
    }

    companion object {
        private const val TAG = "CredentialUnlockActivity"
    }
}
