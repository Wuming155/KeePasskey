package com.keepasskey.app.ui.screens.detail

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.components.EntryIconContent
import com.keepasskey.app.ui.components.PasskeyBadge
import com.keepasskey.app.ui.components.PasswordStrengthBar
import com.keepasskey.app.ui.components.TotpMiniGauge
import com.keepasskey.app.ui.components.getVaultIcon
import com.keepasskey.app.ui.model.BitmapEntryIcon
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.theme.LocalSecurityColors
import com.keepasskey.app.ui.theme.MonospacePasswordStyle
import com.keepasskey.app.ui.theme.MonospaceTotpStyle

/**
 * 详情页区块标题（统一 titleMedium + SemiBold 样式）
 */
@Composable
internal fun SectionTitle(@StringRes textRes: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
    )
}

/**
 * 头部 Hero 区域
 *
 * ISSUE-P3-02：[icon] 为状态层投影后的图标（自定义位图 / 缺图占位 / 标准图标），
 * [urlText] 为 URL 字段引用展开后的展示文案；本组件只做纯绘制。
 *
 * ISSUE-P3-17：[groupPath] 非空时在 URL 下方展示条目所属分组完整路径
 * （仅 `showGroupInEntry` 开启时由状态层下发，UI 不做路径计算）。
 */
@Composable
internal fun EntryHeaderSection(
    entry: UiVaultEntry,
    icon: BitmapEntryIcon,
    urlText: String,
    groupPath: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            EntryIconContent(
                icon = icon,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                placeholderIcon = getVaultIcon(entry.iconName),
                contentSize = 32.dp
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (entry.isPasskey) {
                    Spacer(modifier = Modifier.width(8.dp))
                    PasskeyBadge()
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = urlText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            // ISSUE-P3-17：showGroupInEntry 开启时的所属分组路径
            if (groupPath != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = groupPath,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 快捷操作磁贴组
 */
@Composable
internal fun QuickActionRow(
    entry: UiVaultEntry,
    onShowMessage: (UiMessage) -> Unit,
    onCopyUsername: (String, String) -> Unit,
    onCopyPassword: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        QuickActionTile(
            icon = Icons.AutoMirrored.Filled.OpenInNew,
            label = stringResource(R.string.detail_btn_open_url),
            modifier = Modifier.weight(1f),
            onClick = { onShowMessage(UiMessage(R.string.detail_opening_browser)) }
        )
        QuickActionTile(
            icon = Icons.Default.ContentCopy,
            label = stringResource(R.string.detail_btn_copy_user),
            modifier = Modifier.weight(1f),
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onCopyUsername(entry.title, entry.username)
            }
        )
        QuickActionTile(
            icon = Icons.Default.Key,
            label = stringResource(R.string.detail_btn_copy_pwd),
            modifier = Modifier.weight(1f),
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onCopyPassword(entry.title)
            }
        )
    }
}

/**
 * 基础凭据卡片（用户名 / 密码行与强度条）
 */
@Composable
internal fun BasicCredentialsCard(
    uiState: EntryDetailUiState,
    entry: UiVaultEntry,
    onTogglePasswordVisibility: () -> Unit,
    onCopyPassword: (String) -> Unit,
    onCopyUsername: (String, String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )

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
                        // 明文/掩码切换：交叉淡入 + 容器尺寸平滑过渡（消除突兀跳变）
                        AnimatedContent(
                            targetState = uiState.isPasswordVisible,
                            transitionSpec = {
                                (fadeIn(tween(150)) togetherWith fadeOut(tween(90)))
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
    }
}

/**
 * TOTP 卡片（动态验证码 + 剩余秒数仪表）
 *
 * ISSUE-P3-17：[EntryDetailUiState.isTotpVisible] 为 false 时验证码以掩码呈现，
 * 用户可经眼睛按钮显式展开——`maskTotpDefault` 只决定**初始**遮掩态，不锁定字段。
 * 遮掩不影响倒计时与换码：验证码始终按周期重算，展开即为当前有效码。
 */
@Composable
internal fun TotpCard(
    uiState: EntryDetailUiState,
    entry: UiVaultEntry,
    onToggleVisibility: () -> Unit,
    onShowMessage: (UiMessage) -> Unit
) {
    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = stringResource(R.string.detail_totp_code),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (uiState.isTotpVisible) {
                        uiState.liveTotpCode ?: entry.totpCode.orEmpty()
                    } else {
                        TOTP_MASK
                    },
                    style = MonospaceTotpStyle.copy(color = MaterialTheme.colorScheme.primary)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TotpMiniGauge(
                    remainingSeconds = uiState.totpRemainingSeconds ?: entry.totpRemainingSeconds,
                    modifier = Modifier.size(34.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                IconButton(onClick = onToggleVisibility) {
                    Icon(
                        imageVector = if (uiState.isTotpVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(R.string.cd_toggle_password_visibility),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = { onShowMessage(UiMessage(R.string.detail_totp_copied)) }) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.cd_copy_totp),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * TOTP 遮掩占位：纯符号，不含任何真实验证码信息（展开即为当前有效码）。
 */
private const val TOTP_MASK = "••••••"

/**
 * Passkey 凭据卡片
 */
@Composable
internal fun PasskeyCard(entry: UiVaultEntry) {
    val securityColors = LocalSecurityColors.current
    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = securityColors.passkeyContainer.copy(alpha = 0.35f),
        borderColor = securityColors.passkey.copy(alpha = 0.5f)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = stringResource(R.string.cd_passkey),
                    tint = securityColors.passkey,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.detail_passkey_chip),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = securityColors.passkey
                    )
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.detail_passkey_rp, entry.passkeyRpId ?: entry.url),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.detail_passkey_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 快捷操作磁贴小组件
 */
@Composable
internal fun QuickActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.medium
            ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
