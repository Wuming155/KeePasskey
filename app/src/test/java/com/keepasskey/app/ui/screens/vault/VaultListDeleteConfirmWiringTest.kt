package com.keepasskey.app.ui.screens.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P2-357 AC①/AC③ 的**出口接线守卫**：两份破坏性删除确认（批量删除 / 回收站永久删除）
 * 与软删除 Snackbar 撤销的接线，Compose 呈现无法在 JVM 断言，判据形态与本仓其余接线守卫一致
 * （如 `EntryDetailDestructiveConfirmWiringTest`）：读源码文本比对，不依赖运行时。
 *
 * 与 [VaultListViewModelTest] 的行为用例互补：那边锁「撤销真的恢复条目」，
 * 这边锁「确认对话框真的在闸门上、撤销只由 `ActionPerformed` 触发」。
 */
class VaultListDeleteConfirmWiringTest {

    @Test
    fun `顶栏批量删除与行内永久删除都必须先置位确认对话框`() {
        // §280 规模门禁同批：Scaffold / LazyColumn 装配自 VaultListScreen 逐字迁至同包
        // VaultListContent（顶栏批量删除置位 / 行内永久删除置位随迁），故按
        // 「页面壳 + 内容装配」**并集**扫描——断言与计数逐字保留，只放宽定位范围。
        val screen = readSource(SCREEN) + "\n" + readSource(CONTENT)
        assertTrue(
            "顶栏删除图标只能置位确认开关，不得直连执行体",
            screen.contains("onBatchDelete = { dialogs.showBatchDeleteConfirm = true }")
        )
        assertTrue(
            "回收站行的 DeleteForever 只能置位待确认条目，不得直连 onPurgeEntry",
            screen.contains("onPurge = { dialogs.purgeEntryToDelete = entry }")
        )
        assertEquals(
            "执行体 viewModel::batchDeleteSelected 只允许经宿主透传一处；出现第二处即绕过确认闸门",
            1,
            countOccurrences(screen, "= viewModel::batchDeleteSelected")
        )
        assertEquals(
            "执行体 viewModel::purgeEntry 只允许经宿主透传一处",
            1,
            countOccurrences(screen, "= viewModel::purgeEntry")
        )
    }

    @Test
    fun `两份确认的执行上行必须在确认分支内且复位先于上行`() {
        val host = readSource(HOST)
        assertEquals(
            "两份确认段组件各一处定义 + 一处调用；数量漂移即闸门被移除",
            2,
            countOccurrences(host, "fun VaultBatchDeleteConfirmDialog(") +
                countOccurrences(host, "fun VaultPurgeEntryConfirmDialog(")
        )
        assertEquals(
            "批量删除执行体只允许在确认分支被调用一次",
            1,
            countOccurrences(host, "onBatchDelete()")
        )
        assertEquals(
            "永久删除执行体只允许在确认分支被调用一次（签名是 onPurgeEntry: 不会被此计数命中）",
            1,
            countOccurrences(host, "onPurgeEntry(target.id)")
        )
        assertTrue(
            "确认分支必须先复位开关再上行（先复位、再上行的仓库口径）",
            host.contains("controller.showBatchDeleteConfirm = false") &&
                host.contains("controller.purgeEntryToDelete = null")
        )
        assertResetPrecedesPropagate(
            host,
            confirmAnchor = "VaultBatchDeleteConfirmDialog(",
            reset = "controller.showBatchDeleteConfirm = false",
            propagate = "onBatchDelete()"
        )
        assertResetPrecedesPropagate(
            host,
            confirmAnchor = "VaultPurgeEntryConfirmDialog(",
            reset = "controller.purgeEntryToDelete = null",
            propagate = "onPurgeEntry(target.id)"
        )
    }

    @Test
    fun `撤销只能由 SnackbarResult ActionPerformed 触发且受 undoable 闸门`() {
        // ISSUE-P3-359 AC④：屏级 VaultListSnackbarEffect 已随全局宿主迁移退役——
        // 判据锚点改到外壳宿主与 ViewModel 发布件（判据意图逐条保留：ActionPerformed 闸门、
        // undoable 才附动作标签、撤销入口真实接线；文件路径常量同步改指新现场）
        val host = readSource(HOST_SOURCE)
        assertTrue(
            "撤销出口必须判定 SnackbarResult.ActionPerformed（Dismissed / 超时不得恢复）",
            host.contains("result == SnackbarResult.ActionPerformed")
        )
        assertTrue(
            "只有 undoable 消息（且携带撤销动作）才附加撤销动作标签",
            host.contains("if (message.undoable && event.onUndo != null)")
        )
        assertTrue(
            "撤销动作标签必须作为 actionLabel 传入 showSnackbar",
            host.contains("actionLabel = undoLabel")
        )
        val publish = readSource(PUBLISH)
        assertTrue(
            "列表页发布件必须把撤销入口接到事件（undoable → undoPendingSoftDelete）",
            publish.contains("onUndo = if (message.undoable)") &&
                publish.contains("undoPendingSoftDelete()")
        )
        val controller = readSource(CONTROLLER)
        assertEquals(
            "可撤销标记只能在批量删除成功分支产出一处（其余消息恒为默认 false）",
            1,
            countOccurrences(controller, "undoable = true")
        )
        assertTrue(
            "撤销入口必须消费待撤销批次并置空（防重复恢复）",
            controller.contains("val ids = pendingUndoIdsFlow.value ?: return") &&
                controller.contains("pendingUndoIdsFlow.value = null")
        )
    }

    /** 在含上行的确认现场内断言「复位出现在上行之前」（先复位、再上行的仓库口径）。 */
    private fun assertResetPrecedesPropagate(
        source: String,
        confirmAnchor: String,
        reset: String,
        propagate: String
    ) {
        // 锚点恰有两处现场（渲染调用 + 组件定义，先后顺序不作假设）；
        // 只有含上行的那一处必须满足「先复位再上行」——定义处带的是 onConfirm() 泛参，不会命中。
        val regions = mutableListOf<String>()
        var index = source.indexOf(confirmAnchor)
        while (index >= 0) {
            regions.add(source.substring(index, minOf(index + 400, source.length)))
            index = source.indexOf(confirmAnchor, index + confirmAnchor.length)
        }
        assertEquals("确认段组件现场数异常（调用 + 定义应恰为 2）：$confirmAnchor", 2, regions.size)
        val withPropagate = regions.filter { it.contains(propagate) }
        assertEquals("上行只允许出现在一处现场（确认分支）：$propagate", 1, withPropagate.size)
        val block = withPropagate.single()
        val resetIndex = block.indexOf(reset)
        val propagateIndex = block.indexOf(propagate)
        assertTrue("确认现场内缺少复位：$reset", resetIndex >= 0)
        assertEquals(
            "确认现场应恰有两处复位（onDismiss + onConfirm 各一）：$reset",
            2,
            countOccurrences(block, reset)
        )
        assertTrue("必须先复位再上行（reset=$reset, propagate=$propagate）", resetIndex < propagateIndex)
    }

    private fun countOccurrences(source: String, needle: String): Int {
        var count = 0
        var index = source.indexOf(needle)
        while (index >= 0) {
            count++
            index = source.indexOf(needle, index + needle.length)
        }
        return count
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("守卫目标文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val SCREEN =
            "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListScreen.kt"
        const val CONTENT =
            "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListContent.kt"
        const val HOST =
            "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListDialogHost.kt"
        /** ISSUE-P3-359 AC④：屏级效果件退役后，撤销编排的现场是外壳全局宿主 */
        const val HOST_SOURCE = "app/src/main/java/com/keepasskey/app/ui/AppGlobalSnackbarHost.kt"
        /** 消息发布（含 undoable → 撤销入口接线）的现场 */
        const val PUBLISH =
            "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListMessagePublish.kt"
        const val CONTROLLER =
            "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListActionController.kt"

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先（沿用接线守卫先例） */
        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(4) {
                val candidate = dir ?: return@repeat
                if (File(candidate, "app/src/main/java").isDirectory &&
                    File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
        }
    }
}
