package com.keepasskey.database.io

import com.keepasskey.database.exception.KdbxCorruptFileException
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * KDBX 二进制 Little-Endian 编解码工具
 */
object LittleEndianUtil {

    /**
     * readBytes 默认安全上限（16 MiB，约为官方 HMAC 块大小的 16 倍）。
     * 文件中的长度字段在 SHA-256 / HMAC 认证通过前一律视为不可信输入，
     * 严禁未设上限的长度直接驱动 ByteArray 分配（P0-5：恶意长度 0x7FFFFFFF 造成
     * 分配期 OOM，0xFFFFFFFF 转为负数造成 NegativeArraySizeException）。
     * 调用方应按字段语义进一步收窄（如外层 Header 单字段 1 MiB、变体字典 value 1 MiB）。
     */
    const val DEFAULT_MAX_READ_BYTES: Int = 16 * 1024 * 1024

    fun readInt(inputStream: InputStream): Int {
        val b0 = inputStream.read()
        val b1 = inputStream.read()
        val b2 = inputStream.read()
        val b3 = inputStream.read()
        if ((b0 or b1 or b2 or b3) < 0) {
            throw EOFException("意外到达流末尾")
        }
        return (b0 and 0xFF) or
                ((b1 and 0xFF) shl 8) or
                ((b2 and 0xFF) shl 16) or
                ((b3 and 0xFF) shl 24)
    }

    fun readShort(inputStream: InputStream): Short {
        val b0 = inputStream.read()
        val b1 = inputStream.read()
        if ((b0 or b1) < 0) {
            throw EOFException("意外到达流末尾")
        }
        return ((b0 and 0xFF) or ((b1 and 0xFF) shl 8)).toShort()
    }

    fun readLong(inputStream: InputStream): Long {
        val bytes = readBytes(inputStream, 8)
        return bytesToLong(bytes)
    }

    /**
     * 从输入流读取指定长度字节。
     * 长度值来源于文件内容，在认证通过前不可信：负数（如 0xFFFFFFFF 的符号扩展）
     * 或超过 [maxLength] 的长度一律按损坏文件拒绝，绝不进入 ByteArray 分配。
     *
     * @throws KdbxCorruptFileException length 为负或超过安全上限
     * @throws EOFException 流中实际数据不足 length 字节
     */
    fun readBytes(inputStream: InputStream, length: Int, maxLength: Int = DEFAULT_MAX_READ_BYTES): ByteArray {
        if (length < 0 || length > maxLength) {
            throw KdbxCorruptFileException("读取字段长度非法或超过安全上限: length=$length")
        }
        val buffer = ByteArray(length)
        // readFully 语义：「读满 length 字节，否则抛 EOFException」——与手写循环逐字等价。
        // 注意不得用 use{} 包装：DataInputStream.close 会传导关闭底层流，破坏 KDBX 流式解析。
        DataInputStream(inputStream).readFully(buffer)
        return buffer
    }

    fun writeInt(outputStream: OutputStream, value: Int) {
        outputStream.write(value and 0xFF)
        outputStream.write((value ushr 8) and 0xFF)
        outputStream.write((value ushr 16) and 0xFF)
        outputStream.write((value ushr 24) and 0xFF)
    }

    fun writeShort(outputStream: OutputStream, value: Short) {
        outputStream.write(value.toInt() and 0xFF)
        outputStream.write((value.toInt() ushr 8) and 0xFF)
    }

    fun writeLong(outputStream: OutputStream, value: Long) {
        outputStream.write(longTo8Bytes(value))
    }

    fun intTo4Bytes(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 24) and 0xFF).toByte()
        )
    }

    fun longTo8Bytes(value: Long): ByteArray {
        val bytes = ByteArray(8)
        for (i in 0 until 8) {
            bytes[i] = ((value ushr (i * 8)) and 0xFF).toByte()
        }
        return bytes
    }

    fun bytesToInt(bytes: ByteArray, offset: Int = 0): Int {
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    fun bytesToLong(bytes: ByteArray, offset: Int = 0): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = result or ((bytes[offset + i].toLong() and 0xFFL) shl (i * 8))
        }
        return result
    }
}
