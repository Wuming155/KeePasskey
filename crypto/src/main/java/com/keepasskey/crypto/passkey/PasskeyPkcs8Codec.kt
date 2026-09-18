package com.keepasskey.crypto.passkey

import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.model.PasskeyKeyText
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.sec.SECObjectIdentifiers
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECNamedDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.asn1.x9.X9ECParameters
import java.math.BigInteger
import java.util.Arrays

/**
 * 通行密钥私钥的 **PKCS#8 / PEM** 编解码协作单元 —— KeePassXC / KeePassDX 互操作面。
 *
 * ## 为什么是 PKCS#8 PEM
 *
 * KeePassXC 的 `KPEX_PASSKEY_PRIVATE_KEY_PEM` 与 KeePassDX 的 `Passkey.privateKeyPem`
 * 都要求私钥文本为 **PKCS#8（PrivateKeyInfo）ASN.1 DER 的 PEM 包裹**；本仓原先把
 * ES256 存成 64 位 hex 标量、Ed25519 存 Base64 种子、RS256 存 Base64 DER —— 结果是
 * **只有本应用读得懂**（互通性为零）。故生成侧统一改为输出 PEM，签名侧统一按下述规则还原。
 *
 * ## 解码产物（签名侧消费口径，全部经字节通道）
 *
 * | 算法 | PKCS#8 内层 | 交付给 [PasskeyCryptoEngine.signAssertion] 的字节 |
 * |---|---|---|
 * | ES256 | SEC1 `ECPrivateKey`（命名曲线 secp256r1） | **32 字节定长大端标量**（命中 Rust 内核快路径） |
 * | Ed25519 | OCTET STRING（32B 种子，兼容 `04 20` 再包一层） | **32 字节原始种子**（命中 Rust 内核快路径） |
 * | RS256 | RSAPrivateKey | **整段 PKCS#8 DER** |
 *
 * ## 敏感纪律（ISSUE-P1-02 延伸）
 *
 * - 编码方向只产出 [CharArray]（PEM 文本恒 ASCII），中间 DER 副本在 `finally` 中清零；
 * - 解码方向全程 `ByteArray`，PEM 文本的 ASCII 副本、DER 副本用毕即清零；
 * - 解析失败一律抛 [IllegalArgumentException]（fail-closed，绝不部分放行）。
 */
internal object PasskeyPkcs8Codec {

    /**
     * 解码结果：COSE 算法标识 + 签名侧可消费的私钥字节（见类 KDoc 的对照表）。
     *
     * 有意不重写 equals/hashCode/toString——承载的是敏感材料，禁止参与相等比较或日志。
     */
    class SigningKey internal constructor(
        val algorithmId: Int,
        val keyBytes: ByteArray
    )

    /** ES256 私钥标量的定长字节数（256 位 → 32 字节） */
    private const val EC_SCALAR_BYTES = 32

    /** Ed25519 种子长度 */
    private const val ED25519_SEED_BYTES = 32

    /** PKCS#8 PrivateKeyInfo 合法版本号：v0（PrivateKeyInfo）/ v1（oneAsymmetricKey，RFC 5958，Ed25519 编码即 v1） */
    private val PKCS8_VERSIONS = setOf(0, 1)

    /** SEC1 ECPrivateKey 结构版本号（v1） */
    private const val SEC1_EC_VERSION = 1

    private val OID_EC_PUBLIC_KEY = X9ObjectIdentifiers.id_ecPublicKey
    private val OID_ED25519 = ASN1ObjectIdentifier("1.3.101.112")
    private val OID_RSA_ENCRYPTION = org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.rsaEncryption

    private val secp256r1Params: X9ECParameters = SECNamedCurves.getByName("secp256r1")

    /**
     * 编码方向使用的**命名曲线**域参数：BC 的 `PrivateKeyInfoFactory` 仅在参数实例为
     * [ECNamedDomainParameters] 时写出曲线 OID（`1.2.840.10045.3.1.7`）；若传普通
     * [ECDomainParameters] 则会内联整段显式曲线参数——那既臃肿，又会让我们自己的
     * 解码器（只认命名曲线）与 KeePassXC 的解析器一起拒收。
     */
    private val namedCurveParams = ECNamedDomainParameters(
        SECObjectIdentifiers.secp256r1,
        ECDomainParameters(
            secp256r1Params.curve,
            secp256r1Params.g,
            secp256r1Params.n,
            secp256r1Params.h
        )
    )

    // ==================== 编码（生成 / 导入） ====================

    /** ES256：标量 → PKCS#8（命名曲线 secp256r1）→ PEM 文本（CharArray，零 String 中间量） */
    fun encodeEcToPem(scalar: BigInteger): CharArray {
        val privateKeyInfo = PrivateKeyInfoFactory.createPrivateKeyInfo(
            ECPrivateKeyParameters(scalar, namedCurveParams)
        )
        val der = privateKeyInfo.encoded
        try {
            return PasskeyKeyText.derToPemChars(der)
        } finally {
            Arrays.fill(der, 0.toByte())
        }
    }

    /** Ed25519：32 字节种子 → PKCS#8（RFC 8410）→ PEM 文本 */
    fun encodeEd25519ToPem(seed: ByteArray): CharArray {
        require(seed.size == ED25519_SEED_BYTES) { "Ed25519 种子必须为 32 字节，实际 ${seed.size}" }
        val privateKeyInfo = PrivateKeyInfoFactory.createPrivateKeyInfo(
            Ed25519PrivateKeyParameters(seed, 0)
        )
        val der = privateKeyInfo.encoded
        try {
            return PasskeyKeyText.derToPemChars(der)
        } finally {
            Arrays.fill(der, 0.toByte())
        }
    }

    /** RS256：BC 私钥参数（CRT 形态）→ PKCS#8 → PEM 文本 */
    fun encodeToPem(privateKey: AsymmetricKeyParameter): CharArray {
        val privateKeyInfo = PrivateKeyInfoFactory.createPrivateKeyInfo(privateKey)
        val der = privateKeyInfo.encoded
        try {
            return PasskeyKeyText.derToPemChars(der)
        } finally {
            Arrays.fill(der, 0.toByte())
        }
    }

    // ==================== 解码（签名） ====================

    /**
     * PEM 私钥文本（CharArray）→ 签名侧私钥字节 + 算法标识。
     *
     * @throws IllegalArgumentException 非 PEM、Base64/DER 结构非法、算法或曲线不受支持（fail-closed）
     */
    fun pemCharsToSigningKey(pemChars: CharArray): SigningKey {
        var ascii: ByteArray? = null
        var der: ByteArray? = null
        try {
            ascii = charsToAsciiBytes(pemChars)
            der = PasskeyKeyText.pemToDer(ascii)
                ?: throw IllegalArgumentException("PEM 私钥解析失败（非 PEM 形态或 Base64 内容非法）")
            return derToSigningKey(der)
        } finally {
            ascii?.fill(0)
            der?.fill(0)
        }
    }

    /**
     * PKCS#8 DER → 签名侧私钥字节 + 算法标识（导入路径的校验入口，同一权威解析）。
     */
    fun derToSigningKey(der: ByteArray): SigningKey {
        val topLevel = parseSequence(der)
        if (topLevel.size() < 3) {
            throw IllegalArgumentException("PKCS#8 结构非法：顶层 SEQUENCE 成员数不足")
        }
        val version = try {
            ASN1Integer.getInstance(topLevel.getObjectAt(0)).value.intValueExact()
        } catch (e: Exception) {
            throw IllegalArgumentException("PKCS#8 结构非法：version 不是整数", e)
        }
        if (version !in PKCS8_VERSIONS) {
            throw IllegalArgumentException("PKCS#8 版本不受支持：$version")
        }

        val algId = try {
            ASN1Sequence.getInstance(topLevel.getObjectAt(1))
        } catch (e: Exception) {
            throw IllegalArgumentException("PKCS#8 结构非法：AlgorithmIdentifier 不是 SEQUENCE", e)
        }
        val oid = try {
            ASN1ObjectIdentifier.getInstance(algId.getObjectAt(0))
        } catch (e: Exception) {
            throw IllegalArgumentException("PKCS#8 结构非法：AlgorithmIdentifier 首元不是 OID", e)
        }
        val keyOctets = try {
            ASN1OctetString.getInstance(topLevel.getObjectAt(2)).octets
        } catch (e: Exception) {
            throw IllegalArgumentException("PKCS#8 结构非法：privateKey 不是 OCTET STRING", e)
        }

        return when {
            OID_EC_PUBLIC_KEY.equals(oid) -> decodeEc(keyOctets, algId)
            OID_ED25519.equals(oid) -> decodeEd25519(keyOctets)
            OID_RSA_ENCRYPTION.equals(oid) -> SigningKey(PasskeyData.ALGORITHM_RS256, der.clone())
            else -> throw IllegalArgumentException("PKCS#8 算法 OID 不受支持：$oid")
        }
    }

    /** ES256：校验命名曲线为 secp256r1，从 SEC1 ECPrivateKey 提取 32 字节定长标量 */
    private fun decodeEc(keyOctets: ByteArray, algId: ASN1Sequence): SigningKey {
        if (algId.size() < 2) {
            throw IllegalArgumentException("EC AlgorithmIdentifier 缺少命名曲线参数")
        }
        val curve = try {
            ASN1ObjectIdentifier.getInstance(algId.getObjectAt(1))
        } catch (e: Exception) {
            throw IllegalArgumentException("EC 命名曲线参数不是 OID（显式曲线参数不受支持）", e)
        }
        if (!SECObjectIdentifiers.secp256r1.equals(curve)) {
            throw IllegalArgumentException("EC 曲线不受支持（仅 secp256r1）：$curve")
        }
        val ecKey = parseSequence(keyOctets)
        if (ecKey.size() < 2) {
            throw IllegalArgumentException("SEC1 ECPrivateKey 结构非法：成员数不足")
        }
        val secVersion = try {
            ASN1Integer.getInstance(ecKey.getObjectAt(0)).value.intValueExact()
        } catch (e: Exception) {
            throw IllegalArgumentException("SEC1 ECPrivateKey 结构非法：version 不是整数", e)
        }
        if (secVersion != SEC1_EC_VERSION) {
            throw IllegalArgumentException("SEC1 ECPrivateKey 版本不受支持：$secVersion")
        }
        var scalar: ByteArray? = null
        try {
            scalar = try {
                ASN1OctetString.getInstance(ecKey.getObjectAt(1)).octets
            } catch (e: Exception) {
                throw IllegalArgumentException("SEC1 ECPrivateKey 结构非法：privateKey 不是 OCTET STRING", e)
            }
            // BigInteger(1, scalar) 无符号解析（前导 0x00 符号填充由编码器自行剥除），标量越界
            // （d ∉ [1, n-1]）由签名侧 PasskeyKeyCodec 的权威检查点 fail-closed，此处不重复校验
            return SigningKey(
                PasskeyData.ALGORITHM_ES256,
                toFixedLength(BigInteger(1, scalar), EC_SCALAR_BYTES)
            )
        } finally {
            scalar?.fill(0)
        }
    }

    /** Ed25519：privateKey OCTET STRING 即 32 字节种子（部分编码器按 RFC 8410 再包一层 OCTET STRING） */
    private fun decodeEd25519(keyOctets: ByteArray): SigningKey {
        val seed = unwrapEd25519Seed(keyOctets)
        if (seed.size != ED25519_SEED_BYTES) {
            throw IllegalArgumentException("Ed25519 种子必须为 32 字节，实际 ${seed.size}")
        }
        return SigningKey(PasskeyData.ALGORITHM_ED25519, seed)
    }

    /**
     * Ed25519 种子剥离：32 字节原样接受；34 字节且前缀为 `0x04 0x20`（DER OCTET STRING 包裹，
     * RFC 8410 §10.3 编码惯例，BC 等采用）时剥壳取种子；其余形态拒绝。
     */
    private fun unwrapEd25519Seed(keyOctets: ByteArray): ByteArray {
        if (keyOctets.size == ED25519_SEED_BYTES) return keyOctets.copyOf()
        if (keyOctets.size == 34 &&
            keyOctets[0] == 0x04.toByte() && keyOctets[1] == 0x20.toByte()
        ) {
            return keyOctets.copyOfRange(2, 34)
        }
        throw IllegalArgumentException("Ed25519 私钥形态不受支持：长度 ${keyOctets.size}")
    }

    /** 顶层 SEQUENCE 解析（流式读取后立即关闭） */
    private fun parseSequence(der: ByteArray): ASN1Sequence {
        try {
            return ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(der))
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("DER 解析失败：${e.javaClass.simpleName}", e)
        }
    }

    /** CharArray（PEM ASCII 文本）→ ByteArray（调用方负责清零） */
    private fun charsToAsciiBytes(chars: CharArray): ByteArray {
        val out = ByteArray(chars.size)
        for (i in chars.indices) {
            val c = chars[i]
            require(c.code <= 0x7F) { "PEM 文本必须为 ASCII" }
            out[i] = c.code.toByte()
        }
        return out
    }

    /** BigInteger → 定长大端字节数组（左补零；越界即 fail-closed 抛出） */
    private fun toFixedLength(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        try {
            val unsigned = if (raw.size == length + 1 && raw[0] == 0.toByte()) {
                raw.copyOfRange(1, raw.size)
            } else {
                raw.copyOf()
            }
            try {
                require(unsigned.size <= length) { "数值超出 $length 字节容量" }
                val out = ByteArray(length)
                System.arraycopy(unsigned, 0, out, length - unsigned.size, unsigned.size)
                return out
            } finally {
                unsigned.fill(0)
            }
        } finally {
            Arrays.fill(raw, 0.toByte())
        }
    }
}
