package com.keepasskey.app.ui.screens.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 详情页两个破坏性确认（单条删除 / 版本回滚）的**出口接线守卫**（ISSUE-P3-188 剩余清单第 5 项）。
 *
 * [EntryDetailConfirmExitTest] 锁住了纯函数面的策略与顺序；本类锁「宿主真的走这条路」：
 * 若有人绕开 [entryDetailConfirmExitHandler] 在闭包里直接上行（例如把 `onDeleteEntry()` 写回
 * 确认按钮闭包），纯函数用例不会报红——§198 §4 的证据边界正是「该层缺断言」。
 * 判据形态与本仓其余接线守卫一致（如 `SettingsSubscreenScaffoldWiringTest`）：读源码文本比对，
 * 不依赖运行时（Compose 呈现无法在 JVM 断言）。
 */
class EntryDetailDestructiveConfirmWiringTest {

    @Test
    fun `两份破坏性确认必须都经出口执行器接线`() {
        val source = readSource(HOST)
        assertEquals(
            "出口执行器调用点应为 2（单条删除 + 版本回滚各一）；数量漂移即接线被改" +
                "（判据带「= 」前缀以排除同文件内的函数定义行）",
            2,
            countOccurrences(source, "= entryDetailConfirmExitHandler(")
        )
        assertTrue(
            "单条删除确认段组件不得被移除（否则上一条判据空转）",
            source.contains("EntryDetailDeleteEntryConfirm(")
        )
        assertTrue(
            "版本回滚确认段组件不得被移除（否则上一条判据空转）",
            source.contains("EntryDetailRollbackConfirm(")
        )
    }

    @Test
    fun `单条删除不得在闭包里直接上行`() {
        val source = readSource(HOST)
        assertFalse(
            "onDeleteEntry 只能作为 propagate 引用传入执行器；出现 onDeleteEntry() 即绕过确认闸门",
            source.contains("onDeleteEntry()")
        )
        assertEquals(
            "删除上行闭包应恰好一份（reset / propagate 各一处、两意图共用）",
            1,
            countOccurrences(source, "propagate = onDeleteEntry")
        )
    }

    @Test
    fun `回滚上行只允许两处合法现场且确认闸门必须走执行器`() {
        val source = readSource(HOST)
        assertEquals(
            "onRollbackRevision 调用合法现场共 2 处：①回滚确认经执行器的 propagate 闭包；" +
                "②差异对话框 onRollback 本体（它无确认步骤、不属本项两份闸门，不在整改范围）。" +
                "出现第 3 处即有人绕过确认闸门",
            2,
            countOccurrences(source, "onRollbackRevision(")
        )
        assertEquals(
            "回滚确认的上行必须写在执行器的 propagate 实参里（先复位、再上行）",
            1,
            countOccurrences(source, "propagate = { onRollbackRevision(rev) }")
        )
    }

    @Test
    fun `触发态复位各只允许一份且写在执行器的 reset 实参里`() {
        val source = readSource(HOST)
        assertEquals(
            "showDeleteEntryConfirm = false 应只出现在执行器的 reset 实参（其余出口一律经意图上报）",
            1,
            countOccurrences(source, "showDeleteEntryConfirm = false")
        )
        assertEquals(
            "revisionToRollback = null 应只出现在执行器的 reset 实参（差异对话框用的是 revisionToDiff，不在本判据内）",
            1,
            countOccurrences(source, "revisionToRollback = null")
        )
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("守卫目标文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
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

    private companion object {
        const val HOST =
            "app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailDialogHost.kt"

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
