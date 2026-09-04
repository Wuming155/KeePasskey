package com.keepasskey.crypto.cipher

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.exception.CryptoException

/**
 * 加密引擎工厂，负责根据算法 UUID 分派对应的对称分组密码引擎
 */
object CipherFactory {

    private val engines = mapOf(
        KdbxConstants.Cipher.AES_256_CBC to AesCipherEngine(),
        KdbxConstants.Cipher.CHACHA20 to ChaCha20CipherEngine(),
        KdbxConstants.Cipher.TWOFISH to TwofishCipherEngine()
    )

    fun getEngine(uuid: KdbxUuid): CipherEngine {
        return engines[uuid] ?: throw CryptoException.CipherException("不支持的密码算法 UUID: $uuid")
    }

    fun isSupported(uuid: KdbxUuid): Boolean {
        return engines.containsKey(uuid)
    }
}
