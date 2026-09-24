package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.database.exception.KdbxCorruptFileException
import org.junit.Assert.assertThrows
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * ISSUE-P3-311 项 1：内层随机流**密钥字段存在性断言**。
 *
 * 缺陷形态：`streamKey` 默认 `ByteArray(64)` 且解析侧只查上界——流算法为 Salsa20/ChaCha20
 * 而密钥字段缺失时静默以全零密钥解密受保护字段，且无任何类型化诊断。
 * 整改：END 字段处断言「ID∈{Salsa20, ChaCha20} ⇒ 密钥字段必须出现过」，缺失抛类型化异常。
 */
class InnerHeaderStreamKeyPresenceTest {

    /** 手工拼装内层 Header 字节（fieldId + 小端 Int32 长度 + 内容，END 结尾） */
    private fun buildInnerHeader(withKeyField: Boolean, streamId: Int = KdbxConstants.InnerRandomStream.CHACHA20): ByteArray {
        val out = ByteArrayOutputStream()
        fun field(id: Byte, data: ByteArray) {
            out.write(id.toInt() and 0xFF)
            val len = data.size
            out.write(len and 0xFF)
            out.write((len ushr 8) and 0xFF)
            out.write((len ushr 16) and 0xFF)
            out.write((len ushr 24) and 0xFF)
            out.write(data)
        }
        field(KdbxConstants.InnerHeaderFieldId.INNER_RANDOM_STREAM_ID, byteArrayOf(
            streamId.toByte(), 0, 0, 0
        ))
        if (withKeyField) {
            field(KdbxConstants.InnerHeaderFieldId.INNER_RANDOM_STREAM_KEY, ByteArray(64) { it.toByte() })
        }
        field(KdbxConstants.InnerHeaderFieldId.END, ByteArray(0))
        return out.toByteArray()
    }

    @Test
    fun `ChaCha20 缺密钥字段时解析抛类型化异常而非静默全零`() {
        assertThrows(KdbxCorruptFileException::class.java) {
            InnerHeader.deserialize(ByteArrayInputStream(buildInnerHeader(withKeyField = false)))
        }
    }

    @Test
    fun `Salsa20 缺密钥字段时同样抛类型化异常`() {
        assertThrows(KdbxCorruptFileException::class.java) {
            InnerHeader.deserialize(
                ByteArrayInputStream(
                    buildInnerHeader(withKeyField = false, streamId = KdbxConstants.InnerRandomStream.SALSA20)
                )
            )
        }
    }

    @Test
    fun `密钥字段存在时解析正常且逐字节一致（既有语义不变）`() {
        val bytes = buildInnerHeader(withKeyField = true, streamId = KdbxConstants.InnerRandomStream.CHACHA20)
        val header = InnerHeader.deserialize(ByteArrayInputStream(bytes))
        assertArrayEquals(ByteArray(64) { it.toByte() }, header.innerRandomStreamKey)
    }

    @Test
    fun `算法标识为 None 时密钥字段可缺省（合法形态不受断言误拒）`() {
        val bytes = buildInnerHeader(
            withKeyField = false,
            streamId = KdbxConstants.InnerRandomStream.NONE
        )
        val header = InnerHeader.deserialize(ByteArrayInputStream(bytes))
        assertArrayEquals(ByteArray(64), header.innerRandomStreamKey)
    }
}
