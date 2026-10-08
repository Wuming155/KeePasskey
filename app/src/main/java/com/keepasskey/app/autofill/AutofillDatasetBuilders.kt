package com.keepasskey.app.autofill

import android.app.PendingIntent
import android.content.Intent
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.Presentations
import android.service.autofill.SaveInfo
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.RemoteViews
import com.keepasskey.app.R
import java.util.concurrent.atomic.AtomicInteger
import com.keepasskey.app.autofill.KeePasskeyAutofillService.Companion.TAG
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.database.fieldref.FieldReferenceEngine.RefField

/**
 * 自动填充数据集构建（自 [KeePasskeyAutofillService] 原样抽出为同包扩展函数）。
 *
 * 仅做结构搬运，不改变任何判定逻辑、PendingIntent flags/requestCode 或 data 顺序；
 * 依赖的注入字段保持 public，故同包扩展函数可直接访问。
 */

/**
 * 构建 IME 内联建议展示（官方 androidx.autofill.inline v1 内容模型 → Slice）。
 *
 * ISSUE-P3-03 (43b)：`inlineSuggestionsEnabled` 经 [AutofillInlinePresentationFactory]
 * 真实生效——开关关闭时恒返回 null，调用方 Dataset 不携带内联展示，
 * 自动回退为下拉/填充对话框呈现。构建细节已下沉至该工厂（单一职责）。
 */
internal fun KeePasskeyAutofillService.buildInlinePresentation(
    inlineRequest: InlineSuggestionsRequest?,
    title: CharSequence,
    subtitle: CharSequence
): InlinePresentation? = inlinePresentationFactory.build(inlineRequest, title, subtitle)

/**
 * ISSUE-P3-122（IPC-01）：认证 `PendingIntent` 的 requestCode **进程级单调分配器**（自动填充通道）。
 *
 * ## 缺陷形态
 *
 * 整改前三条认证入口各自使用**常量** requestCode（解锁 100 / 确认 100+index / 选择器 200），
 * 且一律带 `FLAG_UPDATE_CURRENT`。两次不同的 `onFillRequest` 一旦落到同一 requestCode，
 * 后一次会**就地更新**前一次 PendingIntent 的 extras——用户点中的是旧候选，实际拉起的却是
 * 新上下文的 Activity（TOTP 错配、以及 `ISSUE-P3-42` 会话授权开启时的 30 秒授权串扰）。
 *
 * ## 修复口径
 *
 * 每次分配取新值，从根上消除「不同响应共用 requestCode」这一前提；
 * `FLAG_UPDATE_CURRENT` 随之不再有覆盖对象。计数器为进程级单调，无需随响应重置
 * （重置反而会重新引入复用）。
 *
 * ## 与 CM 通道的关系（ISSUE-P2-199 更正）
 *
 * 本注释原称「与 CM 通道的 `CredentialResponseAssembler.RequestCodeAllocator` 同构」，
 * 而当时 CM 侧是**每响应复位**的局部计数器——该自称**失实**。ISSUE-P2-199 已把 CM 通道
 * 同形缺陷一并整改：[CredentialPendingIntents.nextRequestCode] 亦为进程级 `AtomicInteger`
 * 单调分配（唯一差异是分配基线，用于日志辨认）。两通道自此**真正同构**，
 * 该对应关系由 `CredentialRequestCodeWiringTest` 的源码断言常态锁定（防再次漂移）。
 */
private val authRequestCodeAllocator = AtomicInteger(AUTH_REQUEST_CODE_BASE)

private fun nextAuthRequestCode(): Int = authRequestCodeAllocator.getAndIncrement()

/** 分配基线（刻意避开既有常量区间，便于日志与抓包中辨认） */
private const val AUTH_REQUEST_CODE_BASE = 100

/**
 * 库已锁定时构建解锁引导数据集。
 *
 * ISSUE-P2-86：认证入口指向 [AutofillUnlockActivity]，该页解锁成功后经 **PD-05 路由**
 * （[AutofillUnlockRouter]，候选 C：唯一强匹配且调用方已绑定 ⇒ 链 [AutofillConfirmActivity]，
 * 否则维持链 [AutofillPickerActivity]）完成交付，并原样转发落地页的认证结果——两条链路都经
 * `AutofillManager.EXTRA_AUTHENTICATION_RESULT` 回传真实 [Dataset]，均为本应用内
 * 经真机验证可用的交付路径。因此基 Intent 必须按选择器所需的上下文补齐 extras
 * （路由判据的输入亦取自同一批 extras）。
 *
 * @param callingPkg 调用方包名（下发解锁页，用于路由归属展示与绑定状态判定）
 * @param webDomain 表单**自报**域（下发解锁页，解锁后经归属校验再参与匹配；可为 null）
 * @return 库锁定时返回仅含解锁引导数据集的响应；库已解锁时返回 null（由调用方继续走已解锁分支）
 */
internal fun KeePasskeyAutofillService.buildLockedUnlockDataset(
    usernameId: AutofillId?,
    passwordId: AutofillId?,
    callingPkg: String,
    webDomain: String?,
    inlineRequest: InlineSuggestionsRequest?,
    otpId: AutofillId? = null
): FillResponse? {
    // 库已锁定：提供解锁 Action Dataset
    if (!vaultRepository.isLocked()) return null

    val views = RemoteViews(packageName, R.layout.autofill_dataset_item).apply {
        setTextViewText(R.id.tv_username, getString(R.string.cred_autofill_unlock_prompt))
        setTextViewText(R.id.tv_subtitle, getString(R.string.cred_autofill_locked_subtitle))
    }
    // ISSUE-P2-86：与已验证可用的选择器基 Intent（[buildPickerDataset]）**严格同构**——
    // 不带任何 activity flag（旧构造的 NEW_TASK|CLEAR_TOP 与之相异），并携带选择器所需的
    // 全部上下文，使解锁页解锁后能直接链入选择器完成交付。
    val unlockIntent = Intent(this, AutofillUnlockActivity::class.java).apply {
        putExtra(AutofillPickerActivity.EXTRA_USERNAME_ID, usernameId)
        putExtra(AutofillPickerActivity.EXTRA_PASSWORD_ID, passwordId)
        // ISSUE-P3-298 ⑤：OTP 框 id 随链路转发（解锁后选择器 / 确认页才能把 TOTP 值填入该框）
        putExtra(AutofillPickerActivity.EXTRA_OTP_ID, otpId)
        putExtra(AutofillPickerActivity.EXTRA_CALLING_PACKAGE, callingPkg)
        putExtra(AutofillPickerActivity.EXTRA_WEB_DOMAIN, webDomain.orEmpty())
    }
    val pendingIntent = PendingIntent.getActivity(
        this,
        nextAuthRequestCode(),
        unlockIntent,
        // ISSUE-P2-86：框架需向该 PendingIntent 注入 fillIn extras 并消费其回传的认证结果，
        // 必须 FLAG_MUTABLE——选择器路径（真机实测可填充）即此构造；旧构造的 FLAG_IMMUTABLE
        // 真机实测恒不填充（对照事实见 docs/records/自动填充认证链路真机实测记录.md）
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
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
    if (otpId != null) {
        // ISSUE-P3-298 ⑤：与解锁引导数据集同语义——值在解锁后回传的数据集中才可用
        @Suppress("DEPRECATION")
        dsBuilder.setValue(otpId, null)
    }
    dsBuilder.setAuthentication(pendingIntent.intentSender)

    return FillResponse.Builder().apply { addDataset(dsBuilder.build()) }.build()
}

/**
 * 库已解锁时按候选打分追加候选数据集；结构化目标存在时同批追加结构化数据集（ISSUE-P3-375）。
 */
internal suspend fun KeePasskeyAutofillService.appendUnlockedDatasets(
    responseBuilder: FillResponse.Builder,
    callingPkg: String,
    scanResult: ScanResult,
    usernameId: AutofillId?,
    passwordId: AutofillId?,
    inlineRequest: InlineSuggestionsRequest?,
    otpId: AutofillId? = null,
    structuredTargetIds: Map<StructuredFieldRole, AutofillId> = emptyMap()
) {
    val candidates = resolveUnlockedCandidates(callingPkg, scanResult)
    val context = UnlockedDatasetContext(
        callingPkg = callingPkg,
        webDomain = candidates.webDomain,
        usernameId = usernameId,
        passwordId = passwordId,
        inlineRequest = inlineRequest,
        otpId = otpId,
        // ISSUE-P2-88：官方契约（`Dataset.Builder#setAuthentication` 原文）对认证数据集有**两条**强制要求：
        // ① 认证 PendingIntent 不得不可变（平台要注入认证参数）⇒ `FLAG_MUTABLE`；
        // ② 认证结束后必须经 `AutofillManager.EXTRA_AUTHENTICATION_RESULT` 回传「fully populated
        //    dataset」，框架才会「replace the authenticated dataset and immediately fill in」。
        // 本路径原先两条都违反（IMMUTABLE + 确认页只回传成功而不回传数据集）⇒ 真机恒不写入。
        // 现与选择器入口（[buildPickerDataset]，真机实测可用）**同构造**：无 activity flag +
        // 随认证 Intent 下发目标字段 id，供确认页确认后按条目取回凭据并构造真实 Dataset 回传。
        // ISSUE-P3-375：结构化目标角色与框 id 同批下发（确认页据此把卡 / 地址字段写入回传数据集）
        confirmIntent = Intent(this, AutofillConfirmActivity::class.java).apply {
            putExtra(AutofillConfirmActivity.EXTRA_TARGET_USERNAME_ID, usernameId)
            putExtra(AutofillConfirmActivity.EXTRA_TARGET_PASSWORD_ID, passwordId)
            // ISSUE-P3-298 ⑤：OTP 框 id 随认证 Intent 下发，确认页回传时把当前 TOTP 值填入
            putExtra(AutofillConfirmActivity.EXTRA_TARGET_OTP_ID, otpId)
            putExtra(
                EXTRA_TARGET_STRUCTURED_ROLES,
                ArrayList(structuredTargetIds.keys.map { it.name })
            )
            putExtra(
                EXTRA_TARGET_STRUCTURED_IDS,
                ArrayList(structuredTargetIds.values)
            )
        },
        skipRepeatConfirmation = unlockedConfirmationPolicy(callingPkg, candidates, passwordId)
    )

    // ISSUE-P2-73 AC③：设备侧核对「已解锁分支命中了几个自动匹配候选」的调试留痕
    // （仅 debug 构建输出；只记数量，不含条目名 / 域名 / 包名等标识）
    AppLog.d(TAG, "已解锁分支候选数据集数量=${candidates.ranked.size}")

    for (ranked in candidates.ranked) {
        // ISSUE-P2-534：候选在「检索 → 下发」窗口内被并发擦除时 `buildCandidateDataset` 返回 null
        // （交付面拒绝把已清零字段读成空串填给目标应用），此处如实跳过该候选
        buildCandidateDataset(context, ranked)?.let { responseBuilder.addDataset(it) }
    }

    // ISSUE-P3-375 AC②：结构化数据集（卡 / 地址）——与登录候选正交追加
    appendStructuredDatasets(responseBuilder, context, structuredTargetIds)
}

/** 本轮请求内对所有候选恒定的建集入参（域 / 字段 id / 内联请求 / 认证 Intent / 确认策略） */
internal data class UnlockedDatasetContext(
    val callingPkg: String,
    val webDomain: String?,
    val usernameId: AutofillId?,
    val passwordId: AutofillId?,
    val inlineRequest: InlineSuggestionsRequest?,
    // ISSUE-P3-298 ⑤：显式声明的 OTP 框（命中 TOTP 的候选把当前码填入）
    val otpId: AutofillId?,
    val confirmIntent: Intent,
    val skipRepeatConfirmation: Boolean
)

/**
 * 单候选 → 数据集：值解析、菜单 / 内联呈现、字段值与二次认证挂接。
 *
 * `ISSUE-P2-534`：**返回 null 表示该候选已被并发擦除**，调用方须跳过（绝不向目标应用交付空值）。
 * 判据见 [hasClearedDeliveryFields]。
 */
private suspend fun KeePasskeyAutofillService.buildCandidateDataset(
    ctx: UnlockedDatasetContext,
    ranked: AutofillCandidateRanker.Ranked
): Dataset? {
    val entry = ranked.entry
    // ISSUE-P2-534：**交付面预判**——「检索 → 下发」窗口内候选可能已被会话整树替换就地清零。
    // 此时把已清零字段读成空串，会向目标应用填入空账号 / 空口令（静默污染，比报错更糟）；
    // 故按 `cleared` 观测位（不物化明文）预判中止，跳过该候选并留脱敏日志。
    if (entry.hasClearedFields()) {
        AppLog.w(TAG, "候选条目在检索与下发之间已被擦除，本轮跳过该候选")
        return null
    }
    // TASK-17：下发前解析 {REF:...} 字段引用（仅在取值消费点展开，投影层不物化）
    // ISSUE-P0-08：消费点面白名单——username 通道为非口令消费点，{REF:P@…} 一律掩码，
    // 口令明文不得经用户名通道进入 RemoteViews / IME 内联建议 / 确认页 extra / 请求方输入框；
    // password 通道为口令消费点，按 KDBX 语义展开
    val entryIdHex = entry.id.toHexString()
    val entryTitle = entry.displayTitle()
    // 上面已按 `cleared` 预判中止；此处仍走展示面读口，覆盖「预判 → 读取」之间仅剩的几条指令窗口
    val entryUserName = entry.displayUserName()
    val username = vaultRepository.resolveFieldReferences(entryIdHex, entryUserName, RefField.USER_NAME)
        ?: entryUserName
    val password = entry.password?.readStringForDisplay()
        ?.let { raw -> vaultRepository.resolveFieldReferences(entryIdHex, raw, RefField.PASSWORD) }
        .orEmpty()
    val displayName = username.ifBlank { entryTitle }

    val views = RemoteViews(packageName, R.layout.autofill_dataset_item).apply {
        setTextViewText(R.id.tv_username, displayName)
        setTextViewText(R.id.tv_subtitle, entryTitle)
    }
    val dsBuilder = Dataset.Builder(
        Presentations.Builder()
            .setMenuPresentation(views)
            .setDialogPresentation(views)
            .apply {
                buildInlinePresentation(ctx.inlineRequest, displayName, entryTitle)
                    ?.let { setInlinePresentation(it) }
            }
            .build()
    )
    if (ctx.usernameId != null && username.isNotEmpty()) {
        dsBuilder.setField(ctx.usernameId, Field.Builder().setValue(AutofillValue.forText(username)).build())
    }
    if (ctx.passwordId != null && password.isNotEmpty()) {
        dsBuilder.setField(ctx.passwordId, Field.Builder().setValue(AutofillValue.forText(password)).build())
    }
    // ISSUE-P3-298 ⑤：表单显式声明 OTP 框且条目命中 TOTP → 经 AutofillValue 正规通道直填
    // 当前验证码。**仅 TOTP**：HOTP 的当前码不推进计数器（推进是「取下一个码」的显式动作），
    // 直填会给出与服务端不同步的旧值，故 HOTP 条目不参与直填（复制 / 通知路径不受影响）。
    if (ctx.otpId != null) {
        val otpSnapshot = vaultRepository.calculateEntryTotp(entryIdHex)
        if (otpSnapshot != null && !otpSnapshot.isHotp && otpSnapshot.code.isNotEmpty()) {
            dsBuilder.setField(
                ctx.otpId,
                Field.Builder().setValue(AutofillValue.forText(otpSnapshot.code)).build()
            )
        }
    }
    if (!ctx.skipRepeatConfirmation) {
        attachConfirmationAuth(dsBuilder, entryIdHex, displayName, ctx)
    }
    return dsBuilder.build()
}

/** 每个数据集独立 requestCode，避免 PendingIntent 因 extras 相互覆盖 */
internal fun KeePasskeyAutofillService.attachConfirmationAuth(
    dsBuilder: Dataset.Builder,
    entryIdHex: String,
    displayName: String,
    ctx: UnlockedDatasetContext
) {
    // ISSUE-P3-03 (43b)：随确认入口下传条目标识，供 autofillCopyTotp
    // 在用户确认后按条目取回 TOTP（不物化明文，仅传标识）
    // ISSUE-P3-42：下传授权上下文（包名 + 域），供确认成功后写入会话授权
    val confirmPendingIntent = PendingIntent.getActivity(
        this,
        nextAuthRequestCode(),
        ctx.confirmIntent.putExtra(
            AutofillConfirmActivity.EXTRA_CREDENTIAL_TITLE, displayName
        ).putExtra(AutofillConfirmActivity.EXTRA_ENTRY_ID, entryIdHex)
            .putExtra(AutofillConfirmActivity.EXTRA_GRANT_PACKAGE, ctx.callingPkg)
            .putExtra(AutofillConfirmActivity.EXTRA_GRANT_DOMAIN, ctx.webDomain.orEmpty()),
        // ISSUE-P2-88：官方明文「Do not make the provided pending intent immutable ... as the
        // platform needs to fill in the authentication arguments」——认证 PendingIntent 必须可变，
        // 与该契约一致（接线守卫按此强制：本文件不得出现不可变标志）
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    dsBuilder.setAuthentication(confirmPendingIntent.intentSender)
}

/**
 * 追加手动搜索兜底入口（认证数据集形式）数据集。
 */
internal fun KeePasskeyAutofillService.buildPickerDataset(
    responseBuilder: FillResponse.Builder,
    callingPkg: String,
    scanResult: ScanResult,
    usernameId: AutofillId?,
    passwordId: AutofillId?,
    otpId: AutofillId? = null
) {
    // ISSUE-P3-376：手动选择器兜底数据集开关（默认开启）——关闭即本次响应不挂
    // 「搜索全部条目…」入口；自动匹配数据集与解锁引导不受影响（在本函数之前已装配）
    if (!settingsStore.isAutofillManualPickerEnabled()) return
    // ISSUE-P3-40：手动搜索兜底入口（自动匹配零候选/候选不含目标条目时使用）。
    // 以「认证数据集」形式挂入：值在用户于选择器中选中并确认后才经
    // AutofillManager.EXTRA_AUTHENTICATION_RESULT 回传——未确认前不携带任何明文。
    val pickerIntent = Intent(this, AutofillPickerActivity::class.java).apply {
        putExtra(AutofillPickerActivity.EXTRA_USERNAME_ID, usernameId)
        putExtra(AutofillPickerActivity.EXTRA_PASSWORD_ID, passwordId)
        // ISSUE-P3-298 ⑤：OTP 框 id 随链路转发，选择器交付时把当前 TOTP 值填入该框
        putExtra(AutofillPickerActivity.EXTRA_OTP_ID, otpId)
        // ISSUE-P3-43 ②：下传「字段签名」所需上下文，使选择器成为字段级屏蔽的写入入口。
        // 只传包名与表单自报域（均为非敏感标识），不传任何表单内容或凭据。
        putExtra(AutofillPickerActivity.EXTRA_CALLING_PACKAGE, callingPkg)
        putExtra(AutofillPickerActivity.EXTRA_WEB_DOMAIN, scanResult.webDomain.orEmpty())
    }
    val pickerPendingIntent = PendingIntent.getActivity(
        this,
        nextAuthRequestCode(),
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
    if (otpId != null) {
        // ISSUE-P3-298 ⑤：与解锁引导数据集同语义——值在认证后（选择器回传时）才可用
        @Suppress("DEPRECATION")
        pickerBuilder.setValue(otpId, null)
    }
    pickerBuilder.setAuthentication(pickerPendingIntent.intentSender)
    responseBuilder.addDataset(pickerBuilder.build())
}

/**
 * 按需注册 SaveInfo 以便在用户提交时捕获新账密。
 */
internal fun KeePasskeyAutofillService.applySaveInfoIfNeeded(
    responseBuilder: FillResponse.Builder,
    usernameId: AutofillId?,
    passwordId: AutofillId?
) {
    // 注册 SaveInfo 以便在用户提交时捕获新账密
    // ISSUE-P3-44：仅在用户开启「新密码保存提示」时注册
    if (!settingsStore.isOfferSaveCredentialsEnabled()) return
    val saveFlags = SaveInfo.SAVE_DATA_TYPE_PASSWORD or SaveInfo.SAVE_DATA_TYPE_USERNAME

    val saveInfo = when {
        passwordId != null -> {
            val builder = SaveInfo.Builder(saveFlags, arrayOf(passwordId))
                .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
            if (usernameId != null) {
                builder.setOptionalIds(arrayOf(usernameId))
            }
            builder.build()
        }
        usernameId != null -> {
            SaveInfo.Builder(saveFlags, arrayOf(usernameId))
                .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
                .build()
        }
        else -> null
    }

    if (saveInfo != null) {
        responseBuilder.setSaveInfo(saveInfo)
    }
}
