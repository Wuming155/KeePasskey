package com.keepasskey.crypto.cipher

import com.keepasskey.core.model.KdbxUuid
import java.io.InputStream
import java.io.OutputStream

/**
 * 分组对称加密引擎接口
 */
interface CipherEngine {
    val cipherUuid: KdbxUuid
    val name: String

    /**
     * 该算法要求的 EncryptionIV / Nonce 长度（字节）。
     * KDBX4 规范要求 Header 字段 7 的长度等于所选算法的 IV 长度
     * （ChaCha20 = 12，AES-256-CBC / Twofish = 16，对齐官方 KeePass 2.61.1 各 Cipher 构造器硬校验）。
     */
    val ivLength: Int

    fun encrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray
    fun decrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray

    fun createEncryptingStream(outputStream: OutputStream, key: ByteArray, iv: ByteArray): OutputStream
    fun createDecryptingStream(inputStream: InputStream, key: ByteArray, iv: ByteArray): InputStream
}
