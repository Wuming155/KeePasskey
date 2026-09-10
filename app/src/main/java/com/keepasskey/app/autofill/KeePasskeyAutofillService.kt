package com.keepasskey.app.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.Presentations
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import android.widget.inline.InlinePresentationSpec
import android.view.inputmethod.InlineSuggestionsRequest
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import com.keepasskey.app.MainActivity
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.passkey.DomainMatcher
import com.keepasskey.app.security.RuntimeIntegrityGate
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.model.PasskeyData
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

        val parsedNodes = mutableListOf<ParsedViewNode>()
        val scanNodes = mutableListOf<ScanNode>()

        traverseStructure(structure) { node ->
            val indexStr = parsedNodes.size.toString()
            parsedNodes.add(node)
            scanNodes.add(
                ScanNode(
                    id = indexStr,
                    autofillHints = node.autofillHints,
                    inputType = node.inputType,
                    isFocused = node.isFocused,
                    htmlName = node.htmlName,
                    webDomain = node.webDomain,
                    packageName = callingPkg
                )
            )
        }

        val scanResult = AutofillFieldScanner.scan(scanNodes)
        val usernameParsed = scanResult.usernameId?.toIntOrNull()?.let { parsedNodes.getOrNull(it) }
        val passwordParsed = scanResult.passwordId?.toIntOrNull()?.let { parsedNodes.getOrNull(it) }

        val usernameId: AutofillId? = usernameParsed?.autofillId
        val passwordId: AutofillId? = passwordParsed?.autofillId

        if (usernameId == null && passwordId == null) {
            callback.onSuccess(null)
            return
        }

        val responseBuilder = FillResponse.Builder()
        // 内联建议通道（IME）：请求侧携带 InlineSuggestionsRequest 且声明 supportsInlineSuggestions
        // 时才构建 InlinePresentation，否则 Dataset 自动回退为下拉/填充对话框展示
        val inlineRequest = request.inlineSuggestionsRequest

        // 库已锁定：提供解锁 Action Dataset
        if (vaultRepository.isLocked()) {
            val views = RemoteViews(packageName, R.layout.autofill_dataset_item).apply {
                setTextViewText(R.id.tv_username, getString(R.string.cred_autofill_unlock_prompt))
                setTextViewText(R.id.tv_subtitle, getString(R.string.cred_autofill_locked_subtitle))
            }
            // 认证入口指向专用 AutofillUnlockActivity：解锁成功即 setResult+finish，
            // 自动填充框架收到成功结果后自动重发 onFillRequest（此时库已解锁，直接出真实候选）
            val unlockIntent = Intent(this, AutofillUnlockActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                REQUEST_CODE_UNLOCK,
                unlockIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val dsBuilder = Dataset.Builder(
                Presentations.Builder()
                    .setMenuPresentation(views)
                    .setDialogPresentation(views)
                    .apply {
                        buildInlinePresentation(
                            inlineRequest,
                            getString(R.string.cred_autofill_unlock_prompt),
                            getString(R.string.cred_autofill_locked_subtitle)
                        )?.let { setInlinePresentation(it) }
                    }
                    .build()
            )
            if (usernameId != null) {
                // 认证数据集语义（官方 Dataset.Builder.setValue 文档）：value 传 null 表示
                // 该字段属于本数据集但值在解锁后才可用（新版 Field.Builder.setValue 已标注
                // 非空，null 值走旧版 setValue 重载）
                @Suppress("DEPRECATION")
                dsBuilder.setValue(usernameId, null)
            }
            if (passwordId != null) {
                @Suppress("DEPRECATION")
                dsBuilder.setValue(passwordId, null)
            }
            dsBuilder.setAuthentication(pendingIntent.intentSender)

            responseBuilder.addDataset(dsBuilder.build())
            callback.onSuccess(responseBuilder.build())
            return
        }

        // 库已解锁：查找匹配凭据
        // ISSUE-P2-07：webDomain 参与匹配前必须通过归属校验（受信浏览器白名单或 DAL 归属声明）；
        // 无法验证时按 null 处理（不下发该域候选），绝不静默放行
        val webDomain = autofillOriginResolver.resolveUsableWebDomain(callingPkg, scanResult.webDomain)
        if (scanResult.webDomain != null && webDomain == null) {
            AppLog.w(TAG, "webDomain 归属无法验证，已忽略该域候选（fail-closed）")
        }
        val allEntries = vaultRepository.getKdbxEntries()
        val matchedEntries = allEntries.filter { entry ->
            val passkey = PasskeyData.fromCustomFields(entry.customFields)
            val matchDomain = webDomain != null && (
                    (passkey != null && DomainMatcher.isDomainMatch(passkey.relyingPartyId, webDomain)) ||
                            (entry.url.isNotBlank() && DomainMatcher.isDomainMatch(entry.url, webDomain))
                    )
            // L1 整改：包名匹配仅走 DomainMatcher 严格点号边界（含 android:// scheme 剥离），
            // 移除 title/notes.contains 启发式，杜绝宽松包含导致的跨应用凭据泄露
            val matchPackage = callingPkg.isNotBlank() && entry.url.isNotBlank() &&
                    DomainMatcher.isPackageMatch(entry.url, callingPkg)
            matchDomain || matchPackage
        }

        // TASK-11 整改（审核报告 P2-24）：已解锁分支的每个数据集必须携带 setAuthentication
        // 二次确认——否则任何前台应用都可静默拉起候选并完成明文密码填充（用户无感知泄露）。
        // 用户点选数据集 → 拉起 AutofillConfirmActivity（生物识别/锁屏凭据或受保护窗口内
        // 手动确认）→ RESULT_OK 后框架才将该数据集的值真正写入目标表单。
        val confirmIntent = Intent(this, AutofillConfirmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        for ((index, entry) in matchedEntries.take(MAX_DATASET_COUNT).withIndex()) {
            // TASK-17：下发前解析 {REF:...} 字段引用（仅在取值消费点展开，投影层不物化）
            val entryIdHex = entry.id.toHexString()
            val username = vaultRepository.resolveFieldReferences(entryIdHex, entry.userName)
                ?: entry.userName
            val password = entry.password?.readString()
                ?.let { raw -> vaultRepository.resolveFieldReferences(entryIdHex, raw) }
                .orEmpty()

            val views = RemoteViews(packageName, R.layout.autofill_dataset_item).apply {
                setTextViewText(R.id.tv_username, username.ifBlank { entry.title })
                setTextViewText(R.id.tv_subtitle, entry.title)
            }

            val dsBuilder = Dataset.Builder(
                Presentations.Builder()
                    .setMenuPresentation(views)
                    .setDialogPresentation(views)
                    .apply {
                        buildInlinePresentation(
                            inlineRequest,
                            username.ifBlank { entry.title },
                            entry.title
                        )?.let { setInlinePresentation(it) }
                    }
                    .build()
            )
            // 每个数据集独立 requestCode，避免 PendingIntent 因 extras 相互覆盖
            val confirmPendingIntent = PendingIntent.getActivity(
                this,
                REQUEST_CODE_CONFIRM_BASE + index,
                confirmIntent.putExtra(
                    AutofillConfirmActivity.EXTRA_CREDENTIAL_TITLE,
                    username.ifBlank { entry.title }
                ),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            if (usernameId != null && username.isNotEmpty()) {
                dsBuilder.setField(
                    usernameId,
                    Field.Builder().setValue(AutofillValue.forText(username)).build()
                )
            }
            if (passwordId != null && password.isNotEmpty()) {
                dsBuilder.setField(
                    passwordId,
                    Field.Builder().setValue(AutofillValue.forText(password)).build()
                )
            }
            dsBuilder.setAuthentication(confirmPendingIntent.intentSender)
            responseBuilder.addDataset(dsBuilder.build())
        }

        // 注册 SaveInfo 以便在用户提交时捕获新账密
        val requiredIds = listOfNotNull(usernameId, passwordId).toTypedArray()
        if (requiredIds.isNotEmpty()) {
            val saveFlags = SaveInfo.SAVE_DATA_TYPE_PASSWORD or SaveInfo.SAVE_DATA_TYPE_USERNAME
            val saveInfo = SaveInfo.Builder(saveFlags, requiredIds).build()
            responseBuilder.setSaveInfo(saveInfo)
        }

        callback.onSuccess(responseBuilder.build())
    }

    /**
     * 构建 IME 内联建议展示（官方 androidx.autofill.inline v1 内容模型 → Slice）。
     * 请求侧未携带 [InlineSuggestionsRequest]、IME spec 未声明 v1 UI 模板或构建失败时
     * 返回 null，调用方 Dataset 不携带内联展示，自动回退为下拉/填充对话框呈现。
     */
    private fun buildInlinePresentation(
        inlineRequest: InlineSuggestionsRequest?,
        title: CharSequence,
        subtitle: CharSequence
    ): InlinePresentation? {
        if (inlineRequest == null) return null
        val spec: InlinePresentationSpec = inlineRequest.inlinePresentationSpecs.firstOrNull()
            ?: return null
        return try {
            // 官方裁决：仅当 IME spec 声明支持 v1 UI 模板时才构建 Slice
            if (!UiVersions.getVersions(spec.style).contains(UiVersions.INLINE_UI_VERSION_1)) {
                return null
            }
            // v1 内容构建器要求 attribution PendingIntent（系统内联卡片上打开提供方应用的入口）
            val attribution = PendingIntent.getActivity(
                this,
                REQUEST_CODE_INLINE_ATTRIBUTION,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            val content = InlineSuggestionUi.newContentBuilder(attribution)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setStartIcon(Icon.createWithResource(this, R.drawable.ic_launcher))
                .build()
            InlinePresentation(content.slice, spec, false)
        } catch (t: Throwable) {
            AppLog.w(TAG, "构建 InlinePresentation 失败，回退下拉展示", t)
            null
        }
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

                val parsedNodes = mutableListOf<ParsedViewNode>()
                val scanNodes = mutableListOf<ScanNode>()

                traverseStructure(structure) { node ->
                    val indexStr = parsedNodes.size.toString()
                    parsedNodes.add(node)
                    scanNodes.add(
                        ScanNode(
                            id = indexStr,
                            autofillHints = node.autofillHints,
                            inputType = node.inputType,
                            isFocused = node.isFocused,
                            htmlName = node.htmlName,
                            webDomain = node.webDomain,
                            packageName = callingPkg
                        )
                    )
                }

                val scanResult = AutofillFieldScanner.scan(scanNodes)
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

    private fun traverseStructure(
        structure: AssistStructure,
        onNode: (ParsedViewNode) -> Unit
    ) {
        val windowNodes = (0 until structure.windowNodeCount).map { structure.getWindowNodeAt(it) }
        for (window in windowNodes) {
            val root = window.rootViewNode ?: continue
            traverseViewNode(root, onNode)
        }
    }

    private fun traverseViewNode(
        node: AssistStructure.ViewNode,
        onNode: (ParsedViewNode) -> Unit
    ) {
        val autofillId = node.autofillId
        if (autofillId != null) {
            val hints = node.autofillHints?.toList().orEmpty()
            val textVal = node.autofillValue?.textValue?.toString() ?: node.text?.toString().orEmpty()
            val htmlName = node.idEntry ?: node.hint
            onNode(
                ParsedViewNode(
                    autofillId = autofillId,
                    autofillHints = hints,
                    inputType = node.inputType,
                    isFocused = node.isFocused,
                    htmlName = htmlName,
                    webDomain = node.webDomain,
                    text = textVal
                )
            )
        }

        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) ?: continue
            traverseViewNode(child, onNode)
        }
    }

    private data class ParsedViewNode(
        val autofillId: AutofillId,
        val autofillHints: List<String>,
        val inputType: Int,
        val isFocused: Boolean,
        val htmlName: String?,
        val webDomain: String?,
        val text: String
    )

    companion object {
        private const val TAG = "KeePasskeyAutofill"
        private const val AUTOFILL_TIMEOUT_MS = 4_000L
        private const val MAX_DATASET_COUNT = 8
        private const val REQUEST_CODE_UNLOCK = 2001
        private const val REQUEST_CODE_INLINE_ATTRIBUTION = 2002
        /** TASK-11：已解锁分支二次确认数据集的 PendingIntent requestCode 基址 */
        private const val REQUEST_CODE_CONFIRM_BASE = 2100

        // ISSUE-P2-07/08：保存被拒的提示文案已迁入 strings.xml
        // （autofill_save_blocked / autofill_save_integrity_blocked），与填充侧同源资源化。
    }
}
