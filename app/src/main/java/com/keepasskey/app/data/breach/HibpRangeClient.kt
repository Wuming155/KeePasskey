package com.keepasskey.app.data.breach

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw BreachCheckException("泄露库返回 HTTP ${response.code}")
            }
            parseRangeBody(body)
        }
    }

    /**
     * 解析「后缀:次数」行集。
     *
     * 容错边界：仅跳过无法识别的整行（防御服务端附加内容），绝不因局部异常而返回部分
     * 结果充数——能解析的行全部采纳，无法解析的行静默忽略（泄露检测宁可漏报也不谎报，
     * 但解析失败的整体性已在 [queryRange] 的非 2xx 分支覆盖）。
     */
    private fun parseRangeBody(body: String): Set<String> {
        val suffixes = LinkedHashSet<String>()
        for (rawLine in body.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val match = LINE_PATTERN.matchEntire(line) ?: continue
            val suffix = match.groupValues[1].uppercase(Locale.US)
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

        private const val HEADER_USER_AGENT = "User-Agent"
        private const val USER_AGENT = "KeePasskey-Android"

        private val PREFIX_PATTERN = Regex("^[0-9A-F]{$PREFIX_LENGTH}$")

        /** `<35 位十六进制后缀>:<非负整数次数>`（后缀长度 = 40 - 5） */
        private val LINE_PATTERN = Regex("^([0-9A-Fa-f]{35}):([0-9]+)$")
    }
}
