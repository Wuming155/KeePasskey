package com.keepasskey.database.io

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * KDBX 二进制 Little-Endian 编解码工具
 */
object LittleEndianUtil {

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

    fun readBytes(inputStream: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var totalRead = 0
        while (totalRead < length) {
            val read = inputStream.read(buffer, totalRead, length - totalRead)
            if (read < 0) {
                throw EOFException("期望读取 $length 字节，实际仅读取 $totalRead 字节")
            }
            totalRead += read
        }
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
