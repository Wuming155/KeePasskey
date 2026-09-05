package com.keepasskey.app.passkey

import android.util.Base64
import androidx.credentials.provider.CallingAppInfo
import java.security.MessageDigest

/**
 * 调用方 Origin 可信解析器（H1 整改，CWE-346）。
 *
 * 安全设计（对齐 Android 官方 Credential Provider 集成指南）：
 * 1. 浏览器委派调用（[CallingAppInfo.isOriginPopulated] 为 true）：强制走官方
 *    [CallingAppInfo.getOrigin]——仅在调用方包名与签名证书指纹均匹配浏览器特权白名单时
 *    才返回 origin，白名单缺失、格式漂移或校验失败一律 fail-closed 降级为普通应用处理；
 * 2. 普通应用调用：绝不信任调用方可控的 origin 字符串（bundle/requestJson 均为攻击面），
 *    固定颁发 `android:apk-key-hash:<base64url(sha256(签名证书))>` origin；
 * 3. 白名单外浏览器（或指纹轮换）降级为普通应用路径，安全边界不放松。
 */
object CallingOriginResolver {

    const val APK_KEY_HASH_PREFIX = "android:apk-key-hash:"

    /**
     * 浏览器特权白名单（官方 getOrigin 格式）。
     * 指纹为浏览器签名证书 SHA-256 十六进制摘要（同时收录带冒号/不带冒号两种写法以兼容解析差异）。
     * 浏览器签名证书轮换或新增受信浏览器时更新此处即可。
     */
    private val PRIVILEGED_BROWSER_ALLOWLIST = """
        {
          "apps": [
            {
              "type": "android",
              "info": {
                "package_name": "com.android.chrome",
                "signatures": [
                  {"build": "default", "userdebug": false, "cert_fingerprint_sha256": "32:a2:fc:74:d7:31:10:58:59:e5:a8:5d:f1:6d:95:f1:02:d8:5b:22:09:9b:80:64:c6:d6:ba:bb:66:52:84:9f"},
                  {"build": "default", "userdebug": false, "cert_fingerprint_sha256": "32a2fc74d731105859e5a85df16d95f102d85b22099b8064c6d6babbb6652849f"}
                ]
              }
            }
          ]
        }
    """.trimIndent()

    /**
     * 解析调用方可信 origin。
     * 返回值仅可能为：浏览器特权白名单校验通过的 web origin（https://…），
     * 或普通应用的 android:apk-key-hash: origin；无法确定签名时返回空串。
     */
    fun resolveTrustedOrigin(callingAppInfo: CallingAppInfo): String {
        if (callingAppInfo.isOriginPopulated()) {
            return try {
                callingAppInfo.getOrigin(PRIVILEGED_BROWSER_ALLOWLIST).orEmpty()
            } catch (_: Throwable) {
                // 不在白名单 / 白名单格式漂移 / origin 缺失：fail-closed，按普通应用处理
                apkKeyHashOrigin(callingAppInfo)
            }
        }
        return apkKeyHashOrigin(callingAppInfo)
    }

    /** 是否为浏览器委派的 web origin（非 android:apk-key-hash 且为 https） */
    fun isBrowserOrigin(origin: String): Boolean =
        origin.startsWith("https://")

    /** 普通应用固定颁发 apk-key-hash origin（签名证书 SHA-256，base64url 无填充） */
    private fun apkKeyHashOrigin(callingAppInfo: CallingAppInfo): String {
        return try {
            val signer = callingAppInfo.signingInfo?.apkContentsSigners?.firstOrNull()
                ?: return ""
            val digest = MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
            APK_KEY_HASH_PREFIX + Base64.encodeToString(
                digest,
                Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING
            )
        } catch (_: Throwable) {
            ""
        }
    }
}
