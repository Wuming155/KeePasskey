package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.BuildConfig
import com.keepasskey.app.data.breach.BreachCheckStatus
import com.keepasskey.app.data.repository.UserSettings
import com.keepasskey.app.ui.model.StringsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P2-498：设置主页 / 「关于」页版本读数的**真实来源接线**守卫。
 *
 * 缺陷形态：「关于」行副标题与「关于」页版本 / 构建号行展示的是自初始化提交起从未接线的
 * 静态虚构字面量（`v1.0.0-Preview (2026 Edition)` / `Build 2026.09.04`），与
 * `app/build.gradle.kts` 的真实 `versionName` / `versionCode` 脱节——即「看似读真实版本、
 * 实为静态假读数」。整改统一取自 AGP 生成的 [BuildConfig]（见 [AppVersionInfo]）。
 *
 * ENG-20261028-12 整改：原附加的「主源码剔除注释后不得再残留虚构版本字面量」用例按源码文本
 * 反查两个历史字面量。它锁的是整改当时的字面量清单（换写法、换文件、换占位符都会漏判或误红），
 * 而真实来源已由本类三个**行为读数**用例锁定（投影输出必须由 `BuildConfig` 派生，default 路径同源）。
 * 故删除该源码文本守卫。
 */
class AppVersionWiringTest {

    private fun project(): SettingsUiState = buildSettingsUiState(
        userSettings = UserSettings(),
        syncState = SettingsSyncController.SyncUiState(),
        healthState = SettingsHealthController.HealthCheckUiState(
            healthScore = 0,
            healthStatus = "",
            healthMessage = "",
            weakPasswordCount = 0,
            reusedPasswordCount = 0,
            compromisedPasswordCount = null,
            breachCheckStatus = BreachCheckStatus.DISABLED,
            breachCheckMessage = "",
            lastHealthScanTime = "",
            isHealthScanning = false
        ),
        dbState = DatabaseConfigUiState(
            databaseName = "",
            defaultUsername = "",
            encryptionAlgorithm = "",
            kdfAlgorithm = "",
            argon2Iterations = 0L,
            argon2MemoryMb = 0L,
            argon2Parallelism = 0,
            recycleBinEnabled = true
        ),
        extState = ExtendedSettings(),
        debugLogLines = emptyList(),
        mountedChildDatabases = 0,
        strings = StringsProvider { _, _ -> "" }
    )

    @Test
    fun `投影版本号取自 BuildConfig 的 versionName 而非字面量`() {
        val state = project()
        assertEquals(
            "投影版本号必须由 BuildConfig.VERSION_NAME 派生",
            "v" + BuildConfig.VERSION_NAME,
            state.appVersion
        )
        assertTrue(
            "投影版本号必须含真实 versionName=${BuildConfig.VERSION_NAME}",
            state.appVersion.contains(BuildConfig.VERSION_NAME)
        )
    }

    @Test
    fun `投影构建号取自 BuildConfig 的 versionName 与 versionCode`() {
        val state = project()
        assertEquals(
            "投影构建号必须由 BuildConfig 的 versionName / versionCode 派生",
            "Build " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")",
            state.buildNumber
        )
        assertTrue(
            "投影构建号必须含真实 versionCode=${BuildConfig.VERSION_CODE}",
            state.buildNumber.contains(BuildConfig.VERSION_CODE.toString())
        )
    }

    @Test
    fun `首帧 initialValue 路径与投影同源`() {
        // initialValue = SettingsUiState(appLanguage = ...) 走默认值 ⇒ 默认值必须是真实派生值
        assertEquals(AppVersionInfo.versionLabel, SettingsUiState().appVersion)
        assertEquals(AppVersionInfo.buildLabel, SettingsUiState().buildNumber)
    }
}
