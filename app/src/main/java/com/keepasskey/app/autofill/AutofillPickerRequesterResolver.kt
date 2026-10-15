package com.keepasskey.app.autofill

import android.content.Intent
import android.content.pm.PackageManager
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.security.CallerCertDigests
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

/**
 * 选择器路径的**会话授权宽限上下文**解析（`ISSUE-P3-185`，自 `AutofillPickerActivity`
 * 抽出以控其分档）。
 *
 * 与数据集路径同口径：域经 [AutofillOriginResolver.resolveUsableWebDomain] 归属校验
 * （与确认页写入 `EXTRA_GRANT_DOMAIN`、数据集查询所用域同源），不可归属 → 归一化为 null
 * → 授权存储自身既不写入也不命中（fail-closed，见 [AutofillSessionGrantStore]）。
 * 开关关闭 / 包名缺失时返回 null（不做归属校验的网络开销，行为与既有一致）。
 */
internal suspend fun resolveAutofillPickerGrantContext(
    intent: Intent,
    settingsStore: ExtendedSettingsStore,
    autofillOriginResolver: AutofillOriginResolver
): AutofillGrantContext? {
    if (!settingsStore.isAutofillSessionGrantEnabled()) return null
    val callingPackage = intent.getStringExtra(AutofillPickerActivity.EXTRA_CALLING_PACKAGE)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val verifiedDomain = autofillOriginResolver.resolveUsableWebDomain(
        callingPackage,
        intent.getStringExtra(AutofillPickerActivity.EXTRA_WEB_DOMAIN)
    )
    return AutofillGrantContext(callingPackage, verifiedDomain)
}

/**
 * ISSUE-P2-46：写入调用方「首次绑定」（包名 + 主签名摘要），供 `android://` 维度后续放行。
 *
 * 自 `AutofillPickerActivity` 按职责拆出（行数闸门；拆分**零行为变更**，只做结构搬运）：
 * 选择器是**唯一**由用户显式指认「把这条凭据填给这个调用方」的入口（受保护窗口 + 已展示
 * 包名 / 应用名 / 签名摘要，见 ISSUE-P2-70），故首次绑定写入落在此处；写入后 `android://`
 * 维度才对同签名调用方参与自动匹配。
 *
 * **fail-closed**：签名摘要不可读（空集）时**不写入**降级键（`pkg|`）——「只认包名」正是
 * 本项要消灭的形态；包名非法时存储自身拒绝写入（返回 false）。两种情况一律保持「未绑定」，
 * 该调用方的 `android://` 候选继续不命中。
 *
 * @return 本次读取的签名摘要快照（ISSUE-P3-528 起下传交付点复用，避免同页二次读取）
 */
internal fun bindCallerForPackageDimension(
    intent: Intent,
    autofillOriginResolver: AutofillOriginResolver,
    callerTrustStore: AutofillCallerTrustStore
): CallerCertDigests {
    val callingPackage = intent.getStringExtra(AutofillPickerActivity.EXTRA_CALLING_PACKAGE).orEmpty()
    if (callingPackage.isBlank()) return CallerCertDigests.EMPTY
    val digests = autofillOriginResolver.callingAppCertDigests(callingPackage)
    if (digests.isEmpty) {
        // 日志不携带包名 / 摘要等调用方标识（ISSUE-P1-10 语义）
        AppLog.w(TAG, "调用方签名摘要不可读，android:// 维度保持未绑定（fail-closed）")
        return digests
    }
    val written = callerTrustStore.trust(callingPackage, digests.primary)
    AppLog.i(TAG, "android:// 维度首次绑定写入结果=$written")
    return digests
}
