package com.keepasskey.app.ui.screens.detail

import com.keepasskey.crypto.exception.CryptoException
import com.keepasskey.crypto.strength.PasswordStrengthEvaluator
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

/**
 * 详情页密码强度条的**真实熵**估算器（ISSUE-P3-46）。
 *
 * 背景：`EntryDetailUiState.passwordStrengthBits` 此前**没有任何写入方**（恒为 null），
 * 而 `PasswordStrengthBar` 对 null 直接 `return` —— 即详情页强度条从不渲染，
 * 但源码注释却声称「真实熵由 ViewModel 在用户显式查看密码时估算后下发」。本类补上该写入方。
 *
 * 安全边界（敏感数据铁律）：
 * 1. 入参为调用方持有的 `CharArray`，本类**不克隆、不留存、不清零**它（清零点仍属调用方）；
 * 2. 内部 UTF-8 编码产生的字节缓冲（含 `ByteBuffer` 的 backing array）在 `finally` 中**全部清零**；
 * 3. 全程**不物化 String**，故不产生不可擦除的明文驻留；
 * 4. 评估失败（原生布局非法等）时返回 null —— 宁可不显示强度条，也绝不谎报一个强度。
 */
internal object PasswordEntropyEstimator {

    /** `log2(10)`：把 `log10(猜测次数)` 换算为熵位数的换底系数。 */
    private const val LOG2_OF_10 = 3.321928094887362

    /**
     * 估算 [password] 的熵位数。
     *
     * @return 熵位数（≥ 0）；[password] 为 null / 空，或评估不可用时返回 null（UI 侧隐藏强度条）。
     */
    fun estimateBits(password: CharArray?): Int? {
        if (password == null || password.isEmpty()) return null

        // CharBuffer.wrap 是零拷贝视图；encode 产出的堆缓冲是本方法独占的秘密副本
        val encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password))
        val backing = if (encoded.hasArray()) encoded.array() else null
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        return try {
            bitsOf(PasswordStrengthEvaluator.evaluate(bytes).guessesLog10)
        } catch (e: CryptoException) {
            // 原生内核返回非法布局等异常：如实降级为「无强度信息」
            null
        } finally {
            bytes.fill(0)
            backing?.fill(0)
        }
    }

    /**
     * 把 `log10(估计猜测次数)` 换算为熵位数（可单测的纯换算，负值与 NaN 一并收敛为 0）。
     */
    fun bitsOf(guessesLog10: Double): Int {
        if (guessesLog10.isNaN()) return 0
        val bits = guessesLog10 * LOG2_OF_10
        if (bits <= 0.0) return 0
        return bits.toInt()
    }
}
