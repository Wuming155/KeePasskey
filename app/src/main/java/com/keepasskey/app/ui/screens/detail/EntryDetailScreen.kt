package com.keepasskey.app.ui.screens.detail

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.R
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

    // M1 整改：离开详情页（返回导航 / 目的地销毁）时擦除 ViewModel 内按需解密的全部明文
    DisposableEffect(entryId) {
        onDispose { viewModel.onScreenDisposed() }
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 断点3 整改：SAF 导出挂起中的附件，选择目标后交给 ViewModel 真实写盘
    var pendingExportAttachment by remember { mutableStateOf<UiAttachment?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val att = pendingExportAttachment
        if (uri != null && att != null) {
            viewModel.exportAttachment(att, uri)
        }
        pendingExportAttachment = null
    }
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
        onCopyCustomField = viewModel::copyCustomField,
        onExportAttachment = { att ->
            // 断点3 整改：呼起真实 SAF 另存为，导出经仓库解析的真实附件字节
            pendingExportAttachment = att
            exportLauncher.launch(att.fileName)
        },
        onRollbackRevision = viewModel::rollbackToRevision,
        onPrepareRevisionDiff = viewModel::prepareRevisionDiff,
        onClearRevisionDiff = viewModel::clearRevisionDiff,
        onShowMessage = viewModel::showMessage,
        onCopyPassword = viewModel::copyPassword,
        onCopyUsername = viewModel::copyUsername,
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
    onCopyCustomField: (String, String) -> Unit = { _, _ -> },
    onExportAttachment: (UiAttachment) -> Unit,
    onRollbackRevision: (UiEntryRevision) -> Unit,
    onPrepareRevisionDiff: (String) -> Unit = {},
    onClearRevisionDiff: () -> Unit = {},
    onShowMessage: (UiMessage) -> Unit,
    onCopyPassword: (String) -> Unit = { _ -> },
    onCopyUsername: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val entry = uiState.entry
    val securityColors = LocalSecurityColors.current
    val haptic = LocalHapticFeedback.current
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
                    // H4-只读整改：只读会话隐藏编辑入口
                    if (!uiState.isReadOnly) {
                        IconButton(onClick = onEditClick) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.cd_edit),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
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

                // 基础凭据卡片
                Text(
                    text = stringResource(R.string.detail_basic_section),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )

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

                // TOTP 卡片
                if (entry.totpCode != null) {
                    Text(
                        text = stringResource(R.string.detail_totp_section),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )

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
                                    text = uiState.liveTotpCode ?: entry.totpCode,
                                    style = MonospaceTotpStyle.copy(color = MaterialTheme.colorScheme.primary)
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TotpMiniGauge(
                                    remainingSeconds = uiState.totpRemainingSeconds ?: entry.totpRemainingSeconds,
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
                        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
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
                                        // F2 整改：受保护字段明文不随投影下发，展开时经 ViewModel 按需解密
                                        val displayValue = if (field.isProtected) {
                                            if (isVisible) uiState.revealedProtectedFields[field.id].orEmpty()
                                            else "••••••••"
                                        } else field.value
                                        Text(
                                            text = displayValue,
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
                                        // F2 整改：受保护字段复制经按需解密 + 受保护剪贴板，非保护字段保留原行为
                                        IconButton(onClick = {
                                            if (field.isProtected) {
                                                onCopyCustomField(field.id, field.key)
                                            } else {
                                                onShowMessage(UiMessage(R.string.detail_field_copied, listOf(field.key)))
                                            }
                                        }) {
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
                        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
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
                        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
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
                                                text = stringResource(R.string.detail_revision_meta, rev.modifiedAt),
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
                        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
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
        LaunchedEffect(rev.id) { onPrepareRevisionDiff(rev.id) }
        entry?.let { current ->
            RevisionVisualDiffDialog(
                currentEntry = current,
                revision = rev,
                currentPassword = uiState.revealedPassword.orEmpty(),
                revisionPassword = uiState.revealedRevisionPasswords[rev.id].orEmpty(),
                onDismiss = {
                    onClearRevisionDiff()
                    revisionToDiff = null
                },
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
