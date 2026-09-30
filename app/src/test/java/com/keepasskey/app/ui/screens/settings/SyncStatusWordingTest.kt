package com.keepasskey.app.ui.screens.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 同步状态文案守卫：测连成功不得写成「已同步」（那要等真正同步完成）。
 */
class SyncStatusWordingTest {

    @Test
    fun `投影区分连接已验证与已同步`() {
        val projection = readSource(PROJECTION)
        assertTrue(
            "测连成功且未同步时应显示 sync_status_verified_only",
            projection.contains("sync_status_verified_only")
        )
        assertTrue(
            "真正同步后才显示 sync_status_synced",
            projection.contains("syncState.lastSyncTimeText.isNotEmpty() -> strings.get(R.string.sync_status_synced)")
        )
        assertTrue(
            "未同步且已验证应显示 sync_last_time_verified_never",
            projection.contains("sync_last_time_verified_never")
        )
    }

    @Test
    fun `中英文案已登记 verified 语义键`() {
        val zh = readSource(STRINGS_ZH)
        val en = readSource(STRINGS_EN)
        assertTrue(zh.contains("sync_status_verified_only"))
        assertTrue(zh.contains("sync_last_time_verified_never"))
        assertTrue(en.contains("sync_status_verified_only"))
        assertTrue(en.contains("sync_last_time_verified_never"))
        // 不得再出现重复的 sync_last_time_never 定义
        assertTrue(
            "sync_last_time_never 只允许定义一次",
            Regex("""name="sync_last_time_never"""").findAll(zh).count() == 1
        )
    }

    @Test
    fun `冷启动闸门在未配置同步时不触发`() {
        val gate = readSource(COLD_START_GATE)
        assertTrue(gate.contains("isSyncConfigured"))
        assertTrue(gate.contains("syncOnColdStart"))
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("扫描目标不存在：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val PROJECTION =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiStateProjection.kt"
        const val COLD_START_GATE =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsColdStartSyncGate.kt"
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
