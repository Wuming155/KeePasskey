package com.keepasskey.app.ui.screens.settings.subscreens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `ISSUE-P3-61` 的三态判定直调用例（§191 随 `healthAuditTone` 从页面内联 `Triple` 链
 * 下沉为纯函数而新增）。
 *
 * 这条判定的**代价是安全语义**：扫描前的计数本就是 0，若先判计数，未扫描时页面会亮出
 * 「安全」徽标——正是 ISSUE-P3-61 要消除的「以 0 冒充安全」。故顺序（未扫描优先）
 * 与被判定的两个输入都必须钉住。
 *
 * 只测判定，不测展示映射（图标 / 配色 / 文案资源由 [HealthCountAuditRow] 承担，
 * 属 Compose 呈现面，另由 `UiMd3AlignmentWiringTest` 一类的接线守卫与截图包装覆盖）。
 */
class HealthCheckAuditToneTest {

    @Test
    fun `未扫描且计数为 0 时必须是中性态而非「安全」`() {
        assertEquals(
            "扫描前的计数天然是 0，不得据此判为通过",
            HealthAuditTone.NOT_SCANNED,
            healthAuditTone(hasScanned = false, violationCount = 0)
        )
    }

    @Test
    fun `未扫描优先于计数——计数非零时也保持中性态`() {
        assertEquals(
            "判定顺序不可颠倒：先 hasScanned 再计数",
            HealthAuditTone.NOT_SCANNED,
            healthAuditTone(hasScanned = false, violationCount = 2)
        )
    }

    @Test
    fun `已扫描且无违规才给出通过徽标`() {
        assertEquals(
            HealthAuditTone.PASS,
            healthAuditTone(hasScanned = true, violationCount = 0)
        )
    }

    @Test
    fun `已扫描且存在违规时给出需注意徽标`() {
        assertEquals(
            HealthAuditTone.WARNING,
            healthAuditTone(hasScanned = true, violationCount = 1)
        )
    }
}
