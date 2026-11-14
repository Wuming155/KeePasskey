package com.keepasskey.app.autofill

import android.content.Context
import android.content.Intent
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.service.autofill.Presentations
import android.view.autofill.AutofillId
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.RemoteViews
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.core.log.AppLog
import javax.inject.Inject

/** 解锁落地页组装候选响应所需的本次请求上下文（不含任何凭据明文）。 */
data class UnlockResponseRequest(
    val callingPackage: String,
    val reportedWebDomain: String?,
    val verifiedWebDomain: String?,
    val usernameId: AutofillId?,
    val passwordId: AutofillId?,
    val otpId: AutofillId?,
    val blockedIds: List<AutofillId>,
    val inlineRequest: InlineSuggestionsRequest?,
    val candidates: List<AutofillCandidateRanker.Ranked>
)

/**
 * 库锁定态解锁成功后的认证结果 [FillResponse] 装配。
 *
 * 官方契约：认证结果既可回传 `Dataset`，也可回传完整 `FillResponse`；后者由框架替换当前响应并
 * 重新展示候选 UI（KeePassDX `AutofillHelper.buildResponse` 同型）。本类据此把**全部命中条目**
 * 各装配一个 Dataset：值均为 null 占位（明文只在用户于二次确认页显式确认后才由该页构造），
 * 每个 Dataset 仍挂 `setAuthentication` 指向 [AutofillConfirmActivity]；另按已解锁分支同一偏好
 * 开关追加「搜索全部条目」入口（与已解锁分支共用同一装配函数；开关关闭时两条链路都不挂）。
 *
 * 安全口径：候选解析、字段级屏蔽、P2-46 门与二次确认均由上游承担，本类不放宽任何一项；
 * 候选在「解析 → 下发」窗口内被并发擦除时如实跳过，绝不把空用户名 / 空标题伪装成可填候选。
 * 调用方须跨「解析 → 装配」整段持有敏感值读租约。
 */
class AutofillUnlockResponseBuilder @Inject constructor(
    private val settingsStore: ExtendedSettingsStore,
    private val inlinePresentationFactory: AutofillInlinePresentationFactory
) {
    /**
     * @return 无可交付数据集 / 组装失败时返回 null，调用方按 `RESULT_CANCELED` 处置（不谎报成功）。
     */
    fun build(context: Context, request: UnlockResponseRequest): FillResponse? {
        val plan = AutofillUnlockResponsePlan.of(
            candidateCount = request.candidates.size,
            manualPickerEnabled = settingsStore.isAutofillManualPickerEnabled(),
            hasTargetField = request.usernameId != null || request.passwordId != null
        )
        if (!plan.canDeliver) return null

        val responseBuilder = FillResponse.Builder()
        // ISSUE-P2-384 AC②：与已解锁分支同口径，被字段级屏蔽的框写入 setIgnoredIds，
        // 使系统侧缓存响应不再把它们当作可填充目标。
        if (request.blockedIds.isNotEmpty()) {
            responseBuilder.setIgnoredIds(*request.blockedIds.toTypedArray())
        }
        var datasetCount = 0
        for (ranked in request.candidates) {
            val dataset = buildCandidateDataset(context, request, ranked) ?: continue
            responseBuilder.addDataset(dataset)
            datasetCount++
        }
        if (plan.includeManualPicker) {
            appendManualPickerDataset(
                context = context,
                responseBuilder = responseBuilder,
                callingPkg = request.callingPackage,
                reportedDomain = request.reportedWebDomain,
                usernameId = request.usernameId,
                passwordId = request.passwordId,
                otpId = request.otpId
            )
            datasetCount++
        }
        // 候选全被并发擦除且兜底入口关闭时无数据集可交付，如实取消（不构造空响应）。
        if (datasetCount == 0) return null
        applyAutofillSaveInfo(
            responseBuilder = responseBuilder,
            usernameId = request.usernameId,
            passwordId = request.passwordId,
            offerSaveEnabled = settingsStore.isOfferSaveCredentialsEnabled()
        )
        return try {
            responseBuilder.build()
        } catch (t: Exception) {
            AppLog.w(TAG, "解锁后候选响应组装失败，按取消回传：${t.javaClass.simpleName}")
            null
        }
    }

    /** 单候选 → 占位数据集：展示面 + 空值占位 + 二次确认认证入口。 */
    private fun buildCandidateDataset(
        context: Context,
        request: UnlockResponseRequest,
        ranked: AutofillCandidateRanker.Ranked
    ): Dataset? {
        val entry = ranked.entry
        // ISSUE-P2-534：候选在「检索 → 下发」窗口内被会话整树替换就地清零时跳过，
        // 不把已擦除字段读成空串展示（展示面读口会降级为空串）。
        if (entry.hasClearedFields()) {
            AppLog.w(TAG, "解锁候选条目在检索与下发之间已被擦除，本轮跳过该候选")
            return null
        }
        val entryIdHex = entry.id.toHexString()
        val title = entry.displayTitle()
        val displayName = entry.displayUserName().ifBlank { title }
        val views = RemoteViews(context.packageName, R.layout.autofill_dataset_item).apply {
            setTextViewText(R.id.tv_username, displayName)
            setTextViewText(R.id.tv_subtitle, title)
        }
        val presentations = Presentations.Builder()
            .setMenuPresentation(views)
            .setDialogPresentation(views)
            .apply {
                inlinePresentationFactory.build(request.inlineRequest, displayName, title)
                    ?.let { setInlinePresentation(it) }
            }
            .build()
        val dsBuilder = Dataset.Builder(presentations)
        // 值经认证后由确认页构造；此处只声明「本数据集属于这些框」，与解锁引导 / 手动选择
        // 入口同语义（value 为 null 表示值在认证后才可用）。
        if (request.usernameId != null) {
            @Suppress("DEPRECATION")
            dsBuilder.setValue(request.usernameId, null)
        }
        if (request.passwordId != null) {
            @Suppress("DEPRECATION")
            dsBuilder.setValue(request.passwordId, null)
        }
        if (request.otpId != null) {
            @Suppress("DEPRECATION")
            dsBuilder.setValue(request.otpId, null)
        }
        // 与已解锁分支共用同一份确认 Intent 构造：每条候选独立 requestCode + 随机身份，
        // 且下传条目标识、归属校验后域与调用方包名（归属展示 / 会话授权 / 关联记忆同源）。
        val confirmContext = UnlockedDatasetContext(
            callingPkg = request.callingPackage,
            webDomain = request.verifiedWebDomain,
            usernameId = request.usernameId,
            passwordId = request.passwordId,
            inlineRequest = request.inlineRequest,
            otpId = request.otpId,
            confirmIntent = Intent(context, AutofillConfirmActivity::class.java).apply {
                putExtra(AutofillConfirmActivity.EXTRA_TARGET_USERNAME_ID, request.usernameId)
                putExtra(AutofillConfirmActivity.EXTRA_TARGET_PASSWORD_ID, request.passwordId)
                putExtra(AutofillConfirmActivity.EXTRA_TARGET_OTP_ID, request.otpId)
                putExtra(EXTRA_TARGET_STRUCTURED_ROLES, ArrayList<String>())
                putExtra(EXTRA_TARGET_STRUCTURED_IDS, ArrayList<AutofillId>())
            },
            // 解锁后首次交付恒需二次确认，不消费会话授权宽限。
            skipRepeatConfirmation = false
        )
        context.attachConfirmationAuth(dsBuilder, entryIdHex, displayName, confirmContext)
        return dsBuilder.build()
    }

    private companion object {
        private const val TAG = "AutofillUnlock"
    }
}
