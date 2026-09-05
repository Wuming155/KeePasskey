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
     *
     * @param rpIdOrDomain 凭据中记录的 RP ID 或条目域名（如 "github.com"）
     * @param originHost 调用方真实来源主机名（如 "github.com", "login.github.com"）
     */
    fun isDomainMatch(rpIdOrDomain: String, originHost: String): Boolean {
        val d1 = extractDomain(rpIdOrDomain)
        val d2 = extractDomain(originHost)
        if (d1.isEmpty() || d2.isEmpty()) return false

        return d1 == d2 || d2.endsWith(".$d1")
    }

    /**
     * Android 应用包名匹配逻辑。
     * 双方必须完全相等，或以点号 '.' 边界保持父子包名包含关系。
     * L1 整改：支持剥离条目 url 中的 android:// 等 scheme 前缀，
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

        return p1 == p2 || p1.endsWith(".$p2") || p2.endsWith(".$p1")
    }
}
