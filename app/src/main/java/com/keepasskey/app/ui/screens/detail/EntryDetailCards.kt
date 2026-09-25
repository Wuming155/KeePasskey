package com.keepasskey.app.ui.screens.detail

import android.content.res.Configuration
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.components.PasswordStrengthBar
import com.keepasskey.app.ui.components.TotpMiniGauge
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.theme.LocalSecurityColors
import com.keepasskey.app.ui.theme.MonospacePasswordStyle
import com.keepasskey.app.ui.theme.MonospaceTotpStyle

/**
 * 基础凭据卡片（用户名 / 密码行与强度条）
 *
 * §206：用户名行与密码区下沉至 [BasicCredentialsUsernameRow] / [BasicCredentialsPasswordArea]
 * （EntryDetailCardSections.kt，逐字搬动、零行为变更），本文件保留卡片编排。
 */
@Composable
internal fun BasicCredentialsCard(
    uiState: EntryDetailUiState,
    entry: UiVaultEntry,
    onTogglePasswordVisibility: () -> Unit,
    onCopyPassword: (String) -> Unit,
    onCopyUsername: (String, String) -> Unit
) {
    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BasicCredentialsUsernameRow(
                entry = entry,
                onCopyUsername = onCopyUsername
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )

            BasicCredentialsPasswordArea(
                uiState = uiState,
                entry = entry,
                onTogglePasswordVisibility = onTogglePasswordVisibility,
                onCopyPassword = onCopyPassword
            )
        }
    }
}

/**
 * TOTP 卡片（动态验证码 + 剩余秒数仪表）
 *
 * ISSUE-P3-17：[EntryDetailUiState.isTotpVisible] 为 false 时验证码以掩码呈现，
 * 用户可经眼睛按钮显式展开——`maskTotpDefault` 只决定**初始**遮掩态，不锁定字段。
 * 遮掩不影响倒计时与换码：验证码始终按周期重算，展开即为当前有效码。
 *
 * ISSUE-P3-184：TOTP 分支的复制按钮走 [onCopyTotp] 真实写入剪贴板（此前只弹提示、不复制）。
 * 遮掩态下复制的是**真实当前码**（遮掩只是显示态，与密码复制语义一致）；
 * HOTP 分支有意**不提供**复制入口（见下方 `entry.isHotp` 注释）。
 */
@Composable
internal fun TotpCard(
    uiState: EntryDetailUiState,
    entry: UiVaultEntry,
    onToggleVisibility: () -> Unit,
    // ISSUE-P3-49：HOTP 取码（推进计数器并复制本次所出之码）
    onAdvanceHotp: () -> Unit = {},
    // ISSUE-P3-184：TOTP 取码（复制当前有效码；不推进任何状态，也不经本组件弹提示）
    onCopyTotp: () -> Unit = {}
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
                    // 与验证码大卡 / 列表同一色语义（success），避免跨页蓝/绿混用
                    style = MonospaceTotpStyle.copy(color = LocalSecurityColors.current.success)
                )
                // ISSUE-P2-289 AC③：解析期回落诊断如实呈现（禁静默改写）
                if (entry.totpWarnings.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.detail_totp_fallback_warning,
                            entry.totpWarnings.joinToString("；")
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.isHotp) {
                    // ISSUE-P3-49：HOTP 由持久化计数器决定，无时间倒计时——以「取下一个码」
                    // 显式推进计数器（对齐 KeePassXC）；不提供「复制当前码」入口，
                    // 因为复制而不推进会让同一计数器被重复使用
                    IconButton(onClick = onAdvanceHotp) {
                        Icon(
                            imageVector = Icons.Default.Autorenew,
                            contentDescription = stringResource(R.string.cd_hotp_advance),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    TotpMiniGauge(
                        remainingSeconds = uiState.totpRemainingSeconds ?: entry.totpRemainingSeconds,
                        // ISSUE-P3-158：环分母取条目自身周期（此前沿用缺省的 30 秒）
                        totalSeconds = entry.totpPeriod,
                        modifier = Modifier.size(34.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    // ISSUE-P3-184：此前只弹「已复制」提示而不写剪贴板（谎报成功）——
                    // 现改为调用真实复制通道，成功/失败的文案一律由 ViewModel 经 userMessage 上浮
                    IconButton(onClick = onCopyTotp) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.cd_copy_totp),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                IconButton(onClick = onToggleVisibility) {
                    Icon(
                        imageVector = if (uiState.isTotpVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(R.string.cd_toggle_password_visibility),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
 * IDE 预览专用状态装载：仅在组合首帧把示例状态写入 remember 状态，绕开
 * `remember(…) { mutableStateOf(示例) }` 的「非 Composable 上下文求值」静态检查。
 */
@Composable
private fun <T> previewStateOf(value: T): androidx.compose.runtime.MutableState<T> {
    val state = remember { mutableStateOf(value) }
    LaunchedEffect(Unit) { state.value = value }
    return state
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@Preview(name = "基础凭据卡片 - 浅色", showBackground = true)
@Preview(name = "基础凭据卡片 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun BasicCredentialsCardPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        val previewEntry = com.keepasskey.app.ui.preview.PreviewEntryLogin
        val previewUiState = previewStateOf(
            com.keepasskey.app.ui.screens.detail.EntryDetailUiState(
                entry = previewEntry,
                isPasswordVisible = true,
                revealedPassword = "预览用假密码",
                passwordStrengthBits = 72,
                revealedProtectedFields = mapOf("preview-field-2" to "预览受保护字段值"),
                protectedFieldsVisibility = mapOf("preview-field-2" to true),
                totpRemainingSeconds = 18,
                liveTotpCode = "654321",
                isFavorite = true
            )
        ).value
        val previewSnackbar = remember { SnackbarHostState() }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            BasicCredentialsCard(
                uiState = previewUiState,
                entry = previewEntry,
                onTogglePasswordVisibility = {},
                onCopyPassword = { _ -> },
                onCopyUsername = { _, _ -> }
            )
            TotpCard(
                uiState = previewUiState,
                entry = previewEntry,
                onToggleVisibility = {},
                onAdvanceHotp = {},
                onCopyTotp = {}
            )
            PasskeyCard(entry = com.keepasskey.app.ui.preview.PreviewEntryPasskey)
            CustomFieldsCard(
                uiState = previewUiState,
                entry = previewEntry,
                onToggleCustomFieldVisibility = { _ -> },
                onCopyCustomField = { _, _ -> },
                onShowMessage = { _ -> }
            )
            AttachmentsCard(
                entry = com.keepasskey.app.ui.preview.PreviewEntryLogin.copy(
                    attachments = com.keepasskey.app.ui.preview.PreviewAttachments
                ),
                onPreviewAttachment = { _ -> },
                onExportAttachment = { _ -> }
            )
            RevisionsCard(
                entry = com.keepasskey.app.ui.preview.PreviewEntryLogin.copy(
                    revisions = com.keepasskey.app.ui.preview.PreviewRevisions
                ),
                onCompareRevision = { _ -> },
                onRequestRollback = { _ -> }
            )
            NotesCard(notesText = "预览用备注文本，仅用于界面排版展示。", updatedAt = "2026-01-02 12:00")
        }
    }
}
