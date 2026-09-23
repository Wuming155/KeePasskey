package com.keepasskey.app.ui.screens.generator

import com.keepasskey.app.security.ClipboardSecurityChannel
import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ISSUE-P2-65 回归：会话锁定 / 关闭时，持有明文的 ViewModel 必须立即擦除驻留。
 *
 * 以 [GeneratorViewModel]（生成结果全为明文 [com.keepasskey.core.security.ProtectedString]）为代表：
 * 原实现仅在 `onCleared` 擦除，锁库后 ViewModel 若仍被导航栈持有则明文继续驻留。
 *
 * `ISSUE-P2-286` 起生成与强度评估异步化（`Dispatchers.Default`），用例以
 * `withTimeout + first { 非空 }` 等待首轮生成落地，语义断言不变。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GeneratorViewModelSessionLockTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        MainDispatcherGuard.tearDown()
    }

    private class FakeClipboardChannel : ClipboardSecurityChannel {
        override fun copySensitiveText(label: CharSequence, text: CharSequence, customTimeoutSeconds: Int?) = Unit
        override fun copySensitiveChars(label: CharSequence, chars: CharArray, customTimeoutSeconds: Int?) = Unit
        override fun copyPlainText(label: CharSequence, text: CharSequence) = Unit
    }

    @Test
    fun `会话锁定后生成结果被擦除`() {
        val session = DatabaseSession()
        val vm = GeneratorViewModel(FakeClipboardChannel(), session)
        MainDispatcherGuard.track(vm)

        val generated = runBlocking {
            withTimeout(5000) {
                vm.uiState.first { it.currentPassword.length > 0 }.currentPassword
            }
        }
        assertTrue("初始应有非空生成结果", generated.readString().isNotEmpty())

        runBlocking { session.lock() }

        assertThrows(
            "锁定后生成结果必须已清零（readString 抛 IllegalStateException）",
            IllegalStateException::class.java
        ) { generated.readString() }
    }
}
