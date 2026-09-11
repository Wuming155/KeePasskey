package com.keepasskey.crypto.passkey

import com.keepasskey.crypto.exception.CryptoException
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.pkcs.RSAPublicKey
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyFactory
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
                try {
                    newEcPrivateKey(BigInteger(String(bytes, Charsets.UTF_8), 16))
                } catch (e: CryptoException.InvalidKeyException) {
                    throw e
                } catch (e: Exception) {
                    newEcPrivateKey(BigInteger(1, bytes))
                }
            }
            else -> {
                try {
                    val keyParam = PrivateKeyFactory.createKey(bytes) as ECPrivateKeyParameters
                    keyParam
                } catch (e: Exception) {
                    try {
                        // 回退尝试当作 UTF-8 hex 文本
                        newEcPrivateKey(BigInteger(String(bytes, Charsets.UTF_8), 16))
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
            bytes.size == 65 && bytes[0] == 0x04.toByte() -> {
                val x = bytes.copyOfRange(1, 33)
                val y = bytes.copyOfRange(33, 65)
                Pair(x, y)
            }
            bytes.size == 64 -> {
                val x = bytes.copyOfRange(0, 32)
                val y = bytes.copyOfRange(32, 64)
                Pair(x, y)
            }
            else -> {
                // 尝试解析 X.509 SubjectPublicKeyInfo DER
                val spki = SubjectPublicKeyInfo.getInstance(bytes)
                val pointBytes = spki.publicKeyData.bytes
                if (pointBytes.size == 65 && pointBytes[0] == 0x04.toByte()) {
                    Pair(pointBytes.copyOfRange(1, 33), pointBytes.copyOfRange(33, 65))
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
}
