package com.keepasskey.app.ui.screens.settings.subscreens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §411 走查续（ISSUE-P3-448）：私有目录副本路径的**遮蔽呈现**（纯函数，JVM 直测）。
 *
 * 用户要求路径「像密码一样」默认被遮挡、点击眼睛才展开。本用例锁定两条口径：
 * 遮蔽为**等长圆点**（与口令字段 `PasswordVisualTransformation('●')` 同字符），
 * 且遮蔽结果**不残留任何原字符**（否则「遮挡」名不副实）。
 */
class KeyFileCopyPathMaskTest {

    @Test
    fun `遮蔽为等长圆点且不残留原字符`() {
        val path = "应用私有目录/keyfiles/my.keyx"
        val masked = maskKeyFilePath(path)
        assertEquals("必须等长（与口令字段同款圆点口径）", path.length, masked.length)
        assertNotEquals("遮蔽结果不得等于原串", path, masked)
        assertTrue("遮蔽结果只能由圆点组成", masked.all { it == '●' })
    }

    @Test
    fun `空路径遮蔽为空串`() {
        assertEquals("空串遮蔽后仍为空串（不凭空造出圆点）", "", maskKeyFilePath(""))
    }
}
