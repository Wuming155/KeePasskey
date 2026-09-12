package com.keepasskey.core.model

import com.keepasskey.core.security.BinarySource
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.InputStream

/**
 * KDBX 条目二进制附件（引用式模型）。
 * 遵循 KDBX 4 标准：附件二进制数据存储于内层 Header 的二进制池中，条目仅保留名称、引用索引与保护标志。
 *
 * ISSUE-P2-24：附件字节来源分为两种——
 * - **内存副本**（≤ 落盘阈值，或新建 / 编辑期由 UI 提供的字节）：逐字保留既有语义；
 * - **落盘按需读取**（> 阈值）：仅持 [BinarySource] 引用，[data] 按需读回独立副本，
 *   使大附件库不再「整池 + 逐附件副本」双份常驻 GC 堆。
 *
 * 别名隔离契约（ISSUE-P3-07）不变：无论哪种来源，[data] / [openStream] 交付的字节都不与
 * 内层二进制池、也不与其它附件共享可变引用；[clear] 只作用于本实例自身。
 */
class KdbxAttachment(
    val name: String,
    val refIndex: Int = 0,
    val isProtected: Boolean = false,
    data: ByteArray = ByteArray(0),
    private val source: BinarySource? = null
) : Closeable {

    /** 内存副本；落盘附件为空数组（真实字节只在 [source] 中）。 */
    private val inlineData: ByteArray = data

    /**
     * 附件实际二进制数据的**借用视图**——所有权因来源而异，消费方必须先确认自己属于哪一侧：
     * - **内存附件**（[source] 为 null）：返回本实例持有的数组**本身**（不是副本），
     *   调用方**严禁**修改或清零它；需要独立副本请自行 `data.copyOf()`；
     * - **落盘附件**（[source] 非空）：每次调用按需读回**独立副本**，调用方用毕应自行清零。
     *
     * 该差异曾导致真实缺陷：写侧误以为恒为副本，在 `finally` 中 `fill(0)`，
     * 使「写出」顺带销毁了内存附件的字节（同一实例再次保存即全零）。
     */
    val data: ByteArray
        get() = source?.load() ?: inlineData

    /** 字节数；落盘附件不触发读取。 */
    val size: Long
        get() = source?.size ?: inlineData.size.toLong()

    /** 流式读取附件字节（导出 / 写回路径避免整份物化）。 */
    fun openStream(): InputStream = source?.openStream() ?: ByteArrayInputStream(inlineData)

    /**
     * 获取附件实际二进制数据。
     * 若当前实例已绑定内存副本且非空则优先返回，否则从提供的 [binaryPool] 中根据 [refIndex] 解析。
     */
    fun resolveData(binaryPool: List<ByteArray>): ByteArray {
        source?.let { return it.load() }
        if (inlineData.isNotEmpty()) return inlineData
        return if (refIndex in binaryPool.indices) binaryPool[refIndex] else inlineData
    }

    /** 落盘来源（供同进程内去重 / 写回路径复用，避免整份读回）；内存附件为 null。 */
    fun binarySource(): BinarySource? = source

    /** 内存副本（供同进程内去重路径读取）；落盘附件为空数组。 */
    fun inlineBytes(): ByteArray = inlineData

    /**
     * 清零本实例持有的内存副本。
     *
     * 落盘附件（[source] 非空）的字节在 store 中由多个引用者共享，**不在此处清零**
     * （清零会连带损坏其它引用者）——其生命周期由会话锁定时的 store 统一清理收口
     * （见 [com.keepasskey.core.security.BinaryStore.clear]）。
     */
    fun clear() {
        inlineData.fill(0)
    }

    override fun close() {
        clear()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KdbxAttachment) return false
        if (name != other.name) return false
        if (refIndex != other.refIndex) return false
        if (isProtected != other.isProtected) return false
        if (size != other.size) return false
        if (source != null || other.source != null) {
            // 落盘附件：按内容哈希比较，避免整份物化（另一侧为内存副本时亦然，哈希算法同源）
            return contentHash() == other.contentHash()
        }
        return inlineData.contentEquals(other.inlineData)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + refIndex
        result = 31 * result + isProtected.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + contentHash()
        return result
    }

    override fun toString(): String {
        return "KdbxAttachment(name='$name', refIndex=$refIndex, isProtected=$isProtected, size=$size)"
    }

    private fun contentHash(): Int = source?.contentHash() ?: inlineData.contentHashCode()
}
