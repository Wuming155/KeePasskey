package com.keepasskey.crypto.kdf

import com.keepasskey.core.model.KdbxUuid

/**
 * 密钥派生函数统一接口
 */
interface KdfEngine {
    val kdfUuid: KdbxUuid
    val name: String

    /**
     * 对 compositeKey 执行派生变换，返回派生后的 32 字节密钥
     */
    fun transform(compositeKey: ByteArray, parameters: KdfParameters): ByteArray
}
