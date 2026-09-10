package com.keepasskey.app.data.importer

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * 导入文本解码器（XML / CSV 共用）。
 *
 * 已知残余面（如实登记，对齐 `ImportContracts.kt` 类 KDoc 的既有声明）：
 * 明文导出文件本质是文本格式，**整份文本必然以 `String` 形式在解析期驻留**，其密码明文
 * 无法像 `CharArray` 那样确定性擦除。本框架的收敛手段是：
 * 1. 解码产物仅存活于解析调用栈，解析结束即失去引用；
 * 2. 密码等敏感字段在**字段映射第一现场**即转为 `CharArray`（见 [SensitiveTextBuffer]），
 *    此后不再参与任何拼接、比较、日志；
 * 3. 原始输入 `ByteArray` 由调用方（控制器）在 `finally` 中清零，尽早移除最原始的明文副本。
 *
 * 编码策略：**仅支持 UTF-8**（可带 BOM）。UTF-16/32 或含 NUL 的伪文本一律 fail-closed，
 * 避免「NUL 填充的乱码」被静默导入。
 */
internal object ImportTextDecoder {

    /** 严格 UTF-8 解码：非法字节序列 / 不支持的 BOM / NUL 伪文本均抛 [ImportEncodingException]。 */
    fun decodeStrictUtf8(bytes: ByteArray): String {
        rejectUnsupportedBom(bytes)
        val text = decode(bytes, CodingErrorAction.REPORT) ?: throw ImportEncodingException(DECODE_FAILED)
        rejectNulPaddedText(text)
        return text
    }

    /**
     * 宽松 UTF-8 解码：非法字节序列替换为 U+FFFD（不崩溃），返回是否发生过替换
     * 供调用方记 [ImportWarningReason.INVALID_UTF8] 警告。
     */
    fun decodeLenientUtf8(bytes: ByteArray): Pair<String, Boolean> {
        rejectUnsupportedBom(bytes)
        val strict = decode(bytes, CodingErrorAction.REPORT)
        val text = strict ?: decode(bytes, CodingErrorAction.REPLACE) ?: throw ImportEncodingException(DECODE_FAILED)
        rejectNulPaddedText(text)
        return text to (strict == null)
    }

    /**
     * 执行一次完整解码。
     *
     * 注意：**不能**使用 `CharsetDecoder.decode(ByteBuffer)` 便捷方法——按 JDK 规范它恒以
     * 替换语义处理非法输入，会绕过 [CodingErrorAction.REPORT]。必须走三段式
     * `decode(in, out, endOfInput)` 重载。
     *
     * ⚠️ **修复记录（ISSUE-P3-19 过程缺陷，由并行队员实测发现）**：原实现写作
     * `.decode(...).throwException()` / `.flush(...).throwException()`，是**全框架致命缺陷**：
     * `CoderResult.throwException()` 对 **UNDERFLOW**（三段式解码的**正常收尾**状态）抛出的是
     * `BufferUnderflowException`——一个 `RuntimeException`，**不是** `CharacterCodingException`，
     * 故下方 `catch (_: CharacterCodingException)` 根本接不住 → **任何输入（含纯 ASCII 合法文本）
     * 都会抛异常** → 四个数据源全部解析失败，用户侧表现为「导入永远报格式非法」。
     * 现改为显式判定 `CoderResult`：仅 `isError`（MALFORMED/UNMAPPABLE）与 `isOverflow`（输出缓冲不足）
     * 才算失败，UNDERFLOW 是正常收尾。
     *
     * @return 成功返回文本；[CodingErrorAction.REPORT] 下遇非法输入返回 null。
     */
    private fun decode(bytes: ByteArray, onError: CodingErrorAction): String? {
        val offset = bodyOffset(bytes)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(onError)
            .onUnmappableCharacter(onError)
        // UTF-8 最坏情况为 1 字符/字节（ASCII）或 0.5 字符/字节（4 字节序列 → 代理对），
        // 故按字节数分配输出缓冲必然足够。
        val output = CharBuffer.allocate(bytes.size - offset + OUTPUT_SLACK)
        val input = ByteBuffer.wrap(bytes, offset, bytes.size - offset)

        val decoded = decoder.decode(input, output, true)
        if (decoded.isError || decoded.isOverflow) return null
        val flushed = decoder.flush(output)
        if (flushed.isError || flushed.isOverflow) return null

        output.flip()
        return output.toString()
    }

    /** UTF-8 BOM 之后的正文偏移；无 BOM 时为 0。 */
    private fun bodyOffset(bytes: ByteArray): Int = if (hasUtf8Bom(bytes)) UTF8_BOM_SIZE else 0

    private fun hasUtf8Bom(bytes: ByteArray): Boolean =
        bytes.size >= UTF8_BOM_SIZE &&
            bytes[0] == UTF8_BOM[0] && bytes[1] == UTF8_BOM[1] && bytes[2] == UTF8_BOM[2]

    /**
     * 拒绝 UTF-16/32 BOM：这两类编码的字节流在 UTF-8 视角下多数仍是「合法」的
     * （NUL 与 ASCII 均为合法单字节序列），若不显式拒绝就会导入一整篇乱码。
     */
    private fun rejectUnsupportedBom(bytes: ByteArray) {
        if (bytes.size < BOM_PROBE_BYTES) return
        val b0 = bytes[0].toInt() and BYTE_MASK
        val b1 = bytes[1].toInt() and BYTE_MASK
        val b2 = bytes[2].toInt() and BYTE_MASK
        val b3 = bytes[3].toInt() and BYTE_MASK
        val utf16 = (b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)
        val utf32 = (b0 == 0x00 && b1 == 0x00 && b2 == 0xFE && b3 == 0xFF) ||
            (b0 == 0xFF && b1 == 0xFE && b2 == 0x00 && b3 == 0x00)
        if (utf16 || utf32) throw ImportEncodingException(UNSUPPORTED_BOM)
    }

    private fun rejectNulPaddedText(text: String) {
        if (text.indexOf(NUL_CHAR) >= 0) throw ImportEncodingException(NUL_PADDED)
    }

    private const val UTF8_BOM_SIZE = 3
    private const val BOM_PROBE_BYTES = 4
    private const val BYTE_MASK = 0xFF
    private const val OUTPUT_SLACK = 1
    private const val NUL_CHAR = '\u0000'
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /** 诊断用消息（绝不作为用户可见文案，见文件 KDoc）。 */
    private const val DECODE_FAILED = "导入文件不是合法的 UTF-8 文本"
    private const val UNSUPPORTED_BOM = "导入文件使用了 UTF-16/UTF-32 编码，仅支持 UTF-8"
    private const val NUL_PADDED = "导入文件含 NUL 字符，不是合法的 UTF-8 文本"
}
