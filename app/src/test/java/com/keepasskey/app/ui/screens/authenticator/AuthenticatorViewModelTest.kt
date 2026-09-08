package com.keepasskey.app.ui.screens.authenticator

import androidx.lifecycle.ViewModel
import com.keepasskey.app.data.repository.FakeVaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AuthenticatorViewModel 真实 TOTP 计算与状态容器单元测试 (Wave 3-E P1-11)
 *
 * TASK-42（P2-30）整改后 uiState 上游经 `flowOn(Dispatchers.Default)` 在真实 Default
 * 线程池计算（与测试虚拟调度器异步），状态断言改为「轮询等待」式：
 * [awaitUiState] 以真实时间短轮询等待 Flow 传播完成，避免虚拟时钟跑不到真实线程的时序脆弱性。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthenticatorViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 真实时间轮询等待 uiState 满足条件（上游 flowOn(Default) 与虚拟调度器异步） */
    private suspend fun awaitUiState(
        viewModel: AuthenticatorViewModel,
        timeoutMs: Long = 5_000L,
        cond: (AuthenticatorUiState) -> Boolean
    ): AuthenticatorUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val state = viewModel.uiState.value
            if (cond(state)) return state
            if (System.currentTimeMillis() > deadline) {
                error("等待 uiState 满足条件超时（${timeoutMs}ms）")
            }
            Thread.sleep(10)
        }
    }

    @Test
    fun `仅展示包含真实 TOTP 密钥的条目并计算真实动态码`() = runTest(testDispatcher) {
        val fakeRepo = FakeVaultRepository()
        val viewModel = AuthenticatorViewModel(fakeRepo)
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        val state = awaitUiState(viewModel) { it.items.isNotEmpty() }
        // FakeVaultRepository 中 entry 2 配备了 totpSecret: GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ
        assertTrue("应至少包含 1 个带 TOTP 的条目", state.items.isNotEmpty())

        val githubItem = state.items.firstOrNull { it.entryId == "2" }
        assertNotNull("条目 2 (GitHub Enterprise) 应在 TOTP 列表中", githubItem)
        // TASK-33 整改：codeRaw 可空（种子缺失=占位符不可复制）；种子完好时必须非空且全数字
        val codeRaw = githubItem!!.codeRaw ?: error("种子完好条目的验证码不得为 null")
        assertEquals(6, codeRaw.length)
        assertTrue("TOTP 代码必须全为数字", codeRaw.all { it.isDigit() })
        // 格式化应为 "xxx xxx"
        assertEquals(7, githubItem.codeFormatted.length)
        assertTrue(githubItem.codeFormatted.contains(' '))

        // 验证不再包含无 TOTP 密钥的普通条目（如条目 1, 3, 5）
        val nonTotpItem = state.items.firstOrNull { it.entryId == "1" || it.entryId == "3" || it.entryId == "5" }
        assertTrue("无 TOTP 密钥条目不应出现在列表中", nonTotpItem == null)

        job.cancel()
    }

    @Test
    fun `搜索过滤正确联动`() = runTest(testDispatcher) {
        val fakeRepo = FakeVaultRepository()
        val viewModel = AuthenticatorViewModel(fakeRepo)
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        awaitUiState(viewModel) { it.items.isNotEmpty() }

        viewModel.onSearchQueryChange("non_existing_keyword_xyz")
        val state = awaitUiState(viewModel) { it.items.isEmpty() }
        assertEquals("搜索无匹配时项应为空", 0, state.items.size)

        job.cancel()
    }
}
