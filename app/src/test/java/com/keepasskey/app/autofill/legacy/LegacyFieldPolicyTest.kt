package com.keepasskey.app.autofill.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 旧版无障碍通道字段判定内核的行为测试（ISSUE-P3-324 AC④，纯 JVM）。
 *
 * [LegacyFieldPolicy] 是通道触发（发通知）与回填（选框）的**同一份判据**——
 * 「没有可填充口令框就不打扰用户」与「回填只写选中的框」都由它裁决，故逐形态穷举锁定。
 */
class LegacyFieldPolicyTest {

    private fun probe(
        isPassword: Boolean = false,
        isEditable: Boolean = true,
        isFocused: Boolean = false,
        hasText: Boolean = false
    ): LegacyFieldProbe = LegacyFieldProbe(
        isPassword = isPassword,
        isEditable = isEditable,
        isFocused = isFocused,
        hasText = hasText
    )

    @Test
    fun `无口令框不构成填充场景`() {
        // 普通界面：只有可编辑的普通文本框（搜索框、聊天输入等）
        assertNull(LegacyFieldPolicy.selectFields(listOf(probe(), probe())))
    }

    @Test
    fun `空口令框是唯一锚点`() {
        val pair = LegacyFieldPolicy.selectFields(listOf(probe(isPassword = true)))

        assertNull("纯密码表单无前置可编辑框，用户名应为 null", pair?.usernameIndex)
        assertEquals(0, pair?.passwordIndex)
    }

    @Test
    fun `口令框之前最近的可编辑框作为用户名`() {
        val pair = LegacyFieldPolicy.selectFields(
            listOf(
                probe(hasText = true),        // 非可编辑?仍是可编辑但有内容的普通框，也属候选
                probe(),                      // 最近的前置可编辑框 → 用户名
                probe(isPassword = true)      // 口令框
            )
        )

        assertEquals(1, pair?.usernameIndex)
        assertEquals(2, pair?.passwordIndex)
    }

    @Test
    fun `口令框已有一行内容即放弃`() {
        // 用户已键入或此前已填充过：自动覆写会与其意图冲突，宁可不提示
        assertNull(LegacyFieldPolicy.selectFields(listOf(probe(isPassword = true, hasText = true))))
    }

    @Test
    fun `口令框不可编辑即放弃`() {
        assertNull(
            LegacyFieldPolicy.selectFields(
                listOf(probe(isPassword = true, isEditable = false))
            )
        )
    }

    @Test
    fun `口令框之前只有口令框时用户名为空`() {
        // 密码 + 确认密码形态：第一个口令框命中锚点且前置无可编辑非口令框
        val pair = LegacyFieldPolicy.selectFields(
            listOf(
                probe(isPassword = true),
                probe(isPassword = true)
            )
        )

        assertNull(pair?.usernameIndex)
        assertEquals(0, pair?.passwordIndex)
    }

    @Test
    fun `首个命中锚点的口令框获胜且后续口令框不参与`() {
        val pair = LegacyFieldPolicy.selectFields(
            listOf(
                probe(),                      // 登录页用户名
                probe(isPassword = true),     // 登录口令框（锚点）
                probe(isPassword = true)      // 确认口令框（注册页形态，不参与登录回填）
            )
        )

        assertEquals(0, pair?.usernameIndex)
        assertEquals(1, pair?.passwordIndex)
    }

    @Test
    fun `空清单不构成填充场景`() {
        assertNull(LegacyFieldPolicy.selectFields(emptyList()))
    }
}
