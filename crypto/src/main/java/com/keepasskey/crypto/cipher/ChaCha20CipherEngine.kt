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

    override fun encrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        return try {
            val cipher = initCipher(Cipher.ENCRYPT_MODE, key, iv)
            cipher.doFinal(data)
        } catch (e: Exception) {
            throw CryptoException.CipherException("ChaCha20 加密失败", e)
        }
    }

    override fun decrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
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
        val cipher = Cipher.getInstance("ChaCha7539", BouncyCastleProvider.PROVIDER_NAME)
        // 若 IV 是 16 字节，ChaCha7539 取前 12 字节作为 nonce（RFC 7539 规范）
        val nonce = if (iv.size > 12) iv.copyOfRange(0, 12) else iv
        cipher.init(mode, SecretKeySpec(key, "ChaCha7539"), IvParameterSpec(nonce))
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
