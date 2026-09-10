package com.keepasskey.app.ui.screens.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-17 `showKillAppOption` 单测：入口可见性与终止动作顺序。
 */
class AppTerminationPolicyTest {

    @Test
    fun `偏好开启且宿主可终止时呈现入口`() {
        assertTrue(AppTerminationPolicy.showsEntry(enabled = true, hostAvailable = true))
    }

    @Test
    fun `偏好关闭时不呈现入口`() {
        assertFalse(AppTerminationPolicy.showsEntry(enabled = false, hostAvailable = true))
    }

    @Test
    fun `宿主不可终止时不呈现入口`() {
        // 无 Activity 上下文时如实不呈现，避免「点了没反应」的假入口
        assertFalse(AppTerminationPolicy.showsEntry(enabled = true, hostAvailable = false))
    }

    @Test
    fun `终止动作为先解除任务栈亲和性再以正常码退出进程`() {
        val calls = mutableListOf<String>()

        AppTerminationPolicy.terminate(
            detachTask = { calls.add("finishAffinity") },
            exitProcess = { code -> calls.add("exit:$code") }
        )

        assertEquals(listOf("finishAffinity", "exit:0"), calls)
        assertEquals(0, AppTerminationPolicy.EXIT_CODE_NORMAL)
    }
}
