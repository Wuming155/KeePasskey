package com.keepasskey.app.security

import java.util.Locale

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
 * ## 通道口径差异（第四轮复核 `NEW-B09-02`，2026-09-16）
 *
 * 本对象服务**自动填充通道**；`org.mozilla.firefox` 两条指纹是该通道按「来源 2」扩列的。
 * **CM（Credential Manager）通道的浏览器白名单 `CallingOriginResolver.PRIVILEGED_BROWSER_ALLOWLIST`
 * 未收录 Firefox（仅 `com.android.chrome`）** ⇒ 同一「受信浏览器」概念在两通道口径不同，较窄的一侧
 * （CM）方向 fail-closed，不构成攻击面；两侧口径是否对齐未作裁决，且 Firefox 经 CM 通道的
 * **实际可用性未经设备侧验证**（本注不构成可用性结论）。
 *
 * 指纹格式：SHA-256 十六进制、**大写、无冒号**（与 `AutofillOriginResolver.callingAppCertSha256Hex`
 * 的输出口径一致）。
 */
object BrowserSigningFingerprints {

    /** 包名（小写）→ 已取证签名证书 SHA-256 指纹集合（大写、无冒号） */
    val TRUSTED: Map<String, Set<String>> = mapOf(
        // 来源 1：仓库内既有 passkey 白名单（CallingOriginResolver.PRIVILEGED_BROWSER_ALLOWLIST）
        // ISSUE-P3-88：原值多出一个 hex 字符（65 位）⇒ 该条目**永不匹配**（SHA-256 恒 64 位）。
        // 已按同一白名单中的**冒号分隔规范副本**收敛为此处的无冒号大写形式（非 retype），
        // 并由 `BrowserFingerprintFormatTest` 的「全部指纹恒为 64 位大写 hex」断言兜底。
        "com.android.chrome" to setOf(
            "32A2FC74D731105859E5A85DF16D95F102D85B22099B8064C6D6BABB6652849F",
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
     *
     * ISSUE-P3-93：判定按**摘要集合**做，**任一**调用方签名摘要命中即通过——
     * 签名轮换期调用方同时持有当前与历史签名者，只比对单个摘要会随系统返回顺序误判未授权。
     * 包名大小写不敏感；集合为空 / 未取证包名 / 无任何摘要命中一律 false（fail-closed）。
     */
    fun isTrusted(callingPackage: String, certDigests: CallerCertDigests): Boolean {
        val fingerprints = TRUSTED[callingPackage.trim().lowercase(Locale.ROOT)] ?: return false
        return certDigests.anyMatch { it in fingerprints }
    }

    /**
     * 单摘要入口（兼容既有调用点与测试）：语义等价于「只含该摘要的集合」。
     * 放行判定请优先使用集合入口，避免在签名轮换期退化回「只看一个摘要」。
     */
    fun isTrusted(callingPackage: String, certSha256Hex: String?): Boolean =
        isTrusted(callingPackage, CallerCertDigests.ofSingle(certSha256Hex))
}
