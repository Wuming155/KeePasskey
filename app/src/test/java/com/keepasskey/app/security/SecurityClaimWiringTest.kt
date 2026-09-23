package com.keepasskey.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P2-285 AC⑥ 接线守卫：硬件类安全声明**按实测安全等级条件渲染**的静态回归
 * （体例沿用 `AutofillChannelSwitchWiringTest` 的源码消费点计数口径）。
 *
 * ## 锁定的缺陷形态（禁复现）
 *
 * 1. **有真值字段但无生产赋值**：`UnlockUiState.hardwareBackedSecurity` 全仓唯一赋值点
 *    在 `@Preview`（生产 8 处构造无一回填）⇒ 该字段**已删除**，禁任何同名复活；
 * 2. **硬编码硬件声明**：解锁副标题 / 生物识别副标题 / 同步凭据封印声明三处，
 *    都必须出现「实测降级字段 + 软件级文案键」的成对引用——缺一则退回「无条件硬件文案」；
 * 3. **真值链路断点**：同步面的实测字段必须从 `SyncCredentialsStore.syncSealSecurityLevel`
 *    经 `SettingsSyncController` → 投影 → `SettingsUiState` → `ZeroKnowledgeCard` 逐环接通。
 */
class SecurityClaimWiringTest {

    // ========== 形态 1：死真值字段禁复现 ==========

    @Test
    fun `UnlockUiState 不再持有无生产赋值的硬件声明字段`() {
        assertFalse(
            "hardwareBackedSecurity 已删除（有真值字段但无生产赋值，禁复现）",
            unlockUiStateSource.contains("hardwareBackedSecurity")
        )
    }

    // ========== 形态 2：三处硬件声明按实测降级渲染 ==========

    @Test
    fun `解锁页快速解锁副标题按实测降级态成对渲染`() {
        assertTrue(
            "副标题必须按 quickUnlockDowngraded 条件渲染（禁硬件文案硬编码）",
            unlockScreenSource.contains("uiState.quickUnlockDowngraded")
        )
        assertTrue(
            "软件级文案键必须被引用（中英成对的降级文案）",
            unlockScreenSource.contains("R.string.unlock_quick_subtitle_software")
        )
    }

    @Test
    fun `生物识别副标题按实测降级态成对渲染`() {
        assertTrue(
            "副标题必须按 quickUnlockDowngradeAcknowledged 条件渲染",
            securitySettingsScreenSource.contains("uiState.quickUnlockDowngradeAcknowledged")
        )
        assertTrue(
            "软件级文案键必须被引用",
            securitySettingsScreenSource.contains("R.string.sec_biometric_sub_software")
        )
    }

    @Test
    fun `同步凭据封印声明按实测硬件落位成对渲染`() {
        assertTrue(
            "ZeroKnowledgeCard 必须接收实测字段（禁默认 true 谎报）",
            cloudSyncScreenSource.contains("ZeroKnowledgeCard(sealHardwareBacked = uiState.syncSealHardwareBacked")
        )
        assertTrue(
            "软件级降级文案键必须被引用（AC② 与解锁面同形）",
            cloudSyncSectionsSource.contains("R.string.sync_credential_auth_notice_software")
        )
    }

    // ========== 形态 3：同步面实测链路逐环接通 ==========

    @Test
    fun `同步封印实测链路从探测到渲染逐环接通`() {
        assertTrue(
            "真值源头：SyncCredentialsStore 必须暴露封印密钥实测等级",
            syncCredentialsStoreSource.contains("fun syncSealSecurityLevel()")
        )
        assertTrue(
            "控制器必须以实测值初始化 / 刷新 syncSealHardwareBacked",
            settingsSyncControllerSource.countInvocationsOf("syncSealSecurityLevel()") >= 1 &&
                settingsSyncControllerSource.contains("syncSealHardwareBacked = probeSealHardwareBacked()")
        )
        assertTrue(
            "投影必须把实测字段搬进 SettingsUiState",
            projectionSource.contains("syncSealHardwareBacked = syncState.syncSealHardwareBacked")
        )
        assertTrue(
            "SettingsUiState 必须声明该字段（投影落点）",
            settingsUiStateSource.contains("val syncSealHardwareBacked: Boolean")
        )
    }

    @Test
    fun `软件级文案键中英成对存在`() {
        listOf(
            "unlock_quick_subtitle_software",
            "sec_biometric_sub_software",
            "sync_credential_auth_notice_software"
        ).forEach { key ->
            assertTrue("中文文案缺 $key", zhStringsSource.contains("name=\"$key\""))
            assertTrue("英文文案缺 $key", enStringsSource.contains("name=\"$key\""))
        }
    }

    // ========== 源码读取 ==========

    private val unlockUiStateSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/ui/screens/unlock/UnlockUiState.kt")

    private val unlockScreenSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/ui/screens/unlock/UnlockScreen.kt")

    private val securitySettingsScreenSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/SecuritySettingsScreen.kt")

    private val cloudSyncScreenSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/CloudSyncScreen.kt")

    private val cloudSyncSectionsSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/CloudSyncSections.kt")

    private val syncCredentialsStoreSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/sync/SyncCredentialsStore.kt")

    private val settingsSyncControllerSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsSyncController.kt")

    private val projectionSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiStateProjection.kt")

    private val settingsUiStateSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiState.kt")

    private val zhStringsSource: String
        get() = readSource("app/src/main/res/values/strings.xml")

    private val enStringsSource: String
        get() = readSource("app/src/main/res/values-en/strings.xml")

    private fun String.countInvocationsOf(token: String): Int =
        Regex(Regex.escape(token)).findAll(this).count()

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
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

        const val ROOT_SEARCH_DEPTH = 4
    }
}
