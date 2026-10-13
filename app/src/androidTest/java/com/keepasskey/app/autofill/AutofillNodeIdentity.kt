package com.keepasskey.app.autofill

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** ISSUE-P3-608：设备定位身份契约；文字、控件角色和窗口归属须同时成立。 */
internal object AutofillNodeIdentity {
    const val TEXT_VIEW = "android.widget.TextView"
    const val BUTTON = "android.widget.Button"
    private const val FRAMEWORK_PACKAGE = "android"
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    /** 仅忽略排版空白；禁止 contains 吞掉相似项。 */
    fun normalize(text: CharSequence?): String = text?.toString().orEmpty().filterNot {
        it.isWhitespace() || Character.isSpaceChar(it)
    }

    fun exactText(node: AccessibilityNodeInfo, expected: String): Boolean =
        normalize(node.text ?: node.contentDescription) == normalize(expected)

    fun appButton(node: AccessibilityNodeInfo, packageName: String, expected: String): Boolean {
        if (!appNode(node, packageName) || !node.isClickable) return false
        if (node.className?.toString() == BUTTON) return exactText(node, expected)
        if (node.className?.toString() != "android.view.View") return false
        // Compose 将点击、文字和 Button 角色拆成虚拟子节点；按同一点击子树归属，不依赖滚动坐标。
        val parts = mutableListOf<AccessibilityNodeInfo>()
        fun collect(part: AccessibilityNodeInfo?, depth: Int) {
            if (part == null || depth > 4 || parts.size >= 64) return
            if (appNode(part, packageName)) parts.add(part)
            for (index in 0 until part.childCount) collect(part.getChild(index), depth + 1)
        }
        collect(node, 0)
        val roles = parts.count { it.className?.toString() == BUTTON }
        val titles = parts.count { exactText(it, expected) }
        return roles == 1 && titles == 1
    }

    fun appNode(node: AccessibilityNodeInfo, packageName: String): Boolean =
        node.packageName?.toString() == packageName && node.isVisibleToUser && node.isEnabled

    /** 填充弹窗/对话框必须由框架承载；内联建议只在 IME 窗口内查找。拒绝背后的旧窗口。 */
    fun datasetWindow(type: Int, rootPackage: String?, layer: Int, focusedLayer: Int?): Boolean =
        focusedLayer != null && layer >= focusedLayer &&
            (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                ((type == AccessibilityWindowInfo.TYPE_SYSTEM || type == AccessibilityWindowInfo.TYPE_APPLICATION) &&
                    (rootPackage == FRAMEWORK_PACKAGE || rootPackage == SYSTEM_UI_PACKAGE)))

    /** RemoteViews 标题优先用固定 ID；IME 内联无该 ID 时仍要求 TextView 与精确文字。 */
    fun datasetTitle(
        node: AccessibilityNodeInfo,
        titleResourceName: String,
        expected: String,
        inline: Boolean = false
    ): Boolean {
        if (!node.isVisibleToUser || !node.isEnabled || node.className?.toString() != TEXT_VIEW) return false
        val resourceId = node.viewIdResourceName
        if (!inline && resourceId != null && resourceId != titleResourceName) return false
        return exactText(node, expected)
    }
}
