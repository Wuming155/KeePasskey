package com.keepasskey.app.ui.screens.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 详情页真实熵估算器单元测试（ISSUE-P3-46）。
 *
 * 背景：`passwordStrengthBits` 此前无任何写入方（强度条恒不渲染）；
 * 修复后由 [PasswordEntropyEstimator] 在密码按需解密时估算。本测试锁定：
 * 非 null/空边界、输出单调非负、bitsOf 纯换算的收敛行为（含 NaN / 负值）、
 * 以及**估算不改变入参内容**（调用方持有的 CharArray 清零责任仍在调用方）。
 */
class PasswordEntropyEstimatorTest {

    @Test
    fun `null 与空密码返回 null`() {
        assertNull(PasswordEntropyEstimator.estimateBits(null))
        assertNull(PasswordEntropyEstimator.estimateBits(CharArray(0)))
    }

    @Test
    fun `正常密码返回正的熵位数`() {
        val password = "correct-horse-battery-staple-2026!".toCharArray()

        val bits = PasswordEntropyEstimator.estimateBits(password)

        assertTrue("强口令的熵位数应为正: $bits", bits != null && bits > 0)
    }

    @Test
    fun `输出恒非负且单调不降（更长口令不低于更短口令）`() {
        val short = PasswordEntropyEstimator.estimateBits("aB1!".toCharArray())
        val long = PasswordEntropyEstimator.estimateBits("aB1!aB1!aB1!aB1!".toCharArray())

        assertTrue(short == null || short >= 0)
        assertTrue(long == null || long >= 0)
        if (short != null && long != null) {
            assertTrue("更长口令的熵不应更低: short=$short long=$long", long >= short)
        }
    }

    @Test
    fun `估算不修改入参内容`() {
        val password = "keep-me-intact".toCharArray()
        val copy = password.copyOf()

        PasswordEntropyEstimator.estimateBits(password)

        assertTrue("估算器不得修改调用方持有的口令缓冲", password.contentEquals(copy))
    }

    @Test
    fun `bitsOf 把 log10 猜测次数换算为熵位数`() {
        // log10(2^10) ≈ 3.0103 → 10 bits
        assertEquals(10, PasswordEntropyEstimator.bitsOf(kotlin.math.log10(1024.0)))
        // 0 次猜测 → 0 bits
        assertEquals(0, PasswordEntropyEstimator.bitsOf(0.0))
    }

    @Test
    fun `bitsOf 对 NaN 与负值收敛为 0`() {
        assertEquals(0, PasswordEntropyEstimator.bitsOf(Double.NaN))
        assertEquals(0, PasswordEntropyEstimator.bitsOf(-3.5))
    }
}
