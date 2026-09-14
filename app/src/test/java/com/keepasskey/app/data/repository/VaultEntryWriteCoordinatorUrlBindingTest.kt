package com.keepasskey.app.data.repository

import com.keepasskey.app.passkey.CallingOriginResolver
import com.keepasskey.app.passkey.DomainMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P2-78（威胁建模 T-10）回归：凭据保存的 URL 绑定形态分流。
 *
 * CM 保存通道下传的是调用方 **origin**（浏览器委派 `https://…`、普通应用
 * `android:apk-key-hash:…`）。原实现无条件拼 `"https://$domain"`，落库为
 * `https://https://host` / `https://android:apk-key-hash:…`——条目此后既不匹配
 * web 域也不匹配 `android://` 包名（永久无法被域命中的完整性 / 可用性缺陷）。
 */
class VaultEntryWriteCoordinatorUrlBindingTest {

    @Test
    fun `浏览器委派 web origin 原样入库且可被域匹配命中`() {
        val binding = VaultEntryWriteCoordinator.resolveCredentialUrlBinding(
            "https://example.com", "com.android.chrome"
        )
        assertTrue(binding.isWebBinding)
        assertEquals("https://example.com", binding.entryUrl)
        assertEquals("example.com", binding.displayDomain)

        // 落库 URL 可被 DomainMatcher 命中（同域与子域）
        assertTrue(DomainMatcher.isDomainMatch(binding.entryUrl, "example.com"))
        assertTrue(DomainMatcher.isDomainMatch("example.com", "login.example.com"))
        assertFalse(DomainMatcher.isDomainMatch(binding.entryUrl, "evil-example.com"))
    }

    @Test
    fun `apk-key-hash origin 落 android 包名绑定且可被包名匹配命中`() {
        val origin = CallingOriginResolver.APK_KEY_HASH_PREFIX + "dGVzdA"
        val binding = VaultEntryWriteCoordinator.resolveCredentialUrlBinding(origin, "com.example.app")
        assertFalse(binding.isWebBinding)
        assertEquals("android://com.example.app", binding.entryUrl)
        assertEquals("com.example.app", binding.displayDomain)

        // 落库 URL 可被包名维度命中（与自动填充保存路径语义一致）
        assertTrue(DomainMatcher.isPackageMatch(binding.entryUrl, "com.example.app"))
        assertTrue(DomainMatcher.isAndroidPackageMatch(binding.entryUrl, "com.example.app"))
    }

    @Test
    fun `空白域回落 android 包名绑定`() {
        for (webDomain in listOf<String?>(null, "", "   ")) {
            val binding = VaultEntryWriteCoordinator.resolveCredentialUrlBinding(webDomain, "com.example.app")
            assertFalse(binding.isWebBinding)
            assertEquals("android://com.example.app", binding.entryUrl)
        }
    }

    @Test
    fun `裸域名保持自动填充既有形态 https 拼接`() {
        val binding = VaultEntryWriteCoordinator.resolveCredentialUrlBinding(
            "example.com", "com.example.app"
        )
        assertTrue(binding.isWebBinding)
        assertEquals("https://example.com", binding.entryUrl)
        assertEquals("example.com", binding.displayDomain)
        assertTrue(DomainMatcher.isDomainMatch(binding.entryUrl, "example.com"))
    }

    @Test
    fun `负例_任何输入形态不得产生 https 前缀重复叠加`() {
        val samples = listOf(
            "https://example.com",
            CallingOriginResolver.APK_KEY_HASH_PREFIX + "dGVzdA",
            null, "", "   ", "example.com"
        )
        for (webDomain in samples) {
            val binding = VaultEntryWriteCoordinator.resolveCredentialUrlBinding(webDomain, "com.example.app")
            assertFalse(
                "输入=$webDomain 落库=${binding.entryUrl}：不得出现 https:// 二次叠加",
                binding.entryUrl.contains("https://https://")
            )
            assertTrue(
                "输入=$webDomain 落库=${binding.entryUrl}：不得混入 apk-key-hash 尾巴",
                !binding.entryUrl.contains(CallingOriginResolver.APK_KEY_HASH_PREFIX)
            )
        }
    }

    @Test
    fun `web origin 携带路径与端口时归一为裸域名展示且尾部斜杠被剥离`() {
        val binding = VaultEntryWriteCoordinator.resolveCredentialUrlBinding(
            "https://example.com:8443/login/", "com.android.chrome"
        )
        assertTrue(binding.isWebBinding)
        assertEquals("https://example.com:8443/login", binding.entryUrl)
        assertEquals("example.com", binding.displayDomain)
        assertTrue(DomainMatcher.isDomainMatch(binding.entryUrl, "example.com"))
    }
}
