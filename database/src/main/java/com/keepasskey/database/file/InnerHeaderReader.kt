package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.security.BinaryStore
import com.keepasskey.core.security.BinaryStorePolicy
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.io.LittleEndianUtil
import java.io.InputStream

/**
 * 内层 Header 的逐字段读取器（ISSUE-P3-188 自 `InnerHeader.deserialize` 下沉；
 * ISSUE-P2-311 批再自 `InnerHeader` 内部类外置为同包协作类，函数体逐行搬运——
 * `InnerHeader.kt` 触及 tier1(>500) 规模闸门，拆分口径同 §308 先例）。
 *
 * 把原函数内的四个可变量（流算法 id / 流密钥 / 二进制池 / 池累计字节）收拢为实例状态，
 * 使每类字段的守卫（长度上限、条目数与累计字节封顶、落盘分流）各自成函数；
 * 判定口径、异常文案与读取时序与拆分前**逐字一致**。
 */
internal class InnerHeaderReader(
    private val inputStream: InputStream,
    private val binaryStore: BinaryStore?,
    private val spillThresholdBytes: Long
) {
    var streamId = KdbxConstants.InnerRandomStream.CHACHA20
        private set
    var streamKey = ByteArray(INNER_RANDOM_STREAM_KEY_SIZE)
        private set
    val binaries = mutableListOf<InnerHeader.BinaryItem>()

    /** Wave 12 解析炸弹防线：二进制池累计字节数封顶（条目数封顶见 [enforceBinaryPoolEntryLimit]） */
    private var binaryPoolTotalBytes = 0L

    fun toHeader(): InnerHeader = InnerHeader(
        innerRandomStreamId = streamId,
        innerRandomStreamKey = streamKey,
        binaries = binaries
    )

    fun readFields() {
        while (true) {
            val fieldIdByte = inputStream.read()
            if (fieldIdByte < 0) throw KdbxCorruptFileException("意外到达内层 Header 末尾")
            val fieldId = fieldIdByte.toByte()

            val fieldLen = LittleEndianUtil.readInt(inputStream)
            // P0-5：长度字段不直接驱动分配，负数（0xFFFFFFFF）或超限值按损坏文件拒绝
            if (fieldLen < 0 || fieldLen > InnerHeader.MAX_INNER_FIELD_BYTES) {
                throw KdbxCorruptFileException(
                    "内层 Header 字段长度非法或超过安全上限: fieldId=$fieldIdByte, " +
                            "length=$fieldLen（允许 0 ~ ${InnerHeader.MAX_INNER_FIELD_BYTES}）"
                )
            }

            if (fieldId == KdbxConstants.InnerHeaderFieldId.END) {
                break
            }

            // 大附件字段：长度已知，直接流式落盘，不整份物化（ISSUE-P2-24）
            if (!readBinaryFieldAsStream(fieldId, fieldLen)) {
                acceptField(fieldId, LittleEndianUtil.readBytes(inputStream, fieldLen, InnerHeader.MAX_INNER_FIELD_BYTES))
            }
        }
    }

    /** 命中「BINARY 且长度大于标志位」时流式消费该字段并返回 true，否则原样交回调用方 */
    private fun readBinaryFieldAsStream(fieldId: Byte, fieldLen: Int): Boolean {
        if (fieldId != KdbxConstants.InnerHeaderFieldId.BINARY ||
            fieldLen <= InnerHeader.FLAGS_FIELD_BYTES.toInt()
        ) return false

        enforceBinaryPoolEntryLimit(binaries.size)
        val flag = readFlagByte(inputStream)
        val payloadLen = fieldLen - InnerHeader.FLAGS_FIELD_BYTES.toInt()
        binaryPoolTotalBytes += payloadLen
        enforceBinaryPoolTotalLimit(binaryPoolTotalBytes)
        val store = binaryStore
        if (store != null &&
            BinaryStorePolicy.shouldSpill(payloadLen.toLong(), spillThresholdBytes)
        ) {
            val key = store.storeFromStream(inputStream, payloadLen.toLong())
            binaries.add(InnerHeader.BinaryItem(flag, store, key, payloadLen.toLong()))
        } else {
            val data = LittleEndianUtil.readBytes(inputStream, payloadLen, InnerHeader.MAX_INNER_FIELD_BYTES)
            binaries.add(InnerHeader.BinaryItem(flag, data))
        }
        return true
    }

    private fun acceptField(fieldId: Byte, fieldData: ByteArray) {
        when (fieldId) {
            KdbxConstants.InnerHeaderFieldId.INNER_RANDOM_STREAM_ID -> acceptStreamId(fieldData)
            KdbxConstants.InnerHeaderFieldId.INNER_RANDOM_STREAM_KEY -> acceptStreamKey(fieldData)
            KdbxConstants.InnerHeaderFieldId.BINARY -> if (fieldData.isNotEmpty()) acceptInlineBinary(fieldData)
        }
    }

    private fun acceptStreamId(fieldData: ByteArray) {
        if (fieldData.size != InnerHeader.INNER_RANDOM_STREAM_ID_FIELD_SIZE) {
            throw KdbxCorruptFileException(
                "InnerRandomStreamID 字段长度非法: ${fieldData.size}" +
                        "（期望 ${InnerHeader.INNER_RANDOM_STREAM_ID_FIELD_SIZE}）"
            )
        }
        streamId = LittleEndianUtil.bytesToInt(fieldData)
    }

    private fun acceptStreamKey(fieldData: ByteArray) {
        if (fieldData.size > InnerHeader.MAX_INNER_RANDOM_STREAM_KEY_BYTES) {
            throw KdbxCorruptFileException(
                "InnerRandomStreamKey 字段长度非法或超过安全上限: ${fieldData.size}" +
                        "（允许 0 ~ ${InnerHeader.MAX_INNER_RANDOM_STREAM_KEY_BYTES}）"
            )
        }
        streamKey = fieldData
    }

    private fun acceptInlineBinary(fieldData: ByteArray) {
        enforceBinaryPoolEntryLimit(binaries.size)
        val flag = fieldData[0]
        val data = fieldData.copyOfRange(1, fieldData.size)
        binaryPoolTotalBytes += data.size
        enforceBinaryPoolTotalLimit(binaryPoolTotalBytes)
        binaries.add(InnerHeader.BinaryItem(flag, data))
    }
}

private fun enforceBinaryPoolEntryLimit(currentCount: Int) {
    if (currentCount >= InnerHeader.MAX_BINARY_POOL_ENTRIES) {
        throw KdbxCorruptFileException(
            "二进制池条目数超出安全上限: ${currentCount + 1}" +
                    "（允许 ≤ ${InnerHeader.MAX_BINARY_POOL_ENTRIES}），疑似解析炸弹"
        )
    }
}

private fun enforceBinaryPoolTotalLimit(totalBytes: Long) {
    if (totalBytes > InnerHeader.MAX_BINARY_POOL_TOTAL_BYTES) {
        throw KdbxCorruptFileException(
            "二进制池累计字节超出安全上限（允许 ≤ ${InnerHeader.MAX_BINARY_POOL_TOTAL_BYTES}），疑似解析炸弹"
        )
    }
}

private fun readFlagByte(inputStream: InputStream): Byte {
    val value = inputStream.read()
    if (value < 0) throw java.io.EOFException("意外到达流末尾")
    return value.toByte()
}
