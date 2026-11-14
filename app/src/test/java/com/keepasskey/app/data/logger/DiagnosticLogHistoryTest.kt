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
    fun tearDown() {
        DiagnosticLogHistory.clear()
        // 排空归档任务（删除排在前），避免临时目录影响后续用例
        DiagnosticLogHistory.snapshotForExport()
    }

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

    @Test
    fun `调整保留周期后导出立即按新周期裁剪`() {
        val dir = Files.createTempDirectory("diagnostic-retention").toFile()
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val fiveDaysAgo = LocalDate.now().minusDays(5).format(DateTimeFormatter.ISO_LOCAL_DATE)
        DiagnosticLogHistory.configure(dir, 7)
        DiagnosticLogHistory.appendSync("[$fiveDaysAgo] [00:00:00.000] [main] [INFO] [X] old-entry")
        DiagnosticLogHistory.appendSync("[$today] [00:00:01.000] [main] [INFO] [X] fresh-entry")

        DiagnosticLogHistory.setRetentionDays(3)
        val exported = DiagnosticLogHistory.snapshotForExport().orEmpty()

        assertFalse("缩短周期后过期行须立即消失：$exported", exported.contains("old-entry"))
        assertTrue("周期内的行必须保留", exported.contains("fresh-entry"))
    }

    @Test
    fun `长行归档越限后按字节裁剪而非保持超限体积`() {
        val dir = Files.createTempDirectory("diagnostic-bytes").toFile()
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val file = File(dir, "diagnostic_history.log")
        val filler = (1..1_200).map {
            "[$today] [00:00:00.000] [main] [INFO] [X] filler-$it " + "x".repeat(1_000)
        }
        file.writeText(filler.joinToString("\n", postfix = "\n"))
        assertTrue("前置条件：归档须超过 1MB", file.length() > 1_200_000)

        DiagnosticLogHistory.configure(dir, 3)
        repeat(50) { index ->
            DiagnosticLogHistory.append(
                "[$today] [10:00:00.000] [main] [INFO] [X] appended-$index " + "y".repeat(1_000)
            )
        }

        val exported = DiagnosticLogHistory.snapshotForExport().orEmpty()
        assertTrue("最新追加必须保留：$exported", exported.contains("appended-49"))
        assertTrue(
            "裁剪后应回落到约 768KB，而不是每次追加都保持 1.2MB（实际 ${file.length()}）",
            file.length() <= 900_000
        )
        assertTrue("行数上限仍然生效", exported.lines().size <= 4_000)
    }

    @Test
    fun `启动裁剪与崩溃同步追加并发时不丢崩溃行`() {
        val dir = Files.createTempDirectory("diagnostic-race").toFile()
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val file = File(dir, "diagnostic_history.log")
        val filler = (1..1_500).map {
            "[$today] [00:00:00.000] [main] [INFO] [X] filler-$it " + "z".repeat(500)
        }
        file.writeText(filler.joinToString("\n", postfix = "\n"))

        DiagnosticLogHistory.configure(dir, 3)

        // 模拟进程退出前的同步追加与启动裁剪重叠：裁剪的读-改-写必须让追加要么完全在前、
        // 要么完全在后，不能出现在「读完之后、替换之前」而被旧快照覆盖。
        val appended = 200
        val worker = Thread {
            repeat(appended) { index ->
                DiagnosticLogHistory.appendSync(
                    "[$today] [11:00:00.000] [main] [ERROR] [Crash] crash-$index"
                )
                Thread.sleep(1)
            }
        }
        worker.start()
        worker.join()

        val exported = DiagnosticLogHistory.snapshotForExport().orEmpty()
        repeat(appended) { index ->
            assertTrue("崩溃行 crash-$index 被启动裁剪覆盖", exported.contains("crash-$index"))
        }
    }

    @Test
    fun `追加积压时导出快照仍在等待预算内返回`() {
        val dir = Files.createTempDirectory("diagnostic-backlog").toFile()
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        DiagnosticLogHistory.configure(dir, 3)
        File(dir, "diagnostic_history.log").writeText("[$today] [00:00:00.000] [main] [INFO] [X] seed\n")

        repeat(1_500) { index ->
            DiagnosticLogHistory.append("[$today] [10:00:00.000] [main] [INFO] [X] burst-$index")
        }

        val startedAt = System.nanoTime()
        val exported = DiagnosticLogHistory.snapshotForExport()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertNotNull("积压后导出不得因等待超时返回 null", exported)
        assertTrue("导出等待不应超过 2 秒预算（实际 ${elapsedMs}ms）", elapsedMs < 2_000)
        assertTrue("积压的最后一行必须可见", exported!!.contains("burst-1499"))
    }
}
