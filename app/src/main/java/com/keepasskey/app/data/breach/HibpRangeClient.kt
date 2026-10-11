package com.keepasskey.app.data.breach

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Have I Been Pwned「Pwned Passwords」范围查询客户端（TASK-47）。
 *
 * 协议：`GET <baseUrl>/range/<前缀5位>`，响应为若干行 `<35位后缀>:<出现次数>`。
 * 客户端只送出密码 SHA-1 的**前 5 位**，完整哈希与明文永不出端；比对在本地完成。
 *
 * 传输安全：明文传输防线不在本类硬编码 URL 前缀校验，而是沿用本项目的「平台层 + 传输层」
 * 双层防御（`network_security_config.xml` 全局禁明文 + 注入的 [OkHttpClient] 必须以
 * `ConnectionSpec` 排除 CLEARTEXT，见 [com.keepasskey.app.di.BreachCheckModule]），与
 * `SyncHttpClientFactory` 策略一致；信任锚仅系统 CA，不做证书固定。
 *
 * 失败语义：非 2xx 或响应体不可解析一律抛 [BreachCheckException]，绝不回落为空集合
 * （空集合的语义是「该前缀下无泄露」，谎报即等于伪造安全结论）。
 *
 * 响应体封顶（`ISSUE-P3-578`，对齐 sync 侧 `ISSUE-P0-09` 的 `SyncDownloadLimits` 双重封顶口径）：
 * 响应体内容完全由 TLS 对端控制（含取得系统信任 CA 者），OkHttp 的 `ResponseBody.string()` 会把
 * 任意大小的响应**整体物化**为 String ⇒ 超大响应直接 OOM。本类改为**有界读取**并施加两道判据：
 * ① **申报尺寸预检**（`Content-Length` 如实声明且超 [MAX_RESPONSE_BYTES] 时**不读体**直接拒绝）；
 * ② **读后兜底**（声明缺失 / chunked 或声明小于实际时，按块累积，累计越界立即拒绝）。
 * 正常 Add-Padding 响应（对齐 800–1000 条记录）远小于 100 KiB，上限留足余量。
 * 注意：本类是 app 模块，**不**复用 sync 的 `SyncDownloadLimits.readBounded`（那会抛 sync 侧
 * `SyncException.ProtocolError`）——按其语义**等价实现**后统一转抛 [BreachCheckException]。
 *
 * 隐私增强（HIBP 官方 v3「Introducing padding」）：恒发 `Add-Padding: true`，服务端把响应
 * 对齐到 800–1000 条记录，使能截获密文长度者无法据响应大小反推查询了哪个前缀；填充行的
 * `count` 恒为 0（官方原文「Padded entries always have a password count of 0 and can be
 * discarded once received」），解析时丢弃、不影响命中判定（真实泄露行 `count ≥ 1`）。
 *
 * 构造由 [com.keepasskey.app.di.BreachCheckModule] 显式装配（未标注 `@Inject`）：
 * 基址与客户端属部署期配置，交由模块集中声明便于审计与测试替换。
 */
class HibpRangeClient(
    private val httpClient: OkHttpClient,
    private val baseUrl: String
) : BreachRangeClient {

    override suspend fun queryRange(prefix: String): Set<String> = withContext(Dispatchers.IO) {
        val normalizedPrefix = prefix.uppercase(Locale.US)
        if (!PREFIX_PATTERN.matches(normalizedPrefix)) {
            throw BreachCheckException("非法的 k-匿名前缀（须为 $PREFIX_LENGTH 位十六进制）")
        }
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}$RANGE_PATH$normalizedPrefix")
            .header(HEADER_USER_AGENT, USER_AGENT)
            .header(HEADER_ADD_PADDING, ADD_PADDING_VALUE)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw BreachCheckException("泄露库返回 HTTP ${response.code}")
            }
            val body = response.body
            // 闸门①：申报尺寸预检——Content-Length（-1 = 未声明 / chunked）超限则不读体直接拒绝
            val declaredLength = body.contentLength()
            if (exceedsRangeBodyLimit(declaredLength)) {
                throw BreachCheckException(
                    "泄露库响应体超出上限（声明 $declaredLength 字节 > 上限 $MAX_RESPONSE_BYTES 字节），已拒绝接收"
                )
            }
            // 闸门②：有界流式读取——声明缺失或撒谎时按块累积封顶，堆占用恒被上限约束
            val bytes = readBounded(body)
            parseRangeBody(bytes.toString(Charsets.UTF_8))
        }
    }

    /**
     * 按块有界读取 [body]：累计字节越过 [MAX_RESPONSE_BYTES] 立即抛 [BreachCheckException]（不 OOM）。
     *
     * 与 sync 侧 SyncDownloadLimits.copyBounded 语义等价（声明缺失 / chunked / 声明值撒谎均在
     * 读取中途被拒，堆占用恒为常量缓冲），差异仅在异常类型：本类是 app 模块，转抛
     * [BreachCheckException] 而非 sync 的 `SyncException.ProtocolError`。
     */
    private fun readBounded(body: ResponseBody): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        body.byteStream().use { input ->
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (exceedsRangeBodyLimit(total)) {
                    throw BreachCheckException(
                        "泄露库响应体超出上限（> $MAX_RESPONSE_BYTES 字节），已拒绝接收"
                    )
                }
                out.write(buffer, 0, n)
            }
        }
        return out.toByteArray()
    }

    /**
     * 解析「后缀:次数」行集。
     *
     * 容错边界：仅跳过无法识别的整行（防御服务端附加内容），绝不因局部异常而返回部分
     * 结果充数——能解析的行全部采纳，无法解析的行静默忽略（泄露检测宁可漏报也不谎报，
     * 但解析失败的整体性已在 [queryRange] 的非 2xx 分支覆盖）。
     *
     * Add-Padding 语义：填充行 `count` 恒为 0，官方要求「can be discarded once received」。
     * 丢弃后不影响命中判定——真实泄露行 `count ≥ 1`，后缀若恰与某填充行重合（随机填充、
     * 概率极低）按未泄露处理比误报「已泄露」更符合 fail-open-on-padding / fail-closed-on-error
     * 的分界：填充行不是泄露证据。
     */
    private fun parseRangeBody(body: String): Set<String> {
        val suffixes = LinkedHashSet<String>()
        for (rawLine in body.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val match = LINE_PATTERN.matchEntire(line) ?: continue
            val suffix = match.groupValues[1].uppercase(Locale.US)
            // 「全 0」计数（含 "0" / "00"）＝ Add-Padding 填充行，丢弃；不去 toLong 避免大计数溢出
            val countToken = match.groupValues[2]
            if (countToken.trimStart('0').isEmpty()) continue
            if (suffix.isNotEmpty()) suffixes.add(suffix)
        }
        return suffixes
    }

    companion object {
        /** 官方 Pwned Passwords API 基址（仅 HTTPS） */
        const val DEFAULT_BASE_URL = "https://api.pwnedpasswords.com"

        /** 范围查询路径（HIBP v3 契约） */
        internal const val RANGE_PATH = "/range/"

        internal const val PREFIX_LENGTH = BreachHasher.PREFIX_LENGTH

        /**
         * 范围查询响应体上限：1 MiB（`ISSUE-P3-578`）。
         *
         * 取值依据：HIBP v3 Add-Padding 把响应对齐到 800–1000 条 `<35 位后缀>:<次数>` 记录
         * （约 40–50 KiB），合法响应远小于 100 KiB ⇒ 本上限留约 10 倍余量；同时把
         * 「TLS 对端单方面灌大响应」的物化峰值约束在 MiB 量级，远低于 Android 常见堆界。
         * 与 sync 侧 `SyncDownloadLimits` 同型，仅按本出口的合法响应量级单独定值（不跨模块共用）。
         */
        internal const val MAX_RESPONSE_BYTES: Long = 1L * 1024 * 1024

        /** 有界读取的块缓冲大小（64 KiB，与 sync 侧同量级） */
        private const val BUFFER_SIZE = 64 * 1024

        /** 长度未知（`Payload` / `Content-Length` 缺失或不可解析；OkHttp 以 `-1` 表示） */
        internal const val UNKNOWN_LENGTH: Long = -1L

        private const val HEADER_USER_AGENT = "User-Agent"
        private const val USER_AGENT = "KeePasskey-Android"

        /** HIBP v3 隐私填充请求头（官方文档化建议，非强制） */
        internal const val HEADER_ADD_PADDING = "Add-Padding"
        internal const val ADD_PADDING_VALUE = "true"

        private val PREFIX_PATTERN = Regex("^[0-9A-F]{$PREFIX_LENGTH}$")

        /** `<35 位十六进制后缀>:<非负整数次数>`（后缀长度 = 40 - 5） */
        private val LINE_PATTERN = Regex("^([0-9A-Fa-f]{35}):([0-9]+)$")

        /**
         * 响应体尺寸是否越界（纯判定，无 Android / IO 依赖，供纯 JVM 单测直测）。
         *
         * 同一函数同口径服务两道闸门：
         * - 闸门①传**申报长度**（`Content-Length`，`null` / [UNKNOWN_LENGTH] 视为未知 ⇒ 放行给读后兜底）；
         * - 闸门②传**已读累计长度**（恒非负、恒非 `null`）。
         *
         * @param bytes 待判定的字节数：`null` 与 [UNKNOWN_LENGTH] 均视为「未知」不拦
         * @param maxBytes 上限，默认 [MAX_RESPONSE_BYTES]（单测可传小值）
         * @return `true` = 越界（调用方须拒绝且不物化）
         */
        internal fun exceedsRangeBodyLimit(
            bytes: Long?,
            maxBytes: Long = MAX_RESPONSE_BYTES
        ): Boolean = bytes != null && bytes != UNKNOWN_LENGTH && bytes > maxBytes
    }
}
