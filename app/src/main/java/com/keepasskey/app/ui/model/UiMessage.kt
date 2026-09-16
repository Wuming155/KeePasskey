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

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI。
// 本文件唯一的 Composable 是「资源 ID → 当前语言文案」的解析函数（返回 String，无自绘 UI），
// 故预览用最小 Text 承载其解析结果，验证多语言文案解析链路
@androidx.compose.ui.tooling.preview.Preview(name = "界面消息文案解析 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "界面消息文案解析 - 深色", showBackground = true, uiMode = 0x20 /* UI_MODE_NIGHT_YES */)
@Composable
private fun UiMessagePreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        androidx.compose.material3.Text(
            text = com.keepasskey.app.ui.model.UiMessage(
                resId = com.keepasskey.app.R.string.btn_close
            ).resolveText()
        )
    }
}
