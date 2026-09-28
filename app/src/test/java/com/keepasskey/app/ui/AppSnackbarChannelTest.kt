package com.keepasskey.app.ui

import com.keepasskey.app.R
import com.keepasskey.app.ui.model.UiMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P3-359` AC④ 全局消息通道的语义单测：
 * 1. **单次消费**——每条事件至多被外壳宿主取走一次（一次性与 `clearUserMessage` 对齐）；
 * 2. **撤销动作随事件携带**——由消费方（外壳宿主）触发，生产者不感知显示时机。
 *
 * 通道是进程级单例（生产环境唯一消费者是外壳宿主），其他用例可能先发布过事件：
 * 每条用例开头先**排空**存量，避免跨用例串读。
 */
class AppSnackbarChannelTest {

    /** 排空通道内可能存在的历史事件（虚拟时钟下空通道立即超时返回 null）。 */
    private suspend fun drainChannel() {
        while (withTimeoutOrNull(DRAIN_TIMEOUT_MS) { AppSnackbarChannel.events.first() } != null) {
            // 丢弃存量
        }
    }

    @Test
    fun `通道单次消费同一条消息不会被读到两次`() = runTest {
        drainChannel()

        AppSnackbarChannel.trySend(AppSnackbarEvent(UiMessage(R.string.btn_undo)))
        val first = AppSnackbarChannel.events.first()
        assertEquals(R.string.btn_undo, first.message.resId)

        val second = withTimeoutOrNull(DRAIN_TIMEOUT_MS) { AppSnackbarChannel.events.first() }
        assertNull("消费过的事件不得再次可读（单次消费语义）", second)
    }

    @Test
    fun `undoable事件携带撤销动作并由消费方调用`() = runTest {
        drainChannel()

        var invoked = false
        AppSnackbarChannel.trySend(
            AppSnackbarEvent(
                message = UiMessage(R.string.vault_entry_deleted, undoable = true),
                onUndo = { invoked = true }
            )
        )
        val event = AppSnackbarChannel.events.first()
        assertTrue("undoable 事件必须携带撤销动作", event.message.undoable)
        assertNotNull(event.onUndo)

        event.onUndo!!.invoke()
        assertTrue("撤销动作必须由消费方（外壳宿主）触发", invoked)
    }

    @Test
    fun `非撤销消息不携带动作`() = runTest {
        drainChannel()

        AppSnackbarChannel.trySend(AppSnackbarEvent(UiMessage(R.string.vault_entry_restored)))
        val event = AppSnackbarChannel.events.first()
        assertNull(event.onUndo)
    }

    private companion object {
        /** 空通道等待上限（runTest 虚拟时钟，立即推进） */
        const val DRAIN_TIMEOUT_MS = 50L
    }
}
