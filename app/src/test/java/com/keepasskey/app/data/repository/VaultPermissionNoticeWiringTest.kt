package com.keepasskey.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ISSUE-P3-230` 的**接线守卫**（源码文本断言）。
 *
 * 本条目的失效形态是「缺陷原样复发但**全绿**」：授权失败被重新静默吞掉、提示不再出现、
 * 列表状态不再渲染——这三件事都不会让任何既有用例变红（界面不会崩、行为无异常返回），
 * 与 `CreateVaultLocationWiringTest` / `UiMd3AlignmentWiringTest` 同型，故按同一先例以源码钉住。
 *
 * 四条判据：
 * 1. **不得回到静默**：`takePersistableUriPermission` 的 catch 分支必须落 `AppLog.w`，
 *    且**不得**再出现吞异常的 `catch (_: Throwable)` 形态；
 * 2. **解锁页一次性提示**必须接线（成功分支引用 `unlock_msg_no_persisted_permission`，
 *    且判定经与列表同源的纯函数）；
 * 3. **列表状态与重授入口**必须接线（卡片渲染 + 页面提供 launcher 并复用既有导入路径）；
 * 4. **列表投影**必须填充 `lacksPersistedPermission`（否则 ③ 永远为 false，成了摆设）。
 */
class VaultPermissionNoticeWiringTest {

    @Test
    fun `授权失败不得回到静默吞异常`() {
        val source = readRepoFile(COORDINATOR)
        // 按**方法体**切片而非固定字窗：中文注释的字面长度不可预期，固定窗口会随注释增删误判
        val method = source
            .substringAfter(FN_IMPORT_EXTERNAL, missingDelimiterValue = "")
            .substringBefore(FN_NEXT_ANCHOR, missingDelimiterValue = "")

        assertTrue(
            "[$COORDINATOR] 未切出 importExternalDatabase 方法体（锚点是否已变更？）",
            method.contains("takePersistableUriPermission")
        )
        assertTrue(
            "[$COORDINATOR] 持久化授权失败的 catch 分支未落 AppLog.w —— 缺陷将静默复发（ISSUE-P3-230 AC①）",
            method.contains("AppLog.w(")
        )
        assertFalse(
            "[$COORDINATOR] 持久化授权失败又退回「吞异常的 catch (_: Throwable)」形态 —— " +
                "这正是 ISSUE-P3-230 的原缺陷",
            method.contains("catch (_: Throwable)")
        )
    }

    @Test
    fun `解锁页导入成功分支必须给出未授权提示`() {
        val source = readRepoFile(UNLOCK_VIEW_MODEL)
        assertTrue(
            "[$UNLOCK_VIEW_MODEL] 成功分支未接线 unlock_msg_no_persisted_permission（AC① 的一次性提示）",
            source.contains("unlock_msg_no_persisted_permission")
        )
        assertTrue(
            "[$UNLOCK_VIEW_MODEL] 未授权判定未经与列表同源的纯函数（两处口径会漂移）",
            source.contains("lacksPersistedReadPermission(") && source.contains("persistedReadUriStrings(")
        )
    }

    @Test
    fun `列表卡片与页面提供状态展示与重新授权入口`() {
        val card = readRepoFile(CARD)
        assertTrue(
            "[$CARD] 卡片未渲染 vault_permission_not_persisted 状态（AC②）",
            card.contains("vault_permission_not_persisted")
        )
        assertTrue(
            "[$CARD] 卡片未暴露 onRestoreAccess 回调（AC② 的重授入口）",
            card.contains("onRestoreAccess")
        )

        val screen = readRepoFile(PICKER_SCREEN)
        assertTrue(
            "[$PICKER_SCREEN] 页面未接线 onRestoreAccess（入口点了没反应）",
            screen.contains("onRestoreAccess = {")
        )
        assertTrue(
            "[$PICKER_SCREEN] 重授未复用既有导入路径（onImportFromSource）——不得新开登记分支",
            screen.contains("onImportFromSource(OpenVaultSourceType.LOCAL")
        )
    }

    @Test
    fun `列表投影必须填充缺授权标记`() {
        val source = readRepoFile(CATALOG)
        assertTrue(
            "[$CATALOG] buildDatabaseList 未填充 lacksPersistedPermission —— 卡片状态永远是 false（摆设）",
            source.contains("lacksPersistedPermission = ")
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

        /** 方法体切片锚点：起于目标方法签名，止于其后继方法签名 */
        const val FN_IMPORT_EXTERNAL = "suspend fun importExternalDatabase"
        const val FN_NEXT_ANCHOR = "suspend fun assessKdfStrength"

        const val COORDINATOR =
            "app/src/main/java/com/keepasskey/app/data/repository/VaultLifecycleCoordinator.kt"
        const val CATALOG =
            "app/src/main/java/com/keepasskey/app/data/repository/VaultDatabaseCatalog.kt"
        const val UNLOCK_VIEW_MODEL =
            "app/src/main/java/com/keepasskey/app/ui/screens/unlock/UnlockViewModel.kt"
        const val CARD =
            "app/src/main/java/com/keepasskey/app/ui/screens/database/VaultDatabaseCard.kt"
        const val PICKER_SCREEN =
            "app/src/main/java/com/keepasskey/app/ui/screens/database/DatabasePickerScreen.kt"
    }
}
