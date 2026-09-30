package com.keepasskey.app.ui.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 离线缓存开关不得映射为 SyncEngine 强制离线（否则首传永不联网）。
 */
class OfflineCacheNotEngineOfflineTest {

    @Test
    fun `SettingsViewModel 不得再把 useOfflineCache 接到 setOfflineMode`() {
        val viewModel = readSource(SETTINGS_VIEW_MODEL)
        assertFalse(
            "禁止 setOfflineMode(useOfflineCache) 误接：会令同步引擎永不联网",
            viewModel.contains("setOfflineMode(extendedPreferences.settings.value.useOfflineCache)")
        )
        assertTrue(
            "冷启动闸门仍须在恢复凭据之后调用",
            viewModel.contains("coldStartSyncGate.checkAndTrigger()")
        )
    }

    @Test
    fun `离线缓存开关控制器不得调用 setOfflineMode`() {
        val controller = readSource(SETTINGS_EXT_PREF)
        assertFalse(
            "setUseOfflineCache 不得再 setOfflineMode",
            controller.contains("setOfflineMode")
        )
        assertTrue(controller.contains("useOfflineCache = enabled"))
    }

    @Test
    fun `首传 RemoteUnreachable 不得误报为上传云端失败`() {
        val commitPaths = readSource(SYNC_COMMIT_PATHS)
        assertTrue(
            "RemoteUnreachable 应映射 Offline",
            commitPaths.contains("is SyncCommitResult.RemoteUnreachable -> SyncOutcome.Offline")
        )
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("扫描目标不存在：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val SETTINGS_VIEW_MODEL =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsViewModel.kt"
        const val SETTINGS_EXT_PREF =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsExtendedPreferencesController.kt"
        const val SYNC_COMMIT_PATHS =
            "app/src/main/java/com/keepasskey/app/sync/SyncCycleCommitPaths.kt"

        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(6) {
                val candidate = dir ?: return@repeat
                if (File(candidate, "app/src/main/java").isDirectory &&
                    File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("无法定位仓库根目录")
        }
    }
}
