package com.keepasskey.app.ui.screens.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 远端浏览对齐 keepass2android：面包屑 / kdbx 过滤 / 副文案接线守卫。
 */
class RemoteBrowseBreadcrumbWiringTest {

    @Test
    fun `对话框与装配段使用面包屑与过滤`() {
        val dialog = readSource(DIALOG)
        val section = readSource(SECTION)
        assertTrue(dialog.contains("RemoteBrowseBreadcrumb"))
        assertTrue(dialog.contains("sync_browse_kdbx_only"))
        assertTrue(dialog.contains("RemoteBrowsePaths.visibleUnderKdbxOnly"))
        assertTrue(dialog.contains("RemoteBrowsePaths.entrySubtitle"))
        assertTrue(section.contains("onNavigateToPath"))
        assertTrue(section.contains("fun browseDirectory"))
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
