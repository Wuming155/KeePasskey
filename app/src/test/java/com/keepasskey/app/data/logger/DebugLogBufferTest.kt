package com.keepasskey.app.data.logger

import com.keepasskey.core.log.diagnosticText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [DebugLogBuffer] 写入即脱敏的行为用例。
 *
 * 稳定版也会把运行事件写入可导出的缓冲，因此脱敏不再只是「导出前的清理」：缓冲快照、
 * 设置页预览、logcat 直出与导出文件必须同源，全部只含脱敏文本。导出边界的二次脱敏
 * 是幂等的第二道防线。
 */
class DebugLogBufferTest {

    private lateinit var buffer: DebugLogBuffer

    @Before
    fun setUp() {
        buffer = DebugLogBuffer()
    }

    @Test
    fun `导出前移除网址字段`() {
        buffer.info("SyncCoordinator", "同步完成: https://dav.example.com/remote.php/file.kdbx 已上传")
        val out = buffer.exportSanitizedText()
        assertFalse("URL 主机不应残留在导出内容中", out.contains("dav.example.com"))
        assertTrue("URL 应被替换为脱敏占位符", out.contains("<redacted-url>"))
    }

    @Test
    fun `导出前移除账号字段`() {
        buffer.warn("SyncCoordinator", "探测同步配置失败: user.name@example.com 未配置")
        val out = buffer.exportSanitizedText()
        assertFalse("邮箱账号不应残留在导出内容中", out.contains("user.name@example.com"))
        assertTrue("账号应被替换为脱敏占位符", out.contains("<redacted-account>"))
    }

    @Test
    fun `URL 内嵌账号一并整体移除`() {
        buffer.info("SyncCoordinator", "上传 https://user@nas.example.com/path/file.kdbx 成功")
        val out = buffer.exportSanitizedText()
        assertFalse("URL 整体移除后不应残留账号", out.contains("user@"))
        assertFalse(out.contains("nas.example.com"))
        assertTrue(out.contains("<redacted-url>"))
    }

    @Test
    fun `非敏感日志行导出时原样保留`() {
        buffer.info("Unlock", "主密码解锁成功")
        val out = buffer.exportSanitizedText()
        assertTrue(out.contains("[INFO] [Unlock] 主密码解锁成功"))
    }

    @Test
    fun `写入时即脱敏，快照与预览均不含网址与账号`() {
        buffer.info(
            "SyncCoordinator",
            "同步完成: https://dav.example.com/remote.php/file.kdbx，账号 user.name@example.com"
        )
        val line = buffer.snapshot().single()
        assertFalse("缓冲内不得残留 URL 主机", line.contains("dav.example.com"))
        assertFalse("缓冲内不得残留邮箱", line.contains("user.name@example.com"))
        assertTrue(line.contains("<redacted-url>"))
        assertTrue(line.contains("<redacted-account>"))
        assertFalse("进程内预览同源", buffer.exportText().contains("dav.example.com"))
    }

    @Test
    fun `进程内预览文本同样是脱敏文本`() {
        buffer.info("T", "host https://keep.example.com")
        assertFalse(buffer.exportText().contains("keep.example.com"))
        assertTrue(buffer.exportText().contains("<redacted-url>"))
    }

    @Test
    fun `审计通道同样写入即脱敏`() {
        buffer.audit("ExportAudit", "导出审计: 目标=https://dav.example.com/vault.kdbx")
        assertFalse(buffer.exportText().contains("dav.example.com"))
        assertTrue(buffer.exportText().contains("<redacted-url>"))
    }

    @Test
    fun `结构化标识在写入时统一移除`() {
        val cases = listOf(
            RedactionCase(
                "SAF 目标 Uri",
                "读取失败: content://com.android.providers.downloads.documents/document/我的工作库.kdbx",
                listOf("com.android.providers", "我的工作库.kdbx"),
                "<redacted-url>"
            ),
            RedactionCase("裸主机名", "探测 nas.example.com 超时", listOf("nas.example.com"), "<redacted-host>"),
            RedactionCase("IPv4", "连接 192.168.1.10 失败", listOf("192.168.1.10"), "<redacted-host>"),
            RedactionCase("库文件名", "打开 私人库.kdbx 失败", listOf("私人库.kdbx"), "<redacted-file>"),
            RedactionCase("密钥文件名", "密钥文件 vault.keyx 不可读", listOf("vault.keyx"), "<redacted-file>"),
            RedactionCase("密值赋值", "请求失败 token=abc123def", listOf("abc123def"), "<redacted-secret>")
        )
        cases.forEach { case ->
            val caseBuffer = DebugLogBuffer()
            caseBuffer.info("T", case.message)
            val line = caseBuffer.snapshot().single()
            case.leaked.forEach { leaked ->
                assertFalse("${case.name} 不得残留 $leaked：$line", line.contains(leaked))
            }
            assertTrue("${case.name} 应带脱敏占位符：$line", line.contains(case.marker))
        }
    }

    @Test
    fun `异常类型等定位信息不被脱敏规则误伤`() {
        buffer.error(
            "Unlock",
            "解锁失败: java.lang.IllegalStateException, KdbxInvalidCredentialsException, invalidCreds=true"
        )
        val line = buffer.snapshot().single()
        assertTrue(line.contains("java.lang.IllegalStateException"))
        assertTrue(line.contains("KdbxInvalidCredentialsException"))
        assertTrue(line.contains("invalidCreds=true"))
    }

    @Test
    fun `多行导出按换行拼接且顺序一致`() {
        buffer.info("A", "第一行普通内容")
        buffer.warn("B", "第二行含 https://x.example.com/path")
        val out = buffer.exportSanitizedText()
        val lines = out.split("\n")
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("第一行普通内容"))
        assertTrue(lines[1].contains("<redacted-url>"))
    }

    @Test
    fun `空缓冲导出为空串`() {
        assertEquals("", buffer.exportSanitizedText())
    }

    @Test
    fun `导出二次脱敏幂等且不改写缓冲本体`() {
        buffer.info("T", "https://keep.example.com/path")
        val before = buffer.exportText()
        val exported = buffer.exportSanitizedText()
        assertEquals("导出不得改写缓冲", before, buffer.exportText())
        assertEquals(before, exported)
        assertEquals(exported, buffer.exportSanitizedText())
        assertTrue(exported.contains("<redacted-url>"))
    }

    @Test
    fun `日志行带日期时刻与线程名且保持级别与 tag 连续`() {
        buffer.info("Unlock", "主密码解锁成功")

        val line = buffer.snapshot().single()
        assertTrue("须带日期以便跨天排查：$line", Regex("""\[\d{4}-\d{2}-\d{2}]""").containsMatchIn(line))
        assertTrue("须带时刻与毫秒：$line", Regex("""\[\d{2}:\d{2}:\d{2}\.\d{3}]""").containsMatchIn(line))
        assertTrue("须带线程名：$line", line.contains("[${Thread.currentThread().name.take(48)}]"))
        assertTrue(line.contains("[INFO] [Unlock] 主密码解锁成功"))
    }

    @Test
    fun `环形缓冲溢出时给出丢弃提示且状态可查`() {
        repeat(DebugLogBuffer.MAX_LINES + 3) { buffer.info("T", "line-$it") }

        val status = buffer.status()
        assertEquals(DebugLogBuffer.MAX_LINES, status.retained)
        assertEquals(DebugLogBuffer.MAX_LINES, status.capacity)
        assertEquals(3, status.dropped)
        assertNotNull(status.firstTimestamp)
        assertNotNull(status.lastTimestamp)
        assertTrue(
            "快照首行须提示截断：${buffer.snapshot().first()}",
            buffer.snapshot().first().contains("已丢弃较早的 3 行")
        )
        assertTrue("导出正文同样含截断提示", buffer.exportText().contains("已丢弃较早的 3 行"))
        assertFalse("最早的记录确已被淘汰", buffer.snapshot().any { it.endsWith("line-0") })
    }

    @Test
    fun `清空后状态与溢出提示一并复位`() {
        repeat(DebugLogBuffer.MAX_LINES + 1) { buffer.info("T", "line-$it") }

        buffer.clear()

        assertEquals(0, buffer.status().retained)
        assertEquals(0, buffer.status().dropped)
        assertTrue(buffer.snapshot().isEmpty())
        assertEquals("", buffer.exportText())
    }

    @Test
    fun `诊断栈帧写入后仍是一条事件且包名不被主机名规则误伤`() {
        val stack = diagnosticText("解锁失败", IllegalStateException("raw-secret"), frameBudget = 4)

        buffer.error("Unlock", stack)

        val line = buffer.snapshot().single()
        assertTrue(line.contains("java.lang.IllegalStateException"))
        assertTrue(line.contains("\tat "))
        assertTrue("栈帧类名须完整保留以供 retrace：$line", line.contains("com.keepasskey."))
        assertFalse("异常 message 不得进入缓冲", line.contains("raw-secret"))
    }

    @Test
    fun `未捕获异常写入脱敏栈且不带异常 message`() {
        val crash = IllegalStateException("raw-secret password=leak-me")

        buffer.recordCrash("main", crash)

        val line = buffer.snapshot().single()
        assertTrue("须带可检索的退出文案：$line", line.contains("[ERROR] [Crash]"))
        assertTrue(line.contains(DebugLogBuffer.CRASH_MESSAGE))
        assertTrue("须带异常类型供 retrace", line.contains("java.lang.IllegalStateException"))
        assertTrue("须带栈帧锚点", line.contains("\tat "))
        assertFalse("异常 message 不得进入缓冲", line.contains("raw-secret"))
        assertFalse(line.contains("leak-me"))
    }

    @Test
    fun `关闭诊断日志时不记录未捕获异常`() {
        val off = DebugLogBuffer(DiagnosticLogGate.alwaysOff())

        off.recordCrash("main", IllegalStateException("boom"))

        assertTrue("用户关闭诊断日志即不记录", off.snapshot().isEmpty())
    }

    private data class RedactionCase(
        val name: String,
        val message: String,
        val leaked: List<String>,
        val marker: String
    )
}
