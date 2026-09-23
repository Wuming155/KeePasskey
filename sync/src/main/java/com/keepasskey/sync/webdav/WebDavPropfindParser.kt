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
 * 集中承载 DOM 解析、XXE 纵深防御与「解析失败回退空元数据」语义；
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

    fun parse(xml: String): ParsedPropfind {
        if (xml.isBlank()) {
            return ParsedPropfind("", -1L, 0L, false)
        }
        return try {
            // L2 整改：与 KdbxXmlParser 同级的 XXE 纵深防御——防御恶意/被劫持的 WebDAV 服务端
            // 返回带 XXE payload 的 PROPFIND 响应。平台侧真正生效的是 [newHardenedBuilder] 装配的
            // 空实体 EntityResolver；四项 Xerces 特性名在 Android 上全部不受支持（仅桌面生效）。
            // ISSUE-P3-172：工厂改为按线程缓存（原实现每次响应都重建并逐项设 6 个特性）
            val builder = newHardenedBuilder()
            val doc = builder.parse(xml.byteInputStream())
            val root = doc.documentElement

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
            // P3-17 整改：解析失败至少落日志，不再静默吞掉（回退空元数据语义保留，
            // 由调用方按「缺失」回退 HTTP 头哨兵处理）。
            // ISSUE-P0-09：捕获面扩到 Throwable——超深 XML 的 StackOverflowError 等属于
            // Error 而非 Exception，仅捕 Exception 时 Error 穿透 provider 的 runCatching
            // （会包成 Result.failure）经引擎 getOrThrow 原样重抛、杀死进程；远端（或
            // 系统 CA 级 MITM）可单方面构造该响应，必须在解析边界就地遏制为「回退空元数据」
            AppLog.w(TAG, "PROPFIND 响应 XML 解析失败，回退空元数据", e)
            ParsedPropfind("", -1L, 0L, false)
        }
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
     * ISSUE-P3-10 子项 3：逐项应用 DOM 解析器 XXE 加固特性，**失败即落告警且不中断**。
     *
     * 逐项设置并留痕：原 `runCatching` 空吞使「加固特性未生效」完全不可观测，
     * 且首项失败会连带后续三项根本不被尝试。
     *
     * 不 fail-fast 的理由：加固特性在部分实现（如 Android 平台 DOM）上不受支持，
     * 若因此判定 PROPFIND 响应非法，则一个实现差异会让所有 WebDAV 同步直接失败；
     * 解析失败路径另有外层 catch 留痕。故保留「尽力加固 + 可观测告警」语义。
     *
     * ISSUE-P1-191 更正此前的一句推定：「`isExpandEntityReferences = false` 与不加载外部 DTD
     * 的默认语义仍在」**在 Android 上并不成立**——真机实测这四项 Xerces 特性名全部抛
     * `ParserConfigurationException`（即全部未生效），平台实现上唯一可用的外部实体防线是
     * builder 侧的 `EntityResolver`（见 [newHardenedBuilder]）。
     *
     * ISSUE-P3-172：加固结果取决于平台能力（进程级常量），故告警现只在每线程首次
     * 建工厂时出现一次；原实现每次响应都重新探测一遍。
     */
    private fun applyXxeGuardFeature(factory: DocumentBuilderFactory, feature: String, enabled: Boolean) {
        try {
            factory.setFeature(feature, enabled)
        } catch (e: Exception) {
            AppLog.w(TAG, "DOM 解析器 XXE 加固特性不受支持，已跳过该项: $feature=$enabled", e)
        }
    }

    /**
     * ISSUE-P3-172：加固后的 DOM 工厂**按线程**构建一次。
     *
     * `DocumentBuilderFactory` 除 `newDocumentBuilder()` 外的实例状态不可并发共享，
     * 故用 `ThreadLocal` 而非进程级单例——两种写法都只消除「每次响应重建工厂 + 逐项设 6 个
     * 特性」的开销，不改变加固语义；每次解析仍取全新的 `DocumentBuilder`。
     */
    private val hardenedFactories = ThreadLocal.withInitial {
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // 以下四项在 Android 平台实现（`org.apache.harmony.xml.parsers.DocumentBuilderFactoryImpl`）
            // 上**全部**抛 ParserConfigurationException（真机实测见 WebDavPropfindParserDeviceTest），
            // 即「尝试过、未生效」；桌面 JVM（Xerces）才真正吃到特性项。
            // 平台侧的有效防线见 [newHardenedBuilder]。
            applyXxeGuardFeature(this, "http://apache.org/xml/features/disallow-doctype-decl", true)
            applyXxeGuardFeature(this, "http://xml.org/sax/features/external-general-entities", false)
            applyXxeGuardFeature(this, "http://xml.org/sax/features/external-parameter-entities", false)
            applyXxeGuardFeature(this, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            // ISSUE-P1-191：这两项曾以**裸赋值**写在这里，而平台实现的
            // `setXIncludeAware(false)` 直接抛 `UnsupportedOperationException`——它不在
            // [applyXxeGuardFeature] 的容错面内，异常顺着 ThreadLocal 初始化落到外层 catch，
            // 于是真机上**每一次** PROPFIND 解析整体落空（元数据全部回退 HTTP 头）。
            // 与特性项同法处理：尽力加固、失败留痕，绝不让一项可选加固否决整篇解析。
            applyXxeGuardSetting("isXIncludeAware=false") { isXIncludeAware = false }
            applyXxeGuardSetting("isExpandEntityReferences=false") { isExpandEntityReferences = false }
        }
    }

    private fun hardenedFactory(): DocumentBuilderFactory = hardenedFactories.get()

    /**
     * 逐项加固布尔开关：不受支持仅告警（与 [applyXxeGuardFeature] 同一容错口径，ISSUE-P1-191）。
     *
     * 必须是**非递归**的就地容错：本函数在 `hardenedFactories` 的初始化块内被调用，
     * 若再经 `hardenedFactory()` 取工厂会自入 `ThreadLocal.get()` 的重入循环。
     */
    private fun DocumentBuilderFactory.applyXxeGuardSetting(label: String, block: DocumentBuilderFactory.() -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            AppLog.w(TAG, "DOM 解析器加固开关不受支持，已跳过该项: $label", e)
        }
    }

    /**
     * 新建已加固的 `DocumentBuilder`（ISSUE-P1-191 补的平台侧有效防线）。
     *
     * 四项 Xerces 特性名在 Android 平台实现上全部不受支持（实测仅落告警），意味着
     * 「禁用外部实体 / 不加载外部 DTD」在设备上**从未真正生效**；JAXP 在平台实现上唯一
     * 可用的拦截点是 builder 侧的 [EntityResolver]——外部实体（含 DOCTYPE 外部子集与
     * 参数实体）一律以**空输入**兑现，使被劫持/恶意的服务端无法借 PROPFIND 读取本应用
     * 私有目录（缓存内是完整 KDBX 密文快照）。桌面 JVM 仍另有特性项把关，两条防线互不冲突。
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
