package com.keepasskey.app.autofill

import android.app.assist.AssistStructure
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
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
 *    不产出于解锁引导、数据集与 SaveInfo（语义上等价于未注册本填充服务）。
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

        // ISSUE-P2-08：完整性风险态禁用自动填充；TASK-44：黑名单命中 fail-closed——
        // 两者均不下发数据集（含解锁引导与 SaveInfo），等价于「该应用从未注册过本填充服务」，
        // 不降级已有填充语义也不返回错误。
        val enforcement = runtimeIntegrityGate.awaitEnforcement()
        when (AutofillAccessPolicy.rejectReason(enforcement, callingPkg, autofillBlocklistStore::isBlocked)) {
            AutofillRejection.INTEGRITY_RISK -> {
                // ISSUE-P1-10：日志不得携带调用包名等敏感标识
                AppLog.i(TAG, "设备完整性风险，拒绝下发自动填充数据集")
                callback.onSuccess(null)
                return
            }
            AutofillRejection.BLOCKLISTED -> {
                AppLog.i(TAG, "调用应用已列入自动填充黑名单，拒绝下发数据集")
                callback.onSuccess(null)
                return
            }
            null -> Unit
        }

        val scanned = AutofillStructureScanner.scan(structure, callingPkg)
        val parsedNodes = scanned.viewNodes

        val scanResult = AutofillFieldScanner.scan(
            scanned.scanNodes,
            respectImportantForAutofill = !settingsStore.isOverrideNoAutofillEnabled()
        )
        val usernameParsed = scanResult.usernameId?.toIntOrNull()?.let { parsedNodes.getOrNull(it) }
        val passwordParsed = scanResult.passwordId?.toIntOrNull()?.let { parsedNodes.getOrNull(it) }

        val scannedUsernameId: AutofillId? = usernameParsed?.autofillId
        val scannedPasswordId: AutofillId? = passwordParsed?.autofillId

        if (scannedUsernameId == null && scannedPasswordId == null) {
            callback.onSuccess(null)
            return
        }

        // ISSUE-P3-43 ②：字段签名级屏蔽——判定先于「库锁定引导」与任何数据集构建，
        // 因此被屏蔽的框连解锁引导都不会收到（更保守）。签名的域取表单**自报**的
        // scanResult.webDomain（用户屏蔽的是他当时看到的那个表单），
        // 与后续用于凭据匹配的「归属校验后 webDomain」是两个独立用途，不可互替。
        val fieldDecision = AutofillFieldBlockPolicy.decide(
            hasUsernameField = scannedUsernameId != null,
            hasPasswordField = scannedPasswordId != null
        ) { role ->
            autofillFieldBlocklistStore.isBlocked(callingPkg, scanResult.webDomain, role)
        }
        if (fieldDecision.blocksEntireForm) {
            AppLog.i(TAG, "本表单字段已被用户逐字段屏蔽，拒绝下发数据集")
            callback.onSuccess(null)
            return
        }
        val usernameId: AutofillId? = scannedUsernameId.takeIf { fieldDecision.allowUsername }
        val passwordId: AutofillId? = scannedPasswordId.takeIf { fieldDecision.allowPassword }

        val responseBuilder = FillResponse.Builder()
        // 内联建议通道（IME）：请求侧携带 InlineSuggestionsRequest 且声明 supportsInlineSuggestions
        // 时才构建 InlinePresentation，否则 Dataset 自动回退为下拉/填充对话框展示
        val inlineRequest = request.inlineSuggestionsRequest

        // 库已锁定：提供解锁 Action Dataset
        buildLockedUnlockDataset(usernameId, passwordId, inlineRequest)?.let { lockedResponse ->
            callback.onSuccess(lockedResponse)
            return
        }

        // 库已解锁：查找匹配凭据并追加候选数据集
        appendUnlockedDatasets(
            responseBuilder = responseBuilder,
            callingPkg = callingPkg,
            scanResult = scanResult,
            usernameId = usernameId,
            passwordId = passwordId,
            inlineRequest = inlineRequest
        )

        // ISSUE-P3-40：手动搜索兜底入口
        buildPickerDataset(
            responseBuilder = responseBuilder,
            callingPkg = callingPkg,
            scanResult = scanResult,
            usernameId = usernameId,
            passwordId = passwordId
        )

        // 注册 SaveInfo 以便在用户提交时捕获新账密
        applySaveInfoIfNeeded(
            responseBuilder = responseBuilder,
            usernameId = usernameId,
            passwordId = passwordId
        )

        callback.onSuccess(responseBuilder.build())
    }

    override fun onSaveRequest(
        request: SaveRequest,
        callback: SaveCallback
    ) {
        val structure: AssistStructure = request.fillContexts.lastOrNull()?.structure ?: run {
            callback.onSuccess()
            return
        }

        serviceScope.launch {
            try {
                val callingPkg = structure.activityComponent.packageName

                // ISSUE-P2-08 / TASK-44：保存侧同样前置于完整性闸门与黑名单检查——
                // 命中即拒绝落库并向系统回调非敏感提示，绝不让被屏蔽/风险环境写入任何凭据
                val enforcement = runtimeIntegrityGate.awaitEnforcement()
                when (AutofillAccessPolicy.rejectReason(enforcement, callingPkg, autofillBlocklistStore::isBlocked)) {
                    AutofillRejection.INTEGRITY_RISK -> {
                        AppLog.i(TAG, "设备完整性风险，拒绝保存自动填充凭据")
                        callback.onFailure(getString(R.string.autofill_save_integrity_blocked))
                        return@launch
                    }
                    AutofillRejection.BLOCKLISTED -> {
                        AppLog.i(TAG, "调用应用已列入自动填充黑名单，拒绝保存凭据")
                        callback.onFailure(getString(R.string.autofill_save_blocked))
                        return@launch
                    }
                    null -> Unit
                }

                // ISSUE-P3-44：接线既有「新密码保存提示」开关（此前无填充侧消费方，属假开关）。
                // 关闭时不落库、不打扰用户——向框架回调成功即表示「本次无需保存」。
                if (!settingsStore.isOfferSaveCredentialsEnabled()) {
                    AppLog.i(TAG, "已关闭新密码保存，跳过本次自动填充保存")
                    callback.onSuccess()
                    return@launch
                }

                // ISSUE-P3-43 ③：保存侧独立黑名单（与填充黑名单分离）。
                // 命中即**静默**跳过：不落库、不向用户报错（onSuccess 表示「本次无需保存」），
                // 且不影响该应用的填充能力。包名非法时 store 侧 fail-closed 同样跳过。
                if (autofillSaveBlocklistStore.isSaveBlocked(callingPkg)) {
                    AppLog.i(TAG, "调用应用已列入保存黑名单，静默跳过本次保存")
                    callback.onSuccess()
                    return@launch
                }

                val scanned = AutofillStructureScanner.scan(structure, callingPkg)
                val parsedNodes = scanned.viewNodes

                val scanResult = AutofillFieldScanner.scan(
                    scanned.scanNodes,
                    respectImportantForAutofill = !settingsStore.isOverrideNoAutofillEnabled()
                )
                val username = scanResult.usernameId?.toIntOrNull()?.let { parsedNodes.getOrNull(it)?.text }.orEmpty()
                val password = scanResult.passwordId?.toIntOrNull()?.let { parsedNodes.getOrNull(it)?.text }.orEmpty()
                // ISSUE-P2-07：保存前同样做 webDomain 归属校验，避免把不可归属的域写进条目
                val usableWebDomain =
                    autofillOriginResolver.resolveUsableWebDomain(callingPkg, scanResult.webDomain)

                if (password.isNotBlank()) {
                    // Wave 12 敏感数据卫生：调用方持有的密码 CharArray 在任何结果路径下用毕立即清零
                    // （注：来源 node.text 的 String 由系统 AssistStructure 提供，应用侧无法擦除，
                    //  已尽量缩短其存活期——本回调结束即失去引用，绝不进入日志/StateFlow/成员变量）
                    val passwordChars = password.toCharArray()
                    try {
                        val result = vaultRepository.saveAutofillCredential(
                            packageName = callingPkg,
                            webDomain = usableWebDomain,
                            username = username,
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
                } else {
                    callback.onSuccess()
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
    }

    override fun onDestroy() {
        // 无状态服务契约（官方）：系统解绑即取消全部在途协程，杜绝解绑后空转与迟到回调
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        internal const val TAG = "KeePasskeyAutofill"
        private const val AUTOFILL_TIMEOUT_MS = 4_000L
        internal const val MAX_DATASET_COUNT = 8
        internal const val REQUEST_CODE_UNLOCK = 2001
        /** TASK-11：已解锁分支二次确认数据集的 PendingIntent requestCode 基址 */
        internal const val REQUEST_CODE_CONFIRM_BASE = 2100

        /** ISSUE-P3-40：手动选择器入口数据集的 requestCode（与确认基址段无重叠） */
        internal const val REQUEST_CODE_PICKER = 2200

        // ISSUE-P2-07/08：保存被拒的提示文案已迁入 strings.xml
        // （autofill_save_blocked / autofill_save_integrity_blocked），与填充侧同源资源化。
    }
}
