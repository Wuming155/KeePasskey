package com.keepasskey.app.data.logger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * DebugLogBuffer 脱敏导出单元测试（断点整改：导出真实化）。
 * 兑现设置页 UI 承诺：导出前自动移除账号名与网址字段。
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
    fun `exportText 不做脱敏仅进程内预览使用`() {
        buffer.info("T", "host https://keep.example.com")
        assertTrue(buffer.exportText().contains("https://keep.example.com"))
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
    fun `脱敏导出后缓冲本体不受影响`() {
        buffer.info("T", "https://keep.example.com/path")
        buffer.exportSanitizedText()
        assertTrue("缓冲内原始行应保留（脱敏仅作用于导出副本）", buffer.exportText().contains("https://keep.example.com/path"))
    }
}
