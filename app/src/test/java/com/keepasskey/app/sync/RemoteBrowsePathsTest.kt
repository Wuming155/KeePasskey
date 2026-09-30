package com.keepasskey.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对齐 keepass2android 文件选择器的路径/展示辅助。
 */
class RemoteBrowsePathsTest {

    @Test
    fun `面包屑段与点击路径`() {
        assertEquals(listOf("/"), RemoteBrowsePaths.breadcrumbSegments(""))
        assertEquals(listOf("/"), RemoteBrowsePaths.breadcrumbSegments("/"))
        assertEquals(listOf("/", "a", "b"), RemoteBrowsePaths.breadcrumbSegments("a/b"))
        assertEquals("", RemoteBrowsePaths.breadcrumbPathAt(listOf("/", "a", "b"), 0))
        assertEquals("a", RemoteBrowsePaths.breadcrumbPathAt(listOf("/", "a", "b"), 1))
        assertEquals("a/b", RemoteBrowsePaths.breadcrumbPathAt(listOf("/", "a", "b"), 2))
    }

    @Test
    fun `kdbx 过滤与副文案`() {
        assertTrue(RemoteBrowsePaths.isKdbxName("keepasskey.KDBX"))
        assertFalse(RemoteBrowsePaths.isKdbxName("readme.txt"))
        assertTrue(RemoteBrowsePaths.visibleUnderKdbxOnly(true, "any.txt"))
        assertTrue(RemoteBrowsePaths.visibleUnderKdbxOnly(false, "db.kdbx"))
        assertFalse(RemoteBrowsePaths.visibleUnderKdbxOnly(false, "db.txt"))
        assertEquals("", RemoteBrowsePaths.formatRemoteSize(0))
        assertEquals("512 B", RemoteBrowsePaths.formatRemoteSize(512))
        assertTrue(RemoteBrowsePaths.formatRemoteSize(2048).contains("KiB"))
        assertEquals("", RemoteBrowsePaths.formatRemoteModified(0))
        assertTrue(RemoteBrowsePaths.entrySubtitle(100, 0).contains("100 B"))
    }
}
