package com.keepasskey.app.security

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardSecurityManagerScheduledClearTest {
    @Test
    fun `关闭自动清除时自定义超时不得绕过开关`() {
        listOf(null, -1, 0, 1, 30, 3600).forEach { custom ->
            assertEquals(
                null,
                ClipboardClearPolicy.resolveScheduledTimeoutSeconds(
                    customTimeoutSeconds = custom,
                    autoClearClipboard = false,
                    settingsTimeoutSeconds = 30,
                ),
            )
        }
    }

    @Test
    fun `开启时优先使用自定义超时且非正数不调度`() {
        listOf(null to 30, -1 to null, 0 to null, 7 to 7).forEach { (custom, expected) ->
            assertEquals(
                expected,
                ClipboardClearPolicy.resolveScheduledTimeoutSeconds(
                    customTimeoutSeconds = custom,
                    autoClearClipboard = true,
                    settingsTimeoutSeconds = 30,
                ),
            )
        }
    }
}
