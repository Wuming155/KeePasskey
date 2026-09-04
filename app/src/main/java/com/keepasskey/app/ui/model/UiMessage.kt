package com.keepasskey.app.ui.model

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * 轻量界面消息封装：ViewModel 仅持有字符串资源 ID 与格式化参数，
 * 展示层通过 stringResource 解析，保证多语言环境下不漏出硬编码文案。
 */
data class UiMessage(
    @StringRes val resId: Int,
    val args: List<Any> = emptyList()
)

/**
 * 在组合环境中将消息解析为当前语言的实际文案
 */
@Composable
fun UiMessage.resolveText(): String = stringResource(resId, *args.toTypedArray())
