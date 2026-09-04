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
 * Twofish 对称加密引擎
 */
class TwofishCipherEngine : CipherEngine {

    init {
        ChaCha20CipherEngine.ensureBouncyCastle()
    }

    override val cipherUuid: KdbxUuid = KdbxConstants.Cipher.TWOFISH
    override val name: String = "Twofish"

    override fun encrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        return try {
            val cipher = initCipher(Cipher.ENCRYPT_MODE, key, iv)
            cipher.doFinal(data)
        } catch (e: Exception) {
            throw CryptoException.CipherException("Twofish 加密失败", e)
        }
    }

    override fun decrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        return try {
            val cipher = initCipher(Cipher.DECRYPT_MODE, key, iv)
            cipher.doFinal(data)
        } catch (e: Exception) {
            throw CryptoException.CipherException("Twofish 解密失败", e)
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
        val cipher = Cipher.getInstance("Twofish/CBC/PKCS7PADDING", BouncyCastleProvider.PROVIDER_NAME)
        cipher.init(mode, SecretKeySpec(key, "Twofish"), IvParameterSpec(iv))
        return cipher
    }
}
