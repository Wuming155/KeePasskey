package com.keepasskey.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-387：PROPFIND Depth:1 目录列举解析。
 *
 * 保守降级口径：解析失败必须返回 null（调用方禁止伪装空目录）；
 * 合法 multistatus 返回子项列表；空白 / 非 multistatus 返回 null。
 */
class WebDavPropfindListParserTest {

    @Test
    fun `正常 multistatus 列举解析子项`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8" ?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/dav/files/user/vaults/</D:href>
                <D:propstat>
                  <D:prop>
                    <D:resourcetype><D:collection/></D:resourcetype>
                  </D:prop>
                </D:propstat>
              </D:response>
              <D:response>
                <D:href>/dav/files/user/vaults/keepasskey.kdbx</D:href>
                <D:propstat>
                  <D:prop>
                    <D:getetag>"abc"</D:getetag>
                    <D:getcontentlength>2048</D:getcontentlength>
                    <D:getlastmodified>Wed, 12 Feb 2025 15:00:00 GMT</D:getlastmodified>
                    <D:resourcetype/>
                  </D:prop>
                </D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        val children = WebDavPropfindParser.parseChildren(xml)
        assertNotNull(children)
        assertEquals(2, children!!.size)
        assertTrue(children[0].isDirectory)
        assertFalse(children[1].isDirectory)
        assertEquals(2048L, children[1].contentLength)
        assertEquals("keepasskey.kdbx", children[1].href.substringAfterLast('/'))
    }

    @Test
    fun `空白输入保守降级为失败而非空目录`() {
        assertNull(WebDavPropfindParser.parseChildren(""))
        assertNull(WebDavPropfindParser.parseChildren("   "))
    }

    @Test
    fun `非 multistatus 根元素保守降级为失败`() {
        assertNull(WebDavPropfindParser.parseChildren("<html><body>not dav</body></html>"))
    }

    @Test
    fun `非法 XML 保守降级为失败`() {
        assertNull(WebDavPropfindParser.parseChildren("<?xml version=\"1.0\"?><D:multistatus>"))
    }

    @Test
    fun `合法空 multistatus 视为空目录成功`() {
        val xml = """
            <?xml version="1.0"?>
            <D:multistatus xmlns:D="DAV:"></D:multistatus>
        """.trimIndent()
        val children = WebDavPropfindParser.parseChildren(xml)
        assertNotNull(children)
        assertTrue(children!!.isEmpty())
    }
}
