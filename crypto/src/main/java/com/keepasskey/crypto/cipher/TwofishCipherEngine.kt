package com.keepasskey.crypto.cipher

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.exception.CryptoException
import java.io.InputStream
import java.io.OutputStream
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Twofish 对称加密引擎（CBC + PKCS#7）。
 *
 * ISSUE-P3-35：优先走原生 Rust 内核（`crypto/src/main/rust/src/twofish_cbc.rs`，
 * RustCrypto `twofish`）。动机：KDBX 三种可选 cipher 中，AES-256-CBC 与 ChaCha20 都在硬件
 * 加速路径上，**只有 Twofish 依赖 BouncyCastle 纯 Java 实现**，而它作用于整库数据流
 * （[createEncryptingStream] / [createDecryptingStream]），是数据面热点而非一次性开销。
 *
 * 兜底：原生不可用（桌面单测未注入宿主库、个别机型缺 ABI）时回退 BouncyCastle——
 * 与接线前**逐字节一致**的实现，零功能缺口。
 *
 * 填充与流式语义（两条路径的分工）：
 * - **原生路径**：原生侧只做分组变换，PKCS#7 与流式包装由 [Pkcs7] /
 *   [CbcEncryptingOutputStream] / [CbcDecryptingInputStream] 承担（单一实现，整型与流式共用）；
 * - **兜底路径**：仍由 JCE 的 `CipherInputStream` / `CipherOutputStream` 承担。
 *   两路径的可观测行为差异由差分类单测锁定（见 `TwofishNativeParityTest`）。
 */
class TwofishCipherEngine : CipherEngine {

    init {
        ChaCha20CipherEngine.ensureBouncyCastle()
    }

    override val cipherUuid: KdbxUuid = KdbxConstants.Cipher.TWOFISH
    override val name: String = "Twofish"
    override val ivLength: Int = KdbxConstants.Cipher.BLOCK_CIPHER_IV_LENGTH

    override fun encrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        return if (NativeTwofish.available) encryptNative(key, iv, data) else encryptJce(key, iv, data)
    }

    override fun decrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        return if (NativeTwofish.available) decryptNative(key, iv, data) else decryptJce(key, iv, data)
    }

    override fun createEncryptingStream(
        outputStream: OutputStream,
        key: ByteArray,
        iv: ByteArray
    ): OutputStream {
        if (!NativeTwofish.available) {
            return CipherOutputStream(outputStream, initCipher(Cipher.ENCRYPT_MODE, key, iv))
        }
        return CbcEncryptingOutputStream(
            sink = outputStream,
            key = key,
            iv = iv,
            transform = { k, i, d -> NativeTwofish.encryptBlocks(k, i, d) }
        )
    }

    override fun createDecryptingStream(
        inputStream: InputStream,
        key: ByteArray,
        iv: ByteArray
    ): InputStream {
        if (!NativeTwofish.available) {
            return CipherInputStream(inputStream, initCipher(Cipher.DECRYPT_MODE, key, iv))
        }
        return CbcDecryptingInputStream(
            source = inputStream,
            key = key,
            iv = iv,
            transform = { k, i, d -> NativeTwofish.decryptBlocks(k, i, d) }
        )
    }

    // ==================== 原生路径 ====================

    private fun encryptNative(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val padded = Pkcs7.pad(data)
        return try {
            NativeTwofish.encryptBlocks(key, iv.copyOf(), padded)
        } catch (e: CryptoException.CipherException) {
            throw e
        } catch (e: Exception) {
            throw CryptoException.CipherException("Twofish 加密失败", e)
        } finally {
            Arrays.fill(padded, 0.toByte())
        }
    }

    private fun decryptNative(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        if (data.isEmpty() || data.size % Pkcs7.BLOCK_SIZE != 0) {
            throw CryptoException.CipherException("Twofish 解密失败：密文长度非分组整数倍（${data.size} 字节）")
        }
        val plain = try {
            NativeTwofish.decryptBlocks(key, iv.copyOf(), data)
        } catch (e: CryptoException.CipherException) {
            throw CryptoException.CipherException("Twofish 解密失败", e)
        } catch (e: Exception) {
            throw CryptoException.CipherException("Twofish 解密失败", e)
        }
        // 填充只落在最后一个分组：按「全量明文」判定去填充长度，而非只接受单分组
        val unpadded = Pkcs7.unpaddedLength(plain)
        if (unpadded < 0) {
            Arrays.fill(plain, 0.toByte())
            throw CryptoException.CipherException(
                "Twofish 解密失败：PKCS#7 填充非法（密钥或数据被篡改，密文 ${data.size} 字节）"
            )
        }
        val stripped = plain.copyOf(unpadded)
        Arrays.fill(plain, 0.toByte())
        return stripped
    }

    // ==================== BouncyCastle 兜底路径 ====================

    private fun encryptJce(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        return try {
            val cipher = initCipher(Cipher.ENCRYPT_MODE, key, iv)
            cipher.doFinal(data)
        } catch (e: Exception) {
            throw CryptoException.CipherException("Twofish 加密失败", e)
        }
    }

    private fun decryptJce(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        return try {
            val cipher = initCipher(Cipher.DECRYPT_MODE, key, iv)
            cipher.doFinal(data)
        } catch (e: Exception) {
            throw CryptoException.CipherException("Twofish 解密失败", e)
        }
    }

    private fun initCipher(mode: Int, key: ByteArray, iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance("Twofish/CBC/PKCS7PADDING", ChaCha20CipherEngine.bouncyCastleProvider())
        cipher.init(mode, SecretKeySpec(key, "Twofish"), IvParameterSpec(iv))
        return cipher
    }
}
