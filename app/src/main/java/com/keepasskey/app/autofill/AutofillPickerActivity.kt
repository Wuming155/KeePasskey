package com.keepasskey.app.autofill

import android.os.Bundle
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.Presentations
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
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
import com.keepasskey.app.security.ApplyObscuredTouchFilter
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricResult
import com.keepasskey.app.security.BiometricStatus
import com.keepasskey.core.log.AppLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 传统自动填充的「手动选择器」落地 Activity (ISSUE-P3-40)。
 *
 * 背景：自动匹配严格（严格域名 / 严格包名，无启发式），在弱域名、纯 App、内嵌 WebView 等
 * 场景下可能零候选，用户此前无任何补救路径。本 Activity 提供全库搜索与手动选择兜底。
 *
 * 回传机制（官方语义）：选择器以认证数据集方式挂入 `FillResponse`；用户选中后本 Activity
 * 构建真实 [Dataset] 并经 `AutofillManager.EXTRA_AUTHENTICATION_RESULT` 回传，
 * 由框架完成填充——**数据集只在用户显式选中并确认后才携带明文**。
 *
 * 安全加固（对齐 AutofillConfirmActivity）：FLAG_SECURE 防截屏录屏 +
 * `setHideOverlayWindows` 反悬浮窗覆盖 + 遮挡触摸过滤（反点击劫持）。
 */
@AndroidEntryPoint
class AutofillPickerActivity : FragmentActivity() {

    @Inject
    lateinit var biometricAuthManager: BiometricAuthManager

    private val viewModel: AutofillPickerViewModel by viewModels()

    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        window.setHideOverlayWindows(true)
        window.decorView.filterTouchesWhenObscured = true

        setContent {
            var query by remember { mutableStateOf("") }
            val entries by viewModel.entries.collectAsStateWithLifecycle()
            val results = remember(query, entries) { AutofillEntrySearch.filter(entries, query) }

            ApplyObscuredTouchFilter()
            AutofillPickerScreen(
                query = query,
                onQueryChange = { query = it },
                results = results,
                onPick = ::confirmAndFill,
                onCancel = ::finish
            )
        }
    }

    /** 用户选中条目：按需解密 → 二次确认（可用生物识别时）→ 回传数据集 */
    private fun confirmAndFill(entryId: String) {
        if (completed) return
        lifecycleScope.launch {
            val credentials = viewModel.resolveCredentials(entryId)
            if (credentials == null) {
                AppLog.w(TAG, "选中条目凭据不可用，放弃本次填充")
                finish()
                return@launch
            }
            when (
                biometricAuthManager.canAuthenticate(
                    this@AutofillPickerActivity,
                    BiometricAuthManager.UNLOCK_AUTHENTICATORS
                )
            ) {
                BiometricStatus.AVAILABLE -> biometricAuthManager.authenticate(
                    activity = this@AutofillPickerActivity,
                    title = getString(R.string.autofill_confirm_title),
                    subtitle = getString(R.string.autofill_picker_confirm_sub),
                    authenticators = BiometricAuthManager.UNLOCK_AUTHENTICATORS
                ) { result ->
                    if (result is BiometricResult.Success) deliver(credentials) else finish()
                }

                else -> {
                    // 无可用认证器：用户在受保护窗口内的点选本身即一次显式确认
                    // （与 AutofillConfirmActivity 的退化策略一致）
                    deliver(credentials)
                }
            }
        }
    }

    private fun deliver(credentials: AutofillPickerViewModel.Credentials) {
        completed = true
        val usernameId = readAutofillId(EXTRA_USERNAME_ID)
        val passwordId = readAutofillId(EXTRA_PASSWORD_ID)

        val views = RemoteViews(packageName, R.layout.autofill_dataset_item).apply {
            setTextViewText(
                R.id.tv_username,
                credentials.username.ifBlank { getString(R.string.autofill_picker_title) }
            )
            setTextViewText(R.id.tv_subtitle, getString(R.string.autofill_picker_title))
        }

        val dataset = Dataset.Builder(
            Presentations.Builder()
                .setMenuPresentation(views)
                .setDialogPresentation(views)
                .build()
        ).apply {
            if (usernameId != null && credentials.username.isNotEmpty()) {
                setField(
                    usernameId,
                    Field.Builder().setValue(AutofillValue.forText(credentials.username)).build()
                )
            }
            if (passwordId != null && credentials.password.isNotEmpty()) {
                setField(
                    passwordId,
                    Field.Builder().setValue(AutofillValue.forText(credentials.password)).build()
                )
            }
        }.build()

        setResult(RESULT_OK, intentOfResult(dataset))
        finish()
    }

    private fun intentOfResult(dataset: Dataset) =
        android.content.Intent()
            .putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset)

    private fun readAutofillId(key: String): AutofillId? =
        intent.getParcelableExtra(key, AutofillId::class.java)

    companion object {
        private const val TAG = "AutofillPicker"

        /** 目标用户名框（可为 null：纯密码表单） */
        const val EXTRA_USERNAME_ID = "com.keepasskey.app.autofill.EXTRA_PICKER_USERNAME_ID"

        /** 目标密码框（可为 null） */
        const val EXTRA_PASSWORD_ID = "com.keepasskey.app.autofill.EXTRA_PICKER_PASSWORD_ID"
    }
}
