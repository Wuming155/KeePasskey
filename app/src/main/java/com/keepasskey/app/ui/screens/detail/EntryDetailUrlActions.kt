package com.keepasskey.app.ui.screens.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.keepasskey.app.R
import com.keepasskey.app.ui.AppSnackbarChannel
import com.keepasskey.app.ui.AppSnackbarEvent
import com.keepasskey.app.ui.model.UiMessage

/**
 * ISSUE-P2-353 AC③：「打开网址」的动作裁决（纯函数，JVM 单测直测）。
 *
 * - [LAUNCH]：`http://` / `https://` 网址 → 发 `Intent.ACTION_VIEW` 呼起浏览器；
 * - [COPY_FALLBACK]：其余 scheme（ftp / 自定义 scheme 等）**不发隐式 Intent**，
 *   以及呼起失败（`ActivityNotFoundException` 等）→ 回退为复制网址并如实提示；
 * - [MISSING]：条目未配置网址。
 */
internal enum class OpenUrlAction {
    LAUNCH,
    COPY_FALLBACK,
    MISSING
}

/**
 * ISSUE-P2-353 AC③：仅 `http(s)://` 视为可呼起浏览器的网址。
 * 比较前 trim 并整体小写——`HTTPS://Example.com` 同样放行，`javascript:` / `ftp:` /
 * `android://` 一律不进隐式 Intent 分支。
 */
internal fun resolveOpenUrlAction(rawUrl: String): OpenUrlAction {
    val url = rawUrl.trim()
    if (url.isEmpty()) return OpenUrlAction.MISSING
    val lower = url.lowercase()
    return if (lower.startsWith("http://") || lower.startsWith("https://")) {
        OpenUrlAction.LAUNCH
    } else {
        OpenUrlAction.COPY_FALLBACK
    }
}

/**
 * 「打开网址」执行器：裁决 → 呼起 / 回退复制 → 如实提示（AC③ 全文案见各分支）。
 *
 * `startActivity` 走 [runCatching] 兜底：无浏览器可处理、URL 非法等一律回落复制分支，
 * 绝不向调用方抛裸异常（UI 层错误处理口径）。
 *
 * @param onShowMessage 反馈通道（详情页为 Snackbar 上行口）；null 时 [notify] 改发全局
 *   Snackbar 通道（ISSUE-P3-446），保证「如实提示」在未接线的区域（如头部 Hero）也不丢。
 */
internal fun executeOpenUrl(
    rawUrl: String,
    context: Context,
    onShowMessage: ((UiMessage) -> Unit)?
) {
    val url = rawUrl.trim()
    when (resolveOpenUrlAction(url)) {
        OpenUrlAction.MISSING ->
            notify(UiMessage(R.string.detail_url_missing), onShowMessage)
        OpenUrlAction.COPY_FALLBACK ->
            copyUrlWithNotice(url, context, onShowMessage)
        OpenUrlAction.LAUNCH -> {
            val launched = runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }.isSuccess
            if (launched) {
                notify(UiMessage(R.string.detail_opening_browser), onShowMessage)
            } else {
                copyUrlWithNotice(url, context, onShowMessage)
            }
        }
    }
}

/** 回退分支：复制网址到剪贴板并如实提示（非 http(s) 与呼起失败共用）。 */
private fun copyUrlWithNotice(
    url: String,
    context: Context,
    onShowMessage: ((UiMessage) -> Unit)?
) {
    copyUrlToClipboard(url, context)
    notify(UiMessage(R.string.detail_url_fallback_copied), onShowMessage)
}

/**
 * 回退复制走系统主剪贴板：网址为非敏感值（本就在界面明文展示），
 * 无需 `EXTRA_IS_SENSITIVE` / 调度擦除通道；详情页组件层无剪贴板协作者，
 * 此处直写 Android 剪贴板（与用户名明文通道同一系统落点）。
 */
internal fun copyUrlToClipboard(url: String, context: Context) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("url", url))
}

private fun notify(
    message: UiMessage,
    onShowMessage: ((UiMessage) -> Unit)?
) {
    if (onShowMessage != null) {
        onShowMessage(message)
    } else {
        // 未接 Snackbar 通道的区域改发全局宿主通道（ISSUE-P3-446 清退 Toast）：
        // 本执行器只被主界面外壳内的组合调用，全局宿主必可达
        AppSnackbarChannel.trySend(AppSnackbarEvent(message))
    }
}

/**
 * 组合期绑定 [LocalContext] 的「打开网址」执行器（记忆化，随上下文 / 通道变化重建）。
 * 快捷操作行与头部 URL 文本行共用同一实现，行为不可能分叉。
 */
@Composable
internal fun rememberOpenUrlAction(onShowMessage: ((UiMessage) -> Unit)?): (String) -> Unit {
    val context = LocalContext.current
    return remember(context, onShowMessage) {
        { rawUrl -> executeOpenUrl(rawUrl, context, onShowMessage) }
    }
}
