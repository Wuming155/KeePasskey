package com.keepasskey.app.autofill

import android.content.Intent
import android.view.autofill.AutofillId
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.core.log.AppLog

/**
 * 确认页认证回传 Intent 构造（自 `AutofillConfirmActivity` 按职责拆出——
 * 本文件新增后该 Activity 回落出行数闸门 tier2 以下；拆分**零行为变更**，只做结构搬运）。
 *
 * 本文件承载「按认证 Intent 下发的目标字段 id 与条目标识取回凭据，构造待回传数据集」；
 * 锁定门控与 `setResult` 仍留在 Activity 本体（`deliverAuthResult` / `discardPendingResult`），
 * 故 `AutofillConfirmDeliveryLockTest` 的接线判据不受本次搬运影响。
 */

private const val TAG = "AutofillConfirm"

/**
 * ISSUE-P2-88：按认证 Intent 下发的目标字段 id 与条目标识取回凭据，构造待回传数据集。
 *
 * 复用选择器同一条取数路径（[AutofillPickerViewModel.resolveCredentials]）与同一份载荷构造
 * （[AutofillConfirmAuthDelivery]）——这两处都是经真机验证可填充的形态。
 *
 * @return 无目标框 / 凭据不可用 / 无可写字段时返回 null（调用方按取消处置）
 */
internal suspend fun resolveConfirmAuthResultIntent(
    intent: Intent,
    packageName: String,
    vaultRepository: VaultRepository,
    autofillFieldBlocklistStore: AutofillFieldBlocklistStore,
    pickerViewModel: AutofillPickerViewModel
): Intent? {
    val entryId = intent.getStringExtra(AutofillConfirmActivity.EXTRA_ENTRY_ID)
        ?.takeIf { it.isNotBlank() } ?: return null
    val usernameId = intent.readAutofillId(AutofillConfirmActivity.EXTRA_TARGET_USERNAME_ID)
    val passwordId = intent.readAutofillId(AutofillConfirmActivity.EXTRA_TARGET_PASSWORD_ID)
    val otpId = intent.readAutofillId(AutofillConfirmActivity.EXTRA_TARGET_OTP_ID)
    // ISSUE-P3-375：结构化目标（读取实现拆至 AutofillConfirmStructuredTargets）
    val structured = readStructuredTargetsFrom(intent)
    if (usernameId == null && passwordId == null && structured.isEmpty()) return null

    // ISSUE-P2-384 AC①：认证回传构造 Dataset 前复检字段级屏蔽（fail-closed）。
    // 确认页是「再次确认」路径——屏蔽写入后系统缓存重放 / 用户再次确认时，
    // 被屏蔽字段不得再出现在回传数据集里（与选择器 deliver 同一策略对象）。
    // 域取表单**自报**域（与屏蔽写入键同源）；确认页仅有归属校验后域时回落之。
    val callingPackage = intent.getStringExtra(AutofillPickerActivity.EXTRA_CALLING_PACKAGE)
        ?: intent.getStringExtra(AutofillConfirmActivity.EXTRA_GRANT_PACKAGE)
        ?: packageName
    val formDomain = intent.getStringExtra(AutofillPickerActivity.EXTRA_WEB_DOMAIN)
        ?: intent.getStringExtra(AutofillConfirmActivity.EXTRA_GRANT_DOMAIN)?.takeIf { it.isNotBlank() }
    val deliverable = AutofillAuthDeliveryBlockPolicy.filter(
        usernameId = usernameId,
        passwordId = passwordId
    ) { role ->
        autofillFieldBlocklistStore.isBlocked(callingPackage, formDomain, role)
    }
    if (deliverable.blocksEntireForm && structured.isEmpty()) {
        AppLog.w(TAG, "确认页回传复检：字段已被屏蔽，按取消回传")
        return null
    }

    // ISSUE-P2-617：最终交付前复检条目与当前来源的匹配（fail-closed，与候选装配同源判据）。
    // 候选装配的记忆域隔离 + 排序器域复检已拦住跨站记忆，但确认 Intent 在「下发 → 用户确认」
    // 窗口内可被系统缓存重放，且未来新增的准入来源可能绕过这两道门；此处复检——浏览器表单
    // （formDomain 非空）要求条目命中当前域或经 `android://` 显式绑定调用包名，否则按取消回传；
    // 纯 App 表单（formDomain 为空）无域约束。合法候选恒通过，不改变任何放行语义。
    // 条目取数走展示面读口（已擦除即空串 ⇒ 复检不通过 ⇒ 取消，与交付面既有口径一致）。
    val deliveryEntry = vaultRepository.getKdbxEntry(entryId) ?: return null
    if (!entryMatchesDeliveryOrigin(
            deliveryEntry,
            deliveryEntry.displayUrl(),
            formDomain,
            callingPackage
        )
    ) {
        AppLog.w(TAG, "确认页交付复检：条目与当前来源不匹配，按取消回传")
        return null
    }

    val credentials = pickerViewModel.resolveCredentials(entryId) ?: return null
    val credentialTitle = intent.getStringExtra(AutofillConfirmActivity.EXTRA_CREDENTIAL_TITLE).orEmpty()
    // ISSUE-P3-298 ⑤：回传时刻现算 TOTP（值新鲜度以交付时刻为准）；仅 TOTP 参与
    // 直填——HOTP 当前码不推进计数器，直填会给出与服务端不同步的旧值
    val otpCode = if (otpId != null) {
        vaultRepository.calculateEntryTotp(entryId)
            ?.takeIf { !it.isHotp }?.code.orEmpty()
    } else {
        ""
    }
    // ISSUE-P3-375 AC③：结构化字段值只在确认成功后按条目取回（实现拆至 AutofillConfirmStructuredTargets）
    // 条目复用交付复检已取回的实例，不二次查询
    val structuredValues: Map<AutofillId, String> = if (structured.isEmpty()) emptyMap() else {
        buildStructuredFieldValues(structured, deliveryEntry)
    }
    val dataset = AutofillConfirmAuthDelivery.build(
        packageName = packageName,
        // ISSUE-P3-330：用户名为空时标题行即条目标题，副行留空——避免两行同文
        menuTitle = credentials.username.ifBlank { credentialTitle },
        menuSubtitle = if (credentials.username.isNotBlank()) credentialTitle else "",
        username = credentials.username,
        password = credentials.password,
        deliverable = deliverable,
        otpId = otpId,
        otpCode = otpCode,
        structuredFields = structuredValues
    ) ?: return null
    // 只记录「哪些字段真的有值」，不含任何凭据内容 / 用户名 / 条目名 / 包名
    AutofillConfirmAuthDelivery.logDelivery(
        tag = TAG,
        usernameHasValue = credentials.username.isNotEmpty(),
        passwordHasValue = credentials.password.isNotEmpty(),
        otpHasValue = otpCode.isNotEmpty(),
        structuredCount = structuredValues.size,
        usernameIdPresent = deliverable.usernameId != null,
        passwordIdPresent = deliverable.passwordId != null,
        otpIdPresent = otpId != null
    )
    return authenticationResultIntent(dataset)
}
