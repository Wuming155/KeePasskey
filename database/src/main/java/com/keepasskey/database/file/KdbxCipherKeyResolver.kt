package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.crypto.cipher.CipherEngine
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.io.LittleEndianUtil
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Arrays

/**
 * cipherKey 派生变体裁决器（ISSUE-P3-29：自 `KdbxFile.kt` 拆出，纯结构性拆分）。
 *
 * 用首个数据块的解密结果裁决 cipherKey 派生变体：
 * GZIP 压缩库（官方默认）解密产物以 GZIP 魔数 1F 8B 08 开始；未压缩库解密产物
 * 呈现合法的内层 Header 字段序列。错误密钥的解密产物几乎不可能通过结构校验。
 * 两种派生均不合法时抛 [KdbxCorruptFileException]（完整性失败）。
 *
 * D20 异常语义依据：本裁决**只在 `KdbxFile.load` 的头部 HMAC 校验通过之后调用**，
 * 而头部 HMAC 与数据块 HMAC 使用同一 `hmacKey64`——头部 HMAC 通过即证明凭据正确，
 * 官方旧派生（SHA-256 cipherKey）与官方派生共享完全相同的 `hmacKey64`（见
 * [KdbxKeyDerivation.deriveKeys]），故两种派生都解不出合法内层前缀时不可能是口令问题，
 * 只能是文件内容损坏或被篡改。官方对应语义见 KeePass 2.61.1 KdbxFile.Read.cs:157
 * （头部 HMAC 不符 → InvalidCompositeKeyException）与数据段
 * HmacBlockStream.cs:233/264（数据/终止块不符 → InvalidDataException(FileCorrupted)）。
 *
 * 原实现逐字迁移，仅异常类型与文案按 D20 修正；裁决逻辑零变更。
 */
internal object KdbxCipherKeyResolver {

    /** 内层 Header 字段头大小：1 字节字段 ID + 4 字节小端长度 */
    private const val FIELD_HEADER_SIZE = 5

    /** 解密探针所需的最小块大小（一个 AES 分组） */
    private const val MIN_PROBE_BLOCK_SIZE = 16

    /** 解密探针的分块读取缓冲 */
    private const val PROBE_READ_BUFFER_SIZE = 8192

    private val INNER_HEADER_FIELD_IDS = setOf(
        KdbxConstants.InnerHeaderFieldId.END.toInt(),
        KdbxConstants.InnerHeaderFieldId.INNER_RANDOM_STREAM_ID.toInt(),
        KdbxConstants.InnerHeaderFieldId.INNER_RANDOM_STREAM_KEY.toInt(),
        KdbxConstants.InnerHeaderFieldId.BINARY.toInt()
    )

    /**
     * 裁决结果：
     * [activeKey] 为本次解密实际使用的密钥（官方派生或旧派生）；
     * [legacyKeyToWipe] 为被选中的旧派生密钥原数组——调用方在解密流建立（密钥材料已被
     * SecretKeySpec 克隆）后必须立即擦除；官方派生被选中时为 null（由 `KdbxFile.load` 的
     * finally 统一擦除）。
     */
    class Resolution(
        val activeKey: ByteArray,
        val legacyKeyToWipe: ByteArray?
    )

    /**
     * 用首个数据块的解密结果裁决 cipherKey 派生变体。
     *
     * P2-10 整改（TASK-24）：旧派生密钥的生命周期由本方法全权管理——
     * 未被选中的 legacyCipherKey 与裁决失败抛异常路径下的 legacyCipherKey
     * 均在 finally 中统一清零，绝不残留 GC 堆。
     */
    fun resolve(
        cipherEngine: CipherEngine,
        header: KdbxHeader,
        firstBlock: ByteArray?,
        officialKey: ByteArray,
        isGzipCompressed: Boolean,
        deriveLegacyKeys: () -> Pair<ByteArray, ByteArray>
    ): Resolution {
        if (firstBlock == null || firstBlock.size < MIN_PROBE_BLOCK_SIZE) {
            // 块过小无法构成有效探针（正常 KDBX 负载远大于此），按官方派生继续，由后续解析暴露问题
            return Resolution(officialKey, null)
        }
        if (isPlausibleInnerHeaderPrefix(cipherEngine, header.encryptionIv, officialKey, firstBlock, isGzipCompressed)) {
            return Resolution(officialKey, null)
        }
        val (legacyCipherKey, legacyHmacKey) = deriveLegacyKeys()
        var legacyAccepted = false
        try {
            if (isPlausibleInnerHeaderPrefix(cipherEngine, header.encryptionIv, legacyCipherKey, firstBlock, isGzipCompressed)) {
                // 旧派生被选中：原数组交由调用方在解密流建立后擦除（见 Resolution 契约）
                legacyAccepted = true
                return Resolution(legacyCipherKey, legacyCipherKey)
            }
            throw KdbxCorruptFileException(
                "数据解密探针失败：文件已损坏或被篡改（头部认证已通过，凭据正确，故非主密码错误）"
            )
        } finally {
            // 未被选中的旧派生密钥在任何结果路径（含裁决失败抛异常）下统一清零；
            // hmacKey64 属 transformedKey 直接派生物，无论是否选中均立即擦除
            if (!legacyAccepted) {
                Arrays.fill(legacyCipherKey, 0.toByte())
            }
            Arrays.fill(legacyHmacKey, 0.toByte())
        }
    }

    /**
     * 试解密首块并校验其前缀结构：
     * GZIP 压缩库校验魔数（1F 8B 08）；未压缩库校验内层 Header 字段序列前缀。
     * 首块通常并非消息结尾，AES-PKCS5 在收尾 doFinal 时会触发 BadPadding——
     * 逐块读取并在该异常处停止，已解出的前缀对结构校验依然有效。
     */
    private fun isPlausibleInnerHeaderPrefix(
        cipherEngine: CipherEngine,
        encryptionIv: ByteArray,
        cipherKey: ByteArray,
        firstBlock: ByteArray,
        isGzipCompressed: Boolean
    ): Boolean {
        val prefix = try {
            val probe = cipherEngine.createDecryptingStream(ByteArrayInputStream(firstBlock), cipherKey, encryptionIv)
            probe.use { stream ->
                val buffer = ByteArray(PROBE_READ_BUFFER_SIZE)
                val collected = ByteArrayOutputStream()
                try {
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        collected.write(buffer, 0, count)
                    }
                } catch (_: java.io.IOException) {
                    // 解密流收尾异常：截取已解出的前缀继续校验
                }
                collected.toByteArray()
            }
        } catch (_: Exception) {
            return false
        }
        return if (isGzipCompressed) {
            prefix.size >= 3 &&
                    prefix[0] == 0x1F.toByte() &&
                    prefix[1] == 0x8B.toByte() &&
                    prefix[2] == 0x08.toByte()
        } else {
            isPlausibleFieldSequence(prefix)
        }
    }

    /**
     * 校验字节序列是否为合法的内层 Header 字段序列前缀：
     * 字段 ID 必须属于已知集合，字段长度非负且有界，END 字段正常终止；
     * 仅 BINARY 字段允许「长度超出前缀剩余量」——大二进制池可合法跨越后续 HMAC 块延续。
     */
    private fun isPlausibleFieldSequence(prefix: ByteArray): Boolean {
        var offset = 0
        while (offset + FIELD_HEADER_SIZE <= prefix.size) {
            val fieldId = prefix[offset].toInt() and 0xFF
            if (fieldId !in INNER_HEADER_FIELD_IDS) return false
            val length = LittleEndianUtil.bytesToInt(prefix, offset + 1)
            if (length < 0) return false
            offset += FIELD_HEADER_SIZE + length
            if (fieldId == KdbxConstants.InnerHeaderFieldId.END.toInt()) return true
            if (offset > prefix.size) {
                return fieldId == KdbxConstants.InnerHeaderFieldId.BINARY.toInt()
            }
        }
        return offset <= prefix.size
    }
}
