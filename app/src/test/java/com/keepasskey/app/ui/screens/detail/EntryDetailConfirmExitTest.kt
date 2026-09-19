package com.keepasskey.app.ui.screens.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-188 剩余清单第 5 项：详情页两个破坏性确认（单条删除 / 版本回滚）**出口决策**的宿主断言。
 *
 * §198 的证据边界：这两份 `AlertDialog` 是不可逆动作（移入回收站 / 覆盖当前版本）前的唯一闸门，
 * 下沉后宿主对它们零断言——若「确认后才上行」被改坏（如取消也上行、上行两次、对话框不复位），
 * 现有测试面不报红。本类把决策面锁进 JVM：
 * [entryDetailConfirmExit] 锁策略（谁上行、谁复位），[entryDetailConfirmExitHandler] 锁执行顺序
 * （「先复位、再上行」，§169 / §198 口径）。
 *
 * 边界：本类不断言 Compose 呈现（JVM 不可达）；「宿主真的走这条路」由
 * [EntryDetailDestructiveConfirmWiringTest] 的静态接线守卫钉住，两类样板不互相顶替。
 */
class EntryDetailConfirmExitTest {

    @Test
    fun `取消决策绝不上行且必复位`() {
        val exit = entryDetailConfirmExit(EntryDetailConfirmIntent.DISMISS)
        assertFalse("取消出口不得上行破坏性动作（确认后才上行）", exit.propagateAction)
        assertTrue("取消出口必须复位触发态，对话框不得滞留", exit.resetTriggerState)
    }

    @Test
    fun `确认决策上行且复位`() {
        val exit = entryDetailConfirmExit(EntryDetailConfirmIntent.CONFIRM)
        assertTrue("确认出口必须上行不可逆动作，否则闸门卡死", exit.propagateAction)
        assertTrue("确认出口必须复位触发态", exit.resetTriggerState)
    }

    @Test
    fun `取消经执行器只复位不上行`() {
        val calls = mutableListOf<String>()
        entryDetailConfirmExitHandler(
            reset = { calls += "reset" },
            propagate = { calls += "propagate" }
        )(EntryDetailConfirmIntent.DISMISS)
        assertEquals("取消路径不得出现 propagate", listOf("reset"), calls)
    }

    @Test
    fun `确认经执行器先复位再上行且恰好一次`() {
        val calls = mutableListOf<String>()
        entryDetailConfirmExitHandler(
            reset = { calls += "reset" },
            propagate = { calls += "propagate" }
        )(EntryDetailConfirmIntent.CONFIRM)
        assertEquals(
            "「先复位、再上行」的顺序或次数被改坏（§200 口径）",
            listOf("reset", "propagate"),
            calls
        )
    }
}
