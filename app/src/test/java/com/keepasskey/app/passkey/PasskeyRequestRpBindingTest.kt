package com.keepasskey.app.passkey

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasskeyRequestRpBindingTest {
    @Test fun 显式RP必须精确匹配而不是父域子域关系() {
        assertTrue(PasskeyRequestRpBinding.matches("example.com", "example.com"))
        assertTrue(PasskeyRequestRpBinding.matches("EXAMPLE.COM.", "example.com"))
        assertFalse(PasskeyRequestRpBinding.matches("login.example.com", "example.com"))
        assertFalse(PasskeyRequestRpBinding.matches("example.com", "login.example.com"))
        assertFalse(PasskeyRequestRpBinding.matches("other.example", "example.com"))
        assertFalse(PasskeyRequestRpBinding.matches("example.com", ""))
    }

    @Test fun 缺失RP只交给既有调用方门控() {
        assertTrue(PasskeyRequestRpBinding.matches("", "example.com"))
        assertTrue(PasskeyRequestRpBinding.matches("  ", "example.com"))
    }
}
