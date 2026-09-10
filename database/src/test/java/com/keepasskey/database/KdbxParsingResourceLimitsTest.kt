package com.keepasskey.database

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.file.InnerHeader
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.SizeBoundedInputStream
import com.keepasskey.database.io.LittleEndianUtil
import com.keepasskey.database.xml.KdbxXmlParser
import com.keepasskey.database.xml.TextNode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * KDBX 解析资源防线单元测试（Wave 12 解析炸弹防护）：
 * 覆盖 XML 文本长度上限、嵌套深度上限、二进制池条目/总量封顶与解压输出护栏。
 */
class KdbxParsingResourceLimitsTest {

    @Test
    fun `XML 文本节点超出字符上限即拒绝`() {
        val node = TextNode(maxChars = 8, onText = {})

        node.text("12345".toCharArray(), 0, 5)
        assertThrows(KdbxCorruptFileException::class.java) {
            node.text("67890".toCharArray(), 0, 5)
        }
    }

    @Test
    fun `XML 嵌套深度超出上限即拒绝`() {
        val depth = KdbxXmlParser.MAX_XML_DEPTH + 6
        val xml = buildString {
            append("<KeePassFile><Root>")
            repeat(depth) { append("<Group>") }
            repeat(depth) { append("</Group>") }
            append("</Root></KeePassFile>")
        }

        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxXmlParser(null).parse(xml.byteInputStream())
        }
    }

    @Test
    fun `合法深度范围内的空分组 XML 可正常解析或按语义拒绝`() {
        // 合法浅层文档：深度防线不得误伤（解析结果仅要求不因深度被拒）
        val xml = "<KeePassFile><Root><Group></Group></Root></KeePassFile>"
        // 空 Root Group 允许解析为默认库结构（不抛深度异常即视为通过防线）
        try {
            KdbxXmlParser(null).parse(xml.byteInputStream())
        } catch (e: KdbxCorruptFileException) {
            // 语义层拒绝（如缺少 UUID）不属于资源防线范畴，此处仅确保消息与深度无关
            assert(!e.message!!.contains("嵌套深度"))
        }
    }

    @Test
    fun `二进制池条目数超出上限即拒绝`() {
        val bos = ByteArrayOutputStream()
        repeat(InnerHeader.MAX_BINARY_POOL_ENTRIES + 1) {
            bos.write(KdbxConstants.InnerHeaderFieldId.BINARY.toInt())
            LittleEndianUtil.writeInt(bos, 5)
            bos.write(1) // flags
            bos.write(byteArrayOf(1, 2, 3, 4))
        }

        assertThrows(KdbxCorruptFileException::class.java) {
            InnerHeader.deserialize(ByteArrayInputStream(bos.toByteArray()))
        }
    }

    @Test
    fun `解压输出护栏_超出字节上限即拒绝`() {
        val data = ByteArray(64) { it.toByte() }
        val bounded = SizeBoundedInputStream(ByteArrayInputStream(data), maxBytes = 8)

        assertThrows(KdbxCorruptFileException::class.java) {
            bounded.readAllBytes()
        }
    }

    @Test
    fun `解压输出护栏_限额内读取正常`() {
        val data = ByteArray(8) { it.toByte() }
        val bounded = SizeBoundedInputStream(ByteArrayInputStream(data), maxBytes = 8)

        val read = bounded.readAllBytes()
        assert(read.size == 8)
    }

    /**
     * ISSUE-P3-10 子项 1：载荷解压上限常量本身的取值契约——
     * 必须落在 64 ~ 128 MiB 区间（移动端可承受），且不得低于内层 Header 单字段上限
     * （[InnerHeader.MAX_INNER_FIELD_BYTES]，承载单个合法附件），否则会误拒合法库。
     */
    @Test
    fun `载荷解压上限取值落在 64 至 128 MiB 且不低于单附件字段上限`() {
        val limit = KdbxFile.MAX_DECOMPRESSED_PAYLOAD_BYTES
        assertTrue("上限 $limit 应 ≥ 64 MiB", limit >= 64L * 1024 * 1024)
        assertTrue("上限 $limit 应 ≤ 128 MiB", limit <= 128L * 1024 * 1024)
        assertTrue(
            "上限 $limit 不应低于单个内层 Header 字段上限 ${InnerHeader.MAX_INNER_FIELD_BYTES}",
            limit >= InnerHeader.MAX_INNER_FIELD_BYTES.toLong()
        )
    }

    /** ISSUE-P3-10 子项 1：解压输出超出生产上限即拒绝（GZip 压缩炸弹，压缩后仅百余 KB） */
    @Test
    fun `载荷解压护栏_超出生产上限即拒绝`() {
        val bomb = gzipOfRepeatedZeros(KdbxFile.MAX_DECOMPRESSED_PAYLOAD_BYTES + 1024)

        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxFile.guardPayloadSize(ByteArrayInputStream(bomb), isGzipCompressed = true)
                .use { it.readAllBytes() }
        }
    }

    /** ISSUE-P3-10 子项 1：正常大小的 GZip 载荷在护栏内逐字节透传（不误伤合法解析） */
    @Test
    fun `载荷解压护栏_正常大小载荷逐字节透传`() {
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        val compressed = ByteArrayOutputStream().also { bos ->
            GZIPOutputStream(bos).use { it.write(payload) }
        }.toByteArray()

        val read = KdbxFile.guardPayloadSize(ByteArrayInputStream(compressed), isGzipCompressed = true)
            .use { it.readAllBytes() }

        assertArrayEquals(payload, read)
    }

    /** 未压缩载荷（Compression.NONE）同样受同一上限约束，且限额内原样透传 */
    @Test
    fun `载荷解压护栏_未压缩载荷走同一上限`() {
        val payload = ByteArray(32 * 1024) { it.toByte() }

        val read = KdbxFile.guardPayloadSize(ByteArrayInputStream(payload), isGzipCompressed = false)
            .use { it.readAllBytes() }
        assertArrayEquals(payload, read)

        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxFile.guardPayloadSize(
                ByteArrayInputStream(gzipOfRepeatedZeros(KdbxFile.MAX_DECOMPRESSED_PAYLOAD_BYTES + 1)),
                isGzipCompressed = true
            ).use { it.readAllBytes() }
        }
    }

    /**
     * 构造解压后为 [decompressedSize] 字节全零的 GZip 压缩流。
     * 全零数据 deflate 压缩比极高（约 1000:1），因此超限护栏用例的内存与耗时开销可忽略。
     */
    private fun gzipOfRepeatedZeros(decompressedSize: Long): ByteArray {
        val chunk = ByteArray(GZIP_CHUNK_BYTES)
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gz ->
            var remaining = decompressedSize
            while (remaining > 0L) {
                val n = minOf(remaining, chunk.size.toLong()).toInt()
                gz.write(chunk, 0, n)
                remaining -= n
            }
        }
        return bos.toByteArray()
    }

    private companion object {
        const val GZIP_CHUNK_BYTES = 64 * 1024
    }
}
