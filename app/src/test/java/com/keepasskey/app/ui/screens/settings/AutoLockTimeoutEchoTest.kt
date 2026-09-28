package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.data.breach.BreachCheckStatus
import com.keepasskey.app.data.repository.UserSettings
import com.keepasskey.app.ui.model.StringsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P3-362：「自动锁定超时」回显的单一真相源守卫。
 *
 * 缺陷形态：回显曾走 `securityTimeoutStateFlow` 内存流（初值硬编码 0、全仓无仓库播种），
 * 冷启动进安全设置页显示「立即」，而行为侧（AutoLockManager / AutoLockSessionGuard）
 * 生效的是持久化的 60 秒——显示与行为分叉。整改把投影改为直读 `userSettings`，
 * 本测试从**行为读数**（投影输出 == 仓库输入）与**源码形态**（内存流不再存在）两侧锁定。
 */
class AutoLockTimeoutEchoTest {

    private fun project(userSettings: UserSettings): SettingsUiState = buildSettingsUiState(
        userSettings = userSettings,
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
    fun `冷启动默认回显等于仓库持久化默认值 60`() {
        assertEquals(60, project(UserSettings()).autoLockTimeoutSeconds)
    }

    @Test
    fun `自定义档回显逐值等于仓库输入`() {
        assertEquals(300, project(UserSettings(autoLockTimeoutSeconds = 300)).autoLockTimeoutSeconds)
    }

    @Test
    fun `永不档回显逐值等于仓库输入`() {
        assertEquals(-1, project(UserSettings(autoLockTimeoutSeconds = -1)).autoLockTimeoutSeconds)
    }

    @Test
    fun `投影与控制器不再持有超时内存回显流`() {
        val projection = readMain(PROJECTION)
        val controller = readMain(PREFERENCES_CONTROLLER)
        val viewModel = readMain(SETTINGS_VM)
        // 回显唯一通路 = userSettings（防 secState 双源复活）
        val echoDeclarations = projection.split("autoLockTimeoutSeconds = userSettings.autoLockTimeoutSeconds").size - 1
        assertEquals("投影必须恰好一处直读 userSettings", 1, echoDeclarations)
        // SecurityTimeoutUiState 类型已整体移除（含定义与一切引用）
        val classRefs = (projection + controller + viewModel).split("SecurityTimeoutUiState").size - 1
        assertEquals("SecurityTimeoutUiState 应零残留", 0, classRefs)
        // 内存流字段声明不得复活（注释中提及整改缘由不算声明）
        val flowDeclarations = controller.split("val securityTimeoutStateFlow").size - 1
        assertEquals("securityTimeoutStateFlow 字段不得复活", 0, flowDeclarations)
        // 解析条数下限防空转（文件读空时上面断言会假绿）
        assertTrue("投影源码解析过短，疑似读取失败", projection.length > 2_000)
        assertTrue("控制器源码解析过短，疑似读取失败", controller.length > 2_000)
    }

    private fun readMain(relative: String): String {
        val file = File(repositoryRoot, relative)
        assertTrue("源码文件不存在（是否被重命名或移动）：$relative", file.isFile)
        return file.readText()
    }

    private companion object {
        const val PROJECTION =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiStateProjection.kt"
        const val PREFERENCES_CONTROLLER =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsPreferencesController.kt"
        const val SETTINGS_VM =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsViewModel.kt"
        const val ROOT_SEARCH_DEPTH = 6

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先（同 CloudSyncSwitchWiringTest 口径） */
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
            error("未能定位仓库根（自 ${System.getProperty("user.dir")} 向上 ${ROOT_SEARCH_DEPTH} 层）")
        }
    }
}
