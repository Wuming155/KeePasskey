package com.keepasskey.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebDAV 默认端点 = 坚果云（用户指示：避免每次重敲示例 URL）。
 */
class WebDavDefaultsTest {

    @Test
    fun `默认端点为坚果云 HTTPS`() {
        assertEquals("https://dav.jianguoyun.com/dav/", WebDavDefaults.NUTSTORE_URL)
        assertTrue(WebDavDefaults.NUTSTORE_URL.startsWith("https://"))
        assertTrue(WebDavDefaults.NUTSTORE_URL.contains("jianguoyun.com"))
    }

    @Test
    fun `默认远程路径为 keepasskey kdbx`() {
        assertEquals("keepasskey.kdbx", WebDavDefaults.DEFAULT_REMOTE_PATH)
    }

    @Test
    fun `打开库占位含坚果云完整形态`() {
        assertTrue(WebDavDefaults.NUTSTORE_URL_PLACEHOLDER.startsWith(WebDavDefaults.NUTSTORE_URL))
        assertTrue(WebDavDefaults.NUTSTORE_URL_PLACEHOLDER.endsWith(".kdbx"))
    }
}
