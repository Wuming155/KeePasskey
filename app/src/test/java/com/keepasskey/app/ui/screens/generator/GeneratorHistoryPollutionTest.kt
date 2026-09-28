package com.keepasskey.app.ui.screens.generator

import com.keepasskey.app.security.ClipboardSecurityChannel
import com.keepasskey.app.testutil.MainDispatcherGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ISSUE-P3-360 AC②：生成器滑杆不得污染历史。
 *
 * 缺陷形态：`setRandomLength` 每变一次即全量生成并把被替换的旧值推进 history——
 * 拖一次滑杆（6→64）就把 10 条历史塞满中间态，真正的生成动作反而无处可查。
 *
 * 取舍（AC 允许二选一）：**滑杆调节不入历史**（而非拖动结束才生成）——
 * 参数变化仍即时重生成，实时预览体验不回退；history 只由
 * 模式切换 / 开关 / 分隔符 / 重新生成等**离散动作**产生。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GeneratorHistoryPollutionTest {

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
    fun `滑杆调节不入历史，离散生成动作照常入历史`() {
        val vm = GeneratorViewModel(FakeClipboardChannel())
        MainDispatcherGuard.track(vm)

        runBlocking {
            // 初始生成落地
            withTimeout(5000) {
                vm.uiState.first { it.currentPassword.length > 0 }
            }

            // 模拟拖动长度滑杆（逐步推进并逐步等待落地，排除并发代数竞态）
            for (length in 17..30) {
                vm.setRandomLength(length)
                withTimeout(5000) {
                    vm.uiState.first {
                        it.randomLength == length && it.currentPassword.length == length
                    }
                }
            }
            assertEquals(
                "拖动滑杆（14 档）期间不得产生任何历史（整改前一次拖动即塞满 10 条）",
                0,
                vm.uiState.value.history.size
            )

            // 词数滑杆同为滑杆调节，同样不入历史
            val beforeWordCount = vm.uiState.value.currentPassword
            vm.setWordCount(5)
            withTimeout(5000) {
                vm.uiState.first { it.currentPassword !== beforeWordCount }
            }
            assertEquals("词数滑杆同样不得入历史", 0, vm.uiState.value.history.size)

            // 离散动作（重新生成）保持既有入史语义：被替换的当前值进历史
            val beforeRegenerate = vm.uiState.value.currentPassword.readString()
            vm.regenerate()
            withTimeout(5000) {
                vm.uiState.first { it.history.isNotEmpty() }
            }
            val history = vm.uiState.value.history
            assertEquals("重新生成后历史应恰有 1 条", 1, history.size)
            assertEquals(
                "被替换的当前值必须完整进入历史",
                beforeRegenerate,
                history.first().readString()
            )

            // 之后的滑杆调节仍不得把当前值推进历史
            vm.setRandomLength(33)
            withTimeout(5000) {
                vm.uiState.first { it.randomLength == 33 && it.currentPassword.length == 33 }
            }
            assertTrue(
                "滑杆调节不得再增长历史",
                vm.uiState.value.history.size == 1
            )
        }
    }
}
