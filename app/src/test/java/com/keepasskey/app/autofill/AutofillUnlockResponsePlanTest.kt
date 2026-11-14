package com.keepasskey.app.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AutofillUnlockResponsePlan] 真值表穷举：锁定态解锁后不再按候选数分路，
 * 唯一候选与多候选同口径，且恒保留「搜索全部条目」入口（开关开启时）。
 *
 * 旧 [AutofillUnlockRouter] 的「唯一强匹配 ⇒ 直链确认页」分支随该捷径一并退役；
 * 签名摘要快照、P2-46 门与字段绑定的既有覆盖留在 `AutofillCandidateRankerTest`。
 */
class AutofillUnlockResponsePlanTest {

    @Test
    fun `唯一候选仍回传候选数据集与搜索全部条目入口_不直达确认页`() {
        val unique = AutofillUnlockResponsePlan.of(
            candidateCount = 1,
            manualPickerEnabled = true,
            hasTargetField = true
        )
        val multiple = AutofillUnlockResponsePlan.of(
            candidateCount = 2,
            manualPickerEnabled = true,
            hasTargetField = true
        )

        assertEquals(1, unique.candidateCount)
        assertTrue(unique.canDeliver)
        assertTrue(unique.includeManualPicker)
        // 唯一候选与多候选只在数量上不同：不存在「唯一候选走另一条链路」的判定。
        assertEquals(unique.includeManualPicker, multiple.includeManualPicker)
        assertEquals(unique.canDeliver, multiple.canDeliver)
    }

    @Test
    fun `多候选全部回传不截断`() {
        val plan = AutofillUnlockResponsePlan.of(
            candidateCount = 8,
            manualPickerEnabled = true,
            hasTargetField = true
        )
        assertEquals(8, plan.candidateCount)
        assertTrue(plan.includeManualPicker)
        assertTrue(plan.canDeliver)
    }

    @Test
    fun `零候选仍保留搜索全部条目入口`() {
        val plan = AutofillUnlockResponsePlan.of(
            candidateCount = 0,
            manualPickerEnabled = true,
            hasTargetField = true
        )
        assertEquals(0, plan.candidateCount)
        assertTrue(plan.includeManualPicker)
        assertTrue(plan.canDeliver)
    }

    @Test
    fun `兜底入口关闭且零候选时取消回传`() {
        val plan = AutofillUnlockResponsePlan.of(
            candidateCount = 0,
            manualPickerEnabled = false,
            hasTargetField = true
        )
        assertFalse(plan.includeManualPicker)
        assertFalse(plan.canDeliver)
    }

    @Test
    fun `兜底入口关闭但仍有候选时只回传候选`() {
        val plan = AutofillUnlockResponsePlan.of(
            candidateCount = 1,
            manualPickerEnabled = false,
            hasTargetField = true
        )
        assertEquals(1, plan.candidateCount)
        assertFalse(plan.includeManualPicker)
        assertTrue(plan.canDeliver)
    }

    @Test
    fun `无目标框一律取消与候选数及开关无关`() {
        for (count in listOf(0, 1, 3)) {
            for (pickerEnabled in listOf(true, false)) {
                val plan = AutofillUnlockResponsePlan.of(count, pickerEnabled, hasTargetField = false)
                assertFalse("候选数=$count 开关=$pickerEnabled", plan.canDeliver)
                assertFalse("候选数=$count 开关=$pickerEnabled", plan.includeManualPicker)
                assertEquals(0, plan.candidateCount)
            }
        }
    }
}
