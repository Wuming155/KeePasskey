package com.keepasskey.database.io

import java.io.ByteArrayOutputStream

/**
 * ISSUE-P3-118（威胁建模 T-16 残余）：可在用毕后**清零内部缓冲**的 [ByteArrayOutputStream]。
 *
 * 缺陷形态：`ByteArrayOutputStream.toByteArray()` 返回的是**副本**，而产生它的内部缓冲
 * （第二份完整的整库序列化字节）在对象被 GC 前不会被清零——`reset()` 只把计数置 0，
 * **不清内容**；JDK 也未提供任何清零 API。序列化产物是**整库密文**（内层除头部外全部加密，
 * 仍属会话派生敏态数据），故它必须与「返回数组」一样走确定性擦除，而不是等待 GC。
 *
 * 用法（务必 `finally` 收口，异常路径同样是残留路径）：
 * ```
 * val buffer = WipableByteArrayOutputStream()
 * val bytes = try {
 *     KdbxFile.save(buffer, db, pwd, keyFile)
 *     buffer.toByteArray()
 * } finally {
 *     buffer.wipe()
 * }
 * ```
 */
internal class WipableByteArrayOutputStream(initialSize: Int = DEFAULT_SIZE) :
    ByteArrayOutputStream(initialSize) {

    /**
     * 清零内部缓冲并复位计数。
     *
     * 幂等：可重复调用（多次擦除无害）。`buf` 为父类的 `protected` 字段——
     * 在 JDK 未提供清零 API 的前提下，这是唯一可靠的落点。
     */
    fun wipe() {
        buf.fill(0)
        reset()
    }

    /**
     * 仅测试可见：内部缓冲的**副本**（用于断言 [wipe] 是否把字节逐位清零）。
     *
     * 之所以需要该探针：`buf` 是父类的 `protected` 字段、本类为 final（Kotlin 默认不可继承），
     * 而 JDK 未提供任何读取内部缓冲的公开 API——没有它就只能断言「计数已复位」，
     * 而本项缺陷的定义恰恰是「计数复位、字节仍在」。
     */
    @androidx.annotation.VisibleForTesting
    internal fun bufferedBytesSnapshot(): ByteArray = buf.copyOf()

    private companion object {
        /** 与 `ByteArrayOutputStream` 默认容量一致 */
        const val DEFAULT_SIZE = 32
    }
}
