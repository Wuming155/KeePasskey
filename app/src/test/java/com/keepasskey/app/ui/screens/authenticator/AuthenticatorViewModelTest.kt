package com.keepasskey.app.ui.screens.authenticator

import androidx.lifecycle.ViewModel
import com.keepasskey.app.data.repository.FakeVaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AuthenticatorViewModel 真实 TOTP 计算与状态容器单元测试 (Wave 3-E P1-11)
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

    @Test
    fun `仅展示包含真实 TOTP 密钥的条目并计算真实动态码`() = runTest(testDispatcher) {
        val fakeRepo = FakeVaultRepository()
        val viewModel = AuthenticatorViewModel(fakeRepo)
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        // FakeVaultRepository 中 entry 2 配备了 totpSecret: GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ
        assertTrue("应至少包含 1 个带 TOTP 的条目", state.items.isNotEmpty())

        val githubItem = state.items.firstOrNull { it.entryId == "2" }
        assertNotNull("条目 2 (GitHub Enterprise) 应在 TOTP 列表中", githubItem)
        assertEquals(6, githubItem!!.codeRaw.length)
        assertTrue("TOTP 代码必须全为数字", githubItem.codeRaw.all { it.isDigit() })
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

        testScheduler.runCurrent()

        viewModel.onSearchQueryChange("non_existing_keyword_xyz")
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals("搜索无匹配时项应为空", 0, state.items.size)

        job.cancel()
    }
}
