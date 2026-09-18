package com.keepasskey.app.passkey

import androidx.credentials.provider.CallingAppInfo
import com.keepasskey.app.security.CallerCertDigests
import java.security.MessageDigest
import kotlin.io.encoding.Base64

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
     * URL-Safe 无 Padding Base64（WebAuthn apk-key-hash 语义，对齐 android.util.Base64
     * 的 URL_SAFE or NO_PADDING or NO_WRAP；ABSENT_OPTIONAL 解码兼容有/无 padding 输入）。
     */
    private val Base64UrlNoPadding = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    /**
     * 内置浏览器特权白名单（官方 `getOrigin` 要求的 JSON 形态）。
     *
     * **单一真相源**：不再手抄字面量，而是由 [com.keepasskey.app.security.BrowserSigningFingerprints.TRUSTED]
     * （已取证指纹表：每条均注明来源与核实方式，改写须先取证据）派生，指纹统一为
     * 大写冒号分隔形式。
     *
     * 这样同时解决两个历史问题：
     * 1. ISSUE-P3-88 的「同一指纹两种写法互相打架」——派生后不存在第二份副本；
     * 2. 通道口径不一致（自动填充通道已收录 Firefox，而 CM 通道仅 Chrome）——
     *    两通道自此共用同一张已取证指纹表。
     *
     * 用户显式启用的其它浏览器（安装包现场读取签名）由
     * [com.keepasskey.app.data.repository.PasskeyPrivilegedBrowserStore] 追加合并。
     */
    val builtInAllowlistJson: String by lazy { buildAllowlistJson(builtInAllowlistEntries()) }

    /**
     * 内置白名单的**结构化形态**（包名 → 大写冒号分隔指纹），供用户白名单与之合并后
     * 一次性构造 JSON（避免「解析 JSON 再合并」的额外依赖与失败面）。
     */
    internal fun builtInAllowlistEntries(): Map<String, List<String>> =
        com.keepasskey.app.security.BrowserSigningFingerprints.TRUSTED
            .mapValues { (_, fingerprints) -> fingerprints.map { toColonSeparatedUpper(it) } }

    /** 解析调用方可信 origin。
     * 返回值仅可能为：浏览器特权白名单校验通过的 web origin（https://…），
     * 或普通应用的 android:apk-key-hash: origin；无法确定签名时返回空串。
     *
     * @param allowlistJson 传入的白名单（内置已取证条目 + 用户显式启用的浏览器）；
     *   缺省为 [builtInAllowlistJson]，便于无 Context 的调用点与单测直接使用。
     */
    fun resolveTrustedOrigin(
        callingAppInfo: CallingAppInfo,
        allowlistJson: String = builtInAllowlistJson
    ): String {
        if (callingAppInfo.isOriginPopulated()) {
            return try {
                callingAppInfo.getOrigin(allowlistJson).orEmpty()
            } catch (_: Throwable) {
                // 不在白名单 / 白名单格式漂移 / origin 缺失：fail-closed，按普通应用处理
                apkKeyHashOrigin(callingAppInfo)
            }
        }
        return apkKeyHashOrigin(callingAppInfo)
    }

    /** 无冒号 hex → 大写冒号分隔（`getOrigin` 白名单的规范写法） */
    internal fun toColonSeparatedUpper(fingerprint: String): String {
        val hex = fingerprint.replace(":", "").uppercase()
        return hex.chunked(2).joinToString(":")
    }

    /**
     * 由「包名 → 指纹集合」构造 `getOrigin` 白名单 JSON（确定性输出，便于断言）。
     *
     * **手写拼接而非 JSON 库**：① 该串是安全关键面，形状固定且字段极少，手写可读可测；
     * ② 避免依赖 `org.json`——它在宿主单测里是未实现的桩，用库会让本函数无法被 JVM 用例覆盖。
     * 包名与指纹由系统/取证表提供，字符集受限（`[A-Za-z0-9._:-]`），仍统一做最小转义兜底。
     */
    internal fun buildAllowlistJson(entries: Map<String, List<String>>): String {
        val sb = StringBuilder(256)
        sb.append("{\"apps\":[")
        var firstApp = true
        for ((packageName, fingerprints) in entries) {
            if (!firstApp) sb.append(',')
            firstApp = false
            sb.append("{\"type\":\"android\",\"info\":{\"package_name\":\"")
                .append(escapeJson(packageName))
                .append("\",\"signatures\":[")
            fingerprints.forEachIndexed { index, fingerprint ->
                if (index > 0) sb.append(',')
                sb.append("{\"build\":\"default\",\"userdebug\":false,\"cert_fingerprint_sha256\":\"")
                    .append(escapeJson(fingerprint))
                    .append("\"}")
            }
            sb.append("]}}")
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun escapeJson(value: String): String {
        val sb = StringBuilder(value.length + 8)
        value.forEach { c ->
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c.code < 0x20 -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** 是否为浏览器委派的 web origin（非 android:apk-key-hash 且为 https） */
    fun isBrowserOrigin(origin: String): Boolean =
        origin.startsWith("https://")

    /**
     * Android 系统自身包名。
     *
     * ISSUE-P2-72：`Activity.getCallingPackage()` 在「系统经 PendingIntent 拉起」场景返回
     * `"android"`（或 null）——把该值当作调用方归属，会落出 `android://android` 这种
     * 无意义（且可被同包名侧载应用命中）的绑定，故一律排除。
     */
    const val SYSTEM_PACKAGE_ANDROID = "android"

    /**
     * ISSUE-P2-72：**系统背书**的调用方包名。
     *
     * 仅接受平台经 [CallingAppInfo] 下发的包名（由系统在凭据请求中背书），
     * 排除系统自身包名（[SYSTEM_PACKAGE_ANDROID]）与空白值；取不到即返回 null。
     *
     * **禁止回退**：不得回退 `Activity.getCallingPackage()`（PendingIntent 场景下为 `android`）
     * 或本应用包名——前者制造 `android://android` 绑定，后者把归属错误地写成我们自己。
     */
    fun systemAttestedPackageName(callingAppInfo: CallingAppInfo?): String? =
        callingAppInfo?.packageName
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != SYSTEM_PACKAGE_ANDROID }

    /**
     * ISSUE-P2-72：`clientDataJSON.androidPackageName` 的归属值。
     *
     * 按 [candidates] 顺序取首个「非空白且非系统包名」者；全部不合格时返回 null——
     * 调用方应**省略该字段**，而不是回退为本应用包名（那是对 RP 的虚假归属）。
     */
    fun clientDataAndroidPackageName(vararg candidates: String?): String? =
        candidates.asSequence()
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotEmpty() && it != SYSTEM_PACKAGE_ANDROID }

    /**
     * 普通应用固定颁发 apk-key-hash origin（签名证书 SHA-256，base64url 无填充）。
     *
     * ISSUE-P3-93：取**主 origin**（有序集合的首项，即当前有效签名者）；多签名者 / 签名轮换期
     * 的完整 origin 集合见 [apkKeyHashOrigins]（需要按 origin 匹配的路径应遍历该集合）。
     */
    private fun apkKeyHashOrigin(callingAppInfo: CallingAppInfo): String =
        apkKeyHashOrigins(callingAppInfo).firstOrNull().orEmpty()

    /**
     * ISSUE-P3-93（审计 F-19）：调用方**全部**签名者对应的 apk-key-hash origin。
     *
     * 取值口径：`apkContentsSigners`（当前有效，在前）+ `signingCertificateHistory`（历史轮换，在后），
     * 逐个计算 `android:apk-key-hash:<base64url(sha256(cert))>`。只取首个会随系统返回顺序变化，
     * 在签名轮换期把合法调用方误判为未授权（方向 fail-closed）。
     * 全部签名者不可读时返回空列表（调用方按 fail-closed 处理）。
     */
    fun apkKeyHashOrigins(callingAppInfo: CallingAppInfo): List<String> {
        return try {
            val info = callingAppInfo.signingInfo
            val signers = info.apkContentsSigners.orEmpty().toList() +
                info.signingCertificateHistory.orEmpty().toList()
            signers.map { signer ->
                APK_KEY_HASH_PREFIX + Base64UrlNoPadding.encode(sha256(signer.toByteArray()))
            }.distinct()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * 调用方签名证书 SHA-256 十六进制摘要（大写、无冒号，ISSUE-P2-02）。
     * 供 [DigitalAssetLinksVerifier] 与 DAL 声明中的 `sha256_cert_fingerprints` 比对；
     * 无法确定签名时返回 null（调用方按 fail-closed 处理）。
     *
     * ISSUE-P3-93：本方法只返回**首个**摘要，语义已收敛为「主摘要（展示/记录用）」；
     * 放行判定请改用 [certDigests]（遍历全部签名者，任一命中即通过）。
     */
    fun certSha256Hex(callingAppInfo: CallingAppInfo): String? = certDigests(callingAppInfo).primary

    /**
     * ISSUE-P3-93（审计 F-19）：调用方**全部**签名证书摘要。
     *
     * 缺陷形态：此前各处只取 `apkContentsSigners?.firstOrNull()`，签名轮换期结果**随系统返回顺序
     * 变化**（应用同时持有当前与历史签名者）→ 白名单 / DAL 可能误判未授权。
     *
     * 取值口径：`apkContentsSigners`（当前有效，在前）+ `signingCertificateHistory`（历史轮换，在后），
     * 去重后归一化为大写十六进制。平台保证 `signingInfo` 非空；数组仍可能为空 → 空集合（fail-closed）。
     * 无过去签名证书时 `signingCertificateHistory` 与 `apkContentsSigners` 内容相同，去重后无重复。
     */
    fun certDigests(callingAppInfo: CallingAppInfo): CallerCertDigests {
        return try {
            val info = callingAppInfo.signingInfo
            val signers = info.apkContentsSigners.orEmpty().toList() +
                info.signingCertificateHistory.orEmpty().toList()
            CallerCertDigests.of(signers.map { sha256(it.toByteArray()).toHex() })
        } catch (_: Throwable) {
            CallerCertDigests.EMPTY
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
