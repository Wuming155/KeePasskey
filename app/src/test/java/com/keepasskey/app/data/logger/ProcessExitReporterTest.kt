package com.keepasskey.app.data.logger

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 进程退出原因的日志形态：枚举名 + 数值，不含任何用户数据。 */
class ProcessExitReporterTest {

    @Test
    fun `退出原因映射为稳定枚举名`() {
        assertEquals("LOW_MEMORY", ProcessExitFormatter.reasonLabel(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("CRASH", ProcessExitFormatter.reasonLabel(ApplicationExitInfo.REASON_CRASH))
        assertEquals("CRASH_NATIVE", ProcessExitFormatter.reasonLabel(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("ANR", ProcessExitFormatter.reasonLabel(ApplicationExitInfo.REASON_ANR))
        assertEquals("USER_REQUESTED", ProcessExitFormatter.reasonLabel(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertEquals("SIGNALED", ProcessExitFormatter.reasonLabel(ApplicationExitInfo.REASON_SIGNALED))
        assertEquals("UNKNOWN", ProcessExitFormatter.reasonLabel(Int.MAX_VALUE))
    }

    @Test
    fun `退出记录只含枚举与数值`() {
        val text = ProcessExitFormatter.format(
            reason = ApplicationExitInfo.REASON_CRASH,
            status = 0,
            importance = 100,
            pssKb = 12_345,
            timestampMs = 0L
        )

        assertTrue(
            "字段顺序与取值须稳定：$text",
            Regex(
                """进程退出记录: reason=CRASH, status=0, importance=100, pss=12345KB, at=\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}"""
            ).matches(text)
        )
    }
}
