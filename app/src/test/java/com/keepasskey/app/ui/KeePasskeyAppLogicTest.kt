package com.keepasskey.app.ui

import com.keepasskey.app.data.repository.AppLanguage
import com.keepasskey.app.ui.navigation.Screen
import com.keepasskey.app.ui.theme.AppThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * §185：应用外壳 [KeePasskeyApp] 下沉出的**纯逻辑**单测。
 *
 * 这批断言是**本批新增的验证面**——原先该文件只有两条源码文本接线守卫
 * （`AppTerminationPolicyTest` / `AlgoHotPathGuardsTest`），语言映射、主题循环与
 * 「Tab 被隐藏时的回落」三处判定从未被执行过，只能靠读码。PD-11 之所以不豁免本函数，
 * 正是因为它含这类真实逻辑；拆出来即必须可测。
 */
class KeePasskeyAppLogicTest {

    // ===== localeFor =====

    @Test
    fun `语言偏好映射到 Locale，跟随系统中为 null`() {
        assertEquals(Locale.SIMPLIFIED_CHINESE, localeFor(AppLanguage.ZH_CN))
        assertEquals(Locale.ENGLISH, localeFor(AppLanguage.EN_US))
        assertNull("SYSTEM 必须返回 null，表示不改写配置", localeFor(AppLanguage.SYSTEM))
    }

    // ===== nextThemeMode =====

    @Test
    fun `主题三态循环逐跳正确`() {
        assertEquals(AppThemeMode.DARK, nextThemeMode(AppThemeMode.LIGHT))
        assertEquals(AppThemeMode.SYSTEM, nextThemeMode(AppThemeMode.DARK))
        assertEquals(AppThemeMode.LIGHT, nextThemeMode(AppThemeMode.SYSTEM))
    }

    @Test
    fun `主题循环三步回到起点`() {
        var mode = AppThemeMode.LIGHT
        repeat(3) { mode = nextThemeMode(mode) }
        assertEquals(mode, AppThemeMode.LIGHT)
    }

    // ===== hiddenTabRedirectRoute =====

    @Test
    fun `正在浏览的 Tab 被关闭时回落到密码库`() {
        assertEquals(
            Screen.VaultList.route,
            hiddenTabRedirectRoute(
                currentRoute = Screen.Authenticator.route,
                showAuthenticatorTab = false,
                showGeneratorTab = true
            )
        )
        assertEquals(
            Screen.VaultList.route,
            hiddenTabRedirectRoute(
                currentRoute = Screen.Generator.route,
                showAuthenticatorTab = true,
                showGeneratorTab = false
            )
        )
    }

    @Test
    fun `Tab 仍可见或不在该 Tab 时不回落`() {
        // 三条「不该发生导航」的组合：可见 / 在别处 / 无路由
        assertNull(
            hiddenTabRedirectRoute(Screen.Authenticator.route, showAuthenticatorTab = true, showGeneratorTab = true)
        )
        assertNull(
            hiddenTabRedirectRoute(Screen.VaultList.route, showAuthenticatorTab = false, showGeneratorTab = false)
        )
        assertNull(
            hiddenTabRedirectRoute(null, showAuthenticatorTab = false, showGeneratorTab = false)
        )
        // 解锁页不受 Tab 开关影响（原实现即如此：两个分支只匹配各自 Tab 的路由）
        assertNull(
            hiddenTabRedirectRoute(Screen.Unlock.route, showAuthenticatorTab = false, showGeneratorTab = false)
        )
    }
}
