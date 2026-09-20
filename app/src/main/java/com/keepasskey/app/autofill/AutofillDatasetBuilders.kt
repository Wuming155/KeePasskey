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
import com.keepasskey.app.autofill.KeePasskeyAutofillService.Companion.MAX_DATASET_COUNT
import java.util.concurrent.atomic.AtomicInteger
import com.keepasskey.app.autofill.KeePasskeyAutofillService.Companion.TAG
import com.keepasskey.core.log.AppLog
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
 * ISSUE-P2-86：认证入口指向 [AutofillUnlockActivity]，该页解锁成功后**链入选择器**
 * （[AutofillPickerActivity]）并原样转发其认证结果——选择器经
 * `AutofillManager.EXTRA_AUTHENTICATION_RESULT` 回传真实 [Dataset]，是本应用内
 * **唯一**经真机验证可用的交付路径。因此基 Intent 必须按选择器所需的上下文补齐 extras。
 *
 * @param callingPkg 调用方包名（下发选择器，用于归属展示与首次绑定写入）
 * @param webDomain 表单**自报**域（下发选择器，用于字段级屏蔽签名；可为 null）
 * @return 库锁定时返回仅含解锁引导数据集的响应；库已解锁时返回 null（由调用方继续走已解锁分支）
 */
internal fun KeePasskeyAutofillService.buildLockedUnlockDataset(
    usernameId: AutofillId?,
    passwordId: AutofillId?,
    callingPkg: String,
    webDomain: String?,
    inlineRequest: InlineSuggestionsRequest?
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
    dsBuilder.setAuthentication(pendingIntent.intentSender)

    return FillResponse.Builder().apply { addDataset(dsBuilder.build()) }.build()
}

/**
 * 库已解锁时按候选打分追加候选数据集。
 */
internal suspend fun KeePasskeyAutofillService.appendUnlockedDatasets(
    responseBuilder: FillResponse.Builder,
    callingPkg: String,
    scanResult: ScanResult,
    usernameId: AutofillId?,
    passwordId: AutofillId?,
    inlineRequest: InlineSuggestionsRequest?
) {
    val candidates = resolveUnlockedCandidates(callingPkg, scanResult)
    val context = UnlockedDatasetContext(
        callingPkg = callingPkg,
        webDomain = candidates.webDomain,
        usernameId = usernameId,
        passwordId = passwordId,
        inlineRequest = inlineRequest,
        // ISSUE-P2-88：官方契约（`Dataset.Builder#setAuthentication` 原文）对认证数据集有**两条**强制要求：
        // ① 认证 PendingIntent 不得不可变（平台要注入认证参数）⇒ `FLAG_MUTABLE`；
        // ② 认证结束后必须经 `AutofillManager.EXTRA_AUTHENTICATION_RESULT` 回传「fully populated
        //    dataset」，框架才会「replace the authenticated dataset and immediately fill in」。
        // 本路径原先两条都违反（IMMUTABLE + 确认页只回传成功而不回传数据集）⇒ 真机恒不写入。
        // 现与选择器入口（[buildPickerDataset]，真机实测可用）**同构造**：无 activity flag +
        // 随认证 Intent 下发目标字段 id，供确认页确认后按条目取回凭据并构造真实 Dataset 回传。
        confirmIntent = Intent(this, AutofillConfirmActivity::class.java).apply {
            putExtra(AutofillConfirmActivity.EXTRA_TARGET_USERNAME_ID, usernameId)
            putExtra(AutofillConfirmActivity.EXTRA_TARGET_PASSWORD_ID, passwordId)
        },
        skipRepeatConfirmation = unlockedConfirmationPolicy(callingPkg, candidates, passwordId)
    )

    // ISSUE-P2-73 AC③：设备侧核对「已解锁分支命中了几个自动匹配候选」的调试留痕
    // （仅 debug 构建输出；只记数量，不含条目名 / 域名 / 包名等标识）
    AppLog.d(TAG, "已解锁分支候选数据集数量=${candidates.ranked.size}")

    for (ranked in candidates.ranked) {
        responseBuilder.addDataset(buildCandidateDataset(context, ranked))
    }
}

/** 本轮请求内对所有候选恒定的建集入参（域 / 字段 id / 内联请求 / 认证 Intent / 确认策略） */
private data class UnlockedDatasetContext(
    val callingPkg: String,
    val webDomain: String?,
    val usernameId: AutofillId?,
    val passwordId: AutofillId?,
    val inlineRequest: InlineSuggestionsRequest?,
    val confirmIntent: Intent,
    val skipRepeatConfirmation: Boolean
)

/** 已解锁分支的候选集：域名维度 + `android://` 包名维度双路打分后的截断结果 */
private data class UnlockedCandidates(
    val webDomain: String?,
    val ranked: List<AutofillCandidateRanker.Ranked>
)

/**
 * 解析本轮可用候选（域归属校验 + 包名维度首次绑定校验 + 打分排序）。
 */
private suspend fun KeePasskeyAutofillService.resolveUnlockedCandidates(
    callingPkg: String,
    scanResult: ScanResult
): UnlockedCandidates {
    // 库已解锁：查找匹配凭据
    // ISSUE-P2-07：webDomain 参与匹配前必须通过归属校验（受信浏览器白名单或 DAL 归属声明）；
    // 无法验证时按 null 处理（不下发该域候选），绝不静默放行
    // ISSUE-P3-170：调用方证书摘要**只读一次**并由下述两处共用（归属解析 + `android://` 维度
    // 首次绑定校验）——原实现各读一次，各含一次 `getPackageInfo` + 逐签名者 SHA-256 + 逐字节 hex 格式化；
    // 顺带消除「两次读取之间调用方身份变化」造成的判定不一致
    val callerCertDigests = autofillOriginResolver.callingAppCertDigests(callingPkg)
    val webDomain = autofillOriginResolver.resolveUsableWebDomain(callingPkg, scanResult.webDomain, callerCertDigests)
    if (scanResult.webDomain != null && webDomain == null) {
        AppLog.w(TAG, "webDomain 归属无法验证，已忽略该域候选（fail-closed）")
    }
    // ISSUE-P2-46：`android://` 包名维度必须先通过「调用方包名 + 签名摘要」首次绑定校验。
    // 未绑定 / 签名不可读时该维度一律不命中（fail-closed），条目仍可经域名维度入选；
    // 未绑定调用方的补救路径是选择器显式指认（该动作即首次绑定写入，见 AutofillPickerActivity）。
    val packageDimensionAuthorized = AndroidPackageBindingPolicy.isPackageDimensionAuthorized(
        callingPackage = callingPkg,
        callingCertDigests = callerCertDigests,
        isTrusted = { pkg, digests -> callerTrustStore.isTrusted(pkg, digests) }
    )
    if (!packageDimensionAuthorized && callingPkg.isNotBlank()) {
        // 日志不携带包名 / 摘要等调用方标识（ISSUE-P1-10 语义）
        AppLog.i(TAG, "android:// 维度未授权（未完成包名+签名首次绑定），本次不提供包名维度候选")
    }
    // ISSUE-P3-39：候选打分排序——严格匹配（DomainMatcher）通过的条目按
    // 「精确域名 > 精确包名 > 父域」打分并截断；匹配条件一字未放宽，
    // 未通过 isDomainMatch / isPackageMatch 的条目不会进入结果。
    return UnlockedCandidates(
        webDomain = webDomain,
        ranked = AutofillCandidateRanker.rank(
            entries = vaultRepository.getKdbxEntries(),
            callingPackage = callingPkg,
            webDomain = webDomain,
            packageDimensionAuthorized = packageDimensionAuthorized,
            lastFilledEntryId = autofillLastFilledStore.lastFilledEntryId(),
            limit = MAX_DATASET_COUNT
        )
    )
}

/**
 * TASK-11 整改（审核报告 P2-24）：已解锁分支的每个数据集必须携带 setAuthentication
 * 二次确认——否则任何前台应用都可静默拉起候选并完成明文密码填充（用户无感知泄露）。
 * 用户点选数据集 → 拉起 AutofillConfirmActivity（生物识别/锁屏凭据或受保护窗口内
 * 手动确认）→ RESULT_OK 后框架才将该数据集的值真正写入目标表单。
 *
 * ISSUE-P3-42：会话授权宽限（默认关闭）。本分支库已解锁；仅当开关开启且存在与
 * 「包名 + 域」严格匹配的有效授权（30 秒 TTL，由上次确认写入）时跳过重复二次确认。
 * 开关关闭时不查询授权存储，行为与既有「每次强制确认」完全一致。
 * ISSUE-P1-24 AC③：宽限**不得**作用于携带口令值的数据集——口令仅在显式确认后下发，
 * 用户名字段可例外（见 [AutofillAuthenticationPolicy.skipRepeatConfirmation]）。
 */
private suspend fun KeePasskeyAutofillService.unlockedConfirmationPolicy(
    callingPkg: String,
    candidates: UnlockedCandidates,
    passwordId: AutofillId?
): Boolean {
    val sessionGrantEnabled = settingsStore.isAutofillSessionGrantEnabled()
    val grantActive = sessionGrantEnabled && AutofillSessionGrants.isGranted(
        AutofillGrantContext(callingPkg, candidates.webDomain)
    )
    return AutofillAuthenticationPolicy.skipRepeatConfirmation(
        sessionGrantEnabled = sessionGrantEnabled,
        vaultLocked = vaultRepository.isLocked(),
        grantActive = grantActive,
        // 首个候选即代表本批数据集的口令承载面（同批候选口径一致：口令通道同为
        // passwordId + 非空口令才携带值），逐数据集计算无增量信息
        datasetCarriesPassword = passwordId != null && candidates.ranked.any { ranked ->
            ranked.entry.password?.readString()?.isNotEmpty() == true
        }
    )
}

/** 单候选 → 数据集：值解析、菜单 / 内联呈现、字段值与二次认证挂接 */
private suspend fun KeePasskeyAutofillService.buildCandidateDataset(
    ctx: UnlockedDatasetContext,
    ranked: AutofillCandidateRanker.Ranked
): Dataset {
    val entry = ranked.entry
    // TASK-17：下发前解析 {REF:...} 字段引用（仅在取值消费点展开，投影层不物化）
    // ISSUE-P0-08：消费点面白名单——username 通道为非口令消费点，{REF:P@…} 一律掩码，
    // 口令明文不得经用户名通道进入 RemoteViews / IME 内联建议 / 确认页 extra / 请求方输入框；
    // password 通道为口令消费点，按 KDBX 语义展开
    val entryIdHex = entry.id.toHexString()
    val username = vaultRepository.resolveFieldReferences(entryIdHex, entry.userName, RefField.USER_NAME)
        ?: entry.userName
    val password = entry.password?.readString()
        ?.let { raw -> vaultRepository.resolveFieldReferences(entryIdHex, raw, RefField.PASSWORD) }
        .orEmpty()
    val displayName = username.ifBlank { entry.title }

    val views = RemoteViews(packageName, R.layout.autofill_dataset_item).apply {
        setTextViewText(R.id.tv_username, displayName)
        setTextViewText(R.id.tv_subtitle, entry.title)
    }
    val dsBuilder = Dataset.Builder(
        Presentations.Builder()
            .setMenuPresentation(views)
            .setDialogPresentation(views)
            .apply {
                buildInlinePresentation(ctx.inlineRequest, displayName, entry.title)
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
    if (!ctx.skipRepeatConfirmation) {
        attachConfirmationAuth(dsBuilder, entryIdHex, displayName, ctx)
    }
    return dsBuilder.build()
}

/** 每个数据集独立 requestCode，避免 PendingIntent 因 extras 相互覆盖 */
private fun KeePasskeyAutofillService.attachConfirmationAuth(
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
    passwordId: AutofillId?
) {
    // ISSUE-P3-40：手动搜索兜底入口（自动匹配零候选/候选不含目标条目时使用）。
    // 以「认证数据集」形式挂入：值在用户于选择器中选中并确认后才经
    // AutofillManager.EXTRA_AUTHENTICATION_RESULT 回传——未确认前不携带任何明文。
    val pickerIntent = Intent(this, AutofillPickerActivity::class.java).apply {
        putExtra(AutofillPickerActivity.EXTRA_USERNAME_ID, usernameId)
        putExtra(AutofillPickerActivity.EXTRA_PASSWORD_ID, passwordId)
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
