package com.keepasskey.crypto.cose

/**
 * COSE (RFC 8152 / RFC 9052 / RFC 9053) 与 FIDO2 / WebAuthn 键规范常量定义。
 * 杜绝代码内出现裸数字。
 */
object CoseConstants {
    // 通用参数标签 (COSE Key Common Parameter Labels)
    const val LABEL_KTY: Long = 1L       // 密钥类型标识 (Identification of the key type)
    const val LABEL_KEY_ID: Long = 2L    // 密钥标识符 (Key identification)
    const val LABEL_ALG: Long = 3L       // 算法限制 (Key usage restriction to this algorithm)
    const val LABEL_KEY_OPS: Long = 4L   // 密钥操作集合
    const val LABEL_BASE_IV: Long = 5L   // 基础初始向量

    // 密钥类型 (Key Types - kty)
    const val KTY_OKP: Long = 1L         // 八位字节密钥对 (Octet Key Pair, 例如 Ed25519/X25519)
    const val KTY_EC2: Long = 2L         // 椭圆曲线密钥对 (2-element EC key, 例如 P-256)
    const val KTY_RSA: Long = 3L         // RSA 密钥对
    const val KTY_SYMMETRIC: Long = 4L   // 对称密钥 (Symmetric Key)

    // EC2 与 OKP 专用曲线参数标签
    const val LABEL_CRV: Long = -1L      // 曲线标识 (EC2 / OKP curve identifier)
    const val LABEL_X: Long = -2L        // X 坐标 (EC2) 或公钥字节 (OKP)
    const val LABEL_Y: Long = -3L        // Y 坐标 (EC2 专用)
    const val LABEL_D: Long = -4L        // 私钥数值 (private key)

    // RSA 专用参数标签
    const val LABEL_N: Long = -1L        // 模数 (RSA modulus n)
    const val LABEL_E: Long = -2L        // 公钥指数 (RSA public exponent e)
    const val LABEL_RSA_D: Long = -3L    // 私钥指数 (RSA private exponent d)

    // 曲线定义 (Elliptic Curves)
    const val CRV_P256: Long = 1L        // NIST P-256 (secp256r1)
    const val CRV_P384: Long = 2L        // NIST P-384
    const val CRV_P521: Long = 3L        // NIST P-521
    const val CRV_X25519: Long = 4L      // X25519 (ECDH)
    const val CRV_ED25519: Long = 6L     // Ed25519 (EdDSA)

    // 算法标识 (Algorithms)
    const val ALG_ES256: Long = -7L      // ECDSA with SHA-256 (P-256)
    const val ALG_EDDSA: Long = -8L      // EdDSA (Ed25519)
    const val ALG_RS256: Long = -257L    // RSASSA-PKCS1-v1_5 with SHA-256
}
