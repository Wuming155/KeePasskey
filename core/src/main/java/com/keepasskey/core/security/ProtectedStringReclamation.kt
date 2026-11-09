package com.keepasskey.core.security

import java.io.Closeable
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.TreeSet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 共享受保护实例的**回收协调器**（`ENG-20261028-11`：基于 Epoch 的分代旧树读者协调）。
 *
 * ## 设计原则与所有权契约
 *
 * 1. **单调租约 ID 与分代归组（Epoch）**：
 *    每次登记读租约分配单调递增的 `leaseId`；当实例下线交付 [retire] 时，按当前已发放的最大 `leaseId`
 *    归入退休桶（Retirement Bucket）。该桶仅需等待下线时刻在册的读者（`leaseId <= bucket.maxLeaseId`）全部结束；
 * 2. **后续新租约不推迟更早退休桶**：
 *    在某批实例下线之后新创建的读租约（`leaseId > bucket.maxLeaseId`）读取的是新树，不持有该批下线实例，
 *    因此绝不推迟更早退休桶的回收。这彻底消除了全局计数永不归零导致的无限滞留；
 * 3. **无硬阈值强制清零，杜绝中断活读**：
 *    只要读者持有合法的读租约，其可见的受保护实例绝对不会被强制擦除；
 * 4. **无主动等待与死锁消除（实事求是开销声明）**：
 *    协调器内部不主动等待读者（不调用 wait/join，无死锁风险）；但必须指出：所有状态变更与批量排空
 *    均持有同一对象锁（synchronized），存在锁竞争；批量排空下线实例执行 clear() 具有 O(N) 循环开销，
 *    若在主线程释放最后一个租约会产生可观测耗时，开销需经基准实测，不可宣称零竞争或零开销；
 * 5. **锁定 / 会话终止直接清理不等待**：
 *    走 [ProtectedString.clear] 的直接擦除，不经过本协调器，也不等租约——锁定是硬边界，旧树读取直接打断并拒绝交付；
 * 6. **内存滞留真实限制**：
 *    待回收实例按未释放的更早活跃读者滞留，因此在长租约或泄漏场景下滞留内存并非硬上限有界，
 *    必须依赖消费面严格遵守「不持租约跨用户交互/跨网络等待」的调用纪律；
 * 7. **按身份集合防重复回收**：
 *    以 IdentityHashMap 记录已登记实例，避免同一实例被多条擦除路径重复入桶或提前回收。
 */
object ProtectedStringReclamation {

    private val monitor = Any()

    /** 单调递增的租约发号器（从 1 开始） */
    private var nextLeaseId = 1L

    /** 当前所有处于活跃状态的租约 ID 集合（自然排序，便于快速取最小活跃 ID） */
    private val activeLeases = TreeSet<Long>()

    /**
     * 退休实例的待排空桶：按 [maxLeaseId] 升序排列。
     * 当 `activeLeases` 最小活跃 ID 严格大于某桶的 [maxLeaseId] 时，表示该桶创建时的所有在册读者均已退出，
     * 该桶内的实例可安全擦除。
     */
    private val buckets = ArrayDeque<Bucket>()

    /** 已登记待回收的实例与其绑定的最大租约 ID（按对象身份去重） */
    private val pendingInstances = IdentityHashMap<ProtectedString, Long>()

    private class Bucket(val maxLeaseId: Long) {
        val instances = mutableListOf<ProtectedString>()
    }

    /**
     * 登记一次读租约。返回值必须成对释放（`use` / try-finally）；重复释放无副作用。
     *
     * 用法（挂起上下文同样适用，[java.io.Closeable.use] 是 inline）：
     * ```
     * ProtectedStringReclamation.acquireReadLease().use { /* 消费这份树快照 */ }
     * ```
     */
    fun acquireReadLease(): ReadLease {
        val id: Long
        synchronized(monitor) {
            id = nextLeaseId++
            activeLeases.add(id)
        }
        return ReadLease(id)
    }

    /** [acquireReadLease] 的词法作用域封装：块内结束（含异常）即释放租约 */
    inline fun <R> withReadLease(block: () -> R): R = acquireReadLease().use { block() }

    /**
     * 回收一个已下线的受保护实例：无读者在册则立即擦除，有读者在册则归入当前 Epoch 桶。
     *
     * 仅供 core 的定点擦除路径调用（见
     * [com.keepasskey.core.model.KdbxEntry.clearSensitiveIdentitiesNotIn]）——**不得**用它替代
     * 锁定 / 会话终止的直接擦除，那两条路径的语义是「立即、不等读者」。
     */
    internal fun retire(instance: ProtectedString) {
        if (instance === ProtectedString.EMPTY || instance.cleared) return
        synchronized(monitor) {
            if (activeLeases.isEmpty()) {
                instance.clear()
                return
            }
            val maxIssued = nextLeaseId - 1
            val existing = pendingInstances[instance]
            if (existing != null && existing >= maxIssued) {
                return
            }
            pendingInstances[instance] = maxIssued
            val lastBucket = buckets.peekLast()
            if (lastBucket != null && lastBucket.maxLeaseId == maxIssued) {
                lastBucket.instances.add(instance)
            } else {
                val newBucket = Bucket(maxIssued)
                newBucket.instances.add(instance)
                buckets.addLast(newBucket)
            }
        }
    }

    /** 当前待回收实例数（仅供 core 测试与探针观测，不含明文） */
    internal fun pendingRetirementCount(): Int = synchronized(monitor) { pendingInstances.size }

    /** 当前在册读租约数（同上） */
    internal fun activeReadLeaseCount(): Int = synchronized(monitor) { activeLeases.size }

    internal fun releaseReadLease(leaseId: Long) {
        synchronized(monitor) {
            activeLeases.remove(leaseId)
            drainEligibleBucketsLocked()
        }
    }

    /** 调用方必须持有 [monitor] */
    private fun drainEligibleBucketsLocked() {
        if (buckets.isEmpty()) return
        if (activeLeases.isEmpty()) {
            // 无活跃读者：全部桶立即可排空
            while (buckets.isNotEmpty()) {
                val bucket = buckets.removeFirst()
                for (inst in bucket.instances) {
                    if (pendingInstances.remove(inst) != null) {
                        inst.clear()
                    }
                }
            }
            return
        }
        val minActive = activeLeases.first()
        while (buckets.isNotEmpty()) {
            val first = buckets.peekFirst() ?: break
            if (first.maxLeaseId >= minActive) break
            val bucket = buckets.removeFirst()
            for (inst in bucket.instances) {
                if (pendingInstances[inst] == bucket.maxLeaseId) {
                    pendingInstances.remove(inst)
                    inst.clear()
                }
            }
        }
    }

    /** 一次读租约的句柄；`close` 幂等 */
    class ReadLease internal constructor(private val leaseId: Long) : Closeable {

        private val released = AtomicBoolean(false)

        override fun close() {
            if (!released.compareAndSet(false, true)) return
            releaseReadLease(leaseId)
        }
    }
}
