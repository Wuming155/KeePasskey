package com.keepasskey.database.file

import com.keepasskey.crypto.hash.HashUtil
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.io.LittleEndianUtil
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 终止块（blockSize = 0）的 HMAC 无数据段。
 * [BlockHmac.compute] 在 `length == 0` 时不读取该参数，仅用于满足非空签名。
 */
private val EMPTY_BLOCK_DATA = ByteArray(0)

/**
 * KDBX 4 HMAC 认证块流（HmacBlockStream）。
 * 针对每个数据块进行独立 HMAC-SHA256 签名校验，提供防篡改验证与加密块完整性。
 * 流式形态（HmacBlockInputStream/HmacBlockOutputStream）为 KdbxFile.load/save 主管线专用，
 * 保留官方语义——「边解密边校验，错误尽早暴露」，且不整体物化密文。
 * 全量形态（readAll/writeAll）会将整条数据物化进内存，仅限单元测试与离线工具使用；
 * 生产代码严禁调用，以免退回整库物化的内存路径。
 *
 * ISSUE-P3-37：块摘要的五处手工拼装（`writeAll` / `readAll` / `loadNextBlock` / `flushBlock` /
 * `close`）统一收敛到 [BlockHmac]；[HmacBlockStream.computeBlockKey] 作为既有公开 API 保留原样。
 * 块格式与异常语义**完全未变**（由既有篡改 / 终止块 / EOF 三类用例锁定）；
 * 读侧块尺寸上限经 ISSUE-P3-268 裁决**移除**（对齐官方 KeePass 2.61.1 / keepass2android /
 * KeePassXC：读侧仅拒绝负数块长，规格对块长仅定 Int32，1 MiB 只是官方写侧惯例），
 * 旧 1 MiB 上限曾会误拒「块长 > 1 MiB」的合法第三方文件；恶意分配仍由
 * [LittleEndianUtil.readBytes] 的 16 MiB 通用护栏（P0-5）与 EOF 路径兜底。
 */
object HmacBlockStream {

    const val DEFAULT_BLOCK_SIZE = 1024 * 1024 // 1 MB

    /// HMAC-SHA256 签名长度（字节）
    const val HMAC_SIZE = 32

    /**
     * 计算指定块索引的 64 字节 HMAC 密钥（官方规范 / 对齐 KeePass 2.x GetBlockKey、pykeepass、KeePassXC）：
     * blockKey = SHA-512(blockIndex (Little Endian 8 bytes) ‖ hmacKey64)
     */
    fun computeBlockKey(blockIndex: Long, hmacKey64: ByteArray): ByteArray {
        val indexBytes = LittleEndianUtil.longTo8Bytes(blockIndex)
        return HashUtil.sha512(indexBytes, hmacKey64)
    }

    /**
     * 将明文流按 HMAC 块切分并写入目标输出流
     */
    fun writeAll(
        data: ByteArray,
        outputStream: OutputStream,
        hmacKey64: ByteArray,
        blockSize: Int = HmacBlockStream.DEFAULT_BLOCK_SIZE
    ) {
        // ISSUE-P3-37：块摘要统一由 BlockHmac 计算（原先五处手工拼装收敛为一份实现）
        val hmacer = BlockHmac(hmacKey64)
        try {
            var blockIndex = 0L
            var offset = 0

            while (offset < data.size) {
                val currentBlockSize = minOf(blockSize, data.size - offset)
                val sizeBytes = LittleEndianUtil.intTo4Bytes(currentBlockSize)
                // 官方规范：块 HMAC 数据 = LE64(块索引) ‖ 4 字节块长 ‖ 块数据（对齐 KeePassXC）
                val hmac = hmacer.compute(blockIndex, currentBlockSize, data, offset, currentBlockSize)

                outputStream.write(hmac)
                outputStream.write(sizeBytes)
                outputStream.write(data, offset, currentBlockSize)

                blockIndex++
                offset += currentBlockSize
            }

            // 写入终止块 (blockSize = 0)
            val termSizeBytes = LittleEndianUtil.intTo4Bytes(0)
            outputStream.write(hmacer.compute(blockIndex, 0, EMPTY_BLOCK_DATA, 0, 0))
            outputStream.write(termSizeBytes)
            outputStream.flush()
        } finally {
            hmacer.wipe()
        }
    }

    /**
     * 从输入流中读取并校验全部 HMAC 数据块，返回组装后的完整数据。
     *
     * D20：块/终止块 HMAC 不符一律抛 [KdbxCorruptFileException]（完整性失败），
     * 与流式路径完全一致；比较使用 [java.security.MessageDigest.isEqual]（非常时比较）。
     * F-16：原 `contentEquals` 为非常时比较，已随本路径一并替换。
     */
    fun readAll(inputStream: InputStream, hmacKey64: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        val hmacer = BlockHmac(hmacKey64)
        try {
            var blockIndex = 0L

            while (true) {
                val expectedHmac = try {
                    LittleEndianUtil.readBytes(inputStream, 32)
                } catch (e: EOFException) {
                    throw KdbxCorruptFileException("HMAC 块读取意外中断", e)
                }

                val blockSize = LittleEndianUtil.readInt(inputStream)
                if (blockSize < 0) {
                    throw KdbxCorruptFileException("非法的负数块大小: $blockSize")
                }
                // ISSUE-P3-268：块长仅受 Int32 域约束（负数拒绝），无上限——对齐官方读侧语义；
                // 声明超长而流不满足时由 readBytes 的 EOF 路径兜底为损坏文件。

                if (blockSize == 0) {
                    // 终止块校验。
                    // D20：数据块/终止块 HMAC 不符 = **完整性失败**，不得误判为凭据错误。
                    // 依据官方三态语义（KeePass 2.61.1 KdbxFile.Read.cs:150/157 与
                    // HmacBlockStream.cs:233/264）：头部 SHA 不符 → InvalidDataException(FileHeaderCorrupted)；
                    // 头部 HMAC 不符 → InvalidCompositeKeyException（凭据错误）；**数据块/终止块 HMAC 不符
                    // → InvalidDataException(FileCorrupted)**。此处已越过头部认证，凭据必然正确，
                    // 故只能归因为「文件损坏或被篡改」。
                    val actualHmac = hmacer.compute(blockIndex, 0, EMPTY_BLOCK_DATA, 0, 0)
                    if (!java.security.MessageDigest.isEqual(actualHmac, expectedHmac)) {
                        throw KdbxCorruptFileException("HMAC 终止块校验失败：文件已损坏或被篡改（完整性认证未通过）")
                    }
                    break
                }

                // ISSUE-P3-268：块长无上限，读侧不得因「声明超大块」提前拒绝；
                // 底层流数据不足时仍以类型化异常 fail-closed（与上方 HMAC 读取处同型）。
                val blockData = try {
                    LittleEndianUtil.readBytes(inputStream, blockSize)
                } catch (e: EOFException) {
                    throw KdbxCorruptFileException("HMAC 块读取意外中断", e)
                }
                val actualHmac = hmacer.compute(blockIndex, blockSize, blockData, 0, blockData.size)
                if (!java.security.MessageDigest.isEqual(actualHmac, expectedHmac)) {
                    // D20：同上——块 HMAC 失败属完整性失败。此读取条目（readAll）无生产调用方，
                    // 但异常语义必须与流式路径一致，避免离线工具把篡改误报为「主密码错误」。
                    throw KdbxCorruptFileException("HMAC 块 #$blockIndex 校验失败：文件已损坏或被篡改（完整性认证未通过）")
                }

                bos.write(blockData)
                blockIndex++
            }

            return bos.toByteArray()
        } finally {
            hmacer.wipe()
        }
    }
}

/**
 * HMAC 块流读取侧（流式）：逐块校验 HMAC 后才交付明文，不在内存中积压整条密文。
 * 读取到 size=0 的终止块后返回 EOF；[verifyEndOfStream] 用于强制确认终止块已被校验
 * （上层数据流可能提前停止拉取，例如 GZip 解压到尾部即终止）。
 *
 * TASK-01 整改：`terminated` 仅在终止块 HMAC 校验**通过后**置位；校验失败时先记录
 * [terminalValidationFailed] 再抛出。原因：javax.crypto.CipherInputStream 会把底层流
 * 抛出的 IOException（含本流的完整性异常）吞掉并伪装为 EOF，若解析期间（GZip 预读）
 * 恰好拉取到终止块，异常将无法抵达上层——权威裁决以 [verifyEndOfStream] 为准。
 *
 * D20 异常语义：本流始终运行在**头部 HMAC 已通过之后**，此时凭据已被证明正确，
 * 因此块/终止块 HMAC 校验失败一律抛 [KdbxCorruptFileException]（完整性失败，
 * 对齐官方 HmacBlockStream.cs 抛 `InvalidDataException(FileCorrupted)` 的语义），
 * **绝不抛凭据异常**——否则上层会把「文件被篡改」显示为「主密码错误」并计入解锁失败节流，
 * 且用户永远无法得知库已被篡改。
 */
class HmacBlockInputStream(
    private val source: InputStream,
    hmacKey64: ByteArray
) : InputStream() {

    /** ISSUE-P3-37：块摘要计算器（与单个流实例一一对应，非线程安全）。 */
    private val hmacer = BlockHmac(hmacKey64)

    private var blockIndex = 0L
    private var currentBlock = ByteArray(0)
    private var position = 0

    /** 终止块已被消费且 HMAC 校验通过 */
    private var terminated = false

    /**
     * 终止块 HMAC 校验失败（TASK-01 整改）。
     * 校验异常可能被解密流（CipherInputStream 吞底层异常伪装 EOF）拦截，
     * 该标记确保 [verifyEndOfStream] 作为权威检查点时必能重放失败，fail-closed。
     */
    private var terminalValidationFailed = false

    override fun read(): Int {
        if (!ensureDataAvailable()) return -1
        return currentBlock[position++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!ensureDataAvailable()) return -1
        val count = minOf(len, currentBlock.size - position)
        System.arraycopy(currentBlock, position, b, off, count)
        position += count
        return count
    }

    /**
     * 确认流已正常走到终止块并完成其 HMAC 校验；若上层数据流提前停止拉取，则继续消费并校验剩余块。
     */
    fun verifyEndOfStream() {
        // TASK-01 整改（flaky 根因修复）：终止块 HMAC 校验失败的异常可能被解密流
        // （javax.crypto.CipherInputStream 会把底层流异常吞掉并伪装为 EOF）拦截，
        // 导致本方法把「未校验通过」误判为「已通过」。此处作为权威检查点，
        // 必须重放已记录的校验失败，确保篡改文件在任何路径下都 fail-closed。
        if (terminalValidationFailed) {
            throw KdbxCorruptFileException("HMAC 终止块校验失败：文件已损坏或被篡改（完整性认证未通过）")
        }
        while (!terminated) {
            if (!loadNextBlock()) break
        }
        if (!terminated) {
            throw KdbxCorruptFileException("HMAC 块流在终止块之前意外中断")
        }
    }

    /**
     * 消费并返回下一个完整数据块的字节（到达终止块时返回 null）。
     * 仅供 [com.keepasskey.database.file.KdbxFile] 在流起始处做旧派生探针裁决：
     * 取出的块必须由调用方经 SequenceInputStream 原样回填到解密流之前，且仅允许调用一次。
     */
    fun readBlock(): ByteArray? {
        if (!loadNextBlock()) return null
        val block = currentBlock
        currentBlock = ByteArray(0)
        position = 0
        return block
    }

    private fun ensureDataAvailable(): Boolean {
        while (position >= currentBlock.size) {
            if (!loadNextBlock()) return false
        }
        return true
    }

    private fun loadNextBlock(): Boolean {
        if (terminated) return false

        val expectedHmac = try {
            LittleEndianUtil.readBytes(source, HmacBlockStream.HMAC_SIZE)
        } catch (e: EOFException) {
            throw KdbxCorruptFileException("HMAC 块读取意外中断", e)
        }

        val blockSize = LittleEndianUtil.readInt(source)
        if (blockSize < 0) {
            throw KdbxCorruptFileException("非法的负数块大小: $blockSize")
        }
        // ISSUE-P3-268：块长仅受 Int32 域约束（负数拒绝），无上限——对齐官方读侧语义；
        // 声明超长而底层流不满足时由 readBytes 的 EOF 路径兜底为损坏文件。

        if (blockSize == 0) {
            val actualHmac = hmacer.compute(blockIndex, 0, EMPTY_BLOCK_DATA, 0, 0)
            if (!java.security.MessageDigest.isEqual(actualHmac, expectedHmac)) {
                // TASK-01 整改：先记账再抛出。绝不提前置 terminated=true——
                // 若该异常被中间层（如 CipherInputStream）吞掉，[verifyEndOfStream]
                // 必须能凭 terminalValidationFailed 重放失败，杜绝篡改文件静默通过。
                // D20：重放的异常类型同时改为完整性失败（[KdbxCorruptFileException]），
                // 与首次抛出保持一致——本流已越过头部认证，凭据必然正确。
                terminalValidationFailed = true
                throw KdbxCorruptFileException("HMAC 终止块校验失败：文件已损坏或被篡改（完整性认证未通过）")
            }
            terminated = true
            return false
        }

        // ISSUE-P3-268：块长无上限，读侧不得因「声明超大块」提前拒绝；
        // 底层流数据不足时仍以类型化异常 fail-closed（与上方 HMAC 读取处同型）。
        val blockData = try {
            LittleEndianUtil.readBytes(source, blockSize)
        } catch (e: EOFException) {
            throw KdbxCorruptFileException("HMAC 块读取意外中断", e)
        }
        val actualHmac = hmacer.compute(blockIndex, blockSize, blockData, 0, blockData.size)
        if (!java.security.MessageDigest.isEqual(actualHmac, expectedHmac)) {
            // D20：数据块 HMAC 不符属**完整性失败**，不是凭据错误。官方三态语义见
            // KeePass 2.61.1 HmacBlockStream.cs:233（官方抛 InvalidDataException(FileCorrupted)）：
            // 头部 HMAC 已在上层通过，凭据已被证明正确，故不得让上层据此计入解锁失败节流。
            throw KdbxCorruptFileException("HMAC 块 #$blockIndex 校验失败：文件已损坏或被篡改（完整性认证未通过）")
        }

        currentBlock = blockData
        position = 0
        blockIndex++
        return true
    }

    /**
     * 关闭本流：仅清零 [hmacer] 持有的派生中间量，**不关闭底层流**
     * （底层多为 `NonClosingInputStream`，其关闭本就是空操作；此处保持同一语义）。
     */
    override fun close() {
        hmacer.wipe()
    }
}

/**
 * HMAC 块流写出侧（流式）：按块缓冲、写满即校验并落盘，避免整库密文驻留内存。
 * [close] 冲刷残余块并写入终止块；[flush] 仅透传底层冲刷。
 */
class HmacBlockOutputStream(
    private val sink: OutputStream,
    hmacKey64: ByteArray,
    private val blockSize: Int = HmacBlockStream.DEFAULT_BLOCK_SIZE
) : OutputStream() {

    /** ISSUE-P3-37：块摘要计算器（与单个流实例一一对应，非线程安全）。 */
    private val hmacer = BlockHmac(hmacKey64)

    private val buffer = ByteArray(blockSize)
    private var filled = 0
    private var blockIndex = 0L
    private var closed = false

    override fun write(value: Int) {
        if (filled == blockSize) {
            flushBlock()
        }
        buffer[filled++] = value.toByte()
    }

    override fun write(data: ByteArray, off: Int, len: Int) {
        var offset = off
        var remaining = len
        while (remaining > 0) {
            if (filled == blockSize) {
                flushBlock()
            }
            val count = minOf(blockSize - filled, remaining)
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
            flushBlock()

            val termSizeBytes = LittleEndianUtil.intTo4Bytes(0)
            sink.write(hmacer.compute(blockIndex, 0, EMPTY_BLOCK_DATA, 0, 0))
            sink.write(termSizeBytes)
            sink.flush()
        } finally {
            // 缓冲持有明文，收尾时清零；同时清零摘要器的派生中间量
            buffer.fill(0)
            filled = 0
            hmacer.wipe()
        }
    }

    private fun flushBlock() {
        if (filled == 0) return
        val sizeBytes = LittleEndianUtil.intTo4Bytes(filled)
        // 写满的块直接引用缓冲（其清零由 close() 的 finally 承担）；末尾残块才拷贝切片。
        // ISSUE-P3-140：该切片是**明文的独立副本**（`buffer.copyOf(filled)` 不受 close() 的
        // `buffer.fill(0)` 影响），写出后必须显式清零——否则这段明文随局部变量出栈后
        // 静默留存至 GC，属「补一处、漏一处」的同型缺口（对齐 ISSUE-P3-96 的处理口径）。
        val isFullBlock = filled == blockSize
        val chunk = if (isFullBlock) buffer else buffer.copyOf(filled)
        try {
            sink.write(hmacer.compute(blockIndex, filled, chunk, 0, chunk.size))
            sink.write(sizeBytes)
            sink.write(chunk)
        } finally {
            // 满块时 chunk 即 buffer，清零会波及缓冲本身——那由 close() 收口，此处只清副本
            if (!isFullBlock) chunk.fill(0)
        }
        blockIndex++
        filled = 0
    }
}
