package com.keepasskey.app.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.keepasskey.app.ui.theme.AppThemePalette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** ISSUE-P3-653：用生产 DataStore 验证品牌来源写入经重新构造仓库后仍可读。 */
@RunWith(AndroidJUnit4::class)
class BrandPalettePersistenceDeviceTest {

    @Test
    fun switchToBrandPalettePersistsAcrossRepositoryRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val writer = RealSettingsRepository(context)
        val original = writer.getSettings().first()
        try {
            writer.setThemePalette(AppThemePalette.EMERALD)
            writer.setCustomSeedColor(0xFF00897B)
            writer.setDynamicColorEnabled(true)

            writer.useBrandPalette()

            val settings = RealSettingsRepository(context).getSettings().first()
            assertEquals(AppThemePalette.EMERALD, settings.themePalette)
            assertFalse(settings.dynamicColorEnabled)
            assertEquals(null, settings.customSeedColor)
        } finally {
            writer.setThemePalette(original.themePalette)
            writer.setCustomSeedColor(original.customSeedColor)
            writer.setDynamicColorEnabled(original.dynamicColorEnabled)
        }
    }
}
