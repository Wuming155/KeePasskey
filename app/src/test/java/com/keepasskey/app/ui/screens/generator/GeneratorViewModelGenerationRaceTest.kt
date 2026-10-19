package com.keepasskey.app.ui.screens.generator

import androidx.lifecycle.ViewModelStore
import com.keepasskey.app.security.ClipboardSecurityChannel
import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/** ISSUE-P3-648：以可控挂起屏障覆盖生成任务乱序和锁定交错。 */
@OptIn(ExperimentalCoroutinesApi::class)
class GeneratorViewModelGenerationRaceTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
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
    fun `快请求先完成时慢旧结果被丢弃并清零`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = GeneratorViewModel(FakeClipboardChannel())
        MainDispatcherGuard.track(vm)
        val started = Channel<Int>(Channel.UNLIMITED)
        val gates = mapOf(16 to CompletableDeferred<Unit>(), 20 to CompletableDeferred<Unit>(), 21 to CompletableDeferred<Unit>())
        val results = mutableMapOf<Int, ProtectedString>()
        vm.generationDispatcher = StandardTestDispatcher(testScheduler)
        vm.generateResult = { state ->
            started.send(state.randomLength)
            gates.getValue(state.randomLength).await()
            val marker = when (state.randomLength) {
                16 -> "initial"
                20 -> "slow-a"
                else -> "fast-b"
            }
            val secret = ProtectedString(marker.toCharArray(), isProtected = true)
            results[state.randomLength] = secret
            GeneratorGeneratedResult(secret, state.randomLength)
        }

        testScheduler.runCurrent()
        assertEquals(16, started.receive())
        gates.getValue(16).complete(Unit)
        testScheduler.runCurrent()

        vm.setRandomLength(20)
        testScheduler.runCurrent()
        assertEquals(20, started.receive())
        vm.setRandomLength(21)
        testScheduler.runCurrent()
        assertEquals(21, started.receive())

        gates.getValue(21).complete(Unit)
        testScheduler.runCurrent()
        assertEquals("fast-b", vm.uiState.value.currentPassword.readString())
        assertEquals(21, vm.uiState.value.entropyBits)

        gates.getValue(20).complete(Unit)
        testScheduler.runCurrent()
        assertEquals("慢 A 不得覆盖快 B", "fast-b", vm.uiState.value.currentPassword.readString())
        assertEquals("迟到的 A 必须擦除", true, results.getValue(20).cleared)
    }

    @Test
    fun `锁定后迟到的生成结果被丢弃并清零`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val session = DatabaseSession()
        val vm = GeneratorViewModel(FakeClipboardChannel(), session)
        MainDispatcherGuard.track(vm)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        lateinit var lateSecret: ProtectedString
        vm.generationDispatcher = StandardTestDispatcher(testScheduler)
        vm.generateResult = {
            started.complete(Unit)
            release.await()
            lateSecret = ProtectedString("late-secret".toCharArray(), isProtected = true)
            GeneratorGeneratedResult(lateSecret, 42)
        }

        testScheduler.runCurrent()
        assertTrue("初始生成应进入受控屏障", started.isCompleted)
        session.lock()
        testScheduler.runCurrent()
        assertEquals("锁定应立即清空当前结果", "", vm.uiState.value.currentPassword.readString())

        release.complete(Unit)
        testScheduler.runCurrent()

        assertTrue("锁定后迟到的秘密容器必须清零", lateSecret.cleared)
        assertEquals("迟到结果不得重新发布", "", vm.uiState.value.currentPassword.readString())
        assertEquals("迟到结果不得恢复强度读数", 0, vm.uiState.value.entropyBits)
        assertTrue("迟到结果不得产生历史", vm.uiState.value.history.isEmpty())
    }

    @Test
    fun `销毁发生在生成结果交接时未提交容器仍被清零`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val vm = GeneratorViewModel(FakeClipboardChannel())
        store.put("generator", vm)
        MainDispatcherGuard.track(vm)
        lateinit var generatedSecret: ProtectedString
        vm.generateResult = {
            generatedSecret = ProtectedString("handoff-secret".toCharArray(), isProtected = true)
            GeneratorGeneratedResult(generatedSecret, 88)
        }
        vm.generationDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                block.run()
                // 生成块已产生并记录结果，但 withContext 尚未把它交回 Main 调用方。
                store.clear()
            }
        }

        testScheduler.runCurrent()

        assertTrue("取消交接后未提交的秘密容器必须清零", generatedSecret.cleared)
        assertEquals("销毁后不得重新发布结果", "", vm.uiState.value.currentPassword.readString())
    }
}
