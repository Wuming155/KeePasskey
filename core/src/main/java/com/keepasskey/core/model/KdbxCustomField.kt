package com.keepasskey.core.model

import com.keepasskey.core.security.ProtectedString

/**
 * 条目自定义字段（如 TOTP 密钥、自定义属性、Pin 等）
 */
data class KdbxCustomField(
    val key: String,
    val value: ProtectedString,
    val isProtected: Boolean = value.isProtected
)
