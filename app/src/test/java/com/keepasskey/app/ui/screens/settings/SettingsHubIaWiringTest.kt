package com.keepasskey.app.ui.screens.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-410 / 411 / 412：设置 Hub 与子页信息架构静态接线守卫。
 *
 * 判据为源码模式（非 Compose 测试）：CI 无设备，但可锁定「改主密码是否仍在存储区 / 预留假开关是否复活 /
 * 架构卡是否仍在安全页」等可静态验证的接线事实。
 */
class SettingsHubIaWiringTest {

    private fun readSrc(relative: String): String {
        val candidates = listOf(
            File("app/src/main/java/com/keepasskey/app/ui/screens/settings/$relative"),
            File("../app/src/main/java/com/keepasskey/app/ui/screens/settings/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("无法定位源文件: $relative")
        return file.readText()
    }

    private fun readSubscreen(fileName: String): String =
        readSrc("subscreens/$fileName")

    @Test
    fun settingsHub_moveMasterPasswordOutOfStorageSection() {
        val src = readSrc("SettingsScreen.kt")
        val securityStart = src.indexOf("R.string.settings_cat_security")
        val storageStart = src.indexOf("R.string.settings_cat_storage")
        assertTrue(securityStart >= 0)
        assertTrue(storageStart >= 0)
        val changeKey = src.indexOf("R.string.settings_change_master_key")
        assertTrue(changeKey > securityStart)
        assertTrue(changeKey < storageStart)
        assertFalse(src.contains("更改主密钥"))
        assertTrue(src.contains("settings_change_master_key"))
    }

    @Test
    fun settingsHub_statusSummaryAndSystemAutofillDeepLink() {
        val src = readSrc("SettingsScreen.kt")
        assertTrue(src.contains("R.string.settings_status_title"))
        assertTrue(src.contains("SystemSettingsNavigation.autofillServiceIntent"))
        assertTrue(src.contains("R.string.settings_status_biometric"))
        assertTrue(src.contains("R.string.settings_status_sync"))
    }

    @Test
    fun settingsHub_debugHiddenFromProductionHub() {
        val src = readSrc("SettingsScreen.kt")
        assertTrue(src.contains("BuildConfig.DEBUG"))
        val debugIdx = src.indexOf("R.string.debug_title")
        assertTrue(debugIdx >= 0)
        val gateIdx = src.indexOf("if (BuildConfig.DEBUG)")
        assertTrue(gateIdx >= 0 && gateIdx < debugIdx)
    }

    @Test
    fun settingsHub_displaySectionUsesInterfaceNaming() {
        val zh = File("app/src/main/res/values/strings.xml")
            .takeIf { it.isFile } ?: File("../app/src/main/res/values/strings.xml")
        val text = zh.readText()
        assertTrue(text.contains("settings_cat_display"))
        assertTrue(text.contains("界面与显示"))
        assertTrue(text.contains("settings_change_master_key\">更改主密码"))
    }

    @Test
    fun autofill_reservedSwitchRemoved() {
        val components = readSubscreen("AutofillSettingsComponents.kt")
        assertFalse(components.contains("settings_pref_reserved_suffix"))
        assertFalse(components.contains("enabled = false"))
        assertFalse(components.contains("onAutoReturnFromQueryToggle"))
        assertFalse(components.contains("AutofillTotpCard"))
    }

    @Test
    fun autofill_totpSectionRemovedFromAutofillScreen() {
        val screen = readSubscreen("AutofillSettingsScreen.kt")
        assertFalse(screen.contains("onAutofillCopyTotpToggle"))
        assertFalse(screen.contains("onAutoReturnFromQueryToggle"))
        assertFalse(screen.contains("autofill_section_totp"))
    }

    @Test
    fun totpScreen_hostsAutofillLinkageSwitches() {
        val totp = readSubscreen("TotpSettingsScreen.kt")
        assertTrue(totp.contains("onAutofillCopyTotpToggle"))
        assertTrue(totp.contains("onAutofillShowTotpNotificationToggle"))
        assertTrue(totp.contains("autofill_copy_totp_title"))
        assertTrue(totp.contains("autofill_totp_notif_title"))
    }

    @Test
    fun architectureCardMovedFromSecurityToAbout() {
        val security = readSubscreen("SecuritySettingsScreen.kt")
        assertFalse(security.contains("sec_arch_title"))
        assertFalse(security.contains("sec_arch_desc"))

        val about = readSubscreen("AboutSettingsScreen.kt")
        assertTrue(about.contains("AboutArchitectureCard"))
        val sections = readSubscreen("AboutSettingsScreenSections.kt")
        assertTrue(sections.contains("sec_arch_title"))
        assertTrue(sections.contains("sec_arch_desc"))
    }
}
