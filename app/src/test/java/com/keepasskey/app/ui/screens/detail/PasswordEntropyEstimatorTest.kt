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

    /**
     * 「单调不降」只在**前缀族**（后串以前串为前缀）内成立——原生内核的 Rust 侧用例
     * `longer_is_not_worse_within_family` 即按该口径书写。
     *
     * 本用例原以 `aB1!` / `aB1!aB1!aB1!aB1!` 作样本，**该对并非前缀族**；B3 裁决（2026-10-09）
     * 把降级实现改为原生内核直译后，周期重复块改按「单元熵 + 重复次数」重估（重复块**更弱**），
     * 该对的前者反而更高 —— 故改用真正的前缀族样本。原样本锁定的语义另由下方
     * `周期重复块的熵低于其单个单元` 用例承接（不删只改，测试资产纪律 §147）。
     */
    @Test
    fun `输出恒非负且单调不降（更长口令不低于更短口令）`() {
        val short = PasswordEntropyEstimator.estimateBits("aB1!kL".toCharArray())
        val long = PasswordEntropyEstimator.estimateBits("aB1!kLmQ7#xZ".toCharArray())

        assertTrue(short == null || short >= 0)
        assertTrue(long == null || long >= 0)
        if (short != null && long != null) {
            assertTrue("更长口令的熵不应更低: short=$short long=$long", long >= short)
        }
    }

    /**
     * **周期重复块的熵低于其单个单元**（B3 裁决 2026-10-09 直译后新锁定的语义）。
     *
     * 降级实现改直译前不覆盖模式分析，`aB1!` × 4 会被熵基线按 16 字符抬到**高于**单个 `aB1!`；
     * 直译后它与原生一致地按「单元熵 + 重复次数」重估 ⇒ 重复块**更弱**（原生路径一直如此）。
     * 本用例把该语义固定下来，防止将来有人把它当「单调性退化」误修回去。
     */
    @Test
    fun `周期重复块的熵低于其单个单元`() {
        val unit = PasswordEntropyEstimator.estimateBits("aB1!".toCharArray())
        val repeated = PasswordEntropyEstimator.estimateBits("aB1!aB1!aB1!aB1!".toCharArray())

        assertTrue("两侧均应可估: unit=$unit repeated=$repeated", unit != null && repeated != null)
        assertTrue(
            "周期重复块应弱于其单个单元: unit=$unit repeated=$repeated",
            repeated!! < unit!!
        )
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
