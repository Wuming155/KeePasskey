package com.keepasskey.app.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 导航防闪：已有列表时不得先切空 Loading（整表闪一下）。
 */
class RemoteBrowseNoFlashNavigationTest {

    @Test
    fun `Listing 必须支持 loading 标志`() {
        val source = readSource(CONTROLLER)
        assertTrue(source.contains("val loading: Boolean = false"))
        assertTrue(source.contains("previous.copy(loading = true, lastError = null)"))
        assertTrue(
            "成功前不得提前清空 accumulated（下钻闪一下的根因之一）",
            !Regex("""mutableState\.value = RemoteBrowseUiState\.Loading\n\s+if \(cursor == null\) accumulated""").containsMatchIn(
                source
            ) || source.contains("beginBrowseLoading")
        )
        assertTrue(source.contains("fun beginBrowseLoading"))
    }

    @Test
    fun `对话框在 loading 时保持列表可见`() {
        val dialog = readSource(DIALOG)
        assertTrue(dialog.contains("state.loading"))
        assertTrue(dialog.contains("CircularProgressIndicator"))
        assertFalse(
            "loading 时不得直接走空态文案",
            dialog.contains("if (state.accumulated.isEmpty()) {\n                            Text(stringResource(R.string.sync_browse_empty))")
        )
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("扫描目标不存在：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val CONTROLLER =
            "app/src/main/java/com/keepasskey/app/sync/RemoteBrowseController.kt"
        const val DIALOG =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/RemoteBrowseDialog.kt"

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
