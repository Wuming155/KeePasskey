package com.keepasskey.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROPFIND 元数据解析单测：
 * 1. ISSUE-P0-09——超深嵌套 XML 必须被遏制（迭代化遍历 + 深度上限，不抛 `StackOverflowError`，
 *    超上限内容不采信）；
 * 2. ENG-20261028-32——「解析失败」与「属性缺失」是两条通道：不可解析的报文（空白 / 损坏 /
 *    截断 / 根元素不是 multistatus）返回 null，合法但缺属性的报文仍返回成功并走哨兵值。
 */
class WebDavPropfindParserTest {

    @Test
    fun `正常 multistatus 解析元数据（行为不变）`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8" ?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:propstat>
                  <D:prop>
                    <D:getetag>"abc123"</D:getetag>
                    <D:getcontentlength>4096</D:getcontentlength>
                    <D:getlastmodified>Wed, 12 Feb 2025 15:00:00 GMT</D:getlastmodified>
                    <D:resourcetype><D:collection/></D:resourcetype>
                  </D:prop>
                </D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        val parsed = requireNotNull(WebDavPropfindParser.parse(xml)) { "合法 multistatus 必须可解析" }
        assertEquals("abc123", parsed.etag)
        assertEquals(4096L, parsed.contentLength)
        assertTrue(parsed.isDirectory)
    }

    @Test
    fun `报文合法但属性缺失仍算成功并回哨兵值`() {
        // getetag 空节点 + 无 getcontentlength / getlastmodified / collection：
        // 这是「属性缺失」通道（调用方据此回退可信 HTTP 头），不得与解析失败混同
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:"><D:response><D:propstat><D:prop>
              <D:getetag></D:getetag>
            </D:prop></D:propstat></D:response></D:multistatus>
        """.trimIndent()

        val parsed = requireNotNull(WebDavPropfindParser.parse(xml)) { "缺属性不等于解析失败" }
        assertEquals("", parsed.etag)
        assertEquals(-1L, parsed.contentLength)
        assertEquals(0L, parsed.lastModifiedMillis)
        assertFalse(parsed.isDirectory)
    }

    @Test
    fun `超深嵌套multistatus被遏制为不采信且不栈溢出（ISSUE-P0-09 负例）`() {
        // 远端可单方面构造的超深嵌套响应；原递归遍历在此形态下抛 StackOverflowError。
        // 报文本身合法 ⇒ 走「属性缺失」通道：超过深度上限的内容不采信（等价拒绝）
        val depth = 5_000
        val deepXml = buildString {
            append("<?xml version=\"1.0\"?><D:multistatus xmlns:D=\"DAV:\"><D:response><D:propstat><D:prop>")
            repeat(depth) { append("<D:wrapper>") }
            append("<D:getetag>\"deep-etag\"</D:getetag>")
            repeat(depth) { append("</D:wrapper>") }
            append("</D:prop></D:propstat></D:response></D:multistatus>")
        }

        val parsed = requireNotNull(WebDavPropfindParser.parse(deepXml)) { "超深报文仍须被就地遏制" }
        assertEquals("超过深度上限的内容不采信", "", parsed.etag)
        assertEquals(-1L, parsed.contentLength)
        assertFalse(parsed.isDirectory)
    }

    @Test
    fun `深度上限以内的合法嵌套仍正常解析`() {
        val xml = """
            <?xml version="1.0"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:propstat>
                  <D:prop><D:getetag>"shallow-etag"</D:getetag></D:prop>
                </D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        assertEquals("shallow-etag", requireNotNull(WebDavPropfindParser.parse(xml)).etag)
    }

    @Test
    fun `不可解析的报文返回null而非伪装成空元数据（ENG-20261028-32）`() {
        val cases = listOf(
            "" to "空白响应体",
            "   " to "全空白响应体",
            "<not xml" to "截断的 XML",
            "<D:multistatus xmlns:D=\"DAV:\">" to "未闭合的 multistatus",
            """"<?xml version="1.0"?><root><getetag>"etag"</getetag></root>""" to
                "根元素不是 multistatus（如被网关替换的错误页）",
            """<html><body><h1>502 Bad Gateway</h1></body></html>""" to
                "被替换成 HTML 错误页的响应体"
        )
        for ((xml, why) in cases) {
            assertNull(
                "解析失败必须显式失败（$why），不得折成「成功 + 空元数据」",
                WebDavPropfindParser.parse(xml)
            )
        }
    }
}