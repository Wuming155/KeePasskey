package com.keepasskey.crypto.hash

import com.keepasskey.crypto.exception.CryptoException
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 哈希与 HMAC 运算工具，严格遵循敏感数据擦除规范。
 *
 * HMAC 统一走 JCE（`Mac.getInstance` + `SecretKeySpec`），与项目既有 JCE 用法
 * （InMemoryCipher / OtpEngine）保持一致；RFC 4231 官方向量测试锁定实现正确性。
 */
object HashUtil {

    fun sha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }

    fun sha256(vararg chunks: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (chunk in chunks) {
            digest.update(chunk)
        }
        return digest.digest()
    }

    fun sha512(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-512")
        return digest.digest(data)
    }

    fun sha512(vararg chunks: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-512")
        for (chunk in chunks) {
            digest.update(chunk)
        }
        return digest.digest()
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            mac.doFinal(data)
        } catch (e: Exception) {
            throw CryptoException.HashException("HMAC-SHA256 计算失败", e)
        }
    }

    fun hmacSha256(key: ByteArray, vararg chunks: ByteArray): ByteArray {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            for (chunk in chunks) {
                mac.update(chunk)
            }
            mac.doFinal()
        } catch (e: Exception) {
            throw CryptoException.HashException("HMAC-SHA256 计算失败", e)
        }
    }

    fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        return try {
            val mac = Mac.getInstance("HmacSHA512")
            mac.init(SecretKeySpec(key, "HmacSHA512"))
            mac.doFinal(data)
        } catch (e: Exception) {
            throw CryptoException.HashException("HMAC-SHA512 计算失败", e)
        }
    }
}
