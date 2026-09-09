package com.keepasskey.sync.scenario

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 有状态 WebDAV 服务器模拟（仅测试用）。
 *
 * 与逐条 enqueue 响应的 MockWebServer 用法不同，本模拟维护真实的服务端对象状态：
 * 内存键值存储 + 每次写入递增的 ETag + RFC 4918 语义（MOVE 的 `If` tagged-list 预条件、
 * `Overwrite` 头），使「并发竞态恰好一胜」「失败回滚后目标不被污染」等场景的断言
 * 从真实协议语义中涌现，而非依赖手工编排的响应顺序。
 *
 * 路径键与 MockWebServer 记录的请求行一致（客户端已编码的原始字符串，不做解码），
 * 客户端编码一致性由两端使用同一编码函数保证。
 */
class StatefulDavDispatcher : Dispatcher() {

    /** 对象存储：路径(已编码) -> 内容 */
    val files = ConcurrentHashMap<String, ByteArray>()

    /** 每对象 ETag（内部形态，无引号） */
    val etags = ConcurrentHashMap<String, String>()

    /** 最近一次 MOVE 的 Destination 路径（供编码断言） */
    val lastMoveDestination = AtomicReference<String?>(null)

    // ---- 故障注入开关 ----
    /** 下一次 PUT 立即断开连接（模拟上传中断，单次） */
    val failNextPut = AtomicBoolean(false)
    /** 所有 PUT 立即断开连接（OkHttp 可能对连接失败自动重试，持续故障保证确定性） */
    val failAllPuts = AtomicBoolean(false)
    /** 下一次 MOVE 返回 500（模拟 MOVE 单次失败，重试可恢复） */
    val failNextMove = AtomicBoolean(false)
    /** 所有 MOVE 返回 500（模拟 MOVE 持续失败，验证回滚幂等） */
    val failAllMoves = AtomicBoolean(false)
    /** 下一次 GET 返回 500（模拟服务端错误） */
    val failNextGet = AtomicBoolean(false)

    private val counter = AtomicInteger(0)

    /** MOVE 处理日志（调试用） */
    val moveLog = java.util.concurrent.CopyOnWriteArrayList<String>()

    fun nextEtag(): String = "etag-${counter.incrementAndGet()}"

    fun tmpResidues(): List<String> = files.keys.filter { it.endsWith(".kpktmp") }.toList()

    private fun parseIfEtag(request: RecordedRequest): String? {
        // RFC 4918 tagged list: If: <http://host/path> (["etag"])
        val header = request.getHeader("If") ?: return null
        val match = Regex("\\(\\[\"([^\"]*)\"\\]\\)").find(header) ?: return null
        return match.groupValues[1]
    }

    private fun destinationPath(request: RecordedRequest): String? {
        val dest = request.getHeader("Destination") ?: return null
        return "/" + dest.substringAfter("://").substringAfter('/')
    }

    private fun httpDate(): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.format(Date())

    private fun propfindBody(etag: String, length: Long, isCollection: Boolean): String {
        val resourceType = if (isCollection) "<D:resourcetype><D:collection/></D:resourcetype>" else "<D:resourcetype/>"
        return """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:propstat>
      <D:prop>
        <D:getetag>"$etag"</D:getetag>
        <D:getcontentlength>$length</D:getcontentlength>
        <D:getlastmodified>${httpDate()}</D:getlastmodified>
        $resourceType
      </D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
</D:multistatus>"""
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path ?: return MockResponse().setResponseCode(400)
        return when (request.method) {
            "PUT" -> {
                if (failAllPuts.get() || failNextPut.getAndSet(false)) {
                    MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                } else {
                    files[path] = request.body.readByteArray()
                    val etag = nextEtag()
                    etags[path] = etag
                    MockResponse().setResponseCode(201).setHeader("ETag", "\"$etag\"")
                }
            }
            "GET" -> {
                if (failNextGet.getAndSet(false)) {
                    MockResponse().setResponseCode(500).setBody("internal error")
                } else {
                    val bytes = files[path]
                    if (bytes == null) {
                        MockResponse().setResponseCode(404)
                    } else {
                        MockResponse().setResponseCode(200)
                            .setBody(Buffer().write(bytes))
                            .setHeader("ETag", "\"${etags[path]}\"")
                    }
                }
            }
            "PROPFIND" -> {
                if (path == "/") {
                    MockResponse().setResponseCode(207)
                        .setBody(propfindBody(etags[path] ?: "root-etag", 0L, isCollection = true))
                } else {
                    val bytes = files[path]
                    if (bytes == null) {
                        MockResponse().setResponseCode(404)
                    } else {
                        MockResponse().setResponseCode(207)
                            .setBody(propfindBody(etags[path].orEmpty(), bytes.size.toLong(), isCollection = false))
                    }
                }
            }
            "MOVE" -> {
                val dest = destinationPath(request)
                    ?: return MockResponse().setResponseCode(400)
                lastMoveDestination.set(dest)
                moveLog.add("MOVE src=$path dest=$dest if=${request.getHeader("If")} current=${etags[dest]}")
                // 预条件评估与改名必须原子（对齐真实服务端语义）：
                // MockWebServer 每连接一线程，若不加锁，两个并发 MOVE 可能都读到旧 ETag 而双双通过
                synchronized(this) {
                    if (failAllMoves.get() || failNextMove.getAndSet(false)) {
                        return MockResponse().setResponseCode(500).setBody("move failed")
                    }
                    // RFC 4918 tagged list 预条件：绑定目标资源 ETag，目标缺失或 ETag 不符一律 412
                    parseIfEtag(request)?.let { condition ->
                        val current = etags[dest]
                        if (current == null || current != condition) {
                            return MockResponse().setResponseCode(412)
                        }
                    }
                    val overwrite = request.getHeader("Overwrite") ?: "T"
                    if (overwrite == "F" && files.containsKey(dest)) {
                        return MockResponse().setResponseCode(412)
                    }
                    val src = files.remove(path) ?: return MockResponse().setResponseCode(404)
                    etags.remove(path)
                    files[dest] = src
                    val etag = nextEtag()
                    etags[dest] = etag
                    MockResponse().setResponseCode(201).setHeader("ETag", "\"$etag\"")
                }
            }
            "DELETE" -> {
                val removed = files.remove(path) != null
                etags.remove(path)
                MockResponse().setResponseCode(if (removed) 204 else 404)
            }
            else -> MockResponse().setResponseCode(405)
        }
    }
}

/**
 * 有状态 S3 兼容服务器模拟（仅测试用）。
 *
 * 模拟 S3 条件写语义（不校验 SigV4 签名——签名正确性由真实服务器联调验证）：
 * - PUT + `If-None-Match: *`：对象已存在时 412（原子创建保护）；
 * - PUT + `If-Match: "<etag>"`：与当前 ETag 不符或对象已删除时 412（乐观锁）；
 * - DELETE 幂等（S3 语义：删除不存在的对象仍返回 204）；
 * - HEAD/GET 返回带引号 ETag、Content-Length 与 RFC 1123 Last-Modified。
 */
class StatefulS3Dispatcher : Dispatcher() {

    val objects = ConcurrentHashMap<String, ByteArray>()
    val etags = ConcurrentHashMap<String, String>()
    private val counter = AtomicInteger(0)

    fun nextEtag(): String = "s3etag-${counter.incrementAndGet()}"

    private fun httpDate(): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.format(Date())

    override fun dispatch(request: RecordedRequest): MockResponse {
        val key = request.path ?: return MockResponse().setResponseCode(400)
        return when (request.method) {
            "HEAD" -> {
                val etag = etags[key]
                if (etag == null) {
                    MockResponse().setResponseCode(404)
                } else {
                    MockResponse().setResponseCode(200)
                        .setHeader("ETag", "\"$etag\"")
                        .setHeader("Content-Length", (objects[key]?.size ?: 0).toString())
                        .setHeader("Last-Modified", httpDate())
                }
            }
            "GET" -> {
                val bytes = objects[key]
                if (bytes == null) {
                    MockResponse().setResponseCode(404).setBody("<Error><Code>NoSuchKey</Code></Error>")
                } else {
                    MockResponse().setResponseCode(200)
                        .setBody(Buffer().write(bytes))
                        .setHeader("ETag", "\"${etags[key]}\"")
                }
            }
            "PUT" -> {
                // 条件写评估与落盘必须原子（对齐真实 S3 语义）：If-None-Match / If-Match
                // 与写入之间不允许插入其他写请求，否则并发首传/覆盖竞态失去裁决意义
                synchronized(this) {
                    val ifNoneMatch = request.getHeader("If-None-Match")
                    val ifMatch = request.getHeader("If-Match")
                    if (ifNoneMatch == "*" && objects.containsKey(key)) {
                        return MockResponse().setResponseCode(412)
                    }
                    if (ifMatch != null) {
                        val condition = ifMatch.removeSurrounding("\"")
                        val current = etags[key]
                        if (current == null || current != condition) {
                            return MockResponse().setResponseCode(412)
                        }
                    }
                    objects[key] = request.body.readByteArray()
                    val etag = nextEtag()
                    etags[key] = etag
                    MockResponse().setResponseCode(200).setHeader("ETag", "\"$etag\"")
                }
            }
            "DELETE" -> {
                objects.remove(key)
                etags.remove(key)
                MockResponse().setResponseCode(204)
            }
            else -> MockResponse().setResponseCode(405)
        }
    }
}
