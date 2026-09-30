package com.keepasskey.app.ui.screens.settings

import com.keepasskey.database.audit.PasswordRiskLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ISSUE-P3-405：健康检查问题明细的风险映射纯函数守卫。
 *
 * 用户主诉是「不知道哪些是弱密码」——映射错误会让审计行挂错条目或列表为空，
 * 故把枚举转换钉成可宿主直测的纯函数。
 */
class HealthIssueRiskUiMappingTest {

    @Test
    fun `引擎弱口令映射为 UI 的 WEAK`() {
        assertEquals(
            HealthIssueRiskUi.WEAK,
            PasswordRiskLevel.WEAK.toHealthIssueRiskUi()
        )
    }

    @Test
    fun `引擎复用映射为 UI 的 REUSED`() {
        assertEquals(
            HealthIssueRiskUi.REUSED,
            PasswordRiskLevel.REUSED.toHealthIssueRiskUi()
        )
    }

    @Test
    fun `引擎过期映射为 UI 的 EXPIRED`() {
        assertEquals(
            HealthIssueRiskUi.EXPIRED,
            PasswordRiskLevel.EXPIRED.toHealthIssueRiskUi()
        )
    }

    @Test
    fun `SAFE 不应作为问题明细出现但映射仍保守落在 WEAK 而非抛错`() {
        assertEquals(
            HealthIssueRiskUi.WEAK,
            PasswordRiskLevel.SAFE.toHealthIssueRiskUi()
        )
    }
}
