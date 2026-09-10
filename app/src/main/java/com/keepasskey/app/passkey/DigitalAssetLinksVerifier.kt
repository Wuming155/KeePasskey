package com.keepasskey.app.passkey

import com.keepasskey.core.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Digital Asset Links（DAL）远程资产声明校验器（ISSUE-P2-02）。
 *
 * ### 背景与安全目标
 * 普通应用（`android:apk-key-hash` origin）发起 Passkey 注册时，凭据侧仅有「rp.id 为可注册域名」
 * 的弱约束（[DomainMatcher.isRpIdTrustedForCreation]）——任意应用都可为任意域名生成注册响应。
 * 本校验器经网络拉取 `https://<rpId>/.well-known/assetlinks.json`，要求 RP 站点显式声明
 * 「该应用（包名 + 签名证书指纹）被授权 `delegate_permission/common.get_login_creds`」，
 * 实现 Native App 与 RP ID 的强双向绑定（Google Digital Asset Links 规范）。
 *
 * ### 离线与在线权衡（验收标准 1 的评估结论）
 * - **校验时机**：`onBeginCreateCredentialRequest` 有系统 5s 硬超时预算，且属查询阶段，
 *   在其中做网络拉取会拖慢甚至阻塞系统弹窗；故权威校验落在 [PasskeyCreateActivity]
 *   （用户显式确认创建的最终落地路径），begin-create 阶段维持现有本地约束不变。
 * - **在线优先、缓存兜底**：以带 TTL 的内存缓存吸收重复注册的重复拉取（正向 24h /
 *   负向 10min，负向短 TTL 保证站点补发声明或网络恢复后可及时收敛）。
 * - **fail-closed 策略（验收标准 3）**：网络不可用 / DAL 格式错误 / 无匹配声明一律拒绝创建；
 *   用户如确有离线注册需求，可经设置中「跳过 DAL 校验」（`skipDalVerification`，默认关闭）
 *   **显式**授权降级——该开关是用户主动操作，满足「显式用户告警/授权」替代路径，且落告警日志。
 * - 浏览器委派调用（https web origin）豁免：rp.id ↔ origin 归属已由
 *   [DomainMatcher.isDomainMatch] 严格点号边界强制，DAL 无增量安全收益。
 *
 * 纯匹配逻辑与 JSON 解析零 Android 框架依赖（对齐 [DomainMatcher] 纯函数设计），单测全覆盖。
 */
@Singleton
class DigitalAssetLinksVerifier @Inject constructor() {

    enum class DalResult {
        /** DAL 存在且存在匹配（relation + 包名 + 证书指纹）的声明 */
        VERIFIED,
        /** 声明缺失 / 包名或指纹不匹配 / JSON 格式错误 / HTTP 非 2xx */
        NOT_VERIFIED,
        /** DNS / 连接 / 读取超时等网络层失败（区别于确定性失败，便于现场排查） */
        NETWORK_UNAVAILABLE,
    }

    private data class CacheEntry(val result: DalResult, val cachedAtMs: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** 测试注入点：可 fake 时钟推进 TTL 过期（生产为系统时钟） */
    @Volatile
    internal var clockMs: () -> Long = { System.currentTimeMillis() }

    /** 测试注入点：端点覆盖（单测指向 MockWebServer；生产恒为官方 well-known 路径） */
    @Volatile
    internal var endpointOverride: ((host: String) -> String)? = null

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * 校验 `<rpId>` 站点的 DAL 是否声明授权 [callingPackage]（签名证书 [certSha256Hex]）。
     *
     * @param rpId 注册请求中的 RP ID（任意 URL/域名形态，内部归一化）
     * @param callingPackage 调用方应用包名
     * @param certSha256Hex 调用方签名证书 SHA-256（大小写与冒号格式不敏感）
     */
    suspend fun verify(rpId: String, callingPackage: String, certSha256Hex: String): DalResult {
        val host = DomainMatcher.extractDomain(rpId)
        if (host.isEmpty() || callingPackage.isBlank() || certSha256Hex.isBlank()) {
            return DalResult.NOT_VERIFIED
        }

        val key = "$host|$callingPackage|${normalizeFingerprint(certSha256Hex)}"
        val now = clockMs()
        cache[key]?.let { entry ->
            val ttl = if (entry.result == DalResult.VERIFIED) POSITIVE_TTL_MS else NEGATIVE_TTL_MS
            if (now - entry.cachedAtMs < ttl) return entry.result
            cache.remove(key, entry)
        }

        val url = endpointOverride?.invoke(host) ?: "https://$host/.well-known/assetlinks.json"
        val result = withContext(Dispatchers.IO) { fetchAndMatch(url, callingPackage, certSha256Hex) }
        cache[key] = CacheEntry(result, now)
        AppLog.i(TAG, "DAL 校验完成: result=$result")
        return result
    }

    private fun fetchAndMatch(url: String, callingPackage: String, certSha256Hex: String): DalResult {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // 声明缺失（如 404）属确定性失败，与网络故障区分
                    AppLog.w(TAG, "DAL 响应非 2xx，视为无声明")
                    return DalResult.NOT_VERIFIED
                }
                val body = response.body?.string()
                if (body.isNullOrEmpty() || body.length > MAX_BODY_BYTES) {
                    AppLog.w(TAG, "DAL 响应体缺失或超出大小上限")
                    return DalResult.NOT_VERIFIED
                }
                DalStatementMatcher.match(body, callingPackage, certSha256Hex)
            }
        } catch (t: Throwable) {
            // ISSUE-P1-10：不透传 URL（含 rpId 站点域）到日志
            AppLog.w(TAG, "DAL 拉取失败（网络不可用或超时），按 fail-closed 处理")
            DalResult.NETWORK_UNAVAILABLE
        }
    }

    companion object {
        private const val TAG = "DalVerifier"

        /** 响应体防御性上限：官方 assetlinks.json 实际远小于该值，防止恶意端点撑爆内存 */
        internal const val MAX_BODY_BYTES = 256 * 1024
        internal const val CONNECT_TIMEOUT_MS = 2_000L
        internal const val READ_TIMEOUT_MS = 2_000L
        internal const val CALL_TIMEOUT_MS = 3_000L
        internal const val POSITIVE_TTL_MS = 24 * 60 * 60 * 1000L
        internal const val NEGATIVE_TTL_MS = 10 * 60 * 1000L

        /** 指纹归一：去冒号与空白、统一大写（兼容 assetlinks.json 冒号分写与裸 hex 两种写法） */
        internal fun normalizeFingerprint(fp: String): String =
            fp.replace(":", "").replace(" ", "").trim().uppercase()
    }
}

/**
 * DAL 声明匹配核心（纯函数，零框架依赖）。
 * 文档结构：`[ { "relation": [...], "target": { "namespace", "package_name", "sha256_cert_fingerprints" } } ]`
 */
internal object DalStatementMatcher {

    /** Credential Manager 通行密钥授权关系（Google 官方约定） */
    internal const val RELATION_GET_LOGIN_CREDS = "delegate_permission/common.get_login_creds"

    fun match(json: String, expectedPackage: String, certSha256Hex: String): DigitalAssetLinksVerifier.DalResult {
        val root = MinimalJson.parse(json) as? List<*> ?: return DigitalAssetLinksVerifier.DalResult.NOT_VERIFIED
        val wantFp = DigitalAssetLinksVerifier.normalizeFingerprint(certSha256Hex)
        if (wantFp.isEmpty()) return DigitalAssetLinksVerifier.DalResult.NOT_VERIFIED

        for (statement in root) {
            val obj = statement as? Map<*, *> ?: continue

            val relations = obj["relation"] as? List<*> ?: continue
            if (relations.none { it is String && it.trim() == RELATION_GET_LOGIN_CREDS }) continue

            val target = obj["target"] as? Map<*, *> ?: continue
            if (target["namespace"] != "android_app") continue
            val pkg = (target["package_name"] as? String)?.trim() ?: continue
            if (pkg != expectedPackage.trim()) continue

            val fingerprints = target["sha256_cert_fingerprints"] as? List<*> ?: continue
            if (fingerprints.any { it is String && DigitalAssetLinksVerifier.normalizeFingerprint(it) == wantFp }) {
                return DigitalAssetLinksVerifier.DalResult.VERIFIED
            }
        }
        return DigitalAssetLinksVerifier.DalResult.NOT_VERIFIED
    }
}

/**
 * 极简 JSON 解析器（仅供 [DalStatementMatcher] 消费，纯 JVM 可测）。
 *
 * 不引入 org.json 的原因：Android 单测环境 android.jar 的 org.json 为未 mock stub，
 * 纯 JVM 单测不可用；且本工程 app 模块无既有 JSON 测试依赖。仅支持 DAL 文档所需的
 * 标准结构（对象 / 数组 / 字符串 / 数值 / 布尔 / null），解析失败一律返回 null（fail-closed）。
 */
internal object MinimalJson {

    /** 解析失败返回 null；要求输入整体为一个合法 JSON 值（允许首尾空白） */
    fun parse(text: String): Any? = try {
        val p = Parser(text)
        p.skipWs()
        val v = p.parseValue()
        p.skipWs()
        if (!p.atEnd()) null else v
    } catch (_: Throwable) {
        null
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun parseValue(): Any? {
            if (i >= s.length) fail()
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> parseNumber()
            }
        }

        private fun expect(word: String) {
            if (!s.startsWith(word, i)) fail()
            i += word.length
        }

        private fun parseObject(): Map<String, Any?> {
            i++
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return map }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') fail()
                val key = parseString()
                skipWs()
                if (i >= s.length || s[i] != ':') fail()
                i++
                skipWs()
                map[key] = parseValue()
                skipWs()
                when {
                    i < s.length && s[i] == ',' -> i++
                    i < s.length && s[i] == '}' -> { i++; return map }
                    else -> fail()
                }
            }
        }

        private fun parseArray(): List<Any?> {
            i++
            val list = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return list }
            while (true) {
                skipWs()
                list.add(parseValue())
                skipWs()
                when {
                    i < s.length && s[i] == ',' -> i++
                    i < s.length && s[i] == ']' -> { i++; return list }
                    else -> fail()
                }
            }
        }

        private fun parseString(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail()
                when (val c = s[i]) {
                    '"' -> { i++; return sb.toString() }
                    '\\' -> {
                        i++
                        if (i >= s.length) fail()
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) fail()
                                val code = s.substring(i + 1, i + 5).toIntOrNull(16) ?: fail()
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> fail()
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
        }

        private fun parseNumber(): Any {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            var isDouble = false
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '-' || s[i] == '+')) {
                if (s[i] == '.' || s[i] == 'e' || s[i] == 'E') isDouble = true
                i++
            }
            val token = s.substring(start, i)
            if (token.isEmpty() || token == "-" || token == "+") fail()
            return if (isDouble) token.toDoubleOrNull() ?: fail() else token.toLongOrNull() ?: fail()
        }

        private fun fail(): Nothing = throw IllegalArgumentException("invalid JSON at offset $i")
    }
}

