package com.keepasskey.crypto.cipher

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.exception.CryptoException
import java.io.InputStream
import java.io.OutputStream
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-CBC 对称加密引擎（KDBX 官方默认标准）
 */
class AesCipherEngine : CipherEngine {

    override val cipherUuid: KdbxUuid = KdbxConstants.Cipher.AES_256_CBC
    override val name: String = "AES-256 (CBC)"
    override val ivLength: Int = KdbxConstants.Cipher.BLOCK_CIPHER_IV_LENGTH

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
        // 加密侧维持 `CipherOutputStream`（ISSUE-P3-155 真机实测：对 ≥512 B 的写入它本就直通
        // `Cipher.update`，64 KiB 分块化无收益——2026-09-17 Redmi 4X 实测 1.0×）。
        return CipherOutputStream(outputStream, cipher)
    }

    override fun createDecryptingStream(
        inputStream: InputStream,
        key: ByteArray,
        iv: ByteArray
    ): InputStream {
        val cipher = Cipher.getInstance(TRANSFORMATION_NO_PADDING)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        // ISSUE-P3-155：`CipherInputStream` 内部固定 512 B 缓冲，把解密侧对平台实现的投递粒度
        // 压到 512 字节（真机实测 15.3 MB/s）；改走既有 [CbcDecryptingInputStream] 分块骨架
        // （64 KiB，真机 111.2 MB/s，7.3×）。要点：
        // 1. 用 **NoPadding** 变换：JCE 对分组整数倍输入的全量解密无「持有块」语义，与
        //    [CbcBlockTransform] 的「全量返回、独立数组」契约兼容（同形先例：
        //    `CbcStreamFramingTest.bcCbcDecryptTransform`）；PKCS#7 去填充由流包装的
        //    [Pkcs7.unpad] 承担（与 Twofish 原生路径共用同一实现）；
        // 2. 链值由 Cipher **自持**，`iv` 形参无需回写（该变换为流实例私闭包，一个流恰一个
        //    Cipher，与「链值随变换推进」的观测结果等价）；
        // 3. 三类错误语义（填充非法 / 长度非分组整数倍 / 空输入）由流包装统一以 `IOException`
        //    抛出，与 JCE 基线逐例对齐（`CbcStreamFramingTest` 与本组合的专属用例双重锁定）——
        //    `KdbxCipherKeyResolver` 的首块解密探针依赖该语义。
        return CbcDecryptingInputStream(
            source = inputStream,
            key = key,
            iv = iv,
            transform = { _, _, data ->
                cipher.update(data)
                    ?: throw CryptoException.CipherException("AES 解密流：Cipher.update 未产出任何字节")
            }
        )
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

        /**
         * 流式解密的分组变换所用变换（ISSUE-P3-155）：**无填充**——PKCS#7 由
         * [CbcDecryptingInputStream] 的 [Pkcs7.unpad] 承担，与 Twofish 原生路径共用同一实现；
         * 若在此使用 PKCS5Padding，`Cipher.update` 会持有末块导致与流骨架的分段契约不匹配。
         */
        private const val TRANSFORMATION_NO_PADDING = "AES/CBC/NoPadding"
    }
}
