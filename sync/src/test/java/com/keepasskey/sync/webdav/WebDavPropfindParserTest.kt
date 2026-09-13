package com.keepasskey.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROPFIND 解析器单测（ISSUE-P0-09）：
 * 超深嵌套 XML 必须被遏制为「回退空元数据」——迭代化遍历 + 深度上限后
 * 不抛 `StackOverflowError`、不崩溃，超上限内容不被采信。
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

        val parsed = WebDavPropfindParser.parse(xml)
        assertEquals("abc123", parsed.etag)
        assertEquals(4096L, parsed.contentLength)
        assertTrue(parsed.isDirectory)
    }

    @Test
    fun `超深嵌套XML被遏制为回退元数据且不栈溢出（ISSUE-P0-09 负例）`() {
        // 远端可单方面构造的超深嵌套响应；原递归遍历在此形态下抛 StackOverflowError
        val depth = 5_000
        val deepXml = buildString {
            append("<?xml version=\"1.0\"?><root>")
            repeat(depth) { append("<a>") }
            append("<getetag>\"deep-etag\"</getetag>")
            repeat(depth) { append("</a>") }
            append("</root>")
        }

        val parsed = WebDavPropfindParser.parse(deepXml)
        // 超过深度上限的内容不采信：等价拒绝，回退空元数据（调用方回退 HTTP 头）
        assertEquals("", parsed.etag)
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

        assertEquals("shallow-etag", WebDavPropfindParser.parse(xml).etag)
    }
}
