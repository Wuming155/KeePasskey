package com.keepasskey.app.autofill

import android.content.Intent

/**
 * 确认页调用方归属的解析体（`ISSUE-P1-24` AC①/AC②；`ISSUE-P3-390` 起自
 * `AutofillConfirmActivity` 拆出以控其行数分档——只挪位置，判定逻辑逐字不变）。
 *
 * 可信度口径与拆分前一致：
 * - 包名取自本服务写入的 extra（源自系统结构树，非调用方自报；该 PendingIntent 由本应用创建
 *   且只交给系统框架，第三方拿不到、无从改写）；
 * - 域为本服务已通过归属校验的 webDomain（自报且未通过校验的域不会下发候选）；
 * - 签名摘要经 PackageManager 现场读取（包可见性受限时为 null，展示侧如实标注）。
 *
 * extra 缺失（异常启动路径）返回 null：此时保持既有确认流程，但**不**写会话授权与信任记录。
 */
internal fun resolveAutofillConfirmCallerAttribution(
    intent: Intent,
    autofillOriginResolver: AutofillOriginResolver,
    callerTrustStore: AutofillCallerTrustStore
): AutofillCallerAttribution? {
    val callerPackage = intent.getStringExtra(AutofillConfirmActivity.EXTRA_GRANT_PACKAGE)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val callerDomain = intent.getStringExtra(AutofillConfirmActivity.EXTRA_GRANT_DOMAIN)
        ?.takeIf { it.isNotBlank() }
    // ISSUE-P3-93：读取**全部**签名摘要参与判定；展示与信任记录仍用主摘要
    val certDigests = autofillOriginResolver.callingAppCertDigests(callerPackage)
    val firstOccurrence = !callerTrustStore.isTrusted(callerPackage, certDigests)
    return AutofillCallerAttribution(
        packageName = callerPackage,
        // 展示与信任记录用主摘要（不可读时为 null，展示侧如实标注「不可读」）
        certSha256Hex = certDigests.primary,
        webDomain = callerDomain,
        firstOccurrence = firstOccurrence
    )
}
