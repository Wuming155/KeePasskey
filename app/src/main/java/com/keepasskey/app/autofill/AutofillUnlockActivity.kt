package com.keepasskey.app.autofill

import android.content.Intent
import android.os.Bundle
import android.view.autofill.AutofillId
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.ApplyObscuredTouchFilter
import com.keepasskey.app.security.FlagSecureGuard
import com.keepasskey.app.ui.screens.unlock.UnlockScreen
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.model.KdbxEntry
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 传统自动填充 (AutofillService) 链式解锁落地 Activity。
 *
 * 官方自动填充认证流程：锁库时数据集的认证 PendingIntent 指向本 Activity；
 * 解锁成功后本页**按 PD-05 路由**（[AutofillUnlockRouter]，候选 C）链入选择器
 * [AutofillPickerActivity] 或确认页 [AutofillConfirmActivity]，并把落地页的认证结果
 * （`resultCode` + `data`，其中 `data` 携带 `AutofillManager.EXTRA_AUTHENTICATION_RESULT`
 * 的真实 [android.service.autofill.Dataset]）**原样转发**给框架，由框架写入目标表单。
 *
 * ISSUE-P2-86（真机实测结论，纠正本页原 KDoc 前提）：解锁成功后**框架不会**自动重新发起
 * `onFillRequest`——干净归因口径下（客户端全程不主动请求）6/6 为 false，框架侧会话事件恒 0 条。
 * 故「解锁成功即 setResult(RESULT_OK, 空 extras) 并等框架重发」这条路径**永远填不出凭据**。
 * 现今的交付路径是复用本应用内经真机验证可用的通道（选择器 / 确认页经
 * `EXTRA_AUTHENTICATION_RESULT` 回传真实 Dataset，两条链路同一载荷构造）。
 *
 * PD-05（2026-09-22 裁决，候选 C；ISSUE-P3-256）：唯一强匹配且调用方已绑定时改链确认页，
 * 否则维持链选择器。判据单点化在 [AutofillUnlockRouter]（纯函数）；本页**不自建 Dataset**
 * 回传（PD-05 对候选 B 的禁令延续）——归属展示、二次确认与 Dataset 构造由落地页承担。
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

    // PD-05（ISSUE-P3-256）：路由判据的输入通道——解锁后现算候选集合与绑定状态
    @Inject
    lateinit var vaultRepository: VaultRepository

    @Inject
    lateinit var autofillOriginResolver: AutofillOriginResolver

    @Inject
    lateinit var callerTrustStore: AutofillCallerTrustStore

    @Inject
    lateinit var autofillLastFilledStore: AutofillLastFilledStore

    private var completed = false

    /**
     * 路由已发起（PD-05：判据计算为挂起调用，异步窗口内防 `onUnlockSuccess` 双发；
     * 旧版同步链入无此窗口，双发会经同一 launcher 启动两次落地页）。
     */
    private var routeStarted = false

    /**
     * 落地页结果转发通道（ISSUE-P2-86；PD-05 起选择器与确认页**共用**本通道）。
     *
     * 注册时机受 AndroidX Activity 契约约束（须早于 STARTED），故作为属性初始化器在
     * 构造期注册（早于 `onCreate` 返回，合法且最先）。
     * 落地页已负责构造真实 Dataset、（选择器路径）写调用方首次绑定、做二次确认——本页只做
     * **原样转发**，不另写一份数据集构造。
     */
    private val pickerResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (completed) return@registerForActivityResult
        completed = true
        val resultCode = result.resultCode
        val data = result.data
        if (resultCode == RESULT_OK && data != null) {
            // 原样转发落地页结果：resultCode + data（data 携带 EXTRA_AUTHENTICATION_RESULT 数据集）
            setResult(resultCode, data)
        } else {
            // 用户在落地页中取消 / 放弃（或结果缺失）：如实回传取消，绝不谎报成功。
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
     * 解锁成功：按 PD-05（候选 C，ISSUE-P3-256）路由到选择器或确认页完成交付。
     *
     * 判据经纯函数 [AutofillUnlockRouter.route] 单点计算：[AutofillUnlockRouter.Route.Picker]
     * ⇒ 现状路径逐字不变（链 [AutofillPickerActivity]）；[AutofillUnlockRouter.Route.Confirm]
     * ⇒ 链 [AutofillConfirmActivity] 交付该条目（归属展示 + 单次生物识别 / 手动确认 +
     * Dataset 回传，全在确认页内）。两条链的结果都经 [pickerResultLauncher] **原样转发**给框架；
     * 本页不消费任何凭据明文，也不自建 Dataset。
     */
    private fun chainToPicker() {
        if (completed || routeStarted) return
        val usernameId = readAutofillId(AutofillPickerActivity.EXTRA_USERNAME_ID)
        val passwordId = readAutofillId(AutofillPickerActivity.EXTRA_PASSWORD_ID)
        // ISSUE-P3-298 ⑤：OTP 框 id 只透传、不参与路由判据（无 OTP 框不影响交付链路）
        val otpId = readAutofillId(AutofillPickerActivity.EXTRA_OTP_ID)
        if (usernameId == null && passwordId == null) {
            // 本次请求未识别到任何目标框（正常下发路径不会出现）：无可填充目标，如实取消
            completed = true
            AppLog.w(TAG, "解锁页未收到目标字段 id，无法链入选择器，按取消回传")
            setResult(RESULT_CANCELED, Intent().putExtras(Bundle.EMPTY))
            finish()
            return
        }
        routeStarted = true
        lifecycleScope.launch {
            val routing = resolveRouting(hasTargetField = usernameId != null || passwordId != null)
            when (val route = routing.route) {
                is AutofillUnlockRouter.Route.Confirm ->
                    launchConfirm(route.entry, usernameId, passwordId, otpId, routing.verifiedWebDomain)

                AutofillUnlockRouter.Route.Picker ->
                    launchPicker(usernameId, passwordId, otpId)
            }
        }
    }

    /** 路由结论 + 归属校验后的域（CONFIRM 分支下传确认页作会话授权上下文） */
    private data class Routing(
        val route: AutofillUnlockRouter.Route,
        val verifiedWebDomain: String?
    )

    /**
     * 现算路由判据的输入（候选集合 + 绑定状态 + 表单上下文）。
     *
     * 与服务端已解锁分支（`AutofillDatasetBuilders.resolveUnlockedCandidates`）同口径：
     * 证书摘要**只读一次**（归属解析与绑定校验共用，ISSUE-P3-170）、webDomain 经归属校验
     * 不可验证即按 null（fail-closed）、包名维度经 [AndroidPackageBindingPolicy] 首次绑定门。
     *
     * 任何不确定分支（异常）一律回落 PICKER——绝不静默直达确认页。
     */
    private suspend fun resolveRouting(hasTargetField: Boolean): Routing = try {
        val callingPackage = intent.getStringExtra(AutofillPickerActivity.EXTRA_CALLING_PACKAGE).orEmpty()
        val reportedDomain = intent.getStringExtra(AutofillPickerActivity.EXTRA_WEB_DOMAIN)
            ?.takeIf { it.isNotBlank() }
        val certDigests = autofillOriginResolver.callingAppCertDigests(callingPackage)
        val verifiedWebDomain = autofillOriginResolver.resolveUsableWebDomain(
            callingPackage,
            reportedDomain,
            certDigests
        )
        if (reportedDomain != null && verifiedWebDomain == null) {
            AppLog.w(TAG, "webDomain 归属无法验证，路由按无域处理（fail-closed）")
        }
        val packageDimensionAuthorized = AndroidPackageBindingPolicy.isPackageDimensionAuthorized(
            callingPackage = callingPackage,
            callingCertDigests = certDigests,
            isTrusted = { pkg, digests -> callerTrustStore.isTrusted(pkg, digests) }
        )
        val ranked = AutofillCandidateRanker.rank(
            entries = vaultRepository.getKdbxEntries(),
            callingPackage = callingPackage,
            webDomain = verifiedWebDomain,
            packageDimensionAuthorized = packageDimensionAuthorized,
            lastFilledEntryId = autofillLastFilledStore.lastFilledEntryId()
        )
        Routing(
            route = AutofillUnlockRouter.route(
                candidates = ranked,
                packageDimensionAuthorized = packageDimensionAuthorized,
                hasTargetField = hasTargetField
            ),
            verifiedWebDomain = verifiedWebDomain
        )
    } catch (e: CancellationException) {
        throw e
    } catch (t: Exception) {
        // PD-05：判据算不出 = 不确定分支，回落选择器（fail-closed）；日志不携带任何调用方标识
        AppLog.w(TAG, "解锁后路由判据计算失败，回落选择器：${t.javaClass.simpleName}")
        Routing(AutofillUnlockRouter.Route.Picker, null)
    }

    /**
     * PICKER 分支：现状路径逐字不变（ISSUE-P2-86 已真机验证可用的构造）。
     *
     * 复用**本应用内经真机验证可用**的交付路径——选择器（[AutofillPickerActivity]）
     * 选中条目后经 `AutofillManager.EXTRA_AUTHENTICATION_RESULT` 回传真实 Dataset，
     * 由 [pickerResultLauncher] 原样转发给框架。
     */
    private fun launchPicker(usernameId: AutofillId?, passwordId: AutofillId?, otpId: AutofillId?) {
        val pickerIntent = Intent(this, AutofillPickerActivity::class.java).apply {
            putExtra(AutofillPickerActivity.EXTRA_USERNAME_ID, usernameId)
            putExtra(AutofillPickerActivity.EXTRA_PASSWORD_ID, passwordId)
            // ISSUE-P3-298 ⑤：OTP 框 id 透传，选择器交付时把当前 TOTP 值填入该框
            putExtra(AutofillPickerActivity.EXTRA_OTP_ID, otpId)
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

    /**
     * CONFIRM 分支（PD-05 候选 C）：链入确认页交付唯一强匹配条目。
     *
     * 确认页入参与其既有（框架经认证 PendingIntent 拉起）路径**同一批 extras**——全部为本应用
     * 自有键，不依赖平台 fill-in，故无需改动确认页本身；确认页继续承担归属展示（P1-24）、
     * 单次生物识别 / 手动确认、Dataset 构造与回传（P2-88），本页只搬运上下文。
     * 结果经既有 [pickerResultLauncher] 原样转发：`RESULT_OK + data` ⇒ 转发；
     * 取消 / 缺失 ⇒ `RESULT_CANCELED + Bundle.EMPTY`。
     */
    private fun launchConfirm(
        entry: KdbxEntry,
        usernameId: AutofillId?,
        passwordId: AutofillId?,
        otpId: AutofillId?,
        verifiedWebDomain: String?
    ) {
        val confirmIntent = Intent(this, AutofillConfirmActivity::class.java).apply {
            putExtra(AutofillConfirmActivity.EXTRA_TARGET_USERNAME_ID, usernameId)
            putExtra(AutofillConfirmActivity.EXTRA_TARGET_PASSWORD_ID, passwordId)
            // ISSUE-P3-298 ⑤：OTP 框 id 透传，确认页回传时把当前 TOTP 值填入该框
            putExtra(AutofillConfirmActivity.EXTRA_TARGET_OTP_ID, otpId)
            putExtra(AutofillConfirmActivity.EXTRA_ENTRY_ID, entry.id.toHexString())
            putExtra(
                AutofillConfirmActivity.EXTRA_CREDENTIAL_TITLE,
                entry.userName.ifBlank { entry.title }
            )
            putExtra(
                AutofillConfirmActivity.EXTRA_GRANT_PACKAGE,
                intent.getStringExtra(AutofillPickerActivity.EXTRA_CALLING_PACKAGE)
            )
            // 与服务端数据集路径同口径：授权上下文用**归属校验后**的域（不可归属记空串）
            putExtra(AutofillConfirmActivity.EXTRA_GRANT_DOMAIN, verifiedWebDomain.orEmpty())
        }
        pickerResultLauncher.launch(confirmIntent)
    }

    private fun readAutofillId(key: String): AutofillId? =
        intent.getParcelableExtra(key, AutofillId::class.java)

    private companion object {
        private const val TAG = "AutofillUnlock"
    }
}
