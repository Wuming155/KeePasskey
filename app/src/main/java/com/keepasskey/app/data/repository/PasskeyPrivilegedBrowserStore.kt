package com.keepasskey.app.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.keepasskey.app.passkey.CallingOriginResolver
import com.keepasskey.core.log.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通行密钥（CM 通道）**特权浏览器白名单**的用户可管理部分。
 *
 * ## 为什么需要
 *
 * CM 通道只在调用方通过官方 `CallingAppInfo.getOrigin(allowlist)` 校验时才能拿到 web origin；
 * 未列入白名单的浏览器一律退化为 `android:apk-key-hash` origin，于是**非 Chrome 浏览器上
 * 通行密钥完全不出候选**（条目按 `https://<rpId>` 绑定，包名维度不匹配）。
 *
 * 白名单由两部分组成：
 * 1. **内置**已取证条目：[CallingOriginResolver.builtInAllowlistJson]（源自
 *    `BrowserSigningFingerprints.TRUSTED`，每条指纹都注明来源与核实方式）；
 * 2. **用户显式启用**的其它浏览器（本类）：指纹不臆写，而是**现场读取已安装 APK 的签名证书
 *    SHA-256** —— 与系统该校验逐字同源，故不存在「抄错一位即永不匹配」的风险。
 *
 * ## 安全语义（如实声明）
 *
 * 启用某个应用等于允许它**以任意 web origin 发起凭据请求**（这正是浏览器委派的本质）。
 * 因而：默认**空集**；每项须用户单独显式开启；列表只呈现「能处理 https VIEW 意图」的应用
 * （浏览器候选），且指纹取自该包自身签名 ⇒ 换签名/同包名顶替会立刻不再匹配（fail-closed）。
 */
@Singleton
class PasskeyPrivilegedBrowserStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** 已安装的浏览器候选（含启用状态） */
    data class BrowserApp(
        val packageName: String,
        val label: String,
        val fingerprintSha256: String,
        val enabled: Boolean
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 供 `CallingAppInfo.getOrigin` 使用的**合并**白名单 JSON（内置已取证 + 用户启用项）。
     *
     * 直接在结构化形态上合并后一次构造：不做「解析 JSON 再拼接」，因而没有解析失败面，
     * 也不必依赖 `org.json`（宿主单测不可用）。
     */
    fun allowlistJson(): String {
        val userEntries = userEntries()
        if (userEntries.isEmpty()) return CallingOriginResolver.builtInAllowlistJson
        val merged = LinkedHashMap(CallingOriginResolver.builtInAllowlistEntries())
        userEntries.forEach { (packageName, fingerprints) -> merged[packageName] = fingerprints }
        return CallingOriginResolver.buildAllowlistJson(merged)
    }

    /** 已安装浏览器候选列表（用于设置页呈现；读取失败的应用跳过，不虚构） */
    fun installedBrowsers(): List<BrowserApp> {
        val enabled = enabledFingerprints()
        return resolveBrowserPackages().mapNotNull { (packageName, label) ->
            val fingerprint = signingFingerprint(packageName) ?: return@mapNotNull null
            BrowserApp(
                packageName = packageName,
                label = label,
                fingerprintSha256 = fingerprint,
                enabled = enabled.containsKey(packageName)
            )
        }.sortedBy { it.label.lowercase() }
    }

    /**
     * 启用 / 停用某个浏览器的特权资格。
     *
     * @return true=已生效；false=启用时读取不到签名指纹（**不写入**，保持未启用，fail-closed）
     */
    fun setEnabled(packageName: String, enabled: Boolean): Boolean {
        val pkg = packageName.trim().lowercase()
        if (pkg.isEmpty()) return false
        if (!enabled) {
            prefs.edit().remove(KEY_PREFIX + pkg).apply()
            return true
        }
        val fingerprint = signingFingerprint(pkg) ?: run {
            AppLog.w(TAG, "读取不到该应用的签名指纹，保持未启用（fail-closed）")
            return false
        }
        prefs.edit().putString(KEY_PREFIX + pkg, fingerprint).apply()
        return true
    }

    /** 清空全部用户启用项（内置已取证条目不受影响） */
    fun clear() {
        prefs.edit().clear().apply()
    }

    // ---------------- 内部实现 ----------------

    /** 用户启用项：包名 → 启用当时读取到的签名指纹（大写冒号分隔） */
    private fun enabledFingerprints(): Map<String, String> =
        prefs.all.entries
            .mapNotNull { (key, value) ->
                if (!key.startsWith(KEY_PREFIX)) return@mapNotNull null
                val pkg = key.removePrefix(KEY_PREFIX)
                val fingerprint = value as? String ?: return@mapNotNull null
                pkg to fingerprint
            }
            .toMap()

    private fun userEntries(): Map<String, List<String>> =
        enabledFingerprints().mapValues { (_, fingerprint) -> listOf(fingerprint) }

    /**
     * 浏览器候选：能处理 `https` VIEW 意图的应用（无需 QUERY_ALL_PACKAGES 权限——
     * 意图查询本身即被包可见性规则允许），排除本应用自身。
     */
    private fun resolveBrowserPackages(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(BROWSER_PROBE_URL))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val resolved = try {
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        } catch (t: Throwable) {
            AppLog.w(TAG, "查询已安装浏览器失败: ${t.javaClass.simpleName}")
            return emptyList()
        }
        val packages = LinkedHashMap<String, String>()
        for (info in resolved) {
            val pkg = info.activityInfo?.packageName?.trim()?.lowercase() ?: continue
            if (pkg == context.packageName) continue
            packages[pkg] = info.loadLabel(context.packageManager).toString().ifBlank { pkg }
        }
        return packages.entries.map { it.key to it.value }
    }

    /** 该包**当前**签名证书 SHA-256（大写冒号分隔）；不可读取返回 null（调用方 fail-closed） */
    private fun signingFingerprint(packageName: String): String? = try {
        val info = context.packageManager.getPackageInfo(
            packageName,
            PackageManager.GET_SIGNING_CERTIFICATES
        )
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull()
        if (signer == null) {
            null
        } else {
            MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
                .joinToString(":") { "%02X".format(it) }
        }
    } catch (t: Throwable) {
        AppLog.w(TAG, "读取应用签名失败: ${t.javaClass.simpleName}")
        null
    }

    private companion object {
        const val TAG = "PasskeyPrivilegedBrowser"
        const val PREFS_NAME = "keepasskey_privileged_browsers"
        const val KEY_PREFIX = "pkg:"

        /** 探测「是否浏览器」的意图 URL（不联网，仅用于包可见性查询） */
        const val BROWSER_PROBE_URL = "https://example.com"
    }
}
