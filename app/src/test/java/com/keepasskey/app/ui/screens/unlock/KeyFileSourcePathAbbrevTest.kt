package com.keepasskey.app.ui.screens.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §433（ISSUE-P3-448 走查续）：解锁页「来源」路径的**折叠呈现**（纯函数，JVM 直测）。
 *
 * 用户口径＝真实路径**不要整段铺开**，中间以省略号省略；点按钮才展开完整路径。
 * 本用例锁定「短路径原样」「长路径必中间省略且首尾保留」两条判据——
 * 缩略必须仍能一眼看出「私有目录下的 .kfc 副本」。
 */
class KeyFileSourcePathAbbrevTest {

    /** 真机同形的副本绝对路径（库 id 的 SHA-256 文件名，共 118 字符） */
    private val copyPath =
        "/data/user/0/com.keepasskey.debug/files/keyfiles/" +
            "08f8c3ef4ce4b5ea548d5bc5c445cc8a28501e93342268c96a00105ee84fd7c9.kfc"

    @Test
    fun `长路径中间省略且首尾保留`() {
        val shown = abbreviatePath(copyPath)
        assertTrue("长路径必须缩略（默认不得整段铺开）", shown.length < copyPath.length)
        assertTrue("必须使用省略号", shown.contains('…'))
        assertTrue(
            "前缀必须保留（私有目录族）",
            copyPath.startsWith(shown.substringBefore('…'))
        )
        assertTrue(
            "后缀必须保留（文件名尾段）",
            copyPath.endsWith(shown.substringAfterLast('…'))
        )
        assertTrue("缩略后仍须看得出是私有目录下的 kfc 副本", shown.endsWith(".kfc"))
    }

    @Test
    fun `短路径原样呈现`() {
        val short = "content://test/1022"
        assertEquals("短路径无需缩略", short, abbreviatePath(short))
    }

    @Test
    fun `恰等于上限时不缩略`() {
        val exact = "a".repeat(40)
        assertEquals("等长即视为可完整呈现", exact, abbreviatePath(exact))
    }
}
