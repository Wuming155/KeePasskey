package com.keepasskey.crypto.exception

/**
 * 密码学模块统一异常基类
 */
sealed class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class CipherException(message: String, cause: Throwable? = null) : CryptoException(message, cause)
    class KdfException(message: String, cause: Throwable? = null) : CryptoException(message, cause)
    class HashException(message: String, cause: Throwable? = null) : CryptoException(message, cause)
    class InvalidKeyException(message: String, cause: Throwable? = null) : CryptoException(message, cause)
}
