package com.keepasskey.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 配色来源唯一判据 [resolveColorSource] 的真值表锁定（ISSUE-P3-263 AC①）。
 *
 * 设置页与 [KeePasskeyTheme] 共用此纯函数判定「当前实际生效的配色来源」；
 * `Build.VERSION_CODES.S`（API 31）为编译期常量内联进生产函数，此处以字面量
 * 30 / 31 作边界值直测，不引入任何 Android 运行时依赖。
 */
class ColorSourceTest {

    @Test
    fun `偏好关闭时恒为品牌调色盘（与 SDK 无关）`() {
        assertEquals(ColorSource.BRAND_PALETTE, resolveColorSource(dynamicColorEnabled = false, sdkInt = 30))
        assertEquals(ColorSource.BRAND_PALETTE, resolveColorSource(dynamicColorEnabled = false, sdkInt = 31))
        assertEquals(ColorSource.BRAND_PALETTE, resolveColorSource(dynamicColorEnabled = false, sdkInt = 40))
    }

    @Test
    fun `偏好开启但设备不支持（API 30-）必须回落品牌调色盘`() {
        // ISSUE-P3-263 AC① 强制分支：dynamic_color_enabled 可能经备份恢复为 true
        // 而设备低于 Android 12，此时渲染层须显式回落品牌调色盘
        assertEquals(ColorSource.BRAND_PALETTE, resolveColorSource(dynamicColorEnabled = true, sdkInt = 30))
        assertEquals(ColorSource.BRAND_PALETTE, resolveColorSource(dynamicColorEnabled = true, sdkInt = 0))
    }

    @Test
    fun `偏好开启且设备支持（API 31+）命中动态取色`() {
        assertEquals(ColorSource.DYNAMIC, resolveColorSource(dynamicColorEnabled = true, sdkInt = 31))
        assertEquals(ColorSource.DYNAMIC, resolveColorSource(dynamicColorEnabled = true, sdkInt = 37))
    }
}
