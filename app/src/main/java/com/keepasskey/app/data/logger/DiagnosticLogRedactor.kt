package com.keepasskey.app.data.logger

/**
 * 诊断日志脱敏器。
 *
 * 稳定版同样会把运行事件写入可导出的诊断缓冲，因此脱敏必须发生在**写入时**：环形缓冲、
 * 设置页预览、logcat 直出与导出文件都只能拿到脱敏后的文本。导出边界再执行一次（规则幂等），
 * 作为第二道防线。
 *
 * 规则只识别结构化标识：URI（含 `content://` 等 SAF 目标）、邮箱、裸主机名（含端口与
 * `internal` / `lan` / `corp` 等内网后缀）、IPv4/IPv6、多级目录路径、库/密钥文件名，以及
 * 形如 `token=` / `accessKey=` 的密值赋值。URL 必须先于邮箱处理，避免内嵌账号被二次匹配后
 * 残留 scheme 碎片；主机名必须先于文件名处理；路径先于文件名，避免只遮住最后一段。
 *
 * 诊断栈帧（`\tat 类.方法(文件:行)`、`Caused by: 类型`、截断标记）及顶层异常类型行（裸
 * 类名）由 `com.keepasskey.core.log.diagnosticText` 构造，只含类名、方法名、文件名与行号，
 * 不含外部输入或局部变量；但通用主机名规则会把包名 `com.keepasskey.app` 里的 `.app` 误判为
 * 主机名、文件名规则会把 `.xml` 包名片段当文件名，抹掉 release 经 retrace 还原所必需的类名。
 * 故这些行整行豁免，其余文本逐行照常脱敏。
 * 调用方 message 仍是应用内固定文案（非外部原文），豁免面不会成为绕过脱敏的入口。
 *
 * 正则不能识别任意自然语言中的敏感内容，调用点仍须遵守既有铁律：不写入主密码、密钥、
 * 用户字段、库别名、异常 message 或其它外部原文，只留类型、布尔判定与计数。
 */
internal object DiagnosticLogRedactor {

    private const val REDACTED_URL = "<redacted-url>"
    private const val REDACTED_ACCOUNT = "<redacted-account>"
    private const val REDACTED_HOST = "<redacted-host>"
    private const val REDACTED_PATH = "<redacted-path>"
    private const val REDACTED_FILE = "<redacted-file>"
    private const val REDACTED_SECRET = "<redacted-secret>"

    /** 允许中文路径/文件名，但在中文标点与空白处收尾，避免把整句说明一并吞掉 */
    private const val TAIL = """[^\s，。；、（）「」"'<>]+"""

    private val RULES: List<Pair<Regex, String>> = listOf(
        Regex("""[A-Za-z][A-Za-z0-9+.-]*://$TAIL""") to REDACTED_URL,
        Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""") to REDACTED_ACCOUNT,
        // IPv6：方括号写法（含端口）+ 压缩写法（含 `::`）+ 完整 8 组写法。
        // 方括号分支要求地址含 `::` 或至少 3 个冒号，避免把 `[12:34:56.789]` 时刻误判为地址。
        Regex("""\[(?=[0-9A-Fa-f:.]*::)[0-9A-Fa-f:.]{2,45}](?::\d{1,5})?""") to REDACTED_HOST,
        Regex("""\[(?:[0-9A-Fa-f]{1,4}:){3,7}[0-9A-Fa-f]{1,4}](?::\d{1,5})?""") to REDACTED_HOST,
        Regex("""(?<![\w:])(?=[0-9A-Fa-f:]*::)[0-9A-Fa-f:]{3,45}(?![\w:])""") to REDACTED_HOST,
        Regex("""(?<![\w:])(?:[0-9A-Fa-f]{1,4}:){4,7}[0-9A-Fa-f]{1,4}(?![\w:])""") to REDACTED_HOST,
        // 主机名白名单补内网常用后缀，并把端口一并吞掉（原规则只遮主机、端口残留）
        Regex(
            """\b(?:[a-z0-9-]+\.)+(?:com|net|org|io|cn|dev|local|lan|internal|intranet|corp|home|ru|de|uk|jp|xyz|top|cloud|info|biz|me|app|site|online|tech|edu|gov|int|co|test|invalid)\b(?::\d{1,5})?"""
        ) to REDACTED_HOST,
        Regex("""\b(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?\b""") to REDACTED_HOST,
        // 多级目录（POSIX / 盘符）：文件名规则遇 `/` 会被截断，只剩目录名出域。
        Regex("""(?<![\w:/])(?:[A-Za-z]:)?(?:/[\w.@-]+){2,}/?""") to REDACTED_PATH,
        Regex(
            """[^\s/\\,;:，。；、（）「」"'<>]+\.(?:kdbx|keyx|kdb|xml|csv|txt|json|bak|key|pem|p12|pfx)""",
            RegexOption.IGNORE_CASE
        ) to REDACTED_FILE,
        Regex(
            """(?i)\b(?:token|password|passwd|passphrase|secret|client[_-]?secret|api[_-]?key|access[_-]?key|secret[_-]?key|private[_-]?key|credential|authorization|bearer)\b\s*[=:]\s*[^\s，。；、（）「」"'<>]+"""
        ) to REDACTED_SECRET
    )

    /** 诊断栈帧 / cause 类型行 / suppressed 类型行 / 截断标记：只含定位信息，整行不参与通用标识脱敏。 */
    private fun isDiagnosticFrameLine(line: String): Boolean =
        line.startsWith("\tat ") ||
            line.startsWith("Caused by: ") ||
            line.startsWith("\tSuppressed: ") ||
            line.startsWith("... ")

    /** Java 二进制类名（含 `$` 内部类与 R8 混淆后的短名）。 */
    private val FQCN = Regex("""[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+""")

    /**
     * 顶层异常类型行：`diagnosticText` 输出的裸类名，紧跟 `\tat` 栈帧，属定位信息。
     *
     * 不豁免时主机名规则会把 `com.keepasskey.app...` 的 `.app` 当主机名、文件名规则会把
     * `org.apache.harmony.xml...` 的 `.xml` 包名当文件名，抹掉 retrace 必需的类名
     * （ENG-20261111-01 的导出日志实测）。
     *
     * 只在「整行就是一个类名且下一行是本栈帧」时豁免：普通日志行里的主机名、文件路径仍照常
     * 脱敏；无栈帧的裸类名同样按普通文本处理（保守优先）。
     */
    private fun isExceptionTypeLine(line: String, nextLine: String?): Boolean =
        nextLine != null &&
            (nextLine.startsWith("\tat ") || nextLine.startsWith("\t... ")) &&
            FQCN.matches(line)

    /**
     * 幂等：占位符不含任何规则所匹配的结构，豁免行不会被改写，重复调用不改变内容。
     * 逐行处理与整段处理等价——所有规则的待匹配片段都以 `\s` 收尾，本就不跨行；
     * 仅异常类型行的判定需要向后看一行。
     */
    fun redact(text: String): String {
        val lines = text.split('\n')
        return lines.mapIndexed { index, line ->
            if (isDiagnosticFrameLine(line) || isExceptionTypeLine(line, lines.getOrNull(index + 1))) {
                line
            } else {
                RULES.fold(line) { acc, (pattern, replacement) -> acc.replace(pattern, replacement) }
            }
        }.joinToString("\n")
    }
}
