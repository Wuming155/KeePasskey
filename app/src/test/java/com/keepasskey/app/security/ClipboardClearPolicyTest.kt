package com.keepasskey.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * ISSUE-P2-51 AC② 回归：后台读不到剪贴板（`primaryClip == null`）时的清空裁决。
 *
 * 核心负例：发生覆盖写（如复制普通文本）后，记录摘要与计划摘要仍相等，
 * 但此时不得再清空——否则会误清用户 / 他应用写入的内容（原缺陷）。
 */
class ClipboardClearPolicyTest {

    private fun hash(text: String): ByteArray = text.toByteArray(StandardCharsets.UTF_8)

    @Test
    fun `未覆盖写且摘要匹配时 fail-safe 清空`() {
        val h = hash("Sensitive#1")
        assertTrue(
            ClipboardClearPolicy.shouldClearOnUnreadableClipboard(
                recordedHash = h,
                expectedHash = hash("Sensitive#1"),
                superseded = false
            )
        )
    }

    @Test
    fun `发生覆盖写后即使摘要匹配也不清空`() {
        val h = hash("Sensitive#1")
        assertFalse(
            "已观察覆盖写 ⇒ 陈旧摘要匹配不得误清他处内容",
            ClipboardClearPolicy.shouldClearOnUnreadableClipboard(
                recordedHash = h,
                expectedHash = hash("Sensitive#1"),
                superseded = true
            )
        )
    }

    @Test
    fun `无待清敏感记录时不清空`() {
        assertFalse(
            ClipboardClearPolicy.shouldClearOnUnreadableClipboard(
                recordedHash = null,
                expectedHash = hash("Sensitive#1"),
                superseded = false
            )
        )
    }

    @Test
    fun `摘要不匹配时不清空`() {
        assertFalse(
            ClipboardClearPolicy.shouldClearOnUnreadableClipboard(
                recordedHash = hash("Other"),
                expectedHash = hash("Sensitive#1"),
                superseded = false
            )
        )
    }
}
