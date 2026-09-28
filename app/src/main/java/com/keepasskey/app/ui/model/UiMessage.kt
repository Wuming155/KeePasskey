package com.keepasskey.app.ui.model

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.keepasskey.app.R

/**
 * 轻量界面消息封装：ViewModel 仅持有字符串资源 ID 与格式化参数，
 * 展示层通过 stringResource 解析，保证多语言环境下不漏出硬编码文案。
 */
data class UiMessage(
    @StringRes val resId: Int,
    val args: List<Any> = emptyList(),
    /**
     * ISSUE-P2-357 AC②：软删除类消息附带「撤销」出口——true 时 Snackbar 呈现撤销动作，
     * 用户触发 `SnackbarResult.ActionPerformed` 后由宿主调用恢复入口；默认 false（其余消息无撤销）。
     */
    val undoable: Boolean = false,
    /**
     * ISSUE-P2-353 AC④：**类型化**错误标记——错误样式（配色 / 形态）只认本字段，
     * 严禁按展示文案内容推断（如 `contains("失败")` 切英文语言后会把失败条目渲染成成功样式）。
     * 生产者在构造失败反馈时显式传 `isError = true`；成功反馈走默认值 false。
     */
    val isError: Boolean = false,
    /**
     * ISSUE-P3-360 AC③：敏感复制成功消息附带的「剪贴板自动清空」倒计时秒数。
     * null = 不附清空提示（非敏感通道 / 用户关闭自动清空 / 调用方未提供）；
     * 秒数由 [ClipboardClearPolicy] 同源裁决（生产者经 `ClipboardSecurityChannel.scheduledClearSeconds`），
     * 展示层只按下界阈值选秒 / 分钟文案，**不自行编造时长**。
     */
    val clipboardClearSeconds: Int? = null
) {

    companion object {
        /** 清空提示的分钟表述阈值（秒）：≥ 该值改用「N 分钟后清空」（与详情页密码口径一致） */
        const val CLEAR_HINT_MINUTES_THRESHOLD = 120
    }
}

/**
 * 在组合环境中将消息解析为当前语言的实际文案。
 * ISSUE-P3-360 AC③：[clipboardClearSeconds] 非 null 时追加「Ns 后自动清空」后缀
 * （≥120 秒改用分钟表述）；后缀只做拼接，不改写基础文案的资源与参数。
 */
@Composable
fun UiMessage.resolveText(): String {
    val base = stringResource(resId, *args.toTypedArray())
    val clearSeconds = clipboardClearSeconds ?: return base
    return if (clearSeconds < UiMessage.CLEAR_HINT_MINUTES_THRESHOLD) {
        base + stringResource(R.string.clipboard_clear_suffix_seconds, clearSeconds)
    } else {
        base + stringResource(R.string.clipboard_clear_suffix_minutes, clearSeconds / 60)
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI。
// 本文件唯一的 Composable 是「资源 ID → 当前语言文案」的解析函数（返回 String，无自绘 UI），
// 故预览用最小 Text 承载其解析结果，验证多语言文案解析链路
@androidx.compose.ui.tooling.preview.Preview(name = "界面消息文案解析 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "界面消息文案解析 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun UiMessagePreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        androidx.compose.material3.Text(
            text = com.keepasskey.app.ui.model.UiMessage(
                resId = com.keepasskey.app.R.string.btn_close
            ).resolveText()
        )
    }
}
