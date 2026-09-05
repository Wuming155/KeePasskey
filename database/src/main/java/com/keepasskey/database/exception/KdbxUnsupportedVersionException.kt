package com.keepasskey.database.exception

import java.io.IOException

/**
 * 当 KDBX 文件主版本不受当前引擎支持（如非 v4 版本）时抛出的异常。
 * 兼容现有的 [IOException] 捕获逻辑。
 */
class KdbxUnsupportedVersionException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)
