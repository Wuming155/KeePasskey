package com.keepasskey.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ObscuredTouchPolicy 遮挡触摸判定单元测试（ISSUE-P2-09 / ZT-14）。
 * 位值与 android.view.MotionEvent.FLAG_WINDOW_IS_OBSCURED / FLAG_WINDOW_IS_PARTIALLY_OBSCURED 对齐。
 */
class ObscuredTouchPolicyTest {

    @Test
    fun `无遮挡标志时不丢弃触摸`() {
        assertFalse(ObscuredTouchPolicy.isObscured(0))
        assertFalse(ObscuredTouchPolicy.shouldDropTouch(0))
    }

    @Test
    fun `完全遮挡标志时丢弃触摸`() {
        assertTrue(ObscuredTouchPolicy.isObscured(ObscuredTouchPolicy.FLAG_WINDOW_IS_OBSCURED))
        assertTrue(ObscuredTouchPolicy.shouldDropTouch(ObscuredTouchPolicy.FLAG_WINDOW_IS_OBSCURED))
    }

    @Test
    fun `部分遮挡标志时同样丢弃触摸`() {
        assertTrue(
            ObscuredTouchPolicy.shouldDropTouch(ObscuredTouchPolicy.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)
        )
    }

    @Test
    fun `两种遮挡标志叠加仍判定为遮挡`() {
        val combined =
            ObscuredTouchPolicy.FLAG_WINDOW_IS_OBSCURED or ObscuredTouchPolicy.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
        assertTrue(ObscuredTouchPolicy.isObscured(combined))
    }

    @Test
    fun `其它无关位不会被误判为遮挡`() {
        assertFalse(ObscuredTouchPolicy.isObscured(0x00000004))
        assertFalse(ObscuredTouchPolicy.isObscured(0x00000100))
    }

    @Test
    fun `遮挡位与无关位混合时仍判定为遮挡`() {
        assertTrue(
            ObscuredTouchPolicy.isObscured(0x00000004 or ObscuredTouchPolicy.FLAG_WINDOW_IS_OBSCURED)
        )
    }
}
