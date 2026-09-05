package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.io.LittleEndianUtil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * P0-5 整改回归：内层 Header 字段长度安全边界。
 * fieldLen（负数 / 0x7FFFFFFF / 超 64 MiB 上限）在 ByteArray 分配前以类型化
 * [KdbxCorruptFileException] 拒绝；InnerRandomStreamID / Key 按官方语义收窄。
 */
class InnerHeaderSecurityTest {

    /** 构造内层 Header 字节流；terminate=false 模拟流提前截断 */
    private fun innerHeaderBytes(fields: List<Triple<Int, Int, ByteArray>>, terminate: Boolean = true): ByteArray {
        val bos = ByteArrayOutputStream()
        for ((fieldId, declaredLength, data) in fields) {
            bos.write(fieldId)
            LittleEndianUtil.writeInt(bos, declaredLength)
            bos.write(data)
        }
        if (terminate) {
            bos.write(KdbxConstants.InnerHeaderFieldId.END.toInt())
            LittleEndianUtil.writeInt(bos, 0)
        }
        return bos.toByteArray()
    }

    /** 正常字段：声明长度与实际数据一致 */
    private fun field(fieldId: Byte, data: ByteArray): Triple<Int, Int, ByteArray> =
        Triple(fieldId.toInt(), data.size, data)

    private fun deserialize(bytes: ByteArray) =
        InnerHeader.deserialize(ByteArrayInputStream(bytes))

    // ================= P0-5：fieldLen 边界 =================

    @Test
    fun `负数 fieldLen 被类型化异常拒绝而非 NegativeArraySizeException`() {
        val bytes = innerHeaderBytes(
            listOf(Triple(KdbxConstants.InnerHeaderFieldId.INNER_RANDOM_STREAM_ID.toInt(), -1, ByteArray(0)))
        )
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
    }

    @Test
    fun `超大 fieldLen 0x7FFFFFFF 被类型化异常拒绝而非 OOM`() {
        val bytes = innerHeaderBytes(
            listOf(Triple(KdbxConstants.InnerHeaderFieldId.BINARY.toInt(), 0x7FFFFFFF, ByteArray(0)))
        )
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
    }

    @Test
    fun `超过 64MiB 安全上限的 fieldLen 被拒绝`() {
        val bytes = innerHeaderBytes(
            listOf(
                Triple(
                    KdbxConstants.InnerHeaderFieldId.BINARY.toInt(),
                    InnerHeader.MAX_INNER_FIELD_BYTES + 1,
                    ByteArray(0)
                )
            )
        )
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
    }

    @Test
    fun `流提前截断抛类型化异常`() {
        val bytes = innerHeaderBytes(
            listOf(field(KdbxConstants.InnerHeaderFieldId.INNER_RANDOM_STREAM_ID, LittleEndianUtil.intTo4Bytes(3))),
            terminate = false
        )
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
    }

    // ================= 字段语义收窄 =================

    @Test
    fun `InnerRandomStreamID 长度非 4 字节被拒绝而非越界崩溃`() {
        for (badSize in listOf(3, 5)) {
            val bytes = innerHeaderBytes(
                listOf(field(KdbxConstants.InnerHeaderFieldId.INNER_RANDOM_STREAM_ID, ByteArray(badSize)))
            )
            assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
        }
    }

    @Test
    fun `InnerRandomStreamKey 超过 1KiB 上限被拒绝`() {
        val bytes = innerHeaderBytes(
            listOf(field(KdbxConstants.InnerHeaderFieldId.INNER_RANDOM_STREAM_KEY, ByteArray(1025)))
        )
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
    }

    // ================= 合法内层 Header 不受影响 =================

    @Test
    fun `合法内层 Header 完整解析`() {
        val streamKey = ByteArray(64) { 0x33 }
        val binaryData = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val binaryField = byteArrayOf(0) + binaryData // flags + data
        val bytes = innerHeaderBytes(
            listOf(
                field(
                    KdbxConstants.InnerHeaderFieldId.INNER_RANDOM_STREAM_ID,
                    LittleEndianUtil.intTo4Bytes(KdbxConstants.InnerRandomStream.CHACHA20)
                ),
                field(KdbxConstants.InnerHeaderFieldId.INNER_RANDOM_STREAM_KEY, streamKey),
                field(KdbxConstants.InnerHeaderFieldId.BINARY, binaryField)
            )
        )
        val innerHeader = deserialize(bytes)
        assertEquals(KdbxConstants.InnerRandomStream.CHACHA20, innerHeader.innerRandomStreamId)
        assertArrayEquals(streamKey, innerHeader.innerRandomStreamKey)
        assertEquals(1, innerHeader.binaries.size)
        assertEquals(0.toByte(), innerHeader.binaries[0].flags)
        assertArrayEquals(binaryData, innerHeader.binaries[0].data)
    }

    @Test
    fun `序列化往返保持语义一致`() {
        val original = InnerHeader.createDefault()
        val bos = ByteArrayOutputStream()
        original.serialize(bos)
        val restored = deserialize(bos.toByteArray())
        assertEquals(original.innerRandomStreamId, restored.innerRandomStreamId)
        assertArrayEquals(original.innerRandomStreamKey, restored.innerRandomStreamKey)
        assertEquals(0, restored.binaries.size)
    }
}
