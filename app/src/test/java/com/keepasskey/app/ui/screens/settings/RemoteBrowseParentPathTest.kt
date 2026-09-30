package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.sync.parentDirectoryPath
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 浏览种子目录：文件取父目录；完整 URL 取 path 后再取父；单段目录/根路径 → 端点根。
 */
class RemoteBrowseParentPathTest {

    @Test
    fun `文件路径取父目录`() {
        assertEquals("vaults", parentDirectoryPath("vaults/keepasskey.kdbx"))
        assertEquals("Passkeys/sub", parentDirectoryPath("Passkeys/sub/db.kdbx"))
        assertEquals("user@example.com", parentDirectoryPath("user@example.com/keepasskey.kdbx"))
    }

    @Test
    fun `单段目录在根下取空串`() {
        assertEquals("", parentDirectoryPath("Passkeys"))
        assertEquals("", parentDirectoryPath("keepasskey.kdbx"))
        assertEquals("", parentDirectoryPath("/keepasskey.kdbx"))
    }

    @Test
    fun `完整 URL 取 path 后再取父目录`() {
        // 坚果云：剥掉 NUTSTORE_URL 后再取父
        assertEquals(
            "user@example.com",
            parentDirectoryPath("https://dav.jianguoyun.com/dav/user@example.com/keepasskey.kdbx")
        )
        assertEquals(
            "user@example.com/folder",
            parentDirectoryPath("https://dav.jianguoyun.com/dav/user@example.com/folder/keepasskey.kdbx")
        )
        assertEquals("", parentDirectoryPath("https://dav.jianguoyun.com/dav/keepasskey.kdbx"))
        // 通用 URL：path=/dav/user@e.com/x.kdbx → 剥 /dav → user@e.com
        assertEquals(
            "user@example.com",
            parentDirectoryPath("https://other.example.com/dav/user@example.com/keepasskey.kdbx")
        )
    }

    @Test
    fun `空路径与尾斜杠归一`() {
        assertEquals("", parentDirectoryPath(""))
        assertEquals("", parentDirectoryPath("/"))
        assertEquals("", parentDirectoryPath("https://dav.jianguoyun.com/dav/"))
    }
}
