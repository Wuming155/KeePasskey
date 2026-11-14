package com.keepasskey.app.data.logger

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 故障现场快照的边界：只写枚举/数值、按分钟限流、诊断开关关闭时不采集。
 */
class DiagnosticSnapshotTest {

    @Test
    fun `快照只含枚举与数值且不携带路径`() {
        val text = DiagnosticSnapshot.describe(
            context = null,
            locked = true,
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        )

        assertTrue(
            "字段顺序与取值须稳定：$text",
            Regex(
                """snapshot=locked=true, mem=\d+MB, storage=\?, powerSave=\?, foreground=\?, tz=Asia/Shanghai"""
            ).matches(text)
        )
        assertFalse("不得输出库名或文件路径", text.contains(".kdbx"))
        assertFalse("不得输出存储路径", text.contains("storage=/"))
    }

    @Test
    fun `快照按分钟限流且窗口外恢复采集`() {
        val buffer = DebugLogBuffer()
        var now = 1_000L
        var sequence = 0
        val emitter = DiagnosticSnapshotEmitter(
            buffer = buffer,
            throttle = DiagnosticSnapshotThrottle(minIntervalMs = 60_000L),
            now = { now },
            describe = {
                sequence++
                "snapshot=locked=false, mem=1MB, storage=2MB, powerSave=false, foreground=true, seq=$sequence"
            }
        )

        emitter.onError()
        emitter.onError()
        assertEquals("同一分钟内最多一行", 1, buffer.snapshot().size)

        now += 60_000L
        emitter.onError()
        assertEquals("窗口外应恢复采集", 2, buffer.snapshot().size)
    }

    @Test
    fun `诊断开关关闭时不采集现场快照`() {
        val buffer = DebugLogBuffer(DiagnosticLogGate.alwaysOff())
        val emitter = DiagnosticSnapshotEmitter(
            buffer = buffer,
            now = { 1_000L },
            describe = { error("开关关闭时不应计算快照") }
        )

        emitter.onError()

        assertTrue("关闭诊断日志后不得留下快照", buffer.snapshot().isEmpty())
    }
}
