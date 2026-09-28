package com.keepasskey.app.ui.screens.detail

import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P2-353 AC③ 回归：「打开网址」必须是**真动作**，且失败路径回退复制并如实提示。
 *
 * 缺陷背景：详情页「打开网址」芯片点击只 `onShowMessage(detail_opening_browser)`
 * （文案称「正在呼起浏览器…」但全 detail 包无 `ACTION_VIEW`），URL 文本行也不可点击。
 *
 * 断言分两层：
 * 1. **行为层**（纯函数 [resolveOpenUrlAction]）：仅 http(s) 进入呼起分支；
 *    ftp / 自定义 scheme / 无 scheme / 空串一律不发隐式 Intent（COPY_FALLBACK / MISSING）；
 * 2. **接线层**（源码守卫）：组件必须把芯片与 URL 文本行接到同一执行器，
 *    执行器必须真发 `Intent.ACTION_VIEW` 且有 `runCatching` 兜底；
 *    旧形态「芯片只发 detail_opening_browser 消息」不得回归。
 */
class EntryDetailOpenUrlActionTest {

    // ---------------------------------------------------------------- 行为层

    @Test
    fun `http 与 https 进入呼起分支 含大小写与空白容忍`() {
        assertEquals(OpenUrlAction.LAUNCH, resolveOpenUrlAction("https://example.com"))
        assertEquals(OpenUrlAction.LAUNCH, resolveOpenUrlAction("http://example.com"))
        assertEquals(OpenUrlAction.LAUNCH, resolveOpenUrlAction("  HTTPS://Example.com/path  "))
    }

    @Test
    fun `非 http scheme 一律回退复制 不发隐式 Intent`() {
        assertEquals(OpenUrlAction.COPY_FALLBACK, resolveOpenUrlAction("ftp://files.example.com"))
        assertEquals(OpenUrlAction.COPY_FALLBACK, resolveOpenUrlAction("android://com.example.app"))
        assertEquals(OpenUrlAction.COPY_FALLBACK, resolveOpenUrlAction("javascript:alert(1)"))
        assertEquals(OpenUrlAction.COPY_FALLBACK, resolveOpenUrlAction("mailto:user@example.com"))
    }

    @Test
    fun `无 scheme 与空串分别回退复制与缺失`() {
        assertEquals(OpenUrlAction.COPY_FALLBACK, resolveOpenUrlAction("example.com/login"))
        assertEquals(OpenUrlAction.MISSING, resolveOpenUrlAction(""))
        assertEquals(OpenUrlAction.MISSING, resolveOpenUrlAction("    "))
    }

    // ---------------------------------------------------------------- 接线层

    @Test
    fun `执行器真发 ACTION_VIEW 且有 runCatching 兜底`() {
        val actions = stripCommentsOnly(readSource(URL_ACTIONS))

        assertTrue("必须构造 ACTION_VIEW 隐式 Intent（假动作不得回归）", actions.contains("Intent.ACTION_VIEW"))
        assertTrue("startActivity 必须经 runCatching 兜底（ActivityNotFoundException 不得上抛）", actions.contains("runCatching"))
        assertTrue("呼起失败必须回退复制并如实提示", actions.contains("detail_url_fallback_copied"))
        assertTrue("非 http(s) 裁决必须存在（COPY_FALLBACK 分支）", actions.contains("COPY_FALLBACK"))
    }

    @Test
    fun `芯片与 URL 文本行都接到同一打开执行器 旧假动作不得回归`() {
        val components = stripCommentsOnly(readSource(COMPONENTS))

        assertTrue("「打开网址」芯片必须调用打开执行器", components.contains("openUrl(entry.url)"))
        assertTrue("URL 文本行必须可点击并调用同一执行器", components.contains("clickable { openUrl(urlText) }"))
        assertFalse(
            "旧形态不得回归：芯片点击只发「正在呼起浏览器」消息而无真实 Intent",
            components.contains("onShowMessage(UiMessage(R.string.detail_opening_browser))")
        )
    }

    // ---------------------------------------------------------------- 工具

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被改名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val COMPONENTS =
            "app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailComponents.kt"
        const val URL_ACTIONS =
            "app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailUrlActions.kt"
        const val ROOT_SEARCH_DEPTH = 6

        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(ROOT_SEARCH_DEPTH) {
                val candidate = dir ?: return@repeat
                if (File(candidate, "app/src/main/java").isDirectory &&
                    File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
        }
    }
}
