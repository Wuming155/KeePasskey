package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.sync.parentDirectoryPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 远端目录浏览「返回上一级」接线守卫（仿安卓文件管理器）。
 */
class RemoteBrowseBackUpWiringTest {

    @Test
    fun `parentDirectoryPath 支持返回上一级`() {
        assertEquals("folder", parentDirectoryPath("folder/sub"))
        assertEquals("", parentDirectoryPath("folder"))
        assertEquals("", parentDirectoryPath(""))
        assertEquals("a/b", parentDirectoryPath("a/b/c"))
        assertEquals("", parentDirectoryPath("/"))
    }

    @Test
    fun `对话框与装配段必须接 onNavigateUp`() {
        val dialog = readSource(DIALOG)
        val section = readSource(SECTION)
        assertTrue(dialog.contains("onNavigateUp"))
        assertTrue(dialog.contains("sync_browse_back_up"))
        assertTrue(section.contains("onNavigateUp"))
        assertTrue(section.contains("parentDirectoryPath"))
        assertFalse("根目录不得显示返回按钮的判据在 UI 侧（canGoUp）", !dialog.contains("canGoUp"))
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("扫描目标不存在：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val DIALOG =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/RemoteBrowseDialog.kt"
        const val SECTION =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/RemoteBrowseSection.kt"

        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(6) {
                val candidate = dir ?: return@repeat
                if (File(candidate, "app/src/main/java").isDirectory &&
                    File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("无法定位仓库根目录")
        }
    }
}
