package com.keepasskey.app.autofill

/**
 * Wi-Fi 填充启发（ISSUE-P3-373 AC①②，吸收 Monica `WifiAutofillAssist`）。
 *
 * Monica 行为：目标包名命中 Wi-Fi 设置类应用时，把 WIFI 条目置顶（不抓 SSID——权限取舍）。
 * 本仓无 WIFI 条目类型，以**标题 / URL 信号词**等价落地；同样是纯启发**只改排序、不改准入**
 * （不在设置清单内的包名恒零加成；无信号条目照常可见）。
 *
 * 全部为纯函数，JVM 可测；分数档常量在 [AutofillCandidateRanker]（排序面单点收口）。
 */
object WifiFillBoostPolicy {

    /**
     * Wi-Fi 设置类应用包名清单（命名常量，按需扩充）。
     *
     * 主流 ROM（AOSP / MIUI / ColorOS / OneUI / OriginOS）的 Wi-Fi 面板宿主均为
     * `com.android.settings`；`networkstack` 为 AOSP 网络栈（部分 ROM 在其中呈现
     * 网络详情 UI）。清单只做「何时给加成」的启发，误命中的代价仅是排序加成，不涉准入。
     */
    private val WIFI_SETTINGS_PACKAGES = setOf(
        "com.android.settings",
        "com.android.networkstack",
        "com.google.android.networkstack"
    )

    /**
     * 信号词（AC①：大小写不敏感、**词边界**；CJK 词按子串）：
     * 拉丁词用 `\b` 边界防「无关单词里恰好含 wifi 字母串」的误加成（如 `kuwifi…` 形态），
     * 「无线」按 CJK 子串。正则在类加载时编译一次（ISSUE-P3-172 同款：热路径不重复编译）。
     */
    private val WIFI_SIGNAL_PATTERNS = listOf(
        Regex("""\bwifi\b""", RegexOption.IGNORE_CASE),
        Regex("""\bwi-fi\b""", RegexOption.IGNORE_CASE),
        Regex("""\bssid\b""", RegexOption.IGNORE_CASE),
        Regex("""无线""")
    )

    /** 目标包名是否为 Wi-Fi 设置类应用（AC①：清单外恒 false ⇒ 恒零加成） */
    fun isWifiSettingsPackage(packageName: String): Boolean {
        val normalized = packageName.trim()
        if (normalized.isEmpty()) return false
        return normalized in WIFI_SETTINGS_PACKAGES
    }

    /**
     * 条目是否携带 Wi-Fi 信号（AC①：标题 / URL 任一命中信号词）。
     * 只读非敏感元数据，不解密任何受保护字段。
     */
    fun hasWifiSignal(title: String, url: String): Boolean {
        val haystack = "$title $url"
        if (haystack.isBlank()) return false
        return WIFI_SIGNAL_PATTERNS.any { it.containsMatchIn(haystack) }
    }
}
