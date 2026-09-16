package com.keepasskey.app.autofill

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream

/**
 * ISSUE-P2-73 AC③：设备侧 UI 探测/驱动助手（instrumented 用例专用，**不属于产品代码**）。
 *
 * 自动填充链路横跨三方：客户端进程 → 系统框架/SystemUI 填充 UI → 本应用认证 Activity。
 * instrumented 测试进程只持有本应用的 View，故对另外两方一律经
 * [UiAutomation]（无障碍树 + shell 身份）观察与驱动：
 *
 * - **观察**：枚举所有窗口（需 `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`）并递归导出节点文本/边界，
 *   作为「系统填充 UI 是否真的展示了认证数据集」的直接证据；
 * - **驱动**：优先 `ACTION_CLICK` / `ACTION_SET_TEXT`（无障碍动作），失败回退为注入真实触摸事件；
 * - **shell**：`settings` / `logcat` / `am` 等经 [UiAutomation.executeShellCommand] 以 shell 身份执行
 *   （本仓 AGENTS.md §5 约定调试走 Android CLI；CLI 无「任意 shell 命令」与「设备列表」能力，
 *   设备侧命令由用例内部经 shell 身份执行，CLI/adb 仅用于安装与取证，退路在记录文档中已声明）。
 *
 * 节点对象一律不做 recycle：跨窗口枚举期间回收易导致悬空引用，用例生命周期短，交给 GC。
 */
internal class DeviceAutofillProbe {

    /**
     * UiAutomation 实例（**延迟获取**，绝不在字段初始化期获取）。
     *
     * 2026-09-16 真机实测教训：在测试类字段初始化期调用
     * `InstrumentationRegistry.getInstrumentation().uiAutomation` 会走到
     * `Instrumentation.getUiAutomation()` 的 `connectWithTimeout` 路径，与系统内**已有的一次注册**
     * 冲突（`UiAutomationService ... already registered!`），随后框架在「连接中」状态调用
     * `disconnect()`，直接令 instrumented 进程崩溃（用例连断言都来不及执行）。
     *
     * 系统侧 UiAutomation 是**每用户单槽位**资源：Android CLI 的交互辅助 APK
     * （`com.android.cli.interact.instrumentation`）一旦在跑就会占住该槽位，
     * 此时任何 instrumented 进程都无法再申请——复现与规避见记录文档。
     */
    private val automationRef: UiAutomation? by lazy {
        runCatching { InstrumentationRegistry.getInstrumentation().uiAutomation }
            .onFailure { Log.e(TAG, "UiAutomation 获取失败（槽位被占用？）: ${it.javaClass.name} ${it.message}") }
            .getOrNull()
    }

    /** 用例首步显式校验可驱动性：不可用时给出可诊断的失败原因，而不是静默降级 */
    fun requireAutomation(): UiAutomation = automationRef
        ?: throw IllegalStateException(
            "无法获取 UiAutomation（系统每用户单槽位）——请先释放占用者：" +
                "`adb shell am force-stop com.android.cli.interact.instrumentation`"
        )

    private var prepared = false

    /** 内部统一出口：不可用时抛出可诊断异常（[requireAutomation]） */
    private val automation: UiAutomation get() = requireAutomation()

    /** 申请「可枚举全部交互窗口」能力（系统填充 UI 是独立于客户端的窗口） */
    fun prepare() {
        if (prepared) return
        val info = automation.serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        automation.serviceInfo = info
        prepared = true
    }

    /** 以 shell 身份执行命令并读取全部输出（失败不抛出，返回带标记的文本） */
    fun shell(command: String): String = try {
        automation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "shell 执行失败: ${t.javaClass.simpleName}")
        "「shell 执行失败: ${t.javaClass.simpleName}」"
    }

    /** 当前所有窗口的根节点（无窗口能力时退化为活动窗口） */
    fun roots(): List<AccessibilityNodeInfo> {
        prepare()
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val windows: List<AccessibilityWindowInfo>? = runCatching { automation.windows }.getOrNull()
        windows?.forEach { window -> window.root?.let { roots.add(it) } }
        if (roots.isEmpty()) {
            runCatching { automation.rootInActiveWindow }.getOrNull()?.let { roots.add(it) }
        }
        return roots
    }

    /** 递归收集节点（带上限，避免异常树导致长循环） */
    fun collect(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_DEPTH || out.size >= MAX_NODES) return
            out.add(node)
            for (index in 0 until node.childCount) {
                walk(node.getChild(index), depth + 1)
            }
        }
        roots().forEach { walk(it, 0) }
        return out
    }

    /** 节点文本摘要（text / contentDescription / hint 三路，便于定位 RemoteViews 与 Compose 节点） */
    fun textOf(node: AccessibilityNodeInfo): String = buildString {
        node.text?.let { append(it) }
        if (isBlank()) node.contentDescription?.let { append(it) }
        if (isBlank()) node.hintText?.let { append(it) }
    }.toString()

    /**
     * 在给定超时内轮询查找满足条件的节点。
     *
     * @param label 仅用于失败日志的可读描述
     */
    fun awaitNode(label: String, timeoutMs: Long, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastSnapshot = ""
        while (SystemClock.uptimeMillis() < deadline) {
            val nodes = collect()
            nodes.firstOrNull(predicate)?.let { return it }
            lastSnapshot = describe(nodes)
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        Log.w(TAG, "等待节点超时: $label；最后一次窗口快照:\n$lastSnapshot")
        return null
    }

    /** 文本包含给定子串的节点（跨全部窗口） */
    fun awaitText(needle: String, timeoutMs: Long): AccessibilityNodeInfo? =
        awaitNode("文本包含「$needle」", timeoutMs) { textOf(it).contains(needle) }

    /**
     * 只在**活动窗口**内查找节点。
     *
     * 认证 Activity 与客户端表单可能同时在无障碍树中（客户端窗口仍在栈内），
     * 而对认证页的输入/点击必须落在当前活动窗口，故单独收敛一条按活动窗口检索的通道。
     */
    fun awaitActiveNode(
        label: String,
        timeoutMs: Long,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val root = runCatching { automation.rootInActiveWindow }.getOrNull()
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            fun walk(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > MAX_DEPTH || nodes.size >= MAX_NODES) return
                nodes.add(node)
                for (index in 0 until node.childCount) walk(node.getChild(index), depth + 1)
            }
            walk(root, 0)
            nodes.firstOrNull(predicate)?.let { return it }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        Log.w(TAG, "活动窗口内等待节点超时: $label；快照:\n${snapshot()}")
        return null
    }

    /** 点击节点：先尝试无障碍点击（含可点击祖先），失败则注入触摸事件到节点中心 */
    fun click(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var guard = 0
        while (current != null && guard++ < MAX_ANCESTOR_HOPS) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
        }
        return tapNode(node)
    }

    /** 向节点中心注入真实触摸（对不可点击节点、RemoteViews 亦有效） */
    fun tapNode(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return false
        return tap(rect.centerX(), rect.centerY())
    }

    /** 注入 DOWN/UP 触摸事件（shell 身份的 UiAutomation 具备注入能力） */
    fun tap(x: Int, y: Int): Boolean {
        if (x <= 0 || y <= 0) return false
        val downAt = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
        val up = MotionEvent.obtain(downAt, downAt + TAP_DURATION_MS, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
        val downOk = automation.injectInputEvent(down, true)
        val upOk = automation.injectInputEvent(up, true)
        down.recycle()
        up.recycle()
        return downOk && upOk
    }

    /** 以无障碍动作直接写入文本（Compose 文本框支持 ACTION_SET_TEXT） */
    fun setText(node: AccessibilityNodeInfo, value: String): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /** 完整窗口/节点快照（证据留痕；含窗口层与节点文本、类名、边界、可点击性） */
    fun snapshot(): String {
        val builder = StringBuilder()
        val windows: List<AccessibilityWindowInfo>? = runCatching { automation.windows }.getOrNull()
        builder.append("窗口数=").append(windows?.size ?: 0).append('\n')
        windows?.forEachIndexed { index, window ->
            builder.append("  [窗口 $index] type=").append(window.type)
                .append(" focused=").append(window.isFocused)
                .append(" active=").append(window.isActive)
                .append('\n')
            window.root?.let { builder.append(describe(listOf(it), indent = "    ")) }
        }
        builder.append("活动窗口节点:\n")
        runCatching { automation.rootInActiveWindow }.getOrNull()
            ?.let { builder.append(describe(collect(), indent = "    ")) }
        return builder.toString()
    }

    private fun describe(nodes: List<AccessibilityNodeInfo>, indent: String = ""): String = buildString {
        nodes.forEach { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            append(indent)
            append('[').append(node.className?.toString()?.substringAfterLast('.')).append(']')
            append(" text=\"").append(textOf(node).replace('\n', ' ')).append('"')
            append(" bounds=").append(rect.toShortString())
            append(" clickable=").append(node.isClickable)
            append(" editable=").append(node.isEditable)
            append(" password=").append(node.isPassword)
            append(" resourceId=").append(node.viewIdResourceName)
            append('\n')
        }
    }

    private companion object {
        const val TAG = "AutofillAC3"
        const val POLL_INTERVAL_MS = 300L
        const val MAX_DEPTH = 30
        const val MAX_NODES = 600
        const val MAX_ANCESTOR_HOPS = 6
        const val TAP_DURATION_MS = 50L
    }
}
