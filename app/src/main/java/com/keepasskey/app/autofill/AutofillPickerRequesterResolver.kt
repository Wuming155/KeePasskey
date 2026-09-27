package com.keepasskey.app.autofill

import android.content.Intent
import android.content.pm.PackageManager
import com.keepasskey.core.log.AppLog

/**
 * 选择器请求方身份的解析体（`ISSUE-P2-70`，自 `AutofillPickerActivity` 抽出以控其分档）。
 *
 * 可信度分层（与 [AutofillPickerRequester] 一致）：
 * - 包名取自 extra（系统结构树下发，**不可伪造锚点**）；缺失 → 返回 null（不展示归属块）；
 * - 应用名经 PackageManager 读取，**可被应用自声明**，仅作辅助识别，读取失败按无名称处理；
 * - 签名证书 SHA-256 经 [AutofillOriginResolver] 读取（与确认页同一通道），不可读时如实标注；
 * - 域为**表单自报且未经归属校验**（本页不据其放行，仅如实展示给用户）。
 */
fun resolveAutofillPickerRequester(
    intent: Intent,
    packageManager: PackageManager,
    autofillOriginResolver: AutofillOriginResolver
): AutofillPickerRequester? {
    val callingPackage = intent.getStringExtra(AutofillPickerActivity.EXTRA_CALLING_PACKAGE)
    if (callingPackage.isNullOrBlank()) return null
    val label = try {
        val appInfo = packageManager.getApplicationInfo(callingPackage, 0)
        packageManager.getApplicationLabel(appInfo).toString()
    } catch (t: Throwable) {
        // 包可见性受限 / 应用已卸载：按「无名称」处理，绝不伪造名称
        AppLog.w(TAG, "读取请求方应用名称失败，按无名称处理: ${t.javaClass.simpleName}")
        null
    }
    return buildAutofillPickerRequester(
        packageName = callingPackage,
        appLabel = label,
        certSha256Hex = autofillOriginResolver.callingAppCertSha256Hex(callingPackage),
        reportedDomain = intent.getStringExtra(AutofillPickerActivity.EXTRA_WEB_DOMAIN)
    )
}

private const val TAG = "AutofillPicker"
