package com.keepasskey.database.exception

import java.io.IOException

/**
 * 当 KDBX 文件损坏、头部 SHA-256 不符、签名/字段长度非法、Base64 损坏或 XML 数据格式错误时抛出的异常。
 * 兼容现有的 [IOException] 捕获逻辑。
 */
class KdbxCorruptFileException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)
