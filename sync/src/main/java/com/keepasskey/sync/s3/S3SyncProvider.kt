package com.keepasskey.sync.s3

import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.model.cleanEtag
import com.keepasskey.sync.network.SyncEndpointGuard
import com.keepasskey.sync.network.SyncHttpClientFactory
import com.keepasskey.sync.network.SyncNetworkOptions
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS S3 兼容协议客户端实现。
 * 兼容 AWS S3、Cloudflare R2、MinIO、阿里云 OSS、Backblaze B2 等标准 S3 兼容存储。
 * 内部实现纯净轻量级 AWS Signature Version 4 (SigV4) 鉴权算法：
 * 1. 规范请求 (Canonical Request) 构造与 SHA-256 摘要；
 * 2. 待签名字符串 (StringToSign) 生成；
 * 3. 级联 HMAC-SHA256 派生签名密钥 (Signing Key) 与最终 Authorization Header 构造；
 * 4. 对象键 URL 编码与 SigV4 canonicalUri 严格一致；
 * 5. 首传 If-None-Match: * 原子创建与覆盖 If-Match 条件写并发控制（无 TOCTOU 竞争窗口）。
 */
class S3SyncProvider(
    private val endpoint: String,
    private val bucketName: String,
    private val region: String = "us-east-1",
    private val accessKeyId: String,
    private val secretAccessKey: String,
    /**
     * 寻址风格：false = virtual-host 风格（`bucket.endpoint`，AWS S3 等默认）；
     * true = path 风格（`endpoint/bucket`，Cloudflare R2、IP 直连端点等商业云场景开启）。
     */
    private val usePathStyle: Boolean = false,
    /**
     * Wave 14 传输安全网络选项（TLS-only 恒定 + 显式超时；证书固定已移除，走系统默认 CA 链）。
     * 由 app 层组装传入（纯数据契约，维持 sync 不依赖 app 的单向依赖）。
     */
    private val networkOptions: SyncNetworkOptions = SyncNetworkOptions(),
    // ===== TASK-45（FINDINGS P2-14）：服务端时钟偏移补偿 =====
    // 上次会话经 app 层持久化恢复的时钟偏移（服务端时间 - 本地时间，毫秒）。
    // 默认 0 = 尚未探测：fail-closed，不补偿、维持本地时间签名（与服务端无关时行为与旧版一致）。
    private val initialClockOffsetMillis: Long = 0L,
    // 偏移量刷新后的持久化回调（敏感度低，随同步凭据文件落盘即可，见 SyncCredentialsStore）。
    // 持久化失败静默容忍：本会话内存偏移仍即时生效，仅丢失跨进程记忆（KDoc 声明尽力而为）。
    private val clockOffsetUpdater: ((Long) -> Unit)? = null,
    // 测试注入口：HTTP 回环（MockWebServer）需显式传入默认规格客户端；生产恒为 null（走 TLS-only 工厂）
    client: OkHttpClient? = null
) : SyncProvider {

    // Wave 12/14 传输安全：默认经 TLS-only 工厂构建（排除 CLEARTEXT + 显式超时 + 系统 CA 链验证），
    // 仅 HTTP 回环测试需显式注入明文客户端
    private val httpClient: OkHttpClient = client ?: SyncHttpClientFactory.createSyncClient(networkOptions)

    // TASK-45：运行时时钟偏移（server - local）。@Volatile：Provider 实例可能被
    // 并发上传/下载共享（SyncCoordinator 单次同步周期内复用同一实例）。
    // 每次响应携带有效 Date 头即刷新，吸收 NTP 校正漂移。
    @Volatile
    private var clockOffsetMillis: Long = initialClockOffsetMillis

    // 上次已上报持久化的偏移量：仅当变化 ≥ 1s 才回调（HTTP Date 秒级精度，
    // 抑制相邻请求亚秒抖动导致的重复刷盘）。
    @Volatile
    private var reportedClockOffsetMillis: Long = initialClockOffsetMillis

    init {
        // ISSUE-P1-05（ZT-05）主机注入防线：桶名恒常严格按 S3 命名规则校验（与是否回环无关），
        // 杜绝 virtual-host 分支 `scheme://bucket.host/key` 经 `@ / # ?` 改写真实目标主机
        SyncEndpointGuard.validateBucketName(bucketName)
        // Wave 14 全站强制 HTTPS（生产路径 fail-fast）：显式 http:// 端点在构造期即拒绝并抛
        // 类型化 InvalidEndpointError；无 scheme 输入由 buildUrl 自动补 https://；
        // 仅测试回环（显式注入 HTTP 客户端）豁免
        if (client == null) {
            val trimmedEndpoint = endpoint.trim()
            if (trimmedEndpoint.contains("://") && !trimmedEndpoint.startsWith("https://", ignoreCase = true)) {
                throw SyncException.InvalidEndpointError(
                    "S3 端点必须使用 HTTPS（当前协议为 \"${trimmedEndpoint.substringBefore("://")}://\"）。" +
                        "明文 HTTP 已被禁止以保护凭据与密码库传输，请填写 https:// 开头的商业云服务地址"
                )
            }
            // ISSUE-P1-05（ZT-05）SSRF 构造期防线：拒绝 userinfo 注入、本地/内网保留名与
            // 字面 IP 的内网/保留网段（含 169.254.169.254 云元数据端点）；主机名的解析后
            // 网段校验由连接期 SsrfGuardDns 承担。仅测试回环（注入客户端）豁免
            SyncEndpointGuard.validateEndpointHost(endpoint, networkOptions.ssrfAllowedHosts)
        }
    }

    /**
     * TASK-26 整改：AWS SigV4 规范 URI 编码（对齐官方 `SignatureVersion4` 文档的
     * URI encode 规则）——保留集仅为 RFC 3986 unreserved 字符（`A-Za-z0-9 - _ . ~`），
     * 其余全部百分号编码（大写十六进制，空格编码为 %20 而非 +）。
     *
     * 此前实现使用 java.net.URLEncoder（表单编码语义）：`*` 属 URLEncoder 保留集不编码、
     * `~` 被强制编码为 %7E——两者均与 AWS 规范相反，含这两字符的对象键 canonicalUri
     * 与服务端期望不一致，签名必然不匹配（403 SignatureDoesNotMatch）。
     * internal 可见性仅供单元测试已知答案向量校验。
     */
    internal fun encodePath(path: String): String {
        return path.split('/').joinToString("/") { segment ->
            awsUriEncode(segment)
        }
    }

    private fun awsUriEncode(segment: String): String {
        val bytes = segment.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val isUnreserved = (c in 'A'.code..'Z'.code) ||
                (c in 'a'.code..'z'.code) ||
                (c in '0'.code..'9'.code) ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
            if (isUnreserved) {
                sb.append(c.toChar())
            } else {
                sb.append('%').append("%02X".format(c))
            }
        }
        return sb.toString()
    }

    private fun buildUrl(remotePath: String): String {
        val cleanEndpoint = endpoint.trimEnd('/')
        val cleanKey = encodePath(remotePath.trimStart('/'))
        val base = if (cleanEndpoint.contains("://")) cleanEndpoint else "https://$cleanEndpoint"
        return if (usePathStyle) {
            "$base/$bucketName/$cleanKey"
        } else {
            val scheme = base.substringBefore("://")
            val host = base.substringAfter("://")
            "$scheme://$bucketName.$host/$cleanKey"
        }
    }

    private fun getHost(url: String): String {
        val withoutScheme = url.substringAfter("://")
        return withoutScheme.substringBefore('/')
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = buildUrl("")
            executeSignedRequest(
                send = { signDate ->
                    val headers = signV4("HEAD", url, EMPTY_SHA256, signDate)
                    val requestBuilder = Request.Builder().url(url).head()
                    headers.forEach { (k, v) -> requestBuilder.header(k, v) }
                    httpClient.newCall(requestBuilder.build()).execute()
                },
                consume = { response ->
                    when {
                        response.isSuccessful || response.code == 404 -> Unit
                        response.code == 401 || response.code == 403 ->
                            throw SyncException.AuthenticationError("S3 鉴权失败 (${response.code})")
                        else -> throw SyncException.ProtocolError(response.code, response.message)
                    }
                }
            )
        }
    }

    override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> = withContext(Dispatchers.IO) {
        runCatching {
            val url = buildUrl(remotePath)
            executeSignedRequest(
                send = { signDate ->
                    val headers = signV4("HEAD", url, EMPTY_SHA256, signDate)
                    val requestBuilder = Request.Builder().url(url).head()
                    headers.forEach { (k, v) -> requestBuilder.header(k, v) }
                    httpClient.newCall(requestBuilder.build()).execute()
                },
                consume = { response ->
                    when {
                        response.code == 404 -> throw SyncException.FileNotFound("S3 对象不存在: $remotePath")
                        response.code == 401 || response.code == 403 ->
                            throw SyncException.AuthenticationError("S3 鉴权失败 (${response.code})")
                        !response.isSuccessful -> throw SyncException.ProtocolError(response.code, response.message)
                    }

                    val etag = response.header("ETag").orEmpty().cleanEtag()
                    val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L
                    val lastModifiedStr = response.header("Last-Modified").orEmpty()
                    val lastModified = parseHttpDate(lastModifiedStr)

                    RemoteFileMetadata(
                        path = remotePath,
                        etag = etag,
                        contentLength = contentLength,
                        lastModifiedMillis = lastModified,
                        isDirectory = false
                    )
                }
            )
        }
    }

    override suspend fun download(remotePath: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val url = buildUrl(remotePath)
            executeSignedRequest(
                send = { signDate ->
                    val headers = signV4("GET", url, EMPTY_SHA256, signDate)
                    val requestBuilder = Request.Builder().url(url).get()
                    headers.forEach { (k, v) -> requestBuilder.header(k, v) }
                    httpClient.newCall(requestBuilder.build()).execute()
                },
                consume = { response ->
                    when {
                        response.code == 404 -> throw SyncException.FileNotFound("S3 对象不存在: $remotePath")
                        response.code == 401 || response.code == 403 ->
                            throw SyncException.AuthenticationError("S3 鉴权失败 (${response.code})")
                        !response.isSuccessful -> throw SyncException.ProtocolError(response.code, response.message)
                    }

                    response.body?.bytes() ?: throw SyncException.NetworkError("S3 响应为空")
                }
            )
        }
    }

    /**
     * 上传本地数据库文件至 S3。
     *
     * 并发安全与条件写策略（条件写全闭环，无 TOCTOU 竞争窗口）：
     * 1. 首传场景 (expectedEtag == null 且远端不存在)：
     *    PUT 附带 `If-None-Match: *` 请求头，实现 S3 协议级原子创建；
     *    遭遇并发创建冲突时 S3 返回 HTTP 412 Precondition Failed。
     * 2. 覆盖更新场景：
     *    PUT 附带 `If-Match: "<expectedEtag>"` 条件头（AWS S3 及支持条件写的兼容存储
     *    在服务端原子校验），远端 ETag 与期望不符（含 HEAD 探测后被并发修改）时
     *    S3 返回 HTTP 412，映射为 [SyncException.ConflictError]。
     *    HEAD 预检仅作快速失败优化，正确性完全由 PUT 的 If-Match 服务端校验保证。
     * 3. 兼容性降级说明：少数未支持条件覆写的 S3 兼容存储可能忽略 If-Match 头，
     *    此时语义退化为「HEAD 预检 + 无条件 PUT」，行为与旧版一致，不会更差。
     */
    override suspend fun upload(
        remotePath: String,
        data: ByteArray,
        expectedEtag: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val isFirstUpload: Boolean
            var precheckEtag: String? = null
            if (expectedEtag.isNullOrBlank()) {
                val metaResult = getMetadata(remotePath)
                when {
                    // 确认 404：真首传，PUT 附带 If-None-Match: * 原子创建
                    metaResult.exceptionOrNull() is SyncException.FileNotFound -> {
                        isFirstUpload = true
                    }
                    // 远端已存在却未声明期望 ETag：锁定 HEAD 所见版本，保证「覆盖的即所见」
                    metaResult.isSuccess -> {
                        isFirstUpload = false
                        precheckEtag = metaResult.getOrThrow().etag
                    }
                    // HEAD 探测遭遇网络错误 / 5xx 等非 404 失败时严禁无条件 PUT——
                    // 此时不带任何条件头的 PUT 若成功将静默覆盖远端（可能含他人更新）。
                    // 如实上抛失败交由上层按「远端不可达」处理（本地缓存已安全保留）
                    else -> {
                        throw metaResult.exceptionOrNull()
                            ?: SyncException.NetworkError("S3 上传前置探测失败")
                    }
                }
            } else {
                isFirstUpload = false
                val currentMeta = getMetadata(remotePath).getOrNull()
                if (currentMeta != null && currentMeta.etag != cleanEtag(expectedEtag)) {
                    throw SyncException.ConflictError(
                        remoteEtag = currentMeta.etag,
                        localExpectedEtag = expectedEtag,
                        message = "S3 远端文件已被其他人更新 (ETag 不匹配)"
                    )
                }
                precheckEtag = expectedEtag
            }

            val url = buildUrl(remotePath)
            val payloadHash = sha256Hex(data)
            executeSignedRequest(
                send = { signDate ->
                    val headers = signV4("PUT", url, payloadHash, signDate)
                    val requestBuilder = Request.Builder()
                        .url(url)
                        .put(data.toRequestBody("application/octet-stream".toMediaType()))
                    headers.forEach { (k, v) -> requestBuilder.header(k, v) }

                    if (isFirstUpload) {
                        requestBuilder.header("If-None-Match", "*")
                    } else {
                        precheckEtag?.takeIf { it.isNotBlank() }?.let { conditionEtag ->
                            requestBuilder.header("If-Match", "\"${cleanEtag(conditionEtag)}\"")
                        }
                    }
                    httpClient.newCall(requestBuilder.build()).execute()
                },
                consume = { response ->
                    when {
                        response.code == 412 -> {
                            val currentMeta = getMetadata(remotePath).getOrNull()
                            throw SyncException.ConflictError(
                                remoteEtag = currentMeta?.etag.orEmpty(),
                                localExpectedEtag = expectedEtag.orEmpty(),
                                message = "S3 对象并发创建冲突或已被其他人修改 (HTTP 412 Precondition Failed)"
                            )
                        }
                        response.code == 401 || response.code == 403 ->
                            throw SyncException.AuthenticationError("S3 鉴权失败 (${response.code})")
                        !response.isSuccessful -> throw SyncException.ProtocolError(response.code, response.message)
                    }

                    response.header("ETag").orEmpty().cleanEtag().ifBlank {
                        getMetadata(remotePath).getOrThrow().etag
                    }
                }
            )
        }
    }

    override suspend fun delete(remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = buildUrl(remotePath)
            executeSignedRequest(
                send = { signDate ->
                    val headers = signV4("DELETE", url, EMPTY_SHA256, signDate)
                    val requestBuilder = Request.Builder().url(url).delete()
                    headers.forEach { (k, v) -> requestBuilder.header(k, v) }
                    httpClient.newCall(requestBuilder.build()).execute()
                },
                consume = { response ->
                    if (!response.isSuccessful && response.code != 404) {
                        throw SyncException.ProtocolError(response.code, response.message)
                    }
                }
            )
        }
    }

    /**
     * TASK-45：签名时间戳取「本地时间 + 已探测时钟偏移」。偏移为 0（未探测）时
     * 退化为本地时间，与旧版行为一致（fail-closed）。
     */
    private fun signingDate(): Date = Date(System.currentTimeMillis() + clockOffsetMillis)

    /**
     * TASK-45：据响应 `Date` 头刷新运行时时钟偏移（服务端时间 - 本地时间）。
     *
     * 返回是否成功刷新：
     * - 响应携带可解析的 RFC 1123 `Date` 头 → 更新内存偏移并（变化 ≥ 1s 时）回调
     *   [clockOffsetUpdater] 持久化（尽力而为，失败静默容忍，仅丢失跨进程记忆）；
     * - 无 `Date` 头 / 解析失败 → 返回 false，**fail-closed**：不补偿、保持现状。
     */
    private fun refreshClockOffset(response: Response): Boolean {
        val dateHeader = response.header("Date") ?: return false
        val serverMillis = parseHttpDate(dateHeader)
        if (serverMillis <= 0L) return false
        val newOffset = serverMillis - System.currentTimeMillis()
        clockOffsetMillis = newOffset
        // 节流：HTTP Date 头秒级精度，相邻请求亚秒抖动不触发重复刷盘
        if (Math.abs(newOffset - reportedClockOffsetMillis) >= CLOCK_OFFSET_PERSIST_THRESHOLD_MS) {
            reportedClockOffsetMillis = newOffset
            runCatching { clockOffsetUpdater?.invoke(newOffset) }
        }
        return true
    }

    /**
     * TASK-45：判断本次请求是否因时钟偏斜被 S3 拒绝（`403 RequestTimeTooSkewed`，
     * SigV4 服务端容限 ±15 分钟）。**双信号任一命中即判定**：
     * - 响应主体含偏斜错误码标记（仅 GET/PUT/DELETE 等带实体的方法可靠）——
     *   [peekBody] 探测不消费实体，仅 403 读取前 4 KiB，其余状态码直接短路；
     * - 响应刷新后的时钟偏移相对本次签名时发生了超过容限量级的跳变（对无实体的
     *   HEAD 请求同样有效——HTTP 规范禁止 HEAD 携带错误主体，只能依赖 Date 头）。
     *
     * 无有效 `Date` 头（无法计算偏移）或两种信号均不满足时返回 false（fail-closed）。
     */
    private fun Response.isClockSkewRejection(
        offsetBeforeSigningMillis: Long,
        offsetAfterRefreshMillis: Long
    ): Boolean {
        if (code != 403) return false
        val bodyMarked = runCatching { peekBody(4096).string().contains(REQUEST_TIME_TOO_SKEWED) }
            .getOrDefault(false)
        val offsetJump = Math.abs(offsetAfterRefreshMillis - offsetBeforeSigningMillis)
        // 请求被拒仅因签名时间偏离服务端超过 15 分钟；此处跳变必然同量级。阈值取 14 分钟
        // 吸收 HTTP Date 秒级截断与网络 RTT 造成的亚分钟偏差，杜绝在正常时钟下误判重试
        val offsetJumpedSkewScale = offsetJump > CLOCK_SKEW_RETRY_THRESHOLD_MS
        return bodyMarked || offsetJumpedSkewScale
    }

    /**
     * 执行一次 SigV4 签名请求并以 [consume] 消费响应。统一承载 TASK-45 时钟偏移自愈：
     * 1. **每响必刷新**：任何响应（含 4xx/5xx）携带有效 `Date` 头都会刷新内部时钟偏移
     *    （吸收 NTP 校正漂移，本会话后续请求签名立即受益）；
     * 2. **skew 自愈重试**：若响应为 `403` 且经判定属时钟偏斜拒绝（偏移跳变超容限量级，
     *    或错误主体含 `RequestTimeTooSkewed`），自动用补偿后时间重签重试**恰好一次**
     *    （防退避风暴）——首次同步在偏移未知时也能自愈，不再向用户暴露 RequestTimeTooSkewed；
     * 3. **fail-closed**：无 `Date` 头 / 非偏斜拒绝 / 重试后仍失败——不做补偿也不静默放行，
     *    落入 [consume] 原路径如实上浮（签名错误、鉴权失败等按原映射处理）。
     */
    private suspend fun <T> executeSignedRequest(
        send: (signDate: Date) -> Response,
        consume: suspend (response: Response) -> T
    ): T {
        var skewRetried = false
        while (true) {
            val offsetBeforeSigning = clockOffsetMillis
            val response = send(signingDate())
            response.use {
                val refreshed = refreshClockOffset(it)
                val skewRejected = !skewRetried && refreshed &&
                    it.isClockSkewRejection(offsetBeforeSigning, clockOffsetMillis)
                if (skewRejected) {
                    skewRetried = true
                    // 偏移已按该 403 响应的 Date 头刷新，回到循环顶部用补偿后时间重签重试
                } else {
                    return consume(it)
                }
            }
        }
    }

    /**
     * 实现 AWS Signature Version 4 鉴权。
     * canonicalUri 必须与实际请求 URL 经过相同路径编码后的 URI 严格一致。
     *
     * TASK-45：生产调用方一律经 [signingDate] 传入补偿后时间戳；[dateTime] 保留默认值
     * 仅供单元测试注入固定时间点（已知答案向量）使用。
     */
    internal fun signV4(
        method: String,
        url: String,
        payloadHash: String,
        dateTime: Date = Date()
    ): Map<String, String> {
        val isoFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val amzDate = isoFormat.format(dateTime)
        val dateStamp = dateFormat.format(dateTime)

        val host = getHost(url)
        val canonicalUri = "/" + url.substringAfter("://").substringAfter('/').substringBefore('?')

        val canonicalHeaders = "host:$host\nx-amz-content-sha256:$payloadHash\nx-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"

        val canonicalRequest = "$method\n$canonicalUri\n\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        val canonicalRequestHash = sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))

        val credentialScope = "$dateStamp/$region/s3/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$credentialScope\n$canonicalRequestHash"

        val signingKey = getSignatureKey(secretAccessKey, dateStamp, region, "s3")
        val signature = hmacSha256Hex(signingKey, stringToSign)

        val authorizationHeader = "AWS4-HMAC-SHA256 Credential=$accessKeyId/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"

        return mapOf(
            "Host" to host,
            "x-amz-date" to amzDate,
            "x-amz-content-sha256" to payloadHash,
            "Authorization" to authorizationHeader
        )
    }

    private fun getSignatureKey(key: String, dateStamp: String, regionName: String, serviceName: String): ByteArray {
        val kSecret = ("AWS4$key").toByteArray(Charsets.UTF_8)
        val kDate = hmacSha256(kSecret, dateStamp)
        val kRegion = hmacSha256(kDate, regionName)
        val kService = hmacSha256(kRegion, serviceName)
        return hmacSha256(kService, "aws4_request")
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hmacSha256Hex(key: ByteArray, data: String): String {
        return hmacSha256(key, data).toHexString()
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).toHexString()
    }

    private fun parseHttpDate(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        try {
            val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            return sdf.parse(dateStr)?.time ?: 0L
        } catch (_: Exception) {
            return 0L
        }
    }

    companion object {
        const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        // TASK-45：时钟偏移持久化节流阈值——HTTP Date 头秒级精度，变化 < 1s 不回调刷盘
        private const val CLOCK_OFFSET_PERSIST_THRESHOLD_MS = 1000L

        // TASK-45：skew 判定偏移跳变阈值——SigV4 服务端容限 ±15 分钟，取 14 分钟吸收
        // HTTP Date 秒级截断与网络 RTT 的亚分钟偏差（正常时钟下绝无如此量级跳变）
        private const val CLOCK_SKEW_RETRY_THRESHOLD_MS = 14L * 60 * 1000

        // S3 时钟偏斜错误码主体标记（SigV4 ±15 分钟容限，设备时钟偏移超限时返回）
        private const val REQUEST_TIME_TOO_SKEWED = "RequestTimeTooSkewed"
    }
}
