package com.keepasskey.crypto.hash

import com.keepasskey.crypto.exception.CryptoException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import java.security.MessageDigest

/**
 * 哈希与 HMAC 运算工具，严格遵循敏感数据擦除规范。
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
            val hmac = HMac(SHA256Digest())
            hmac.init(KeyParameter(key))
            hmac.update(data, 0, data.size)
            val result = ByteArray(hmac.macSize)
            hmac.doFinal(result, 0)
            result
        } catch (e: Exception) {
            throw CryptoException.HashException("HMAC-SHA256 计算失败", e)
        }
    }

    fun hmacSha256(key: ByteArray, vararg chunks: ByteArray): ByteArray {
        return try {
            val hmac = HMac(SHA256Digest())
            hmac.init(KeyParameter(key))
            for (chunk in chunks) {
                hmac.update(chunk, 0, chunk.size)
            }
            val result = ByteArray(hmac.macSize)
            hmac.doFinal(result, 0)
            result
        } catch (e: Exception) {
            throw CryptoException.HashException("HMAC-SHA256 计算失败", e)
        }
    }

    fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        return try {
            val hmac = HMac(SHA512Digest())
            hmac.init(KeyParameter(key))
            hmac.update(data, 0, data.size)
            val result = ByteArray(hmac.macSize)
            hmac.doFinal(result, 0)
            result
        } catch (e: Exception) {
            throw CryptoException.HashException("HMAC-SHA512 计算失败", e)
        }
    }
}
