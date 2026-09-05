package com.keepasskey.crypto.cipher

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.exception.CryptoException
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.InputStream
import java.io.OutputStream
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ChaCha20 对称加密引擎（RFC 7539 / ChaCha7539）
 */
class ChaCha20CipherEngine : CipherEngine {

    init {
        ensureBouncyCastle()
    }

    override val cipherUuid: KdbxUuid = KdbxConstants.Cipher.CHACHA20
    override val name: String = "ChaCha20"
    override val ivLength: Int = KdbxConstants.Cipher.CHACHA20_NONCE_LENGTH

    override fun encrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        validateNonceLength(iv)
        return try {
            val cipher = initCipher(Cipher.ENCRYPT_MODE, key, iv)
            cipher.doFinal(data)
        } catch (e: Exception) {
            throw CryptoException.CipherException("ChaCha20 加密失败", e)
        }
    }

    override fun decrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        validateNonceLength(iv)
        return try {
            val cipher = initCipher(Cipher.DECRYPT_MODE, key, iv)
            cipher.doFinal(data)
        } catch (e: Exception) {
            throw CryptoException.CipherException("ChaCha20 解密失败", e)
        }
    }

    override fun createEncryptingStream(
        outputStream: OutputStream,
        key: ByteArray,
        iv: ByteArray
    ): OutputStream {
        validateNonceLength(iv)
        val cipher = initCipher(Cipher.ENCRYPT_MODE, key, iv)
        return CipherOutputStream(outputStream, cipher)
    }

    override fun createDecryptingStream(
        inputStream: InputStream,
        key: ByteArray,
        iv: ByteArray
    ): InputStream {
        validateNonceLength(iv)
        val cipher = initCipher(Cipher.DECRYPT_MODE, key, iv)
        return CipherInputStream(inputStream, cipher)
    }

    /**
     * P0-4 整改：RFC 7539 nonce 恒为 12 字节，长度不符立即失败。
     * 原实现把超长 IV 静默截断为前 12 字节，掩盖了 KdbxFile 侧恒生成 16 字节 IV 的上游缺陷，
     * 产出官方 KeePass（ChaCha20Cipher 构造器对 pbIV12.Length != 12 直接抛出）无法打开的文件；
     * 读取侧对称截断又令自读自写往返永远通过，互操作缺陷被结构性掩盖。
     */
    private fun validateNonceLength(iv: ByteArray) {
        if (iv.size != KdbxConstants.Cipher.CHACHA20_NONCE_LENGTH) {
            throw IllegalArgumentException(
                "ChaCha20 nonce 必须为 " + KdbxConstants.Cipher.CHACHA20_NONCE_LENGTH +
                    " 字节，实际为: " + iv.size
            )
        }
    }

    private fun initCipher(mode: Int, key: ByteArray, iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance("ChaCha7539", BouncyCastleProvider.PROVIDER_NAME)
        cipher.init(mode, SecretKeySpec(key, "ChaCha7539"), IvParameterSpec(iv))
        return cipher
    }

    companion object {
        fun ensureBouncyCastle() {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }
}
