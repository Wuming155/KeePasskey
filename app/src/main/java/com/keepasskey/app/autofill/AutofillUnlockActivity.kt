package com.keepasskey.app.autofill

import android.content.Intent
import android.os.Bundle
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.inputmethod.InlineSuggestionsRequest
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.coroutines.safeLaunch
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.ApplyObscuredTouchFilter
import com.keepasskey.app.security.FlagSecureGuard
import com.keepasskey.app.ui.localeFor
import com.keepasskey.app.ui.localizedConfigurationOf
import com.keepasskey.app.ui.localizedContextOf
import com.keepasskey.app.ui.screens.unlock.UnlockScreen
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.security.ProtectedStringReclamation
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * 传统自动填充 (AutofillService) 链式解锁落地 Activity。
 *
 * 官方自动填充认证流程：锁库时数据集的认证 PendingIntent 指向本 Activity；解锁成功后本页按与
 * 「库已解锁」分支同源的候选解析，把**全部命中条目**各装配一个仍带 `setAuthentication` 的候选
 * Dataset，并在偏好开启时追加「搜索全部条目」入口，组装成完整 [android.service.autofill.FillResponse]，经
 * [AutofillManager.EXTRA_AUTHENTICATION_RESULT] 回传给框架，由框架替换当前响应并重新展示候选 UI。
 *
 * ISSUE-P2-86（真机实测结论）：解锁成功后**框架不会**自动重新发起 `onFillRequest`，
 * 故「解锁成功即 setResult(RESULT_OK, 空 extras) 并等框架重发」这条路径永远填不出凭据。
 *
 * 旧版按候选数分路（唯一强匹配且调用方已绑定 ⇒ 链 [AutofillConfirmActivity]，其余链
 * [AutofillPickerActivity]）已退役：多账号应用里只有一条账号绑定了应用时，解锁后只匹配到这一条，
 * 确认页内没有换账号入口，生物识别路径更是系统弹窗，用户会在未察觉的情况下填入另一账号。
 * 现锁定态与已解锁态呈现同一候选列表口径，唯一候选与多候选同构；每个候选仍须经
 * [AutofillConfirmActivity] 二次确认后才由该页构造真实 Dataset 交付，本页不物化任何凭据明文。
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

    @Inject
    lateinit var vaultRepository: VaultRepository

    @Inject
    lateinit var settingsStore: ExtendedSettingsStore

    /** 与已解锁分支同源的候选解析（域归属校验 / P2-46 门 / 签名摘要快照 / 关联记忆）。 */
    @Inject
    lateinit var candidateResolver: AutofillCandidateResolver

    @Inject
    lateinit var unlockResponseBuilder: AutofillUnlockResponseBuilder

    private var completed = false

    /** 候选解析为挂起调用，异步窗口内防 `onUnlockSuccess` 双发。 */
    private var deliveryStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 与 MainActivity 同源的 FLAG_SECURE 动态守卫（用户开关 ∨ 会话锁定态并集，首帧同步生效）
        flagSecureGuard.attach(this, lifecycleScope)
        // 官方反 overlay 攻击加固：屏蔽其它应用悬浮窗覆盖解锁窗口
        window.setHideOverlayWindows(true)

        lifecycleScope.safeLaunch("AutofillUnlockActivity") {
            val settings = settingsRepository.getSettings().first()
            setContent {
                // ISSUE-P2-09：Compose 侧遮挡触摸过滤（点击劫持防护）
                ApplyObscuredTouchFilter()
                // ISSUE-P3-365 AC①：与主外壳同源的强制语言覆盖（复用 AppShellLocalization）——
                // 本页不经 MainActivity，不覆盖则回落系统语言，与主界面语言分叉。
                val targetLocale = remember(settings.appLanguage) { localeFor(settings.appLanguage) }
                val localizedConfiguration = remember(targetLocale) {
                    localizedConfigurationOf(resources.configuration, targetLocale)
                }
                val localizedContext = remember(targetLocale) {
                    localizedContextOf(this@AutofillUnlockActivity, resources.configuration, targetLocale)
                }
                CompositionLocalProvider(
                    LocalContext provides localizedContext,
                    LocalConfiguration provides localizedConfiguration
                ) {
                    UnlockScreen(
                        onUnlockSuccess = { deliverCandidateResponse() },
                        onNavigateToDatabasePicker = { /* 自动填充解锁场景不提供库切换导航 */ }
                    )
                }
            }
        }
    }

    /**
     * 解锁成功：回传完整候选响应。
     *
     * 候选解析与装配整段处于一条敏感值读租约内（候选是活的 `KdbxEntry`，展示面读口与会话整树
     * 替换的就地擦除并发）；锁定态 / 通道开关在挂起前后各复核一次，与确认页交付边界同口径。
     * 任何不确定分支（异常、零数据集、目标框缺失）一律按 `RESULT_CANCELED` 收尾，绝不谎报成功。
     */
    private fun deliverCandidateResponse() {
        if (completed || deliveryStarted) return
        val usernameId = readAutofillId(AutofillPickerActivity.EXTRA_USERNAME_ID)
        val passwordId = readAutofillId(AutofillPickerActivity.EXTRA_PASSWORD_ID)
        val otpId = readAutofillId(AutofillPickerActivity.EXTRA_OTP_ID)
        if (usernameId == null && passwordId == null) {
            cancelDelivery("解锁页未收到目标字段 id，无法回传候选响应")
            return
        }
        deliveryStarted = true
        lifecycleScope.safeLaunch("AutofillUnlockActivity") {
            val lease = ProtectedStringReclamation.acquireReadLease()
            try {
                if (!canDeliver()) {
                    cancelDelivery("会话已锁定或自动填充通道已关闭，丢弃本次解锁候选响应")
                    return@safeLaunch
                }
                val callingPackage = intent.getStringExtra(AutofillPickerActivity.EXTRA_CALLING_PACKAGE).orEmpty()
                val reportedDomain = intent.getStringExtra(AutofillPickerActivity.EXTRA_WEB_DOMAIN)
                    ?.takeIf { it.isNotBlank() }
                val candidates = candidateResolver.resolve(callingPackage, reportedDomain)
                val request = UnlockResponseRequest(
                    callingPackage = callingPackage,
                    reportedWebDomain = reportedDomain,
                    verifiedWebDomain = candidates.webDomain,
                    usernameId = usernameId,
                    passwordId = passwordId,
                    otpId = otpId,
                    blockedIds = intent.getParcelableArrayListExtra(
                        EXTRA_BLOCKED_IDS,
                        AutofillId::class.java
                    ).orEmpty(),
                    inlineRequest = intent.getParcelableExtra(
                        EXTRA_INLINE_SUGGESTIONS_REQUEST,
                        InlineSuggestionsRequest::class.java
                    ),
                    candidates = candidates.ranked
                )
                val response = unlockResponseBuilder.build(this@AutofillUnlockActivity, request)
                if (!canDeliver() || response == null) {
                    cancelDelivery("解锁后无可交付候选或会话已失效")
                    return@safeLaunch
                }
                AppLog.i(
                    TAG,
                    "解锁后回传候选响应：候选数=${candidates.ranked.size}, " +
                        "手动选择入口=${settingsStore.isAutofillManualPickerEnabled()}"
                )
                finishWithResult(
                    RESULT_OK,
                    Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Exception) {
                cancelDelivery("解锁后候选响应组装异常：${t.javaClass.simpleName}")
            } finally {
                lease.close()
            }
        }
    }

    /** 交付边界复核：锁定态与自动填充通道开关均不放宽（与确认页 `canDeliverAuthResult` 同口径）。 */
    private fun canDeliver(): Boolean =
        AutofillAuthenticationPolicy.canDeliverAuthResult(vaultRepository.isLocked()) &&
            settingsStore.isAutofillServiceEnabled()

    private fun cancelDelivery(reason: String) {
        AppLog.w(TAG, "$reason，按取消回传")
        finishWithResult(RESULT_CANCELED, Intent().putExtras(Bundle.EMPTY))
    }

    /** 双参重载是强制项：Android 12 起认证结果 Intent 的 extras 为 null 会崩溃。 */
    private fun finishWithResult(resultCode: Int, data: Intent) {
        if (completed) return
        setResult(resultCode, data)
        completed = true
        finish()
        // FillResponse 内的 ParceledListSlice 只可 parcel 一次；finish() 已把本次结果同步发出，
        // 随即清空 Activity 上残留的 result data——系统或测试若再次 finish() 本页，不会重 parcel
        // 同一响应而抛 IllegalStateException（不影响已交付的 RESULT_OK + FillResponse）。
        setResult(RESULT_CANCELED, Intent().putExtras(Bundle.EMPTY))
    }

    private fun readAutofillId(key: String): AutofillId? =
        intent.getParcelableExtra(key, AutofillId::class.java)

    companion object {
        /** 本次被字段级屏蔽的框 id（解锁后的认证结果响应以 `setIgnoredIds` 继承）。 */
        const val EXTRA_BLOCKED_IDS = "com.keepasskey.app.autofill.EXTRA_UNLOCK_BLOCKED_IDS"

        /** 本次内联建议请求（解锁后候选 Dataset 与解锁引导同口径携带内联展示）。 */
        const val EXTRA_INLINE_SUGGESTIONS_REQUEST =
            "com.keepasskey.app.autofill.EXTRA_UNLOCK_INLINE_SUGGESTIONS_REQUEST"

        private const val TAG = "AutofillUnlock"
    }
}
