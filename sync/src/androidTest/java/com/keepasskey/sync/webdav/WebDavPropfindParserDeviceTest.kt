package com.keepasskey.sync.webdav

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * WebDAV PROPFIND 解析 · **设备侧真机验证**（平台 DOM 实现差异）。
 *
 * 为什么必须真机：[WebDavPropfindParser] 走的是 JAXP `DocumentBuilderFactory`，
 * **Android 上的实现是平台自带的 Expat 后端，不是桌面 JVM 的 Xerces**。该类的三道防线
 * 全部依赖解析器实现的具体行为，宿主单测 green 什么都不说明：
 * 1. XXE 加固的四项特性（`disallow-doctype-decl` 等是 Apache/Xerces 特性名）在平台实现上
 *    **可能整体不受支持**——[applyXxeGuardFeature] 对此只落告警不 fail-fast（ISSUE-P3-10 子项 3
 *    的既定取舍），于是「加固是否真的生效」在真机上是一个**未被证明的事实**。若平台实现展开
 *    外部实体，被劫持的服务端可读取本应用私有目录下的文件（缓存里是完整 KDBX 密文快照）。
 * 2. ISSUE-P0-09 的超深 XML 遏制：`StackOverflowError` 是否真被 `catch (Throwable)` 兜住，
 *    取决于 ART 上平台解析器**自身的递归深度**（解析期即可能爆栈，与遍历期的深度上限是两件事）。
 * 3. HTTP 日期解析依赖 `SimpleDateFormat` 的星期名 / 月名数据——Android 侧由 ICU 提供。
 *
 * 本用例的对照口径与 `database` 模块的 `KdbxXmlDtdRejectionDeviceTest`（SAX 路径）一致：
 * 那里已经证实过「Android 与桌面在同一项 SAX 能力上行为不同」。
 */
@RunWith(AndroidJUnit4::class)
class WebDavPropfindParserDeviceTest {

    /** 哨兵明文：只可能经由「外部实体被展开」进入解析结果。 */
    private val canary: String = "XXE-CANARY-${System.nanoTime()}${Build.MODEL.filter(Char::isLetterOrDigit)}"

    private val canaryFile: File by lazy {
        File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "propfind-xxe-canary.txt"
        )
    }

    @Test
    fun 真机加固不得让整篇PROPFIND解析落空() {
        // ISSUE-P1-191 回归锁：Android 平台 DOM（Harmony DocumentBuilderFactoryImpl）对
        // `setXIncludeAware(false)` 直接抛 UnsupportedOperationException。该调用曾以裸赋值写在
        // 加固块里、不在任何容错面内，异常顺 ThreadLocal 初始化落到 parse 的 catch(Throwable)，
        // 于是**真机上每一次 PROPFIND 解析都返回空元数据**（etag 空 / 长度 -1 / 非目录），
        // 而桌面 Xerces 支持该项 ⇒ 宿主单测 100% 绿。合法报文必须逐项真实采信，缺一项即红。
        val xml = multistatus(
            getetag = "\"live-etag\"",
            contentLength = "12345",
            lastModified = "Wed, 21 Oct 2015 07:28:00 GMT"
        )
        val parsed = WebDavPropfindParser.parse(xml)
        assertEquals("真机上合法 PROPFIND 报文必须解析出 etag", "live-etag", parsed.etag)
        assertEquals("真机上必须解析出 getcontentlength", 12_345L, parsed.contentLength)
        assertEquals("真机上必须解析出 getlastmodified", FROZEN_EPOCH_MILLIS, parsed.lastModifiedMillis)
        assertFalse("无 collection 元素时不得判为目录", parsed.isDirectory)
        assertTrue(
            "含 collection 元素时必须判为目录",
            WebDavPropfindParser.parse(multistatus(collection = true)).isDirectory
        )
    }

    @Test
    fun 真机平台DOM不得展开外部实体() {
        writeCanary()
        val fileEntity = "file://${canaryFile.absolutePath}"
        try {
            val payloads = mapOf(
                // 经典文件实体：被劫持的服务端借此读取本应用私有目录（缓存内是完整 KDBX 密文快照）
                "general-external-entity" to
                    """<!DOCTYPE D:multistatus [<!ENTITY xxe SYSTEM "$fileEntity">]>""" +
                    """<D:multistatus xmlns:D="DAV:"><D:response><D:propstat><D:prop>""" +
                    """<D:getetag>&xxe;</D:getetag></D:prop></D:propstat></D:response></D:multistatus>""",
                // 外部 DTD 子集：`load-external-dtd=false` 针对的正是这条通路
                "external-dtd-subset" to
                    """<!DOCTYPE D:multistatus SYSTEM "$fileEntity">""" +
                    """<D:multistatus xmlns:D="DAV:"><D:response><D:propstat><D:prop>""" +
                    """<D:getetag>plain-etag</D:getetag></D:prop></D:propstat></D:response></D:multistatus>"""
            )
            for ((name, xml) in payloads) {
                val parsed = WebDavPropfindParser.parse(xml)
                assertFalse(
                    "$name：外部实体 / 外部 DTD 在真机上被展开，服务端可读到 ${canaryFile.absolutePath}",
                    parsed.etag.contains(canary)
                )
                println(
                    "XXE-DEVICE|payload=$name etag=${parsed.etag.take(MAX_LOGGED_ETAG_CHARS)}…" +
                        " length=${parsed.contentLength} api=${Build.VERSION.SDK_INT}"
                )
            }
            // 反向对照：同一份报文去掉 DOCTYPE 必须正常解析，
            // 否则上面的「未泄漏」只是「整篇没解析」的假阴性。
            val control = WebDavPropfindParser.parse(multistatus(getetag = "\"etag-on-device\""))
            assertEquals("对照报文必须能解析出 etag", "etag-on-device", control.etag)
        } finally {
            canaryFile.delete()
        }
    }

    @Test
    fun 超过信任深度的节点不被采信且超深报文不杀死进程() {
        // 深度上限（MAX_XML_DEPTH = 64）之内：multistatus(0)/response(1)/propstat(2)/prop(3)/wrapper*4.. → 采信
        val withinCap = nested(depth = 10, getetag = "shallow-etag")
        assertEquals("上限内的 etag 必须被采信", "shallow-etag", WebDavPropfindParser.parse(withinCap).etag)

        // 上限之外：内容不采信（等价拒绝），但报文本身合法，不得抛错
        val beyondCap = nested(depth = 90, getetag = "deep-etag")
        val deep = WebDavPropfindParser.parse(beyondCap)
        assertEquals("超过信任深度的 etag 不得被采信", "", deep.etag)
        assertEquals("超过信任深度的长度不得被采信", -1L, deep.contentLength)

        // 解析期爆栈探针：平台实现在建 DOM 时即可能递归过深，必须被 catch(Throwable) 遏制
        val absurd = nested(depth = PARSE_BOMB_DEPTH, getetag = "bomb-etag")
        val bomb = WebDavPropfindParser.parse(absurd)
        assertEquals("超深报文必须回退空 etag 而非抛错穿透", "", bomb.etag)
        assertEquals("超深报文必须回退 -1 长度哨兵", -1L, bomb.contentLength)
        println("DEPTH-DEVICE|parseBomb=$PARSE_BOMB_DEPTH api=${Build.VERSION.SDK_INT}")
    }

    @Test
    fun 元数据语义在真机保持_零字节与哨兵与目录判定() {
        val zeroByte = multistatus(
            getetag = "W/\"abc123\"",
            contentLength = "0",
            lastModified = "Wed, 21 Oct 2015 07:28:00 GMT"
        )
        val parsed = WebDavPropfindParser.parse(zeroByte)
        assertEquals("弱 etag 前缀与引号必须被清洗", "abc123", parsed.etag)
        assertEquals("零字节文件的长度必须如实采信（不得回退 -1 哨兵）", 0L, parsed.contentLength)
        assertEquals("RFC1123 时间戳必须解析为冻结时刻", FROZEN_EPOCH_MILLIS, parsed.lastModifiedMillis)
        assertFalse("无 collection 元素时不得判为目录", parsed.isDirectory)

        // 节点缺失才是「回退 HTTP 头」的 -1 哨兵
        val missing = WebDavPropfindParser.parse(multistatus(getetag = "\"x\""))
        assertEquals("getcontentlength 缺失时才回退 -1 哨兵", -1L, missing.contentLength)

        val collection = WebDavPropfindParser.parse(multistatus(collection = true))
        assertTrue("含 collection 元素时必须判为目录", collection.isDirectory)
    }

    @Test
    fun HTTP日期三形态在真机ICU下解析为同一时刻() {
        val cases = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz" to "Wed, 21 Oct 2015 07:28:00 GMT",
            "EEE, dd MMM yyyy HH:mm:ss zzz" to "Wed, 21 Oct 2015 07:28:00 UTC",
            "ISO-8601 秒" to "2015-10-21T07:28:00Z",
            "ISO-8601 毫秒" to "2015-10-21T07:28:00.000Z"
        )
        for ((shape, value) in cases) {
            assertEquals(
                "形态 $shape 在真机上解析不符（ICU 星期名 / 月名 / 时区缩写差异）：$value",
                FROZEN_EPOCH_MILLIS,
                WebDavPropfindParser.parseHttpDate(value)
            )
        }
        // 不可识别的时间一律 0（缺失），不得抛异常
        assertEquals(0L, WebDavPropfindParser.parseHttpDate(""))
        assertEquals(0L, WebDavPropfindParser.parseHttpDate("not-a-date"))
    }

    @Test
    fun 畸形报文一律回退空元数据() {
        for (malformed in listOf("", "   ", "<not xml", "<D:multistatus xmlns:D=\"DAV:\">", "\u0000\u0001")) {
            val parsed = WebDavPropfindParser.parse(malformed)
            assertEquals("畸形输入 [$malformed] 必须回退空 etag", "", parsed.etag)
            assertEquals("畸形输入 [$malformed] 必须回退 -1 长度", -1L, parsed.contentLength)
            assertEquals("畸形输入 [$malformed] 必须回退 0 时间", 0L, parsed.lastModifiedMillis)
            assertFalse("畸形输入 [$malformed] 不得判为目录", parsed.isDirectory)
        }
    }

    // ---------- 报文构造 ----------

    private fun multistatus(
        getetag: String? = null,
        contentLength: String? = null,
        lastModified: String? = null,
        collection: Boolean = false
    ): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?>""")
        append("""<D:multistatus xmlns:D="DAV:"><D:response>""")
        if (collection) append("<D:collection/>")
        append("<D:propstat><D:prop>")
        getetag?.let { append("<D:getetag>$it</D:getetag>") }
        contentLength?.let { append("<D:getcontentlength>$it</D:getcontentlength>") }
        lastModified?.let { append("<D:getlastmodified>$it</D:getlastmodified>") }
        append("</D:prop></D:propstat></D:response></D:multistatus>")
    }

    /** 把 `getetag` 埋到 [depth] 层包装之下（multistatus→response→propstat→prop 之后再加 [depth] 层）。 */
    private fun nested(depth: Int, getetag: String): String {
        val open = StringBuilder()
        val close = StringBuilder()
        repeat(depth) {
            open.append("<D:wrapper>")
            close.insert(0, "</D:wrapper>")
        }
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:"><D:response><D:propstat><D:prop>$open
            <D:getetag>$getetag</D:getetag>$close
            </D:prop></D:propstat></D:response></D:multistatus>
        """.trimIndent()
    }

    private fun writeCanary() {
        canaryFile.writeText(canary)
        assertTrue("哨兵文件必须落在真机私有目录上", canaryFile.exists() && canaryFile.length() > 0L)
    }

    private companion object {
        /** 与 `date -u -d '2015-10-21T07:28:00Z' +%s%3N` 同值（Python/ICU/JDK 三方独立复算一致）。 */
        private const val FROZEN_EPOCH_MILLIS = 1445412480000L

        /** 解析期爆栈探针深度：远超平台实现的实际递归承受力，用于验证遏制而非验证崩溃。 */
        private const val PARSE_BOMB_DEPTH = 5_000

        /** 日志里截断 etag 的字符数（哨兵值本身不进日志）。 */
        private const val MAX_LOGGED_ETAG_CHARS = 16
    }
}
