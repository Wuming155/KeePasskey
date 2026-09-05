package com.keepasskey.app.passkey

/**
 * 严格域名与应用包名匹配工具（解决 P2-14 凭据跨域嗅探安全漏洞）。
 * 纯函数设计，零 Android 框架依赖，保证确定性与跨平台可测试性。
 */
object DomainMatcher {

    /**
     * 从 URL、Origin 或混合字符串中提取归一化小写的主机名/域名 (Domain/Host)。
     * 自动处理：
     * 1. 协议头剔除（如 https://, http://, android:// 等）；
     * 2. 认证凭据剔除（user:pass@）；
     * 3. 路径、查询参数、片段标识符截断（/, ?, #）；
     * 4. 端口号剔除（包括标准端口及 IPv6 方括号端口形式）；
     * 5. 非 URL 纯域名输入原样小写返回。
     */
    fun extractDomain(url: String): String {
        var s = url.trim().lowercase()
        if (s.isEmpty()) return ""

        // 去除 scheme (如 https://, http://, webauthn:// 等)
        val schemeIdx = s.indexOf("://")
        if (schemeIdx >= 0) {
            s = s.substring(schemeIdx + 3)
        }

        // 去除 path、query、fragment
        val slashIdx = s.indexOf('/')
        if (slashIdx >= 0) {
            s = s.substring(0, slashIdx)
        }
        val questionIdx = s.indexOf('?')
        if (questionIdx >= 0) {
            s = s.substring(0, questionIdx)
        }
        val hashIdx = s.indexOf('#')
        if (hashIdx >= 0) {
            s = s.substring(0, hashIdx)
        }

        // 去除认证信息 user:pass@host
        val atIdx = s.lastIndexOf('@')
        if (atIdx >= 0) {
            s = s.substring(atIdx + 1)
        }

        // 去除端口号
        if (s.startsWith("[") && s.contains("]")) {
            val closeBracket = s.indexOf(']')
            val colonAfter = s.indexOf(':', closeBracket)
            if (colonAfter >= 0) {
                s = s.substring(0, colonAfter)
            }
            s = s.removeSurrounding("[", "]")
        } else {
            val colonIdx = s.indexOf(':')
            if (colonIdx >= 0) {
                s = s.substring(0, colonIdx)
            }
        }

        return s.trim()
    }

    /**
     * 严格域名匹配逻辑。
     * 双方经 [extractDomain] 归一化后：必须完全相等，或 [originHost] 以 ".$rpIdOrDomain" 结尾（严格点号标签边界）。
     *
     * 严禁使用模糊双向 contains，防止 "evilgithub.com" 恶意冒充 "github.com"。
     * F5 整改：凭据侧域名（[rpIdOrDomain]）须满足公共后缀下限约束（[isRegistrableDomain]）——
     * 单标签（如 "io"）或多标签公共后缀（如 "com.cn"）一律拒绝，杜绝经恶意 kdbx 导入植入
     * 公共后缀条目后冒充整条 TLD 的钓鱼匹配（WebAuthn 规范亦禁止公共后缀作 RP ID）。
     *
     * @param rpIdOrDomain 凭据中记录的 RP ID 或条目域名（如 "github.com"）
     * @param originHost 调用方真实来源主机名（如 "github.com", "login.github.com"）
     */
    fun isDomainMatch(rpIdOrDomain: String, originHost: String): Boolean {
        val d1 = extractDomain(rpIdOrDomain)
        val d2 = extractDomain(originHost)
        if (d1.isEmpty() || d2.isEmpty()) return false
        if (!isRegistrableDomain(d1)) return false

        return d1 == d2 || d2.endsWith(".$d1")
    }

    /**
     * 判断主机名是否为可注册域名：至少含一个点号，且不属于内置多标签公共后缀集合。
     * 内置集合为常见多级公共后缀的最小子集（非完整 PSL），覆盖主流国家/地区二级注册域。
     */
    private fun isRegistrableDomain(host: String): Boolean {
        if (!host.contains('.')) return false
        return host !in MULTILABEL_PUBLIC_SUFFIXES
    }

    /**
     * Android 应用包名匹配逻辑。
     * F1 整改（CWE-284 水平越权）：scheme 剥离后必须**精确相等**。
     * Android 包名之间不存在任何父子信任关系——"evil.com.victim.app" 与 "com.victim.app"
     * 是可由任意开发者分别注册的两个独立应用，任何后缀/前缀包含匹配都会让无关包名
     * 命中他人凭据（跨应用凭据读取）。对齐 Android 官方 Credential Provider 指南的包名精确匹配要求。
     * L1 整改保留：支持剥离条目 url 中的 android:// 等 scheme 前缀，
     * 使入库时记录为 android://<包名> 的凭据可与调用包名正确匹配。
     */
    fun isPackageMatch(entryPackageHint: String, callingPackage: String): Boolean {
        var p1 = entryPackageHint.trim().lowercase()
        val p2 = callingPackage.trim().lowercase()
        if (p1.isEmpty() || p2.isEmpty()) return false

        // 剥离 scheme 前缀（android://<包名> → <包名>），并截断路径尾部
        val schemeIdx = p1.indexOf("://")
        if (schemeIdx >= 0) {
            p1 = p1.substring(schemeIdx + 3)
            val slashIdx = p1.indexOf('/')
            if (slashIdx >= 0) p1 = p1.substring(0, slashIdx)
        }

        return p1 == p2
    }

    /**
     * 从条目 url 中提取 android:// 绑定的包名（F4 整改）。
     * 仅接受 android scheme；非 android 绑定（如浏览器创建的 https://<rpId> 条目）返回 null，
     * 确保普通应用无法通过注册与 Web 域同形的包名冒领 Web 绑定凭据。
     */
    fun extractAndroidBoundPackage(entryUrl: String): String? {
        val schemeIdx = entryUrl.indexOf("://")
        if (schemeIdx <= 0) return null
        if (!entryUrl.substring(0, schemeIdx).equals("android", ignoreCase = true)) return null
        var p = entryUrl.substring(schemeIdx + 3)
        val slashIdx = p.indexOf('/')
        if (slashIdx >= 0) p = p.substring(0, slashIdx)
        val trimmed = p.trim().lowercase()
        return trimmed.ifEmpty { null }
    }

    /** 常见多级公共后缀最小子集（F5 整改，非完整 PSL；命中即视为不可注册域） */
    private val MULTILABEL_PUBLIC_SUFFIXES = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk",
        "com.cn", "net.cn", "org.cn", "gov.cn",
        "co.jp", "ne.jp", "or.jp", "ac.jp",
        "com.hk", "org.hk", "com.tw", "com.au", "net.au", "org.au",
        "co.nz", "net.nz", "org.nz", "com.br", "com.mx",
        "co.in", "net.in", "org.in", "com.sg", "com.tr",
        "com.ar", "co.za", "com.pl", "com.ru", "com.ua"
    )
}
