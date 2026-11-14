package com.keepasskey.app.data.logger

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 私有目录留存的最小行为：导出覆盖周期内所有行，过期行被裁剪，周期可即时调整。 */
class DiagnosticLogHistoryTest {

    @After
    fun tearDown() = DiagnosticLogHistory.clear()

    @Test
    fun `按天留存的日志可导出且过期行被裁剪`() {
        val dir = Files.createTempDirectory("diagnostic-history").toFile()
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val stale = LocalDate.now().minusDays(10).format(DateTimeFormatter.ISO_LOCAL_DATE)
        File(dir, "diagnostic_history.log").writeText(
            "[$stale] [00:00:00.000] [main] [INFO] [X] stale-entry\n" +
                "[$today] [00:00:01.000] [main] [INFO] [X] fresh-entry\n"
        )

        DiagnosticLogHistory.configure(dir, 3)
        DiagnosticLogHistory.append("[$today] [00:00:02.000] [main] [INFO] [X] appended-entry")

        val exported = DiagnosticLogHistory.snapshotForExport()
        assertNotNull("启用磁盘留存后导出正文应取归档", exported)
        assertFalse("超出保留周期的行必须被裁剪", exported!!.contains("stale-entry"))
        assertTrue("周期内的既有行应保留", exported.contains("fresh-entry"))
        assertTrue("本进程新写入的行应可读回", exported.contains("appended-entry"))
    }

    @Test
    fun `崩溃现场同步追加在进程退出前即落到归档`() {
        val dir = Files.createTempDirectory("diagnostic-sync").toFile()
        DiagnosticLogHistory.configure(dir, 3)

        DiagnosticLogHistory.appendSync("[2026-11-14] [14:43:00.500] [main] [ERROR] [Crash] crash-entry")

        val file = File(dir, "diagnostic_history.log")
        val content = file.readText()
        assertTrue("同步追加必须立刻可见，不能等异步队列：$content", content.contains("crash-entry"))
    }
}
