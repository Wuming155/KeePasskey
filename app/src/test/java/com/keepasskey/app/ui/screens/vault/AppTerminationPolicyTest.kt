package com.keepasskey.app.ui.screens.vault

import org.junit.Assert.assertEquals
import org.junit.Test

class AppTerminationPolicyTest {
    @Test
    fun `终止入口仅在偏好开启且宿主可用时显示`() {
        listOf(true, false).forEach { enabled ->
            listOf(true, false).forEach { hostAvailable ->
                assertEquals(
                    enabled && hostAvailable,
                    AppTerminationPolicy.showsEntry(enabled = enabled, hostAvailable = hostAvailable),
                )
            }
        }
    }

    @Test
    fun `退出必须先退栈再清理缓存最后终止进程`() {
        val calls = mutableListOf<String>()
        AppTerminationPolicy.terminate(
            detachTask = { calls.add("detach") },
            purgeCaches = { calls.add("purge") },
            exitProcess = { code -> calls.add("exit:$code") },
        )
        assertEquals(listOf("detach", "purge", "exit:0"), calls)
    }
}
