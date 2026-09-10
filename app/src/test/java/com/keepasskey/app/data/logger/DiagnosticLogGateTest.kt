package com.keepasskey.app.data.logger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-03 (43f)：诊断日志闸门接线单元测试。
 *
 * 验收要点：设置页「诊断日志」开关必须**真实生效**——关闭后普通诊断事件不再进入缓冲；
 * 同时导出审计留痕（ISSUE-P2-10 治理要求）不得被该用户偏好静默关闭。
 */
class DiagnosticLogGateTest {

    @Test
    fun `闸门关闭时普通诊断事件不进入缓冲`() {
        val buffer = DebugLogBuffer(DiagnosticLogGate.alwaysOff())

        buffer.info("SyncCoordinator", "同步开始")
        buffer.warn("SyncCoordinator", "缓存监督告警")
        buffer.error("SyncCoordinator", "同步失败")
        buffer.debug("SyncCoordinator", "详细过程")

        assertTrue("关闭态不得记录任何普通诊断事件", buffer.snapshot().isEmpty())
    }

    @Test
    fun `闸门开启时普通诊断事件正常记录`() {
        val buffer = DebugLogBuffer(DiagnosticLogGate.alwaysOn())

        buffer.info("SyncCoordinator", "同步开始")

        assertEquals(1, buffer.snapshot().size)
        assertTrue(buffer.snapshot().single().contains("[INFO] [SyncCoordinator] 同步开始"))
    }

    @Test
    fun `导出审计不受诊断日志开关约束`() {
        val buffer = DebugLogBuffer(DiagnosticLogGate.alwaysOff())

        buffer.audit("ExportAudit", "导出审计: 类型=PLAINTEXT_XML, 目标=<marker>, 结果=成功")

        val line = buffer.snapshot().single()
        assertTrue("审计留痕必须落缓冲（不得为假开关所关闭）", line.contains("[AUDIT] [ExportAudit]"))
        assertTrue(line.contains("成功"))
    }

    @Test
    fun `闸门切换对后续写入立即生效`() {
        var enabled = false
        val buffer = DebugLogBuffer(DiagnosticLogGate { enabled })

        buffer.info("T", "关闭态事件")
        assertTrue(buffer.snapshot().isEmpty())

        enabled = true
        buffer.info("T", "开启态事件")
        assertEquals(1, buffer.snapshot().size)
        assertTrue(buffer.snapshot().single().contains("开启态事件"))

        enabled = false
        buffer.info("T", "再次关闭后的事件")
        assertEquals("关闭后不得追加", 1, buffer.snapshot().size)
    }

    @Test
    fun `默认构造保持既有可观测语义且审计与诊断互不干扰`() {
        // 无参构造（纯 JVM 单测便捷路径）等价于闸门恒开
        val buffer = DebugLogBuffer()

        buffer.info("T", "普通事件")
        buffer.audit("ExportAudit", "审计事件")

        assertEquals(2, buffer.snapshot().size)
        assertFalse(buffer.snapshot().first().contains("[AUDIT]"))
        assertTrue(buffer.snapshot().last().contains("[AUDIT]"))
    }
}
