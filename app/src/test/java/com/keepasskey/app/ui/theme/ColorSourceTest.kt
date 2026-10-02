package com.keepasskey.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 配色来源唯一判据 [resolveColorSource] 的真值表锁定（ISSUE-P3-263 AC①；
 * ISSUE-P3-441 AC① 扩第三形态「自定义种子色」）。
 *
 * 设置页与 [KeePasskeyTheme] 共用此纯函数判定「当前实际生效的配色来源」；
 * `Build.VERSION_CODES.S`（API 31）为编译期常量内联进生产函数，此处以字面量
 * 30 / 31 作边界值直测，不引入任何 Android 运行时依赖。
 */
class ColorSourceTest {

    private val seed: Long = 0xFF00629E

    @Test
    fun `无种子色时偏好关闭恒为品牌调色盘（与 SDK 无关）`() {
        assertEquals(
            ColorSource.BRAND_PALETTE,
            resolveColorSource(dynamicColorEnabled = false, seedColor = null, sdkInt = 30)
        )
        assertEquals(
            ColorSource.BRAND_PALETTE,
            resolveColorSource(dynamicColorEnabled = false, seedColor = null, sdkInt = 31)
        )
        assertEquals(
            ColorSource.BRAND_PALETTE,
            resolveColorSource(dynamicColorEnabled = false, seedColor = null, sdkInt = 40)
        )
    }

    @Test
    fun `无种子色时偏好开启但设备不支持（API 30-）必须回落品牌调色盘`() {
        // ISSUE-P3-263 AC① 强制分支：dynamic_color_enabled 可能经备份恢复为 true
        // 而设备低于 Android 12，此时渲染层须显式回落品牌调色盘
        assertEquals(
            ColorSource.BRAND_PALETTE,
            resolveColorSource(dynamicColorEnabled = true, seedColor = null, sdkInt = 30)
        )
        assertEquals(
            ColorSource.BRAND_PALETTE,
            resolveColorSource(dynamicColorEnabled = true, seedColor = null, sdkInt = 0)
        )
    }

    @Test
    fun `无种子色时偏好开启且设备支持（API 31+）命中动态取色`() {
        assertEquals(
            ColorSource.DYNAMIC,
            resolveColorSource(dynamicColorEnabled = true, seedColor = null, sdkInt = 31)
        )
        assertEquals(
            ColorSource.DYNAMIC,
            resolveColorSource(dynamicColorEnabled = true, seedColor = null, sdkInt = 37)
        )
    }

    // ===== ISSUE-P3-441 AC①：自定义种子色第三形态 =====

    @Test
    fun `已设种子色且动态取色未生效时命中自定义`() {
        assertEquals(
            ColorSource.CUSTOM,
            resolveColorSource(dynamicColorEnabled = false, seedColor = seed, sdkInt = 30)
        )
        assertEquals(
            ColorSource.CUSTOM,
            resolveColorSource(dynamicColorEnabled = false, seedColor = seed, sdkInt = 31)
        )
    }

    @Test
    fun `动态取色优先级高于种子色`() {
        // 存储层互斥写本应二者不同时成立（备份恢复 / 损坏数据兜底口径）
        assertEquals(
            ColorSource.DYNAMIC,
            resolveColorSource(dynamicColorEnabled = true, seedColor = seed, sdkInt = 31)
        )
        // 设备不支持动态取色时仍回落到自定义种子色（而非品牌调色盘）
        assertEquals(
            ColorSource.CUSTOM,
            resolveColorSource(dynamicColorEnabled = true, seedColor = seed, sdkInt = 30)
        )
    }
}
