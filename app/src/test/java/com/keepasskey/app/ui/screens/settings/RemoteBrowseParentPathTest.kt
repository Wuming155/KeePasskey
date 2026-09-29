package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.sync.parentDirectoryPath
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ISSUE-P3-396：路径字段 → 父目录（仅初始选库使用；下钻不再二次取父）。
 */
class RemoteBrowseParentPathTest {

    @Test
    fun `文件路径取父目录`() {
        assertEquals("vaults", parentDirectoryPath("vaults/keepasskey.kdbx"))
        assertEquals("Passkeys/sub", parentDirectoryPath("Passkeys/sub/db.kdbx"))
    }

    @Test
    fun `单段目录在根下取空串（不误取为自身）`() {
        // 下钻 entry.path="Passkeys" 时应直接列 Passkeys，而非再取父成空串
        assertEquals("", parentDirectoryPath("Passkeys"))
    }

    @Test
    fun `空路径与尾斜杠归一`() {
        assertEquals("", parentDirectoryPath(""))
        assertEquals("", parentDirectoryPath("/"))
        assertEquals("", parentDirectoryPath("/keepasskey.kdbx"))
    }
}
