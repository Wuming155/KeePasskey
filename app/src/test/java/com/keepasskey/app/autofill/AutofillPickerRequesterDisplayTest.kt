package com.keepasskey.app.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutofillPickerRequesterDisplayTest {
    @Test
    fun `缺失包名不得伪造请求方身份`() {
        listOf(null, "", "   ").forEach { packageName ->
            assertNull(buildAutofillPickerRequester(packageName, "Name", "AA", "example.com"))
        }
    }

    @Test
    fun `包名去空白且其它空字段按缺失处理`() {
        val requester = buildAutofillPickerRequester("  com.example.app  ", "   ", "", "  ")
        assertEquals("com.example.app", requester?.packageName)
        assertNull(requester?.appLabel)
        assertNull(requester?.certSha256Hex)
        assertNull(requester?.reportedDomain)
    }

    @Test
    fun `完整身份逐项保留`() {
        val requester = buildAutofillPickerRequester("com.example.app", "Example", "A".repeat(64), "example.com")
        assertEquals("com.example.app", requester?.packageName)
        assertEquals("Example", requester?.appLabel)
        assertEquals("A".repeat(64), requester?.certSha256Hex)
        assertEquals("example.com", requester?.reportedDomain)
    }
}
