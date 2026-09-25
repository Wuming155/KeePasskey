package com.keepasskey.app.autofill.legacy

/**
 * 无障碍通道字段识别的**纯判定内核**（ISSUE-P3-324；判定与探测分离——
 * 真实 `AccessibilityNodeInfo` 树的遍历在 [LegacyFieldScanner]，本文件只对
 * 非敏感投影做选择，零 Android 依赖、JVM 可穷举）。
 *
 * 投影只携带判定所需元数据（是否口令框 / 可编辑 / 聚焦 / 已有内容），**不携带任何文本值**
 * ——字段的实际内容在扫描层被丢弃，不在判定层出现。
 */
data class LegacyFieldProbe(
    val isPassword: Boolean,
    val isEditable: Boolean,
    val isFocused: Boolean,
    val hasText: Boolean
)

/**
 * 选中的一对填充目标（按下标引用 [LegacyFieldScanner.collect] 的记录清单）：
 * [usernameIndex] 可为 null（纯密码表单，如再次输入口令的确认页）。
 */
data class LegacyFieldPair(val usernameIndex: Int?, val passwordIndex: Int)

object LegacyFieldPolicy {

    /**
     * 从字段投影清单中选出口令框及其前置用户名框。
     *
     * 判定口径（保守、可解释）：
     * 1. **口令框是通道触发的唯一锚点**：清单里不存在「可编辑的口令框」即返回 null——
     *    没有口令框的界面（普通搜索框、聊天输入等）不构成填充场景，不发通知；
     * 2. **口令框已有内容即放弃**：用户已键入或此前已填充过，自动覆写会与其意图冲突
     *    （与框架通道「选中才写入」的显式性一致，宁可不提示）；
     * 3. **用户名框取口令框之前最近的可编辑非口令文本框**（登录表单惯例：账号在上、密码在下）；
     *    纯密码表单（口令框之前没有其它可编辑框）时用户名为 null，只填口令框。
     *
     * @return 选中下标对；无口令框 / 口令框已有内容时返回 null（= 本窗口不构成填充场景）
     */
    fun selectFields(probes: List<LegacyFieldProbe>): LegacyFieldPair? {
        val passwordIndex = probes.indexOfFirst { it.isPassword }
        if (passwordIndex < 0) return null
        val password = probes[passwordIndex]
        if (!password.isEditable || password.hasText) return null

        val usernameIndex = probes.take(passwordIndex)
            .indexOfLast { it.isEditable && !it.isPassword }
            .takeIf { it >= 0 }
        return LegacyFieldPair(usernameIndex = usernameIndex, passwordIndex = passwordIndex)
    }
}
