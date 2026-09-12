package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.security.BinarySource
import com.keepasskey.core.security.BinaryStore
import com.keepasskey.core.security.BinaryStorePolicy
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.io.LittleEndianUtil
import java.io.ByteArrayInputStream
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
    /**
     * 内层二进制池条目（ISSUE-P2-24）。
     *
     * 字节来源二选一：
     * - **内存副本**（≤ 落盘阈值）：[data] 直接返回常驻数组，逐字保留既有语义；
     * - **落盘引用**（> 阈值）：只保留 [BinaryStore] 的 key，[data] 按需流式读回独立副本，
     *   使大附件库的二进制池不再整批常驻 GC 堆。
     *
     * 无论哪种来源，[data] / [openStream] 交付的字节都与池内状态、与其它引用者保持别名隔离。
     * 构造签名 `(flags, data)` 与既有调用方（去重器 / 单测）逐字兼容。
     */
    class BinaryItem private constructor(
        val flags: Byte,
        private val inlineData: ByteArray,
        private val store: BinaryStore?,
        private val spillKey: String?,
        private val spilledSize: Long
    ) : BinarySource {

        constructor(flags: Byte, data: ByteArray) : this(flags, data, null, null, 0L)

        /** 落盘条目：仅持 key，字节按需读回。 */
        internal constructor(flags: Byte, store: BinaryStore, spillKey: String, size: Long) :
                this(flags, ByteArray(0), store, spillKey, size)

        internal val isSpilled: Boolean
            get() = spillKey != null

        /** 内容字节数（落盘条目不触发读取）。 */
        override val size: Long
            get() = if (spillKey == null) inlineData.size.toLong() else spilledSize

        /** 内容字节（落盘条目每次返回独立副本；内存条目返回常驻副本，语义同既往）。 */
        val data: ByteArray
            get() = load()

        override fun load(): ByteArray =
            spillKey?.let { store!!.load(it) } ?: inlineData

        override fun openStream(): InputStream =
            spillKey?.let { store!!.openStream(it) } ?: ByteArrayInputStream(inlineData)

        override fun contentHash(): Int = hashCache

        private val hashCache: Int by lazy(LazyThreadSafetyMode.NONE) {
            if (spillKey == null) {
                inlineData.contentHashCode()
            } else {
                // 与 java.util.Arrays.hashCode(byte[]) 逐位等价，但流式计算，不整份物化
                var result = 1
                asStream().use { stream ->
                    val buffer = ByteArray(HASH_BUFFER_BYTES)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        for (i in 0 until read) {
                            result = 31 * result + buffer[i]
                        }
                    }
                }
                result
            }
        }

        /**
         * 仅改写保护标志位并复用同一字节来源（ISSUE-P2-24）：
         * 落盘条目保持 store key 不变（零读取），内存条目复用同数组。
         * 供去重器按「条目声明的保护标志」归池，避免为改一个字节标志而整份读回大附件。
         */
        internal fun withFlags(newFlags: Byte): BinaryItem =
            if (spillKey != null) BinaryItem(newFlags, store!!, spillKey, spilledSize)
            else BinaryItem(newFlags, inlineData)

        /** 序列化时直接写出（落盘条目流式拷贝，不整份物化）。 */
        internal fun writeTo(outputStream: OutputStream) {
            if (spillKey == null) {
                if (inlineData.isNotEmpty()) outputStream.write(inlineData)
            } else {
                openStream().use { it.copyTo(outputStream) }
            }
        }

        private fun asStream(): InputStream = openStream()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BinaryItem) return false
            if (flags != other.flags) return false
            if (size != other.size) return false
            if (spillKey != null || other.spillKey != null) return contentHash() == other.contentHash()
            return inlineData.contentEquals(other.inlineData)
        }

        override fun hashCode(): Int {
            var result = flags.toInt()
            result = 31 * result + contentHash()
            return result
        }

        private companion object {
            /** 流式哈希缓冲（8 KiB，与常见页大小对齐，避免逐字节 I/O）。 */
            const val HASH_BUFFER_BYTES = 8 * 1024
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

        // 字段 3: Binaries —— 落盘条目流式写出（字段长度 = 1(flags) + 内容字节数），不整份物化
        for (bin in binaries) {
            writeFieldHeader(outputStream, KdbxConstants.InnerHeaderFieldId.BINARY, bin.size + FLAGS_FIELD_BYTES)
            outputStream.write(bin.flags.toInt())
            bin.writeTo(outputStream)
        }

        // 字段 0: EndOfHeader
        writeField(outputStream, KdbxConstants.InnerHeaderFieldId.END, ByteArray(0))
    }

    companion object {
        private val secureRandom = SecureRandom()

        /**
         * 单字段上限相对整包上限的份额分母（ISSUE-P3-27 子项 1）：单字段 = 整包上限 / 本分母。
         */
        private const val FIELD_CAP_SHARE_DIVISOR = 2L

        /**
         * 内层 Header 单字段长度安全上限（64 MiB = [KdbxFile.MAX_DECOMPRESSED_PAYLOAD_BYTES] / 2）。
         *
         * **取值关系（ISSUE-P3-27 子项 1 显式定义）**：单个字段必然整体包含在受整包上限约束的
         * 解压载荷之内，因此「单字段上限 ≤ 整包上限」是恒真不变量；本上限由整包上限**派生**
         * （而非各写一个字面量），使两者不可能互相矛盾（任意一方漂移都会同步生效或被
         * 伴生对象的初始化期 `require` 拦下）。
         *
         * 取「整包的一半」的取舍：单个附件池条目最多可占整包预算的一半，另一半留给内层
         * Header 其余字段与 XML 正文——上限过低会误拒含单个大附件的合法库，取满整包则
         * 一个字段即可吃光预算、后续字段与 XML 必然解析失败，1/2 是既不误拒又保留解析余量的取值。
         *
         * BINARY 字段承载附件二进制池，上限须远超一切正常附件体积；
         * 其余字段按各自语义另行收窄（见 [INNER_RANDOM_STREAM_ID_FIELD_SIZE] /
         * [MAX_INNER_RANDOM_STREAM_KEY_BYTES]）。内层数据虽位于 HMAC 认证之后，
         * 长度字段仍按不可信输入对待（P0-5 纵深防御）。
         */
        internal val MAX_INNER_FIELD_BYTES: Int =
            (KdbxFile.MAX_DECOMPRESSED_PAYLOAD_BYTES / FIELD_CAP_SHARE_DIVISOR).toInt()

        /** InnerRandomStreamID 字段合法长度（小端 Int32，官方规范固定 4 字节） */
        private const val INNER_RANDOM_STREAM_ID_FIELD_SIZE = 4

        /** InnerRandomStreamKey 字段长度安全上限（官方写入 64 字节） */
        private const val MAX_INNER_RANDOM_STREAM_KEY_BYTES = 1024

        /** BINARY 字段内 flags 前缀长度（1 字节），用于序列化时计算字段总长度。 */
        private const val FLAGS_FIELD_BYTES = 1L

        /**
         * 二进制池条目数安全上限（Wave 12 解析炸弹防线）。
         * 官方实现的附件数量与库规模线性相关，1024 远超一切合法库的附件总量；
         * 超限即视为恶意构造（每条目仍受 [MAX_INNER_FIELD_BYTES] 单字段上限约束）。
         */
        internal const val MAX_BINARY_POOL_ENTRIES = 1024

        /**
         * 二进制池累计字节数安全上限的**设计值**（256 MiB）：按「海量附件条目累计驻留」
         * 独立评估的取值，保留在此作为设计意图留痕，实际生效上限见 [MAX_BINARY_POOL_TOTAL_BYTES]。
         */
        private const val BINARY_POOL_TOTAL_DESIGN_BYTES = 256L * 1024 * 1024

        /**
         * 二进制池累计字节数安全上限 = `min(设计值, 整包上限)` = 128 MiB（ISSUE-P3-27 子项 1 收敛后取值）。
         *
         * 为何收敛：池字节是整包（解压载荷）字节的**子集**——[deserialize] 的生产调用方
         * `KdbxFile` 恒以 `guardPayloadSize` 包裹后的流驱动本方法（见 KdbxFile.kt 中
         * `InnerHeader.deserialize(xmlInputStream)`），因此「池累计 > 整包上限」物理上不可能发生。
         * 原实现把本上限硬编码为 256 MiB（> 整包 128 MiB），是一条**永不生效的死守卫**，
         * 并使「内层上限 vs 整包上限」的取值关系无从判断；现改为由整包上限派生，
         * 使二者不可能互相矛盾。
         *
         * 为何取 `min` 而非直接写整包上限：设计值（256 MiB）是独立评估的结论，保留它使未来
         * 若放宽整包上限（例如支持超大库）时本上限自动跟随而无需再改一处；而当整包上限更严时
         * 本上限自动收敛到整包上限，杜绝「内层上限高于整包上限」的不自洽重现。
         *
         * 防线定位：**本上限相对生产解析路径是 defense-in-depth（非 binding）**——整包上限
         * 恒先于本上限触发（见 [KdbxFile.MAX_DECOMPRESSED_PAYLOAD_BYTES]）。它只在
         * [deserialize] 被不经整包护栏的调用方直接使用时才可能成为 binding 守卫；
         * 经 2026-09-10 全仓 grep 核实，当前生产代码仅 `KdbxFile.loadPayload` 一处调用本方法
         * （恒在护栏内），其余调用方均为单测（直接喂 `ByteArrayInputStream`），
         * 故本上限当前不构成任何生产文件的接受/拒绝判定依据，收敛它不改变任何库的可解析性。
         *
         * 即使每条目均满足单字段上限，海量条目仍可累积出巨量内存驻留；
         * 总量封顶将恶意文件的资源消耗约束在常数界内。
         */
        internal val MAX_BINARY_POOL_TOTAL_BYTES: Long =
            minOf(BINARY_POOL_TOTAL_DESIGN_BYTES, KdbxFile.MAX_DECOMPRESSED_PAYLOAD_BYTES)

        init {
            // ISSUE-P3-27 子项 1：上限取值关系不变量（初始化期 fail-fast，杜绝常量漂移悄悄制造语义不自洽）。
            // 失败即代表常量被人为改坏（非用户输入所致），宁可启动即暴露，也不退化为永不生效的死守卫。
            require(MAX_INNER_FIELD_BYTES.toLong() <= MAX_BINARY_POOL_TOTAL_BYTES) {
                "内层 Header 单字段上限($MAX_INNER_FIELD_BYTES)必须 ≤ 二进制池累计上限($MAX_BINARY_POOL_TOTAL_BYTES)，" +
                        "否则单个合法字段无法落入池预算"
            }
            require(MAX_BINARY_POOL_TOTAL_BYTES <= KdbxFile.MAX_DECOMPRESSED_PAYLOAD_BYTES) {
                "二进制池累计上限($MAX_BINARY_POOL_TOTAL_BYTES)必须 ≤ 整包上限" +
                        "(${KdbxFile.MAX_DECOMPRESSED_PAYLOAD_BYTES})，否则该守卫永不生效"
            }
        }

        fun createDefault(): InnerHeader {
            val key = ByteArray(64)
            secureRandom.nextBytes(key)
            return InnerHeader(
                innerRandomStreamId = KdbxConstants.InnerRandomStream.CHACHA20,
                innerRandomStreamKey = key
            )
        }

        /**
         * 解析内层 Header。
         *
         * ISSUE-P2-24：[binaryStore] 非空时，超过 [spillThresholdBytes] 的 BINARY 字段
         * **流式落盘**，池中仅保留 store key，不再整份驻留内存；传 null（或字段不超阈值）
         * 时行为与既往逐字一致。落盘字段的长度校验、条目数与累计字节数封顶守卫全部保留。
         */
        fun deserialize(
            inputStream: InputStream,
            binaryStore: BinaryStore? = null,
            spillThresholdBytes: Long = BinaryStorePolicy.DEFAULT_THRESHOLD_BYTES
        ): InnerHeader {
            var streamId = KdbxConstants.InnerRandomStream.CHACHA20
            var streamKey = ByteArray(64)
            val binaries = mutableListOf<BinaryItem>()
            // Wave 12 解析炸弹防线：二进制池条目数与累计字节数双封顶
            var binaryPoolTotalBytes = 0L

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

                if (fieldId == KdbxConstants.InnerHeaderFieldId.END) {
                    break
                }

                // 大附件字段：长度已知，直接流式落盘，不整份物化（ISSUE-P2-24）
                if (fieldId == KdbxConstants.InnerHeaderFieldId.BINARY &&
                    fieldLen > FLAGS_FIELD_BYTES.toInt()
                ) {
                    enforceBinaryPoolEntryLimit(binaries.size)
                    val flag = readFlagByte(inputStream)
                    val payloadLen = fieldLen - FLAGS_FIELD_BYTES.toInt()
                    binaryPoolTotalBytes += payloadLen
                    enforceBinaryPoolTotalLimit(binaryPoolTotalBytes)
                    if (binaryStore != null &&
                        BinaryStorePolicy.shouldSpill(payloadLen.toLong(), spillThresholdBytes)
                    ) {
                        val key = binaryStore.storeFromStream(inputStream, payloadLen.toLong())
                        binaries.add(BinaryItem(flag, binaryStore, key, payloadLen.toLong()))
                    } else {
                        val data = LittleEndianUtil.readBytes(inputStream, payloadLen, MAX_INNER_FIELD_BYTES)
                        binaries.add(BinaryItem(flag, data))
                    }
                    continue
                }

                val fieldData = LittleEndianUtil.readBytes(inputStream, fieldLen, MAX_INNER_FIELD_BYTES)

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
                            enforceBinaryPoolEntryLimit(binaries.size)
                            val flag = fieldData[0]
                            val data = fieldData.copyOfRange(1, fieldData.size)
                            binaryPoolTotalBytes += data.size
                            enforceBinaryPoolTotalLimit(binaryPoolTotalBytes)
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

        private fun enforceBinaryPoolEntryLimit(currentCount: Int) {
            if (currentCount >= MAX_BINARY_POOL_ENTRIES) {
                throw KdbxCorruptFileException(
                    "二进制池条目数超出安全上限: ${currentCount + 1}" +
                            "（允许 ≤ $MAX_BINARY_POOL_ENTRIES），疑似解析炸弹"
                )
            }
        }

        private fun enforceBinaryPoolTotalLimit(totalBytes: Long) {
            if (totalBytes > MAX_BINARY_POOL_TOTAL_BYTES) {
                throw KdbxCorruptFileException(
                    "二进制池累计字节超出安全上限（允许 ≤ $MAX_BINARY_POOL_TOTAL_BYTES），疑似解析炸弹"
                )
            }
        }

        private fun readFlagByte(inputStream: InputStream): Byte {
            val value = inputStream.read()
            if (value < 0) throw java.io.EOFException("意外到达流末尾")
            return value.toByte()
        }

        private fun writeField(outputStream: OutputStream, fieldId: Byte, data: ByteArray) {
            writeFieldHeader(outputStream, fieldId, data.size.toLong())
            if (data.isNotEmpty()) {
                outputStream.write(data)
            }
        }

        /** 仅写字段头（fieldId + 小端 Int32 长度）；后续内容由调用方自行流式写出。 */
        private fun writeFieldHeader(outputStream: OutputStream, fieldId: Byte, length: Long) {
            require(length in 0..Int.MAX_VALUE.toLong()) {
                "内层 Header 字段长度超出可编码范围: $length"
            }
            outputStream.write(fieldId.toInt())
            LittleEndianUtil.writeInt(outputStream, length.toInt())
        }
    }
}
