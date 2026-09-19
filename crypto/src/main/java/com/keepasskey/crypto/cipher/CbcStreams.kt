package com.keepasskey.crypto.cipher

import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.Arrays

/**
 * CBC 分组变换签名（byte[] 形态）：`(key, iv(原地演化为下一链值), data(长度须为 16 的整数倍)) -> 变换结果`。
 *
 * 注入式设计使流式包装**与具体算法/是否走原生完全解耦**，从而可用纯 JVM 变换在单测中
 * 覆盖全部边界（分段对齐、缺失块、非法填充），无需加载原生库。
 */
internal typealias CbcBlockTransform = (key: ByteArray, iv: ByteArray, data: ByteArray) -> ByteArray

/**
 * CBC 分组变换签名（**direct 形态**，`ISSUE-P3-198`）：对 [view]（**direct** `ByteBuffer`，
 * 容量即变换区间，须为 16 的整数倍）**原地**施加 CBC 变换并推进 [iv] 链值（原地演化，
 * 出口 = 最后一组密文）；失败抛 `CryptoException`（fail-closed）。
 *
 * 与 byte[] 形态的关键差异：变换**就地覆写输入**（无独立结果数组），故 `requireDistinctResult`
 * 契约不适用于本形态；明文无 Rust 侧副本，擦除责任在流（交付即归零 / close 整段兜底归零）。
 * 变换实现**不得移动 [view] 的 `position`**（native 直写内存不经过 Java 游标；JVM 模拟
 * 实现结束时须复位为 0）。生产注入点：`AesCipherEngine` 原生分支
 * （[NativeAes.encryptBlocksDirect] / [NativeAes.decryptBlocksDirect]）；宿主单测以 JCE
 * 纯 JVM 实现注入同一签名覆盖框架逻辑（`CbcDirectStreamsTest`）。
 */
internal typealias CbcDirectBlockTransform = (key: ByteArray, iv: ByteArray, view: ByteBuffer) -> Unit

/** 流式缓冲尺寸（必须是 [Pkcs7.BLOCK_SIZE] 的整数倍，否则分段对齐会被破坏）。 */
internal const val CBC_STREAM_BUFFER_SIZE = 64 * 1024

/**
 * 将 direct 缓冲的绝对区间 `[from, to)` 就地归零（堆外内存不受 GC 管辖，擦除必须显式且
 * 确定性——`ISSUE-P3-198` 擦除责任上移契约的执行原语）。完成后位置复位为调用前的值。
 *
 * 内部先 `clear()` 把 `limit` 复位为 `capacity`：调用方的残留 `limit`（如 `flip()` 后的
 * 短限位）不得截断归零区间，否则相对 `put` 会以 `BufferOverflowException` 中途失败、
 * 擦除不完整。副作用是 `limit` 被复位为 `capacity`——调用方后续均以显式 `position` 定位，
 * 不依赖残留限位。
 */
internal fun ByteBuffer.wipeRange(from: Int, to: Int) {
    val saved = position()
    clear()
    position(from)
    while (position() < to) put(0)
    position(saved)
}

/**
 * CBC + PKCS#7 加密输出流（ISSUE-P3-35）。
 *
 * 行为对齐 `javax.crypto.CipherOutputStream`：
 * - `write` 只累积，**不满一整块绝不输出**（CBC 反馈链要求）；
 * - [close] 对残余做 PKCS#7 补齐（整块对齐时追加一整个填充块）后输出，再关闭底层流；
 * - [flush] 仅冲刷底层流，**不**触发补齐（中途 flush 不是消息边界）。
 *
 * **双形态注入（`ISSUE-P3-198`）**：[directTransform] 非空 ⇒ 走 **direct 直扣形态**——调用方
 * 写入经 `put` 累积进**流实例自持的堆外缓冲**，满段后对 `[0, aligned)` 等容量视图**原地**
 * 变换（明文就地变密文，无明文中转副本；每段 3 次拷贝、零分配；旧形态为 4 次拷贝 +
 * 每段 2 个 64 KiB 数组分配）；为 `null` ⇒ 走既有 byte[] 形态（JCE 兜底 / 宿主纯 JVM 注入，
 * 逻辑逐字保留）。两种形态的分组对齐、补齐与清零纪律完全一致。
 */
internal class CbcEncryptingOutputStream(
    private val sink: OutputStream,
    private val key: ByteArray,
    iv: ByteArray,
    /** byte[] 形态变换（与 [directTransform] **二选一**，见 [CbcBlockTransform] KDoc）。 */
    private val transform: CbcBlockTransform?,
    bufferSize: Int = CBC_STREAM_BUFFER_SIZE,
    /**
     * 由本流**自持所有权**的秘密缓冲（原生路径传入的 `key.copyOf()`），[close] 时确定性擦除。
     * 契约与背景见 [CbcDecryptingInputStream.ownedSecrets] 的说明。
     */
    private val ownedSecrets: List<ByteArray> = emptyList(),
    /** direct 形态变换（与 [transform] **二选一**，见 [CbcDirectBlockTransform] KDoc）。 */
    private val directTransform: CbcDirectBlockTransform? = null
) : OutputStream() {

    init {
        require(bufferSize > Pkcs7.BLOCK_SIZE && bufferSize % Pkcs7.BLOCK_SIZE == 0) {
            "bufferSize 必须为分组长度的整数倍且大于一个分组: $bufferSize"
        }
        require(iv.size == Pkcs7.BLOCK_SIZE) { "IV 必须为 ${Pkcs7.BLOCK_SIZE} 字节" }
        require((transform == null) != (directTransform == null)) {
            "transform 与 directTransform 必须二选一（不得同时为空或同时非空）"
        }
    }

    /** CBC 链值：随每次分组变换原地推进（由变换实现写回）。 */
    private val chain: ByteArray = iv.copyOf()

    /** 累积区容量（供方法体使用；构造参数在初始化器之外不可见）。 */
    private val capacity: Int = bufferSize

    /** 累积缓冲（byte[] 形态）；direct 形态下转作 `sink.write` 的密文暂存（不再持有明文）。 */
    private val buffer = ByteArray(bufferSize)

    /**
     * `ISSUE-P3-198`：direct 形态的**累积变换区**（堆外）。调用方写入经 `put` 累积于此
     * （不变式：`position` 恒等于 [filled]），满段后对 `[0, aligned)` 等容量视图原地变换。
     * byte[] 形态恒为 `null`，零开销。
     */
    private val direct: ByteBuffer? =
        if (directTransform != null) ByteBuffer.allocateDirect(bufferSize) else null

    private var filled = 0
    private var closed = false

    override fun write(value: Int) {
        ensureOpen()
        if (filled == capacity) emitAlignedBlocks()
        if (direct != null) {
            direct.put(value.toByte())
        } else {
            buffer[filled] = value.toByte()
        }
        filled++
    }

    override fun write(data: ByteArray, off: Int, len: Int) {
        ensureOpen()
        var offset = off
        var remaining = len
        while (remaining > 0) {
            if (filled == capacity) emitAlignedBlocks()
            val count = minOf(capacity - filled, remaining)
            if (direct != null) {
                direct.put(data, offset, count)
            } else {
                System.arraycopy(data, offset, buffer, filled, count)
            }
            filled += count
            offset += count
            remaining -= count
        }
    }

    override fun flush() {
        sink.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            emitAlignedBlocks()
            // ISSUE-P3-96：`plainSource` 是明文的**中转副本**——`Pkcs7.pad` 内部会再复制
            // 一份（返回新数组），故该副本的唯一用途就是作为 pad 的入参，用毕**立即**清零
            // （direct 形态：残余明文在堆外累积区，先拷出小副本再补齐，堆外区随后被覆盖）
            val plainSource: ByteArray = if (direct != null) {
                ByteArray(filled).also {
                    direct.position(0)
                    direct.get(it, 0, filled)
                }
            } else {
                buffer.copyOf(filled)
            }
            val finalBlock = try {
                Pkcs7.pad(plainSource)
            } finally {
                Arrays.fill(plainSource, 0)
            }
            try {
                if (directTransform != null) {
                    // finalBlock（≤ 2 分组）经 direct 就地变换后由堆内暂存写出
                    val d = direct!!
                    d.clear()
                    d.put(finalBlock)
                    d.flip()
                    val view = d.slice()
                    directTransform(key, chain, view)
                    view.get(buffer, 0, finalBlock.size)
                    sink.write(buffer, 0, finalBlock.size)
                } else {
                    sink.write(
                        requireNotNull(transform) { "两种变换形态必须二选一" }(key, chain, finalBlock)
                    )
                }
            } finally {
                Arrays.fill(finalBlock, 0)
            }
            sink.flush()
        } finally {
            Arrays.fill(buffer, 0)
            Arrays.fill(chain, 0)
            filled = 0
            // direct 形态：整段兜底归零（任何意外路径都不在堆外残留数据）
            direct?.let { d ->
                d.wipeRange(0, d.capacity())
            }
            // 自持密钥副本（原生路径）随之确定性擦除；调用方原数组不在所有权内（不受影响）
            ownedSecrets.forEach { Arrays.fill(it, 0) }
            sink.close()
        }
    }

    /** 写出缓冲中「已满的整数倍分组」，残余前移保留。 */
    private fun emitAlignedBlocks() {
        val aligned = filled - (filled % Pkcs7.BLOCK_SIZE)
        if (aligned == 0) return
        val rest = filled - aligned
        if (directTransform != null) {
            // direct 形态（`ISSUE-P3-198`）：[0, aligned) 等容量视图原地变换——明文就地变
            // 密文，无明文中转副本；密文经堆内暂存写出，残余（不足一分组）绝对定位前移。
            val d = direct!!
            val dup = d.duplicate()
            dup.clear()
            dup.limit(aligned)
            val view = dup.slice()
            directTransform(key, chain, view)
            view.get(buffer, 0, aligned)
            if (rest > 0) {
                for (i in 0 until rest) d.put(i, d.get(aligned + i))
            }
            d.position(rest)
            filled = rest
            sink.write(buffer, 0, aligned)
            return
        }
        // ISSUE-P3-96：`chunk` 是交给变换的**明文副本**（原地加密与否由变换实现决定），
        // 写出后必须显式清零——否则该明文副本会随局部变量出栈后静默留存至 GC
        val chunk = buffer.copyOf(aligned)
        try {
            sink.write(requireNotNull(transform) { "两种变换形态必须二选一" }(key, chain, chunk))
        } finally {
            Arrays.fill(chunk, 0)
        }
        if (rest > 0) System.arraycopy(buffer, aligned, buffer, 0, rest)
        filled = rest
    }

    private fun ensureOpen() {
        if (closed) throw IOException("流已关闭")
    }
}
