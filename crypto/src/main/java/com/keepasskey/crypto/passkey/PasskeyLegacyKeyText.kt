package com.keepasskey.crypto.passkey

import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.model.PasskeyKeyText
import com.keepasskey.crypto.exception.CryptoException
import java.math.BigInteger
import java.util.Arrays
import java.util.Base64

/**
 * **历史 v1 私钥文本形态**的协作单元（自断言侧与写路径的共同需求抽出，`ISSUE-P3-213` 立）。
 *
 * v1 schema 把私钥存成「ES256 = 定长 hex 标量 / Ed25519 = Base64 32 字节种子 /
 * RS256 = Base64 PKCS#8 DER」，与现行 KPEX 口径（PKCS#8 PEM）不同。本对象承担两件事：
 *
 * 1. [legacyTextToKeyBytes]：文本 → 原始签名材料字节，供**断言回退路径**消费
 *    （历史条目在迁移完成前仍必须能签名）；
 * 2. [legacyTextToPemChars]：文本 → PKCS#8 PEM，供**写路径就地迁移**消费
 *    （app 层 `PasskeyEntryCoordinator` 在计数器修补的同一受控变换内把 v1 条目整条换新为 KPEX）。
 *
 * 敏感纪律（ISSUE-P1-02）：全程 `ByteArray` 通道，**不物化不可擦除的 String**；
 * 一切中间副本（ASCII 切片 / 解出的 DER）在 `finally` 中清零。
 *
 * 失败语义分两档：解码失败抛 [IllegalArgumentException]（fail-closed，与其它解码入口一致）；
 * 迁移入口 [legacyTextToPemChars] 把一切失败**归一为 null**（fail-safe，见其 KDoc）。
 */
internal object PasskeyLegacyKeyText {

    /** 两侧 ASCII 空白上界（与历史 `trim()` 语义等价的字节判据） */
    private const val ASCII_SPACE = 0x20

    /** 历史 v1 hex 文本的合法字符数区间（1 ~ 32 字节标量） */
    private const val LEGACY_HEX_MIN_CHARS = 2
    private const val LEGACY_HEX_MAX_CHARS = 64

    /** Ed25519 种子长度（RFC 8032） */
    private const val ED25519_SEED_BYTES = 32

    /**
     * 历史 v1 私钥文本字节流 → 原始签名材料字节（ES256 标量 / Ed25519 种子 / RS256 PKCS#8 DER）。
     *
     * 形态判据：整段为 hex 字符且长度在 `[2, 64]` 时按**无符号** hex 解析（奇数长度左对齐补零
     * 半字节，等价 `BigInteger(String, 16)`）；其余先剔除两侧 ASCII 空白再 Base64 解码。
     * 注意 hex 分支要求**整段无空白**（v1 写入形态即如此），带空白的文本走 Base64 分支——
     * 该口径自原 app 层实现逐字搬入，未作改写。
     *
     * @throws IllegalArgumentException 文本既非 hex 也非合法 Base64（fail-closed，由调用方决定处置）
     */
    internal fun legacyTextToKeyBytes(keyTextBytes: ByteArray): ByteArray {
        if (keyTextBytes.size in LEGACY_HEX_MIN_CHARS..LEGACY_HEX_MAX_CHARS &&
            keyTextBytes.all { isHexAsciiDigit(it) }
        ) {
            return hexTextToBytes(keyTextBytes)
        }
        // 先剔除两侧 ASCII 空白再 Base64 解码（与历史 trim() 语义一致，但不物化 String）
        var start = 0
        var end = keyTextBytes.size
        while (start < end && keyTextBytes[start].toInt() <= ASCII_SPACE) start++
        while (end > start && keyTextBytes[end - 1].toInt() <= ASCII_SPACE) end--
        val slice = keyTextBytes.copyOfRange(start, end)
        try {
            return Base64.getDecoder().decode(slice)
        } finally {
            Arrays.fill(slice, 0.toByte())
        }
    }

    /**
     * 历史 v1 私钥文本 → PKCS#8 PEM 文本（`ISSUE-P3-213` 写路径就地迁移的专用入口）。
     *
     * [algorithmId] 为该条目判定的 COSE 算法（扩展键或 [PasskeyKeyText.sniffAlgorithmId] 结果）：
     * - ES256：hex / Base64 标量 → 命名曲线 `secp256r1` 的 PKCS#8；标量有效域复用签名侧同一
     *   权威检查点（[PasskeyKeyCodec.parseEcPrivateKey]，d ∈ [1, n-1]）；
     * - Ed25519：32 字节种子 → RFC 8410 `version = 0` 的 PKCS#8；
     * - RS256：Base64 承载的 PKCS#8 DER → 结构校验通过后原样重包 PEM。
     *
     * 形态与算法不匹配 / 结构非法 / 标量越界 / 算法不受支持一律返回 **null**（fail-safe）：
     * 由调用方放弃迁移并保持原文——绝不写入一份解析不了的 PEM 把条目改成不可用。
     */
    internal fun legacyTextToPemChars(keyTextBytes: ByteArray, algorithmId: Int): CharArray? {
        var raw: ByteArray? = null
        try {
            raw = legacyTextToKeyBytes(keyTextBytes)
            return when (algorithmId) {
                PasskeyData.ALGORITHM_ES256 -> {
                    // 标量越界（d=0 / d≥n）在此即拒绝迁移；权威检查点与签名侧同源
                    PasskeyKeyCodec.parseEcPrivateKey(raw)
                    PasskeyPkcs8Codec.encodeEcToPem(BigInteger(1, raw))
                }
                PasskeyData.ALGORITHM_ED25519 ->
                    if (raw.size == ED25519_SEED_BYTES) PasskeyPkcs8Codec.encodeEd25519ToPem(raw) else null
                PasskeyData.ALGORITHM_RS256 -> rs256DerToPemCharsOrNull(raw)
                else -> null
            }
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: CryptoException.InvalidKeyException) {
            return null
        } finally {
            raw?.fill(0)
        }
    }

    /**
     * PKCS#8 DER → PEM 文本，**仅当**该 DER 是结构合法且算法为 RSA 的 PKCS#8 时返回；
     * 否则 null（迁移前的存在性 / 结构校验，避免把非法文本包成「看似合法」的 PEM）。
     */
    private fun rs256DerToPemCharsOrNull(der: ByteArray): CharArray? {
        val signingKey = try {
            PasskeyPkcs8Codec.derToSigningKey(der)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return try {
            if (signingKey.algorithmId == PasskeyData.ALGORITHM_RS256) {
                PasskeyKeyText.derToPemChars(der)
            } else {
                null
            }
        } finally {
            signingKey.keyBytes.fill(0)
        }
    }

    /** 历史 hex 文本 → 字节（奇数长度左对齐补零半字节，等价 `BigInteger(String, 16)` 的无符号解析） */
    private fun hexTextToBytes(raw: ByteArray): ByteArray {
        val out = ByteArray((raw.size + 1) / 2)
        val offset = out.size * 2 - raw.size
        for (i in out.indices) {
            val hiIndex = 2 * i - offset
            val hi = if (hiIndex >= 0) hexNibble(raw[hiIndex]) else 0
            out[i] = ((hi shl 4) or hexNibble(raw[hiIndex + 1])).toByte()
        }
        return out
    }

    /** 单个 hex ASCII 字符 → 半字节值（非法字符 fail-closed 抛出） */
    private fun hexNibble(b: Byte): Int = when (b.toInt()) {
        in '0'.code..'9'.code -> b.toInt() - '0'.code
        in 'a'.code..'f'.code -> b.toInt() - 'a'.code + 10
        in 'A'.code..'F'.code -> b.toInt() - 'A'.code + 10
        else -> throw IllegalArgumentException("非法 hex 字符: ${b.toInt()}")
    }

    private fun isHexAsciiDigit(b: Byte): Boolean {
        val v = b.toInt()
        return v in '0'.code..'9'.code || v in 'a'.code..'f'.code || v in 'A'.code..'F'.code
    }
}
