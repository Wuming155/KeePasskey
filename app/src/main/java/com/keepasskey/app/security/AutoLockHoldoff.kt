package com.keepasskey.app.security

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 长任务挂锁闸（ISSUE-P3-366 AC②）。
 *
 * 语义：[begin] / [end] 成对调用并支持嵌套（深度计数）；挂起期间收到的锁定触发
 * （熄屏 / 后台超时 / 回前台补偿）经 [defer] 暂存为「待补执行原因」——**延迟而非丢弃**；
 * depth 归零时由 [end] 返回暂存原因，调用方（[AutoLockSessionGuard.endLongTask]）
 * 据此补执行锁定。
 *
 * 对称性约束（由单测锁定）：
 * - 漏配对的 [begin] 会使 depth 永停于 > 0 ⇒ 挂锁永不恢复，故全部挂点必须 try/finally 成对；
 * - 多余的 [end] 钳制在 0 且不产生补锁原因，不会反向放大为「提前解锁」。
 */
@Singleton
class AutoLockHoldoff @Inject constructor() {

    private val gate = Any()
    private var depth = 0
    private var pendingReason: String? = null

    /** 当前是否处于挂锁期（depth > 0） */
    val isHolding: Boolean
        get() = synchronized(gate) { depth > 0 }

    /** 开启一层挂锁（可嵌套；必须与 [end] 成对，建议调用点 try/finally 包裹） */
    fun begin() {
        synchronized(gate) { depth++ }
    }

    /**
     * 挂锁期间暂存一次锁定请求：返回 true 表示调用方须延迟锁定并立即返回；
     * 未挂锁返回 false（调用方照常立即锁定）。多次暂存保留最近原因（绝不丢锁）。
     */
    fun defer(reason: String): Boolean = synchronized(gate) {
        if (depth <= 0) return false
        pendingReason = reason
        true
    }

    /**
     * 结束一层挂锁：depth 归零且存在暂存原因时返回之（调用方补执行锁定），否则返回 null。
     * 多余的 end（无配对 begin）不产生原因——漏配对方向的防护见类 KDoc。
     */
    fun end(): String? = synchronized(gate) {
        if (depth > 0) depth--
        if (depth > 0) return null
        pendingReason.also { pendingReason = null }
    }
}
