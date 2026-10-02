package com.keepasskey.app.ui

import com.keepasskey.app.data.repository.AppLanguage
import com.keepasskey.app.ui.components.BottomNavItem
import com.keepasskey.app.ui.navigation.Screen
import com.keepasskey.app.ui.theme.AppThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ===== hiddenTabRedirectRoute（ISSUE-P3-443：可见性由有序可见 Tab 名单裁决）=====

    @Test
    fun `正在浏览的 Tab 被隐藏时回落到密码库`() {
        assertEquals(
            Screen.VaultList.route,
            hiddenTabRedirectRoute(
                currentRoute = Screen.Authenticator.route,
                visibleItems = listOf(BottomNavItem.VAULT, BottomNavItem.GENERATOR, BottomNavItem.SETTINGS)
            )
        )
        assertEquals(
            Screen.VaultList.route,
            hiddenTabRedirectRoute(
                currentRoute = Screen.Generator.route,
                visibleItems = listOf(BottomNavItem.VAULT, BottomNavItem.AUTHENTICATOR, BottomNavItem.SETTINGS)
            )
        )
    }

    @Test
    fun `Tab 仍可见或不在该 Tab 时不回落`() {
        val allVisible = BottomNavItem.entries.toList()
        // 四条「不该发生导航」的组合：可见 / 在别处 / 无路由 / 顺序无关
        assertNull(
            hiddenTabRedirectRoute(Screen.Authenticator.route, visibleItems = allVisible)
        )
        assertNull(
            hiddenTabRedirectRoute(Screen.VaultList.route, visibleItems = listOf(BottomNavItem.VAULT))
        )
        assertNull(hiddenTabRedirectRoute(null, visibleItems = allVisible))
        // 解锁页不在顶层 Tab 路由集内，不受 Tab 配置影响（原实现即如此）
        assertNull(
            hiddenTabRedirectRoute(Screen.Unlock.route, visibleItems = listOf(BottomNavItem.VAULT))
        )
    }

    // ===== BottomNavItem 显隐 + 排序解析（ISSUE-P3-443 行为层）=====

    @Test
    fun `名单即顺序 不在名单中的 Tab 为隐藏`() {
        val resolved = BottomNavItem.resolveVisibleItems(
            listOf("SETTINGS", "VAULT", "AUTHENTICATOR")
        )
        assertEquals(
            listOf(BottomNavItem.SETTINGS, BottomNavItem.VAULT, BottomNavItem.AUTHENTICATOR),
            resolved
        )
    }

    @Test
    fun `密码库缺失时回插队首 空名单回落全部可见`() {
        assertEquals(
            listOf(BottomNavItem.VAULT, BottomNavItem.SETTINGS),
            BottomNavItem.resolveVisibleItems(listOf("SETTINGS"))
        )
        assertEquals(BottomNavItem.entries.toList(), BottomNavItem.resolveVisibleItems(emptyList()))
        // 非法名一律忽略
        assertEquals(BottomNavItem.entries.toList(), BottomNavItem.resolveVisibleItems(listOf("BOGUS")))
    }

    @Test
    fun `完整展示序列为可见在前隐藏殿后`() {
        assertEquals(
            listOf(BottomNavItem.VAULT, BottomNavItem.SETTINGS, BottomNavItem.AUTHENTICATOR, BottomNavItem.GENERATOR),
            BottomNavItem.displayOrderFor(listOf("VAULT", "SETTINGS"))
        )
        // 不可隐藏集合：密码库与设置固定显示
        assertFalse(BottomNavItem.VAULT.canHide)
        assertFalse(BottomNavItem.SETTINGS.canHide)
        assertTrue(BottomNavItem.AUTHENTICATOR.canHide)
        assertTrue(BottomNavItem.GENERATOR.canHide)
    }
}
