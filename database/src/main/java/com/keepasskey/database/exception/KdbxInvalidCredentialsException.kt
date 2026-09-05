package com.keepasskey.database.exception

import java.io.IOException

/**
 * 当主密码、密钥文件不正确或文件头部/数据块 HMAC 认证校验未通过时抛出的异常。
 * 兼容现有的 [IOException] 捕获逻辑。
 */
class KdbxInvalidCredentialsException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)
