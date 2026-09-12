package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.file.InnerHeader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

/**
 * 附件 `<Value>` 解析语义回归测试（缺陷 D9 / P1）。
 *
 * 官方 `KdbxFile.Read.Streamed.cs:980-1028`：
 * 1. `Ref` 存在、非空、可解析为 int 且**池内命中** → 取池条目；
 * 2. 否则（无 Ref / 非数字 / 越界）→ 回退读该 `<Value>` 的**内联 Base64** 正文；
 * 3. `Compressed="True"` → GZip 解压（写侧 `KdbxFile.Write.cs:945-978` 确实会产出该形态）。
 *
 * 旧缺陷：`refStr.toIntOrNull() ?: 0` 把上述回退形态**全部折叠成池索引 0**（张冠李戴到无关附件），
 * 且完全不识别 `Compressed`。
 */
class KdbxBinaryNodeValueFormTest {

    private val uuidB64: String = Base64.getEncoder().encodeToString(ByteArray(16))
    private val poolData = byteArrayOf(11, 22, 33, 44)

    private fun newCipher(): InnerRandomStreamCipher =
        InnerRandomStreamCipher(KdbxConstants.InnerRandomStream.CHACHA20, ByteArray(64))

    private fun entryXml(vararg binaryBlocks: String): String = """
        <KeePassFile>
            <Root>
                <Group>
                    <UUID>$uuidB64</UUID>
                    <Name>G</Name>
                    <Entry>
                        <UUID>$uuidB64</UUID>
                        ${binaryBlocks.joinToString("\n")}
                    </Entry>
                </Group>
            </Root>
        </KeePassFile>
    """.trimIndent()

    private fun parse(
        xml: String,
        pool: List<InnerHeader.BinaryItem> = emptyList(),
        cipher: InnerRandomStreamCipher? = null
    ) = KdbxXmlParser(cipher).parse(ByteArrayInputStream(xml.toByteArray()), pool).rootGroup.entries.single()

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    // ---------- ① Ref 越界：回退内联，不得张冠李戴到池条目 0 ----------

    @Test
    fun `Ref 越界且带内联正文时读取内联字节而非池条目 0`() {
        val inline = "INLINE-PAYLOAD".toByteArray()
        val b64 = Base64.getEncoder().encodeToString(inline)
        val entry = parse(
            entryXml("<Binary><Key>a.bin</Key><Value Ref=\"7\">$b64</Value></Binary>"),
            pool = listOf(InnerHeader.BinaryItem(0, poolData))
        )

        val att = entry.attachments.single()
        assertArrayEquals("越界 Ref 必须回退读内联正文，绝不能取池条目 0", inline, att.data)
        assertEquals(BinaryNode.INLINE_REF_INDEX, att.refIndex)
    }

    @Test
    fun `Ref 越界且无内联正文时交付空字节且不抛异常`() {
        val entry = parse(
            entryXml("<Binary><Key>ghost.bin</Key><Value Ref=\"7\"/></Binary>"),
            pool = listOf(InnerHeader.BinaryItem(0, poolData))
        )

        val att = entry.attachments.single()
        assertTrue("越界且无正文只能交付空字节", att.data.isEmpty())
        assertEquals(BinaryNode.INLINE_REF_INDEX, att.refIndex)
    }

    // ---------- ② 非数字 Ref：回退内联 ----------

    @Test
    fun `非数字 Ref 回退内联正文而非池索引 0`() {
        val inline = "NOT-A-NUMBER-REF".toByteArray()
        val b64 = Base64.getEncoder().encodeToString(inline)
        val entry = parse(
            entryXml("<Binary><Key>a.bin</Key><Value Ref=\"abc\">$b64</Value></Binary>"),
            pool = listOf(InnerHeader.BinaryItem(0, poolData))
        )

        assertArrayEquals(inline, entry.attachments.single().data)
    }

    @Test
    fun `空 Ref 属性回退内联正文`() {
        val inline = byteArrayOf(9, 8, 7)
        val b64 = Base64.getEncoder().encodeToString(inline)
        val entry = parse(
            entryXml("<Binary><Key>a.bin</Key><Value Ref=\"\">$b64</Value></Binary>"),
            pool = listOf(InnerHeader.BinaryItem(0, poolData))
        )

        assertArrayEquals(inline, entry.attachments.single().data)
    }

    // ---------- ③ 纯内联 base64（无 Ref）：KDBX4 合法形态 ----------

    @Test
    fun `无 Ref 的内联 base64 正文按内联读取`() {
        val inline = byteArrayOf(1, 3, 5, 7, 9)
        val b64 = Base64.getEncoder().encodeToString(inline)
        val entry = parse(
            entryXml("<Binary><Key>a.bin</Key><Value>$b64</Value></Binary>"),
            pool = listOf(InnerHeader.BinaryItem(0, poolData))
        )

        val att = entry.attachments.single()
        assertArrayEquals(inline, att.data)
        assertEquals(BinaryNode.INLINE_REF_INDEX, att.refIndex)
    }

    // ---------- ④ Compressed="True"：GZip 解压 ----------

    @Test
    fun `Compressed 为 True 时 GZip 解压内联字节`() {
        val raw = "COMPRESSED-ATTACHMENT-CONTENT".repeat(20).toByteArray()
        val compressed = gzip(raw)
        val b64 = Base64.getEncoder().encodeToString(compressed)
        assertNotEquals("压缩结果应短于原文，确保用例真的走了压缩路径", raw.size, compressed.size)

        val entry = parse(entryXml("<Binary><Key>c.bin</Key><Value Compressed=\"True\">$b64</Value></Binary>"))

        assertArrayEquals(raw, entry.attachments.single().data)
    }

    @Test
    fun `Compressed 非规范拼写不触发解压`() {
        // 官方 CompareOrdinal 精确比较 "True"；"true" 不触发解压，按原始字节交付
        val raw = byteArrayOf(5, 5, 5)
        val b64 = Base64.getEncoder().encodeToString(raw)

        val entry = parse(entryXml("<Binary><Key>c.bin</Key><Value Compressed=\"true\">$b64</Value></Binary>"))

        assertArrayEquals(raw, entry.attachments.single().data)
    }

    // ---------- ⑤ 池内命中：仍走池引用（既有契约不得放宽） ----------

    @Test
    fun `池内命中仍交付池条目独立副本`() {
        val pool = listOf(InnerHeader.BinaryItem(0, poolData))
        val entry = parse(entryXml("<Binary><Key>a.bin</Key><Value Ref=\"0\"/></Binary>"), pool)

        val att = entry.attachments.single()
        assertEquals(0, att.refIndex)
        assertArrayEquals(poolData, att.data)
        assertFalse("仍不得别名池内数组（ISSUE-P3-07 契约）", att.data === pool[0].data)
    }

    @Test
    fun `池内命中优先于内联正文`() {
        // 官方池命中分支直接返回池条目并跳过正文（Read.Streamed.cs:990-1002）
        val inlineB64 = Base64.getEncoder().encodeToString("SHOULD-BE-IGNORED".toByteArray())
        val pool = listOf(InnerHeader.BinaryItem(0, poolData))
        val entry = parse(
            entryXml("<Binary><Key>a.bin</Key><Value Ref=\"0\">$inlineB64</Value></Binary>"),
            pool
        )

        assertArrayEquals(poolData, entry.attachments.single().data)
    }

    // ---------- ⑥ 内联非法 base64：立即判损坏（不得降级为原始字节） ----------

    @Test
    fun `内联非法 base64 抛出损坏异常`() {
        val xml = entryXml("<Binary><Key>bad.bin</Key><Value>!!!NOT_BASE64@@@</Value></Binary>")
        val ex = assertThrows(KdbxCorruptFileException::class.java) {
            parse(xml)
        }
        assertTrue("异常消息应指明附件名: ${ex.message}", ex.message!!.contains("bad.bin"))
    }

    @Test
    fun `内联受保护值按密钥流解密`() {
        val plain = byteArrayOf(7, 7, 7, 7)
        val cipherText = Base64.getEncoder().encodeToString(newCipher().processBytes(plain))
        val xml = entryXml("<Binary><Key>p.bin</Key><Value Protected=\"True\">$cipherText</Value></Binary>")

        val entry = parse(xml, cipher = newCipher())

        assertTrue("规范 Protected=\"True\" 应标记为受保护", entry.attachments.single().isProtected)
        assertArrayEquals(plain, entry.attachments.single().data)
    }

    // ---------- ⑦ 折行 base64（官方 .NET 解码器容忍内部空白） ----------

    @Test
    fun `折行的内联 base64 正文可正常解码`() {
        val inline = ByteArray(60) { it.toByte() }
        val b64 = Base64.getEncoder().encodeToString(inline)
        val wrapped = b64.chunked(16).joinToString("\n  ")

        val entry = parse(
            entryXml("<Binary><Key>w.bin</Key><Value>\n  $wrapped\n</Value></Binary>"),
            pool = listOf(InnerHeader.BinaryItem(0, poolData))
        )

        assertArrayEquals(inline, entry.attachments.single().data)
    }

    // ---------- ⑧ 完全缺失 <Value>：不产出附件（既有语义） ----------

    @Test
    fun `缺失 Value 子元素时不产出附件`() {
        val entry = parse(entryXml("<Binary><Key>bare.bin</Key></Binary>"))
        assertEquals(0, entry.attachments.size)
    }

    @Test
    fun `空 Value 元素交付空字节附件`() {
        val entry = parse(entryXml("<Binary><Key>empty.bin</Key><Value/></Binary>"))
        val att = entry.attachments.single()
        assertTrue(att.data.isEmpty())
        assertEquals(BinaryNode.INLINE_REF_INDEX, att.refIndex)
    }

    @Test
    fun `附件名始终保留`() {
        val entry = parse(entryXml("<Binary><Key>named.bin</Key><Value/></Binary>"))
        assertEquals("named.bin", entry.attachments.single().name)
    }

    @Test
    fun `内联附件受保护标志按精确 True 判定`() {
        val inline = Base64.getEncoder().encodeToString("plain".toByteArray())
        val entry = parse(entryXml("<Binary><Key>x.bin</Key><Value Protected=\"true\">$inline</Value></Binary>"))

        // 非规范拼写 → 明文承载，isProtected 为 false 仍未解码错乱
        assertFalse(entry.attachments.single().isProtected)
        assertArrayEquals("plain".toByteArray(), entry.attachments.single().data)
    }

    @Test
    fun `内联附件常量与池索引语义一致`() {
        assertTrue("INLINE_REF_INDEX 必须是负哨兵，恒不可能成为合法池索引", BinaryNode.INLINE_REF_INDEX < 0)
        assertEquals("Compressed 属性名须与官方 KdbxFile.cs 一致", "Compressed", BinaryNode.ATTR_COMPRESSED)
    }
}
