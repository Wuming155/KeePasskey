package com.keepasskey.app.ui.screens.detail

import org.junit.Assert.assertEquals
import org.junit.Test

class EntryDetailOpenUrlActionTest {
    @Test
    fun `只有网页地址允许呼起浏览器其余内容复制或判为空`() {
        val cases = mapOf(
            "https://example.com" to OpenUrlAction.LAUNCH,
            "http://example.com" to OpenUrlAction.LAUNCH,
            "  HTTPS://Example.com/path  " to OpenUrlAction.LAUNCH,
            "ftp://files.example.com" to OpenUrlAction.COPY_FALLBACK,
            "android://com.example.app" to OpenUrlAction.COPY_FALLBACK,
            "javascript:alert(1)" to OpenUrlAction.COPY_FALLBACK,
            "mailto:user@example.com" to OpenUrlAction.COPY_FALLBACK,
            "example.com/login" to OpenUrlAction.COPY_FALLBACK,
            "" to OpenUrlAction.MISSING,
            "    " to OpenUrlAction.MISSING,
        )
        cases.forEach { (input, expected) ->
            assertEquals(input, expected, resolveOpenUrlAction(input))
        }
    }
}
