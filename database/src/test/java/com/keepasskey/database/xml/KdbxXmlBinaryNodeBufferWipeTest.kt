package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.file.InnerHeader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.helpers.AttributesImpl
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

/**
 * `ISSUE-P3-646`：内联附件解析的**解压工作缓冲**必须确定性擦除，且交付物不得被误擦。
 *
 * ## 被锁定的缓冲（[BinaryNode.gunzip] 与内联解码）
 *
 * - `GZIP_COPY_BUFFER_BYTES` 的拷贝缓冲（解压出的明文块）；
 * - `WipableByteArrayOutputStream` 的**每一代**内部缓冲（含扩容遗弃的旧数组）；
 * - `toByteArray()` 交给附件的解压产物（R2 转移，**不得**在本节点内清零）。
 *
 * ## 覆盖路径
 *
 * | 路径 | 期望 |
 * |---|---|
 * | 正常 `Compressed="True"` | 全部工作缓冲清零；交付字节正确 |
 * | GZip 头损坏 | `KdbxCorruptFileException`；工作缓冲仍清零 |
 * | 超出累计预算（解压炸弹） | `KdbxCorruptFileException`；工作缓冲仍清零 |
 * | 内联非压缩 | 不产生解压缓冲；交付字节正确（所有权转移） |
 * | 池引用（含历史复用的共享池条目） | 池条目字节不被擦除；交付独立副本 |
 * | 落盘池条目 | 只持 `BinarySource` 引用，不物化、不擦除 store |
 */
class KdbxXmlBinaryNodeBufferWipeTest {

    private val observed = mutableListOf<ByteArray>()
    private val snapshotAtObservation = mutableListOf<ByteArray>()

    private val observer: (ByteArray) -> Unit = { buffer ->
        observed += buffer
        snapshotAtObservation += buffer.copyOf()
    }

    // ==================== 断言工具 ====================

    /**
     * @param expectSensitiveContent 是否要求「至少一个被观察缓冲曾承载非零明文」。仅当输入在解出
     *   任何明文字节之前就失败（例如 GZip 头非法，`GZIPInputStream` 构造即抛错）时才为 false——
     *   此时仍要求观察面非空，证明清理域确实存在。
     */
    private fun assertAllWiped(label: String, expectSensitiveContent: Boolean = true) {
        assertTrue("[$label] 用例必须真的观察到解压工作缓冲（否则断言空转）", observed.isNotEmpty())
        if (expectSensitiveContent) {
            assertTrue(
                "[$label] 观察到的缓冲中至少有一个原本承载非零数据",
                snapshotAtObservation.any { snapshot -> snapshot.any { it != 0.toByte() } }
            )
        }
        observed.forEachIndexed { index, buffer ->
            val residue = buffer.count { it != 0.toByte() }
            assertTrue(
                "[$label] 第 ${index + 1} 个工作缓冲（size=${buffer.size}）在解析结束后必须逐字节清零，" +
                    "实际残留 $residue 字节",
                residue == 0
            )
        }
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    /** 构造并闭合一个 `<Binary>` 节点，返回交付的附件（若未交付则为 null）。 */
    private fun feedBinary(
        valueText: String,
        attributes: Map<String, String> = emptyMap(),
        pool: List<InnerHeader.BinaryItem> = emptyList(),
        budget: AttachmentBudget = AttachmentBudget.unlimited(),
        observe: ((ByteArray) -> Unit)? = observer
    ): KdbxAttachment? {
        var delivered: KdbxAttachment? = null
        val node = BinaryNode(
            binariesPool = pool,
            innerStreamCipher = null,
            attachmentBudget = budget,
            observeBuffer = observe,
            onDone = { delivered = it }
        )
        val keyNode = node.startChild(KdbxConstants.Xml.KEY, AttributesImpl())
        val keyChars = "a.bin".toCharArray()
        keyNode.text(keyChars, 0, keyChars.size)
        keyNode.end()

        val attrs = AttributesImpl().apply {
            attributes.forEach { (name, value) -> addAttribute("", "", name, "CDATA", value) }
        }
        val valueNode = node.startChild(KdbxConstants.Xml.VALUE, attrs)
        val chars = valueText.toCharArray()
        valueNode.text(chars, 0, chars.size)
        valueNode.end()
        node.end()
        chars.fill('\u0000')
        keyChars.fill('\u0000')
        return delivered
    }

    // ==================== 正常解压 ====================

    @Test
    fun `内联压缩附件正常解压后工作缓冲全部清零且交付字节正确`() {
        val raw = ByteArray(9_000) { ((it * 13 + 7) and 0xFF).toByte() }
        val compressed = gzip(raw)
        val delivered = feedBinary(
            Base64.getEncoder().encodeToString(compressed),
            attributes = mapOf(KdbxConstants.Xml.COMPRESSED to "True")
        )

        assertTrue("必须真的解出附件（否则擦除断言无意义）", delivered != null)
        assertAllWiped("正常解压")
        try {
            assertArrayEquals("解压产物必须逐字节正确（擦除不得越界到交付物）", raw, delivered!!.data)
            assertEquals(BinaryNode.INLINE_REF_INDEX, delivered!!.refIndex)
        } finally {
            raw.fill(0)
            compressed.fill(0)
        }
    }

    @Test
    fun `解压产物跨多代输出缓冲时每一代都被清零`() {
        // 64 KiB 原文 + 小初始容量 ⇒ 输出流必然多次扩容，覆盖多代遗留数组
        val raw = ByteArray(64 * 1024) { ((it * 7) and 0xFF).toByte() }
        val compressed = gzip(raw)
        val delivered = feedBinary(
            Base64.getEncoder().encodeToString(compressed),
            attributes = mapOf(KdbxConstants.Xml.COMPRESSED to "True")
        )
        assertAllWiped("跨多代扩容")
        try {
            assertArrayEquals(raw, delivered!!.data)
        } finally {
            raw.fill(0)
            compressed.fill(0)
        }
    }

    // ==================== 损坏输入 ====================

    @Test
    fun `损坏的GZip流抛损坏异常且工作缓冲仍清零`() {
        // 合法 gzip 头 + 垃圾体：解压中途失败
        val broken = gzip(ByteArray(4_000) { it.toByte() })
        broken[broken.size - 5] = 0x7F
        broken[broken.size - 4] = 0x00
        broken[broken.size - 3] = 0x00
        broken[broken.size - 2] = 0x00

        val error = assertThrows(KdbxCorruptFileException::class.java) {
            feedBinary(
                Base64.getEncoder().encodeToString(broken),
                attributes = mapOf(KdbxConstants.Xml.COMPRESSED to "True")
            )
        }
        assertTrue(
            "必须以「GZip 解压失败」为由 fail-closed，实际: ${error.message}",
            error.message.orEmpty().contains("GZip")
        )
        assertAllWiped("损坏 GZip")
        broken.fill(0)
    }

    @Test
    fun `非GZip数据却声明Compressed时抛损坏异常且缓冲清零`() {
        val notGzip = ByteArray(200) { (it + 1).toByte() }
        val error = assertThrows(KdbxCorruptFileException::class.java) {
            feedBinary(
                Base64.getEncoder().encodeToString(notGzip),
                attributes = mapOf(KdbxConstants.Xml.COMPRESSED to "True")
            )
        }
        assertTrue(
            "必须以解压失败为由拒绝，实际: ${error.message}",
            error.message.orEmpty().contains("GZip")
        )
        // GZip 头非法 ⇒ GZIPInputStream 构造即抛错，一个明文块都没解出：
        // 观察面仍须包含已分配的缓冲（证明清理域存在），但不要求它曾承载明文。
        assertAllWiped("非 GZip 却声明压缩", expectSensitiveContent = false)
        notGzip.fill(0)
    }

    @Test
    fun `超出解压预算时抛损坏异常且工作缓冲仍清零`() {
        val raw = ByteArray(50_000) { 0x41 }
        val compressed = gzip(raw)
        // 预算只留 1 KiB：解压必然在中途触发 fail-closed
        val budget = AttachmentBudget(
            maxPoolReferenceBytes = Long.MAX_VALUE / 2,
            maxInlineMaterializedBytes = 1024L
        )

        val error = assertThrows(KdbxCorruptFileException::class.java) {
            feedBinary(
                Base64.getEncoder().encodeToString(compressed),
                attributes = mapOf(KdbxConstants.Xml.COMPRESSED to "True"),
                budget = budget
            )
        }
        assertTrue(
            "必须以「超出预算」为由拒绝，实际: ${error.message}",
            error.message.orEmpty().contains("预算")
        )
        assertAllWiped("超出预算")
        raw.fill(0)
        compressed.fill(0)
    }

    // ==================== 所有权负例 ====================

    @Test
    fun `内联非压缩附件不产生解压缓冲且交付字节保持有效`() {
        val inline = ByteArray(300) { (it * 3 + 1).toByte() }
        val delivered = feedBinary(Base64.getEncoder().encodeToString(inline))

        assertTrue("内联非压缩路径不得分配解压工作缓冲", observed.isEmpty())
        try {
            assertArrayEquals("交付字节是 R2 转移，必须保持有效", inline, delivered!!.data)
        } finally {
            inline.fill(0)
        }
    }

    @Test
    fun `池内命中的共享池条目字节不被擦除且交付独立副本`() {
        val poolData = ByteArray(512) { (it % 251).toByte() }
        val poolSnapshot = poolData.copyOf()
        val item = InnerHeader.BinaryItem(0, poolData)

        val delivered = feedBinary("", attributes = mapOf(KdbxConstants.Xml.REF to "0"), pool = listOf(item))
        try {
            assertArrayEquals(
                "池条目由 InnerHeader 独占（历史复用共享同一实例），解析不得擦除它",
                poolSnapshot,
                poolData
            )
            assertArrayEquals("交付内容必须与池条目一致", poolSnapshot, delivered!!.data)
            assertNotSame("交付必须是独立副本（ISSUE-P3-07 别名隔离）", poolData, delivered!!.data)
            assertTrue("池引用路径不产生解压工作缓冲", observed.isEmpty())
        } finally {
            poolData.fill(0)
            poolSnapshot.fill(0)
        }
    }

    @Test
    fun `同一池条目被多次引用时各引用者互不影响`() {
        val poolData = ByteArray(64) { (it + 5).toByte() }
        val item = InnerHeader.BinaryItem(0, poolData)
        val first = feedBinary("", attributes = mapOf(KdbxConstants.Xml.REF to "0"), pool = listOf(item), observe = null)
        val second = feedBinary("", attributes = mapOf(KdbxConstants.Xml.REF to "0"), pool = listOf(item), observe = null)

        try {
            assertArrayEquals("第二次引用不得读到被第一次擦除的内容", first!!.data, second!!.data)
            assertTrue("交付内容不得为全零", second!!.data.any { it != 0.toByte() })
        } finally {
            poolData.fill(0)
        }
    }

    @Test
    fun `落盘池条目只持来源引用且不清除store内容`() {
        val stored = ByteArray(128) { (it * 11).toByte() }
        val store = RecordingBinaryStore()
        val key = store.store(stored)
        val item = InnerHeader.BinaryItem(0, store, key, stored.size.toLong())

        val delivered = feedBinary("", attributes = mapOf(KdbxConstants.Xml.REF to "0"), pool = listOf(item))
        try {
            assertEquals("落盘附件必须保留池索引", 0, delivered!!.refIndex)
            assertTrue("落盘附件不得物化 in-line 副本", delivered!!.inlineBytes().isEmpty())
            assertTrue("落盘附件必须持有来源引用", delivered!!.binarySource() != null)
            assertArrayEquals("store 中的字节不属本节点所有权，不得被擦除", stored, store.load(key))
            assertTrue("落盘路径不产生解压工作缓冲", observed.isEmpty())
        } finally {
            stored.fill(0)
            store.clear()
        }
    }

    /** 进程内假 store：只记录键与字节，验证「不物化 / 不擦除」语义。 */
    private class RecordingBinaryStore : com.keepasskey.core.security.BinaryStore {
        private val entries = LinkedHashMap<String, ByteArray>()
        private var counter = 0

        override fun store(bytes: ByteArray): String {
            val key = "k${counter++}"
            entries[key] = bytes.copyOf()
            return key
        }

        override fun storeFromStream(input: java.io.InputStream, size: Long): String {
            val bytes = ByteArray(size.toInt())
            java.io.DataInputStream(input).readFully(bytes)
            return store(bytes)
        }

        override fun load(key: String): ByteArray =
            entries[key]?.copyOf() ?: error("store 中不存在 key=$key")

        override fun openStream(key: String): java.io.InputStream =
            java.io.ByteArrayInputStream(load(key))

        override fun sizeOf(key: String): Long = (entries[key]?.size ?: 0).toLong()

        override fun clear() {
            entries.values.forEach { it.fill(0) }
            entries.clear()
        }
    }
}
