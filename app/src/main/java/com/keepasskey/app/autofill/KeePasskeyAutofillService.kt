package com.keepasskey.app.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.view.inputmethod.InlineSuggestionsRequest
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.passkey.PasswordSaveActivity
import com.keepasskey.app.security.RuntimeIntegrityGate
import com.keepasskey.core.log.AppLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * 生产级传统系统自动填充服务 (AutofillService) 实现（对齐 P0-4 修复计划）。
 * 针对尚未适配 Credential Manager 的应用与 WebView 表单，提供严格安全的凭据填充与保存：
 * 1. 结构树纯库化抽象扫描与表单字段识别；
 * 2. 库锁定时响应单项解锁引导 Action；
 * 3. 库解锁时基于严格域名与包名匹配输出候选数据集；
 * 4. onSaveRequest 自动捕获新密码并安全写回（与 CM 保存通道共用 saveAutofillCredential，
 *    写入层内容级幂等查重，防双通道重复落库）；
 * 5. IME 内联建议（InlinePresentation）：请求侧声明 supportsInlineSuggestions 且 IME 支持
 *    v1 模板时，Dataset 携带官方 androidx.autofill.inline v1 内容 Slice 以内联形式呈现；
 * 6. 生命周期契约（官方：调用无状态、服务仅请求期间绑定）：cancellationSignal 取消即级联
 *    取消协程，onDestroy 取消全部在途任务，杜绝解绑后空转与迟到回调；
 * 7. TASK-44 黑名单：命中黑名单的调用包名在填充前即 fail-closed 返回空响应，
 *    不产出于解锁引导、数据集与 SaveInfo（语义上等价于未注册本填充服务）；
 * 8. ISSUE-P2-226 自我排除：调用包名即本应用包名时同样不下发任何数据集，框架保存通道静默跳过
 *    （本应用内凭据写入走自有写盘链路，不经自动填充）。
 */
@AndroidEntryPoint
class KeePasskeyAutofillService : AutofillService() {

    @Inject
    lateinit var vaultRepository: VaultRepository

    // TASK-44：自动填充黑名单（命中即不下发任何数据集，fail-closed）
    @Inject
    lateinit var autofillBlocklistStore: com.keepasskey.app.data.repository.AutofillBlocklistStore

    // ISSUE-P2-08：运行完整性风险闸门（风险态禁用自动填充，fail-closed）
    @Inject
    lateinit var runtimeIntegrityGate: RuntimeIntegrityGate

    // ISSUE-P2-07：webDomain 归属解析（受信浏览器白名单 / DAL 校验，无法验证即 fail-closed）
    @Inject
    lateinit var autofillOriginResolver: AutofillOriginResolver

    // ISSUE-P3-03 (43b)：IME 内联建议展示构建器（受 inlineSuggestionsEnabled 偏好闸门约束）
    @Inject
    lateinit var inlinePresentationFactory: AutofillInlinePresentationFactory

    // ISSUE-P3-39：「上次填充」记忆（仅用于候选置顶排序，不改变放行判定）
    @Inject
    lateinit var autofillLastFilledStore: AutofillLastFilledStore

    // ISSUE-P3-42：会话授权宽限开关（默认关闭；关闭时根本不查询授权存储）
    @Inject
    lateinit var settingsStore: com.keepasskey.app.data.repository.ExtendedSettingsStore

    // ISSUE-P3-43 ②：字段签名级屏蔽（「包名 + 域 + 角色」粒度，用户在选择器内写入）
    @Inject
    lateinit var autofillFieldBlocklistStore: AutofillFieldBlocklistStore

    // ISSUE-P3-43 ③：保存侧独立黑名单（命中即静默跳过保存，不影响填充）
    @Inject
    lateinit var autofillSaveBlocklistStore: AutofillSaveBlocklistStore

    // ISSUE-P2-46：调用方「包名 + 签名摘要」首次绑定信任存储——`android://` 包名维度的放行依据。
    // 同一实例亦由确认页（写入）与选择器（写入）复用，保证「写入面 = 判定面」是同一份记录。
    @Inject
    lateinit var callerTrustStore: AutofillCallerTrustStore

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        if (cancellationSignal.isCanceled) return

        val job = serviceScope.launch {
            try {
                withTimeoutOrNull(AUTOFILL_TIMEOUT_MS) {
                    processFillRequest(request, callback)
                } ?: run {
                    AppLog.w(TAG, "onFillRequest 超时 ($AUTOFILL_TIMEOUT_MS ms)")
                    callback.onSuccess(null)
                }
            } catch (c: CancellationException) {
                // 系统侧已取消请求：静默退出，不再回调
                throw c
            } catch (t: Throwable) {
                // ISSUE-P1-10：对外回调一律使用预定义用户文案，禁止透传 t.message
                AppLog.e(TAG, "onFillRequest 发生异常", t)
                callback.onFailure(getString(R.string.autofill_fill_failed))
            }
        }
        // 生命周期接线：系统取消请求（界面切换/新聚焦事件）即级联取消协程，不空转至超时预算
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    private suspend fun processFillRequest(
        request: FillRequest,
        callback: FillCallback
    ) {
        val structure: AssistStructure = request.fillContexts.lastOrNull()?.structure ?: run {
            callback.onSuccess(null)
            return
        }
        val callingPkg = structure.activityComponent.packageName
        // ISSUE-P2-226 / ISSUE-P2-228：调用方即本应用、或用户已关闭本通道 ⇒ 一律不下发。
        // 置于完整性闸门**之前**短路：这两种情形无论风险态如何都拒绝，免去自家口令框每次聚焦
        // 都触发一轮 `awaitEnforcement()` 重扫（maps / 路径扫描）。
        if (!settingsStore.isAutofillServiceEnabled() ||
            AutofillAccessPolicy.isSelfApp(callingPkg, packageName)
        ) {
            AppLog.d(TAG, "自动填充通道已关闭或调用方即本应用，跳过下发")
            callback.onSuccess(null)
            return
        }
        if (rejectsDatasetDelivery(callingPkg)) {
            callback.onSuccess(null)
            return
        }

        val targets = resolveTargetFields(structure, callingPkg) ?: run {
            callback.onSuccess(null)
            return
        }
        val usernameId = targets.usernameId
        val passwordId = targets.passwordId
        val otpId = targets.otpId
        val scanResult = targets.scanResult

        val responseBuilder = FillResponse.Builder()
        // 内联建议通道（IME）：请求侧携带 InlineSuggestionsRequest 且声明 supportsInlineSuggestions
        // 时才构建 InlinePresentation，否则 Dataset 自动回退为下拉/填充对话框展示
        val inlineRequest = request.inlineSuggestionsRequest

        // 库已锁定：提供解锁 Action Dataset
        // ISSUE-P2-86：解锁引导数据集需携带链入选择器所需的上下文（目标框 id + 调用方包名 +
        // 表单自报域），故按选择器同一口径传入 callingPkg / scanResult.webDomain
        buildLockedUnlockDataset(
            usernameId = usernameId,
            passwordId = passwordId,
            otpId = otpId,
            callingPkg = callingPkg,
            webDomain = scanResult.webDomain,
            inlineRequest = inlineRequest
        )?.let { lockedResponse ->
            // ISSUE-P2-73 AC③：设备侧核对「库锁定 ⇒ 下发认证引导数据集」的调试留痕。
            // 用 `AppLog.d`（仅 debug 构建输出，release 静默且被 R8 剥离）——不改变任何语义，
            // 只为真机链路提供可核对的时序锚点；日志不含包名/条目等敏感标识。
            AppLog.d(TAG, "onFillRequest 下发解锁引导数据集（密码库锁定，认证由解锁 Activity 承接）")
            callback.onSuccess(lockedResponse)
            return
        }

        deliverUnlockedResponse(
            responseBuilder = responseBuilder,
            callback = callback,
            callingPkg = callingPkg,
            scanResult = scanResult,
            usernameId = usernameId,
            passwordId = passwordId,
            otpId = otpId,
            inlineRequest = inlineRequest
        )
    }

    /**
     * ISSUE-P2-08：完整性风险态禁用自动填充；ISSUE-P2-226：调用方即本应用自身；
     * TASK-44：黑名单命中 fail-closed——三者均不下发数据集（含解锁引导与 SaveInfo），
     * 等价于「该应用从未注册过本填充服务」，不降级已有填充语义也不返回错误。
     *
     * @return true = 本次请求不予下发（调用方据此 `onSuccess(null)`）
     */
    private suspend fun rejectsDatasetDelivery(callingPkg: String): Boolean =
        when (
            AutofillAccessPolicy.rejectReason(
                runtimeIntegrityGate.awaitEnforcement(),
                callingPkg,
                packageName,
                autofillBlocklistStore::isBlocked
            )
        ) {
            AutofillRejection.INTEGRITY_RISK -> {
                // ISSUE-P1-10：日志不得携带调用包名等敏感标识
                AppLog.i(TAG, "设备完整性风险，拒绝下发自动填充数据集")
                true
            }
            AutofillRejection.SELF_APP -> {
                AppLog.i(TAG, "调用方即本应用，拒绝下发自动填充数据集")
                true
            }
            AutofillRejection.BLOCKLISTED -> {
                AppLog.i(TAG, "调用应用已列入自动填充黑名单，拒绝下发数据集")
                true
            }
            null -> false
        }

    /** 库已解锁：候选数据集 + 手动搜索兜底入口 + SaveInfo，一次装配合并后回给框架 */
    private suspend fun deliverUnlockedResponse(
        responseBuilder: FillResponse.Builder,
        callback: FillCallback,
        callingPkg: String,
        scanResult: ScanResult,
        usernameId: AutofillId?,
        passwordId: AutofillId?,
        otpId: AutofillId?,
        inlineRequest: InlineSuggestionsRequest?
    ) {
        appendUnlockedDatasets(
            responseBuilder = responseBuilder,
            callingPkg = callingPkg,
            scanResult = scanResult,
            usernameId = usernameId,
            passwordId = passwordId,
            otpId = otpId,
            inlineRequest = inlineRequest
        )
        // ISSUE-P3-40：手动搜索兜底入口
        buildPickerDataset(
            responseBuilder = responseBuilder,
            callingPkg = callingPkg,
            scanResult = scanResult,
            usernameId = usernameId,
            passwordId = passwordId,
            otpId = otpId
        )
        // 注册 SaveInfo 以便在用户提交时捕获新账密
        applySaveInfoIfNeeded(
            responseBuilder = responseBuilder,
            usernameId = usernameId,
            passwordId = passwordId
        )
        // ISSUE-P2-73 AC③：设备侧核对「认证完成后框架**重发** onFillRequest」的调试留痕
        // （与库锁定分支的留痕配对即为该结论的直接证据）；仅 debug 构建输出。
        AppLog.d(TAG, "onFillRequest 下发已解锁数据集（候选/选择器/保存信息）")
        callback.onSuccess(responseBuilder.build())
    }

    override fun onSaveRequest(
        request: SaveRequest,
        callback: SaveCallback
    ) {
        val contexts = request.fillContexts
        if (contexts.isEmpty()) {
            callback.onSuccess()
            return
        }
        // ISSUE-P2-228：用户关闭「系统自动填充服务」通道 ⇒ 一律不接收保存
        // （onSuccess 表示「本次无需保存」，与「用户关闭保存提示」同一收敛语义；不报错）
        if (!settingsStore.isAutofillServiceEnabled()) {
            callback.onSuccess()
            return
        }

        serviceScope.launch {
            // ISSUE-P3-122（IPC-10）：保存请求整体加**超时预算**。
            // 平台对 onSaveRequest **不提供** CancellationSignal，故无上限即「系统保存 UI 永久等待」：
            // `runtimeIntegrityGate.awaitEnforcement()` 在首次扫描未完成时可等待一整个扫描周期，
            // 库侧 Save 亦可能长时间不返回。超时按「本次无需保存」收尾（onSuccess）：给系统明确答复，
            // 不落库、不报错——与「用户关闭保存提示」同一收敛语义。
            // 注：处理体抽为 [handleSaveRequest] 而非就地包一层——`withTimeoutOrNull` **不是** inline，
            // 内部不允许 `return@launch`（非局部返回），就地包裹无法编译。
            val handled = withTimeoutOrNull(SAVE_REQUEST_TIMEOUT_MS) {
                handleSaveRequest(contexts, callback)
            }
            if (handled == null) {
                AppLog.w(TAG, "保存请求超出 ${SAVE_REQUEST_TIMEOUT_MS}ms 预算，按『本次无需保存』收尾")
                callback.onSuccess()
            }
        }
    }

    /**
     * `onSaveRequest` 的实际处理体（**ISSUE-P3-122 IPC-10** 自该回调抽取）。
     *
     * 抽取的唯一动因是让调用方能在其外层施加超时预算：`withTimeoutOrNull` 不是 inline 函数，
     * 处理体若留在 lambda 内，其中的 `return@launch` 属非局部返回、**无法编译**。
     *
     * ISSUE-P1-224：支持倒序多 Context 回溯，防止跳转新界面导致密码丢失；
     * 库锁定时以 IntentSender 唤起保存解锁界面，库解锁时后台直接写盘。
     */
    private suspend fun handleSaveRequest(
        contexts: List<android.service.autofill.FillContext>,
        callback: SaveCallback
    ) {
        try {
            val callingPkg = contexts.lastOrNull()?.structure?.activityComponent?.packageName.orEmpty()
            if (callingPkg.isBlank() || AutofillAccessPolicy.isSelfApp(callingPkg, packageName)) {
                callback.onSuccess()
                return
            }

            // ISSUE-P2-08 / TASK-44：保存侧同样前置于完整性闸门与黑名单检查——
            // 命中即拒绝落库并向系统回调非敏感提示，绝不让被屏蔽/风险环境写入任何凭据
            val enforcement = runtimeIntegrityGate.awaitEnforcement()
            when (AutofillAccessPolicy.rejectReason(enforcement, callingPkg, packageName, autofillBlocklistStore::isBlocked)) {
                AutofillRejection.INTEGRITY_RISK -> {
                    AppLog.i(TAG, "设备完整性风险，拒绝保存自动填充凭据")
                    callback.onFailure(getString(R.string.autofill_save_integrity_blocked))
                    return
                }
                AutofillRejection.SELF_APP -> {
                    AppLog.i(TAG, "调用方即本应用，跳过框架保存通道")
                    callback.onSuccess()
                    return
                }
                AutofillRejection.BLOCKLISTED -> {
                    AppLog.i(TAG, "调用应用已列入自动填充黑名单，拒绝保存凭据")
                    callback.onFailure(getString(R.string.autofill_save_blocked))
                    return
                }
                null -> Unit
            }

            // ISSUE-P3-44：接线既有「新密码保存提示」开关（此前无填充侧消费方，属假开关）。
            // 关闭时不落库、不打扰用户——向框架回调成功即表示「本次无需保存」。
            if (!settingsStore.isOfferSaveCredentialsEnabled()) {
                AppLog.i(TAG, "已关闭新密码保存，跳过本次自动填充保存")
                callback.onSuccess()
                return
            }

            // ISSUE-P3-43 ③：保存侧独立黑名单（与填充黑名单分离）。
            // 命中即**静默**跳过：不落库、不向用户报错（onSuccess 表示「本次无需保存」），
            // 且不影响该应用的填充能力。包名非法时 store 侧 fail-closed 同样跳过。
            if (autofillSaveBlocklistStore.isSaveBlocked(callingPkg)) {
                AppLog.i(TAG, "调用应用已列入保存黑名单，静默跳过本次保存")
                callback.onSuccess()
                return
            }

            val extracted = AutofillSaveExtractor.extract(
                contexts = contexts,
                callingPkg = callingPkg,
                isOverrideNoAutofillEnabled = settingsStore.isOverrideNoAutofillEnabled(),
                originResolver = autofillOriginResolver
            )
            if (extracted == null || extracted.password.isBlank()) {
                AppLog.i(TAG, "未在表单上下文中提取到有效密码，本次无需保存")
                callback.onSuccess()
                return
            }

            // ISSUE-P1-224：若密码库当前处于锁定状态，无法在后台直接加密落盘；
            // 回传 IntentSender 唤起 PasswordSaveActivity 在受保护窗口中解锁并承接保存
            if (vaultRepository.isLocked()) {
                dispatchLockedSave(callingPkg, extracted, callback)
                return
            }

            val passwordChars = extracted.password.toCharArray()
            try {
                val result = vaultRepository.saveAutofillCredential(
                    packageName = callingPkg,
                    webDomain = extracted.webDomain,
                    username = extracted.username,
                    passwordChars = passwordChars
                )
                when (result) {
                    is com.keepasskey.core.result.KdbxResult.Success -> {
                        callback.onSuccess()
                    }
                    is com.keepasskey.core.result.KdbxResult.Failure -> {
                        // ISSUE-P1-10：对外回调一律使用预定义用户文案，禁止透传异常 message
                        AppLog.e(TAG, "onSaveRequest 保存凭据失败", result.error)
                        callback.onFailure(getString(R.string.autofill_save_failed))
                    }
                }
            } finally {
                passwordChars.fill('0')
            }
        } catch (c: CancellationException) {
            // 服务解绑/协程取消：静默退出，不再回调
            throw c
        } catch (t: Throwable) {
            AppLog.e(TAG, "onSaveRequest 保存凭据失败", t)
            // ISSUE-P1-10：对外回调一律使用预定义用户文案，禁止透传 t.message
            callback.onFailure(getString(R.string.autofill_save_failed))
        }
    }

    /**
     * 库锁定时唤起 PasswordSaveActivity 解锁并承接保存（ISSUE-P1-224）。
     */
    private fun dispatchLockedSave(
        callingPkg: String,
        extracted: ExtractedSaveCredentials,
        callback: SaveCallback
    ) {
        AppLog.i(TAG, "密码库当前处于锁定状态，向系统返回解锁保存 IntentSender")
        val saveIntent = Intent(this, PasswordSaveActivity::class.java).apply {
            putExtra(PasswordSaveActivity.EXTRA_PACKAGE_NAME, callingPkg)
            putExtra(PasswordSaveActivity.EXTRA_WEB_DOMAIN, extracted.webDomain)
            putExtra(PasswordSaveActivity.EXTRA_USERNAME, extracted.username)
            putExtra(PasswordSaveActivity.EXTRA_PASSWORD, extracted.password)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_SAVE,
            saveIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        callback.onSuccess(pendingIntent.intentSender)
    }

    override fun onDestroy() {
        // 无状态服务契约（官方）：系统解绑即取消全部在途协程，杜绝解绑后空转与迟到回调
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        internal const val TAG = "KeePasskeyAutofill"
        private const val AUTOFILL_TIMEOUT_MS = 4_000L

        /**
         * 保存请求（`onSaveRequest`）的整体超时预算（**ISSUE-P3-122 IPC-10**）。
         *
         * 比填充预算（[AUTOFILL_TIMEOUT_MS]）宽 1 秒：保存路径含一次库写入与落盘，
         * 但同样**必须有上限**——平台不提供 `CancellationSignal`，无上限即「系统保存 UI 永久等待」。
         * `internal` 以便接线守卫断言该常量确实被用于包裹。
         */
        internal const val SAVE_REQUEST_TIMEOUT_MS = 5_000L
        internal const val MAX_DATASET_COUNT = 8
        internal const val REQUEST_CODE_UNLOCK = 2001
        /** TASK-11：已解锁分支二次确认数据集的 PendingIntent requestCode 基址 */
        internal const val REQUEST_CODE_CONFIRM_BASE = 2100

        /** ISSUE-P3-40：手动选择器入口数据集的 requestCode（与确认基址段无重叠） */
        internal const val REQUEST_CODE_PICKER = 2200

        /** ISSUE-P1-224：密码库锁定时保存凭据拉起解锁保存 Activity 的 requestCode */
        internal const val REQUEST_CODE_SAVE = 2300

        // ISSUE-P2-07/08：保存被拒的提示文案已迁入 strings.xml
        // （autofill_save_blocked / autofill_save_integrity_blocked），与填充侧同源资源化。
    }
}
