package com.keepasskey.app.ui.screens.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiEntryRevision
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.theme.CapsuleShape
import com.keepasskey.app.ui.theme.LocalSecurityColors

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
        onDuplicateEntry = viewModel::duplicateEntry,
        onToggleAutofillBlock = viewModel::toggleAutofillBlockForApp,
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
    onDuplicateEntry: () -> Unit = {},
    // TASK-44：「为本应用禁用自动填充」开关（仅绑定了 Android 应用的条目呈现）
    onToggleAutofillBlock: () -> Unit = {},
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
                    // TASK-16：条目克隆（只读会话隐藏）
                    if (!uiState.isReadOnly) {
                        IconButton(onClick = onDuplicateEntry) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.cd_duplicate),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // TASK-44：为本应用禁用自动填充（仅条目绑定了 Android 应用时呈现；
                    // 未绑定应用的条目无明确屏蔽对象，入口不出现，避免成为无意义开关）
                    if (uiState.autofillBoundPackage != null) {
                        IconButton(onClick = onToggleAutofillBlock) {
                            Icon(
                                imageVector = Icons.Default.Block,
                                contentDescription = stringResource(R.string.cd_autofill_block),
                                tint = if (uiState.isAutofillBlockedForApp) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
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
                EntryHeaderSection(entry = entry)

                // 快捷操作磁贴组
                QuickActionRow(
                    entry = entry,
                    onShowMessage = onShowMessage,
                    onCopyUsername = onCopyUsername,
                    onCopyPassword = onCopyPassword
                )

                // 基础凭据卡片
                SectionTitle(textRes = R.string.detail_basic_section)
                BasicCredentialsCard(
                    uiState = uiState,
                    entry = entry,
                    onTogglePasswordVisibility = onTogglePasswordVisibility,
                    onCopyPassword = onCopyPassword,
                    onCopyUsername = onCopyUsername
                )

                // TOTP 卡片
                if (entry.totpCode != null) {
                    SectionTitle(textRes = R.string.detail_totp_section)
                    TotpCard(uiState = uiState, entry = entry, onShowMessage = onShowMessage)
                }

                // Passkey 卡片
                if (entry.isPasskey) {
                    SectionTitle(textRes = R.string.detail_passkey_section)
                    PasskeyCard(entry = entry)
                }

                // 自定义字段卡片区
                if (entry.customFields.isNotEmpty()) {
                    SectionTitle(textRes = R.string.detail_custom_fields_section)
                    CustomFieldsCard(
                        uiState = uiState,
                        entry = entry,
                        onToggleCustomFieldVisibility = onToggleCustomFieldVisibility,
                        onCopyCustomField = onCopyCustomField,
                        onShowMessage = onShowMessage
                    )
                }

                // 附件文件列表卡片区
                if (entry.attachments.isNotEmpty()) {
                    SectionTitle(textRes = R.string.detail_attachments_section)
                    AttachmentsCard(
                        entry = entry,
                        onPreviewAttachment = { attachmentToPreview = it },
                        onExportAttachment = onExportAttachment
                    )
                }

                // 版本历史记录卡片区
                if (entry.revisions.isNotEmpty()) {
                    SectionTitle(textRes = R.string.detail_history_section)
                    RevisionsCard(
                        entry = entry,
                        onCompareRevision = { revisionToDiff = it },
                        onRequestRollback = { revisionToRollback = it }
                    )
                }

                // 备注
                if (entry.notes.isNotBlank()) {
                    SectionTitle(textRes = R.string.detail_notes_section)
                    NotesCard(entry = entry)
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
