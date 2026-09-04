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
}
