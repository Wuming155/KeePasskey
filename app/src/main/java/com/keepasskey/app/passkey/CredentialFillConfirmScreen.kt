package com.keepasskey.app.passkey

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Credential Manager 与 Autofill 双通道共用的「下发前手动确认」界面。
 *
 * 仅在设备无可用认证器（[CredentialFillRequirement.MANUAL_CONFIRMATION]）时呈现：
 * 由 [PasswordFillActivity] 与 [com.keepasskey.app.autofill.AutofillConfirmActivity]
 * 在自身已施加 FLAG_SECURE + 反 overlay 加固的受保护窗口内渲染，
 * 保证「无生物识别设备」这一弱能力场景同样存在显式的用户意图确认，而非静默放行。
 *
 * ISSUE-P1-24 AC①：[attributionContent] 槽位用于在确认按钮前渲染调用方归属信息
 * （包名 + 签名证书 SHA-256 + 域），由调用方按需传入；[confirmEnabled] 供
 * 「首次出现目标须显式授权」等门控使用（默认恒可点，不影响既有调用方）。
 */
@Composable
fun CredentialFillConfirmScreen(
    title: String,
    hint: String,
    confirmText: String,
    cancelText: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    confirmEnabled: Boolean = true,
    attributionContent: (@Composable () -> Unit)? = null
) {
    // 底部抽屉式确认：压缩空洞留白，主操作贴近拇指热区，同时保留「中断原应用上下文」的清晰确认语义
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 3.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 20.dp, bottom = 16.dp)
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                attributionContent?.let { content ->
                    Spacer(modifier = Modifier.height(16.dp))
                    content()
                }
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(cancelText)
                    }
                    Button(
                        onClick = onConfirm,
                        enabled = confirmEnabled,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(confirmText)
                    }
                }
            }
        }
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@androidx.compose.ui.tooling.preview.Preview(name = "凭据填充确认页 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "凭据填充确认页 - 深色", showBackground = true, uiMode = 0x20 /* UI_MODE_NIGHT_YES */)
@Composable
internal fun CredentialFillConfirmScreenPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        CredentialFillConfirmScreen(
            title = "预览确认标题",
            hint = "预览提示文案：请确认将凭据填充到预览应用",
            confirmText = "确认填充",
            cancelText = "取消",
            onConfirm = {},
            onCancel = {},
            confirmEnabled = true,
            attributionContent = null
        )
    }
}
