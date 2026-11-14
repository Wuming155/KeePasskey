package com.keepasskey.app.ui

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设备侧验收：**撤销回调抛错不得杀进程，且必须明说「内容未恢复」**。
 *
 * 挂载生产 `AppGlobalSnackbarHost`（宿主 `SnackbarUndoFailureDeviceHostActivity`），撤销回调被
 * 替换为必抛错的注入点。整改前该异常从组合期协程逃逸 ⇒ 进程被杀，用户看到「点撤销即闪退」；
 * 整改后：留痕 + 就地提示，页面仍可用。
 *
 * 竞态纪律：全部等待都是「轮询直到业务事实成立」，不用固定 sleep 断言结果。
 */
@RunWith(AndroidJUnit4::class)
class SnackbarUndoFailureDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation by lazy { instrumentation.uiAutomation }

    @Test
    fun testUndoFailureKeepsProcessAliveAndTellsUser() {
        enableAccessibilityRetrieval()
        ActivityScenario.launch(SnackbarUndoFailureDeviceHostActivity::class.java).use { scenario ->
            assertTrue(
                "可撤销消息必须出现在生产 Snackbar 宿主上（页面=${accessibilityText()}）",
                waitForText(text(R.string.btn_undo))
            )

            clickText(text(R.string.btn_undo))

            assertTrue(
                "撤销失败必须明说「内容未恢复」，不得静默（页面=${accessibilityText()}）",
                waitForText(text(R.string.undo_failed))
            )
            assertTrue(
                "宿主页面必须仍在（异常若逃逸，进程已被系统杀掉）: ${accessibilityText()}",
                waitForText(SnackbarUndoFailureDeviceHostActivity.HOST_MARKER)
            )
            scenario.onActivity { activity ->
                assertTrue("Activity 不得因撤销失败而销毁", !activity.isFinishing && !activity.isDestroyed)
            }
        }
    }

    // --------------------------------------------------- 无障碍驱动辅助

    private fun text(resId: Int): String = context.getString(resId)

    private fun enableAccessibilityRetrieval() {
        val info = automation.serviceInfo ?: android.accessibilityservice.AccessibilityServiceInfo()
        info.flags = info.flags or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        automation.serviceInfo = info
    }

    private fun clickText(label: String) {
        val node = waitForNode { it.text?.toString() == label }
        assertNotNull("找不到可点击文案：$label（页面=${accessibilityText()}）", node)
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_CLICK_ANCESTORS) {
            val candidate = current ?: return@repeat
            if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            current = candidate.parent
        }
        error("$label 不可点击（页面=${accessibilityText()}）")
    }

    private fun findNodes(predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (predicate(node)) nodes += node
            for (index in 0 until node.childCount) collect(node.getChild(index))
        }
        collect(automation.rootInActiveWindow)
        automation.windows.orEmpty().forEach { collect(it.root) }
        return nodes.distinctBy { node ->
            val bounds = Rect().also(node::getBoundsInScreen)
            "${node.text}|$bounds"
        }
    }

    private fun waitForNode(
        timeoutMs: Long = UI_TIMEOUT_MS,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            findNodes(predicate).firstOrNull()?.let { return it }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return null
    }

    private fun waitForText(label: String, timeoutMs: Long = UI_TIMEOUT_MS): Boolean =
        waitForNode(timeoutMs) { it.text?.toString() == label } != null

    private fun accessibilityText(): String = buildString {
        fun appendNode(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_TREE_DEPTH) return
            val label = node.text?.toString().orEmpty()
            if (label.isNotBlank()) append("「").append(label).append("」")
            for (index in 0 until node.childCount) appendNode(node.getChild(index), depth + 1)
        }
        appendNode(automation.rootInActiveWindow, 0)
    }

    private companion object {
        const val UI_TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 50L
        const val MAX_CLICK_ANCESTORS = 8
        const val MAX_TREE_DEPTH = 40
    }
}
