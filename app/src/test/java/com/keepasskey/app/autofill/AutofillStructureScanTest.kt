package com.keepasskey.app.autofill

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 结构树节点当前文本取值的纯判定内核（2026-11-14 release 诊断日志实证缺陷）。
 *
 * 非 TEXT 的 [android.view.autofill.AutofillValue]（TOGGLE / LIST / DATE）不得调用
 * `getTextValue()`，否则整次 `onFillRequest` 失败；这里锁定「哪些值被采用」，
 * 真实平台类型的判型契约由 `AutofillPlatformContractsDeviceTest` 验证。
 */
class AutofillStructureScanTest {

    private data class Case(
        val isTextValue: Boolean,
        val autofillText: CharSequence?,
        val nodeText: CharSequence?,
        val expected: String
    )

    @Test
    fun `取值组合按既有语义逐态锁定`() {
        val cases = listOf(
            // TEXT 值优先于节点文本
            Case(true, "autofill-current", "node-text", "autofill-current"),
            // TEXT 但取不到文本值：沿用原实现的 Elvis 回退
            Case(true, null, "node-text", "node-text"),
            Case(true, null, null, ""),
            // 非 TEXT（调用侧把 TOGGLE / LIST / DATE 统一判为 false）：绝不采用自动填充值
            Case(false, "must-not-be-used", "node-text", "node-text"),
            Case(false, "must-not-be-used", null, ""),
            Case(false, null, "node-text", "node-text"),
            Case(false, null, null, ""),
            // 节点文本为 CharSequence 子类时按内容取值
            Case(true, StringBuilder("autofill-builder"), "node-text", "autofill-builder")
        )
        cases.forEach { case ->
            assertEquals(
                "isTextValue=${case.isTextValue} autofillText=${case.autofillText}",
                case.expected,
                resolveAutofillNodeText(case.isTextValue, case.autofillText, case.nodeText)
            )
        }
    }

    @Test
    fun `非文本值即使携带文本也不得覆盖节点文本`() {
        assertEquals(
            "node-text",
            resolveAutofillNodeText(
                isTextValue = false,
                autofillText = "must-not-be-used",
                nodeText = StringBuilder("node-text")
            )
        )
    }
}
