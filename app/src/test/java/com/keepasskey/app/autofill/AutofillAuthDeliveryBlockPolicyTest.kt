package com.keepasskey.app.autofill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P2-384：认证回传构造 Dataset 前的字段级屏蔽复检。
 *
 * 背景：字段级屏蔽此前只挂在 onFillRequest 的目标解析期；认证回传（选择器 /
 * 确认页）不复检 ⇒ 屏蔽写入后系统缓存重放 / 再次确认仍可交付被屏蔽字段值
 * （Monica `c854ef2a` 同型实证）。
 *
 * AutofillId 为平台 final 类，纯 JVM 无法构造真实实例——本测试只断言与 id 实例
 * 无关的**判定语义**（放行 / 拒绝 / 整表单封锁）；Activity 接线由
 * [AutofillAuthResultRecheckWiringTest] 源码守卫锁定。
 */
class AutofillAuthDeliveryBlockPolicyTest {

    @Test
    fun `屏蔽后缓存重放不交付被屏蔽字段`() {
        // 用户在选择器里屏蔽了密码框后，系统缓存重放再次走认证回传
        val decision = AutofillFieldBlockPolicy.decide(
            hasUsernameField = true,
            hasPasswordField = true
        ) { role -> role == AutofillFieldRole.PASSWORD }
        assertTrue("未屏蔽的用户名框可交付", decision.allowUsername)
        assertFalse("被屏蔽的密码框不可交付", decision.allowPassword)
        assertFalse("仅屏蔽密码时整表单未被屏蔽", decision.blocksEntireForm)
    }

    @Test
    fun `屏蔽后再次确认不交付被屏蔽字段`() {
        val decision = AutofillFieldBlockPolicy.decide(
            hasUsernameField = true,
            hasPasswordField = true
        ) { role -> role == AutofillFieldRole.USERNAME }
        assertFalse("被屏蔽的用户名框不可交付", decision.allowUsername)
        assertTrue("未屏蔽的密码框仍可交付", decision.allowPassword)
    }

    @Test
    fun `双字段全屏蔽时整表单被封锁`() {
        val result = AutofillAuthDeliveryBlockPolicy.filter(
            usernameId = null,
            passwordId = null,
            hasUsernameField = true,
            hasPasswordField = true
        ) { true }
        assertTrue("两框皆被屏蔽时 blocksEntireForm 必须为 true", result.blocksEntireForm)
    }

    @Test
    fun `未屏蔽时放行但不凭空产出 id`() {
        // 两 id 原为 null：filter 只暴露 has* 语义，不得凭空产出 id；
        // blocksEntireForm 在「可交付 id 全空」时为 true，属契约语义而非屏蔽误判
        val result = AutofillAuthDeliveryBlockPolicy.filter(
            usernameId = null,
            passwordId = null,
            hasUsernameField = true,
            hasPasswordField = true
        ) { false }
        assertNull(result.usernameId)
        assertNull(result.passwordId)
        val decision = AutofillFieldBlockPolicy.decide(
            hasUsernameField = true,
            hasPasswordField = true
        ) { false }
        assertTrue(decision.allowUsername)
        assertTrue(decision.allowPassword)
        assertFalse(decision.blocksEntireForm)
    }

    @Test
    fun `Intent 无目标框时不误判为屏蔽`() {
        val result = AutofillAuthDeliveryBlockPolicy.filter(
            usernameId = null,
            passwordId = null,
            hasUsernameField = false,
            hasPasswordField = false
        ) { false }
        assertTrue("两框皆无目标时 blocksEntireForm 仍为 true（与 Intent 契约一致）", result.blocksEntireForm)
        assertNull(result.usernameId)
        assertNull(result.passwordId)
    }

    @Test
    fun `filter 在 id 为 null 且 has 为 false 时不误放行`() {
        // 纯用户名表单：hasPassword=false、passwordId=null，屏蔽判定不应把密码框「造」出来
        val result = AutofillAuthDeliveryBlockPolicy.filter(
            usernameId = null,
            passwordId = null,
            hasUsernameField = true,
            hasPasswordField = false
        ) { false }
        assertNull("passwordId 原为 null，filter 不得凭空产出 id", result.passwordId)
        assertNull("usernameId 原为 null，filter 不得凭空产出 id", result.usernameId)
        val decision = AutofillFieldBlockPolicy.decide(
            hasUsernameField = true,
            hasPasswordField = false
        ) { false }
        assertTrue("hasUsername=true 且未屏蔽时 Decision.allowUsername 必须为 true", decision.allowUsername)
        assertFalse("hasPassword=false 时 Decision.allowPassword 必须为 false", decision.allowPassword)
    }
}
