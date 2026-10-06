package com.keepasskey.crypto

import com.keepasskey.core.log.AppLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 原生探活失败 → 非原生兜底的**一次性可观测**登记（ISSUE-P3-392 立规，ISSUE-P2-499 扩至七内核）。
 *
 * 背景：原生内核探活失败（个别机型 so 加载失败 / KAT 不匹配 / 环境性异常）时静默回落非原生实现
 * （KDF / AES-CBC / Twofish / ChaCha20 → BC 或 JCE，口令强度 → JVM 近似实现，Passkey 签名 → BC），
 * 性能或判定质量可能显著偏离（如 ChaCha20 BC 回落 ≈44× 慢、强度兜底丢失模式分析），
 * 但全程零日志、用户与排障方均不可见。
 *
 * 口径（与 `ISSUE-P3-392` 已定裁决一致，**未接受静默降级**）：
 * - 仅记录「已回落」事实本身，**不含** KDF 参数 / 密钥材料 / 主机标识等任何敏感信息；
 *   载荷**只有内核名**一个字段（见 [noteFallbackOnce] 签名，参数面天然无可泄露通道）；
 * - 进程内只记一次（`AtomicBoolean`），避免每次解锁 / 每次加解密刷屏；
 * - crypto 依赖 core，经 [AppLog] 输出。
 *
 * **七内核共用同一闸门**（ISSUE-P2-499 泛化）：Argon2 / AES-KDF（[com.keepasskey.crypto.kdf]）
 * 与 AES-CBC / Twofish / ChaCha20（[com.keepasskey.crypto.cipher]）、口令强度
 * （[com.keepasskey.crypto.strength]）、Passkey 签名（[com.keepasskey.crypto.passkey]）回落时
 * 均调本对象。因闸门为**进程级一次性**，进程内首个回落的内核名即为唯一登记项，其余内核不再重复刷屏。
 *
 * 诊断位（如 `PasswordStrengthEvaluator.nativeAvailable`）与此闸门是两条独立通道：前者可供逐次核对，
 * 后者保证「至少有一条运行时痕迹」；本对象**不**在 `available` 的懒加载体内调用（该 getter 亦被
 * 诊断 / 测试读取，不得把回落事实与「探活被读取」混同）。
 */
internal object NativeFallbackLog {

    private val logged = AtomicBoolean(false)

    /** 供单测断言「只记一次」；生产路径不读 */
    @Volatile
    var lastLoggedKernel: String? = null
        private set

    /** 探活失败已回落非原生实现——进程内只记一次 */
    fun noteFallbackOnce(kernel: String) {
        if (!logged.compareAndSet(false, true)) return
        lastLoggedKernel = kernel
        // 日志不含任何 KDF 参数 / 密钥材料，仅内核名（ISSUE-P3-392 AC① / ISSUE-P2-499 AC①）
        AppLog.i("NativeFallback", "原生 $kernel 探活失败，已回落 JVM 实现")
    }

    /** 单测用：重置一次性闸门 */
    fun resetForTest() {
        logged.set(false)
        lastLoggedKernel = null
    }
}
