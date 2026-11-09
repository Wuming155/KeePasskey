package com.keepasskey.sync.webdav

import androidx.annotation.VisibleForTesting
import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.RemoteListEntry
import com.keepasskey.sync.model.RemoteListPage
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.model.cleanEtag
import com.keepasskey.sync.model.isWeakEtag
import com.keepasskey.sync.network.SyncDownloadLimits
import com.keepasskey.sync.network.SyncEndpointGuard
import com.keepasskey.sync.network.SyncHttpClientFactory
import com.keepasskey.sync.network.SyncNetworkOptions
import com.keepasskey.sync.network.SyncTransferOptions
import com.keepasskey.sync.network.TransientHttpRetry
import com.keepasskey.sync.network.executeCancellable
import com.keepasskey.sync.provider.CredentialReleasingProvider
import com.keepasskey.sync.provider.SyncProvider
import com.keepasskey.sync.network.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.OutputStream
import java.util.UUID

/**
 * 标准 WebDAV 客户端实现 (RFC 4918)。
 * 支持 Nextcloud、ownCloud、坚果云等公有 WebDAV 托管服务，以及自建服务器（NAS / 内网
 * HTTPS 端点默认可用，ISSUE-P2-425；明文 HTTP 一律拒绝）：
 * 1. PROPFIND: 基于 DOM 解析 getetag, getcontentlength, getlastmodified, resourcetype；
 *    报文不可解析时如实失败（不折成「成功 + 空元数据」，见 [WebDavPropfindParser.parse]）；
 * 2. GET: 二进制流下载;
 * 3. PUT: 支持 If-Match: <etag> 乐观并发保护;
 * 4. 事务写 (uploadAtomic): PUT 唯一随机临时名（后缀插在原扩展名前，保留原扩展名）-> MOVE 覆盖 -> 失败重试/回滚;
 * 5. URL 编码：对路径段执行逐段 UTF-8 编码。
 *
 * 纯结构性拆分（零行为变更）：XML(multistatus) 解析委托 [WebDavPropfindParser]、路径 ↔ URL
 * 编码纯函数委托 [WebDavUrlCodec]、Basic 认证头构造委托 [WebDavAuthHeader]；本类保留接口实现与编排。
 *
 * ## 凭据持有与释放周期（ENG-20261028-42）
 *
 * 本类**不再**把 `Basic <base64>` 头缓存在字段里：那是可逆明文的不可擦副本，一旦随字段驻留，
 * 其生命周期就等于 Provider 的整个生命周期（跨越多个同步周期、多个用户交互窗口）。
 * 现在只持有 [WebDavAuthCredentials]（口令的**独占可擦副本**），认证头由
 * [WebDavAuthCredentials.authorizationHeader] 在**每次构造请求时**现算，不可擦的 String 因此
 * 收缩到单次请求作用域（OkHttp header API 以 String 承载，跨协议边界无法全程可擦，属平台边界）。
 *
 * 持有：从构造起至 [clearCredentials] / [releaseCredentials] 被调用；构造期仍即时复制口令
 * （`passwordChars` 借用数组随后由本类清零），因此调用方在构造返回后立即擦除入参数组不影响认证。
 *
 * 释放：**唯一的确定性失效点**由持有方的生命周期决定，见下（本类不自行在每次请求后释放——
 * 一个 Provider 会在同一周期内服务多次请求，并在冲突待决窗口内被用户裁决继续复用）：
 * - 同步周期结束：Provider 归 `SyncProviderResolver.useProvider*` 作用域所有，作用域退出时
 *   经 `SyncProviderFactory.releaseOwned` 释放（S3 同口径）；
 * - 会话终止（锁定 / 关库 / 换库）：`SyncSessionState.clear()` 经
 *   `SyncEngine.releaseProviderCredentials()` 转调本类，覆盖「周期结束但引擎仍被会话引用」的窗口；
 * - 一次性用途（云端导入 / 远端目录浏览）：调用方在自己的 `finally` 中释放。
 * 失效后本类**拒绝**继续构造认证头并抛 [SyncException.AuthenticationError]（fail-closed，
 * 不以空口令降级），因此仍可能发请求的存活消费者必须在释放前确认已无持有者。
 */
class WebDavSyncProvider private constructor(
    // 测试注入口（ISSUE-P3-584）：注入即跳过 TLS-only / SSRF / 构造期 HTTPS 校验。已移出 public 主构造
    // 签名——主构造 private，生产侧仅经下方 public 二级构造进入；internal 二级构造仅同模块测试可见。
    @VisibleForTesting
    private val client: OkHttpClient?,
    private val serverUrl: String,
    private val username: String,
    passwordChars: CharArray,
    /**
     * Wave 14 传输安全网络选项（TLS-only 恒定 + 显式超时；证书固定已移除，走系统默认 CA 链）。
     * 由 app 层组装传入（纯数据契约，维持 sync 不依赖 app 的单向依赖）。
     */
    private val networkOptions: SyncNetworkOptions,
    /**
     * ISSUE-P3-03 (43a)：分块上传传输选项。由 app 层把 `webdavChunkedUpload` /
     * `webdavChunkSizeMb` 偏好归一为 [SyncTransferOptions] 传入（依赖倒置，
     * sync 不读取 app 偏好）。默认关闭 = 与接线前逐字节一致的单次定长 PUT。
     */
    private val transferOptions: SyncTransferOptions
) : SyncProvider, CredentialReleasingProvider {

    /** 生产构造（ISSUE-P3-584）：不暴露 `client`，杜绝 TLS-only / SSRF / 构造期 HTTPS 强制旁路。 */
    constructor(
        serverUrl: String,
        username: String,
        passwordChars: CharArray,
        networkOptions: SyncNetworkOptions = SyncNetworkOptions(),
        transferOptions: SyncTransferOptions = SyncTransferOptions.DISABLED
    ) : this(null, serverUrl, username, passwordChars, networkOptions, transferOptions)

    /** 测试专用构造（ISSUE-P3-584，`internal`）：仅同模块 `src/test` / `src/androidTest` 可见。 */
    @VisibleForTesting
    internal constructor(
        serverUrl: String,
        username: String,
        passwordChars: CharArray,
        networkOptions: SyncNetworkOptions = SyncNetworkOptions(),
        transferOptions: SyncTransferOptions = SyncTransferOptions.DISABLED,
        client: OkHttpClient
    ) : this(client, serverUrl, username, passwordChars, networkOptions, transferOptions)

    // Wave 12/14 传输安全：默认经 TLS-only 工厂构建（排除 CLEARTEXT + 显式超时 + 系统 CA 链验证），
    // 仅 HTTP 回环测试需显式注入明文客户端。生产路径在 init 中随构造期校验一并构建：
    // 已配置端点主机传入工厂登记连接期豁免（ISSUE-P2-425，见 createSyncClient KDoc）
    private val httpClient: OkHttpClient

    // L4 整改：口令副本在构造时立即复制——调用方（SyncCoordinator / 导入 / 浏览）在构造返回后
    // 会立即显式清零传入的密码 CharArray，此前的 by lazy 首请求延迟求值会在清零后
    // 才读取密码，导致实际以空密码认证、同步必然失败。
    // ENG-20261028-42：持有可擦副本而非「构造期算好的 Basic 头」，认证头按请求现算。
    private val credentials: WebDavAuthCredentials

    init {
        if (client == null) {
            // Wave 14 全站强制 HTTPS（生产路径 fail-fast）：显式 http:// 端点在构造期即拒绝并抛
            // 类型化 InvalidEndpointError（用户可理解提示），而非在网络层以晦涩错误失败；
            // 仅测试回环（显式注入 HTTP 客户端）豁免——MockWebServer 回环地址为 http://，不承载生产流量
            val trimmedUrl = serverUrl.trim()
            if (trimmedUrl.contains("://") && !trimmedUrl.startsWith("https://", ignoreCase = true)) {
                throw SyncException.InvalidEndpointError(
                    "WebDAV 端点必须使用 HTTPS（当前协议为 \"${trimmedUrl.substringBefore("://")}://\"）。" +
                        "明文 HTTP 已被禁止以保护凭据与密码库传输，请填写 https:// 开头的商业云服务地址"
                )
            }
            // ISSUE-P1-05（ZT-05）构造期防线（ISSUE-P2-425 放宽）：拒绝 userinfo 注入与云元数据
            // 字面量；自建 / 内网 HTTPS 端点放行，其连接期豁免经返回的规范化主机登记进工厂
            // （重定向 / 重绑定跳到的非预期内网地址仍被 SsrfGuardDns / SsrfGuardSocketFactory 拦截）
            val endpointHost = SyncEndpointGuard.validateEndpointHost(serverUrl, networkOptions.ssrfAllowedHosts)
            httpClient = SyncHttpClientFactory.createSyncClient(networkOptions, endpointHost)
        } else {
            httpClient = client
        }
        credentials = WebDavAuthCredentials(username, passwordChars)
        // 借用语义收口不变：副本已由 credentials 独占持有，入参数组即刻归还并擦除
        passwordChars.fill('0')
    }

    /**
     * ENG-20261028-42：确定性失效本实例的凭据（幂等）。
     *
     * 调用后 [WebDavAuthCredentials.authorizationHeader] 抛 [SyncException.AuthenticationError]，
     * 任何后续请求都以鉴权失败结束，而不是以空口令发出「用户名 + 空密码」的可被服务端记录的错误凭据。
     * 释放时机与「不可复活的存活消费者」约束见类 KDoc。
     */
    fun clearCredentials() {
        credentials.invalidate()
    }

    /** 可选能力接口实现：与 [clearCredentials] 同一语义，供会话/作用域层按能力判定调用。 */
    override fun releaseCredentials() = clearCredentials()

    /**
     * ISSUE-P3-298 ③：请求级瞬时错误重试的统一出口（[TransientHttpRetry]）。
     *
     * @param retryable 请求是否允许传输级重试——幂等读（PROPFIND / GET / DELETE）或**实际携带
     *   服务器预条件**（`If-Match` / `If` 头）的写才为 true；无条件写（临时文件 PUT）恒 false，
     *   杜绝「重试即无条件 PUT」。带预条件的重试由服务端逐次重验基线：基线被推进即 412 →
     *   `ConflictError`（「重试前重新校验基线」的协议级实现）。
     * @param requestFactory 每次尝试重建请求（避免任何一次性 RequestBody 的复用疑义）
     */
    private suspend fun executeTransientRetryable(
        retryable: Boolean,
        requestFactory: () -> Request
    ): Response {
        if (!retryable) return executeCancellable(httpClient.newCall(requestFactory()))
        return try {
            TransientHttpRetry.run(
                maxAttempts = networkOptions.transientRetryAttempts,
                baseDelayMs = networkOptions.transientRetryBaseDelayMs
            ) {
                val response = executeCancellable(httpClient.newCall(requestFactory()))
                if (TransientHttpRetry.isRetryableStatus(response.code)) {
                    response.close()
                    throw TransientHttpRetry.RetryableStatus(response.code)
                }
                response
            }
        } catch (r: TransientHttpRetry.RetryableStatus) {
            // 重试耗尽仍是瞬时可重试状态码：按既有状态映射口径收敛为类型化 ProtocolError，
            // 不以裸 IOException 上浮（调用方按 SyncException 家族归类用户文案）
            throw SyncException.ProtocolError(r.code, "服务器持续返回可重试状态码 (HTTP ${r.code})")
        }
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val url = serverUrl.trimEnd('/')
            val request = Request.Builder()
                .url(url)
                .method("PROPFIND", PROPFIND_XML.toRequestBody("application/xml".toMediaType()))
                .header("Authorization", credentials.authorizationHeader())
                .header("Depth", "0")
                .build()

            executeTransientRetryable(retryable = true) { request }.use { response ->
                when {
                    response.isSuccessful || response.code == 207 -> Unit
                    response.code == 401 || response.code == 403 ->
                        throw SyncException.AuthenticationError("WebDAV 鉴权失败 (${response.code})")
                    else -> throw SyncException.ProtocolError(response.code, response.message)
                }
            }
        }
    }

    override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val fullUrl = WebDavUrlCodec.buildUrl(serverUrl, remotePath)
            val request = Request.Builder()
                .url(fullUrl)
                .method("PROPFIND", PROPFIND_XML.toRequestBody("application/xml".toMediaType()))
                .header("Authorization", credentials.authorizationHeader())
                .header("Depth", "0")
                .build()

            executeTransientRetryable(retryable = true) { request }.use { response ->
                when {
                    response.code == 404 -> throw SyncException.FileNotFound("远程文件不存在: $remotePath")
                    response.code == 401 || response.code == 403 ->
                        throw SyncException.AuthenticationError("WebDAV 鉴权失败 (${response.code})")
                    !response.isSuccessful && response.code != 207 ->
                        throw SyncException.ProtocolError(response.code, response.message)
                }

                // ISSUE-P0-09：PROPFIND 元数据响应有界读取——「流式 + 声明尺寸」双重封顶，
                // 超大响应被拒绝而非整体物化（Depth:0 报文极小，16 MiB 上限极宽裕）。
                // XML 以 UTF-8 解码（OkHttp 无 charset 时的默认行为一致；本仓 WebDAV 端点均为 UTF-8）
                val xml = String(
                    SyncDownloadLimits.readBounded(
                        input = response.body.byteStream(),
                        declaredLength = response.body.contentLength(),
                        maxBytes = SyncDownloadLimits.MAX_PROPFIND_BYTES,
                        label = "PROPFIND"
                    ),
                    Charsets.UTF_8
                )
                // ENG-20261028-32：解析失败（空白 / 损坏 / 截断 / 非 multistatus 根，如网关替换的
                // HTML 错误页）一律 fail-closed，不得折成「成功但属性缺失」——被劫持的中间层可以
                // 只改响应体不改状态码，伪装成无 ETag 服务器即可沿无锁路径剥掉后续覆盖上传的乐观锁。
                // 「报文合法但缺 getetag」才是回退可信 HTTP 头的通道（见下方 ifBlank 回退）。
                val parsed = WebDavPropfindParser.parse(xml)
                    ?: throw SyncException.ProtocolError(
                        response.code,
                        "WebDAV PROPFIND 元数据响应无法解析为 multistatus（已拒绝把解析失败折成元数据）"
                    )

                val etag = parsed.etag.ifBlank {
                    response.header("ETag")?.cleanEtag().orEmpty()
                }
                val contentLength = if (parsed.contentLength >= 0L) {
                    parsed.contentLength
                } else {
                    response.header("Content-Length")?.toLongOrNull() ?: 0L
                }
                val lastModifiedMillis = if (parsed.lastModifiedMillis > 0L) {
                    parsed.lastModifiedMillis
                } else {
                    WebDavPropfindParser.parseHttpDate(response.header("Last-Modified").orEmpty())
                }

                RemoteFileMetadata(
                    path = remotePath,
                    etag = etag,
                    contentLength = contentLength,
                    lastModifiedMillis = lastModifiedMillis,
                    isDirectory = parsed.isDirectory
                )
            }
        }
    }

    /**
     * ISSUE-P3-387：WebDAV 远端目录浏览（PROPFIND Depth:1）。
     * 实现下沉 [WebDavDirectoryList]；本方法只负责按请求取头与委托。
     */
    override suspend fun listRemoteDirectory(
        remotePath: String,
        cursor: String?,
        pageSize: Int
    ): Result<RemoteListPage> {
        // ENG-20261028-42：取头在凭据已失效时会抛 AuthenticationError。此处实参在
        // WebDavDirectoryList 的 runCatchingCancellable **之外**求值，必须自行兜住，
        // 否则异常外溢到调用方而不是与列举失败同口径返回 Result.failure。
        // 边界：单页列举只发一次 PROPFIND，故该头的作用域即一次请求。
        val authHeader = runCatchingCancellable { credentials.authorizationHeader() }
            .getOrElse { return Result.failure(it) }
        return WebDavDirectoryList.list(
            serverUrl = serverUrl,
            authHeader = authHeader,
            remotePath = remotePath,
            cursor = cursor,
            pageSize = pageSize,
            execute = { request -> executeTransientRetryable(retryable = true) { request } }
        )
    }

    override suspend fun download(remotePath: String, sink: OutputStream): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                val fullUrl = WebDavUrlCodec.buildUrl(serverUrl, remotePath)
                val request = Request.Builder()
                    .url(fullUrl)
                    .get()
                    .header("Authorization", credentials.authorizationHeader())
                    .build()

                executeTransientRetryable(retryable = true) { request }.use { response ->
                    when {
                        response.code == 404 -> throw SyncException.FileNotFound("远程文件不存在: $remotePath")
                        response.code == 401 || response.code == 403 ->
                            throw SyncException.AuthenticationError("WebDAV 鉴权失败 (${response.code})")
                        !response.isSuccessful -> throw SyncException.ProtocolError(response.code, response.message)
                    }

                    // ISSUE-P0-09 / ISSUE-P3-206：下载体「流式 + 声明尺寸」双重封顶——超大响应被拒绝
                    // 而非整体物化致 OOM（远端或系统 CA 级 MITM 是唯一可单方面触发的一方）；
                    // 内容边读边写进调用方 sink，下载期堆占用为常量缓冲（不再 ~2×S 整份物化）
                    SyncDownloadLimits.copyBounded(
                        input = response.body.byteStream(),
                        declaredLength = response.body.contentLength(),
                        label = "WebDAV",
                        sink = sink
                    )
                    Unit
                }
            }
        }

    override suspend fun upload(
        remotePath: String,
        data: ByteArray,
        expectedEtag: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val fullUrl = WebDavUrlCodec.buildUrl(serverUrl, remotePath)
            val requestBuilder = Request.Builder()
                .url(fullUrl)
                .put(WebDavUploadBody.create(data, transferOptions))
                .header("Authorization", credentials.authorizationHeader())

            if (!expectedEtag.isNullOrBlank() && !isWeakEtag(expectedEtag)) {
                requestBuilder.header("If-Match", WebDavUrlCodec.formatHeaderEtag(expectedEtag))
            }
            // ISSUE-P1-275 AC②：弱校验 ETag 不得进 If-Match——RFC 7232 §3.1 规定 If-Match 恒用
            // 强比较，弱标签在合规服务器上永不匹配，发送即恒 412 死锁（RFC 4918 §8.6 明示
            // "weak ETags … cannot be used in If-Match headers"）。跳过预条件不是静默降级：
            // 本应用 WebDAV 的目标资源乐观锁由 [uploadAtomic] 的 MOVE `If` 头承担（RFC 4918
            // §10.4.4 允许弱比较，弱形态可表达），生产写路径不经过本分支。

            // ISSUE-P3-298 ③：仅当 PUT 实际携带 If-Match 预条件时才允许传输级重试——
            // 预条件在服务端逐次重验（基线被推进即 412 → ConflictError）；无预条件（expectedEtag
            // 空白或弱形态，If-Match 被 ISSUE-P1-275 AC② 剥除）属无条件 PUT，重试被禁
            val conditionalPut = !expectedEtag.isNullOrBlank() && !isWeakEtag(expectedEtag)
            executeTransientRetryable(retryable = conditionalPut) { requestBuilder.build() }.use { response ->
                when {
                    response.code == 412 -> {
                        // ISSUE-P2-501：元数据重探**失败**（Result.Failure）≠「无 ETag 服务器」
                        // （Success + 空 etag）。把失败折成空串会让 `remoteEtag=""` 沿 commitLocal
                        // 冲突分支（无回填）流入合并上传、被折 null ⇒ 无 `If` 覆盖，剥除乐观锁并
                        // 静默覆盖他端写入。fail-closed：如实上抛探测失败，上层归 RemoteUnreachable。
                        val metaResult = getMetadata(remotePath)
                        val currentMeta = metaResult.getOrNull()
                            ?: throw (metaResult.exceptionOrNull()
                                ?: SyncException.NetworkError("WebDAV 412 后远端元数据重探失败"))
                        throw SyncException.ConflictError(
                            remoteEtag = currentMeta.etag,
                            localExpectedEtag = expectedEtag.orEmpty(),
                            message = "WebDAV 远端文件已被其他人修改 (HTTP 412 Precondition Failed)"
                        )
                    }
                    response.code == 401 || response.code == 403 ->
                        throw SyncException.AuthenticationError("WebDAV 鉴权失败 (${response.code})")
                    !response.isSuccessful && response.code != 201 && response.code != 204 ->
                        throw SyncException.ProtocolError(response.code, response.message)
                }

                response.header("ETag")?.cleanEtag() ?: getMetadata(remotePath).getOrThrow().etag
            }
        }
    }

    /**
     * 事务性原子上传 (P2-16)。
     * 流程：
     * 1. 上传至临时文件——临时名由 [atomicTmpPath] 构造：固定后缀 `.kpktmp` 插在**原扩展名之前**、
     *    保留末段扩展名与目标一致（`vault.kdbx` → `vault.<随机UUID>.kpktmp.kdbx`，ISSUE-P2-382；
     *    keepass2android `b1ae0482` 实测存在按扩展名限制上传的服务器，末段被替换成 `.kpktmp`
     *    的旧形态会被拒收）。注意：由此残留临时文件也会以原扩展名出现在远端目录中，
     *    做目录列举/扩展名过滤时须同时排除含 `.kpktmp` 中缀的名字（P3-387 评估面）。
     *    临时名含每次操作的随机成分：若多客户端共用固定临时名，A 的 MOVE 可能搬运到 B 刚覆盖写入的
     *    临时内容（If 预条件只约束 MOVE 目标，不约束源临时文件），造成数据交叉污染；
     * 2. 发送 WebDAV MOVE 命令（Destination: 目标完整 URL）；
     *    对远端目标文件的 ETag 预条件使用 RFC 4918 `If` 头 tagged list 语法
     *    （`If: <destUrl> (["etag"])`）——`If-Match` 默认仅作用于请求-URI（即源临时文件），
     *    对 MOVE 目标无约束效力；
     * 3. 传输级失败（非 409/412/423）重试 1 次；
     * 4. 仍失败则 DELETE **本次临时文件**并抛错回滚，正式远端目标不被改动。
     *
     * **ISSUE-P2-381 / ENG-20261028-20**：部分服务器对 MOVE 覆盖已有目标报 409/423（仅靠
     * `Overwrite:T` 不可靠；keepass2android `WebDavStorage.java:235` 注释 + 提交 `9c8ee243` 实证）。
     * 收到 409/423 时先重探目标元数据裁决（远端 ETag 非空且 ≠ 预期 ⇒ ConflictError，交上层合并；
     * 探测失败 ⇒ 如实上抛，fail-closed）；远端未漂移也**不再**以「DELETE 正式目标 + 无预条件重试」
     * 兜底——两步之间没有原子性：DELETE 成功而重试 MOVE 失败、取消或进程死亡会让正式远端路径缺失，
     * DELETE 与重试之间他端重建的目标又会被无 `If` 的 MOVE 静默覆盖。此类服务器因此得到
     * 可处理的协议错误（[WebDavMoveOverwritePolicy.overwriteRefusedMessage]），远端旧文件原样保留。
     * 409/423 与 412 分开报错文案。
     *
     * Overwrite 语义裁定（expectedEtag 为空时的两种形态必须区分）：
     * - **真首传**（远端目标不存在）→ `Overwrite: F` 保持原子创建保护，
     *   目标若被并发创建由服务端 412 转 ConflictError；
     * - **无 ETag 服务器的覆盖上传**（本地赢自动上传 / 冲突解决提交路径，目标必然已存在）
     *   → `Overwrite: T`，语义退化为最后写入者胜——无 ETag 服务器无法实施乐观锁，
     *   这是在此类服务器上唯一可行的写语义；恒用 `Overwrite: F` 会让 MOVE 对已存在目标
     *   恒定 412，上传路径陷入死锁（冲突解决提交同样 412，同步永久无法收敛）。
     *   目标存在性以 PROPFIND 探测判定，探测失败按「目标存在」的保守对侧处理为 `F`（fail-safe）。
     */
    override suspend fun uploadAtomic(
        remotePath: String,
        data: ByteArray,
        expectedEtag: String?,
        remoteExists: Boolean?
    ): Result<String> {
        // ENG-20261028-42：同 listRemoteDirectory——实参在 WebDavUploadAtomic 的保护段之外求值，
        // 凭据已失效时必须在此收敛为 Result.failure，不能以异常外溢。
        // **已知边界（helper 签名固定为 String，本次不改）**：该头在本事务内被 MOVE 复用
        // （最多两次重试），因此其作用域是「一次事务」而非「一次请求」；若本事务进行中发生
        // 凭据失效（锁定 / 配置撤销），最后的 MOVE 仍会带上失效前构造的头发出一次请求。
        // 中间那个 PUT 由 [upload] 自行现算，因此失效后它会先失败。不可擦副本的存在面仍
        // 远小于整改前的「随 Provider 常驻整个生命周期」。
        val authHeader = runCatchingCancellable { credentials.authorizationHeader() }
            .getOrElse { return Result.failure(it) }
        return WebDavUploadAtomic.run(
            serverUrl = serverUrl,
            authHeader = authHeader,
            remotePath = remotePath,
            data = data,
            expectedEtag = expectedEtag,
            remoteExists = remoteExists,
            upload = { path, bytes, etag -> upload(path, bytes, etag) },
            getMetadata = { path -> getMetadata(path) },
            delete = { path -> delete(path) },
            execute = { retryable, requestFactory ->
                executeTransientRetryable(retryable = retryable) { requestFactory() }
            }
        )
    }

    override suspend fun delete(remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val fullUrl = WebDavUrlCodec.buildUrl(serverUrl, remotePath)
            val request = Request.Builder()
                .url(fullUrl)
                .delete()
                .header("Authorization", credentials.authorizationHeader())
                .build()

            executeTransientRetryable(retryable = true) { request }.use { response ->
                if (!response.isSuccessful && response.code != 404) {
                    throw SyncException.ProtocolError(response.code, response.message)
                }
            }
        }
    }

    companion object {
        const val ATOMIC_TMP_SUFFIX = ".kpktmp"

        /**
         * 事务上传临时名构造（ISSUE-P2-382）：把固定后缀 [ATOMIC_TMP_SUFFIX] 插在**原扩展名之前**，
         * 保留末段扩展名与目标一致（`vault.kdbx` → `vault.<uniqueToken>.kpktmp.kdbx`）——
         * keepass2android `b1ae0482` 实测存在按扩展名限制上传的 WebDAV 服务器，
         * 末段被替换为 `.kpktmp` 的旧形态（`vault.kdbx.<uniqueToken>.kpktmp`）会被这类服务器拒收。
         * 无扩展名的路径（或末段以「.」开头且无主干，如 `.kdbx` 这种点文件名）保持旧行为尾部追加。
         * 纯函数；命名形态由 `WebDavSyncProviderTest` 单测锁定防回潮。
         */
        internal fun atomicTmpPath(remotePath: String, uniqueToken: String): String {
            val lastSegment = remotePath.substringAfterLast('/', remotePath)
            val lastDot = lastSegment.lastIndexOf('.')
            return if (lastDot > 0) {
                val extension = lastSegment.substring(lastDot)
                "${remotePath.removeSuffix(extension)}.$uniqueToken$ATOMIC_TMP_SUFFIX$extension"
            } else {
                "$remotePath.$uniqueToken$ATOMIC_TMP_SUFFIX"
            }
        }

        private const val PROPFIND_XML = """<?xml version="1.0" encoding="utf-8" ?>
<D:propfind xmlns:D="DAV:">
  <D:prop>
    <D:getcontentlength/>
    <D:getlastmodified/>
    <D:getetag/>
    <D:resourcetype/>
  </D:prop>
</D:propfind>"""
    }
}
