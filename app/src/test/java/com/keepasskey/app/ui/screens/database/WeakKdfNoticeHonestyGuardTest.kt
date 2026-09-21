package com.keepasskey.app.ui.screens.database

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `DatabasePickerViewModel.importDatabaseFromSource` 弱因子提示**如实口径**守卫
 * （`ISSUE-P3-248`；静态源码比对，仓库根定位法沿用 [com.keepasskey.app.ColdStartAttachmentPurgeWiringTest]）。
 *
 * **为什么必须锁住这段 KDoc**：2026-09-21 复核发现该 KDoc 曾声称「弱因子提示统一落在退栈后的落点
 * ——解锁页」——而 `UnlockViewModel.importExternalDatabase` 的**唯一调用点是解锁页自身的导入按钮**
 * （`UnlockScreen.kt:119`），选择器路径只调仓库、从不写 `UnlockUiState.infoMessage`。
 * 也就是说那句 KDoc 描述的是一个**并不存在**的兜底：它把「选择器路径没有任何弱因子提示」这一
 * 残余（已登记 `docs/architecture/已知工程限界.md` §8）粉饰成了「已由别处承接」。
 * 这类「措辞掩盖缺口」的回归不会让任何行为用例变红（缺的正是提示本身），
 * 故只能靠文本判据锁住。
 *
 * 判据（**不剥注释**——本守卫断言的正是 KDoc 文本本身）：
 * 1. 不得再出现「提示已由退栈落点承接」这类表述（回归即红）；
 * 2. 必须出现如实表述「没有任何弱因子提示」；
 * 3. 必须保留「本页会立即退栈」这一真实原因。
 */
class WeakKdfNoticeHonestyGuardTest {

    private val kdoc: String by lazy {
        val source = readSource(VIEW_MODEL_PATH)
        val anchor = "fun importDatabaseFromSource("
        val anchorAt = source.indexOf(anchor)
        assertTrue(
            "未定位到 $anchor（签名或所在文件是否已变更？本守卫随之失效，须同步更新）",
            anchorAt >= 0
        )
        val kdocStart = source.lastIndexOf("/**", anchorAt)
        assertTrue("importDatabaseFromSource 上方未找到 KDoc（提示通道口径必须留在原处）", kdocStart >= 0)
        source.substring(kdocStart, anchorAt)
    }

    @Test
    fun `KDoc 不得再声称提示由退栈落点承接`() {
        BANNED_CLAIMS.forEach { claim ->
            assertFalse(
                "KDoc 出现「$claim」：解锁页的导入入口只服务它自己的按钮（UnlockScreen.kt:119），" +
                    "选择器路径从不写 UnlockUiState.infoMessage ⇒ 该表述描述的是一个不存在的兜底。" +
                    "如要改回，请先让选择器路径真实调用解锁页的提示通道并附用例，否则本残余须留在 " +
                    "docs/architecture/已知工程限界.md §8。",
                kdoc.contains(claim)
            )
        }
    }

    @Test
    fun `KDoc 必须如实写明该路径没有任何弱因子提示`() {
        assertTrue(
            "KDoc 必须如实写明「选择器路径没有任何弱因子提示」（ISSUE-P3-248）：" +
                "这是已登记残余，不得含糊为「已由别处提示」",
            HONEST_STATEMENT.containsMatchIn(kdoc)
        )
        assertTrue(
            "KDoc 必须保留真实原因「本页会立即 popBackStack 退栈」——取舍有效但理由须如实",
            kdoc.contains("popBackStack()")
        )
    }

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val VIEW_MODEL_PATH =
            "app/src/main/java/com/keepasskey/app/ui/screens/database/DatabasePickerViewModel.kt"

        /** 已证伪的表述（回归到任一条即红） */
        val BANNED_CLAIMS = listOf(
            "落在退栈后的落点",
            "落点承接",
            "均由解锁页提示"
        )

        /** 如实表述（措辞可微调，但必须点明「没有任何弱因子提示」） */
        val HONEST_STATEMENT = Regex("没有(任何)?弱因子提示")

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

        const val ROOT_SEARCH_DEPTH = 4
    }
}
