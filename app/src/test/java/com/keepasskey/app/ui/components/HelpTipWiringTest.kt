package com.keepasskey.app.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P3-445 接线守卫（静态源码比对，同 `VaultBatchDiscoverabilityWiringTest` 先例）。
 *
 * 宿主 JVM 无法渲染 Compose 提示条，故锁「接线不被静默改回」：
 * 1. 三个高困惑点调用 `DismissibleHelpTip` 且提示位不错位；
 * 2. 组件自带持久化（HelpTipStore.dismiss）——关闭态须落 SharedPreferences；
 * 3. 新文案 values + values-en 双写。
 */
class HelpTipWiringTest {

    @Test
    fun `三个高困惑点接线且提示位不错位`() {
        assertTrue(
            "云同步配置页须挂 CLOUD_SYNC_TEST_CONNECTION 提示位",
            readSource(CLOUD_SYNC_SCREEN).contains("HelpTip.CLOUD_SYNC_TEST_CONNECTION")
        )
        assertTrue(
            "自动填充设置页须挂 AUTOFILL_CHANNEL 提示位",
            readSource(AUTOFILL_SCREEN).contains("HelpTip.AUTOFILL_CHANNEL")
        )
        // §436/§443：解锁页 4合1 一体化卡片重构（DismissibleHelpTip 挂于 UnlockVaultLayoutSections.kt）——
        // 守卫按「原文件 + 段落文件」**并集**扫描（§186 同款范式：只放宽定位范围、不降低断言强度）
        val unlockSections = listOf(UNLOCK_SECTIONS, UNLOCK_VAULT_LAYOUT)
            .joinToString(separator = "\n") { readSource(it) }
        assertTrue(
            "解锁页须挂 UNLOCK_KEYFILE 提示位",
            unlockSections.contains("HelpTip.UNLOCK_KEYFILE")
        )
    }

    @Test
    fun `关闭态必须经 HelpTipStore 持久化而非仅内存`() {
        val source = readSource(COMPONENT)
        assertTrue(
            "DismissibleHelpTip 关闭时必须落 store.dismiss（重启不复现，AC②）",
            source.contains("store.dismiss(tip)")
        )
        assertTrue(
            "呈现判据必须读持久化标记 shouldShow 而非纯内存初值",
            source.contains("store.shouldShow(tip)")
        )
    }

    @Test
    fun `新文案 values 与 values-en 双写`() {
        listOf(
            "help_tip_cloud_sync_test_title",
            "help_tip_cloud_sync_test_body",
            "help_tip_autofill_channel_title",
            "help_tip_autofill_channel_body",
            "help_tip_unlock_keyfile_title",
            "help_tip_unlock_keyfile_body",
            "help_tip_dismiss"
        ).forEach { name ->
            listOf(
                "app/src/main/res/values/strings.xml" to "zh",
                "app/src/main/res/values-en/strings.xml" to "en"
            ).forEach { (path, lang) ->
                assertTrue(
                    "$name 必须在 $lang 语料双写（$path）",
                    readSource(path).contains("name=\"$name\"")
                )
            }
        }
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val COMPONENT =
            "app/src/main/java/com/keepasskey/app/ui/components/DismissibleHelpTip.kt"
        const val CLOUD_SYNC_SCREEN =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/CloudSyncScreen.kt"
        const val AUTOFILL_SCREEN =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/AutofillSettingsScreen.kt"
        const val UNLOCK_SECTIONS =
            "app/src/main/java/com/keepasskey/app/ui/screens/unlock/UnlockContentSections.kt"
        const val UNLOCK_VAULT_LAYOUT =
            "app/src/main/java/com/keepasskey/app/ui/screens/unlock/UnlockVaultLayoutSections.kt"

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先 */
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
            error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
        }
    }
}
