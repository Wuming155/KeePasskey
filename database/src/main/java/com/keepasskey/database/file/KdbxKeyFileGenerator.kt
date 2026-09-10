package com.keepasskey.database.file

import com.keepasskey.crypto.hash.HashUtil
import java.security.SecureRandom
import java.util.Arrays

/**
 * KeePass 2.x XML 密钥文件生成器（ISSUE-P3-21：建库侧「生成附属密钥文件」假开关整改）。
 *
 * 产出结构与 [KdbxKeyFile] 的官方解析阶梯**严格自洽**（round-trip 由单测锁定），
 * 亦对齐官方 KeePass 2.61.1 `KcpKeyFile.Xml.cs` 的 v2.0 写法：
 * ```
 * <?xml version="1.0" encoding="UTF-8"?>
 * <KeyFile>
 *     <Meta><Version>2.0</Version></Meta>
 *     <Key><Data Hash="XXXXXXXX">64 位十六进制密钥</Data></Key>
 * </KeyFile>
 * ```
 * - v2.0 的 `<Data>` 为**十六进制**（v1.0 才是 Base64）——版本与编码错配会让官方客户端
 *   与 [KdbxKeyFile] 双双解析失败；
 * - `Hash` 属性为密钥 SHA-256 的前 [HASH_PREFIX_BYTES] 字节：官方加载路径强制校验，
 *   [KdbxKeyFile] 同样校验，故必须真实计算而非占位；
 * - 随机源固定为 [SecureRandom]（CSPRNG）：密钥文件是复合密钥的第二因子，
 *   可预测随机数等于把第二因子直接交给攻击者。
 *
 * 敏感数据纪律：32 字节密钥全程以 [ByteArray] 承载并在 `finally` 中显式清零；
 * 中间十六进制文本**即交付制品的内容本身**（用户必须持有该文件的字节才能解锁），
 * 不属于「密钥以不可擦 String 驻留堆内存」的违规面——主密码与派生密钥仍一律不进 String。
 */
object KdbxKeyFileGenerator {

    /** 密钥长度（字节）：与官方 KeyFile 规范及 [KdbxKeyFile] 解析阶梯一致 */
    const val KEY_LENGTH_BYTES: Int = 32

    /** `Hash` 属性长度（字节）：密钥 SHA-256 的前 4 字节（官方同一公式） */
    private const val HASH_PREFIX_BYTES: Int = 4

    private const val XML_VERSION_2_0 = "2.0"
    private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
    private const val HEX_DIGITS = "0123456789ABCDEF"
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0x0F

    /** 预估 XML 长度（纯容量预分配，不做任何语义判断） */
    private const val ESTIMATED_XML_LENGTH = 256

    private val secureRandom = SecureRandom()

    /**
     * 生成全新的 XML 密钥文件字节（32 字节密钥由 [SecureRandom] 抽出）。
     *
     * 返回值归调用方所有：它既是要交付给用户的文件内容，也是复合密钥第二因子的载体；
     * 用毕（写入 SAF 交付用户 / 交由数据库会话克隆缓存后）应由调用方 `fill(0)` 清零。
     */
    fun generate(): ByteArray {
        val key = ByteArray(KEY_LENGTH_BYTES)
        secureRandom.nextBytes(key)
        try {
            return buildXmlKeyFile(key)
        } finally {
            Arrays.fill(key, 0.toByte())
        }
    }

    /** 按官方 v2.0 结构拼装 XML 密钥文件（`Hash` 属性由密钥真实 SHA-256 前 4 字节计算） */
    private fun buildXmlKeyFile(key: ByteArray): ByteArray {
        val hash = HashUtil.sha256(key)
        val hashPrefixHex = try {
            toHex(hash, HASH_PREFIX_BYTES)
        } finally {
            Arrays.fill(hash, 0.toByte())
        }
        val xml = buildString(ESTIMATED_XML_LENGTH) {
            append(XML_DECLARATION).append('\n')
            append("<KeyFile>\n")
            append("    <Meta>\n")
            append("        <Version>").append(XML_VERSION_2_0).append("</Version>\n")
            append("    </Meta>\n")
            append("    <Key>\n")
            append("        <Data Hash=\"").append(hashPrefixHex).append("\">")
            append(toHex(key, key.size))
            append("</Data>\n")
            append("    </Key>\n")
            append("</KeyFile>\n")
        }
        return xml.toByteArray(Charsets.UTF_8)
    }

    /** 取 [bytes] 前 [length] 字节的大写十六进制文本（[KdbxKeyFile] 的十六进制解析大小写不敏感） */
    private fun toHex(bytes: ByteArray, length: Int): String {
        val builder = StringBuilder(length * 2)
        for (index in 0 until length) {
            val value = bytes[index].toInt() and 0xFF
            builder.append(HEX_DIGITS[value ushr NIBBLE_BITS])
            builder.append(HEX_DIGITS[value and NIBBLE_MASK])
        }
        return builder.toString()
    }
}
