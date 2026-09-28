package com.keepasskey.app.autofill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WifiFillBoostPolicy] 单元测试（ISSUE-P3-373 AC①）。
 */
class WifiFillBoostPolicyTest {

    // ===== AC①：设置页包名清单 =====

    @Test
    fun `清单内包名命中且空白恒不命中`() {
        assertTrue(WifiFillBoostPolicy.isWifiSettingsPackage("com.android.settings"))
        assertTrue(WifiFillBoostPolicy.isWifiSettingsPackage("  com.android.settings  "))
        assertTrue(WifiFillBoostPolicy.isWifiSettingsPackage("com.android.networkstack"))
        assertFalse(
            "清单外包名恒 false（AC① 反例：普通应用不得获得 Wi-Fi 加成）",
            WifiFillBoostPolicy.isWifiSettingsPackage("com.example.browser")
        )
        assertFalse(WifiFillBoostPolicy.isWifiSettingsPackage(""))
        assertFalse(WifiFillBoostPolicy.isWifiSettingsPackage("   "))
    }

    // ===== AC①：Wi-Fi 信号词 =====

    @Test
    fun `标题或网址携带信号词即判定有信号`() {
        assertTrue(WifiFillBoostPolicy.hasWifiSignal("家中 WiFi", "https://router.local"))
        assertTrue(WifiFillBoostPolicy.hasWifiSignal("MyRouter", "https://wi-fi.admin/login"))
        assertTrue(WifiFillBoostPolicy.hasWifiSignal("办公室无线网络", ""))
        assertTrue(WifiFillBoostPolicy.hasWifiSignal("Router", "https://ssid-config.example"))
        assertTrue("大小写不敏感", WifiFillBoostPolicy.hasWifiSignal("HOME-WIFI", ""))
    }

    @Test
    fun `无信号与空白恒为 false`() {
        assertFalse(WifiFillBoostPolicy.hasWifiSignal("GitHub", "https://github.com"))
        assertFalse(WifiFillBoostPolicy.hasWifiSignal("", ""))
        assertFalse(WifiFillBoostPolicy.hasWifiSignal("   ", "   "))
    }

    @Test
    fun `词边界反例——无关单词含 wifi 字母串不加成`() {
        assertFalse(
            "字母串粘连（非独立词）不得命中 \bwifi\b 边界",
            WifiFillBoostPolicy.hasWifiSignal("kuwifipass", "")
        )
        assertFalse(WifiFillBoostPolicy.hasWifiSignal("swiftkey", ""))
    }
}
