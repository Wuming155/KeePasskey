package com.keepasskey.sync.s3

import com.keepasskey.core.log.AppLog
import com.keepasskey.sync.model.RemoteListEntry
import com.keepasskey.sync.model.RemoteListPage
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.network.SyncDownloadLimits
import okhttp3.Request
import okhttp3.Response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Node
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * ISSUE-P3-387：S3 ListObjectsV2 目录列举（自 [S3SyncProvider.listRemoteDirectory] 拆出，零行为变更）。
 *
 * 保守降级：HTTP 非 2xx / XML 无法解析时 failure，不伪装空目录。
 */
internal object S3DirectoryList {

    internal data class ParsedListObjects(
        val entries: List<RemoteListEntry>,
        val isTruncated: Boolean,
        val nextToken: String?
    )

    /**
     * @param buildBucketRootUrl bucket 根对象 URL（ListObjectsV2 规范目标）
     * @param sign 传入 method/url/payloadHash/queryString 返回签名头
     */
    suspend fun list(
        remotePath: String,
        cursor: String?,
        pageSize: Int,
        buildBucketRootUrl: () -> String,
        sign: (method: String, url: String, payloadHash: String, queryString: String) -> Map<String, String>,
        execute: (Request) -> Response
    ): Result<RemoteListPage> = withContext(Dispatchers.IO) {
        runCatching {
            val dirPath = remotePath.trim().trim('/')
            val prefix = if (dirPath.isEmpty()) "" else "$dirPath/"
            val maxKeys = pageSize.coerceIn(1, 1000)
            val queryPairs = mutableListOf(
                "list-type" to "2",
                "max-keys" to maxKeys.toString(),
                "delimiter" to "/"
            )
            if (prefix.isNotEmpty()) queryPairs.add("prefix" to prefix)
            if (!cursor.isNullOrBlank()) queryPairs.add("continuation-token" to cursor)
            val signQuery = queryPairs.joinToString("&") { (k, v) -> "$k=$v" }
            val urlQuery = queryPairs.joinToString("&") { (k, v) ->
                "${S3KeyCodec.encodeQueryValue(k)}=${S3KeyCodec.encodeQueryValue(v)}"
            }
            val baseUrl = buildBucketRootUrl()
            val url = "$baseUrl?$urlQuery"
            val headers = sign("GET", baseUrl, S3RequestSigner.EMPTY_SHA256, signQuery)
            val requestBuilder = Request.Builder().url(url).get()
            headers.forEach { (k, v) -> requestBuilder.header(k, v) }
            execute(requestBuilder.build()).use { response ->
                when {
                    response.code == 401 || response.code == 403 ->
                        throw SyncException.AuthenticationError("S3 鉴权失败 (${response.code})")
                    !response.isSuccessful ->
                        throw SyncException.ProtocolError(response.code, response.message)
                }
                val xml = String(
                    SyncDownloadLimits.readBounded(
                        input = response.body.byteStream(),
                        declaredLength = response.body.contentLength(),
                        maxBytes = SyncDownloadLimits.MAX_PROPFIND_BYTES,
                        label = "S3-list"
                    ),
                    Charsets.UTF_8
                )
                val parsed = parseListObjects(xml)
                    ?: throw SyncException.ProtocolError(
                        response.code,
                        "S3 目录列举响应无法解析（已拒绝伪装空目录）"
                    )
                RemoteListPage(
                    entries = parsed.entries,
                    nextCursor = if (parsed.isTruncated) parsed.nextToken else null,
                    truncated = parsed.isTruncated
                )
            }
        }
    }

    internal fun parseListObjects(xml: String): ParsedListObjects? {
        if (xml.isBlank()) return null
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            val builder = factory.newDocumentBuilder().apply {
                setEntityResolver(EntityResolver { _, _ ->
                    InputSource(ByteArrayInputStream(ByteArray(0)))
                })
            }
            val doc = builder.parse(xml.byteInputStream())
            val root = doc.documentElement
            val rootName = root.localName ?: root.nodeName.substringAfter(':')
            if (!rootName.equals("ListBucketResult", ignoreCase = true)) return null

            fun textOf(parent: Node, local: String): String? {
                val nodes = parent.childNodes
                for (i in 0 until nodes.length) {
                    val n = nodes.item(i)
                    val name = n.localName ?: n.nodeName.substringAfter(':')
                    if (name.equals(local, ignoreCase = true)) return n.textContent?.trim()
                }
                return null
            }

            val common = childNodes(root, "CommonPrefixes").mapNotNull { node ->
                val prefix = textOf(node, "Prefix")?.trim().orEmpty()
                if (prefix.isEmpty()) return@mapNotNull null
                val path = prefix.trimEnd('/')
                RemoteListEntry(
                    path = path,
                    name = path.substringAfterLast('/'),
                    isDirectory = true,
                    contentLength = 0L,
                    lastModifiedMillis = 0L
                )
            }
            val contents = childNodes(root, "Contents").mapNotNull { node ->
                val key = textOf(node, "Key")?.trim().orEmpty()
                if (key.isEmpty()) return@mapNotNull null
                val path = key.trimEnd('/')
                if (path.isEmpty()) return@mapNotNull null
                if (common.any { it.path == path }) return@mapNotNull null
                RemoteListEntry(
                    path = path,
                    name = path.substringAfterLast('/'),
                    isDirectory = false,
                    contentLength = textOf(node, "Size")?.toLongOrNull() ?: 0L,
                    lastModifiedMillis = S3HttpDateCodec.parse(textOf(node, "LastModified").orEmpty())
                )
            }
            val isTruncated = textOf(root, "IsTruncated")?.equals("true", ignoreCase = true) == true
            val nextToken = textOf(root, "NextContinuationToken")?.takeIf { it.isNotBlank() }
            val all = (common + contents)
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }, { it.name }))
            // ISSUE-P3-510：`RemoteListPage` 的 KDoc 不变式是「truncated 为 true 时 nextCursor
            // 非空」，而 UI（`RemoteBrowseDialog`）只看 `truncated` 就渲染「加载更多」。
            // 合规 S3 在 IsTruncated=true 时必带 NextContinuationToken，但**端点由用户任填**——
            // 非规范实现可能只回 IsTruncated=true 而不给 token，此时若照原样产出
            // (truncated=true, nextCursor=null)，UI 会渲染一个**点了也无新数据**的空转按钮，
            // 且翻页控制器在 cursor==null 时会把累积结果清回第 1 页并静默吞掉错误。
            // ⇒ 缺 / 空 token 时降级为「未截断」并留一次告警：宁可少给「加载更多」，
            // 也不给一个必然空转、还会回缩已得结果的入口。
            val effectiveTruncated = isTruncated && nextToken != null
            if (isTruncated && nextToken == null) {
                AppLog.w(
                    "S3DirectoryList",
                    "S3 列举响应 IsTruncated=true 但未返回 NextContinuationToken（非规范端点）——"
                        + "按 RemoteListPage 不变式降级为未截断，不再渲染空转的「加载更多」"
                )
            }
            ParsedListObjects(
                entries = all,
                isTruncated = effectiveTruncated,
                nextToken = nextToken
            )
        } catch (e: Throwable) {
            AppLog.w("S3DirectoryList", "S3 目录列举响应解析失败（保守降级为失败）", e)
            null
        }
    }

    private fun childNodes(parent: Node, localName: String): List<Node> {
        val out = mutableListOf<Node>()
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            val name = n.localName ?: n.nodeName.substringAfter(':')
            if (name.equals(localName, ignoreCase = true)) out.add(n)
        }
        return out
    }
}
