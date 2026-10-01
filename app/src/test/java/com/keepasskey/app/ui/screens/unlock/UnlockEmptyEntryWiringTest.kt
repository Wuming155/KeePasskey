package com.keepasskey.app.ui.screens.unlock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 解锁页空状态两枚入口的**接线守卫**（用户裁决 2026-10-01：就地在解锁页弹框，
 * 源码文本断言，体例沿用 [com.keepasskey.app.data.repository.VaultPermissionNoticeWiringTest]）。
 *
 * 裁决背景：原口径（ISSUE-P2-424 AC① / ISSUE-P3-427）是带 `openCreate` / `openImport`
 * 路由参数导航到库管理页并自动展开对应对话框，实测投诉弹框背后就是该页自带的
 * 「新建密码库 / 导入已有库」入口——与来源页形成嵌套重复。现改为解锁页就地弹框
 * （[UnlockVaultDialogsHost]），对话框组件与业务动作复用库管理页同一套实现。
 *
 * 为什么只能靠文本判据：本条目的失效形态全是**静默**的——把入口改回导航、或在库管理页
 * 复活直达参数，界面都不会崩、也不会让任何行为用例变红，用户会重新看见
 * 「弹框背后还有一套同义入口」的嵌套形态，故本守卫把**已裁决的口径**钉住。
 *
 * 四条判据：
 * 1. 空状态两枚卡片各走**专属**回调（`onCreateNewVault` / `onOpenExistingVault`），
 *    不复用「切换库」的 `onNavigateToDatabasePicker`；
 * 2. 解锁页把两枚回调接到 [DatabasePickerViewModel] 的开窗动作（就地弹框）；
 * 3. 解锁页必须承载 [UnlockVaultDialogsHost]（对话框不在，回调就是空响）；
 * 4. 导航层**不得**复活 `openCreate` / `openImport` 直达路由参数（嵌套重复回潮）；
 *    且「切换库」入口不得被顺手改成弹新建向导（点了切换库却弹出建库框是另一类欺骗）。
 */
class UnlockEmptyEntryWiringTest {

    @Test
    fun `空状态两枚卡片各走专属回调而非切换库回调`() {
        val source = readRepoFile(EMPTY_SECTIONS)
        assertTrue(
            "[$EMPTY_SECTIONS] 「新建密码库」卡片未接 onCreateNewVault（用户裁决 2026-10-01）",
            source.contains("onClick = onCreateNewVault")
        )
        assertTrue(
            "[$EMPTY_SECTIONS] 「打开已有库」卡片未接 onOpenExistingVault（用户裁决 2026-10-01）",
            source.contains("onClick = onOpenExistingVault")
        )
        assertFalse(
            "[$EMPTY_SECTIONS] 空状态不得再引用 onNavigateToDatabasePicker（那是「切换库」的回调）",
            source.contains("onNavigateToDatabasePicker")
        )
    }

    @Test
    fun `解锁页把两枚回调接到库管理VM的开窗动作`() {
        val source = readRepoFile(UNLOCK_SCREEN)
        assertTrue(
            "[$UNLOCK_SCREEN] 「打开已有库」未接 pickerViewModel::openOpenSourceDialog（就地弹框失效）",
            source.contains("pickerViewModel::openOpenSourceDialog")
        )
        assertTrue(
            "[$UNLOCK_SCREEN] 「新建密码库」未接 pickerViewModel::openCreateDialog（就地弹框失效）",
            source.contains("pickerViewModel::openCreateDialog")
        )
    }

    @Test
    fun `解锁页承载就地对话框宿主`() {
        val source = readRepoFile(UNLOCK_SCREEN)
        assertTrue(
            "[$UNLOCK_SCREEN] 未承载 UnlockVaultDialogsHost（回调在、对话框不在 = 点击空响）",
            source.contains("UnlockVaultDialogsHost(pickerViewModel)")
        )
    }

    @Test
    fun `导航层不得复活直达路由参数且切换库入口保持原样`() {
        val navSource = readRepoFile(NAV_ROUTES)
        assertFalse(
            "[$NAV_ROUTES] 直达路由参数回潮（openCreate）——弹框背后露出库管理页同义入口的嵌套形态复现",
            navSource.contains("openCreate")
        )
        assertFalse(
            "[$NAV_ROUTES] 直达路由参数回潮（openImport）",
            navSource.contains("openImport")
        )

        val unlockSource = readRepoFile(UNLOCK_SCREEN)
        // 「切换库」的两处入口（概要卡片 clickable 与 TextButton）仍走切换回调
        val switchUses = Regex("onNavigateToDatabasePicker\\(\\)|onClick = onNavigateToDatabasePicker")
            .findAll(unlockSource).count()
        assertTrue(
            "[$UNLOCK_SCREEN] 「切换库」入口不再接 onNavigateToDatabasePicker（命中 $switchUses 处）——" +
                "把切换库改成弹新建向导会欺骗用户",
            switchUses >= 2
        )
        assertFalse(
            "[$UNLOCK_SCREEN] 切换库处误用 onCreateNewVault（点了切换库却弹建库框）",
            unlockSource.contains("clickable { onCreateNewVault() }") ||
                unlockSource.contains("onClick = onCreateNewVault")
        )
    }

    /** 源码全文；app 模块测试工作目录为 `app/`，向上回溯定位仓库根 */
    private fun readRepoFile(relativePath: String): String {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        repeat(ROOT_SEARCH_DEPTH) {
            val candidate = dir ?: return@repeat
            val target = File(candidate, relativePath)
            if (target.isFile) return target.readText()
            dir = candidate.parentFile
        }
        error("无法定位仓库文件：$relativePath（起始：${System.getProperty("user.dir")}）")
    }

    private companion object {
        const val ROOT_SEARCH_DEPTH = 4

        const val EMPTY_SECTIONS =
            "app/src/main/java/com/keepasskey/app/ui/screens/unlock/UnlockEmptyVaultSections.kt"
        const val UNLOCK_SCREEN =
            "app/src/main/java/com/keepasskey/app/ui/screens/unlock/UnlockScreen.kt"
        const val NAV_ROUTES =
            "app/src/main/java/com/keepasskey/app/ui/KeePasskeyNavGraphRoutes.kt"
    }
}
