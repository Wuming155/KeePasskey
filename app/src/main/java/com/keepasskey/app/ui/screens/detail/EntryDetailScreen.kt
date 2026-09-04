package com.keepasskey.app.ui.screens.detail

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.components.PasskeyBadge
import com.keepasskey.app.ui.components.PasswordStrengthBar
import com.keepasskey.app.ui.components.TotpMiniGauge
import com.keepasskey.app.ui.components.getVaultIcon
import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.UiEntryRevision
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.theme.CapsuleShape
import com.keepasskey.app.ui.theme.KeePasskeyTheme
import com.keepasskey.app.ui.theme.LocalSecurityColors
import com.keepasskey.app.ui.theme.MonospacePasswordStyle
import com.keepasskey.app.ui.theme.MonospaceTotpStyle

/**
 * 有状态凭据详情页面（Route）
 */
@Composable
fun EntryDetailScreen(
    entryId: String?,
    onBackClick: () -> Unit,
    onEditClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EntryDetailViewModel = hiltViewModel()
) {
    LaunchedEffect(entryId) {
        viewModel.setEntryId(entryId)
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    uiState.userMessage?.let { message ->
        val text = message.resolveText()
        LaunchedEffect(message, text) {
            snackbarHostState.showSnackbar(text)
            viewModel.clearUserMessage()
        }
    }

    EntryDetailContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBackClick = onBackClick,
        onEditClick = { uiState.entry?.let { onEditClick(it.id) } },
        onToggleFavorite = viewModel::toggleFavorite,
        onTogglePasswordVisibility = viewModel::togglePasswordVisibility,
        onToggleCustomFieldVisibility = viewModel::toggleCustomFieldVisibility,
        onExportAttachment = viewModel::exportAttachment,
        onRollbackRevision = viewModel::rollbackToRevision,
        onShowMessage = viewModel::showMessage,
        modifier = modifier
    )
}

/**
 * 无状态凭据详情渲染组件
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDetailContent(
    uiState: EntryDetailUiState,
    snackbarHostState: SnackbarHostState,
    onBackClick: () -> Unit,
    onEditClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onToggleCustomFieldVisibility: (String) -> Unit,
    onExportAttachment: (UiAttachment) -> Unit,
    onRollbackRevision: (UiEntryRevision) -> Unit,
    onShowMessage: (UiMessage) -> Unit,
    modifier: Modifier = Modifier
) {
    val entry = uiState.entry
    val securityColors = LocalSecurityColors.current
    var revisionToRollback by remember { mutableStateOf<UiEntryRevision?>(null) }
    var revisionToDiff by remember { mutableStateOf<UiEntryRevision?>(null) }
    var attachmentToPreview by remember { mutableStateOf<UiAttachment?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.detail_title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            imageVector = if (uiState.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = stringResource(R.string.cd_favorite),
                            tint = if (uiState.isFavorite) securityColors.warning else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onEditClick) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.cd_edit),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        if (entry == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(text = stringResource(R.string.detail_entry_not_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 头部 Hero 区域
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
                        Icon(
                            imageVector = getVaultIcon(entry.iconName),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(32.dp)
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
                            text = entry.url,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // 快捷操作磁贴组
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
                        onClick = { onShowMessage(UiMessage(R.string.detail_username_copied)) }
                    )
                    QuickActionTile(
                        icon = Icons.Default.Key,
                        label = stringResource(R.string.detail_btn_copy_pwd),
                        modifier = Modifier.weight(1f),
                        onClick = { onShowMessage(uiState.passwordCopyMessage) }
                    )
                }

                // 基础凭据卡片
                Text(
                    text = stringResource(R.string.detail_basic_section),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
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
                            IconButton(onClick = { onShowMessage(UiMessage(R.string.detail_username_copied_short)) }) {
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
                                        text = stringResource(R.string.detail_password_label, entry.passwordPlain.length),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (uiState.isPasswordVisible) entry.passwordPlain else entry.passwordMasked,
                                        style = MonospacePasswordStyle.copy(
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 17.sp
                                        )
                                    )
                                }
                                Row {
                                    IconButton(onClick = onTogglePasswordVisibility) {
                                        Icon(
                                            imageVector = if (uiState.isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = stringResource(R.string.cd_toggle_password_visibility),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    IconButton(onClick = { onShowMessage(uiState.passwordCopyMessage) }) {
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
                            PasswordStrengthBar(
                                entropyBits = entry.strengthBits,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // TOTP 卡片
                if (entry.totpCode != null) {
                    Text(
                        text = stringResource(R.string.detail_totp_section),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    BentoCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
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
                                    text = entry.totpCode,
                                    style = MonospaceTotpStyle.copy(color = MaterialTheme.colorScheme.primary)
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TotpMiniGauge(
                                    remainingSeconds = entry.totpRemainingSeconds,
                                    modifier = Modifier.size(34.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
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

                // Passkey 卡片
                if (entry.isPasskey) {
                    Text(
                        text = stringResource(R.string.detail_passkey_section),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )

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

                // 自定义字段卡片区
                if (entry.customFields.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.detail_custom_fields_section),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    BentoCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            entry.customFields.forEachIndexed { index, field ->
                                val isVisible = uiState.protectedFieldsVisibility[field.id] == true
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = field.key,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = if (field.isProtected && !isVisible) "••••••••" else field.value,
                                            style = if (field.isProtected && !isVisible) MonospacePasswordStyle else MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    Row {
                                        if (field.isProtected) {
                                            IconButton(onClick = { onToggleCustomFieldVisibility(field.id) }) {
                                                Icon(
                                                    imageVector = if (isVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        IconButton(onClick = { onShowMessage(UiMessage(R.string.detail_field_copied, listOf(field.key))) }) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }

                                if (index < entry.customFields.size - 1) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                    )
                                }
                            }
                        }
                    }
                }

                // 附件文件列表卡片区
                if (entry.attachments.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.detail_attachments_section),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    BentoCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            entry.attachments.forEach { att ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { attachmentToPreview = att }
                                            .padding(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = att.fileName,
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = stringResource(R.string.detail_attachment_tap_preview, att.fileSizeFormatted),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }

                                    IconButton(onClick = { onExportAttachment(att) }) {
                                        Icon(
                                            imageVector = Icons.Default.Download,
                                            contentDescription = stringResource(R.string.detail_attachment_export),
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 版本历史记录卡片区
                if (entry.revisions.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.detail_history_section),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    BentoCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            entry.revisions.forEach { rev ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.History,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = rev.summary,
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = stringResource(R.string.detail_revision_meta, rev.modifiedAt, rev.passwordPlain.take(3)),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(onClick = { revisionToDiff = rev }) {
                                            Text(stringResource(R.string.detail_diff_compare), fontSize = 12.sp)
                                        }
                                        TextButton(onClick = { revisionToRollback = rev }) {
                                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Text(stringResource(R.string.detail_history_rollback), fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 备注
                if (entry.notes.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.detail_notes_section),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    BentoCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
                    ) {
                        Column {
                            Text(
                                text = entry.notes,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.detail_updated_meta, entry.updatedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }

    // 版本回滚确认对话框
    revisionToRollback?.let { rev ->
        AlertDialog(
            onDismissRequest = { revisionToRollback = null },
            title = { Text(stringResource(R.string.detail_history_rollback)) },
            text = { Text(stringResource(R.string.detail_history_rollback_confirm)) },
            confirmButton = {
                Button(
                    onClick = {
                        onRollbackRevision(rev)
                        revisionToRollback = null
                    },
                    shape = CapsuleShape
                ) {
                    Text(stringResource(R.string.btn_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { revisionToRollback = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
            shape = RoundedCornerShape(18.dp)
        )
    }

    // 版本历史差异对比对话框 (Visual Diff)
    revisionToDiff?.let { rev ->
        entry?.let { current ->
            RevisionVisualDiffDialog(
                currentEntry = current,
                revision = rev,
                onDismiss = { revisionToDiff = null },
                onRollback = {
                    onRollbackRevision(rev)
                    revisionToDiff = null
                }
            )
        }
    }

    // 安全附件预览对话框 (Safe Attachment Previewer)
    attachmentToPreview?.let { att ->
        SafeAttachmentPreviewDialog(
            attachment = att,
            onDismiss = { attachmentToPreview = null },
            onExport = {
                onExportAttachment(att)
                attachmentToPreview = null
            }
        )
    }
}

/**
 * 快捷操作磁贴小组件
 */
@Composable
private fun QuickActionTile(
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
