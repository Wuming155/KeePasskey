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
import org.junit.Assert.assertEquals
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

    /**
     * `ISSUE-P2-287` AC③：锁库后 UI 不再持有任何明文读数，且复制已擦实例不崩溃。
     *
     * 整改前 `clearGeneratedSecrets()` 只 `clear()` 不发新态——`remember(currentPassword)`
     * 键未变 ⇒ 已物化明文继续渲染；点复制撞 `ProtectedString` 的 fail-fast 抛
     * `IllegalStateException`。整改后擦除与状态失效原子完成（新空实例引用），
     * 复制路径对已擦实例降级为提示。
     */
    @Test
    fun `锁库后 UI 状态失效且复制降级为提示（AC③）`() {
        val session = DatabaseSession()
        val vm = GeneratorViewModel(FakeClipboardChannel(), session)
        MainDispatcherGuard.track(vm)

        val generated = runBlocking {
            withTimeout(5000) {
                vm.uiState.first { it.currentPassword.length > 0 }.currentPassword
            }
        }

        runBlocking { session.lock() }

        val state = vm.uiState.value
        assertTrue(
            "锁库后状态必须交出新的空实例引用（remember 键失效）",
            state.currentPassword !== generated
        )
        assertEquals("锁库后 UI 读数必须为空（不再持有明文）", "", state.currentPassword.readString())
        assertEquals("锁库后强度读数必须归零", 0, state.entropyBits)
        assertTrue("锁库后历史必须清空", state.history.isEmpty())

        // 复制已擦除的旧引用：不得抛异常，如实降级为「请重新生成」提示
        vm.copyGeneratedPassword(generated)
        assertTrue(
            "复制已擦实例必须降级为提示（禁崩溃）",
            vm.uiState.value.userMessage != null
        )
    }
}
