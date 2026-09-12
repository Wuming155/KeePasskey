package com.keepasskey.core.security

import java.io.InputStream

/**
 * 大附件二进制落盘存储（ISSUE-P2-24）。
 *
 * 设计目标：把「逻辑引用」与「物理字节」分离——超过 [BinaryStorePolicy.DEFAULT_THRESHOLD_BYTES]
 * 的附件在内层二进制池中只保留 store key，不再整批常驻 GC 堆，从根源上消除大附件密码库的
 * OOM 与 GC 压力（KDBX 对象树其余部分仍整体驻留内存，见 AGENTS.md §6）。
 *
 * 契约（实现必须逐条满足）：
 * 1. **内容不可变**：同一 key 反复 [load] / [openStream] 必须读到同一份字节；实现不得就地改写。
 * 2. **别名隔离**：[load] 每次返回**独立副本**，调用方改写返回值不得影响后续读取或其它持有者。
 * 3. **敏感数据**：落盘文件权限须与同步缓存同基线（文件 0600 / 目录 0700）。
 * 4. **生命周期**：会话锁定 / 关闭时调用 [clear] 对称清理，缓存目录不留残余。
 */
interface BinaryStore {

    /** 以字节内容写入存储并返回 key。 */
    fun store(bytes: ByteArray): String

    /**
     * 从 [input] 精确读取 [size] 字节落盘（**不整份物化**），返回 key。
     *
     * @throws java.io.EOFException 流中实际字节不足 [size]
     */
    fun storeFromStream(input: InputStream, size: Long): String

    /** 读回整份字节（返回独立副本）。 */
    fun load(key: String): ByteArray

    /** 以流式读回（不整份物化）。 */
    fun openStream(key: String): InputStream

    /** [key] 对应的字节数。 */
    fun sizeOf(key: String): Long

    /** 清空全部落盘数据（会话锁定 / 关闭时调用）。 */
    fun clear()
}

/**
 * 大附件落盘阈值策略（ISSUE-P2-24）。
 *
 * 阈值可配：生产取 [DEFAULT_THRESHOLD_BYTES]，单测注入极小阈值以覆盖落盘路径
 * （避免动辄构造 MiB 级语料）。
 */
object BinaryStorePolicy {

    /** 默认落盘阈值 1 MiB：严格大于它的附件走磁盘缓存，等于或小于则仍驻留内存。 */
    const val DEFAULT_THRESHOLD_BYTES: Long = 1L * 1024 * 1024

    /** 内容字节数是否达到落盘标准（严格大于阈值）。 */
    fun shouldSpill(size: Long, thresholdBytes: Long = DEFAULT_THRESHOLD_BYTES): Boolean =
        size > thresholdBytes
}

/**
 * 附件字节来源抽象（ISSUE-P2-24）。
 *
 * 使 [com.keepasskey.core.model.KdbxAttachment] 既能承载「内存副本」（≤ 阈值，逐字保留既有语义），
 * 也能承载「落盘按需读取」（> 阈值）。
 */
interface BinarySource {

    /** 内容字节数（落盘来源不触发读取）。 */
    val size: Long

    /** 读回整份字节（返回独立副本）。 */
    fun load(): ByteArray

    /** 流式读回。 */
    fun openStream(): InputStream

    /**
     * 内容哈希，语义等同 `java.util.Arrays.hashCode(byte[])`（即 [ByteArray.contentHashCode]）。
     * 实现须流式计算，不得为求哈希而整份物化。
     */
    fun contentHash(): Int
}
