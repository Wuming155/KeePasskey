package com.keepasskey.app.autofill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字段级放行判定单元测试（ISSUE-P3-43 ② 验收标准 3：三级判定组合）。
 *
 * 覆盖：按角色屏蔽、双框全屏蔽、未识别框恒不可填、以及屏蔽查询 **fail-closed 返回 true**
 * 时的保守结果（结构性保证「查询实现不可靠时只会少填、不会多填」）。
 */
class AutofillFieldBlockPolicyTest {

    @Test
    fun `未屏蔽时两个已识别框均可填充`() {
        val decision = AutofillFieldBlockPolicy.decide(
            hasUsernameField = true,
            hasPasswordField = true,
            isBlocked = { false }
        )

        assertTrue(decision.allowUsername)
        assertTrue(decision.allowPassword)
        assertFalse(decision.blocksEntireForm)
    }

    @Test
    fun `仅屏蔽账号框时密码框仍可填充`() {
        val decision = AutofillFieldBlockPolicy.decide(
            hasUsernameField = true,
            hasPasswordField = true,
            isBlocked = { it == AutofillFieldRole.USERNAME }
        )

        assertFalse(decision.allowUsername)
        assertTrue(decision.allowPassword)
        assertFalse(decision.blocksEntireForm)
    }

    @Test
    fun `仅屏蔽密码框时账号框仍可填充`() {
        val decision = AutofillFieldBlockPolicy.decide(
            hasUsernameField = true,
            hasPasswordField = true,
            isBlocked = { it == AutofillFieldRole.PASSWORD }
        )

        assertTrue(decision.allowUsername)
        assertFalse(decision.allowPassword)
        assertFalse(decision.blocksEntireForm)
    }

    @Test
    fun `两个框都被屏蔽时整个表单不可下发`() {
        val decision = AutofillFieldBlockPolicy.decide(
            hasUsernameField = true,
            hasPasswordField = true,
            isBlocked = { true }
        )

        assertFalse(decision.allowUsername)
        assertFalse(decision.allowPassword)
        assertTrue(decision.blocksEntireForm)
    }

    @Test
    fun `未识别的框恒不可填充（即使未屏蔽）`() {
        val decision = AutofillFieldBlockPolicy.decide(
            hasUsernameField = false,
            hasPasswordField = true,
            isBlocked = { false }
        )

        assertFalse(decision.allowUsername)
        assertTrue(decision.allowPassword)

        val passwordOnlyForm = AutofillFieldBlockPolicy.decide(
            hasUsernameField = true,
            hasPasswordField = false,
            isBlocked = { false }
        )
        assertTrue(passwordOnlyForm.allowUsername)
        assertFalse(passwordOnlyForm.allowPassword)
    }

    @Test
    fun `屏蔽查询 fail-closed 返回 true 时只会收紧不会放行`() {
        // 模拟查询实现不可靠（如签名无法计算）时按 true 处理：
        // 结果必须与「真被屏蔽」一致——保守方向，绝不出现「不可判定 → 放行填充」
        val decision = AutofillFieldBlockPolicy.decide(
            hasUsernameField = true,
            hasPasswordField = true,
            isBlocked = { true }
        )

        assertTrue(decision.blocksEntireForm)
    }
}
