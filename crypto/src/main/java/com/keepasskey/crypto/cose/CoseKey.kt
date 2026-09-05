package com.keepasskey.crypto.cose

import com.keepasskey.crypto.cbor.CborEncoder
import java.util.LinkedHashMap

/**
 * COSE_Key (RFC 8152 / RFC 9052) 公钥构建器。
 * 用于组装 WebAuthn / FIDO2 注册认证中 Attested Credential Data (证明凭据数据) 所需的公钥 CBOR 映射表：
 * - EC2 P-256 (ES256): {1: 2, 3: -7, -1: 1, -2: x(32B), -3: y(32B)}
 * - Ed25519 OKP (EdDSA): {1: 1, 3: -8, -1: 6, -2: pubKey(32B)}
 * - RSA-2048 (RS256): {1: 3, 3: -257, -1: n(模数), -2: e(指数)}
 *
 * 严格保持键插入顺序，确保 CBOR 输出满足确定性编码规则。
 */
object CoseKey {

    /**
     * 构建 EC2 P-256 (ES256) 公钥 COSE_Key 二进制结构
     *
     * 映射关系：
     * 1: 2 (kty: EC2)
     * 3: -7 (alg: ES256)
     * -1: 1 (crv: P-256)
     * -2: x (32 字节 byte string)
     * -3: y (32 字节 byte string)
     *
     * @param x 32 字节 X 轴仿射坐标
     * @param y 32 字节 Y 轴仿射坐标
     * @return 确定性 CBOR 编码字节数组
     */
    fun ec2P256(x: ByteArray, y: ByteArray): ByteArray {
        require(x.size == 32) { "EC2 P-256 X 坐标长度必须为 32 字节，当前: ${x.size}" }
        require(y.size == 32) { "EC2 P-256 Y 坐标长度必须为 32 字节，当前: ${y.size}" }

        val map = LinkedHashMap<Long, Any>(5).apply {
            put(CoseConstants.LABEL_KTY, CoseConstants.KTY_EC2)
            put(CoseConstants.LABEL_ALG, CoseConstants.ALG_ES256)
            put(CoseConstants.LABEL_CRV, CoseConstants.CRV_P256)
            put(CoseConstants.LABEL_X, x)
            put(CoseConstants.LABEL_Y, y)
        }
        return CborEncoder.encodeMap(map)
    }

    /**
     * 构建 OKP Ed25519 (EdDSA) 公钥 COSE_Key 二进制结构
     *
     * 映射关系：
     * 1: 1 (kty: OKP)
     * 3: -8 (alg: EdDSA)
     * -1: 6 (crv: Ed25519)
     * -2: pubKey (32 字节 byte string)
     *
     * @param publicKey 32 字节 Ed25519 原始公钥
     * @return 确定性 CBOR 编码字节数组
     */
    fun ed25519(publicKey: ByteArray): ByteArray {
        require(publicKey.size == 32) { "Ed25519 原始公钥长度必须为 32 字节，当前: ${publicKey.size}" }

        val map = LinkedHashMap<Long, Any>(4).apply {
            put(CoseConstants.LABEL_KTY, CoseConstants.KTY_OKP)
            put(CoseConstants.LABEL_ALG, CoseConstants.ALG_EDDSA)
            put(CoseConstants.LABEL_CRV, CoseConstants.CRV_ED25519)
            put(CoseConstants.LABEL_X, publicKey)
        }
        return CborEncoder.encodeMap(map)
    }

    /**
     * 构建 RSA-2048 (RS256) 公钥 COSE_Key 二进制结构
     *
     * 映射关系：
     * 1: 3 (kty: RSA)
     * 3: -257 (alg: RS256)
     * -1: n (模数 modulus byte string，大端，无多余前导 0x00)
     * -2: e (指数 exponent byte string，大端，无多余前导 0x00)
     *
     * @param n RSA 模数大端字节数组
     * @param e RSA 指数大端字节数组
     * @return 确定性 CBOR 编码字节数组
     */
    fun rsa2048(n: ByteArray, e: ByteArray): ByteArray {
        require(n.isNotEmpty()) { "RSA 模数 n 不能为空" }
        require(e.isNotEmpty()) { "RSA 指数 e 不能为空" }

        val cleanN = stripLeadingZero(n)
        val cleanE = stripLeadingZero(e)

        val map = LinkedHashMap<Long, Any>(4).apply {
            put(CoseConstants.LABEL_KTY, CoseConstants.KTY_RSA)
            put(CoseConstants.LABEL_ALG, CoseConstants.ALG_RS256)
            put(CoseConstants.LABEL_N, cleanN)
            put(CoseConstants.LABEL_E, cleanE)
        }
        return CborEncoder.encodeMap(map)
    }

    /**
     * 规范化大整数字节数组：剥离 BigInteger 序列化时由于正符号位引入的多余 0x00 前导字节
     */
    private fun stripLeadingZero(bytes: ByteArray): ByteArray {
        if (bytes.size > 1 && bytes[0] == 0.toByte()) {
            return bytes.copyOfRange(1, bytes.size)
        }
        return bytes
    }
}
