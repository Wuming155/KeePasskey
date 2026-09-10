package com.keepasskey.app.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
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
import android.view.inputmethod.InlineSuggestionsRequest
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
                    label = node.label,
                    webDomain = node.webDomain,
                    packageName = callingPkg,
                    isVisible = node.isVisible,
                    importantForAutofill = node.importantForAutofill
                )
            )
        }

        val scanResult = AutofillFieldScanner.scan(
            scanNodes,
            respectImportantForAutofill = !settingsStore.isOverrideNoAutofillEnabled()
        )
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
        // ISSUE-P3-39：候选打分排序——严格匹配（DomainMatcher）通过的条目按
        // 「精确域名 > 精确包名 > 父域」打分并截断；匹配条件一字未放宽，
        // 未通过 isDomainMatch / isPackageMatch 的条目不会进入结果。
        val rankedEntries = AutofillCandidateRanker.rank(
            entries = allEntries,
            callingPackage = callingPkg,
            webDomain = webDomain,
            lastFilledEntryId = autofillLastFilledStore.lastFilledEntryId(),
            limit = MAX_DATASET_COUNT
        )

        // TASK-11 整改（审核报告 P2-24）：已解锁分支的每个数据集必须携带 setAuthentication
        // 二次确认——否则任何前台应用都可静默拉起候选并完成明文密码填充（用户无感知泄露）。
        // 用户点选数据集 → 拉起 AutofillConfirmActivity（生物识别/锁屏凭据或受保护窗口内
        // 手动确认）→ RESULT_OK 后框架才将该数据集的值真正写入目标表单。
        // ISSUE-P3-42：会话授权宽限（默认关闭）。本分支库已解锁；仅当开关开启且存在与
        // 「包名 + 域」严格匹配的有效授权（30 秒 TTL，由上次确认写入）时跳过重复二次确认。
        // 开关关闭时不查询授权存储，行为与既有「每次强制确认」完全一致。
        val sessionGrantEnabled = settingsStore.isAutofillSessionGrantEnabled()
        val grantActive = sessionGrantEnabled && AutofillSessionGrants.isGranted(
            AutofillGrantContext(callingPkg, webDomain)
        )
        val skipRepeatConfirmation = AutofillAuthenticationPolicy.skipRepeatConfirmation(
            sessionGrantEnabled = sessionGrantEnabled,
            vaultLocked = vaultRepository.isLocked(),
            grantActive = grantActive
        )

        val confirmIntent = Intent(this, AutofillConfirmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        for ((index, ranked) in rankedEntries.withIndex()) {
            val entry = ranked.entry
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

            if (!skipRepeatConfirmation) {
                // 每个数据集独立 requestCode，避免 PendingIntent 因 extras 相互覆盖
                // ISSUE-P3-03 (43b)：随确认入口下传条目标识，供 autofillCopyTotp
                // 在用户确认后按条目取 TOTP（不物化明文，仅传标识）
                // ISSUE-P3-42：下传授权上下文（包名 + 域），供确认成功后写入会话授权
                val confirmPendingIntent = PendingIntent.getActivity(
                    this,
                    REQUEST_CODE_CONFIRM_BASE + index,
                    confirmIntent.putExtra(
                        AutofillConfirmActivity.EXTRA_CREDENTIAL_TITLE,
                        username.ifBlank { entry.title }
                    ).putExtra(AutofillConfirmActivity.EXTRA_ENTRY_ID, entryIdHex)
                        .putExtra(AutofillConfirmActivity.EXTRA_GRANT_PACKAGE, callingPkg)
                        .putExtra(AutofillConfirmActivity.EXTRA_GRANT_DOMAIN, webDomain.orEmpty()),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                dsBuilder.setAuthentication(confirmPendingIntent.intentSender)
            }
            responseBuilder.addDataset(dsBuilder.build())
        }

        // ISSUE-P3-40：手动搜索兜底入口（自动匹配零候选/候选不含目标条目时使用）。
        // 以「认证数据集」形式挂入：值在用户于选择器中选中并确认后才经
        // AutofillManager.EXTRA_AUTHENTICATION_RESULT 回传——未确认前不携带任何明文。
        val pickerIntent = Intent(this, AutofillPickerActivity::class.java).apply {
            putExtra(AutofillPickerActivity.EXTRA_USERNAME_ID, usernameId)
            putExtra(AutofillPickerActivity.EXTRA_PASSWORD_ID, passwordId)
        }
        val pickerPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_PICKER,
            pickerIntent,
            // 框架需注入 fillIn extras，必须 FLAG_MUTABLE
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pickerViews = RemoteViews(packageName, R.layout.autofill_dataset_item).apply {
            setTextViewText(R.id.tv_username, getString(R.string.autofill_picker_entry_title))
            setTextViewText(R.id.tv_subtitle, getString(R.string.autofill_picker_entry_sub))
        }
        val pickerBuilder = Dataset.Builder(
            Presentations.Builder()
                .setMenuPresentation(pickerViews)
                .setDialogPresentation(pickerViews)
                .build()
        )
        if (usernameId != null) {
            // 与解锁引导数据集同语义：value 为 null 表示值在认证后（选择器回传时）才可用
            @Suppress("DEPRECATION")
            pickerBuilder.setValue(usernameId, null)
        }
        if (passwordId != null) {
            @Suppress("DEPRECATION")
            pickerBuilder.setValue(passwordId, null)
        }
        pickerBuilder.setAuthentication(pickerPendingIntent.intentSender)
        responseBuilder.addDataset(pickerBuilder.build())

        // 注册 SaveInfo 以便在用户提交时捕获新账密
        // ISSUE-P3-44：仅在用户开启「新密码保存提示」时注册——否则框架会提示保存、
        // 保存侧却又按开关跳过落库，形成「提示了但没保存」的矛盾语义。
        val requiredIds = listOfNotNull(usernameId, passwordId).toTypedArray()
        if (requiredIds.isNotEmpty() && settingsStore.isOfferSaveCredentialsEnabled()) {
            val saveFlags = SaveInfo.SAVE_DATA_TYPE_PASSWORD or SaveInfo.SAVE_DATA_TYPE_USERNAME
            val saveInfo = SaveInfo.Builder(saveFlags, requiredIds).build()
            responseBuilder.setSaveInfo(saveInfo)
        }

        callback.onSuccess(responseBuilder.build())
    }

    /**
     * 构建 IME 内联建议展示（官方 androidx.autofill.inline v1 内容模型 → Slice）。
     *
     * ISSUE-P3-03 (43b)：`inlineSuggestionsEnabled` 经 [AutofillInlinePresentationFactory]
     * 真实生效——开关关闭时恒返回 null，调用方 Dataset 不携带内联展示，
     * 自动回退为下拉/填充对话框呈现。构建细节已下沉至该工厂（单一职责）。
     */
    private fun buildInlinePresentation(
        inlineRequest: InlineSuggestionsRequest?,
        title: CharSequence,
        subtitle: CharSequence
    ): InlinePresentation? = inlinePresentationFactory.build(inlineRequest, title, subtitle)

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
                            label = node.label,
                            webDomain = node.webDomain,
                            packageName = callingPkg,
                            isVisible = node.isVisible,
                            importantForAutofill = node.importantForAutofill
                        )
                    )
                }

                val scanResult = AutofillFieldScanner.scan(
                    scanNodes,
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
                    label = node.hint,
                    webDomain = node.webDomain,
                    isVisible = node.visibility == android.view.View.VISIBLE,
                    importantForAutofill = isImportantForAutofill(node),
                    text = textVal
                )
            )
        }

        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) ?: continue
            traverseViewNode(child, onNode)
        }
    }

    /**
     * ISSUE-P3-43：页面是否允许对该节点自动填充。
     *
     * `IMPORTANT_FOR_AUTOFILL_NO` 与 `IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`
     * 视为页面显式禁止填充；其余（AUTO / YES / YES_EXCLUDE_DESCENDANTS）视为允许。
     * 是否被跳过取决于扫描参数 `respectImportantForAutofill`（由 `overrideNoAutofill` 开关决定）。
     */
    private fun isImportantForAutofill(node: AssistStructure.ViewNode): Boolean {
        val important = node.importantForAutofill
        return important != android.view.View.IMPORTANT_FOR_AUTOFILL_NO &&
                important != android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    private data class ParsedViewNode(
        val autofillId: AutofillId,
        val autofillHints: List<String>,
        val inputType: Int,
        val isFocused: Boolean,
        val htmlName: String?,
        /** ISSUE-P3-39：邻近 label / hint 文本（用于多语言兜底识别） */
        val label: String?,
        val webDomain: String?,
        /** ISSUE-P3-39：节点可见性（不可见账号框不参与，不可见密码框仍准入） */
        val isVisible: Boolean,
        /** ISSUE-P3-43：页面是否允许对该节点自动填充（`importantForAutofill`） */
        val importantForAutofill: Boolean,
        val text: String
    )

    companion object {
        private const val TAG = "KeePasskeyAutofill"
        private const val AUTOFILL_TIMEOUT_MS = 4_000L
        private const val MAX_DATASET_COUNT = 8
        private const val REQUEST_CODE_UNLOCK = 2001
        /** TASK-11：已解锁分支二次确认数据集的 PendingIntent requestCode 基址 */
        private const val REQUEST_CODE_CONFIRM_BASE = 2100

        /** ISSUE-P3-40：手动选择器入口数据集的 requestCode（与确认基址段无重叠） */
        private const val REQUEST_CODE_PICKER = 2200

        // ISSUE-P2-07/08：保存被拒的提示文案已迁入 strings.xml
        // （autofill_save_blocked / autofill_save_integrity_blocked），与填充侧同源资源化。
    }
}
