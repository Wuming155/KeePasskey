package com.keepasskey.crypto

import com.keepasskey.core.log.AppLog
import java.util.concurrent.ConcurrentHashMap

/**
 * 原生探活失败 → 非原生兜底的**按内核一次性**登记（ISSUE-P3-392 / ISSUE-P2-499 / `ISSUE-P2` C4）。
 *
 * 背景：原生内核探活失败时静默回落非原生实现，性能或判定质量可能显著偏离，但全程零日志。
 * 旧实现用**单个** `AtomicBoolean` 做进程级一次性闸门，先回落的内核会遮蔽后续内核的失败记录；
 * 现按内核名去重：每个内核首次回落各输出一行，之后同内核不再刷屏。
 *
 * 原因分型（`reason=`）只区分两类，均为可判定的静态事实：
 * - `LIBRARY_UNAVAILABLE`：原生库未加载（ABI/符号缺失、`.so` 损坏）；
 * - `KAT_MISMATCH_OR_ERROR`：库已加载但自测向量不匹配或调用异常。
 *
 * 库已加载时额外带 `code=`（`ISSUE-P2` C4）：原生边界在返回失败值前把原因写进线程局部变量
 * （`crypto/src/main/rust/src/error_code.rs`），取值 `NONE|INVALID_ARGUMENT|JNI|KERNEL|PANIC`。
 * `NONE` 表示「调用都成功、但结果与参照实现不符」，即真正的 KAT 不匹配；其余取值指向具体失败面。
 *
 * 载荷只有内核名与原因枚举，**不含** KDF 参数 / 密钥材料 / 主机标识。
 */
internal object NativeFallbackLog {

    private const val TAG = "NativeFallback"

    /** 原因枚举：只写枚举名，不含异常 message。 */
    enum class Reason { LIBRARY_UNAVAILABLE, KAT_MISMATCH_OR_ERROR }

    private val loggedKernels = ConcurrentHashMap.newKeySet<String>()

    /** 原生库加载状态探针（单测可替换，模拟「KAT 不匹配」与「so 缺失」两态）。 */
    @Volatile
    internal var libraryLoadedProbe: () -> Boolean = { NativeCryptoLibrary.loaded }

    /**
     * 失败原因码探针（单测可替换）。生产读 [NativeErrorCode.lastOrNull]——原生侧在返回失败值前
     * 把原因写进线程局部变量，探活与登记在同一线程，故此处读到的是**本次探活**的原因。
     */
    @Volatile
    internal var errorCodeProbe: () -> Int? = { NativeErrorCode.lastOrNull() }

    /** 最近一次登记的内核名（供单测断言；生产路径不读）。 */
    @Volatile
    var lastLoggedKernel: String? = null
        private set

    /** 探活失败已回落非原生实现——同一内核进程内只记一次。 */
    fun noteFallbackOnce(kernel: String) {
        if (!loggedKernels.add(kernel)) return
        lastLoggedKernel = kernel
        val loaded = libraryLoadedProbe()
        val reason = if (loaded) Reason.KAT_MISMATCH_OR_ERROR else Reason.LIBRARY_UNAVAILABLE
        // 库已加载才有原因码可读：区分「入参 / JNI / 内核 / panic」四种失败面。
        val code = if (loaded) errorCodeProbe() else null
        val detail = if (code == null) "" else ", code=${NativeErrorCode.nameOf(code)}"
        AppLog.i(TAG, "原生 $kernel 探活失败，已回落 JVM 实现: reason=${reason.name}$detail")
    }

    /** 单测用：已登记的内核集合。 */
    internal fun loggedKernels(): Set<String> = loggedKernels.toSet()

    /** 单测用：清空已登记内核并复位加载探针。 */
    fun resetForTest() {
        loggedKernels.clear()
        lastLoggedKernel = null
        libraryLoadedProbe = { NativeCryptoLibrary.loaded }
        errorCodeProbe = { NativeErrorCode.lastOrNull() }
    }
}
