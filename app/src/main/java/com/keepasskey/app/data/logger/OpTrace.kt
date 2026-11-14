package com.keepasskey.app.data.logger

import java.util.concurrent.atomic.AtomicLong

/**
 * 单次操作的轻量链路标识（`ISSUE-P2` B1/B2）。
 *
 * 目的：把一次同步 / 解锁的多条日志按 `op=` 聚合成因果链，并给出耗时。
 * 只含操作类型与随机序号，不含库名、路径、账号或任何用户数据；不参与业务判定。
 *
 * 边界：本类不做跨线程传播——需要聚合的日志行由调用方在同一操作内显式带上 [id]，
 * 避免「全局当前操作」在并发同步 / Passkey 流程间串味。
 */
internal class OpTrace private constructor(
    val id: String,
    val kind: String
) {

    private val startedAtNanos = System.nanoTime()

    /** 自 [start] 起的毫秒数；用于日志的 `durMs=`。 */
    fun elapsedMs(): Long = (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLI

    companion object {
        private val sequence = AtomicLong()

        fun start(kind: String): OpTrace {
            val seq = sequence.incrementAndGet().toString(HEX_RADIX)
            val salt = (System.nanoTime() ushr 8).toString(HEX_RADIX).takeLast(4)
            return OpTrace(id = "$kind-$salt$seq", kind = kind)
        }

        private const val HEX_RADIX = 16
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
