package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.io.LittleEndianUtil
import com.keepasskey.database.xml.KdbxXmlParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * P0-5 整改回归：内层 Header 字段长度安全边界。
 * fieldLen（负数 / 0x7FFFFFFF / 超 64 MiB 上限）在 ByteArray 分配前以类型化
 * [KdbxCorruptFileException] 拒绝；InnerRandomStreamID / Key 按官方语义收窄。
 * ENG-20261028-10：零长度 BINARY 字段（无 flags 字节）同样以类型化异常拒绝并保持池索引，
 * flags-only 空附件作为合法形态保留。
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

    // ================= ENG-20261028-10：零长度 BINARY 与合法空附件 =================

    /** 内层随机流前置字段（算法 id + 密钥），使 BINARY 用例不受密钥存在性断言干扰 */
    private fun streamFields(): List<Triple<Int, Int, ByteArray>> = listOf(
        field(
            KdbxConstants.InnerHeaderFieldId.INNER_RANDOM_STREAM_ID,
            LittleEndianUtil.intTo4Bytes(KdbxConstants.InnerRandomStream.CHACHA20)
        ),
        field(KdbxConstants.InnerHeaderFieldId.INNER_RANDOM_STREAM_KEY, ByteArray(64))
    )

    /**
     * 零长度 BINARY（没有任何 flags 字节）是格式错误：旧实现以 `isNotEmpty()` 静默跳过该字段，
     * 其后的池条目整体前移，XML 的整数 `Ref` 便错配到无关附件。池首与池中位置都必须拒绝。
     */
    @Test
    fun `零长度 BINARY 字段在池首被类型化异常拒绝`() {
        val bytes = innerHeaderBytes(
            streamFields() + Triple(KdbxConstants.InnerHeaderFieldId.BINARY.toInt(), 0, ByteArray(0))
        )
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
    }

    @Test
    fun `零长度 BINARY 字段在池中被类型化异常拒绝`() {
        val bytes = innerHeaderBytes(
            streamFields() +
                    field(KdbxConstants.InnerHeaderFieldId.BINARY, byteArrayOf(0, 1, 2, 3)) +
                    Triple(KdbxConstants.InnerHeaderFieldId.BINARY.toInt(), 0, ByteArray(0)) +
                    field(KdbxConstants.InnerHeaderFieldId.BINARY, byteArrayOf(0, 5, 6))
        )
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
    }

    /**
     * 合法对照：flags-only 空附件（字段长度 1、内容 0 字节）必须照常入池并占据自己的索引，
     * 不得因「内容为空」被跳过——否则其后的附件整体前移，XML `Ref` 便指向错误内容。
     */
    @Test
    fun `flags-only 空附件合法入池且不使 XML Ref 索引错位`() {
        val realData = byteArrayOf(0x33, 0x44, 0x55)
        val bytes = innerHeaderBytes(
            streamFields() +
                    field(KdbxConstants.InnerHeaderFieldId.BINARY, byteArrayOf(1)) +
                    field(KdbxConstants.InnerHeaderFieldId.BINARY, byteArrayOf(0) + realData)
        )

        val pool = deserialize(bytes).binaries
        assertEquals("两条 BINARY 字段都必须入池", 2, pool.size)
        assertEquals(1.toByte(), pool[0].flags)
        assertTrue("池首必须是合法空附件，不得被跳过", pool[0].data.isEmpty())
        assertEquals(0.toByte(), pool[1].flags)
        assertArrayEquals(realData, pool[1].data)

        val uuid = Base64.getEncoder().encodeToString(ByteArray(16))
        val xml = """
            <KeePassFile>
                <Root>
                    <Group>
                        <UUID>$uuid</UUID>
                        <Name>G</Name>
                        <Entry>
                            <UUID>$uuid</UUID>
                            <Binary><Key>empty.bin</Key><Value Ref="0"/></Binary>
                            <Binary><Key>real.bin</Key><Value Ref="1"/></Binary>
                        </Entry>
                    </Group>
                </Root>
            </KeePassFile>
        """.trimIndent()
        val entry = KdbxXmlParser(null)
            .parse(ByteArrayInputStream(xml.encodeToByteArray()), pool)
            .rootGroup.entries.single()

        val empty = entry.attachments[0]
        val real = entry.attachments[1]
        assertEquals("Ref=0 必须仍指向池首空附件", 0, empty.refIndex)
        assertTrue("Ref=0 不得错配到其后的真实附件内容", empty.data.isEmpty())
        assertEquals("Ref=1 必须仍指向池内第二条", 1, real.refIndex)
        assertArrayEquals(realData, real.data)
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
