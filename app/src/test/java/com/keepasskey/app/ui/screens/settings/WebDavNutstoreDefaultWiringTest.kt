package com.keepasskey.app.ui.screens.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 接线守卫：WebDAV 默认端点/占位必须指向坚果云（用户指示：避免重复输入）。
 */
class WebDavNutstoreDefaultWiringTest {

    @Test
    fun `同步表单占位与已保存恢复走 WebDavDefaults`() {
        val fields = readSource(CLOUD_SYNC_CONFIG)
        val controller = readSource(SETTINGS_SYNC_CONTROLLER)
        val uiState = readSource(SETTINGS_UI_STATE)
        assertTrue("CloudSyncConfigFields 必须引用 WebDavDefaults", fields.contains("WebDavDefaults"))
        assertTrue("不得残留 Nextcloud 示例 URL", !fields.contains("cloud.example.com"))
        assertTrue("SettingsSyncController 恢复必须优先已保存、否则 WebDavDefaults",
            controller.contains("WebDavDefaults.NUTSTORE_URL") && controller.contains("takeIf { it.isNotBlank() }"))
        assertTrue("SettingsUiState 默认 URL 应为坚果云",
            uiState.contains("WebDavDefaults.NUTSTORE_URL"))
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("扫描目标不存在：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val CLOUD_SYNC_CONFIG =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/CloudSyncConfigFields.kt"
        const val SETTINGS_SYNC_CONTROLLER =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsSyncController.kt"
        const val SETTINGS_UI_STATE =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiState.kt"

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
