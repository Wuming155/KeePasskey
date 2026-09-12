package com.keepasskey.app.passkey

import com.keepasskey.app.ui.model.EntryCategory
import com.keepasskey.app.ui.model.UiVaultEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 凭据提供者服务条目匹配算法单元测试
 */
class CredentialProviderMatchingTest {

    private val service = KeePasskeyCredentialProviderService()

    @Test
    fun `测试基于 Web 域名与 RP ID 精确匹配`() {
        val entry1 = UiVaultEntry(
            id = "1",
            title = "GitHub Account",
            username = "octocat",
            url = "https://github.com/login",
            isPasskey = true,
            passkeyRpId = "github.com",
            category = EntryCategory.PASSKEY
        )

        val entry2 = UiVaultEntry(
            id = "2",
            title = "Google Account",
            username = "test@gmail.com",
            url = "https://accounts.google.com",
            category = EntryCategory.LOGIN
        )

        val list = listOf(entry1, entry2)

        val matched = service.findMatchingEntries(list, origin = "https://github.com", packageName = "com.android.chrome")
        assertEquals(1, matched.size)
        assertEquals("GitHub Account", matched.first().title)
    }

    @Test
    fun `测试未匹配应用来源过滤`() {
        val entry = UiVaultEntry(
            id = "3",
            title = "Custom Service",
            username = "user1",
            url = "https://internal.corp.com"
        )

        val matched = service.findMatchingEntries(listOf(entry), origin = "https://unrelated.org", packageName = "com.unrelated.app")
        assertTrue(matched.isEmpty())
    }

    @Test
    fun `测试填充链路包名匹配改走 android 硬约束`() {
        // P2-40 回归锁：origin 为空 → 仅包名维度参与判定
        val webBound = UiVaultEntry(
            id = "4",
            title = "Web Bound",
            username = "user1",
            url = "https://github.com"
        )
        val barePackage = UiVaultEntry(
            id = "5",
            title = "Bare Package",
            username = "user1",
            url = "com.example.app"
        )
        val androidBound = UiVaultEntry(
            id = "6",
            title = "App Bound",
            username = "user1",
            url = "android://com.example.app"
        )

        assertTrue(
            "https://github.com 条目不得被同形包名 github.com 命中",
            service.findMatchingEntries(listOf(webBound), origin = "", packageName = "github.com").isEmpty()
        )
        assertTrue(
            "裸包名条目不得按包名命中（只认显式 android:// 绑定）",
            service.findMatchingEntries(listOf(barePackage), origin = "", packageName = "com.example.app").isEmpty()
        )

        val matched = service.findMatchingEntries(
            listOf(webBound, barePackage, androidBound),
            origin = "",
            packageName = "com.example.app"
        )
        assertEquals(1, matched.size)
        assertEquals("App Bound", matched.first().title)
    }

    @Test
    fun `测试浏览器委派域匹配路径不回归`() {
        // 浏览器委派（可信 web origin）仍只按域匹配放行：包名为浏览器自身包名，不影响域命中
        val webEntry = UiVaultEntry(
            id = "7",
            title = "GitHub Web",
            username = "octocat",
            url = "https://github.com"
        )
        val appBound = UiVaultEntry(
            id = "8",
            title = "GitHub Android App",
            username = "octocat",
            url = "android://com.github.android"
        )

        val matched = service.findMatchingEntries(
            listOf(webEntry, appBound),
            origin = "https://github.com",
            packageName = "com.android.chrome"
        )

        assertEquals(1, matched.size)
        assertEquals("GitHub Web", matched.first().title)
    }
}
