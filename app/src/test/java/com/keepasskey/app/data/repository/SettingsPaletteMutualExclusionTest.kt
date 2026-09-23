package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.theme.AppThemePalette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-263 / PD-30（候选 A「互斥单选」）存储口径的行为锁定：
 * 「点选调色盘 ⇒ dynamic_color_enabled 关闭」幂等且原子。
 *
 * - [FakeSettingsRepository] 与生产 [RealSettingsRepository] 语义镜像（后者为
 *   DataStore `edit{}` 单事务写两键，原子性由 DataStore 事务保证，接线守卫
 *   [com.keepasskey.app.ui.screens.settings.ThemePaletteMutualExclusionWiringTest]
 *   以源码断言锁定「两键同处一个 edit 块」不得漂移）；
 * - 幂等性：重复点选调色盘，动态取色恒为关闭、无抖动。
 */
class SettingsPaletteMutualExclusionTest {

    @Test
    fun `动态取色开启期间点选调色盘即互斥关闭动态取色`() = runTest {
        val repository = FakeSettingsRepository()
        repository.setDynamicColorEnabled(true)

        repository.setThemePalette(AppThemePalette.EMERALD)

        assertFalse("点选调色盘必须关闭动态取色（PD-30 候选 A 互斥单选）", repository.getSettings().first().dynamicColorEnabled)
        assertEquals(AppThemePalette.EMERALD, repository.getSettings().first().themePalette)
    }

    @Test
    fun `点选调色盘幂等——重复写入动态取色恒关闭`() = runTest {
        val repository = FakeSettingsRepository()
        repository.setDynamicColorEnabled(true)

        repository.setThemePalette(AppThemePalette.AMETHYST)
        repository.setThemePalette(AppThemePalette.AMBER_SUNSET)
        repository.setThemePalette(AppThemePalette.OBSIDIAN)

        val settings = repository.getSettings().first()
        assertFalse(settings.dynamicColorEnabled)
        assertEquals(AppThemePalette.OBSIDIAN, settings.themePalette)
    }

    @Test
    fun `动态取色开关路径不受互斥写影响`() = runTest {
        val repository = FakeSettingsRepository()
        repository.setThemePalette(AppThemePalette.SAPPHIRE)
        repository.setDynamicColorEnabled(true)

        assertTrue(repository.getSettings().first().dynamicColorEnabled)
    }
}
