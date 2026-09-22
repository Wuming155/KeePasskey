package com.keepasskey.app.ui.screens.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.PasswordStrengthBar
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.theme.MonospacePasswordStyle

/**
 * `BasicCredentialsCard` 的段落组件（`ISSUE-P3-188` §206：宿主文件 397 行距 400 余量仅 3 行，
 * 按 §192 / §196 的「余量逐案判」口径**另起新文件**；逐字搬动、零行为变更）。
 *
 * - [BasicCredentialsUsernameRow]：用户名行（标签 + 用户名 + 复制按钮）；
 * - [BasicCredentialsPasswordArea]：密码区（标签 + 明文/掩码交叉淡入 + 揭示/复制按钮 + 强度条）。
 * 两段均**不自持状态**：显隐态与回调全部由 [BasicCredentialsCard] 持有并经参数回传。
 */

@Composable
internal fun BasicCredentialsUsernameRow(
    entry: UiVaultEntry,
    onCopyUsername: (String, String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.detail_username_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = entry.username,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        IconButton(onClick = { onCopyUsername(entry.title, entry.username) }) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.cd_copy_username),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
internal fun BasicCredentialsPasswordArea(
    uiState: EntryDetailUiState,
    entry: UiVaultEntry,
    onTogglePasswordVisibility: () -> Unit,
    onCopyPassword: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    // ISSUE-P3-261 AC⑤：密码明文/掩码交叉淡化取主题 MotionScheme 的 fast 效果 spec
    val passwordRevealFade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.detail_password_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                // 明文/掩码切换：交叉淡入 + 容器尺寸平滑过渡（消除突兀跳变）。
                // ISSUE-P3-261 AC⑤：原自定 150 / 90 系 Compose 默认 tween 的自造第二套语言，
                // 现取主题 MotionScheme 的 fast 效果 spec，与页面转场 / 底栏指示器同族。
                AnimatedContent(
                    targetState = uiState.isPasswordVisible,
                    transitionSpec = {
                        (fadeIn(passwordRevealFade) togetherWith fadeOut(passwordRevealFade))
                            .using(SizeTransform(clip = false))
                    },
                    label = "passwordReveal"
                ) { isVisible ->
                    Text(
                        text = if (isVisible) uiState.revealedPassword.orEmpty() else entry.passwordMasked,
                        style = MonospacePasswordStyle.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 17.sp
                        ),
                        maxLines = 1
                    )
                }
            }
            Row {
                IconButton(onClick = {
                    // 揭示明文用轻触感（Tick）：克制、不与复制成功反馈混淆
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onTogglePasswordVisibility()
                }) {
                    Icon(
                        imageVector = if (uiState.isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(R.string.cd_toggle_password_visibility),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onCopyPassword(entry.title)
                }) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.cd_copy_password),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        // TASK-32 整改：投影层恒不解密密码（entry.strengthBits 恒 null），
        // 真实熵由 ViewModel 在用户显式查看密码时估算后经 uiState 下发
        PasswordStrengthBar(
            entropyBits = uiState.passwordStrengthBits,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
