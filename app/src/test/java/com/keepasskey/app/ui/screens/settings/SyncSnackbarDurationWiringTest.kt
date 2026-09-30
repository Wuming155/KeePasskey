package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.ui.model.UiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §373 同步状态 Snackbar 时长收短的**接线守卫**（判据形态同仓内其余 wiring 测试：读源码，不依赖运行时）。
 *
 * 锁定三件事：
 * 1. [UiMessage] 提供可选 [UiMessage.durationMillis] 与同步状态档 [UiMessage.SYNC_STATUS_DURATION_MS]；
 * 2. 外壳宿主在**无撤销动作**时才尊重自定义时长，有撤销动作恒走 Material Long；
 * 3. 列表页同步**状态类**反馈挂短档，错误反馈构造不得挂短档。
 */
class SyncSnackbarDurationWiringTest {

    @Test
    fun `UiMessage必须提供可选时长字段与同步状态短档`() {
        val model = readSource(UI_MESSAGE)
        assertTrue(
            "UiMessage 必须暴露 durationMillis 供展示层消费",
            model.contains("val durationMillis: Long? = null")
        )
        assertTrue(
            "同步状态短档常量必须存在且为 1000ms（用户 2s→1s 收短后口径）",
            model.contains("const val SYNC_STATUS_DURATION_MS = 1_000L")
        )
    }

    @Test
    fun `外壳宿主无撤销时才收短有撤销恒走Long`() {
        val host = readSource(HOST)
        assertTrue(
            "宿主必须读取消息上的自定义时长",
            host.contains("message.durationMillis")
        )
        assertTrue(
            "自定义时长只在无撤销动作时生效（undoLabel == null 才 override）",
            host.contains("if (undoLabel == null) message.durationMillis else null")
        )
        assertTrue(
            "有撤销动作必须传 SnackbarDuration.Long（不得被收短）",
            host.contains("duration = if (undoLabel != null) SnackbarDuration.Long else SnackbarDuration.Short")
        )
        assertTrue(
            "自定义时长须以延迟 dismiss 落地（Material 枚举无更短档）",
            host.contains("hostState.currentSnackbarData?.dismiss()")
        )
    }

    @Test
    fun `列表页同步状态类反馈挂短档错误不挂`() {
        val controller = readSource(VAULT_SYNC)
        assertEquals(
            "列表页状态类反馈应恰有 3 处短档挂载（completed / uploaded / remote_updated）",
            3,
            countOccurrences(controller, "durationMillis = UiMessage.SYNC_STATUS_DURATION_MS")
        )
        assertTrue(
            "「云端同步校验完成」必须挂短档",
            controller.contains("R.string.vault_sync_completed") &&
                controller.contains("durationMillis = UiMessage.SYNC_STATUS_DURATION_MS")
        )
        assertTrue(
            "「本地修改已上传」必须挂短档",
            controller.contains("R.string.vault_sync_uploaded") &&
                controller.contains("durationMillis = UiMessage.SYNC_STATUS_DURATION_MS")
        )
        assertTrue(
            "「远端已刷新」必须挂短档",
            controller.contains("R.string.vault_sync_remote_updated") &&
                controller.contains("durationMillis = UiMessage.SYNC_STATUS_DURATION_MS")
        )
        assertFalse(
            "错误反馈构造不得携带 SYNC_STATUS_DURATION_MS",
            Regex("""sync_feedback_error[\s\S]{0,160}SYNC_STATUS_DURATION_MS""")
                .containsMatchIn(controller)
        )
    }

    @Test
    fun `设置页同步状态类反馈挂短档`() {
        val controller = readSource(SETTINGS_SYNC)
        assertTrue(
            "设置页 done/uploaded/merged 等状态反馈须挂短档（≥3 处）",
            countOccurrences(controller, "durationMillis = UiMessage.SYNC_STATUS_DURATION_MS") >= 3
        )
        assertFalse(
            "设置页 error 反馈不得挂同步状态短档",
            Regex("""sync_feedback_error[\s\S]{0,160}SYNC_STATUS_DURATION_MS""")
                .containsMatchIn(controller)
        )
    }

    @Test
    fun `发布通道转发消息上的时长宿主据此消费`() {
        val publish = readSource(PUBLISH)
        assertTrue(
            "列表页发布件必须把整条 UiMessage 交给 AppSnackbarEvent（含 durationMillis）",
            publish.contains("AppSnackbarEvent(") && publish.contains("message = message")
        )
    }

    private fun countOccurrences(source: String, needle: String): Int {
        var count = 0
        var index = source.indexOf(needle)
        while (index >= 0) {
            count++
            index = source.indexOf(needle, index + needle.length)
        }
        return count
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("守卫目标文件不存在：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val UI_MESSAGE =
            "app/src/main/java/com/keepasskey/app/ui/model/UiMessage.kt"
        const val HOST =
            "app/src/main/java/com/keepasskey/app/ui/AppGlobalSnackbarHost.kt"
        const val VAULT_SYNC =
            "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListSyncController.kt"
        const val SETTINGS_SYNC =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsSyncController.kt"
        const val PUBLISH =
            "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListMessagePublish.kt"

        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(4) {
                val candidate = dir ?: return@repeat
                if (File(candidate, "app/src/main/java").isDirectory &&
                    File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            throw IllegalStateException("未找到仓库根（app/core 源码目录）")
        }
    }
}
