package com.keepasskey.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ISSUE-P3-396：PROPFIND href → 相对端点路径（下钻/选中/同步不得重复拼接端点 path）。
 */
class WebDavBrowsePathResolutionTest {

    private val serverUrl = "https://cloud.example.com/remote.php/dav/files/user/"
    private val dirPath = ""

    @Test
    fun `完整 URL href 剥掉 serverUrl 后得到相对路径`() {
        val path = WebDavDirectoryList.relativeResourcePath(
            "https://cloud.example.com/remote.php/dav/files/user/vaults/keepasskey.kdbx",
            serverUrl,
            dirPath
        )
        assertEquals("vaults/keepasskey.kdbx", path)
    }

    @Test
    fun `绝对路径 href 剥掉端点 path 前缀 不再重复 remote php`() {
        // Nextcloud 典型形态
        val path = WebDavDirectoryList.relativeResourcePath(
            "/remote.php/dav/files/user/vaults/",
            serverUrl,
            ""
        )
        assertEquals("vaults", path)
    }

    @Test
    fun `绝对路径 href 在子目录列举时仍正确相对化`() {
        val path = WebDavDirectoryList.relativeResourcePath(
            "/remote.php/dav/files/user/vaults/sub/keepasskey.kdbx",
            serverUrl,
            "vaults"
        )
        assertEquals("vaults/sub/keepasskey.kdbx", path)
    }

    @Test
    fun `相对集合 href 直接归一化`() {
        val path = WebDavDirectoryList.relativeResourcePath("keepasskey.kdbx", serverUrl, "vaults")
        assertEquals("vaults/keepasskey.kdbx", path)
        assertEquals("Passkeys", WebDavDirectoryList.relativeResourcePath("Passkeys", serverUrl, ""))
    }

    @Test
    fun `空白 href 返回 null`() {
        assertNull(WebDavDirectoryList.relativeResourcePath("", serverUrl, dirPath))
    }

    @Test
    fun `serverUrlPath 取出 path 组件`() {
        assertEquals("/remote.php/dav/files/user", WebDavDirectoryList.serverUrlPath(serverUrl))
        // 根路径 path="/" 归一后为空 ⇒ null（相对化时不裁剪任何前缀）
        assertNull(WebDavDirectoryList.serverUrlPath("https://cloud.example.com/"))
    }
}
