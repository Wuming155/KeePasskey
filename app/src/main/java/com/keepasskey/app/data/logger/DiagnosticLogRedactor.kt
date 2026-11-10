package com.keepasskey.app.data.logger

/**
 * 诊断日志脱敏器。
 *
 * 稳定版同样会把运行事件写入可导出的诊断缓冲，因此脱敏必须发生在**写入时**：环形缓冲、
 * 设置页预览、logcat 直出与导出文件都只能拿到脱敏后的文本。导出边界再执行一次（规则幂等），
 * 作为第二道防线。
 *
 * 规则只识别结构化标识：URI（含 `content://` 等 SAF 目标）、邮箱、裸主机名与 IPv4、
 * 库/密钥文件名，以及形如 `token=...` 的密值赋值。URL 必须先于邮箱处理，避免内嵌账号
 * 被二次匹配后残留 scheme 碎片；主机名必须先于文件名处理。
 *
 * 正则不能识别任意自然语言中的敏感内容，调用点仍须遵守既有铁律：不写入主密码、密钥、
 * 用户字段、库别名、异常 message 或其它外部原文，只留类型、布尔判定与计数。
 */
internal object DiagnosticLogRedactor {

    private const val REDACTED_URL = "<redacted-url>"
    private const val REDACTED_ACCOUNT = "<redacted-account>"
    private const val REDACTED_HOST = "<redacted-host>"
    private const val REDACTED_FILE = "<redacted-file>"
    private const val REDACTED_SECRET = "<redacted-secret>"

    /** 允许中文路径/文件名，但在中文标点与空白处收尾，避免把整句说明一并吞掉 */
    private const val TAIL = """[^\s，。；、（）「」"'<>]+"""

    private val RULES: List<Pair<Regex, String>> = listOf(
        Regex("""[A-Za-z][A-Za-z0-9+.-]*://$TAIL""") to REDACTED_URL,
        Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""") to REDACTED_ACCOUNT,
        Regex(
            """\b(?:[a-z0-9-]+\.)+(?:com|net|org|io|cn|dev|local|lan|ru|de|uk|jp|xyz|top|cloud|info|biz|me|app|site|online|tech|edu|gov|int|co)\b"""
        ) to REDACTED_HOST,
        Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""") to REDACTED_HOST,
        Regex(
            """[^\s/\\,;:，。；、（）「」"'<>]+\.(?:kdbx|keyx|kdb|xml|csv|txt|json|bak|key|pem|p12|pfx)""",
            RegexOption.IGNORE_CASE
        ) to REDACTED_FILE,
        Regex(
            """(?i)\b(?:token|password|passwd|secret|api[_-]?key|authorization|bearer)\b\s*[=:]\s*[^\s，。；、（）「」"'<>]+"""
        ) to REDACTED_SECRET
    )

    /** 幂等：占位符不含任何规则所匹配的结构，重复调用不会改写已脱敏文本。 */
    fun redact(text: String): String = RULES.fold(text) { acc, (pattern, replacement) ->
        acc.replace(pattern, replacement)
    }
}
