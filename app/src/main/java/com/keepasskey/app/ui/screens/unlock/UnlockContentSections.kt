package com.keepasskey.app.ui.screens.unlock

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.theme.CapsuleShape
import com.keepasskey.app.ui.theme.HeroTitleStyle
import com.keepasskey.app.ui.components.SecurePasswordField

/**
 * 密码库锁 Logo 与呼吸光晕底座
 */
@Composable
internal fun UnlockVaultLogo(uiState: UnlockUiState) {
    Box(
        modifier = Modifier
            .size(92.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                        Color.Transparent
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (!uiState.hasDatabase) Icons.Default.Lock else if (uiState.unlockMode == UnlockMode.QUICK_UNLOCK) Icons.Default.FlashOn else Icons.Default.Lock,
                contentDescription = stringResource(R.string.cd_vault_locked),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

/**
 * 空状态：当前未配置或选择任何密码库
 */
@Composable
internal fun UnlockEmptyVaultContent(
    uiState: UnlockUiState,
    onNavigateToDatabasePicker: () -> Unit,
    onOpenExistingVault: () -> Unit
) {
    Text(
        text = stringResource(R.string.unlock_empty_vault_title),
        style = HeroTitleStyle,
        color = MaterialTheme.colorScheme.onBackground
    )

    Spacer(modifier = Modifier.height(6.dp))

    Text(
        text = stringResource(R.string.unlock_empty_vault_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )

    Spacer(modifier = Modifier.height(24.dp))

    uiState.errorMessage?.let { message ->
        Text(
            text = message.resolveText(),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 12.dp)
        )
    }

    Button(
        onClick = onNavigateToDatabasePicker,
        shape = CapsuleShape,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.unlock_empty_create_btn),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    androidx.compose.material3.OutlinedButton(
        onClick = onOpenExistingVault,
        shape = CapsuleShape,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
    ) {
        Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.unlock_empty_open_btn),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        )
    }
}

/**
 * QuickUnlock 卡片区域 (KP2A / KeePassDX 风格)
 */
@Composable
internal fun UnlockQuickUnlockCard(
    uiState: UnlockUiState,
    onBiometricUnlock: () -> Unit,
    onSwitchMode: (UnlockMode) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // H1 整改：仅在拿到真实数据时展示，不再渲染写死的假硬件声明/假剩余时长
            if (uiState.hardwareBackedSecurity.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = uiState.hardwareBackedSecurity,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // ISSUE-P1-22：本机快速解锁封印为软件密钥时的常驻声明（AC②：
            // UI 常驻声明「不提供硬件级保护」，与降级确认记录绑定，非一次性提示）
            if (uiState.quickUnlockDowngraded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.quick_unlock_software_key_notice),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ISSUE-P1-08 统一快速解锁：仅 Class 3 强生物识别经硬件密钥解封（锁屏凭据不再可解封）——
            // 认证入口由系统 BiometricPrompt 承载，不再提供自研 PIN 输入
            Button(
                onClick = onBiometricUnlock,
                enabled = !uiState.isLoading,
                shape = CapsuleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.unlock_biometric_primary_btn),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            TextButton(
                onClick = { onSwitchMode(UnlockMode.STANDARD) },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(stringResource(R.string.unlock_switch_to_full), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * 完整主密码解锁区（含密钥文件、只读开关与解锁主操作）
 */
@Composable
internal fun UnlockStandardUnlockContent(
    uiState: UnlockUiState,
    onPasswordChange: (CharArray) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onSelectKeyFile: () -> Unit,
    onClearKeyFile: () -> Unit,
    onToggleReadOnly: () -> Unit,
    onUnlock: () -> Unit,
    onSwitchMode: (UnlockMode) -> Unit
) {
    // 完整主密码输入框（SecurePasswordField：显示 String 仅存活于组件内部，CharArray 直达 ViewModel）
    SecurePasswordField(
        label = stringResource(R.string.unlock_master_password),
        placeholder = stringResource(R.string.unlock_master_password_hint),
        onPasswordChanged = onPasswordChange,
        isError = uiState.errorMessage != null,
        supportingText = {
            uiState.errorMessage?.let { message ->
                Text(
                    text = message.resolveText(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            uiState.infoMessage?.let { message ->
                Text(
                    text = message.resolveText(),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        isPasswordVisible = uiState.isPasswordVisible,
        onToggleVisibility = onTogglePasswordVisibility,
        onDone = onUnlock,
        // ISSUE-P1-04：失败/锁定后令牌递增，驱动输入框擦除显示态，与 VM 主密码清零同步
        wipeToken = uiState.clearPasswordFieldToken,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(12.dp))

    // 密钥文件行（§186 拆至同包 UnlockStandardUnlockSections.kt；判定与绘制逐字保留）
    UnlockKeyFileRow(
        hasKeyFile = uiState.hasKeyFile,
        keyFileName = uiState.keyFileName,
        onSelectKeyFile = onSelectKeyFile,
        onClearKeyFile = onClearKeyFile
    )

    Spacer(modifier = Modifier.height(12.dp))

    // H4-只读整改：只读打开开关（§186 拆出）
    UnlockReadOnlyRow(
        openReadOnly = uiState.openReadOnly,
        onToggleReadOnly = onToggleReadOnly
    )

    Spacer(modifier = Modifier.height(16.dp))

    // 解锁主操作按钮：无密码且无密钥文件时禁用（密钥文件单独解锁时允许空密码）
    UnlockSubmitButton(
        enabled = !uiState.isLoading && (uiState.hasPassword || uiState.hasKeyFile),
        isLoading = uiState.isLoading,
        onUnlock = onUnlock
    )

    if (uiState.isQuickUnlockAvailable) {
        UnlockSwitchToQuickEntry(onSwitchToQuickUnlock = { onSwitchMode(UnlockMode.QUICK_UNLOCK) })
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@androidx.compose.ui.tooling.preview.Preview(name = "快速解锁卡片 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "快速解锁卡片 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun UnlockQuickUnlockCardPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        UnlockQuickUnlockCard(
            uiState = UnlockUiState().copy(
                hasDatabase = true,
                unlockMode = UnlockMode.QUICK_UNLOCK,
                hardwareBackedSecurity = stringResource(R.string.unlock_hardware_backed_security)
            ),
            onBiometricUnlock = {},
            onSwitchMode = {}
        )
    }
}
