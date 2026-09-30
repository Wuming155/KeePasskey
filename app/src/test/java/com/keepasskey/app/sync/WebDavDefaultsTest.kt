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

    /**
     * ISSUE-P2-399：云端打开对话框改为「URL + 独立远端路径」模型后，
     * 旧「完整 URL 占位」常量已无消费方，随生产代码一并退役（测试资产修改已登记批次文档）。
     */
    @Test
    fun `默认端点与默认远程路径可拼接为完整远端形态`() {
        val full = WebDavDefaults.NUTSTORE_URL + "user@example.com/" + WebDavDefaults.DEFAULT_REMOTE_PATH
        assertTrue(full.startsWith("https://"))
        assertTrue(full.endsWith(".kdbx"))
    }
}
