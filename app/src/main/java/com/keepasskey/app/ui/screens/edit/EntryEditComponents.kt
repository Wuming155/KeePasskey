package com.keepasskey.app.ui.screens.edit

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.components.SecurePasswordField
import com.keepasskey.app.ui.theme.CapsuleShape
import com.keepasskey.app.ui.theme.LocalSecurityColors

/** SAF 附件显示名查询 */
internal fun queryDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (e: Exception) {
        null
    }
}

/** 附件尺寸格式化 */
internal fun formatAttachmentSize(bytes: Int): String {
    return if (bytes < 1024) "$bytes B" else "${bytes / 1024} KB"
}

/**
 * TOTP 配置区：区块标题 + 安全种子输入卡片（TASK-21 拆分：自 [EntryEditContent]
 * 整体搬移的自包含区块，在原 Column 位置发射标题与卡片两个兄弟节点，组合结构不变）
 */
@Composable
internal fun EntryEditTotpSection(
    entryId: String?,
    loadedTotpSecret: CharArray?,
    onTotpSecretChangeSecure: (CharArray) -> Unit,
    onScanTotpQr: () -> Unit
) {
    Text(
        text = stringResource(R.string.edit_totp_section),
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface
    )

    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        // TASK-10 整改（加解密审查 B9）：TOTP 种子输入走 SecurePasswordField——
        // 显示用 String 仅存活于组件内部，CharArray 直达 ViewModel；
        // 既有种子经 loadedTotpSecret 一次性预填。种子默认明文显示（Base32 配置
        // 常需人工核对），保留可见性切换。
        var totpVisible by remember { mutableStateOf(true) }
        SecurePasswordField(
            label = stringResource(R.string.edit_totp_hint),
            onPasswordChanged = onTotpSecretChangeSecure,
            isPasswordVisible = totpVisible,
            onToggleVisibility = { totpVisible = !totpVisible },
            initialPassword = loadedTotpSecret,
            initialKey = entryId?.let { "totp-$it" } ?: "totp-new-entry",
            trailingIcon = {
                // 断点5 整改：按钮直接呼起真实扫码
                IconButton(onClick = onScanTotpQr) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = stringResource(R.string.cd_scan_qr),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 通行密钥 Passkey 注册绑定区：区块标题 + 绑定状态卡片（TASK-21 拆分：自
 * [EntryEditContent] 整体搬移；LocalSecurityColors 改在本区块内读取，组合输出不变）
 */
@Composable
internal fun EntryEditPasskeySection(
    isPasskey: Boolean,
    onTogglePasskey: () -> Unit
) {
    val securityColors = LocalSecurityColors.current
    Text(
        text = stringResource(R.string.edit_passkey_section),
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface
    )

    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = if (isPasskey) securityColors.passkeyContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = if (isPasskey) securityColors.passkey.copy(alpha = 0.5f) else null
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isPasskey) stringResource(R.string.edit_passkey_has_bound) else stringResource(R.string.edit_passkey_create),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.edit_passkey_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = onTogglePasskey,
                shape = CapsuleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPasskey) securityColors.passkey else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (isPasskey) stringResource(R.string.edit_passkey_unbind) else stringResource(R.string.edit_passkey_bind))
            }
        }
    }
}

/**
 * 高级属性区：标签 / AutoType 默认序列 / Override URL（区块标题 + 卡片；
 * TASK-21 拆分：自 [EntryEditContent] 整体搬移，在原 Column 位置发射
 * 标题与卡片两个兄弟节点，组合结构不变）
 */
@Composable
internal fun EntryEditExtraSection(
    tagsInput: String,
    onTagsInputChange: (String) -> Unit,
    autoTypeSequence: String,
    onAutoTypeSequenceChange: (String) -> Unit,
    overrideUrl: String,
    onOverrideUrlChange: (String) -> Unit
) {
    Text(
        text = stringResource(R.string.edit_extra_section),
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface
    )

    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = tagsInput,
                onValueChange = onTagsInputChange,
                label = { Text(stringResource(R.string.edit_tags_hint)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = autoTypeSequence,
                onValueChange = onAutoTypeSequenceChange,
                label = { Text(stringResource(R.string.edit_autotype_hint)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = overrideUrl,
                onValueChange = onOverrideUrlChange,
                label = { Text(stringResource(R.string.edit_override_url_hint)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 密码生成器微件（抽离组件提高模块化）
 *
 * TASK-21 拆分：自 [EntryEditContent] 同文件搬移至此，实现不变。
 */
@Composable
internal fun PasswordGeneratorWidget(
    passLength: Float,
    useUpper: Boolean,
    useLower: Boolean,
    useDigits: Boolean,
    useSymbols: Boolean,
    onPassLengthChange: (Float) -> Unit,
    onRegenerate: () -> Unit,
    onToggleUpper: () -> Unit,
    onToggleLower: () -> Unit,
    onToggleDigits: () -> Unit,
    onToggleSymbols: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.edit_generator_title, passLength.toInt()),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            IconButton(onClick = onRegenerate) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.cd_regenerate),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Slider(
            value = passLength,
            onValueChange = onPassLengthChange,
            valueRange = 8f..48f,
            steps = 39,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = useUpper,
                onClick = onToggleUpper,
                label = { Text("A-Z") }
            )
            FilterChip(
                selected = useLower,
                onClick = onToggleLower,
                label = { Text("a-z") }
            )
            FilterChip(
                selected = useDigits,
                onClick = onToggleDigits,
                label = { Text("0-9") }
            )
            FilterChip(
                selected = useSymbols,
                onClick = onToggleSymbols,
                label = { Text("#$%") }
            )
        }
    }
}
