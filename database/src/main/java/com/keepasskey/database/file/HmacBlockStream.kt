package com.keepasskey.database.file

import com.keepasskey.crypto.hash.HashUtil
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.exception.KdbxInvalidCredentialsException
import com.keepasskey.database.io.LittleEndianUtil
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * KDBX 4 HMAC 认证块流（HmacBlockStream）。
 * 针对每个数据块进行独立 HMAC-SHA256 签名校验，提供防篡改验证与加密块完整性。
 * 流式形态（HmacBlockInputStream/HmacBlockOutputStream）为 KdbxFile.load/save 主管线专用，
 * 保留官方语义——「边解密边校验，错误尽早暴露」，且不整体物化密文。
 * 全量形态（readAll/writeAll）会将整条数据物化进内存，仅限单元测试与离线工具使用；
 * 生产代码严禁调用，以免退回整库物化的内存路径。
 */
object HmacBlockStream {

    const val DEFAULT_BLOCK_SIZE = 1024 * 1024 // 1 MB

    /// HMAC-SHA256 签名长度（字节）
    const val HMAC_SIZE = 32

    /**
     * 计算指定块索引的 64 字节 HMAC 密钥
     * blockKey = SHA-512(blockIndex (Little Endian 8 bytes) || hmacKey64)
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
        var blockIndex = 0L
        var offset = 0

        while (offset < data.size) {
            val currentBlockSize = minOf(blockSize, data.size - offset)
            val chunk = data.copyOfRange(offset, offset + currentBlockSize)

            val blockKey = HmacBlockStream.computeBlockKey(blockIndex, hmacKey64)
            val sizeBytes = LittleEndianUtil.intTo4Bytes(currentBlockSize)
            val blockHmac = HashUtil.hmacSha256(blockKey, sizeBytes, chunk)

            outputStream.write(blockHmac)
            outputStream.write(sizeBytes)
            outputStream.write(chunk)

            blockIndex++
            offset += currentBlockSize
        }

        // 写入终止块 (blockSize = 0)
        val termBlockKey = HmacBlockStream.computeBlockKey(blockIndex, hmacKey64)
        val termSizeBytes = LittleEndianUtil.intTo4Bytes(0)
        val termHmac = HashUtil.hmacSha256(termBlockKey, termSizeBytes)

        outputStream.write(termHmac)
        outputStream.write(termSizeBytes)
        outputStream.flush()
    }

    /**
     * 从输入流中读取并校验全部 HMAC 数据块，返回组装后的完整数据
     */
    fun readAll(inputStream: InputStream, hmacKey64: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
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

            val blockKey = HmacBlockStream.computeBlockKey(blockIndex, hmacKey64)
            val sizeBytes = LittleEndianUtil.intTo4Bytes(blockSize)

            if (blockSize == 0) {
                // 终止块校验
                val actualHmac = HashUtil.hmacSha256(blockKey, sizeBytes)
                if (!actualHmac.contentEquals(expectedHmac)) {
                    throw KdbxInvalidCredentialsException("HMAC 终止块校验失败：主密码错误或文件末尾被篡改")
                }
                break
            }

            val blockData = LittleEndianUtil.readBytes(inputStream, blockSize)
            val actualHmac = HashUtil.hmacSha256(blockKey, sizeBytes, blockData)
            if (!actualHmac.contentEquals(expectedHmac)) {
                throw KdbxInvalidCredentialsException("HMAC 块 #$blockIndex 校验失败：主密码错误或数据块被篡改")
            }

            bos.write(blockData)
            blockIndex++
        }

        return bos.toByteArray()
    }
}

/**
 * HMAC 块流读取侧（流式）：逐块校验 HMAC 后才交付明文，不在内存中积压整条密文。
 * 读取到 size=0 的终止块后返回 EOF；[verifyEndOfStream] 用于强制确认终止块已被校验
 * （上层数据流可能提前停止拉取，例如 GZip 解压到尾部即终止）。
 */
class HmacBlockInputStream(
    private val source: InputStream,
    private val hmacKey64: ByteArray
) : InputStream() {

    private var blockIndex = 0L
    private var currentBlock = ByteArray(0)
    private var position = 0
    private var terminated = false

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

        val blockKey = HmacBlockStream.computeBlockKey(blockIndex, hmacKey64)
        val sizeBytes = LittleEndianUtil.intTo4Bytes(blockSize)

        if (blockSize == 0) {
            terminated = true
            val actualHmac = HashUtil.hmacSha256(blockKey, sizeBytes)
            if (!actualHmac.contentEquals(expectedHmac)) {
                throw KdbxInvalidCredentialsException("HMAC 终止块校验失败：主密码错误或文件末尾被篡改")
            }
            return false
        }

        val blockData = LittleEndianUtil.readBytes(source, blockSize)
        val actualHmac = HashUtil.hmacSha256(blockKey, sizeBytes, blockData)
        if (!actualHmac.contentEquals(expectedHmac)) {
            throw KdbxInvalidCredentialsException("HMAC 块 #$blockIndex 校验失败：主密码错误或数据块被篡改")
        }

        currentBlock = blockData
        position = 0
        blockIndex++
        return true
    }
}

/**
 * HMAC 块流写出侧（流式）：按块缓冲、写满即校验并落盘，避免整库密文驻留内存。
 * [close] 冲刷残余块并写入终止块；[flush] 仅透传底层冲刷。
 */
class HmacBlockOutputStream(
    private val sink: OutputStream,
    private val hmacKey64: ByteArray,
    private val blockSize: Int = HmacBlockStream.DEFAULT_BLOCK_SIZE
) : OutputStream() {

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
        flushBlock()

        val termBlockKey = HmacBlockStream.computeBlockKey(blockIndex, hmacKey64)
        val termSizeBytes = LittleEndianUtil.intTo4Bytes(0)
        sink.write(HashUtil.hmacSha256(termBlockKey, termSizeBytes))
        sink.write(termSizeBytes)
        sink.flush()
    }

    private fun flushBlock() {
        if (filled == 0) return
        val blockKey = HmacBlockStream.computeBlockKey(blockIndex, hmacKey64)
        val sizeBytes = LittleEndianUtil.intTo4Bytes(filled)
        // 写满的块直接引用缓冲，末尾残块才拷贝切片
        val chunk = if (filled == blockSize) buffer else buffer.copyOf(filled)
        sink.write(HashUtil.hmacSha256(blockKey, sizeBytes, chunk))
        sink.write(sizeBytes)
        sink.write(chunk)
        blockIndex++
        filled = 0
    }
}
