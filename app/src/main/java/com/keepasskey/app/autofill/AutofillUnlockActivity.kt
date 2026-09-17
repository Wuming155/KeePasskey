package com.keepasskey.app.autofill

import android.content.Intent
import android.os.Bundle
import android.view.autofill.AutofillId
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.security.ApplyObscuredTouchFilter
import com.keepasskey.app.security.FlagSecureGuard
import com.keepasskey.app.ui.screens.unlock.UnlockScreen
import com.keepasskey.core.log.AppLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 传统自动填充 (AutofillService) 链式解锁落地 Activity。
 *
 * 官方自动填充认证流程：锁库时数据集的认证 PendingIntent 指向本 Activity；
 * 解锁成功后本页**链入选择器** [AutofillPickerActivity]，并把选择器的认证结果
 * （`resultCode` + `data`，其中 `data` 携带 `AutofillManager.EXTRA_AUTHENTICATION_RESULT`
 * 的真实 [android.service.autofill.Dataset]）**原样转发**给框架，由框架写入目标表单。
 *
 * ISSUE-P2-86（真机实测结论，纠正本页原 KDoc 前提）：解锁成功后**框架不会**自动重新发起
 * `onFillRequest`——干净归因口径下（客户端全程不主动请求）6/6 为 false，框架侧会话事件恒 0 条。
 * 故「解锁成功即 setResult(RESULT_OK, 空 extras) 并等框架重发」这条路径**永远填不出凭据**。
 * 现今的交付路径是复用本应用内唯一经真机验证可用的通道（选择器经
 * `EXTRA_AUTHENTICATION_RESULT` 回传真实 Dataset）。
 *
 *（此前指向 MainActivity 且不结束，框架收不到认证完成事件，导致解锁后候选永远不出。）
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

    /**
     * 选择器结果转发通道（ISSUE-P2-86）。
     *
     * 注册时机受 AndroidX Activity 契约约束（须早于 STARTED），故作为属性初始化器在
     * 构造期注册（早于 `onCreate` 返回，合法且最先）。
     * 选择器已负责构造真实 Dataset、写调用方首次绑定、做二次确认——本页只做**原样转发**，
     * 不另写一份数据集构造。
     */
    private val pickerResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (completed) return@registerForActivityResult
        completed = true
        val resultCode = result.resultCode
        val data = result.data
        if (resultCode == RESULT_OK && data != null) {
            // 原样转发选择器结果：resultCode + data（data 携带 EXTRA_AUTHENTICATION_RESULT 数据集）
            setResult(resultCode, data)
        } else {
            // 用户在选择器中取消 / 放弃（或结果缺失）：如实回传取消，绝不谎报成功。
            // 一律走双参重载——官方明文：Android 12 起认证结果的 extras 为 null 会崩溃。
            setResult(RESULT_CANCELED, Intent().putExtras(Bundle.EMPTY))
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 与 MainActivity 同源的 FLAG_SECURE 动态守卫（用户开关 ∨ 会话锁定态并集，首帧同步生效）
        flagSecureGuard.attach(this, lifecycleScope)
        // 官方反 overlay 攻击加固：屏蔽其它应用悬浮窗覆盖解锁窗口
        window.setHideOverlayWindows(true)

        lifecycleScope.launch {
            val settings = settingsRepository.getSettings().first()
            setContent {
                // ISSUE-P2-09：Compose 侧遮挡触摸过滤（点击劫持防护）
                ApplyObscuredTouchFilter()
                UnlockScreen(
                    currentTheme = settings.themeMode,
                    onThemeToggle = { /* 自动填充解锁场景不提供主题切换 */ },
                    onUnlockSuccess = { chainToPicker() },
                    onNavigateToDatabasePicker = { /* 自动填充解锁场景不提供库切换导航 */ }
                )
            }
        }
    }

    /**
     * 解锁成功：链入选择器完成交付（ISSUE-P2-86）。
     *
     * 复用**本应用内唯一经真机验证可用**的交付路径——选择器（[AutofillPickerActivity]）
     * 选中条目后经 `AutofillManager.EXTRA_AUTHENTICATION_RESULT` 回传真实 Dataset。
     * 本页不消费任何凭据明文，只搬运服务端随认证 Intent 下发的上下文（目标框 id / 调用方
     * 包名 / 表单自报域），并在 [pickerResultLauncher] 中把选择器结果原样转发给框架。
     */
    private fun chainToPicker() {
        if (completed) return
        val usernameId = readAutofillId(AutofillPickerActivity.EXTRA_USERNAME_ID)
        val passwordId = readAutofillId(AutofillPickerActivity.EXTRA_PASSWORD_ID)
        if (usernameId == null && passwordId == null) {
            // 本次请求未识别到任何目标框（正常下发路径不会出现）：无可填充目标，如实取消
            completed = true
            AppLog.w(TAG, "解锁页未收到目标字段 id，无法链入选择器，按取消回传")
            setResult(RESULT_CANCELED, Intent().putExtras(Bundle.EMPTY))
            finish()
            return
        }
        val pickerIntent = Intent(this, AutofillPickerActivity::class.java).apply {
            putExtra(AutofillPickerActivity.EXTRA_USERNAME_ID, usernameId)
            putExtra(AutofillPickerActivity.EXTRA_PASSWORD_ID, passwordId)
            putExtra(
                AutofillPickerActivity.EXTRA_CALLING_PACKAGE,
                intent.getStringExtra(AutofillPickerActivity.EXTRA_CALLING_PACKAGE)
            )
            putExtra(
                AutofillPickerActivity.EXTRA_WEB_DOMAIN,
                intent.getStringExtra(AutofillPickerActivity.EXTRA_WEB_DOMAIN)
            )
        }
        pickerResultLauncher.launch(pickerIntent)
    }

    private fun readAutofillId(key: String): AutofillId? =
        intent.getParcelableExtra(key, AutofillId::class.java)

    private companion object {
        private const val TAG = "AutofillUnlock"
    }
}
