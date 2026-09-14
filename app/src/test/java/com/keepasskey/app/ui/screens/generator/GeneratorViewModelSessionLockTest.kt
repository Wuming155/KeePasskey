package com.keepasskey.app.ui.screens.generator

import com.keepasskey.app.security.ClipboardSecurityChannel
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P2-65 回归：会话锁定 / 关闭时，持有明文的 ViewModel 必须立即擦除驻留。
 *
 * 以 [GeneratorViewModel]（生成结果全为明文 [com.keepasskey.core.security.ProtectedString]）为代表：
 * 原实现仅在 `onCleared` 擦除，锁库后 ViewModel 若仍被导航栈持有则明文继续驻留。
 */
class GeneratorViewModelSessionLockTest {

    private class FakeClipboardChannel : ClipboardSecurityChannel {
        override fun copySensitiveText(label: CharSequence, text: CharSequence, customTimeoutSeconds: Int?) = Unit
        override fun copySensitiveChars(label: CharSequence, chars: CharArray, customTimeoutSeconds: Int?) = Unit
        override fun copyPlainText(label: CharSequence, text: CharSequence) = Unit
    }

    @Test
    fun `会话锁定后生成结果被擦除`() {
        val session = DatabaseSession()
        val vm = GeneratorViewModel(FakeClipboardChannel(), session)

        val generated = vm.uiState.value.currentPassword
        assertTrue("初始应有非空生成结果", generated.readString().isNotEmpty())

        runBlocking { session.lock() }

        assertThrows(
            "锁定后生成结果必须已清零（readString 抛 IllegalStateException）",
            IllegalStateException::class.java
        ) { generated.readString() }
    }
}
