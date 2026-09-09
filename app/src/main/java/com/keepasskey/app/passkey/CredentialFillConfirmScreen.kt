package com.keepasskey.app.passkey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Credential Manager 与 Autofill 双通道共用的「下发前手动确认」界面。
 *
 * 仅在设备无可用认证器（[CredentialFillRequirement.MANUAL_CONFIRMATION]）时呈现：
 * 由 [PasswordFillActivity] 与 [com.keepasskey.app.autofill.AutofillConfirmActivity]
 * 在自身已施加 FLAG_SECURE + 反 overlay 加固的受保护窗口内渲染，
 * 保证「无生物识别设备」这一弱能力场景同样存在显式的用户意图确认，而非静默放行。
 */
@Composable
fun CredentialFillConfirmScreen(
    title: String,
    hint: String,
    confirmText: String,
    cancelText: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(28.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
            ) {
                OutlinedButton(onClick = onCancel) {
                    Text(cancelText)
                }
                Button(onClick = onConfirm) {
                    Text(confirmText)
                }
            }
        }
    }
}
