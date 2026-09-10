package com.keepasskey.app.autofill

import android.content.Context
import android.content.pm.PackageManager
import com.keepasskey.app.passkey.DigitalAssetLinksVerifier
import com.keepasskey.core.log.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动填充 webDomain 归属解析器（ISSUE-P2-07 / ZT-12）。
 *
 * 复用既有 [DigitalAssetLinksVerifier]（ISSUE-P2-02）对非浏览器调用方做站点归属验证：
 * 拉取 `https://<webDomain>/.well-known/assetlinks.json`，要求站点显式声明授权
 * 「该调用包名 + 其签名证书指纹」，从而将调用应用与 webDomain 强绑定。
 *
 * 安全语义：受信任浏览器包名白名单直接放行；其余调用方必须通过 DAL 校验，
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
        if (AutofillWebDomainPolicy.isTrustedBrowser(callingPackage)) return domain

        val certSha256Hex = callingAppCertSha256Hex(callingPackage)
        val dalVerified = if (certSha256Hex == null) {
            false
        } else {
            try {
                dalVerifier.verify(domain, callingPackage, certSha256Hex) ==
                    DigitalAssetLinksVerifier.DalResult.VERIFIED
            } catch (t: Throwable) {
                // 网络/解析异常：按未验证处理（DAL 内部已记录，degraded 不重试不静默放行）
                AppLog.w(TAG, "webDomain 归属 DAL 校验异常，按未验证处理", t)
                false
            }
        }

        return when (AutofillWebDomainPolicy.attribute(callingPackage, domain, dalVerified)) {
            WebDomainAttribution.BROWSER_DELEGATED,
            WebDomainAttribution.DAL_VERIFIED -> domain
            WebDomainAttribution.REJECTED -> null
        }
    }

    /** 读取调用方 APK 签名证书 SHA-256（大写无冒号）；失败返回 null（调用方 fail-closed） */
    private fun callingAppCertSha256Hex(packageName: String): String? = try {
        val info = context.packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        )
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
        MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
            .joinToString("") { "%02X".format(it) }
    } catch (t: Throwable) {
        AppLog.w(TAG, "读取调用方签名证书失败，无法完成 webDomain 归属校验", t)
        null
    }

    companion object {
        private const val TAG = "AutofillOrigin"
    }
}
