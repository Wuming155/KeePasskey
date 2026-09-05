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
 */
object HmacBlockStream {

    const val DEFAULT_BLOCK_SIZE = 1024 * 1024 // 1 MB

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
        blockSize: Int = DEFAULT_BLOCK_SIZE
    ) {
        var blockIndex = 0L
        var offset = 0

        while (offset < data.size) {
            val currentBlockSize = minOf(blockSize, data.size - offset)
            val chunk = data.copyOfRange(offset, offset + currentBlockSize)

            val blockKey = computeBlockKey(blockIndex, hmacKey64)
            val sizeBytes = LittleEndianUtil.intTo4Bytes(currentBlockSize)
            val blockHmac = HashUtil.hmacSha256(blockKey, sizeBytes, chunk)

            outputStream.write(blockHmac)
            outputStream.write(sizeBytes)
            outputStream.write(chunk)

            blockIndex++
            offset += currentBlockSize
        }

        // 写入终止块 (blockSize = 0)
        val termBlockKey = computeBlockKey(blockIndex, hmacKey64)
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

            val blockKey = computeBlockKey(blockIndex, hmacKey64)
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
