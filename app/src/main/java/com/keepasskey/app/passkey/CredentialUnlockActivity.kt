package com.keepasskey.app.passkey

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.screens.unlock.UnlockScreen
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
 */
@AndroidEntryPoint
class CredentialUnlockActivity : FragmentActivity() {

    @Inject
    lateinit var vaultRepository: VaultRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var responseAssembler: CredentialResponseAssembler

    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        // 官方反 overlay 攻击加固：屏蔽其它应用悬浮窗覆盖解锁窗口
        window.setHideOverlayWindows(true)

        val originalRequest = try {
            PendingIntentHandler.retrieveBeginGetCredentialRequest(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "解析原始 BeginGetCredentialRequest 失败", t)
            null
        }

        lifecycleScope.launch {
            // 密码库已处于解锁状态（例如用户在其他入口刚解锁过）时直接回传候选，跳过解锁页
            if (!vaultRepository.isLocked()) {
                completeChainedResponse(originalRequest)
            }
        }

        renderUnlockScreen(originalRequest)
    }

    private fun renderUnlockScreen(originalRequest: BeginGetCredentialRequest?) {
        lifecycleScope.launch {
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
                    Log.w(TAG, "缺少原始凭据请求，无法链式回传候选")
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
                Log.e(TAG, "链式解锁回传候选失败", t)
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
