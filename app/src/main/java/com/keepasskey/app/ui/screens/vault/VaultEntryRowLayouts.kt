package com.keepasskey.app.ui.screens.vault

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.EntryIconContent
import com.keepasskey.app.ui.components.PasskeyBadge
import com.keepasskey.app.ui.components.TotpMiniGauge
import com.keepasskey.app.ui.components.getVaultIcon
import com.keepasskey.app.ui.model.BitmapEntryIcon
import com.keepasskey.app.ui.model.UiVaultEntry

/**
 * 条目行内部布局组件（ISSUE-P3-29：自 `VaultEntryRows.kt` 拆出，同包同可见性，
 * 原 `private` 提升为 `internal` 以支持跨文件调用；纯结构性拆分，无行为变更）。
 */

/**
 * ISSUE-P3-17：搜索结果行的「所属分组」行。
 * 路径已由状态层拼好（[GroupPathPresenter]），本组件只做绘制。
 */
@Composable
internal fun GroupPathLine(
    groupPath: String,
    fontSizeSp: Int,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(GROUP_PATH_ICON_SIZE_DP.dp)
        )
        Spacer(modifier = Modifier.width(GROUP_PATH_ICON_GAP_DP.dp))
        Text(
            text = groupPath,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = fontSizeSp.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 分组路径行前导图标边长（dp） */
private const val GROUP_PATH_ICON_SIZE_DP = 12

/** 分组路径行前导图标与文案间距（dp） */
private const val GROUP_PATH_ICON_GAP_DP = 4

@Composable
internal fun StandardEntryLayout(
    entry: UiVaultEntry,
    icon: BitmapEntryIcon,
    urlText: String,
    isRecycled: Boolean,
    isBatchMode: Boolean,
    isSelected: Boolean,
    showUsername: Boolean,
    showOtp: Boolean,
    showPasskeyBadge: Boolean,
    showUrl: Boolean,
    densitySpec: ListDensitySpec,
    groupPath: String?,
    onCopyPassword: () -> Unit,
    onCopyUsername: () -> Unit,
    onRestore: () -> Unit,
    onPurge: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = densitySpec.rowHorizontalPaddingDp.dp,
            vertical = densitySpec.rowVerticalPaddingDp.dp
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isBatchMode) {
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp).padding(end = 4.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }

        // 通行密钥条目在无自定义图标时以钥匙图标强调（既有视觉语义）
        val (placeholderIcon, iconTint, containerColor) = when {
            entry.isPasskey -> Triple(Icons.Default.Key, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
            else -> Triple(getVaultIcon(entry.iconName), MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.tertiaryContainer)
        }

        Box(
            modifier = Modifier
                .size(densitySpec.iconContainerSizeDp.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(containerColor),
            contentAlignment = Alignment.Center
        ) {
            EntryIconContent(
                icon = icon,
                tint = iconTint,
                placeholderIcon = placeholderIcon,
                contentSize = densitySpec.iconContentSizeDp.dp
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = densitySpec.titleFontSizeSp.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (entry.isPasskey && showPasskeyBadge) {
                    Spacer(modifier = Modifier.width(6.dp))
                    PasskeyBadge()
                }
            }

            // ISSUE-P3-17：搜索结果行的所属分组完整路径（非搜索态不下发，恒不展示）
            if (groupPath != null) {
                Spacer(modifier = Modifier.height(2.dp))
                GroupPathLine(groupPath = groupPath, fontSizeSp = densitySpec.secondaryFontSizeSp)
            }

            if (showUsername || (showUrl && urlText.isNotBlank())) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (showUsername) {
                        Text(
                            text = if (entry.username.isNotBlank()) entry.username else stringResource(R.string.cardrow_no_username),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = densitySpec.secondaryFontSizeSp.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    if (showUsername && showUrl && urlText.isNotBlank()) {
                        Text("•", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    if (showUrl && urlText.isNotBlank()) {
                        Text(
                            text = urlText.removePrefix("https://").removePrefix("http://").trimEnd('/'),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = densitySpec.secondaryFontSizeSp.sp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }

            if (showOtp && entry.totpCode != null) {
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.totpCode,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.sp
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    TotpMiniGauge(remainingSeconds = entry.totpRemainingSeconds)
                }
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        if (!isBatchMode) {
            if (isRecycled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRestore) {
                        Icon(Icons.Default.Restore, contentDescription = stringResource(R.string.btn_restore), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onPurge) {
                        Icon(Icons.Default.DeleteForever, contentDescription = stringResource(R.string.btn_delete), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entry.username.isNotBlank()) {
                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            onCopyUsername()
                        }) {
                            Icon(Icons.Default.PersonOutline, contentDescription = stringResource(R.string.cd_copy_username), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        }
                    }
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onCopyPassword()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.cd_copy_password), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
