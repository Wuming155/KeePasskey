package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.io.LittleEndianUtil
import java.io.ByteArrayOutputStream
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

        /**
         * 内层 Header 单字段长度安全上限（64 MiB）。
         * BINARY 字段承载附件二进制池，上限须远超一切正常附件体积；
         * 其余字段按各自语义另行收窄（见 [INNER_RANDOM_STREAM_ID_FIELD_SIZE] /
         * [MAX_INNER_RANDOM_STREAM_KEY_BYTES]）。内层数据虽位于 HMAC 认证之后，
         * 长度字段仍按不可信输入对待（P0-5 纵深防御）。
         */
        internal const val MAX_INNER_FIELD_BYTES = 64 * 1024 * 1024

        /** InnerRandomStreamID 字段合法长度（小端 Int32，官方规范固定 4 字节） */
        private const val INNER_RANDOM_STREAM_ID_FIELD_SIZE = 4

        /** InnerRandomStreamKey 字段长度安全上限（官方写入 64 字节） */
        private const val MAX_INNER_RANDOM_STREAM_KEY_BYTES = 1024

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
                if (fieldIdByte < 0) throw KdbxCorruptFileException("意外到达内层 Header 末尾")
                val fieldId = fieldIdByte.toByte()

                val fieldLen = LittleEndianUtil.readInt(inputStream)
                // P0-5：长度字段不直接驱动分配，负数（0xFFFFFFFF）或超限值按损坏文件拒绝
                if (fieldLen < 0 || fieldLen > MAX_INNER_FIELD_BYTES) {
                    throw KdbxCorruptFileException(
                        "内层 Header 字段长度非法或超过安全上限: fieldId=$fieldIdByte, " +
                                "length=$fieldLen（允许 0 ~ $MAX_INNER_FIELD_BYTES）"
                    )
                }
                val fieldData = LittleEndianUtil.readBytes(inputStream, fieldLen, MAX_INNER_FIELD_BYTES)

                if (fieldId == KdbxConstants.InnerHeaderFieldId.END) {
                    break
                }

                when (fieldId) {
                    KdbxConstants.InnerHeaderFieldId.INNER_RANDOM_STREAM_ID -> {
                        if (fieldData.size != INNER_RANDOM_STREAM_ID_FIELD_SIZE) {
                            throw KdbxCorruptFileException(
                                "InnerRandomStreamID 字段长度非法: ${fieldData.size}" +
                                        "（期望 $INNER_RANDOM_STREAM_ID_FIELD_SIZE）"
                            )
                        }
                        streamId = LittleEndianUtil.bytesToInt(fieldData)
                    }
                    KdbxConstants.InnerHeaderFieldId.INNER_RANDOM_STREAM_KEY -> {
                        if (fieldData.size > MAX_INNER_RANDOM_STREAM_KEY_BYTES) {
                            throw KdbxCorruptFileException(
                                "InnerRandomStreamKey 字段长度非法或超过安全上限: ${fieldData.size}" +
                                        "（允许 0 ~ $MAX_INNER_RANDOM_STREAM_KEY_BYTES）"
                            )
                        }
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
