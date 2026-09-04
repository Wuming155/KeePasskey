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

    fun encrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray
    fun decrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray

    fun createEncryptingStream(outputStream: OutputStream, key: ByteArray, iv: ByteArray): OutputStream
    fun createDecryptingStream(inputStream: InputStream, key: ByteArray, iv: ByteArray): InputStream
}
