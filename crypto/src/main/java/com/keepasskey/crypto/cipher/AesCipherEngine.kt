package com.keepasskey.crypto.cipher

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.exception.CryptoException
import java.io.InputStream
import java.io.OutputStream
import java.security.spec.AlgorithmParameterSpec
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-CBC 对称加密引擎（KDBX 官方默认标准）。
 *
 * **双路径分派**（ISSUE-P3-155 追问 / §147，与 [TwofishCipherEngine] / [ChaCha20CipherEngine] 同构）：
 * - **原生路径**：RustCrypto `aes` 内核（[NativeAes]，aarch64 走 ARMv8 密码学扩展）；
 * - **兜底路径**：平台 JCE——原生不可用（个别机型缺 ABI / 宿主未注入）时回退，
 *   语义与接线前逐字节一致（差分用例锁定）。
 *
 * **性能状态**：内核级吞吐显著领先（真机 158.7 / 249.2 MB/s），但端到端生产形态是否净收益
 * 取决于跨 JNI 边界代价（详见 `docs/records/真机吞吐实测记录_2026-09-17.md` §7 与
 * `docs/architecture/已知工程限界.md` §17）——该对照须以**连续多轮**实测为准，单轮不作结论。
 */
class AesCipherEngine : CipherEngine {

    override val cipherUuid: KdbxUuid = KdbxConstants.Cipher.AES_256_CBC
    override val name: String = "AES-256 (CBC)"
    override val ivLength: Int = KdbxConstants.Cipher.BLOCK_CIPHER_IV_LENGTH

    override fun encrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        if (NativeAes.available) {
            return encryptNative(key, iv, data)
        }
        return try {
            val cipher = initCipher(Cipher.ENCRYPT_MODE, key, iv)
            cipher.doFinal(data)
        } catch (e: Exception) {
            throw CryptoException.CipherException("AES-256 加密失败", e)
        }
    }

    override fun decrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        if (NativeAes.available) {
            return decryptNative(key, iv, data)
        }
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
        if (NativeAes.available) {
            // 原生侧走与解密侧对称的分块骨架（每 64 KiB 一段，链值随段推进）。
            // `ownedSecrets`：原生变换**惰性**读取密钥，故流必须自持副本并负责擦除
            // （契约背景见 `CbcDecryptingInputStream.ownedSecrets` 的 KDoc，§147 整改）
            val ownedKey = key.copyOf()
            return CbcEncryptingOutputStream(
                sink = outputStream,
                key = ownedKey,
                iv = iv,
                transform = { k, i, d -> NativeAes.encryptBlocks(k, i, d) },
                ownedSecrets = listOf(ownedKey)
            )
        }
        val cipher = initCipher(Cipher.ENCRYPT_MODE, key, iv)
        // 兜底：`CipherOutputStream`（JCE 自带分组与填充语义）
        return CipherOutputStream(outputStream, cipher)
    }

    override fun createDecryptingStream(
        inputStream: InputStream,
        key: ByteArray,
        iv: ByteArray
    ): InputStream {
        if (NativeAes.available) {
            // `ownedSecrets`：原生变换**惰性**读取密钥，故流必须自持副本并负责擦除
            // （契约背景见本类 `ownedSecrets` 的 KDoc，§147 整改）
            val ownedKey = key.copyOf()
            return CbcDecryptingInputStream(
                source = inputStream,
                key = ownedKey,
                iv = iv,
                transform = { k, i, d -> NativeAes.decryptBlocks(k, i, d) },
                ownedSecrets = listOf(ownedKey)
            )
        }
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
        //    `KdbxCipherKeyResolver` 的首块解密探针依赖该语义；
        // 4. **密钥材料**：JCE 在 `init` 时即克隆密钥（`SecretKeySpec`），故调用方在建流后
        //    立即擦除自己的密钥数组是安全的（`KdbxFile` 的擦除时序依赖该性质）。
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

    // ==================== 原生路径（整型） ====================

    private fun encryptNative(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val padded = Pkcs7.pad(data)
        return try {
            // `iv.copyOf()`：原生侧把 iv 原地演化为最后一组密文，不得污染调用方数组
            NativeAes.encryptBlocks(key, iv.copyOf(), padded)
        } catch (e: CryptoException.CipherException) {
            throw e
        } catch (e: Exception) {
            throw CryptoException.CipherException("AES-256 加密失败", e)
        } finally {
            Arrays.fill(padded, 0.toByte())
        }
    }

    private fun decryptNative(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        if (data.isEmpty() || data.size % Pkcs7.BLOCK_SIZE != 0) {
            throw CryptoException.CipherException(
                "AES-256 解密失败：密文长度非分组整数倍（${data.size} 字节）"
            )
        }
        val plain = try {
            NativeAes.decryptBlocks(key, iv.copyOf(), data)
        } catch (e: CryptoException.CipherException) {
            throw e
        } catch (e: Exception) {
            throw CryptoException.CipherException("AES-256 解密失败", e)
        }
        // 填充只落在最后一个分组：按「全量明文」判定去填充长度，而非只接受单分组
        val unpadded = Pkcs7.unpaddedLength(plain)
        if (unpadded < 0) {
            Arrays.fill(plain, 0.toByte())
            throw CryptoException.CipherException(
                "AES-256 解密失败：PKCS#7 填充非法（密钥或数据被篡改，密文 ${data.size} 字节）"
            )
        }
        val stripped = plain.copyOf(unpadded)
        Arrays.fill(plain, 0.toByte())
        return stripped
    }

    // ==================== 平台 JCE 兜底路径 ====================

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
