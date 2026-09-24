package com.keepasskey.crypto.passkey

import com.keepasskey.core.model.PasskeyData
import com.keepasskey.crypto.exception.CryptoException
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.pkcs.RSAPublicKey
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECNamedDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory
import java.math.BigInteger
import java.util.Arrays
import java.util.Base64

/**
 * Passkey 私钥 / 公钥编解码与解析协作单元（自 [PasskeyCryptoEngine] 纯结构性下沉）。
 *
 * 职责边界：
 * 1. 生成侧敏感编码：ES256 标量定长 hex 编码、通用 Base64 → [CharArray]（全程零 String 中间量）；
 * 2. 签名侧 EC 私钥解析与标量有效域校验（d ∈ [1, n-1]，fail-closed）；
 * 3. 公钥字节流解析（EC 坐标点 / Ed25519 raw / RSA 模数与指数）。
 *
 * 本对象仅承载无状态纯函数，所有敏感中间缓冲的显式清零语义与下沉前逐字一致。
 */
internal object PasskeyKeyCodec {

    /** ES256 私钥标量的定长 hex 字符数（256 位 → 64 hex 字符，等价 String.format("%064x")） */
    internal const val EC_SCALAR_HEX_CHARS = 64

    /** 小写 hex 字母表（公开字母表常量，非敏感数据） */
    private const val HEX_DIGITS = "0123456789abcdef"

    private val ecParams: X9ECParameters = SECNamedCurves.getByName("secp256r1")
    private val domainParams = ECDomainParameters(ecParams.curve, ecParams.g, ecParams.n, ecParams.h)

    /**
     * 将非负整数编码为定长 [digits] 位小写 hex 的 [CharArray]（零 String 中间量），
     * 左侧不足补 '0'，结果等价于 `String.format("%0${digits}x", value)`。
     * BigInteger 的二进制副本在 finally 中立即清零。
     */
    internal fun scalarToHexChars(value: BigInteger, digits: Int): CharArray {
        require(value.signum() >= 0) { "仅支持非负整数编码" }
        val magnitude = value.toByteArray()
        try {
            // 去除 two's-complement 符号位前导 0x00（值本身非负，最高位字节 0x00 均为符号填充）
            var start = 0
            while (start < magnitude.size - 1 && magnitude[start] == 0.toByte()) start++
            val magLen = magnitude.size - start
            require(magLen * 2 <= digits) { "数值超出定长 hex 编码容量: 需 ${magLen * 2} 位 > $digits 位" }
            val chars = CharArray(digits) { '0' }
            var ci = digits - 1
            for (i in magnitude.size - 1 downTo start) {
                val b = magnitude[i].toInt() and 0xFF
                // 自右向左回填：先写低半字节（占较高索引），再写高半字节（占较低索引）
                chars[ci--] = HEX_DIGITS[b and 0x0F]
                chars[ci--] = HEX_DIGITS[b ushr 4]
            }
            return chars
        } finally {
            Arrays.fill(magnitude, 0.toByte())
        }
    }

    /**
     * 将字节流以标准 Base64 编码为 [CharArray]（零 String 中间量；Base64 输出恒为 ASCII，
     * 逐字节映射无损且可经 UTF-8 往还），中间编码字节副本在 finally 中立即清零。
     */
    internal fun base64ToChars(bytes: ByteArray): CharArray {
        val encoded = Base64.getEncoder().encode(bytes)
        try {
            return CharArray(encoded.size) { encoded[it].toInt().toChar() }
        } finally {
            Arrays.fill(encoded, 0.toByte())
        }
    }

    /**
     * 解析 EC (ES256/P-256) 私钥字节流为签名参数。
     * 兼容三种输入形态：32 字节原始标量 / 64 字节 hex 文本字节流 / PKCS#8 DER（失败回退 hex 文本）。
     *
     * P2-9 整改：标量必须满足 d ∈ [1, n-1]（SEC1 §3.2 私钥有效域），越界（含 d=0 / d≥n）
     * 一律 fail-closed 抛出本模块类型化 [CryptoException.InvalidKeyException]——杜绝全零字节流
     * 等病态输入生成非法私钥参与签名运算。显式范围校验为权威检查点，库层 IAE 统一归一为同一异常类型。
     */
    internal fun parseEcPrivateKey(bytes: ByteArray): ECPrivateKeyParameters {
        val privKey = when {
            bytes.size == 32 -> {
                newEcPrivateKey(BigInteger(1, bytes))
            }
            bytes.size == 64 -> {
                // 兼容 hex 字符串对应的 ASCII 字节流
                // （ISSUE-P3-311 项 3：逐字节解析，不再物化为不可擦 String）
                try {
                    newEcPrivateKey(hexTextToBigInteger(bytes))
                } catch (e: CryptoException.InvalidKeyException) {
                    throw e
                } catch (e: Exception) {
                    newEcPrivateKey(BigInteger(1, bytes))
                }
            }
            else -> {
                try {
                    val keyParam = PrivateKeyFactory.createKey(bytes)
                    if (keyParam !is ECPrivateKeyParameters) {
                        throw CryptoException.InvalidKeyException(
                            "私钥 DER 不是 EC 私钥（${keyParam.javaClass.simpleName}），已拒绝解析"
                        )
                    }
                    // ISSUE-P3-311 项 2：钉死曲线——DER 自带域参数，此前仅以 P-256 的 n 判界，
                    // 非 P-256 域的私钥会被错误地放进 ES256 签名运算；fail-closed 拒绝
                    if (keyParam.parameters.curve != domainParams.curve) {
                        throw CryptoException.InvalidKeyException(
                            "v1 legacy 私钥 DER 自带非 P-256 域参数，已拒绝解析（仅支持 ES256/P-256）"
                        )
                    }
                    keyParam
                } catch (e: CryptoException.InvalidKeyException) {
                    throw e
                } catch (e: Exception) {
                    try {
                        // 回退尝试当作 UTF-8 hex 文本
                        // （ISSUE-P3-311 项 3：逐字节解析，不再物化为不可擦 String）
                        newEcPrivateKey(hexTextToBigInteger(bytes))
                    } catch (e2: CryptoException.InvalidKeyException) {
                        throw e2
                    } catch (e2: Exception) {
                        throw CryptoException.InvalidKeyException("无法从字节流解析 EC 私钥（所有形态均失败）", e2)
                    }
                }
            }
        }
        // 权威检查点：显式标量范围校验（不依赖库层构造器的行为）
        validateEcScalarRange(privKey.d)
        return privKey
    }

    /**
     * 以字节面解析 UTF-8 hex 文本为 BigInteger（ISSUE-P3-311 项 3）：
     * 原 `BigInteger(String(bytes, UTF_8), 16)` 把私钥文本物化为**不可擦的 String**；
     * 现逐字节校验并拼装（中间副本 `parsed` 在 finally 中清零）。
     * 严格口径：非零偶数长度、纯 [0-9a-fA-F]；带符号 / 空白等宽松形态不再被本解析接受，
     * 由调用方既有回退路径（原始字节重试）承接——合法密钥 hex 文本语义不变。
     */
    private fun hexTextToBigInteger(bytes: ByteArray): BigInteger {
        if (bytes.isEmpty() || bytes.size % 2 != 0) {
            throw NumberFormatException("hex 文本长度必须为非零偶数: ${bytes.size}")
        }
        val parsed = ByteArray(bytes.size / 2)
        try {
            for (i in parsed.indices) {
                val hi = hexDigitValue(bytes[2 * i])
                val lo = hexDigitValue(bytes[2 * i + 1])
                parsed[i] = ((hi shl 4) or lo).toByte()
            }
            return BigInteger(1, parsed)
        } finally {
            Arrays.fill(parsed, 0.toByte())
        }
    }

    /** 单个 ASCII hex 字符的数值；非法字符抛 NumberFormatException（走调用方回退） */
    private fun hexDigitValue(b: Byte): Int = when (b.toInt()) {
        in '0'.code..'9'.code -> b.toInt() - '0'.code
        in 'a'.code..'f'.code -> b.toInt() - 'a'.code + 10
        in 'A'.code..'F'.code -> b.toInt() - 'A'.code + 10
        else -> throw NumberFormatException("非法 hex 字符: 0x${"%02x".format(b.toInt())}")
    }

    /**
     * 构造 EC 私钥参数；库层对标量越界抛出的 IllegalArgumentException 统一归一为
     * 本模块类型化的 [CryptoException.InvalidKeyException]（fail-closed，不作任何回退放行）。
     */
    private fun newEcPrivateKey(d: BigInteger): ECPrivateKeyParameters {
        return try {
            ECPrivateKeyParameters(d, domainParams)
        } catch (e: IllegalArgumentException) {
            throw CryptoException.InvalidKeyException("EC 私钥标量越界：d 须满足 [1, n-1]，实际值不合法，已拒绝签名运算", e)
        }
    }

    /**
     * EC 私钥标量有效性校验：d ∈ [1, n-1]，越界 fail-closed。
     */
    private fun validateEcScalarRange(d: BigInteger) {
        if (d.signum() < 1 || d >= ecParams.n) {
            throw CryptoException.InvalidKeyException(
                "EC 私钥标量越界：d 须满足 [1, n-1]，实际值不合法，已拒绝签名运算"
            )
        }
    }

    internal fun extractEcPoint(bytes: ByteArray): Pair<ByteArray, ByteArray> {
        return when {
            bytes.size == UNCOMPRESSED_POINT_LENGTH && bytes[0] == UNCOMPRESSED_POINT_TAG -> {
                val x = bytes.copyOfRange(COORDINATE_TAG_LENGTH, COORDINATE_TAG_LENGTH + COORDINATE_LENGTH)
                val y = bytes.copyOfRange(COORDINATE_TAG_LENGTH + COORDINATE_LENGTH, UNCOMPRESSED_POINT_LENGTH)
                Pair(x, y)
            }
            bytes.size == RAW_POINT_LENGTH -> {
                val x = bytes.copyOfRange(0, COORDINATE_LENGTH)
                val y = bytes.copyOfRange(COORDINATE_LENGTH, RAW_POINT_LENGTH)
                Pair(x, y)
            }
            else -> {
                // 尝试解析 X.509 SubjectPublicKeyInfo DER
                val spki = SubjectPublicKeyInfo.getInstance(bytes)
                val pointBytes = spki.publicKeyData.bytes
                if (pointBytes.size == UNCOMPRESSED_POINT_LENGTH && pointBytes[0] == UNCOMPRESSED_POINT_TAG) {
                    Pair(
                        pointBytes.copyOfRange(COORDINATE_TAG_LENGTH, COORDINATE_TAG_LENGTH + COORDINATE_LENGTH),
                        pointBytes.copyOfRange(COORDINATE_TAG_LENGTH + COORDINATE_LENGTH, UNCOMPRESSED_POINT_LENGTH)
                    )
                } else {
                    throw CryptoException.InvalidKeyException("无法从公钥数据解析 EC 坐标点: 大小=${bytes.size}")
                }
            }
        }
    }

    internal fun extractEd25519PublicKey(bytes: ByteArray): ByteArray {
        return when (bytes.size) {
            32 -> bytes
            else -> {
                val spki = SubjectPublicKeyInfo.getInstance(bytes)
                val raw = spki.publicKeyData.bytes
                require(raw.size == 32) { "从 SPKI 提取的 Ed25519 公钥不是 32 字节: ${raw.size}" }
                raw
            }
        }
    }

    internal fun extractRsaModulusAndExponent(bytes: ByteArray): Pair<ByteArray, ByteArray> {
        val rsaPub = try {
            val spki = SubjectPublicKeyInfo.getInstance(bytes)
            RSAPublicKey.getInstance(spki.parsePublicKey())
        } catch (e: Exception) {
            ASN1InputStream(bytes).use { stream ->
                RSAPublicKey.getInstance(stream.readObject())
            }
        }
        return Pair(rsaPub.modulus.toByteArray(), rsaPub.publicExponent.toByteArray())
    }

    /**
     * 库内公钥字节流 → **DER 编码的 X.509 `SubjectPublicKeyInfo`（SPKI）**。
     *
     * 用途：注册响应的 `response.publicKey` 按 W3C WebAuthn 规范必须是 SPKI DER——
     * `AuthenticatorAttestationResponse.getPublicKey()` 明确定义为「DER-encoded
     * SubjectPublicKeyInfo」。本仓此前该字段直接下发 **COSE_Key CBOR**（与
     * `attestationObject` 内的公钥同一份），与参考实现 Monica
     * （`keyPair.public.encoded`，即 Java `PublicKey.getEncoded()` 的 SPKI）不一致：
     * 在「同设备 / 同浏览器 / 同站点，Monica 成功而本仓失败」的对照中，这是唯一
     * 已知的响应材料实质分歧点。
     *
     * 输入形态与 [extractEcPoint] / [extractEd25519PublicKey] 一致（即库内既有存储形态）：
     * - ES256：未压缩点 `0x04 || X || Y`（65 字节）→ 包成 SPKI；
     * - Ed25519：32 字节 raw 公钥 → 包成 SPKI；
     * - RS256：库内**已存 SPKI**（`SubjectPublicKeyInfoFactory` 生成）→ 原样返回。
     *
     * 注意：`attestationObject` 内的 `credentialPublicKey` **仍必须是 COSE_Key**（见
     * [PasskeyCryptoEngine.coseKeyFor]），两者是**不同字段的不同编码**，不可互换。
     */
    internal fun toSubjectPublicKeyInfo(algorithmId: Int, publicKeyBytes: ByteArray): ByteArray {
        return when (algorithmId) {
            PasskeyData.ALGORITHM_ES256 -> {
                val point = ecParams.curve.decodePoint(publicKeyBytes)
                // **必须**用带 OID 的命名域参数：普通 [domainParams]（`ECDomainParameters(curve,G,n,h)`）
                // 不携带曲线 OID，`SubjectPublicKeyInfoFactory` 只能编出「显式曲线参数」形式的 SPKI，
                // 而 JDK / OpenSSL 等实现**只接受命名曲线** —— 实测该产物会导致
                // `KeyFactory.generatePublic(X509EncodedKeySpec)` 抛
                // `InvalidKeySpecException: Unable to decode key`。
                val namedDomain = ECNamedDomainParameters(X9ObjectIdentifiers.prime256v1, domainParams)
                SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(
                    ECPublicKeyParameters(point, namedDomain)
                ).encoded
            }
            PasskeyData.ALGORITHM_ED25519 -> {
                // 库内为 32 字节 raw；若已是 SPKI 则先解出 raw 再重新包（幂等）
                val raw = extractEd25519PublicKey(publicKeyBytes)
                SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(
                    Ed25519PublicKeyParameters(raw, 0)
                ).encoded
            }
            PasskeyData.ALGORITHM_RS256 -> publicKeyBytes
            else -> throw CryptoException.InvalidKeyException(
                "无法导出 SPKI 公钥：不支持的算法标识 $algorithmId"
            )
        }
    }

/** 坐标分量长度（P-256 每分量 32 字节） */
private const val COORDINATE_LENGTH = 32

/** 未压缩点前缀标记字节长度（即 0x04 占 1 字节） */
private const val COORDINATE_TAG_LENGTH = 1

/** 未压缩 EC 点总长：tag(1) + X(32) + Y(32) */
private const val UNCOMPRESSED_POINT_LENGTH = COORDINATE_TAG_LENGTH + COORDINATE_LENGTH * 2

/** 裸 EC 点总长（无 tag）：X(32) + Y(32) */
private const val RAW_POINT_LENGTH = COORDINATE_LENGTH * 2

/** SEC 1 未压缩点的前缀标记 */
private val UNCOMPRESSED_POINT_TAG: Byte = 0x04
}
