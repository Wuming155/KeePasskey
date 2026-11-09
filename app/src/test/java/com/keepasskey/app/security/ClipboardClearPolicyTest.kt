package com.keepasskey.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * 剪贴板清空裁决的纯 JVM 回归。
 *
 * 1. ENG03：后台读不到剪贴板（`primaryClip == null`）时，因系统抑制后台变更事件且不可读，
 *    无法证明剪贴板仍属本应用，严禁物理清空（避免误伤其它应用写入的内容），保留标记延后回前台核验；
 * 2. ENG-20261028-03：切到后台是否立即清空取决于本次复制有没有定时擦除计划——有则后台不动
 *    （可用粘贴窗口由用户设置决定），无则沿用「敏感值只在前台驻留」的补偿控制。
 */
class ClipboardClearPolicyTest {

    private fun hash(text: String): ByteArray = text.toByteArray(StandardCharsets.UTF_8)

    @Test
    fun `剪贴板不可读时无法证明所有权不得物理清空`() {
        assertFalse(
            "后台不可读且无法接收变更广播 ⇒ 无法证明值仍属本应用，严禁物理清空",
            ClipboardClearPolicy.shouldClearOnUnreadableClipboard()
        )
    }

    @Test
    fun `已调度定时擦除时切后台不得提前清空`() {
        assertFalse(
            "可用粘贴窗口由用户设置的保留时长决定，后台切换不得早于设置清空",
            ClipboardClearPolicy.shouldClearOnBackgroundSwitch(pasteWindowScheduled = true)
        )
    }

    @Test
    fun `未调度定时擦除时切后台即清空`() {
        assertTrue(
            "未配置保留时长（关闭自动擦除 / 选定「不清空」）时沿用「敏感值只在前台驻留」的补偿控制",
            ClipboardClearPolicy.shouldClearOnBackgroundSwitch(pasteWindowScheduled = false)
        )
    }
}
