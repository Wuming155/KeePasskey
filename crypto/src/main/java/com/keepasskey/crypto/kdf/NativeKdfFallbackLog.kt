package com.keepasskey.crypto.kdf

import com.keepasskey.core.log.AppLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 原生 KDF 探活失败 → JVM 兜底的**一次性可观测**登记（ISSUE-P3-392）。
 *
 * 背景：`NativeArgon2` / `NativeAesKdf` 探活失败（个别机型 so 加载失败 / KAT 不匹配）时
 * 静默回落 BC/JCE，性能可差 2.2~5.4 倍，但全程零日志、用户与排障方均不可见。
 *
 * 口径：
 * - 仅记录「已回落」事实本身，**不含** KDF 参数 / 主机标识等任何敏感信息；
 * - 进程内只记一次（AtomicBoolean），避免每次解锁刷屏；
 * - crypto 依赖 core，经 [AppLog] 输出。
 */
internal object NativeKdfFallbackLog {

    private val logged = AtomicBoolean(false)

    /** 供单测断言「只记一次」；生产路径不读 */
    @Volatile
    var lastLoggedKernel: String? = null
        private set

    /** 探活失败已回落 JVM 实现——进程内只记一次 */
    fun noteFallbackOnce(kernel: String) {
        if (!logged.compareAndSet(false, true)) return
        lastLoggedKernel = kernel
        // 日志不含任何 KDF 参数 / 密钥材料（ISSUE-P3-392 AC①）
        AppLog.i("NativeKdf", "原生 $kernel 探活失败，已回落 JVM 实现")
    }

    /** 单测用：重置一次性闸门 */
    fun resetForTest() {
        logged.set(false)
        lastLoggedKernel = null
    }
}
