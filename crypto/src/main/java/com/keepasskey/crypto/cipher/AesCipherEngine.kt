package com.keepasskey.crypto.cipher

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.exception.CryptoException
import java.io.InputStream
import java.io.OutputStream
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-CBC 对称加密引擎（KDBX 官方默认标准）
 */
class AesCipherEngine : CipherEngine {

    override val cipherUuid: KdbxUuid = KdbxConstants.Cipher.AES_256_CBC
    override val name: String = "AES-256 (CBC)"

    override fun encrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        return try {
            val cipher = initCipher(Cipher.ENCRYPT_MODE, key, iv)
            cipher.doFinal(data)
        } catch (e: Exception) {
            throw CryptoException.CipherException("AES-256 加密失败", e)
        }
    }

    override fun decrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        return try {
            val cipher = initCipher(Cipher.DECRYPT_MODE, key, iv)
            cipher.doFinal(data)
        } catch (e: Exception) {
            throw CryptoException.CipherException("AES-256 解密失败", e)
        }
    }

    override fun createEncryptingStream(
        outputStream: OutputStream,
        key: ByteArray,
        iv: ByteArray
    ): OutputStream {
        val cipher = initCipher(Cipher.ENCRYPT_MODE, key, iv)
        return CipherOutputStream(outputStream, cipher)
    }

    override fun createDecryptingStream(
        inputStream: InputStream,
        key: ByteArray,
        iv: ByteArray
    ): InputStream {
        val cipher = initCipher(Cipher.DECRYPT_MODE, key, iv)
        return CipherInputStream(inputStream, cipher)
    }

    private fun initCipher(mode: Int, key: ByteArray, iv: ByteArray): Cipher {
        val secretKey = SecretKeySpec(key, "AES")
        val ivSpec: AlgorithmParameterSpec = IvParameterSpec(iv)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(mode, secretKey, ivSpec)
        return cipher
    }

    companion object {
        private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    }
}
