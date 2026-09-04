package com.keepasskey.crypto.kdf

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.exception.CryptoException

/**
 * KDF 引擎工厂
 */
object KdfFactory {

    private val aesKdf = AesKdfEngine()
    private val argon2d = Argon2KdfEngine(KdfParameters.Argon2.Argon2Type.ARGON2D)
    private val argon2id = Argon2KdfEngine(KdfParameters.Argon2.Argon2Type.ARGON2ID)

    fun getEngine(uuid: KdbxUuid): KdfEngine {
        return when (uuid) {
            KdbxConstants.Kdf.AES_KDF -> aesKdf
            KdbxConstants.Kdf.ARGON2D -> argon2d
            KdbxConstants.Kdf.ARGON2ID -> argon2id
            else -> throw CryptoException.KdfException("不支持的 KDF 算法 UUID: $uuid")
        }
    }
}
