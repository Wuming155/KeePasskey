package com.keepasskey.sync.webdav

import com.keepasskey.core.log.AppLog
import com.keepasskey.sync.model.cleanEtag
import org.w3c.dom.Node
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

/**
 * WebDAV PROPFIND (multistatus) 响应解析与 HTTP 日期解析（RFC 4918）。
 *
 * 集中承载 DOM 解析、XXE 纵深防御与「解析失败回退空元数据」语义；
 * 日志 tag 与原实现保持一致（"WebDavSyncProvider"）。
 */
internal object WebDavPropfindParser {

    private const val TAG = "WebDavSyncProvider"

    data class ParsedPropfind(
        val etag: String,
        val contentLength: Long,
        val lastModifiedMillis: Long,
        val isDirectory: Boolean
    )

    fun parse(xml: String): ParsedPropfind {
        if (xml.isBlank()) {
            return ParsedPropfind("", -1L, 0L, false)
        }
        return try {
            // L2 整改：与 KdbxXmlParser 同级的 XXE 纵深防御——禁用 DTD 与外部实体，
            // 防御恶意/被劫持的 WebDAV 服务端返回带 XXE payload 的 PROPFIND 响应
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // ISSUE-P3-10 子项 3：逐项设置并留痕——原 runCatching 空吞使「加固特性未生效」
                // 完全不可观测，且首项失败会连带后续三项根本不被尝试
                applyXxeGuardFeature(this, "http://apache.org/xml/features/disallow-doctype-decl", true)
                applyXxeGuardFeature(this, "http://xml.org/sax/features/external-general-entities", false)
                applyXxeGuardFeature(this, "http://xml.org/sax/features/external-parameter-entities", false)
                applyXxeGuardFeature(this, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(xml.byteInputStream())
            val root = doc.documentElement

            fun findNodes(node: Node, targetLocalName: String, results: MutableList<Node>) {
                val name = node.localName ?: node.nodeName.substringAfter(':')
                if (name.equals(targetLocalName, ignoreCase = true)) {
                    results.add(node)
                }
                val children = node.childNodes
                for (i in 0 until children.length) {
                    findNodes(children.item(i), targetLocalName, results)
                }
            }

            val etagNodes = mutableListOf<Node>()
            findNodes(root, "getetag", etagNodes)
            val etag = etagNodes.firstOrNull()?.textContent?.trim().orEmpty().cleanEtag()

            val lengthNodes = mutableListOf<Node>()
            findNodes(root, "getcontentlength", lengthNodes)
            // 节点缺失以 -1 哨兵标记（回退 HTTP 头）；节点存在（含 0，零字节文件）必须如实采信——
            // 207 响应的 HTTP Content-Length 是 XML 报文自身大小，误当文件大小会让零字节文件
            // 元数据撒谎并污染同步基线比较
            val contentLength = lengthNodes.firstOrNull()?.textContent?.trim()?.toLongOrNull() ?: -1L

            val modNodes = mutableListOf<Node>()
            findNodes(root, "getlastmodified", modNodes)
            val lastModifiedStr = modNodes.firstOrNull()?.textContent?.trim().orEmpty()
            val lastModifiedMillis = parseHttpDate(lastModifiedStr)

            val collectionNodes = mutableListOf<Node>()
            findNodes(root, "collection", collectionNodes)
            val isDirectory = collectionNodes.isNotEmpty()

            ParsedPropfind(etag, contentLength, lastModifiedMillis, isDirectory)
        } catch (e: Exception) {
            // P3-17 整改：解析失败至少落日志，不再静默吞掉（回退空元数据语义保留，
            // 由调用方按「缺失」回退 HTTP 头哨兵处理）
            AppLog.w(TAG, "PROPFIND 响应 XML 解析失败，回退空元数据", e)
            ParsedPropfind("", -1L, 0L, false)
        }
    }

    /**
     * ISSUE-P3-10 子项 3：逐项应用 DOM 解析器 XXE 加固特性，**失败即落告警且不中断**。
     *
     * 不 fail-fast 的理由：加固特性在部分实现（如 Android Expat 后端）上不受支持，
     * 若因此判定 PROPFIND 响应非法，则一个实现差异会让所有 WebDAV 同步直接失败；
     * 且 `isExpandEntityReferences = false` 与「不加载外部 DTD」的默认语义仍在，
     * 解析失败路径另有外层 catch 留痕。故保留「尽力加固 + 可观测告警」语义。
     */
    private fun applyXxeGuardFeature(factory: DocumentBuilderFactory, feature: String, enabled: Boolean) {
        try {
            factory.setFeature(feature, enabled)
        } catch (e: Exception) {
            AppLog.w(TAG, "DOM 解析器 XXE 加固特性不受支持，已跳过该项: $feature=$enabled", e)
        }
    }

    fun parseHttpDate(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        )
        for (format in formats) {
            try {
                val sdf = SimpleDateFormat(format, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }
                val date = sdf.parse(dateStr)
                if (date != null) return date.time
            } catch (_: Exception) {}
        }
        return 0L
    }
}
