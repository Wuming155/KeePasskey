package com.keepasskey.core.model

import java.util.Base64

/**
 * 通行密钥私钥**文本形态**识别工具（纯 Kotlin，零 BouncyCastle 依赖；全程走字节通道）。
 *
 * ## 为什么需要它
 *
 * 本仓通行密钥的落库 schema 已对齐 KeePassXC / KeePassDX（`KPEX_PASSKEY_PRIVATE_KEY_PEM`），
 * 私钥文本形态为 **PKCS#8 ASN.1 DER 的 PEM 包裹**；而 `PasskeyData` 属 `core` 模块，
 * **不得**依赖 `crypto`（模块依赖单向：`crypto → core`）。因此「这段私钥文本是什么算法」
 * 只能在此以**纯字节嗅探**回答——按 PKCS#8 `AlgorithmIdentifier` 内 OID 的 DER 编码原样匹配；
 * v1 历史驻留形态（hex 标量 / Base64 种子）无 ASN.1 可依，按**文本形态与长度**判定
 * （见 [sniffAlgorithmId]，`ISSUE-P3-214`）。
 *
 * 嗅探结果仅用于填充 [PasskeyData.algorithmId]（展示与分派）；**权威判定**仍由
 * `crypto` 侧的 PKCS#8 解析器在签名时给出（结构非法 / 算法不受支持一律 fail-closed）。
 *
 * ## 敏感纪律（ISSUE-P1-02）
 *
 * 全部 API 只接受/返回 `ByteArray`，**不得**接受或产出 `String`——私钥（含 PEM 文本形态）
 * 绝不物化为不可擦除的不可变字符串。调用方一律经
 * [com.keepasskey.core.security.ProtectedString.useUtf8] 字节通道传入。
 */
object PasskeyKeyText {

    /** PEM 头前缀（`-----BEGIN PRIVATE KEY-----` / `-----BEGIN EC PRIVATE KEY-----` 等） */
    private const val PEM_PREFIX = "-----BEGIN"

    /** OID `1.2.840.10045.2.1`（ecPublicKey） 的 DER 编码 */
    private val OID_DER_EC_PUBLIC_KEY = byteArrayOf(
        0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01
    )

    /** OID `1.3.101.112`（Ed25519） 的 DER 编码 */
    private val OID_DER_ED25519 = byteArrayOf(0x06, 0x03, 0x2B, 0x65, 0x70)

    /** OID `1.2.840.113549.1.1.1`（rsaEncryption） 的 DER 编码 */
    private val OID_DER_RSA_ENCRYPTION = byteArrayOf(
        0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01
    )

    /**
     * PEM 文本里的空白与换行码位（**唯一一组定义**，语义对齐 RFC 7468 §3 的 PEM 空白字符集）。
     * §168 前本对象内另有 `CHAR_LF` / `CHAR_CR` / `CHAR_SPACE` / `CHAR_TAB` 一组同值异名常量，
     * 已并入此处——收敛只挪定义，未改任何取值。
     */
    private const val ASCII_LF = 0x0A
    private const val ASCII_CR = 0x0D
    private const val ASCII_SPACE = 0x20
    private const val ASCII_TAB = 0x09

    /** PEM 标准折行宽度（RFC 7468 建议 64 字符） */
    private const val PEM_LINE_CHARS = 64

    /**
     * v1 ES256 私钥文本的定长 hex 字符数（256 位标量 → 64 hex 字符，
     * 与 `PasskeyKeyCodec.EC_SCALAR_HEX_CHARS` 同一事实的 core 侧声明——
     * `core` 不得依赖 `crypto`，故不能直接引用后者）。
     */
    private const val HEX_SCALAR_CHARS = 64

    /** v1 Ed25519 私钥文本的定长字节数（32 字节原始种子，RFC 8032） */
    private const val ED25519_SEED_BYTES = 32

    /** 文本是否为 PEM 包裹形态（**唯一**判定入口，避免各处各写一份 trim/startsWith） */
    fun isPem(keyTextBytes: ByteArray): Boolean {
        val start = firstNonSpace(keyTextBytes)
        if (start < 0 || keyTextBytes.size - start < PEM_PREFIX.length) return false
        return PEM_PREFIX.indices.all { keyTextBytes[start + it] == PEM_PREFIX[it].code.toByte() }
    }

    /**
     * PEM → DER 字节流；非 PEM 或结构不完整返回 null。
     *
     * 兼容标准 64 列折行（KeePassXC / OpenSSL 产物）与单行不折行两种排版：
     * 逐字节剔除头尾行与空白后 Base64 解码，**不**因非法字符抛异常（返回 null，
     * 由调用方 fail-closed）。
     */
    fun pemToDer(keyTextBytes: ByteArray): ByteArray? {
        if (!isPem(keyTextBytes)) return null
        // 1. 只取首个 "-----BEGIN" 行之后、首个 "-----END" 行之前的内容（逐行剥离头尾）
        val beginLineEnd = indexOfLineBreak(keyTextBytes, firstNonSpace(keyTextBytes))
        if (beginLineEnd < 0) return null
        val endMarker = "-----END".toByteArray(Charsets.US_ASCII)
        val endIndex = indexOf(keyTextBytes, endMarker, beginLineEnd)
        if (endIndex < 0) return null
        // 2. 主体：剔除一切空白字节（换行 / 回车 / 空格 / TAB）
        val body = stripWhitespace(keyTextBytes, beginLineEnd + 1, endIndex)
        if (body.isEmpty()) return null
        return try {
            Base64.getDecoder().decode(body)
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            body.fill(0)
        }
    }

    /**
     * PKCS#8 DER → PEM 文本（`-----BEGIN PRIVATE KEY-----`，标准 64 列折行）。
     *
     * 全程零 String 中间量：Base64 用 `Encoder.encode(ByteArray): ByteArray` 重载，
     * 输出直接落在 CharArray 上（PEM 恒为 ASCII）；编码副本在 `finally` 中清零。
     */
    fun derToPemChars(der: ByteArray, lineLength: Int = PEM_LINE_CHARS): CharArray {
        require(lineLength > 0) { "PEM 行宽必须为正数" }
        val encoded = Base64.getEncoder().encode(der)
        try {
            if (encoded.isEmpty()) return CharArray(0)
            val header = "-----BEGIN PRIVATE KEY-----\n"
            val footer = "-----END PRIVATE KEY-----\n"
            val lineBreaks = (encoded.size + lineLength - 1) / lineLength
            val out = CharArray(header.length + encoded.size + lineBreaks + footer.length)
            var idx = 0
            for (c in header) out[idx++] = c
            var offset = 0
            while (offset < encoded.size) {
                val n = minOf(lineLength, encoded.size - offset)
                for (j in 0 until n) out[idx++] = encoded[offset + j].toInt().toChar()
                out[idx++] = '\n'
                offset += n
            }
            for (c in footer) out[idx++] = c
            return out
        } finally {
            encoded.fill(0)
        }
    }

    /**
     * 从私钥文本字节流嗅探 COSE 算法标识；无法判定返回 null（**绝不抛出**，由调用方按各自口径回退）。
     *
     * 判定顺序（`ISSUE-P3-214`：把 v1 历史驻留形态与外部条目形态一并覆盖）：
     * 1. **PEM** → 解包为 PKCS#8 DER，按 `AlgorithmIdentifier` 内的 OID 判定；
     * 2. **恰 [HEX_SCALAR_CHARS] 个 hex 字符**（v1 ES256 的定长标量文本）→ [PasskeyData.ALGORITHM_ES256]；
     * 3. **Base64**（v1 Ed25519 种子 / RS256 的 PKCS#8 DER）或**裸 DER** → 先按 OID 判定；
     * 4. 仍无 OID 但候选字节恰为 [ED25519_SEED_BYTES] 字节 → [PasskeyData.ALGORITHM_ED25519]
     *    （v1 Ed25519 的 32 字节种子，Base64 文本或裸字节两种承载）。
     *
     * 第 2 步**必须先于** Base64 解码：hex 字母表是 Base64 字母表的真子集，64 字符 hex 文本会被
     * Base64 解码器「成功」解出 48 字节无 OID 的垃圾，进而白白丢掉 v1 ES256 的唯一判据。
     */
    fun sniffAlgorithmId(keyTextBytes: ByteArray): Int? {
        if (isPem(keyTextBytes)) {
            val der = pemToDer(keyTextBytes) ?: return null
            try {
                return sniffDer(der)
            } finally {
                der.fill(0)
            }
        }
        val body = stripWhitespace(keyTextBytes, 0, keyTextBytes.size)
        var decoded: ByteArray? = null
        try {
            if (isHexScalarText(body)) return PasskeyData.ALGORITHM_ES256
            decoded = try {
                Base64.getDecoder().decode(body)
            } catch (_: IllegalArgumentException) {
                // 非 Base64 文本：按裸 DER 处理（候选即 body 自身）
                null
            }
            val candidate = decoded ?: body
            sniffDer(candidate)?.let { return it }
            return if (candidate.size == ED25519_SEED_BYTES) PasskeyData.ALGORITHM_ED25519 else null
        } finally {
            decoded?.fill(0)
            body.fill(0)
        }
    }

    /** PKCS#8 DER 内的 `AlgorithmIdentifier` OID → COSE 算法标识；无已知 OID 返回 null */
    private fun sniffDer(der: ByteArray): Int? = when {
        der.containsSequence(OID_DER_EC_PUBLIC_KEY) -> PasskeyData.ALGORITHM_ES256
        der.containsSequence(OID_DER_ED25519) -> PasskeyData.ALGORITHM_ED25519
        der.containsSequence(OID_DER_RSA_ENCRYPTION) -> PasskeyData.ALGORITHM_RS256
        else -> null
    }

    /** v1 ES256 私钥文本形态判据：**恰**为 [HEX_SCALAR_CHARS] 个 hex 字符（定长标量的 ASCII 承载） */
    private fun isHexScalarText(bytes: ByteArray): Boolean {
        if (bytes.size != HEX_SCALAR_CHARS) return false
        for (b in bytes) {
            if (!isHexDigit(b)) return false
        }
        return true
    }

    private fun isHexDigit(b: Byte): Boolean {
        val v = b.toInt()
        return v in '0'.code..'9'.code || v in 'a'.code..'f'.code || v in 'A'.code..'F'.code
    }

    /** 朴素子序列匹配（DER 长度普遍 < 1 KiB，无需 KMP；仅匹配固定 OID 常量） */
    private fun ByteArray.containsSequence(pattern: ByteArray): Boolean {
        if (pattern.isEmpty() || pattern.size > size) return false
        outer@ for (start in 0..(size - pattern.size)) {
            for (i in pattern.indices) {
                if (this[start + i] != pattern[i]) continue@outer
            }
            return true
        }
        return false
    }

    private fun firstNonSpace(bytes: ByteArray): Int {
        var i = 0
        while (i < bytes.size && (bytes[i].toInt() and 0xFF) <= ASCII_SPACE) i++
        return if (i == bytes.size) -1 else i
    }

    private fun indexOfLineBreak(bytes: ByteArray, from: Int): Int {
        var i = from
        while (i < bytes.size) {
            if ((bytes[i].toInt() and 0xFF) == ASCII_LF) return i
            i++
        }
        return -1
    }

    private fun indexOf(bytes: ByteArray, pattern: ByteArray, from: Int): Int {
        if (pattern.isEmpty() || from < 0) return -1
        outer@ for (start in from..(bytes.size - pattern.size)) {
            for (i in pattern.indices) {
                if (bytes[start + i] != pattern[i]) continue@outer
            }
            return start
        }
        return -1
    }

    /** 剔除 `[from, to)` 区间内的全部空白字节（换行 / 回车 / 空格 / TAB） */
    private fun stripWhitespace(bytes: ByteArray, from: Int, to: Int): ByteArray {
        val start = from.coerceAtLeast(0)
        val end = to.coerceAtMost(bytes.size)
        if (start >= end) return ByteArray(0)
        val out = ByteArray(end - start)
        var n = 0
        for (i in start until end) {
            val b = bytes[i].toInt() and 0xFF
            if (b == ASCII_LF || b == ASCII_CR || b == ASCII_SPACE || b == ASCII_TAB) continue
            out[n++] = bytes[i]
        }
        return if (n == out.size) out else out.copyOf(n)
    }
}
