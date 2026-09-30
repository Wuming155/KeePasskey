package com.keepasskey.app.ui.screens.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ISSUE-P2-406` 接线守卫：设置二级页导航不得在组合线程上做 Keystore 级同步操作。
 *
 * ## 锁定的缺陷形态（禁复现）
 *
 * 1. **VM 构造期 Keystore 阻塞**：`SettingsViewModel.init` 曾同步调用
 *    `restoreSyncCredentials()`（含 WebDAV/S3 Keystore 解密）与 `updateWifiOnlySync`，
 *    而每个设置二级路由都会 `hiltViewModel()` 新建 VM ⇒ 点进任一设置二级页都卡一下；
 * 2. **控制器构造期探测**：`SettingsSyncController` 初值曾调用 `probeSealHardwareBacked()`
 *    → `syncSealSecurityLevel()`，同样落在导航组合线程；
 * 3. **水合回潮**：若 restore 又回到 init，或 hydrate 不再走 `Dispatchers.IO`，
 *    上述停顿会原样复现。
 */
class SettingsNavigationHydrationWiringTest {

    private val settingsViewModelSource: String
        get() = readSource(SETTINGS_VM)

    private val settingsSyncControllerSource: String
        get() = readSource(SYNC_CONTROLLER)

    private val navGraphRoutesSource: String
        get() = readSource(NAV_ROUTES)

    @Test
    fun `SettingsViewModel init 不得同步 restoreSyncCredentials`() {
        val initBody = functionBody(settingsViewModelSource, "    init {")
        assertTrue(
            "init 必须显式说明不再同步 restore（禁回潮）",
            initBody.contains("ISSUE-P2-406")
        )
        assertTrue(
            "init 内不得再调用 restoreSyncCredentials（Keystore 解密阻塞导航组合线程）",
            !initBody.contains("restoreSyncCredentials()")
        )
    }

    @Test
    fun `hydrate 走后台派发器且由 ViewModel 单语句委托`() {
        val hydrateBody = functionBody(settingsSyncControllerSource, "    fun hydrate()")
        assertTrue(
            "hydrate 必须在 Dispatchers.IO 上执行（Keystore 解密不得占主线程）",
            hydrateBody.contains("Dispatchers.IO")
        )
        assertTrue(
            "hydrate 必须触发凭据恢复",
            hydrateBody.contains("restoreSyncCredentials()")
        )
        assertTrue(
            "hydrate 必须回填 wifiOnlySync",
            hydrateBody.contains("updateWifiOnlySync(")
        )
        assertTrue(
            "SettingsViewModel.hydrateSyncUi 必须是单语句委托（PD-23）",
            settingsViewModelSource.contains(
                "fun hydrateSyncUi() = syncController.hydrate()"
            )
        )
    }

    @Test
    fun `同步页路由触发水合且控制器构造期不探测 Keystore`() {
        assertTrue(
            "settingsSyncRoute 必须触发 hydrateSyncUi（否则同步页凭据预填断裂）",
            navGraphRoutesSource.contains("settingsViewModel.hydrateSyncUi()")
        )
        val controllerInit = functionBody(
            settingsSyncControllerSource,
            "    private val syncStateFlow = MutableStateFlow("
        )
        assertTrue(
            "syncStateFlow 初值不得在构造期探测 Keystore",
            !controllerInit.contains("probeSealHardwareBacked()")
        )
        assertTrue(
            "封印等级仍须由 probe 刷新（ISSUE-P2-285 实测链路不得断）",
            settingsSyncControllerSource.contains("syncSealHardwareBacked = probeSealHardwareBacked()")
        )
    }

    /** 取「起始 token 之后到下一顶层同缩进成员」之间的函数体（粗粒度源码守卫） */
    private fun functionBody(source: String, startToken: String): String {
        val start = source.indexOf(startToken)
        assertTrue("未找到起始 token：$startToken", start >= 0)
        val from = start + startToken.length
        // 以「顶层空白 + fun/val/private」为下一成员边界；找不到则取到文件末尾
        val rest = source.substring(from)
        val next = Regex("\n    (?:fun |private fun |private val |val |// ===)").find(rest)
        return if (next == null) rest else rest.substring(0, next.range.first)
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val SETTINGS_VM =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsViewModel.kt"
        const val SYNC_CONTROLLER =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsSyncController.kt"
        const val NAV_ROUTES =
            "app/src/main/java/com/keepasskey/app/ui/KeePasskeySettingsNavGraphRoutes.kt"

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
            error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
        }
    }
}
