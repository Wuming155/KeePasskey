package com.keepasskey.core.model

/**
 * 自动输入配置
 */
data class KdbxAutoType(
    val enabled: Boolean = true,
    val dataTransferObfuscation: Int = 0,
    val defaultSequence: String = "",
    val associations: List<AutoTypeAssociation> = emptyList()
) {
    data class AutoTypeAssociation(
        val window: String,
        val keystrokeSequence: String
    )
}
