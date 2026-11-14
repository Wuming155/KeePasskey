package com.keepasskey.crypto

/**
 * 原生 JNI 边界的**失败原因码**读取入口（`ISSUE-P2` C4）。
 *
 * 背景：各原生绑定沿用「失败返回 `null` / `-1`」的契约，Kotlin 侧归一为类型化异常。
 * 这对调用方足够，但**丢失失败原因**——`catch_unwind` 把 panic、非法入参、JNI 环境错误、
 * 内核运算失败压成同一个空值，诊断时无法区分「库坏了」与「参数传错了」。
 *
 * 原生侧（`crypto/src/main/rust/src/error_code.rs`）在返回失败值**之前**把原因写进**线程局部**
 * 变量；本对象是只读查询口。契约：
 *
 * - **不改既有返回语义**：失败仍是 `null` / `-1`，本对象是旁路查询，旧调用方零改动；
 * - **线程局部**：一次失败的原因只对发起调用的线程可见（各绑定均同步调用，天然同线程）；
 * - **成功即清零**：每次进入导出函数先置 [NONE]，成功路径不会残留上一次的失败码；
 * - **不承载秘密**：只有枚举值，无参数、无长度、无数据内容。
 *
 * 边界：原因码是**尽力而为**的标注（`?` 提前返回的中间步骤未显式标注时落到 [KERNEL]），
 * 用于**定位**，不作为安全判据——任何失败都必须按失败处理。
 */
object NativeErrorCode {

    /** 无失败。 */
    const val NONE = 0

    /** 入参非法：null 数组 / 参数闸门不过 / 长度不符。 */
    const val INVALID_ARGUMENT = 1

    /** JNI 环境错误：数组拷贝 / 分配 / 写回失败。 */
    const val JNI = 2

    /** 内核拒绝该输入（含内部分配失败）。 */
    const val KERNEL = 3

    /** Rust panic 被 `catch_unwind` 拦截（进程未 abort）。 */
    const val PANIC = 4

    /** 库未加载时不做查询：调用只会再抛一次 `UnsatisfiedLinkError`。 */
    private val queryable: Boolean by lazy { NativeCryptoLibrary.loaded }

    /**
     * 读取当前线程最近一次失败原因；不可查询时返回 `null`。
     *
     * 调用点应在拿到失败返回值**之后、同一线程内**立即调用。读取本身不改变原因码。
     */
    fun lastOrNull(): Int? {
        if (!queryable) return null
        return try {
            lastErrorCode()
        } catch (_: Throwable) {
            // 库已加载但符号缺失（旧版 .so）：与「不可查询」同义，不阻断调用方
            null
        }
    }

    /** 原因码 → 稳定枚举名（日志与测试断言共用；未知值原样带数字）。 */
    fun nameOf(code: Int): String = when (code) {
        NONE -> "NONE"
        INVALID_ARGUMENT -> "INVALID_ARGUMENT"
        JNI -> "JNI"
        KERNEL -> "KERNEL"
        PANIC -> "PANIC"
        else -> "UNKNOWN($code)"
    }

    /** 原生导出方法（符号 `Java_com_keepasskey_crypto_NativeErrorCode_lastErrorCode`）。 */
    private external fun lastErrorCode(): Int
}
