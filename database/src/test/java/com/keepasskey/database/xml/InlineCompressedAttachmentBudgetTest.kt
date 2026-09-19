package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.database.exception.KdbxCorruptFileException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

/**
 * ISSUE-P2-200 落点①：**内联压缩附件**的累计物化预算。
 *
 * 整改前判据是「**每次 gunzip 调用**以整包上限 128 MiB 封顶」，因此：
 * - 单节点即可产出 128 MiB 驻留（真机 192 MiB 堆实测 OOM，见
 *   `InlineCompressedBinaryBudgetDeviceTest`）；
 * - 多节点互不累计（`Compressed="True"` 的内联分支不调任何累计预算）。
 *
 * 现由 [AttachmentBudget] 按**本次解析**累计封顶（[AttachmentBudget.MAX_INLINE_MATERIALIZED_BYTES]
 * = 64 MiB）并附加内联压缩节点数上限。本类三例分别锚定：累计字节、节点数、以及
 * 「预算内的正常附件不得被误拒」的对照组。
 */
class InlineCompressedAttachmentBudgetTest {

    private val uuidB64: String = Base64.getEncoder().encodeToString(ByteArray(16))

    @Test
    fun `多内联压缩附件累计超出预算即拒绝而非 OOM`() {
        // 两个各 33 MiB 的节点：单体均在旧「每次调用 128 MiB」封顶之内（旧实现必然双双放行），
        // 但累计 66 MiB > 64 MiB 预算 ⇒ 第二个节点必须在预算处 fail-closed
        val xml = xmlWithBinaries(
            inlineCompressed("a.bin", NODE_BYTES) + inlineCompressed("b.bin", NODE_BYTES)
        )

        val error = assertThrows(KdbxCorruptFileException::class.java) {
            KdbxXmlParser(null).parse(ByteArrayInputStream(xml.toByteArray()))
        }
        assertTrue(
            "必须以「累计预算」为由拒绝（而非 OOM / 其他解析错误），实际: ${error.message}",
            error.message.orEmpty().contains("累计")
        )
    }

    @Test
    fun `内联压缩附件节点数超出上限即拒绝`() {
        val xml = xmlWithBinaries(
            buildString {
                repeat(AttachmentBudget.MAX_INLINE_COMPRESSED_NODES + 1) { index ->
                    append(inlineCompressed("k$index.bin", 1))
                }
            }
        )

        val error = assertThrows(KdbxCorruptFileException::class.java) {
            KdbxXmlParser(null).parse(ByteArrayInputStream(xml.toByteArray()))
        }
        assertTrue(
            "必须以「节点数上限」为由拒绝，实际: ${error.message}",
            error.message.orEmpty().contains("节点数")
        )
    }

    @Test
    fun `预算内的内联压缩附件正常解压`() {
        val xml = xmlWithBinaries(inlineCompressed("ok.bin", CONTROL_BYTES))

        val entry = KdbxXmlParser(null)
            .parse(ByteArrayInputStream(xml.toByteArray()))
            .rootGroup.entries.single()

        val attachment = entry.attachments.single()
        assertEquals("对照组：预算内的附件必须正常交付且字节数一致", CONTROL_BYTES.toLong(), attachment.size)
        assertEquals("内联形态不得被记为池引用", BinaryNode.INLINE_REF_INDEX, attachment.refIndex)
    }

    /** 单个内联压缩附件节点（官方写侧形态：`Compressed="True"` + Base64 正文） */
    private fun inlineCompressed(key: String, uncompressedBytes: Int): String =
        "<Binary><Key>$key</Key><Value ${KdbxConstants.Xml.COMPRESSED}=\"$COMPRESSED_TRUE\">" +
            Base64.getEncoder().encodeToString(gzipZeros(uncompressedBytes)) +
            "</Value></Binary>"

    private fun xmlWithBinaries(binariesXml: String): String = """
        <KeePassFile>
            <Root>
                <Group>
                    <UUID>$uuidB64</UUID>
                    <Name>G</Name>
                    <Entry>
                        <UUID>$uuidB64</UUID>
                        $binariesXml
                    </Entry>
                </Group>
            </Root>
        </KeePassFile>
    """.trimIndent()

    /** 全零数据的 GZip 体：压缩比极高（≈1000:1），使超限构造的 XML 文本仅数十 KB */
    private fun gzipZeros(totalBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gzip ->
            val chunk = ByteArray(CHUNK_BYTES)
            var written = 0
            while (written < totalBytes) {
                val n = minOf(chunk.size, totalBytes - written)
                gzip.write(chunk, 0, n)
                written += n
            }
        }
        return out.toByteArray()
    }

    private companion object {
        const val CHUNK_BYTES = 64 * 1024

        /** 单节点规模：33 MiB × 2 = 66 MiB > 64 MiB 累计预算，但单节点仍在旧 128 MiB 封顶内 */
        const val NODE_BYTES = 33 * 1024 * 1024

        /** 对照组规模：远小于预算，且证明该代码路径本身可用 */
        const val CONTROL_BYTES = 1024 * 1024

        /** 官方写侧规范字面量（与 `KdbxXmlEntrySerializer` 同源口径） */
        const val COMPRESSED_TRUE = "True"
    }
}
