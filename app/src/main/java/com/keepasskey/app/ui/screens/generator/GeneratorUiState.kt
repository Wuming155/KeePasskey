package com.keepasskey.app.ui.screens.generator

import androidx.annotation.StringRes
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.UiMessage

/**
 * 密码生成器运行模式
 */
enum class GeneratorMode(@StringRes val labelRes: Int) {
    RANDOM(R.string.generator_mode_random),
    PASSPHRASE(R.string.generator_mode_passphrase),
    MASK(R.string.generator_mode_mask)
}

/**
 * 密码生成器 UI 状态模型
 */
data class GeneratorUiState(
    val mode: GeneratorMode = GeneratorMode.RANDOM,
    val currentPassword: String = "",
    val entropyBits: Int = 96,
    val strengthLabel: UiMessage = UiMessage(R.string.generator_strength_extreme, listOf(112)),

    // 模式 1: 随机密码参数
    val randomLength: Int = 16,
    val useUpper: Boolean = true,
    val useLower: Boolean = true,
    val useDigits: Boolean = true,
    val useSymbols: Boolean = true,
    val excludeAmbiguous: Boolean = true,

    // 模式 2: 密码短语参数
    val wordCount: Int = 4,
    val separator: String = "-",
    val capitalizeWords: Boolean = true,
    val includeNumberInPassphrase: Boolean = true,

    // 模式 3: 掩码参数
    val maskPattern: String = "xxxx-xxxx-xxxx-xxxx",

    // 历史临时生成记录 (最近 10 条，仅在内存暂存)
    val history: List<String> = emptyList(),
    val userMessage: UiMessage? = null
)
