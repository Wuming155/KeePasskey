package com.keepasskey.app.security

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.MainActivity
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.ui.screens.recovery.RecoveryViewModel
import com.keepasskey.core.result.KdbxResult
import java.io.File
import java.nio.CharBuffer
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** ISSUE-P2-639：通过真实产品 UI 和屏障验证迟到预览不能跨副本授权恢复。 */
@RunWith(AndroidJUnit4::class)
class RecoveryPreviewInterleavingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation by lazy { instrumentation.uiAutomation }

    @Test
    fun `A预览挂起时UI阻止切换释放后B预览优先且错配恢复不改文件`() = runBlocking {
        val suffix = UUID.randomUUID().toString().take(8)
        val vaultA = "p2-639-a-$suffix"
        val vaultB = "p2-639-b-$suffix"
        val officialA = File(context.cacheDir, "$vaultA.kdbx")
        val officialB = File(context.cacheDir, "$vaultB.kdbx")
        val officialBytesA = byteArrayOf(11, 12, 13)
        val officialBytesB = byteArrayOf(21, 22, 23)
        val payloadBytesA = byteArrayOf(31, 32, 33, 34)
        val payloadBytesB = byteArrayOf(41, 42, 43, 44)
        val store = VaultRecoveryStore(context, DebugLogBuffer())
        val enteredA = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        var scenario: ActivityScenario<MainActivity>? = null
        var viewModel: RecoveryViewModel? = null

        officialA.writeBytes(officialBytesA)
        officialB.writeBytes(officialBytesB)
        assertTrue(store.write(vaultA, "P2-639 A", officialA.absolutePath, payloadBytesA))
        assertTrue(store.write(vaultB, "P2-639 B", officialB.absolutePath, payloadBytesB))
        val payloadAEntry = store.list().single { it.vaultId == vaultA }
        val payloadBEntry = store.list().single { it.vaultId == vaultB }

        try {
            val info = automation.serviceInfo ?: AccessibilityServiceInfo()
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            automation.serviceInfo = info
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity ->
                viewModel = ViewModelProvider(activity).get(RecoveryViewModel::class.java)
                requireNotNull(viewModel).refresh()
            }
            val vm = requireNotNull(viewModel)
            val pendingA = vm.uiState.value.pending.single { it.entry.vaultId == vaultA }
            val pendingB = vm.uiState.value.pending.single { it.entry.vaultId == vaultB }
            vm.previewOperation = { target, _ ->
                if (target.entry.vaultId == vaultA) {
                    enteredA.complete(Unit)
                    releaseA.await()
                }
                KdbxResult.Success(VaultRecoveryCoordinator.Preview(target.entry.vaultName, 1, 0, 1, 0, 0))
            }

            assertTrue("产品 UI 未显示 A 副本", clickTextContaining("P2-639 A", 10_000))
            setPassword("P2-639 synthetic password")
            assertTrue("产品 UI 缺少预览按钮", clickText(context.getString(R.string.recovery_preview_action), 10_000))
            withTimeout(10_000) { enteredA.await() }
            assertTrue(vm.uiState.value.busy)
            assertEquals(RecoveryViewModel.keyOf(pendingA), vm.uiState.value.selectedKey)

            val rowB = waitForNode(5_000) { it.text?.toString()?.contains("P2-639 B") == true }
                ?: error("产品 UI 未显示 B 副本")
            rowB.recycle()
            // 无障碍树可能把 Text 的 enabled 状态与 clickable 修饰符分开暴露；直接尝试点击，
            // 以真实 ViewModel 选择状态作为行为判据。
            clickTextContaining("P2-639 B", 1_000)
            assertEquals("A 预览忙碌期间点击 B 不得改变选择", RecoveryViewModel.keyOf(pendingA), vm.uiState.value.selectedKey)

            // 刷新使 A 的旧请求失效并解锁 UI；测试切至 B，再通过产品 UI 触发 B 预览。
            scenario.onActivity {
                vm.refresh()
                vm.select(RecoveryViewModel.keyOf(pendingB))
            }
            assertEquals(RecoveryViewModel.keyOf(pendingB), vm.uiState.value.selectedKey)
            setPassword("P2-639 synthetic password")
            assertTrue("B 预览按钮不可用", clickText(context.getString(R.string.recovery_preview_action), 5_000))
            waitUntil(10_000) { vm.uiState.value.previewBinding?.target?.vaultId == vaultB }
            assertEquals("P2-639 B", vm.uiState.value.preview?.vaultName)

            releaseA.complete(Unit)
            waitUntil(10_000) {
                vm.uiState.value.previewBinding?.target?.vaultId == vaultB && !vm.uiState.value.busy
            }
            assertEquals(RecoveryViewModel.keyOf(pendingB), vm.uiState.value.selectedKey)

            // 即使程序化传入 A，当前 B 的预览凭证也不得放行；正式库和两份载荷均须保持不变。
            vm.restore(pendingA, forceOverwrite = true)
            assertArrayEquals(officialBytesA, officialA.readBytes())
            assertArrayEquals(officialBytesB, officialB.readBytes())
            assertArrayEquals(payloadBytesA, readPayload(store, payloadAEntry))
            assertArrayEquals(payloadBytesB, readPayload(store, payloadBEntry))
            assertEquals(setOf(vaultA, vaultB), store.list().map { it.vaultId }.toSet())
        } finally {
            releaseA.complete(Unit)
            scenario?.close()
            store.deleteByVaultId(vaultA)
            store.deleteByVaultId(vaultB)
            officialA.delete()
            officialB.delete()
        }
    }

    private fun setPassword(value: String) {
        val node = waitForNode(5_000) { it.isEditable }
            ?: error("恢复产品 UI 未显示密码输入框")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        assertTrue("产品 UI 无法输入合成凭据", node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
        node.recycle()
    }

    private fun clickTextContaining(part: String, timeoutMs: Long): Boolean =
        clickWhen(timeoutMs) { it.text?.toString()?.contains(part, ignoreCase = true) == true }

    private fun clickText(text: String, timeoutMs: Long): Boolean =
        clickWhen(timeoutMs) { it.text?.toString() == text }

    private fun clickWhen(timeoutMs: Long, predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val node = waitForNode(timeoutMs, predicate) ?: return false
        var current: AccessibilityNodeInfo? = node
        repeat(8) {
            val candidate = current ?: return false
            if (candidate.isClickable) {
                val clicked = candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
                return clicked
            }
            current = candidate.parent
        }
        node.recycle()
        return false
    }

    private fun waitForNode(timeoutMs: Long, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            findNode(automation.rootInActiveWindow, predicate)?.let { return it }
            automation.windows.orEmpty().forEach { window ->
                findNode(window.root, predicate)?.let { return it }
            }
            SystemClock.sleep(50)
        }
        return null
    }

    private fun findNode(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) {
            findNode(node.getChild(index), predicate)?.let { return it }
        }
        return null
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (predicate()) return
            SystemClock.sleep(50)
        }
        assertTrue("恢复 UI 状态在 ${timeoutMs}ms 内未达到预期", predicate())
    }

    private suspend fun readPayload(store: VaultRecoveryStore, entry: VaultRecoveryStore.Entry): ByteArray {
        val bytes = store.readPayload(entry)
        assertNotNull("恢复载荷必须仍可读取", bytes)
        return requireNotNull(bytes)
    }
}
