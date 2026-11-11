package com.keepasskey.sync.webdav

import com.keepasskey.core.log.AppLog
import com.keepasskey.sync.model.cleanEtag
import org.w3c.dom.Node
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * WebDAV PROPFIND (multistatus) 响应解析与 HTTP 日期解析（RFC 4918）。
 *
 * 集中承载 DOM 解析、XXE 纵深防御与「解析失败即失败」语义：两种通道必须分开——
 * - **解析失败**（空白 / XML 损坏或截断 / 根元素不是 multistatus）→ [parse] 返回 null；
 * - **属性缺失**（报文合法但没有对应 DAV 属性节点）→ 返回成功，由各字段哨兵值表达。
 * 日志 tag 与原实现保持一致（"WebDavSyncProvider"）。
 */
internal object WebDavPropfindParser {

    private const val TAG = "WebDavSyncProvider"

    /**
     * PROPFIND DOM 遍历深度上限（ISSUE-P0-09）。
     * 合法 multistatus 报文嵌套不超过 ~8 层，64 已留足裕量；
     * 超过该深度的节点不遍历、内容不采信（等价拒绝）。
     */
    private const val MAX_XML_DEPTH = 64

    data class ParsedPropfind(
        val etag: String,
        val contentLength: Long,
        val lastModifiedMillis: Long,
        val isDirectory: Boolean
    )

    /**
     * PROPFIND Depth:1 目录列举中的一个子资源（ISSUE-P3-387）。
     *
     * 保守降级：解析失败时 [parseChildren] 返回 null（**不是**空列表），
     * 调用方必须把 null 当失败，禁止伪装成「空目录」。
     */
    data class ParsedPropfindChild(
        val href: String,
        val etag: String,
        val contentLength: Long,
        val lastModifiedMillis: Long,
        val isDirectory: Boolean
    )

    /**
     * 解析 Depth:0 元数据响应。
     *
     * @return 报文不是可采信的 multistatus 时返回 **null**——空白体、XML 损坏/截断、
     *   被网关替换成 HTML 错误页等形态都必须由调用方按失败处理，**不得折成「属性缺失」**：
     *   折空会让上层把「远端元数据不可知」当成「远端没有 ETag」，进而沿无锁路径覆盖他端写入。
     *   报文合法但缺属性仍是成功（各字段走空串 / -1 / 0 哨兵，调用方可回退可信 HTTP 头）。
     */
    fun parse(xml: String): ParsedPropfind? {
        if (xml.isBlank()) {
            AppLog.w(TAG, "PROPFIND 响应为空，按解析失败处理（不折成空元数据）")
            return null
        }
        return try {
            // L2 整改：与 KdbxXmlParser 同级的 XXE 纵深防御——防御恶意/被劫持的 WebDAV 服务端
            // 返回带 XXE payload 的 PROPFIND 响应。防线由 [newHardenedBuilder] 装配的空实体
            // EntityResolver 兑现；ENG-20261111-01 删除了在本仓唯一的运行平台（Android）上一律
            // 抛异常、零效果的特性设置。ISSUE-P3-172：工厂按线程缓存一次。
            val builder = newHardenedBuilder()
            val doc = builder.parse(xml.byteInputStream())
            val root = doc.documentElement
            if (root == null || !isMultistatusRoot(root)) {
                AppLog.w(TAG, "PROPFIND 响应根元素不是 multistatus，按解析失败处理（不折成空元数据）")
                return null
            }

            val etagNodes = findNodes(root, "getetag")
            val etag = etagNodes.firstOrNull()?.textContent?.trim().orEmpty().cleanEtag()

            val lengthNodes = findNodes(root, "getcontentlength")
            // 节点缺失以 -1 哨兵标记（回退 HTTP 头）；节点存在（含 0，零字节文件）必须如实采信——
            // 207 响应的 HTTP Content-Length 是 XML 报文自身大小，误当文件大小会让零字节文件
            // 元数据撒谎并污染同步基线比较
            val contentLength = lengthNodes.firstOrNull()?.textContent?.trim()?.toLongOrNull() ?: -1L

            val modNodes = findNodes(root, "getlastmodified")
            val lastModifiedStr = modNodes.firstOrNull()?.textContent?.trim().orEmpty()
            val lastModifiedMillis = parseHttpDate(lastModifiedStr)

            val collectionNodes = findNodes(root, "collection")
            val isDirectory = collectionNodes.isNotEmpty()

            ParsedPropfind(etag, contentLength, lastModifiedMillis, isDirectory)
        } catch (e: Throwable) {
            // ISSUE-P0-09：捕获面扩到 Throwable——超深 XML 的 StackOverflowError 等属于
            // Error 而非 Exception，仅捕 Exception 时 Error 穿透 provider 的 runCatching
            // （会包成 Result.failure）经引擎 getOrThrow 原样重抛、杀死进程；远端（或
            // 系统 CA 级 MITM）可单方面构造该响应，必须在解析边界就地遏制。
            // ENG-20261028-32：遏制后返回 null（解析失败），不再回退空元数据——后者会让
            // 「远端元数据不可知」伪装成「远端没有 ETag」。
            AppLog.w(TAG, "PROPFIND 响应 XML 解析失败（按失败处理，不折成空元数据）", e)
            null
        }
    }

    /**
     * 解析 Depth:1 multistatus 的全部 `<response>` 子资源（ISSUE-P3-387 目录浏览）。
     *
     * - 与 [parse] 共用同一加固 DOM 工厂与 XXE 防线；
     * - **保守降级**：空白输入 / XML 非法 / DOM 不含 multistatus 根时返回 **null**（失败），
     *   不得返回 `emptyList()`——「解析失败」与「真空目录」不可混同（kp2a 教训）；
     * - 每个 response 内按文档序取**首个** getetag / getcontentlength / getlastmodified /
     *   collection（与 Depth:0 元数据通道同口径，多 propstat 时不二次展开求并集）。
     */
    fun parseChildren(xml: String): List<ParsedPropfindChild>? {
        if (xml.isBlank()) return null
        return try {
            val builder = newHardenedBuilder()
            val doc = builder.parse(xml.byteInputStream())
            val root = doc.documentElement
            if (root == null || !isMultistatusRoot(root)) return null

            val responses = findNodes(root, "response")
            // multistatus 合法但零 response：视为真空目录（服务端明确列举无子项）
            responses.map { response ->
                val hrefNodes = findNodes(response, "href")
                val href = hrefNodes.firstOrNull()?.textContent?.trim().orEmpty()
                val propNodes = findNodes(response, "prop")
                val scope = propNodes.firstOrNull() ?: response
                val etag = findNodes(scope, "getetag")
                    .firstOrNull()?.textContent?.trim().orEmpty().cleanEtag()
                val contentLength = findNodes(scope, "getcontentlength")
                    .firstOrNull()?.textContent?.trim()?.toLongOrNull() ?: -1L
                val lastModifiedMillis = parseHttpDate(
                    findNodes(scope, "getlastmodified")
                        .firstOrNull()?.textContent?.trim().orEmpty()
                )
                val isDirectory = findNodes(scope, "collection").isNotEmpty()
                ParsedPropfindChild(
                    href = href,
                    etag = etag,
                    contentLength = contentLength,
                    lastModifiedMillis = lastModifiedMillis,
                    isDirectory = isDirectory
                )
            }
        } catch (e: Throwable) {
            AppLog.w(TAG, "PROPFIND 目录列举响应解析失败（保守降级为失败，不伪装空目录）", e)
            null
        }
    }

    /**
     * 根元素是否为 `DAV:multistatus`（按 localName 忽略大小写，兼容任意命名空间前缀与无前缀形态）。
     * 非 multistatus 的 207/200 报文（典型如被网关替换的 HTML 错误页）一律不作为元数据采信。
     */
    private fun isMultistatusRoot(root: Node): Boolean {
        val rootName = root.localName ?: root.nodeName.substringAfter(':')
        return rootName.equals("multistatus", ignoreCase = true)
    }

    /**
     * 遍历 DOM 子树，按文档序收集 `localName` 等于 [targetLocalName]（忽略大小写）的节点。
     *
     * ISSUE-P0-09：**显式栈迭代实现 + 深度上限 [MAX_XML_DEPTH]**——原递归实现对超深嵌套
     * XML（远端可单方面构造）抛 `StackOverflowError`；迭代实现遍历深度恒有界，
     * 超过上限的更深层节点直接跳过（该内容不被采信，等价拒绝）。
     * 按逆序压栈保持与递归版一致的先序文档序，`firstOrNull()` 语义不变。
     */
    private fun findNodes(root: Node, targetLocalName: String): List<Node> {
        val results = mutableListOf<Node>()
        val stack = ArrayDeque<Pair<Node, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            val name = node.localName ?: node.nodeName.substringAfter(':')
            if (name.equals(targetLocalName, ignoreCase = true)) {
                results.add(node)
            }
            if (depth >= MAX_XML_DEPTH) continue
            val children = node.childNodes
            for (i in children.length - 1 downTo 0) {
                stack.addLast(children.item(i) to depth + 1)
            }
        }
        return results
    }

    /**
     * ISSUE-P3-172：DOM 工厂**按线程**构建一次。
     *
     * `DocumentBuilderFactory` 除 `newDocumentBuilder()` 外的实例状态不可并发共享，
     * 故用 `ThreadLocal` 而非进程级单例——两种写法都只消除「每次响应重建工厂」的开销，
     * 不改变解析语义；每次解析仍取全新的 `DocumentBuilder`。
     *
     * ENG-20261111-01：此处曾逐项设置 4 个 Xerces/SAX 特性（`disallow-doctype-decl`、
     * `external-general-entities`、`external-parameter-entities`、`load-external-dtd`）并
     * `setXIncludeAware(false)`。真机日志与源码复核确认 Android 平台实现
     * （`org.apache.harmony.xml.parsers.DocumentBuilderFactoryImpl`）对这 5 项一律抛异常，
     * 只经容错包装落 WARN、从未改变平台行为；本仓只交付 Android APK，故这些调用与两个
     * 容错包装一并删除，外部实体防线只剩 [newHardenedBuilder]（设备哨兵见
     * `WebDavPropfindParserDeviceTest`）。
     *
     * `isExpandEntityReferences = false` 是平台**接受**的设置，保留裸赋值：删除会把工厂状态
     * 退回默认 `true`，属行为变化而非清理无效代码。
     */
    private val hardenedFactories = ThreadLocal.withInitial {
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
    }

    private fun hardenedFactory(): DocumentBuilderFactory = hardenedFactories.get()

    /**
     * 新建已加固的 `DocumentBuilder`（ISSUE-P1-191 补的平台侧有效防线）。
     *
     * 空输入 [EntityResolver] 是 Android 与桌面 JVM 上**唯一**生效的外部实体拦截点：外部实体
     * （含 DOCTYPE 外部子集与参数实体）一律以**空输入**兑现，使被劫持/恶意的服务端无法借
     * PROPFIND 读取本应用私有目录（缓存内是完整 KDBX 密文快照）。真机证明见
     * `WebDavPropfindParserDeviceTest.真机平台DOM不得展开外部实体` 的哨兵断言。
     */
    private fun newHardenedBuilder(): DocumentBuilder =
        hardenedFactory().newDocumentBuilder().apply {
            setEntityResolver(EntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) })
        }

    /** 远端时间戳的三种常见形态（RFC 1123 / ISO-8601 秒 / ISO-8601 毫秒，均为 GMT） */
    private val HTTP_DATE_PATTERNS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    )

    /**
     * ISSUE-P3-172：日期格式按线程构建一次后复用。
     *
     * `SimpleDateFormat` 非线程安全，故按线程持有、在本方法内串行复用（顺序与「首个可解析
     * 的格式胜出」语义均与原来逐次新建时一致）。
     */
    private val httpDateFormats = ThreadLocal.withInitial {
        HTTP_DATE_PATTERNS.map { pattern ->
            SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
        }
    }

    fun parseHttpDate(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        for (sdf in httpDateFormats.get()) {
            try {
                val date = sdf.parse(dateStr)
                if (date != null) return date.time
            } catch (e: Exception) {
                AppLog.d(TAG, "HTTP 日期格式不匹配，尝试下一格式: ${e.javaClass.simpleName}")
            }
        }
        AppLog.w(TAG, "HTTP 日期无法解析为任何已知格式，按 0 处理")
        return 0L
    }
}
