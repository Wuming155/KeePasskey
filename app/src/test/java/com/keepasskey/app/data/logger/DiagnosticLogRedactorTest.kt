package com.keepasskey.app.data.logger

import com.keepasskey.core.log.diagnosticText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DiagnosticLogRedactor] 的异常类型行豁免（ENG-20261111-01）。
 *
 * `diagnosticText` 的顶层类型行是一个裸类名：`com.keepasskey.app...` 里的 `.app` 会被主机名
 * 规则误判、`org.apache.harmony.xml...` 里的 `.xml` 会被文件名规则误判，抹掉 retrace 还原
 * 所需的类名。豁免只针对「整行类名 + 下一行栈帧」这一种形态，消息行仍须照常脱敏。
 */
class DiagnosticLogRedactorTest {

    private class AppSyncException : IllegalStateException("raw-secret")

    @Test
    fun `诊断文本的顶层异常类型行保留完整类名`() {
        val redacted = DiagnosticLogRedactor.redact(diagnosticText("同步失败", AppSyncException()))

        assertTrue(
            "顶层类型行须保留 retrace 所需类名：$redacted",
            redacted.contains("\ncom.keepasskey.app.data.logger.DiagnosticLogRedactorTest\$AppSyncException")
        )
        assertTrue("栈帧类名同样保留", redacted.contains("\tat com.keepasskey.app.data.logger."))
        assertFalse("异常 message 不得进入脱敏文本", redacted.contains("raw-secret"))
        assertEquals("脱敏须幂等", redacted, DiagnosticLogRedactor.redact(redacted))
    }

    @Test
    fun `包名含xml的异常类型行同样保留`() {
        val text = "PROPFIND 响应解析失败\n" +
            "org.apache.harmony.xml.parsers.ParserConfigurationException\n" +
            "\tat com.keepasskey.sync.webdav.WebDavPropfindParser.parse(WebDavPropfindParser.kt:105)"

        val redacted = DiagnosticLogRedactor.redact(text)

        assertTrue(
            "平台异常类型行的 .xml 包名不得被当成文件名：$redacted",
            redacted.contains("org.apache.harmony.xml.parsers.ParserConfigurationException")
        )
        assertFalse(redacted.contains("<redacted-file>"))
    }

    @Test
    fun `类型行豁免不外溢到消息行`() {
        val text = "上传到 nas.example.com 失败\n" +
            "com.keepasskey.app.data.SyncException\n" +
            "\tat com.keepasskey.app.data.Sync.run(Sync.kt:1)"

        val redacted = DiagnosticLogRedactor.redact(text)

        assertFalse("消息行里的主机名仍须脱敏", redacted.contains("nas.example.com"))
        assertTrue(redacted.contains("<redacted-host>"))
        assertTrue(
            "同一文本里的类型行仍完整保留：$redacted",
            redacted.contains("com.keepasskey.app.data.SyncException")
        )
    }

    @Test
    fun `无栈帧的裸类名仍按普通文本脱敏`() {
        val redacted = DiagnosticLogRedactor.redact("com.keepasskey.app.data.SyncException")

        assertFalse("豁免只覆盖「类名 + 紧随栈帧」的形态", redacted.contains("com.keepasskey.app"))
    }

    @Test
    fun `suppressed 类型行保留完整类名`() {
        val text = "敏感缓冲擦除失败\n" +
            "\tSuppressed: com.keepasskey.app.data.logger.CleanupException\n" +
            "\tat com.keepasskey.app.data.logger.History.clear(History.kt:42)"

        val redacted = DiagnosticLogRedactor.redact(text)

        assertTrue(
            "suppressed 类型行须保留 retrace 所需类名：$redacted",
            redacted.contains("\tSuppressed: com.keepasskey.app.data.logger.CleanupException")
        )
        assertTrue(redacted.contains("\tat com.keepasskey.app.data.logger.History.clear"))
    }
}
