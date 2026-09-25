package com.keepasskey.app.autofill.legacy

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 扫描层的一条字段记录：真实节点引用（供回填时 `ACTION_SET_TEXT`）+ 非敏感投影
 * （供 [LegacyFieldPolicy] 纯判定）。文本内容**不**进入本类型——扫描只读元数据，
 * 不物化任何字段值。
 */
data class LegacyFieldRecord(
    val node: AccessibilityNodeInfo,
    val probe: LegacyFieldProbe
)

/**
 * 无障碍通道的窗口字段扫描（ISSUE-P3-324）。
 *
 * 对 `rootInActiveWindow` 的交互节点做**有界**深度优先遍历，把「口令框 / 可编辑文本框」
 * 收集成记录清单。全部调用必须在**主线程**（AccessibilityNodeInfo 的读取非线程安全）。
 *
 * 有界性：节点预算 [MAX_NODES]（典型登录窗口远小于此；超限即停，宁可漏检也不在
 * 无障碍事件回调里无上限遍历异常巨大的窗口树）。
 */
object LegacyFieldScanner {

    /** 单次扫描的节点预算（防异常巨大的窗口树拖住主线程） */
    internal const val MAX_NODES = 400

    /**
     * 收集窗口内可作为填充目标的字段记录（口令框 + 可编辑文本框，按文档序）。
     *
     * 注意：返回的记录持有真实节点引用，仅应在本窗口仍为活动窗口的短窗口内使用；
     * 跨窗口复用由消费侧的包名复核兜底（见服务的回填路径）。
     */
    fun collect(root: AccessibilityNodeInfo): List<LegacyFieldRecord> {
        val records = mutableListOf<LegacyFieldRecord>()
        val visited = mutableSetOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var budget = MAX_NODES

        while (queue.isNotEmpty() && budget > 0) {
            val node = queue.removeFirst()
            if (!visited.add(node)) continue
            budget--

            if (node.isEditable || node.isPassword) {
                records += LegacyFieldRecord(node = node, probe = probeOf(node))
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return records
    }

    private fun probeOf(node: AccessibilityNodeInfo): LegacyFieldProbe =
        LegacyFieldProbe(
            isPassword = node.isPassword,
            isEditable = node.isEditable,
            isFocused = node.isFocused,
            hasText = node.text?.isNotEmpty() == true
        )
}
