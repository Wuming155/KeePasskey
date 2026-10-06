package com.keepasskey.app.ui.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「离线缓存」开关的两条长期不变量：
 *
 * 1. 设置侧（`SettingsViewModel` / `SettingsExtendedPreferencesController`）**不得**把它误接到
 *    `SyncCoordinator.setOfflineMode`——该误接会使同步引擎永不联网、首传被误报为上传失败
 *    （2026-10-30 已拆）。
 * 2. 该开关已按 **PD-75 / ISSUE-P2-497** 整链移除：离线缓存（缓存先行 + 失败保底重试）是
 *    `SyncEngine` 的**无条件内置语义**，开关两向均 no-op ⇒ 属假开关，字段 / 偏好键 / 投影 /
 *    UI 行 / 文案一并下架。本用例以源码文本反断言守卫「不得静默复活」。
 */
class OfflineCacheNotEngineOfflineTest {

    @Test
    fun `设置 ViewModel 不得调用 setOfflineMode`() {
        val viewModel = readSource(SETTINGS_VIEW_MODEL)
        assertFalse(
            "禁止 setOfflineMode 误接：会令同步引擎永不联网",
            viewModel.contains("setOfflineMode")
        )
        assertFalse(
            "被移除的离线缓存开关不得在设置 ViewModel 复活",
            viewModel.contains("useOfflineCache")
        )
        assertTrue(
            "冷启动闸门仍须在恢复凭据之后调用",
            viewModel.contains("coldStartSyncGate.checkAndTrigger()")
        )
    }

    @Test
    fun `进阶偏好控制器不得调用 setOfflineMode`() {
        val controller = readSource(SETTINGS_EXT_PREF)
        assertFalse(
            "进阶偏好控制器不得 setOfflineMode",
            controller.contains("setOfflineMode")
        )
        assertFalse(
            "被移除的离线缓存开关不得在进阶偏好控制器复活",
            controller.contains("useOfflineCache")
        )
    }

    @Test
    fun `离线缓存假开关字段与偏好键不得在源码中复活`() {
        // ISSUE-P2-497 / PD-75：数据类字段 / 偏好键 / UiState / 投影 / UI 行全链移除，
        // 任一残留即「可拨动假开关」复活，判红。
        val sources = listOf(
            EXTENDED_SETTINGS,
            EXTENDED_SETTINGS_STORE,
            SETTINGS_UI_STATE,
            SETTINGS_UI_STATE_PROJECTION,
            CLOUD_SYNC_SECTIONS
        )
        sources.forEach { path ->
            val text = readSource(path)
            assertFalse("$path 不得再出现 useOfflineCache", text.contains("useOfflineCache"))
            assertFalse("$path 不得再出现 use_offline_cache 键", text.contains("use_offline_cache"))
        }
    }

    @Test
    fun `离线缓存开关文案资源不得在双语文案中复活`() {
        listOf(STRINGS_ZH, STRINGS_EN).forEach { path ->
            val text = readSource(path)
            assertFalse("$path 不得再出现 sync_offline_cache_title", text.contains("sync_offline_cache_title"))
            assertFalse("$path 不得再出现 sync_offline_cache_sub", text.contains("sync_offline_cache_sub"))
        }
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
        const val EXTENDED_SETTINGS =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/ExtendedSettings.kt"
        const val EXTENDED_SETTINGS_STORE =
            "app/src/main/java/com/keepasskey/app/data/repository/ExtendedSettingsStore.kt"
        const val SETTINGS_UI_STATE =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiState.kt"
        const val SETTINGS_UI_STATE_PROJECTION =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiStateProjection.kt"
        const val CLOUD_SYNC_SECTIONS =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/CloudSyncSections.kt"
        const val STRINGS_ZH = "app/src/main/res/values/strings.xml"
        const val STRINGS_EN = "app/src/main/res/values-en/strings.xml"

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
