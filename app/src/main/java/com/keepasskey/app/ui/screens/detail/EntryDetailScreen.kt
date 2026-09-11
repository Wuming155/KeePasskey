package com.keepasskey.app.ui.screens.detail

import android.net.Uri
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.keepasskey.app.ui.screens.vault.VaultBatchMoveDialog
import com.keepasskey.app.ui.theme.CapsuleShape

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

    // ISSUE-P3-48：删除成功后返回列表——条目已移入回收站（或已在站内被彻底删除），
    // 详情页不再有对应实体，停留会呈现「条目不存在」，故一次性回退导航。
    val entryDeleted by viewModel.entryDeleted.collectAsStateWithLifecycle()
    LaunchedEffect(entryDeleted) {
        if (entryDeleted) onBackClick()
    }

    // ISSUE-P3-17：进入详情页时刷新进阶显示偏好快照（遮掩默认值 / 所属分组开关）
    LaunchedEffect(Unit) { viewModel.onScreenEntered() }

    // 断点3 整改：SAF 导出挂起中的附件，选择目标后交给 ViewModel 真实写盘
    var pendingExportAttachment by remember { mutableStateOf<UiAttachment?>(null) }
    // ISSUE-P2-10 (ZT-15)：附件为解密后明文，SAF 目标选定后必须先经风险确认才允许写盘
    var pendingPlaintextExportUri by remember { mutableStateOf<Uri?>(null) }
    var showPlaintextExportConfirm by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val att = pendingExportAttachment
        if (uri != null && att != null) {
            // 不直接导出：保留待导出附件与目标，弹出明文风险确认后再决定是否写盘
            pendingPlaintextExportUri = uri
            showPlaintextExportConfirm = true
        } else {
            pendingExportAttachment = null
        }
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
        // ISSUE-P3-02：自定义图标删除（经确认弹窗后调用，状态层负责清理 Meta 与回退引用）
        onDeleteCustomIcon = viewModel::deleteCustomIcon,
        // ISSUE-P3-48：单条删除（经确认弹窗后调用，语义为移入回收站 / 站内彻底删除）
        onDeleteEntry = viewModel::deleteEntry,
        // ISSUE-P3-51：单条移动到分组（null = 根目录）
        onMoveEntry = viewModel::moveEntryToGroup,
        onTogglePasswordVisibility = viewModel::togglePasswordVisibility,
        // ISSUE-P3-17：TOTP 验证码显式展开/收起（默认态来自 maskTotpDefault）
        onToggleTotpVisibility = viewModel::toggleTotpVisibility,
        // ISSUE-P3-49：HOTP 取码（推进计数器并复制）
        onAdvanceHotp = viewModel::advanceHotp,
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

    // ISSUE-P2-10 (ZT-15)：明文附件导出二次确认——取消分支不写盘（fail-closed），
    // 仅确认后才以 confirmed = true 委托 ViewModel 落盘并写审计
    if (showPlaintextExportConfirm) {
        val attachment = pendingExportAttachment
        val targetUri = pendingPlaintextExportUri
        AlertDialog(
            onDismissRequest = {
                showPlaintextExportConfirm = false
                pendingPlaintextExportUri = null
                pendingExportAttachment = null
            },
            title = { Text(stringResource(R.string.detail_attachment_export_warn_title)) },
            text = {
                Text(
                    text = stringResource(R.string.detail_attachment_export_warn_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPlaintextExportConfirm = false
                    pendingPlaintextExportUri = null
                    pendingExportAttachment = null
                    if (attachment != null && targetUri != null) {
                        viewModel.exportAttachment(attachment, targetUri, confirmed = true)
                    }
                }) {
                    Text(stringResource(R.string.detail_attachment_export_warn_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPlaintextExportConfirm = false
                    pendingPlaintextExportUri = null
                    pendingExportAttachment = null
                }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
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
    // ISSUE-P3-02：自定义图标删除（确认弹窗确认后上行；ViewModel 负责库级清理与引用回退）
    onDeleteCustomIcon: () -> Unit = {},
    // ISSUE-P3-48：单条删除（确认弹窗确认后上行；语义为移入回收站 / 站内彻底删除）
    onDeleteEntry: () -> Unit = {},
    // ISSUE-P3-51：单条移动到分组（目标分组 id，null = 根目录）
    onMoveEntry: (String?) -> Unit = {},
    onTogglePasswordVisibility: () -> Unit,
    // ISSUE-P3-17：TOTP 验证码显式展开/收起（默认态来自 maskTotpDefault）
    onToggleTotpVisibility: () -> Unit = {},
    // ISSUE-P3-49：HOTP 取码（推进计数器并复制本次所出之码）
    onAdvanceHotp: () -> Unit = {},
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
    var revisionToRollback by remember { mutableStateOf<UiEntryRevision?>(null) }
    var revisionToDiff by remember { mutableStateOf<UiEntryRevision?>(null) }
    var attachmentToPreview by remember { mutableStateOf<UiAttachment?>(null) }
    // ISSUE-P3-02：自定义图标删除二次确认（库级共享资源，防误删）
    var showDeleteIconConfirm by remember { mutableStateOf(false) }
    // ISSUE-P3-48：单条删除二次确认（移入回收站，防误删）
    var showDeleteEntryConfirm by remember { mutableStateOf(false) }
    // ISSUE-P3-51：单条移动到分组的分组选择器
    var showMoveDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            EntryDetailTopBar(
                uiState = uiState,
                onBackClick = onBackClick,
                onToggleFavorite = onToggleFavorite,
                onDuplicateEntry = onDuplicateEntry,
                onToggleAutofillBlock = onToggleAutofillBlock,
                onEditClick = onEditClick,
                onRequestMoveEntry = { showMoveDialog = true },
                onRequestDeleteEntry = { showDeleteEntryConfirm = true },
                onRequestDeleteCustomIcon = { showDeleteIconConfirm = true }
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
                // 头部 Hero 区域（ISSUE-P3-02：图标投影 + URL 引用展开文案均取自状态层装饰；
                // ISSUE-P3-17：groupPath 仅在 showGroupInEntry 开启时非空）
                EntryHeaderSection(
                    entry = entry,
                    icon = uiState.decorations.iconOf(entry),
                    urlText = uiState.decorations.textOf(entry).url,
                    groupPath = uiState.groupPath
                )

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
                    TotpCard(
                        uiState = uiState,
                        entry = entry,
                        onToggleVisibility = onToggleTotpVisibility,
                        onAdvanceHotp = onAdvanceHotp,
                        onShowMessage = onShowMessage
                    )
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

                // 备注（ISSUE-P3-02：展示文案经状态层展开 {REF:...} 引用，受保护字段为掩码）
                val notesText = uiState.decorations.textOf(entry).notes
                if (notesText.isNotBlank()) {
                    SectionTitle(textRes = R.string.detail_notes_section)
                    NotesCard(notesText = notesText, updatedAt = entry.updatedAt)
                }

                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }

    // ISSUE-P3-02：自定义图标删除确认（库级共享资源，确认后才上行删除）
    if (showDeleteIconConfirm) {
        DeleteCustomIconDialog(
            onConfirm = {
                showDeleteIconConfirm = false
                onDeleteCustomIcon()
            },
            onDismiss = { showDeleteIconConfirm = false }
        )
    }

    // ISSUE-P3-48：单条删除确认（确认后才上行；语义为移入回收站 / 站内彻底删除）
    if (showDeleteEntryConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteEntryConfirm = false },
            title = { Text(stringResource(R.string.detail_delete_entry_title)) },
            text = { Text(stringResource(R.string.detail_delete_entry_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteEntryConfirm = false
                    onDeleteEntry()
                }) {
                    Text(
                        text = stringResource(R.string.btn_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteEntryConfirm = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
            shape = RoundedCornerShape(18.dp)
        )
    }

    // ISSUE-P3-51：单条移动到分组选择器（回收站分组由对话框统一过滤）
    if (showMoveDialog) {
        VaultBatchMoveDialog(
            allGroups = uiState.allGroups,
            onDismiss = { showMoveDialog = false },
            onMove = { targetGroupId ->
                showMoveDialog = false
                onMoveEntry(targetGroupId)
            },
            titleRes = R.string.detail_move_dialog_title
        )
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
