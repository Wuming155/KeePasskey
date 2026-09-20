package com.keepasskey.app.passkey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 凭据创建链路 fail-closed 拒绝的原因呈现页（ISSUE-P2-220）。
 *
 * ## 为何是**整页**而非对话框
 *
 * 本页直接渲染在 [BaseCredentialActivity] 自己那个已施加 `FLAG_SECURE` +
 * `HIDE_OVERLAY_WINDOWS` 的**受保护窗口**内：Compose 的 `AlertDialog` 会另开一个对话框窗口，
 * 其 `FLAG_SECURE` 不会从 Activity 窗口传播（见 `SecureDialog`），整页渲染则无此缺口。
 *
 * ## 语义
 *
 * 只做一件事：把 [message]（预定义、无插值的拒绝原因文案）如实呈现给用户，由用户确认后
 * 收尾；不存在「继续创建」之类的第二条出路——被门控拒绝的请求不可能被用户点通。
 * 返回键与页面销毁走调用方的默认收尾（`RESULT_CANCELED`），语义一致。
 */
@Composable
internal fun CredentialRejectionScreen(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(28.dp))
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(confirmText)
            }
        }
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@androidx.compose.ui.tooling.preview.Preview(name = "凭据创建拒绝页 - 浅色", showBackground = true)
@Composable
internal fun CredentialRejectionScreenPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        CredentialRejectionScreen(
            title = "无法创建通行密钥",
            message = "预览拒绝原因文案：无法确认调用应用对本站点的归属声明，已拒绝创建通行密钥。",
            confirmText = "知道了",
            onConfirm = {}
        )
    }
}