package com.keepasskey.app.autofill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 明文口令载体的 `toString()` 脱敏单测（ISSUE-P2-549）。
 *
 * 整改前 [ExtractedSaveCredentials] 是**唯一**未覆写 `toString()` 的同形态载体（同仓先例
 * `AutofillPickerViewModel.Credentials` 早在 `ISSUE-P2-68` 就覆写了）⇒ 一旦有人在保存链路
 * 加一行 `AppLog.d(TAG, "$extracted")`，明文口令直接出桶，编译期无任何护栏。
 * 护栏本身也必须有回归测试：否则「覆写了但覆漏字段」同样不可见。
 */
class AutofillSaveCredentialsRedactionTest {

    @Test
    fun `保存凭据的 toString 不物化口令与用户名`() {
        val extracted = ExtractedSaveCredentials(
            username = "alice@example.com",
            password = "P@ssw0rd-Secret!",
            webDomain = "example.com"
        )

        val rendered = extracted.toString()
        assertFalse("明文口令不得出现在 toString 里：$rendered", rendered.contains("P@ssw0rd-Secret!"))
        assertFalse("用户名不得出现在 toString 里：$rendered", rendered.contains("alice@example.com"))
        // 只呈现长度：长度本身是可观测的诊断量，内容绝不物化
        assertTrue("须呈现口令长度便于诊断：$rendered", rendered.contains("len=${extracted.password.length}"))
        assertTrue("须呈现用户名长度便于诊断：$rendered", rendered.contains("len=${extracted.username.length}"))
    }

    @Test
    fun `空口令载体同样不抛异常且不物化`() {
        val extracted = ExtractedSaveCredentials(username = "", password = "", webDomain = null)
        val rendered = extracted.toString()
        assertTrue(rendered.contains("len=0"))
        assertFalse(rendered.contains("ExtractedSaveCredentials(username=, "))
    }
}
