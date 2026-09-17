package com.keepasskey.crypto.cipher

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Arrays

/**
 * CBC 分组变换签名：`(key, iv(原地演化为下一链值), data(长度须为 16 的整数倍)) -> 变换结果`。
 *
 * 注入式设计使流式包装**与具体算法/是否走原生完全解耦**，从而可用纯 JVM 变换在单测中
 * 覆盖全部边界（分段对齐、缺失块、非法填充），无需加载原生库。
 */
internal typealias CbcBlockTransform = (key: ByteArray, iv: ByteArray, data: ByteArray) -> ByteArray

/** 流式缓冲尺寸（必须是 [Pkcs7.BLOCK_SIZE] 的整数倍，否则分段对齐会被破坏）。 */
internal const val CBC_STREAM_BUFFER_SIZE = 64 * 1024

/**
 * CBC + PKCS#7 加密输出流（ISSUE-P3-35）。
 *
 * 行为对齐 `javax.crypto.CipherOutputStream`：
 * - `write` 只累积，**不满一整块绝不输出**（CBC 反馈链要求）；
 * - [close] 对残余做 PKCS#7 补齐（整块对齐时追加一整个填充块）后输出，再关闭底层流；
 * - [flush] 仅冲刷底层流，**不**触发补齐（中途 flush 不是消息边界）。
 *
 * 敏感数据处理：明文缓冲在 [close] 时显式归零。
 */
internal class CbcEncryptingOutputStream(
    private val sink: OutputStream,
    private val key: ByteArray,
    iv: ByteArray,
    private val transform: CbcBlockTransform,
    bufferSize: Int = CBC_STREAM_BUFFER_SIZE
) : OutputStream() {

    init {
        require(bufferSize > Pkcs7.BLOCK_SIZE && bufferSize % Pkcs7.BLOCK_SIZE == 0) {
            "bufferSize 必须为分组长度的整数倍且大于一个分组: $bufferSize"
        }
        require(iv.size == Pkcs7.BLOCK_SIZE) { "IV 必须为 ${Pkcs7.BLOCK_SIZE} 字节" }
    }

    /** CBC 链值：随每次分组变换原地推进（由变换实现写回）。 */
    private val chain: ByteArray = iv.copyOf()
    private val buffer = ByteArray(bufferSize)
    private var filled = 0
    private var closed = false

    override fun write(value: Int) {
        ensureOpen()
        if (filled == buffer.size) emitAlignedBlocks()
        buffer[filled++] = value.toByte()
    }

    override fun write(data: ByteArray, off: Int, len: Int) {
        ensureOpen()
        var offset = off
        var remaining = len
        while (remaining > 0) {
            if (filled == buffer.size) emitAlignedBlocks()
            val count = minOf(buffer.size - filled, remaining)
            System.arraycopy(data, offset, buffer, filled, count)
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
            // 残余不足一整块（filled == 0 时补齐一整个填充块）
            // ISSUE-P3-96：`buffer.copyOf(filled)` 是明文的**中转副本**——`Pkcs7.pad` 内部会再复制
            // 一份（返回新数组），故该副本的唯一用途就是作为 pad 的入参，用毕**立即**清零
            val plainSource = buffer.copyOf(filled)
            val finalBlock = try {
                Pkcs7.pad(plainSource)
            } finally {
                Arrays.fill(plainSource, 0)
            }
            try {
                sink.write(transform(key, chain, finalBlock))
            } finally {
                Arrays.fill(finalBlock, 0)
            }
            sink.flush()
        } finally {
            Arrays.fill(buffer, 0)
            Arrays.fill(chain, 0)
            filled = 0
            sink.close()
        }
    }

    /** 写出缓冲中「已满的整数倍分组」，残余前移保留。 */
    private fun emitAlignedBlocks() {
        val aligned = filled - (filled % Pkcs7.BLOCK_SIZE)
        if (aligned == 0) return
        // ISSUE-P3-96：`chunk` 是交给变换的**明文副本**（原地加密与否由变换实现决定），
        // 写出后必须显式清零——否则该明文副本会随局部变量出栈后静默留存至 GC
        val chunk = buffer.copyOf(aligned)
        try {
            sink.write(transform(key, chain, chunk))
        } finally {
            Arrays.fill(chunk, 0)
        }
        val rest = filled - aligned
        if (rest > 0) System.arraycopy(buffer, aligned, buffer, 0, rest)
        filled = rest
    }

    private fun ensureOpen() {
        if (closed) throw IOException("流已关闭")
    }
}

/**
 * CBC + PKCS#7 解密输入流（ISSUE-P3-35）。
 *
 * **错误语义以实测的 `javax.crypto.CipherInputStream` 基线为准**（本类最容易出错、也最容易被
 * 忽略的一点）。ISSUE-P3-35 的验收标准要求「先实测基线再对齐」，`CbcStreamFramingTest`
 * 即以真实 JCE 为参照逐例断言，结论（2026-09-10 于本机 JDK 实测）为：
 *
 * 1. **填充非法** → 基线在读到尾部时抛 `IOException`（cause 为 `BadPaddingException`），
 *    **不是**静默 EOF；
 * 2. **密文长度非分组整数倍 / 空输入** → 基线抛 `IOException`（cause 为
 *    `IllegalBlockSizeException`）；
 * 3. **底层流抛 `IOException`** → 原样上抛。
 *
 * 本类三者一致：均在 `read` 到达尾部时抛出 `IOException`。这一点对既有代码是**必需的**——
 * `KdbxCipherKeyResolver.isPlausibleInnerHeaderPrefix` 正是以
 * `catch (_: java.io.IOException)` 来截断「用首块做解密探针」时的收尾错误并保留已解出前缀；
 * 若本类改为静默 EOF，该探针的行为将随之改变。
 *
 * 实现要点：**恒扣留最后一个整分组**（解密侧链值取密文，故读取过程中即可解出并交付除末块外
 * 的全部明文），仅在真正到达尾部时对该块去填充。
 *
 * 已知与基线的差异（如实声明，方向为**不影响正确性**）：若调用方**未读到尾部**即调用
 * [close]，基线可能仍在 `close` 内触发 `doFinal` 的填充校验并抛异常，本类不做该收尾校验。
 * 该路径下双方都不交付任何未校验明文，故不构成正确性缺口；不做收尾校验是**有意的保守选择**：
 * 对合法库而言「提前 close」意味着解析器已按 GZip 结构自然停止，此时强行校验填充
 * 反而可能对正常数据误报。
 */
internal class CbcDecryptingInputStream(
    private val source: InputStream,
    private val key: ByteArray,
    iv: ByteArray,
    private val transform: CbcBlockTransform,
    private val chunkSize: Int = CBC_STREAM_BUFFER_SIZE
) : InputStream() {

    init {
        require(chunkSize > Pkcs7.BLOCK_SIZE && chunkSize % Pkcs7.BLOCK_SIZE == 0) {
            "chunkSize 必须为分组长度的整数倍且大于一个分组: $chunkSize"
        }
        require(iv.size == Pkcs7.BLOCK_SIZE) { "IV 必须为 ${Pkcs7.BLOCK_SIZE} 字节" }
    }

    /** CBC 链值：随每次分组变换原地推进（由变换实现写回）。 */
    private val chain: ByteArray = iv.copyOf()

    /**
     * `ISSUE-P3-177`：读取缓冲**实例级复用**。布局：
     * `[0, pendingLength)` = 上一轮扣留的末分组（密文），紧随其后是本轮读入的载荷。
     *
     * 原实现每块都新建 `chunk`（64 KiB）与 `concat` 的合并数组（≈64 KiB+16）——一个 10 MiB 库
     * 约 160 个块 ⇒ 约 320 次 64 KiB 分配 + 全部整块拷贝。
     */
    private val buffer = ByteArray(chunkSize + Pkcs7.BLOCK_SIZE)

    /** 扣留于 [buffer] 头部的末分组长度（0 或一个分组） */
    private var pendingLength = 0

    /**
     * `ISSUE-P3-177`：交给变换的**复用**输入缓冲，长度恒为 [chunkSize]（稳态满块载荷等长）。
     *
     * 契约说明：变换**必须返回独立结果数组**（不得原地返回入参）——这一点既有实现本已依赖
     * （[transformBlocks] 的 `finally` 会对入参切片清零；若变换原地返回，明文会被自己抹掉），
     * 本类据此复用该输入缓冲，并以 [requireDistinctResult] 显式校验，避免未来出现原地实现时
     * 静默产出零明文。
     */
    private val bodyScratch = ByteArray(chunkSize)

    /** 待交付明文缓冲。 */
    private var out: ByteArray = EMPTY
    private var outPos = 0
    private var eofDone = false
    private var closed = false

    override fun read(): Int {
        val single = ByteArray(1)
        val count = read(single, 0, 1)
        if (count <= 0) return -1
        val value = single[0].toInt() and 0xFF
        Arrays.fill(single, 0)
        return value
    }

    override fun read(data: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        require(off >= 0 && len <= data.size - off) { "非法的读取区间 off=$off len=$len" }
        while (true) {
            if (outPos < out.size) {
                val count = minOf(len, out.size - outPos)
                System.arraycopy(out, outPos, data, off, count)
                outPos += count
                return count
            }
            if (!refill()) return -1
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        wipeBuffers()
        Arrays.fill(buffer, 0)
        Arrays.fill(bodyScratch, 0)
        pendingLength = 0
        Arrays.fill(chain, 0)
        source.close()
    }

    /** 尝试产出一段明文；返回 `false` 表示流已结束（含上述三类 fail-closed 情形）。 */
    private fun refill(): Boolean {
        if (eofDone) return false
        wipeBuffers()

        val read = readUpTo(pendingLength)
        val filled = pendingLength + read
        val atEof = read < chunkSize

        if (!atEof) {
            // 尾部未到：扣留最后一个整分组，其余立即解密交付
            val keep = filled - Pkcs7.BLOCK_SIZE
            out = if (keep == chunkSize) {
                // 稳态满块（已扣留一个分组）⇒ 载荷长度恒为 chunkSize：复用输入缓冲，零分配
                System.arraycopy(buffer, 0, bodyScratch, 0, chunkSize)
                requireDistinctResult(transform(key, chain, bodyScratch))
            } else {
                // 首块（尚未扣留，载荷为 chunkSize - 16）等非等长情形：沿用一次分配路径
                transformBlocks(buffer, 0, keep)
            }
            outPos = 0
            pendingLength = Pkcs7.BLOCK_SIZE
            // 原地的末分组前移，并把本块载荷区的密文清零（保留既有清零纪律；不得覆盖前移后的末分组）
            System.arraycopy(buffer, filled - Pkcs7.BLOCK_SIZE, buffer, 0, Pkcs7.BLOCK_SIZE)
            Arrays.fill(buffer, Pkcs7.BLOCK_SIZE, filled, 0)
            return true
        }

        eofDone = true
        val tailLength = filled
        pendingLength = 0

        if (tailLength == 0) {
            // 基线：CipherInputStream 在空输入上由 doFinal 抛 IllegalBlockSizeException → IOException
            throw IOException("CBC 解密流：密文为空，无法校验 PKCS#7 填充")
        }
        if (tailLength % Pkcs7.BLOCK_SIZE != 0) {
            // 基线：长度非分组整数倍 → IllegalBlockSizeException → IOException
            Arrays.fill(buffer, 0, tailLength, 0)
            throw IOException("CBC 解密流：密文长度 $tailLength 非 ${Pkcs7.BLOCK_SIZE} 的整数倍")
        }

        val keep = tailLength - Pkcs7.BLOCK_SIZE
        val head = transformBlocks(buffer, 0, keep)
        val lastPlain = transformBlocks(buffer, keep, tailLength)
        val stripped = Pkcs7.unpad(lastPlain)
        Arrays.fill(lastPlain, 0)
        Arrays.fill(buffer, 0, tailLength, 0)

        if (stripped == null) {
            // 基线：填充非法 → BadPaddingException → IOException
            Arrays.fill(head, 0)
            throw IOException("CBC 解密流：PKCS#7 填充非法（密钥错误或数据被篡改）")
        }
        out = if (head.isEmpty()) stripped else head + stripped
        outPos = 0
        return out.isNotEmpty()
    }

    /** 变换结果不得与复用输入别名（见 [bodyScratch] 的契约说明）。 */
    private fun requireDistinctResult(result: ByteArray): ByteArray {
        check(result !== bodyScratch) {
            "CBC 变换实现必须返回独立结果数组（不得原地返回入参）：复用输入缓冲会在下一块被覆盖"
        }
        return result
    }

    /** 变换 `[from, to)` 区间（长度须为分组整数倍），返回新数组；空区间直接返回空。 */
    private fun transformBlocks(source: ByteArray, from: Int, to: Int): ByteArray {
        if (to <= from) return EMPTY
        val slice = source.copyOfRange(from, to)
        return try {
            transform(key, chain, slice)
        } finally {
            Arrays.fill(slice, 0)
        }
    }

    /**
     * 读取至缓冲填满「载荷区」或流结束；返回实际读取字节数（`< chunkSize` 即已到尾部）。
     * `start` 为扣留末分组占用的头部长度，载荷最多读 [chunkSize] 字节。
     */
    private fun readUpTo(start: Int): Int {
        var total = 0
        while (total < chunkSize) {
            val count = source.read(buffer, start + total, chunkSize - total)
            if (count <= 0) {
                // 规范上 len > 0 时不得返回 0；真出现则按结束处理（fail-closed，防自旋）
                break
            }
            total += count
        }
        return total
    }

    private fun wipeBuffers() {
        if (out.isNotEmpty()) Arrays.fill(out, 0)
        out = EMPTY
        outPos = 0
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
