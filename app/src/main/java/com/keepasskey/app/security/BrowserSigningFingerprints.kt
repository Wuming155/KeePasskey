package com.keepasskey.app.security

/**
 * 受信浏览器「包名 + 签名证书指纹」白名单（ISSUE-P1-11）。
 *
 * 背景与安全动机：传统自动填充的 `webDomain` 由**调用方应用**提供（WebView 宿主可控），
 * 仅按包名信任「受信浏览器」可被绕过——Android 不阻拦侧载一个占用**未安装**浏览器包名的 APK，
 * 攻击者即可冒领该站点的 `webDomain` 以触发候选下发。故浏览器委派必须与调用方**签名证书指纹**
 * 双重绑定，与 Credential Manager 侧 `CallingOriginResolver` 的「包名 + 证书指纹」语义对齐。
 *
 * ## 指纹取证纪律（严禁臆写常量）
 *
 * 仅收录**已核实来源**的指纹；每个包名的来源与核实方式如下（2026-09-11 核实）：
 * 1. `com.android.chrome`：来源＝本仓库既有 passkey 特权浏览器白名单
 *    `app/src/main/java/com/keepasskey/app/passkey/CallingOriginResolver.kt`
 *    的 `PRIVILEGED_BROWSER_ALLOWLIST`（其值经 H1 整改固化并已在通行密钥路径使用）。
 * 2. `org.mozilla.firefox`（正式版）/ `org.mozilla.firefox_beta`：来源＝Mozilla 官方源码文档
 *    `https://firefox-source-docs.mozilla.org/mobile/android/fenix/certificates.html`
 *    （Production / Beta 行的 SHA-256 指纹，2026-09-11 拉取）。
 *
 * 其余原「仅包名」列表中的浏览器（如 `org.mozilla.focus`、`com.brave.browser`、
 * `com.microsoft.emmx`、`com.sec.android.app.sbrowser` 等）**未取得可引用来源，一律不收录**：
 * 其 `webDomain` 归属回退到既有 DAL（assetlinks.json）校验路径 fail-closed 处置。
 * 新增浏览器须先取得权威来源证据并在此注明来源与核实方式，禁止臆写。
 *
 * 指纹格式：SHA-256 十六进制、**大写、无冒号**（与 `AutofillOriginResolver.callingAppCertSha256Hex`
 * 的输出口径一致）。
 */
object BrowserSigningFingerprints {

    /** 包名（小写）→ 已取证签名证书 SHA-256 指纹集合（大写、无冒号） */
    val TRUSTED: Map<String, Set<String>> = mapOf(
        // 来源 1：仓库内既有 passkey 白名单（CallingOriginResolver.PRIVILEGED_BROWSER_ALLOWLIST）
        "com.android.chrome" to setOf(
            "32A2FC74D731105859E5A85DF16D95F102D85B22099B8064C6D6BABBB6652849F",
            "F0FD6C5B410F25CB25C3B53346C8972FAE30F8EE7411DF910480AD6B2D60DB83"
        ),
        // 来源 2：Mozilla 官方 Fenix certificates 文档（Production）
        "org.mozilla.firefox" to setOf(
            "5004779088E7F988D5BC5CC5F8798FEBF4F8CD084A1B2A46EFD4C8EE4AEAF211"
        ),
        // 来源 2：Mozilla 官方 Fenix certificates 文档（Beta）
        "org.mozilla.firefox_beta" to setOf(
            "F562C08F30778686D2A47B858F45E9EF357083085CB2891A96C409F360E9CAB9"
        )
    )

    /**
     * 调用方是否为「包名 + 已取证签名指纹」匹配的受信浏览器。
     * 包名大小写不敏感；指纹为空 / 未取证包名 / 指纹不匹配一律 false（fail-closed）。
     */
    fun isTrusted(callingPackage: String, certSha256Hex: String?): Boolean {
        if (certSha256Hex.isNullOrBlank()) return false
        val fingerprints = TRUSTED[callingPackage.trim().lowercase()] ?: return false
        return certSha256Hex.trim().uppercase() in fingerprints
    }
}
