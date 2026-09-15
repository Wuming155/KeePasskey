package com.keepasskey.app.autofill

import android.content.Context
import android.content.pm.PackageManager
import com.keepasskey.app.passkey.DigitalAssetLinksVerifier
import com.keepasskey.app.security.CallerCertDigests
import com.keepasskey.core.log.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动填充 webDomain 归属解析器（ISSUE-P2-07 / ZT-12 / ISSUE-P1-11）。
 *
 * 复用既有 [DigitalAssetLinksVerifier]（ISSUE-P2-02）对非浏览器调用方做站点归属验证：
 * 拉取 `https://<webDomain>/.well-known/assetlinks.json`，要求站点显式声明授权
 * 「该调用包名 + 其签名证书指纹」，从而将调用应用与 webDomain 强绑定。
 *
 * 安全语义（ISSUE-P1-11）：受信任浏览器分支**不再仅按包名放行**——先读取调用方签名证书指纹，
 * 仅「包名 + 已取证指纹」二元组匹配（[AutofillWebDomainPolicy.isTrustedBrowser]）才直接放行；
 * 其余调用方（含未取证浏览器、指纹不匹配的冒名应用）必须通过 DAL 校验，
 * 无法取得证书指纹 / 校验失败 / 网络不可用一律返回 null（fail-closed，不下发该域候选）。
 */
@Singleton
class AutofillOriginResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dalVerifier: DigitalAssetLinksVerifier
) {

    /**
     * 解析可用于凭据匹配的 webDomain。
     * @return 通过归属校验的归一化域名；无法验证时返回 null
     */
    suspend fun resolveUsableWebDomain(callingPackage: String, rawWebDomain: String?): String? {
        val domain = AutofillWebDomainPolicy.normalizeDomain(rawWebDomain) ?: return null

        // ISSUE-P1-11：证书指纹必须在浏览器判定之前读取，使浏览器分支同样受「包名 + 指纹」约束
        // ISSUE-P3-93：读取**全部**签名摘要（当前 + 历史），任一命中即通过——签名轮换期不得因
        // 只取首个摘要而误判未授权
        val certDigests = callingAppCertDigests(callingPackage)
        if (AutofillWebDomainPolicy.isTrustedBrowser(callingPackage, certDigests)) return domain

        val dalVerified = if (certDigests.isEmpty) {
            false
        } else {
            try {
                dalVerifier.verify(domain, callingPackage, certDigests) ==
                    DigitalAssetLinksVerifier.DalResult.VERIFIED
            } catch (t: Throwable) {
                // 网络/解析异常：按未验证处理（DAL 内部已记录，degraded 不重试不静默放行）
                AppLog.w(TAG, "webDomain 归属 DAL 校验异常，按未验证处理", t)
                false
            }
        }

        return when (
            AutofillWebDomainPolicy.attribute(callingPackage, domain, certDigests, dalVerified)
        ) {
            WebDomainAttribution.BROWSER_DELEGATED,
            WebDomainAttribution.DAL_VERIFIED -> domain
            WebDomainAttribution.REJECTED -> null
        }
    }

    /**
     * 读取调用方 APK 签名证书 SHA-256（大写无冒号）**主摘要**；失败返回 null（调用方 fail-closed）。
     *
     * ISSUE-P1-24 AC①：确认页归属展示复用同一读取通道——包名 + 签名摘要是不可伪造锚点
     * （label / icon 应用可自声明）；包可见性受限（Android 11+ 无 `<queries>`）时读取失败，
     * 展示侧须如实标注「不可读」，不得以占位摘要冒充。
     *
     * ISSUE-P3-93：放行判定请改用 [callingAppCertDigests]（全部签名者，任一命中即通过）；
     * 本方法仅供展示 / 信任记录写入。
     */
    internal fun callingAppCertSha256Hex(packageName: String): String? =
        callingAppCertDigests(packageName).primary

    /**
     * ISSUE-P3-93（审计 F-19）：读取调用方**全部**签名证书摘要。
     *
     * 取值口径：`apkContentsSigners`（当前有效，在前）+ `signingCertificateHistory`（历史轮换，在后），
     * 去重后归一化为大写十六进制；读取失败 / 不可见返回空集（调用方按 fail-closed 处理）。
     */
    internal fun callingAppCertDigests(packageName: String): CallerCertDigests = try {
        val info = context.packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        )
        val signingInfo = info.signingInfo
        val signers = signingInfo?.apkContentsSigners.orEmpty().toList() +
            signingInfo?.signingCertificateHistory.orEmpty().toList()
        CallerCertDigests.of(signers.map { signer ->
            MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
                .joinToString("") { "%02X".format(it) }
        })
    } catch (t: Throwable) {
        AppLog.w(TAG, "读取调用方签名证书失败，无法完成 webDomain 归属校验", t)
        CallerCertDigests.EMPTY
    }

    companion object {
        private const val TAG = "AutofillOrigin"
    }
}
