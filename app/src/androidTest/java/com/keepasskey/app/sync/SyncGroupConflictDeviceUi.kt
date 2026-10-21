package com.keepasskey.app.sync

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.R
import com.keepasskey.app.ui.SyncConflictDeviceHostActivity
import com.keepasskey.app.ui.screens.conflict.ConflictResolutionViewModel
import com.keepasskey.app.ui.screens.conflict.GroupResolutionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal class SyncGroupConflictDeviceUi {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    fun resolve(coordinator: SyncCoordinator, choices: Map<String, Pair<String, GroupResolutionMode>>) {
        SyncConflictDeviceHostActivity.suppliedCoordinator = coordinator
        val intent = Intent(context, SyncConflictDeviceHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ActivityScenario.launch<SyncConflictDeviceHostActivity>(intent).use { scenario ->
                lateinit var viewModel: ConflictResolutionViewModel
                scenario.onActivity { viewModel = ViewModelProvider(it)[ConflictResolutionViewModel::class.java] }
                assertTrue("生产冲突页必须接到全部三组冲突", waitUntil {
                    viewModel.uiState.value.groups.map { it.id }.toSet() == choices.keys
                })
                choices.forEach { (id, selection) ->
                    selectGroup(selection.first, selection.second)
                    assertTrue("实际 UI 点击必须更新目标组 $id 的选择", waitUntil {
                        viewModel.uiState.value.groups.single { it.id == id }.mode == selection.second
                    })
                }
                assertEquals("逐组选择不得污染其它卡片", choices.mapValues { it.value.second },
                    viewModel.uiState.value.groups.associate { it.id to it.mode })
                val merge = context.getString(R.string.conflict_btn_merge_push)
                clickText(merge)
                assertTrue("真实合并推送必须出现后果确认", waitUntil {
                    findText(context.getString(R.string.conflict_confirm_merge_title)) != null
                })
                clickText(merge)
                assertTrue("UI 上传及正式落盘必须完成，失败不能清空冲突冒充成功", waitUntil {
                    coordinator.groupConflictFlow.value.isEmpty() && !viewModel.uiState.value.isResolving
                })
                assertTrue("条目冲突流必须为空", coordinator.conflictFlow.value.isEmpty())
                assertTrue("Meta 冲突流必须为空", coordinator.metaConflictFlow.value.isEmpty())
            }
        } finally {
            SyncConflictDeviceHostActivity.suppliedCoordinator = null
        }
    }

    private fun selectGroup(name: String, mode: GroupResolutionMode) {
        val label = context.getString(when (mode) {
            GroupResolutionMode.KEEP_LOCAL -> R.string.conflict_group_mode_keep_local
            GroupResolutionMode.KEEP_REMOTE -> R.string.conflict_group_mode_keep_remote
            GroupResolutionMode.DUPLICATE_BOTH -> R.string.conflict_group_mode_duplicate_both
        })
        repeat(MAX_SCROLLS) {
            instrumentation.waitForIdleSync()
            val title = findText(name)
            var ancestor = title?.parent
            while (ancestor != null) {
                val labels = nodes(ancestor).mapNotNull { it.text?.toString() }
                if (context.getString(R.string.conflict_group_mode_keep_local) in labels &&
                    context.getString(R.string.conflict_group_mode_keep_remote) in labels &&
                    context.getString(R.string.conflict_group_mode_duplicate_both) in labels) {
                    val target = nodes(ancestor).firstOrNull { it.text?.toString() == label && visible(it) }
                    if (target != null) {
                        click(target)
                        return
                    }
                    break
                }
                ancestor = ancestor.parent
            }
            scrollForward()
        }
        error("生产冲突页未找到分组 '$name' 内的 '$label'，UI=${text()}")
    }

    private fun scrollForward() {
        val bounds = Rect().also { automation.rootInActiveWindow?.getBoundsInScreen(it) }
        check(bounds.height() > 0) { "生产冲突页无活动窗口" }
        val x = bounds.centerX()
        val start = bounds.top + bounds.height() * SCROLL_START_PERCENT / PERCENT
        val end = bounds.top + bounds.height() * SCROLL_END_PERCENT / PERCENT
        automation.executeShellCommand("input swipe $x $start $x $end $SCROLL_DURATION_MS").use { }
        SystemClock.sleep(POLL_MS)
    }

    private fun clickText(label: String) {
        assertTrue("生产 UI 中找不到 '$label'：${text()}", waitUntil { findText(label) != null })
        click(requireNotNull(findText(label)))
    }

    private fun click(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_ANCESTORS) {
            val candidate = current ?: return@repeat
            if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            current = candidate.parent
        }
        val bounds = Rect().also(node::getBoundsInScreen)
        check(visible(node)) { "生产 UI 点击节点不在屏幕内" }
        automation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").use { }
    }

    private fun findText(label: String): AccessibilityNodeInfo? =
        nodes(automation.rootInActiveWindow).firstOrNull { it.text?.toString() == label && visible(it) }

    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            add(node)
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        visit(root)
    }

    private fun visible(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect().also(node::getBoundsInScreen)
        return node.isVisibleToUser && bounds.width() > 0 && bounds.height() > 0
    }

    private fun text() = nodes(automation.rootInActiveWindow).mapNotNull { it.text }.joinToString(" | ")

    private fun waitUntil(predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + SyncGroupConflictDeviceFixture.SYNC_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (predicate()) return true
            SystemClock.sleep(POLL_MS)
        }
        return predicate()
    }

    private companion object {
        const val MAX_SCROLLS = 80
        const val MAX_ANCESTORS = 8
        const val POLL_MS = 100L
        const val SCROLL_DURATION_MS = 200
        const val PERCENT = 100
        const val SCROLL_START_PERCENT = 65
        const val SCROLL_END_PERCENT = 40
    }
}
