package com.keepasskey.core.model

import java.io.Closeable

/**
 * KDBX 条目二进制附件（引用式模型）。
 * 遵循 KDBX 4 标准：附件二进制数据存储于内层 Header 的二进制池中，条目仅保留名称、引用索引与保护标志。
 */
class KdbxAttachment(
    val name: String,
    val refIndex: Int = 0,
    val isProtected: Boolean = false,
    val data: ByteArray = ByteArray(0)
) : Closeable {

    /**
     * 获取附件实际二进制数据。
     * 若当前实例已绑定 [data] 且非空则优先返回，否则从提供的 [binaryPool] 中根据 [refIndex] 解析。
     */
    fun resolveData(binaryPool: List<ByteArray>): ByteArray {
        if (data.isNotEmpty()) return data
        return if (refIndex in binaryPool.indices) binaryPool[refIndex] else data
    }

    fun clear() {
        data.fill(0)
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
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + refIndex
        result = 31 * result + isProtected.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }

    override fun toString(): String {
        return "KdbxAttachment(name='$name', refIndex=$refIndex, isProtected=$isProtected, size=${data.size})"
    }
}
