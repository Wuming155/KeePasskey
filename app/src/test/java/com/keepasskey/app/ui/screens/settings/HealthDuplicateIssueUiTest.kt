package com.keepasskey.app.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-409：重复条目明细风险映射与条目投影守卫。
 * 只测 UI 侧风险分类与列表语义，不重测 DuplicateEntryScanner（由 AutofillPolicyBatchTest 覆盖）。
 */
class HealthDuplicateIssueUiTest {

    @Test
    fun `重复条目明细携带 entryId 与 DUPLICATE 风险`() {
        val issue = HealthIssueUi(
            entryId = "dup-entry-1",
            title = "示例账号",
            username = "alice@example.com",
            risk = HealthIssueRiskUi.DUPLICATE,
            description = "与另外 1 个条目重复（同 URL + 同账号）"
        )

        assertEquals("dup-entry-1", issue.entryId)
        assertEquals(HealthIssueRiskUi.DUPLICATE, issue.risk)
        assertTrue(issue.description.contains("重复"))
    }
}
