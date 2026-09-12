package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.file.InnerHeader
import com.keepasskey.database.file.KdbxFile
import org.xml.sax.Attributes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * <Binary> 附件子树：`<Value Ref="n">` 引用内层头二进制池，或 `<Value>` 内联 Base64 正文。
 *
 * ISSUE-P3-25 拆分：原样搬出自 `KdbxXmlGroupReader.kt`。可见性由 `private`（文件级）
 * 放宽为 `internal`——唯一调用方 [EntryNode] 现位于 `KdbxXmlGroupReader.kt`，跨文件使用；
 * `internal` 为同模块可见，不跨模块泄漏（app / sync / core / crypto 均无引用）。
 *
 * ISSUE-P3-07 回归锁：`end()` 出边界防御性拷贝语义逐字保留，
 * 回归测试 `KdbxAttachmentAliasIsolationTest`（4 例）不得放宽或删除。
 *
 * ## 缺陷 D9（P1）解析语义对齐官方 `KdbxFile.Read.Streamed.cs:980-1028`
 *
 * 官方判定顺序与本类一致：
 * 1. `Ref` 属性存在且非空且可解析为 int 且 `m_pbsBinaries.Get(iRef) != null`（**池内命中**）
 *    → 取池条目；
 * 2. 否则（**无 Ref / 非数字 / 池越界**）→ 回退 `ReadBase64` 读取该 `<Value>` 的**内联 Base64**
 *    正文（KDBX4 中内联二进制是合法形态，官方写出侧 `KdbxFile.Write.cs:939` 在附件未入池时
 *    正是这样写）；
 * 3. `Compressed="True"` → 对解码结果 GZip 解压（官方 `MemUtil.Decompress`；
 *    `KdbxFile.Write.cs:957-963` 在「压缩库 + 附件非受保护」时确实会产出该形式）。
 *
 * 旧实现把「非数字 / 缺 Ref / 内联 base64」一律折叠为**池索引 0**，导致张冠李戴到无关附件；
 * 且完全不识别 `Compressed`。现按上述顺序处理：**仅池内命中才用池条目**，其余一律读内联正文。
 *
 * 池索引越界时的取舍（**已在 KDoc 显式留痕**）：与官方一致**回退内联**（而非抛错），
 * 故 `Ref="7"` 但池仅 1 条目的文件仍按内联正文解析（正文为空则交付空字节附件）。
 * 回退后的 [KdbxAttachment.refIndex] 记为 [INLINE_REF_INDEX] 哨兵而非原始越界值——
 * 原始值无法与「池内引用」区分，会让写出侧无从判断该写 `Ref` 还是内联正文。
 * 这与既有回归 `KdbxAttachmentAliasIsolationTest` 中「越界不抛异常」的可用性意图一致。
 */
internal class BinaryNode(
    private val binariesPool: List<InnerHeader.BinaryItem>,
    /**
     * 内层流密码（用于 `Protected="True"` 的内联附件：官方 `ProcessNode`
     * 经 `XorredBuffer` 以同一 keystream 解密，见 `KdbxFile.Read.Streamed.cs:1015-1021`）。
     * 默认 null 以保持既有构造调用点（含单测）源码兼容。
     */
    private val innerStreamCipher: InnerRandomStreamCipher? = null,
    private val onDone: (KdbxAttachment) -> Unit
) : SaxNode() {

    private var key = ""
    private var refAttribute: String? = null
    private var rawValue: String? = null
    private var isProtected = false
    private var isCompressed = false

    /** `<Value>` 子元素是否出现过——区分「空内联值」与「完全缺失该元素」。 */
    private var valueElementSeen = false

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return when (name) {
            KdbxConstants.Xml.KEY -> TextNode { key = it }
            KdbxConstants.Xml.VALUE -> {
                refAttribute = attrs.getValue(KdbxConstants.Xml.REF)
                isProtected = isProtectedAttribute(attrs)
                isCompressed = attrs.getValue(ATTR_COMPRESSED) == PROTECTED_ATTR_TRUE
                valueElementSeen = true
                TextNode { rawValue = it }
            }
            else -> IgnoredNode()
        }
    }

    override fun end() {
        // 从未出现 <Value> 子元素：本节点无可交付内容（保持既有「不产出附件」语义）。
        // 出现即交付——空 <Value/> 交付空字节附件，不得因值为空而丢条目。
        if (!valueElementSeen) return

        emit(resolveValue())
    }

    /**
     * 按官方顺序解析 `<Value>`：池命中优先，否则读内联正文。
     * 返回值语义见 [ResolvedBinary]。
     */
    private fun resolveValue(): ResolvedBinary {
        val refText = refAttribute?.trim()
        if (!refText.isNullOrEmpty()) {
            val refIndex = refText.toIntOrNull()
            if (refIndex != null && refIndex in binariesPool.indices) {
                // ① 池内命中：唯一走池引用的分支
                return ResolvedBinary(item = binariesPool[refIndex], refIndex = refIndex)
            }
            // Ref 非法 / 池越界 → 落入下方内联回退（与官方一致，不抛错）
        }

        // ② 内联 Base64 正文（Ref 缺失/非法/越界的回退路径）
        val bytes = decodeInlineValue(rawValue.orEmpty())
        return ResolvedBinary(data = bytes, refIndex = INLINE_REF_INDEX)
    }

    /**
     * 解码内联 `<Value>` 正文：`Protected="True"` 先解密（XOR keystream，与受保护字符串同语义），
     * 否则 Base64 解码；最后按 `Compressed="True"` GZip 解压。
     *
     * `isProtected` 仅在 [innerStreamCipher] 可用时才走解密分支——与 [StringNode] 同一
     * 降级判定（无内层流密码意味着调用方使用明文 XML 通道，此时 `Protected` 无密钥流可解）。
     */
    private fun decodeInlineValue(text: String): ByteArray {
        if (isProtected && innerStreamCipher != null) {
            val decoded = try {
                KdbxXmlValueUtil.decodeBase64LenientWhitespace(text)
            } catch (e: IllegalArgumentException) {
                throw KdbxCorruptFileException("无法解码受保护附件 Base64 数据: key=$key", e)
            }
            // 官方 ProcessNode：受保护内联值与数据库级压缩互斥（Write.cs:947-963 二选一分支），
            // 故此处不叠加 Compressed 解压。空载荷不消耗密钥流（Write.cs:951-953 仅长度 > 0 才写出）。
            return if (decoded.isEmpty()) ByteArray(0) else innerStreamCipher.processBytes(decoded)
        }

        val decoded = try {
            KdbxXmlValueUtil.decodeBase64LenientWhitespace(text)
        } catch (e: IllegalArgumentException) {
            throw KdbxCorruptFileException("无法解码内联附件 Base64 数据: key=$key", e)
        }
        return if (isCompressed) gunzip(decoded) else decoded
    }

    /**
     * GZip 解压内联附件字节（官方 `MemUtil.Decompress`，GZip 容器）。
     *
     * 解压是**不可信输入**触发的内存放大器，故以
     * [KdbxFile.MAX_DECOMPRESSED_PAYLOAD_BYTES]（整包上限）封顶，
     * 与 [KdbxFile.guardPayloadSize] 对压缩载荷的同级防线一致。
     */
    private fun gunzip(compressed: ByteArray): ByteArray {
        // 初始容量按 4 倍压缩比预估，并以 4 MiB 封顶，避免小压缩体直接预留过大缓冲
        val initialCap = minOf(
            compressed.size.toLong() * GZIP_SIZE_GROWTH_HINT,
            GZIP_INITIAL_CAP_BYTES
        ).toInt()
        val out = ByteArrayOutputStream(initialCap)
        try {
            GZIPInputStream(ByteArrayInputStream(compressed)).use { gzip ->
                val buffer = ByteArray(GZIP_COPY_BUFFER_BYTES)
                while (true) {
                    val read = gzip.read(buffer)
                    if (read < 0) break
                    if (out.size().toLong() + read > MAX_INFLATED_ATTACHMENT_BYTES) {
                        throw KdbxCorruptFileException(
                            "内联附件解压后字节数超出安全上限（允许 ≤ $MAX_INFLATED_ATTACHMENT_BYTES），疑似解压炸弹"
                        )
                    }
                    out.write(buffer, 0, read)
                }
            }
        } catch (e: KdbxCorruptFileException) {
            throw e
        } catch (e: IOException) {
            throw KdbxCorruptFileException("内联附件 GZip 解压失败: key=$key", e)
        }
        return out.toByteArray()
    }

    /**
     * 出边界交付附件（ISSUE-P3-07 别名隔离契约逐字保留）。
     *
     * 池引用路径：内存条目交付 `copyOf()` 独立副本；落盘条目（ISSUE-P2-24）挂 [BinarySource]
     * 引用——`attachment.data` 按需读回**独立副本**，每次调用互不共享引用。
     *
     * 直接引用池内数组会使「同一池条目的多个引用者共享同一可变 ByteArray」，外部按
     * Closeable 契约 `attachment.clear()/close()` 即清零内层 Header 二进制池，连带损坏
     * 其他引用者与后续保存（去重指纹取自已清零数据）——副本化后池仍为唯一权威源。
     * 去重语义不受影响：保存侧按 flags + 字节内容指纹去重（[com.keepasskey.database.file.KdbxBinaryDeduplicator]），
     * 与实例身份无关。
     */
    private fun emit(resolved: ResolvedBinary) {
        val item = resolved.item
        if (item != null && item.isSpilled) {
            onDone(
                KdbxAttachment(
                    name = key,
                    refIndex = resolved.refIndex,
                    isProtected = isProtected,
                    source = item
                )
            )
        } else if (item != null) {
            onDone(
                KdbxAttachment(
                    name = key,
                    refIndex = resolved.refIndex,
                    isProtected = isProtected,
                    data = item.load().copyOf()
                )
            )
        } else {
            onDone(
                KdbxAttachment(
                    name = key,
                    refIndex = resolved.refIndex,
                    isProtected = isProtected,
                    data = resolved.data ?: ByteArray(0)
                )
            )
        }
    }

    /** 解析结果：池引用（[item] 非空）或内联字节（[data] 非空）二选一。 */
    private class ResolvedBinary(
        val item: InnerHeader.BinaryItem? = null,
        val refIndex: Int,
        val data: ByteArray? = null
    )

    internal companion object {
        /**
         * `Compressed` 属性名（官方 `KdbxFile.cs:194 AttrCompressed = "Compressed"`）。
         * 本仓 [KdbxConstants.Xml] 暂未收录该常量（不在本任务名下文件），故就近提为常量；
         * 见交付报告「需要协调的跨文件改动」——建议后续上收至 `KdbxConstants.Xml.COMPRESSED`。
         */
        const val ATTR_COMPRESSED = "Compressed"

        /**
         * 内联附件在 [KdbxAttachment.refIndex] 上的占位索引。
         *
         * ## 不变量（唯一权威定义，消费方不得各自推断）
         * `refIndex == INLINE_REF_INDEX(-1)` ⟺ **该附件的字节由本实例内联承载，不属于任何池条目**；
         * 反之 `refIndex >= 0` ⟹ 该索引**必然**落在产生它的池范围内（读侧仅在池命中时才保留原索引）。
         * 因池索引恒 ≥ 0，`-1` 是恒不可能成为合法池索引的哨兵。
         *
         * 两个消费点（**全仓穷举，均为守卫式消费，无需额外判空**）：
         * - 写出侧 [KdbxXmlEntrySerializer]：`refIndex in 0 until binaryPoolSize` 才写 `Ref`，
         *   否则写内联 Base64 正文——故**绝不会产出** `<Value Ref="-1">` 这类悬空引用；
         * - 去重器 [com.keepasskey.database.file.KdbxBinaryDeduplicator]：`getOrNull(-1)` 返回 null，
         *   于是内联字节被当作全新内容正常收编入池并回填合法索引。
         */
        const val INLINE_REF_INDEX = -1

        /** 解压输出上限：与整包上限同级（内联附件必然整体位于整包之内）。 */
        private val MAX_INFLATED_ATTACHMENT_BYTES: Long = KdbxFile.MAX_DECOMPRESSED_PAYLOAD_BYTES

        /** GZip 输出缓冲初值上限（4 MiB）：避免小压缩体直接预留过大缓冲。 */
        private const val GZIP_INITIAL_CAP_BYTES = 4L * 1024 * 1024

        /** 压缩比预估系数（用于初始缓冲）：按 4 倍压缩比预估，仍受 [GZIP_INITIAL_CAP_BYTES] 封顶。 */
        private const val GZIP_SIZE_GROWTH_HINT = 4L

        /** 解压拷贝缓冲（8 KiB）。 */
        private const val GZIP_COPY_BUFFER_BYTES = 8 * 1024
    }
}
