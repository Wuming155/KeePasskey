package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.BuildConfig
import com.keepasskey.app.data.breach.BreachCheckStatus
import com.keepasskey.app.data.repository.UserSettings
import com.keepasskey.app.testutil.stripCommentsOnly
import com.keepasskey.app.ui.model.StringsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P2-498：设置主页 / 「关于」页版本读数的**真实来源接线**守卫。
 *
 * 缺陷形态：「关于」行副标题与「关于」页版本 / 构建号行展示的是自初始化提交起从未接线的
 * 静态虚构字面量（`v1.0.0-Preview (2026 Edition)` / `Build 2026.09.04`），与
 * `app/build.gradle.kts` 的真实 `versionName` / `versionCode` 脱节——即「看似读真实版本、
 * 实为静态假读数」。整改统一取自 AGP 生成的 [BuildConfig]（见 [AppVersionInfo]）。
 *
 * 本测试从**行为读数**（投影输出由 BuildConfig 派生）与**源码形态**（旧字面量零残留）两侧锁定：
 * 前者带真实 `BuildConfig` 依赖，不是「字面量对字面量」的重言断言；后者防止字面量经注释外路径复活。
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

    @Test
    fun `主源码剔除注释后不得再残留虚构版本字面量`() {
        val offenders = mainSourceFiles()
            .filter { file ->
                val code = stripCommentsOnly(file.readText())
                FICTIONAL_LITERALS.any(code::contains)
            }
            .map { it.name }

        assertFalse(
            "以下文件仍残留静态虚构版本字面量（应改由 BuildConfig 派生）：$offenders",
            offenders.isNotEmpty()
        )
    }

    /** `app/src/main/java` 下全部 Kotlin 源文件 */
    private fun mainSourceFiles(): List<File> =
        File(repositoryRoot, "app/src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private companion object {
        /** 整改前的静态虚构版本字面量：主源码（剔除注释后）不得再出现 */
        val FICTIONAL_LITERALS = listOf("v1.0.0-Preview (2026 Edition)", "Build 2026.09.04")

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先 */
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

        const val ROOT_SEARCH_DEPTH = 6
    }
}
