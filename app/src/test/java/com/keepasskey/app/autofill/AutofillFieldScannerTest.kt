package com.keepasskey.app.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [AutofillFieldScanner] 单元测试，覆盖 hint 优先、htmlName 兜底与未识别场景。
 */
class AutofillFieldScannerTest {

    @Test
    fun scan_detectsByAutofillHints() {
        val nodes = listOf(
            ScanNode(
                id = "node_user",
                autofillHints = listOf("username"),
                inputType = 1,
                isFocused = true,
                webDomain = "github.com",
                packageName = "com.github.android"
            ),
            ScanNode(
                id = "node_pass",
                autofillHints = listOf("password"),
                inputType = 129,
                isFocused = false,
                webDomain = "github.com",
                packageName = "com.github.android"
            )
        )

        val result = AutofillFieldScanner.scan(nodes)
        assertEquals("node_user", result.usernameId)
        assertEquals("node_pass", result.passwordId)
        assertEquals("github.com", result.webDomain)
        assertEquals("com.github.android", result.packageName)
    }

    @Test
    fun scan_fallsBackToHtmlNameWhenNoHints() {
        val nodes = listOf(
            ScanNode(
                id = "input_1",
                autofillHints = emptyList(),
                inputType = 1,
                htmlName = "txtUserLogin",
                webDomain = "login.example.com",
                packageName = "org.example.app"
            ),
            ScanNode(
                id = "input_2",
                autofillHints = emptyList(),
                inputType = 1,
                htmlName = "txtUserPassword",
                webDomain = "login.example.com",
                packageName = "org.example.app"
            )
        )

        val result = AutofillFieldScanner.scan(nodes)
        assertEquals("input_1", result.usernameId)
        assertEquals("input_2", result.passwordId)
        assertEquals("login.example.com", result.webDomain)
        assertEquals("org.example.app", result.packageName)
    }

    @Test
    fun scan_returnsNullWhenNoMatch() {
        val nodes = listOf(
            ScanNode(
                id = "input_search",
                autofillHints = listOf("search"),
                inputType = 1,
                htmlName = "searchQuery",
                webDomain = "search.com",
                packageName = "com.search.app"
            ),
            ScanNode(
                id = "input_comment",
                autofillHints = emptyList(),
                inputType = 1,
                htmlName = "userFeedback",
                webDomain = "search.com",
                packageName = "com.search.app"
            )
        )

        val result = AutofillFieldScanner.scan(nodes)
        assertNull(result.passwordId)
        // search 和 userFeedback 不命中标准登录字段
    }
}
