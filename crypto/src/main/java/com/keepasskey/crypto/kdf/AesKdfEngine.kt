package com.keepasskey.crypto.kdf

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.exception.CryptoException
import com.keepasskey.crypto.hash.HashUtil
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES-KDF 密钥派生引擎（KeePass 经典 KDF）
 */
class AesKdfEngine : KdfEngine {

    override val kdfUuid: KdbxUuid = KdbxConstants.Kdf.AES_KDF
    override val name: String = "AES-KDF"

    override fun transform(compositeKey: ByteArray, parameters: KdfParameters): ByteArray {
        val aesParams = parameters as? KdfParameters.Aes
            ?: throw CryptoException.KdfException("参数类型错误，期望 KdfParameters.Aes")

        require(compositeKey.size == 32) { "AES-KDF compositeKey 长度必须为 32 字节" }
        require(aesParams.seed.size == 32) { "AES-KDF seed 长度必须为 32 字节" }

        val buffer = compositeKey.clone()
        try {
            val keySpec = SecretKeySpec(aesParams.seed, "AES")
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec)

            val rounds = aesParams.rounds
            for (r in 0 until rounds) {
                cipher.update(buffer, 0, 32, buffer, 0)
            }

            return HashUtil.sha256(buffer)
        } catch (e: Exception) {
            throw CryptoException.KdfException("AES-KDF 密钥派生失败", e)
        } finally {
            Arrays.fill(buffer, 0.toByte())
        }
    }
}
