package com.keepasskey.core.model

/**
 * KDBX 数据库内存保护配置（对应 Meta.MemoryProtection）。
 * 分别控制各标准字段在内存中是否受保护。
 */
data class MemoryProtectionConfig(
    val protectTitle: Boolean = false,
    val protectUserName: Boolean = false,
    val protectPassword: Boolean = true,
    val protectUrl: Boolean = false,
    val protectNotes: Boolean = false
)
