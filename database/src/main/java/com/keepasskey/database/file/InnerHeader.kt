package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.io.LittleEndianUtil
import java.io.ByteArrayOutputStream
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
    data class BinaryItem(
        val flags: Byte,
        val data: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BinaryItem) return false
            if (flags != other.flags) return false
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = flags.toInt()
            result = 31 * result + data.contentHashCode()
            return result
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

        // 字段 3: Binaries
        for (bin in binaries) {
            val bos = ByteArrayOutputStream()
            bos.write(bin.flags.toInt())
            bos.write(bin.data)
            writeField(outputStream, KdbxConstants.InnerHeaderFieldId.BINARY, bos.toByteArray())
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

        fun deserialize(inputStream: InputStream): InnerHeader {
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
                val fieldData = LittleEndianUtil.readBytes(inputStream, fieldLen, MAX_INNER_FIELD_BYTES)

                if (fieldId == KdbxConstants.InnerHeaderFieldId.END) {
                    break
                }

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
                            if (binaries.size >= MAX_BINARY_POOL_ENTRIES) {
                                throw KdbxCorruptFileException(
                                    "二进制池条目数超出安全上限: ${binaries.size + 1}" +
                                            "（允许 ≤ $MAX_BINARY_POOL_ENTRIES），疑似解析炸弹"
                                )
                            }
                            val flag = fieldData[0]
                            val data = fieldData.copyOfRange(1, fieldData.size)
                            binaryPoolTotalBytes += data.size
                            if (binaryPoolTotalBytes > MAX_BINARY_POOL_TOTAL_BYTES) {
                                throw KdbxCorruptFileException(
                                    "二进制池累计字节超出安全上限（允许 ≤ $MAX_BINARY_POOL_TOTAL_BYTES），疑似解析炸弹"
                                )
                            }
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

        private fun writeField(outputStream: OutputStream, fieldId: Byte, data: ByteArray) {
            outputStream.write(fieldId.toInt())
            LittleEndianUtil.writeInt(outputStream, data.size)
            if (data.isNotEmpty()) {
                outputStream.write(data)
            }
        }
    }
}
