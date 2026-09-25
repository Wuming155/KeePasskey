package com.keepasskey.app.autofill.legacy

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.R
import com.keepasskey.app.autofill.AutofillAuthenticationPolicy
import com.keepasskey.app.autofill.AutofillEntrySearch
import com.keepasskey.app.autofill.AutofillPickerScreen
import com.keepasskey.app.autofill.AutofillPickerViewModel
import com.keepasskey.app.security.ApplyObscuredTouchFilter
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.AutofillAuthBindingPolicy
import com.keepasskey.app.security.BiometricStatus
import com.keepasskey.core.log.AppLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 旧版无障碍通道的条目选择器（ISSUE-P3-324）。
 *
 * 由「检测到口令框」通知拉起，与目标应用分属两个任务（`FLAG_ACTIVITY_NEW_TASK`），
 * 选择完成回栈即回到目标应用。复用框架通道选择器的既有资产：
 * [AutofillPickerViewModel]（非敏感投影装载 + 按需单条解密）与 [AutofillPickerScreen]。
 *
 * 与框架选择器（`AutofillPickerActivity`）的差异（v1 有意裁剪，登记于批次文档）：
 * 不经 Autofill 框架回传数据集（改为 [LegacyAutofillCoordinator] 递交、服务回填），
 * 不提供字段级屏蔽写入与会话授权宽限（旧版通道每次选择都需显式确认，无重复确认可免）。
 *
 * 安全加固（对齐框架选择器）：FLAG_SECURE 防截屏录屏 + `setHideOverlayWindows`
 * 反悬浮窗覆盖 + 遮挡触摸过滤（反点击劫持）。
 */
@AndroidEntryPoint
class LegacyFillPickerActivity : FragmentActivity() {

    @Inject
    lateinit var biometricAuthManager: BiometricAuthManager

    @Inject
    lateinit var vaultRepository: com.keepasskey.app.data.repository.VaultRepository

    @Inject
    lateinit var coordinator: LegacyAutofillCoordinator

    private val viewModel: AutofillPickerViewModel by viewModels()

    private var completed = false

    private val targetPackage: String?
        get() = intent.getStringExtra(EXTRA_TARGET_PACKAGE)?.takeIf { it.isNotBlank() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        window.setHideOverlayWindows(true)
        window.decorView.filterTouchesWhenObscured = true

        // 无目标包名（非法拉起 / extras 丢失）：无事可做，直接退出
        if (targetPackage == null) {
            AppLog.w(TAG, "旧版通道选择器缺少目标包名，退出")
            finish()
            return
        }
        viewModel.loadEntries()

        setContent {
            var query by remember { mutableStateOf("") }
            val entries by viewModel.entries.collectAsStateWithLifecycle()
            val results = remember(query, entries) { AutofillEntrySearch.filter(entries, query) }

            ApplyObscuredTouchFilter()
            AutofillPickerScreen(
                query = query,
                onQueryChange = { query = it },
                results = results,
                onPick = ::confirmAndSubmit,
                onCancel = ::finish
            )
        }
    }

    /**
     * 用户选中条目：按需解密 → 锁定复核 → 生物识别确认（可用认证器时，退化语义同框架
     * 选择器）→ 递交协调器 → 结束回栈。回填由服务在后台完成（观察待决载荷）。
     */
    private fun confirmAndSubmit(entryId: String) {
        if (completed) return
        val target = targetPackage ?: return
        lifecycleScope.launch {
            // 进入交付链路前锁定复核（与框架选择器 ISSUE-P3-201 同口径）：锁定即终止
            if (!AutofillAuthenticationPolicy.canDeliverAuthResult(vaultRepository.isLocked())) {
                AppLog.w(TAG, "会话已锁定，放弃旧版通道选择交付")
                finish()
                return@launch
            }
            val credentials = viewModel.resolveCredentials(entryId)
            if (credentials == null) {
                AppLog.w(TAG, "选中条目凭据不可用，放弃旧版通道交付")
                finish()
                return@launch
            }
            // 可用认证器时以 Keystore 认证绑定密钥的 Cipher 发起（CryptoObject）；无可用
            // 认证器 / 密钥不可用时退化为受保护窗口内的显式点选确认（同框架选择器退化语义）
            val authStatus = biometricAuthManager.canAuthenticate(
                this@LegacyFillPickerActivity,
                BiometricAuthManager.UNLOCK_AUTHENTICATORS
            )
            val authCipher = if (authStatus == BiometricStatus.AVAILABLE) {
                biometricAuthManager.prepareAutofillAuthCipher()
            } else {
                null
            }
            if (authStatus == BiometricStatus.AVAILABLE && authCipher != null) {
                biometricAuthManager.authenticate(
                    activity = this@LegacyFillPickerActivity,
                    title = getString(R.string.autofill_confirm_title),
                    subtitle = getString(R.string.autofill_picker_confirm_sub),
                    authenticators = BiometricAuthManager.UNLOCK_AUTHENTICATORS,
                    cipher = authCipher
                ) { result ->
                    if (AutofillAuthBindingPolicy.isBound(result)) {
                        submit(target, credentials)
                    } else {
                        finish()
                    }
                }
            } else {
                submit(target, credentials)
            }
        }
    }

    /** 递交协调器（服务侧消费回填）并结束；递交前再次锁定复核（确认等待期间可能已锁） */
    private fun submit(target: String, credentials: AutofillPickerViewModel.Credentials) {
        if (completed) return
        completed = true
        lifecycleScope.launch {
            if (!AutofillAuthenticationPolicy.canDeliverAuthResult(vaultRepository.isLocked())) {
                AppLog.w(TAG, "会话在交付过程中被锁定，丢弃旧版通道待决载荷")
                finish()
                return@launch
            }
            coordinator.submit(
                LegacyAutofillCoordinator.PendingFill(
                    targetPackage = target,
                    username = credentials.username,
                    password = credentials.password
                )
            )
            finish()
        }
    }

    companion object {
        private const val TAG = "LegacyAutofill"

        /** 通知对应的目标应用包名（非敏感标识，仅用于窗口一致性复核） */
        const val EXTRA_TARGET_PACKAGE = "com.keepasskey.app.autofill.legacy.EXTRA_TARGET_PACKAGE"
    }
}
