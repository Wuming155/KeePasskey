package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.database.io.LittleEndianUtil
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/**
 * KDBX 4 内层 Header（解密后、GZip 解压前）
 */
data class InnerHeader(
    val innerRandomStreamId: Int = KdbxConstants.InnerRandomStream.CHACHA20,
    val innerRandomStreamKey: ByteArray = ByteArray(64),
    val binaries: List<BinaryItem> = emptyList()
) {
    data class BinaryItem(
        val flags: Byte,
        val data: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BinaryItem) return false
            if (flags != other.flags) return false
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = flags.toInt()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InnerHeader) return false
        if (innerRandomStreamId != other.innerRandomStreamId) return false
        if (!innerRandomStreamKey.contentEquals(other.innerRandomStreamKey)) return false
        if (binaries != other.binaries) return false
        return true
    }

    override fun hashCode(): Int {
        var result = innerRandomStreamId
        result = 31 * result + innerRandomStreamKey.contentHashCode()
        result = 31 * result + binaries.hashCode()
        return result
    }

    fun serialize(outputStream: OutputStream) {
        // 字段 1: InnerRandomStreamID
        writeField(outputStream, KdbxConstants.InnerHeaderFieldId.INNER_RANDOM_STREAM_ID, LittleEndianUtil.intTo4Bytes(innerRandomStreamId))

        // 字段 2: InnerRandomStreamKey
        writeField(outputStream, KdbxConstants.InnerHeaderFieldId.INNER_RANDOM_STREAM_KEY, innerRandomStreamKey)

        // 字段 3: Binaries
        for (bin in binaries) {
            val bos = ByteArrayOutputStream()
            bos.write(bin.flags.toInt())
            bos.write(bin.data)
            writeField(outputStream, KdbxConstants.InnerHeaderFieldId.BINARY, bos.toByteArray())
        }

        // 字段 0: EndOfHeader
        writeField(outputStream, KdbxConstants.InnerHeaderFieldId.END, ByteArray(0))
    }

    companion object {
        private val secureRandom = SecureRandom()

        fun createDefault(): InnerHeader {
            val key = ByteArray(64)
            secureRandom.nextBytes(key)
            return InnerHeader(
                innerRandomStreamId = KdbxConstants.InnerRandomStream.CHACHA20,
                innerRandomStreamKey = key
            )
        }

        fun deserialize(inputStream: InputStream): InnerHeader {
            var streamId = KdbxConstants.InnerRandomStream.CHACHA20
            var streamKey = ByteArray(64)
            val binaries = mutableListOf<BinaryItem>()

            while (true) {
                val fieldIdByte = inputStream.read()
                if (fieldIdByte < 0) throw IOException("意外到达内层 Header 末尾")
                val fieldId = fieldIdByte.toByte()

                val fieldLen = LittleEndianUtil.readInt(inputStream)
                val fieldData = LittleEndianUtil.readBytes(inputStream, fieldLen)

                if (fieldId == KdbxConstants.InnerHeaderFieldId.END) {
                    break
                }

                when (fieldId) {
                    KdbxConstants.InnerHeaderFieldId.INNER_RANDOM_STREAM_ID -> {
                        streamId = LittleEndianUtil.bytesToInt(fieldData)
                    }
                    KdbxConstants.InnerHeaderFieldId.INNER_RANDOM_STREAM_KEY -> {
                        streamKey = fieldData
                    }
                    KdbxConstants.InnerHeaderFieldId.BINARY -> {
                        if (fieldData.isNotEmpty()) {
                            val flag = fieldData[0]
                            val data = fieldData.copyOfRange(1, fieldData.size)
                            binaries.add(BinaryItem(flag, data))
                        }
                    }
                }
            }

            return InnerHeader(
                innerRandomStreamId = streamId,
                innerRandomStreamKey = streamKey,
                binaries = binaries
            )
        }

        private fun writeField(outputStream: OutputStream, fieldId: Byte, data: ByteArray) {
            outputStream.write(fieldId.toInt())
            LittleEndianUtil.writeInt(outputStream, data.size)
            if (data.isNotEmpty()) {
                outputStream.write(data)
            }
        }
    }
}
