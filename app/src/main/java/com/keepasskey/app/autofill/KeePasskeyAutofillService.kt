package com.keepasskey.app.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.util.Log
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.keepasskey.app.MainActivity
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.passkey.DomainMatcher
import com.keepasskey.core.model.PasskeyData
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * 生产级传统系统自动填充服务 (AutofillService) 实现（对齐 P0-4 修复计划）。
 * 针对尚未适配 Credential Manager 的应用与 WebView 表单，提供严格安全的凭据填充与保存：
 * 1. 结构树纯库化抽象扫描与表单字段识别；
 * 2. 库锁定时响应单项解锁引导 Action；
 * 3. 库解锁时基于严格域名与包名匹配输出候选数据集；
 * 4. onSaveRequest 自动捕获新密码并安全写回。
 */
@AndroidEntryPoint
class KeePasskeyAutofillService : AutofillService() {

    @Inject
    lateinit var vaultRepository: VaultRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        if (cancellationSignal.isCanceled) return

        serviceScope.launch {
            try {
                withTimeoutOrNull(AUTOFILL_TIMEOUT_MS) {
                    processFillRequest(request, callback)
                } ?: run {
                    Log.w(TAG, "onFillRequest 超时 ($AUTOFILL_TIMEOUT_MS ms)")
                    callback.onSuccess(null)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "onFillRequest 发生异常", t)
                callback.onFailure(t.message)
            }
        }
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

        // 库已锁定：提供解锁 Action Dataset
        if (vaultRepository.isLocked()) {
            val views = RemoteViews(packageName, R.layout.autofill_dataset_item).apply {
                setTextViewText(R.id.tv_username, getString(R.string.cred_autofill_unlock_prompt))
                setTextViewText(R.id.tv_subtitle, getString(R.string.cred_autofill_locked_subtitle))
            }
            val unlockIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                REQUEST_CODE_UNLOCK,
                unlockIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val dsBuilder = Dataset.Builder(views)
            if (usernameId != null) dsBuilder.setValue(usernameId, null)
            if (passwordId != null) dsBuilder.setValue(passwordId, null)
            dsBuilder.setAuthentication(pendingIntent.intentSender)

            responseBuilder.addDataset(dsBuilder.build())
            callback.onSuccess(responseBuilder.build())
            return
        }

        // 库已解锁：查找匹配凭据
        val webDomain = scanResult.webDomain
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

        for (entry in matchedEntries.take(MAX_DATASET_COUNT)) {
            val username = entry.userName
            val password = entry.password?.readString().orEmpty()

            val views = RemoteViews(packageName, R.layout.autofill_dataset_item).apply {
                setTextViewText(R.id.tv_username, username.ifBlank { entry.title })
                setTextViewText(R.id.tv_subtitle, entry.title)
            }

            val dsBuilder = Dataset.Builder(views)
            if (usernameId != null && username.isNotEmpty()) {
                dsBuilder.setValue(usernameId, AutofillValue.forText(username))
            }
            if (passwordId != null && password.isNotEmpty()) {
                dsBuilder.setValue(passwordId, AutofillValue.forText(password))
            }
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

                if (password.isNotBlank()) {
                    val passwordChars = password.toCharArray()
                    vaultRepository.saveAutofillCredential(
                        packageName = callingPkg,
                        webDomain = scanResult.webDomain,
                        username = username,
                        passwordChars = passwordChars
                    )
                }

                callback.onSuccess()
            } catch (t: Throwable) {
                Log.e(TAG, "onSaveRequest 保存凭据失败", t)
                callback.onFailure(t.message)
            }
        }
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
    }
}
