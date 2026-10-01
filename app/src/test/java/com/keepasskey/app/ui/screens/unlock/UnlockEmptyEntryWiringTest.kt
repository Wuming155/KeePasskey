package com.keepasskey.app.ui.screens.unlock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 解锁页空状态两枚入口的**接线守卫**（`ISSUE-P3-427`，源码文本断言，体例沿用
 * [com.keepasskey.app.data.repository.VaultPermissionNoticeWiringTest]）。
 *
 * 为什么只能靠文本判据：本条目的失效形态全是**静默**的——把「新建密码库」卡片的回调改回
 * `onNavigateToDatabasePicker`，或导航层漏传 `openCreate = true`，界面都不会崩、也不会让任何
 * 行为用例变红，用户重新退回「点新建 → 停在库管理页 → 再点一次 → 看见并列的导入」这一
 * 正是 `ISSUE-P3-426` / `ISSUE-P3-427` 投诉的形态（§391 当时只按「同排两按钮」处置，
 * 未收口这条路径，故本守卫把**已裁决的口径**钉住）。
 *
 * 四条判据：
 * 1. 空状态「新建」卡片走**专属**回调 `onCreateNewVault`，不复用「切换库」的 `onNavigateToDatabasePicker`；
 * 2. 导航层据该回调导航时**必须**带 `openCreate = true`（导入侧同理带 `openImport = true`）；
 * 3. 库管理页把两个路由参数分别接到 VM 的一次消费入口；
 * 4. 「切换库」的两处入口**不得**被顺手改成弹新建向导（点了切换库却弹出建库框是另一类欺骗）。
 */
class UnlockEmptyEntryWiringTest {

    @Test
    fun `空状态新建卡片走专属回调而非切换库回调`() {
        val source = readRepoFile(EMPTY_SECTIONS)
        assertTrue(
            "[$EMPTY_SECTIONS] 「新建密码库」卡片未接 onCreateNewVault（ISSUE-P3-427 AC①）",
            source.contains("onClick = onCreateNewVault")
        )
        assertTrue(
            "[$EMPTY_SECTIONS] 专属回调缺默认值：非导航宿主（自动填充 / 凭据创建窗口）会被迫显式传参",
            source.contains("onCreateNewVault: () -> Unit = onNavigateToDatabasePicker")
        )
    }

    @Test
    fun `导航层两条直达路径各自带自己的路由参数`() {
        val source = readRepoFile(NAV_ROUTES)
        assertTrue(
            "[$NAV_ROUTES] 新建直达未带 openCreate = true（点了新建却停在列表页）",
            source.contains("createRoute(openCreate = true)")
        )
        assertTrue(
            "[$NAV_ROUTES] 导入直达未带 openImport = true（ISSUE-P2-424 AC① 回退）",
            source.contains("createRoute(openImport = true)")
        )
    }

    @Test
    fun `库管理页把两个路由参数接到一次消费入口`() {
        val source = readRepoFile(PICKER_SCREEN)
        assertTrue(
            "[$PICKER_SCREEN] autoOpenCreate 未接 viewModel.openCreateFromRoute()",
            source.contains("if (autoOpenCreate) viewModel.openCreateFromRoute()")
        )
        assertTrue(
            "[$PICKER_SCREEN] autoOpenImport 未接 viewModel.openImportFromRoute()",
            source.contains("if (autoOpenImport) viewModel.openImportFromRoute()")
        )
        assertTrue(
            "[$PICKER_SCREEN] 两个直达参数不得各起一个 LaunchedEffect（重放时机不同步会互吞）",
            source.contains("LaunchedEffect(autoOpenImport, autoOpenCreate)")
        )
    }

    @Test
    fun `切换库入口不得被改成弹新建向导`() {
        val source = readRepoFile(UNLOCK_SCREEN)
        // 「切换库」的两处入口（概要卡片 clickable 与 TextButton）仍走切换回调
        val switchUses = Regex("onNavigateToDatabasePicker\\(\\)|onClick = onNavigateToDatabasePicker")
            .findAll(source).count()
        assertTrue(
            "[$UNLOCK_SCREEN] 「切换库」入口不再接 onNavigateToDatabasePicker（命中 $switchUses 处）——" +
                "把切换库改成弹新建向导会欺骗用户",
            switchUses >= 2
        )
        assertFalse(
            "[$UNLOCK_SCREEN] 切换库处误用 onCreateNewVault（点了切换库却弹建库框）",
            source.contains("clickable { onCreateNewVault() }") ||
                source.contains("onClick = onCreateNewVault")
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
        const val PICKER_SCREEN =
            "app/src/main/java/com/keepasskey/app/ui/screens/database/DatabasePickerScreen.kt"
    }
}
