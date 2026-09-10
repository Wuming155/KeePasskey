package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.ui.screens.settings.ListDensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-17 列表密度映射单测：`listDensity` 必须真实驱动行高 / 内边距 / 字号，
 * 且三档之间严格单调（紧凑 < 默认 < 宽松），避免出现「开关改了但视觉不变」的假接线。
 */
class ListDensitySpecTest {

    private val compact = ListDensityPresenter.specOf(ListDensity.COMPACT)
    private val normal = ListDensityPresenter.specOf(ListDensity.NORMAL)
    private val comfortable = ListDensityPresenter.specOf(ListDensity.COMFORTABLE)

    @Test
    fun `三档密度映射到互不相同的行规格`() {
        val specs = listOf(compact, normal, comfortable)
        assertEquals("三档密度不得映射到同一规格", 3, specs.toSet().size)
    }

    @Test
    fun `紧凑到宽松行高与字号严格递增`() {
        assertTrue(compact.rowVerticalPaddingDp < normal.rowVerticalPaddingDp)
        assertTrue(normal.rowVerticalPaddingDp < comfortable.rowVerticalPaddingDp)
        assertTrue(compact.titleFontSizeSp < normal.titleFontSizeSp)
        assertTrue(normal.titleFontSizeSp < comfortable.titleFontSizeSp)
        assertTrue(compact.secondaryFontSizeSp < normal.secondaryFontSizeSp)
        assertTrue(normal.secondaryFontSizeSp < comfortable.secondaryFontSizeSp)
    }

    @Test
    fun `三档密度图标容器与内容尺寸均严格递增且内容小于容器`() {
        assertTrue(compact.iconContainerSizeDp < normal.iconContainerSizeDp)
        assertTrue(normal.iconContainerSizeDp < comfortable.iconContainerSizeDp)
        listOf(compact, normal, comfortable).forEach { spec ->
            assertTrue(
                "图标绘制尺寸必须小于容器以留出内衬：$spec",
                spec.iconContentSizeDp < spec.iconContainerSizeDp
            )
        }
    }

    @Test
    fun `默认档保持整改前标准行版式`() {
        // 回归保护：NORMAL 必须与整改前 StandardEntryLayout 的字面量一致
        assertEquals(10, normal.rowVerticalPaddingDp)
        assertEquals(12, normal.rowHorizontalPaddingDp)
        assertEquals(38, normal.iconContainerSizeDp)
        assertEquals(15, normal.titleFontSizeSp)
    }

    @Test
    fun `映射逐档穷尽且数值均为正`() {
        ListDensity.entries.forEach { density ->
            val spec = ListDensityPresenter.specOf(density)
            assertTrue(
                "密度 $density 的规格数值必须全为正：$spec",
                listOf(
                    spec.rowVerticalPaddingDp,
                    spec.rowHorizontalPaddingDp,
                    spec.iconContainerSizeDp,
                    spec.iconContentSizeDp,
                    spec.titleFontSizeSp,
                    spec.secondaryFontSizeSp
                ).all { it > 0 }
            )
        }
    }
}
