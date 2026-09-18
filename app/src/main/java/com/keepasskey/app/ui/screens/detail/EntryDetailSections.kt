package com.keepasskey.app.ui.screens.detail

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiEntryRevision
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.theme.MonospacePasswordStyle

/**
 * 自定义字段卡片区（受保护字段按需解密展示）
 */
@Composable
internal fun CustomFieldsCard(
    uiState: EntryDetailUiState,
    entry: UiVaultEntry,
    onToggleCustomFieldVisibility: (String) -> Unit,
    onCopyCustomField: (String, String) -> Unit,
    onShowMessage: (UiMessage) -> Unit
) {
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

/**
 * 附件文件列表卡片区
 */
@Composable
internal fun AttachmentsCard(
    entry: UiVaultEntry,
    onPreviewAttachment: (UiAttachment) -> Unit,
    onExportAttachment: (UiAttachment) -> Unit
) {
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
                            .clickable { onPreviewAttachment(att) }
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

/**
 * 版本历史记录卡片区
 */
@Composable
internal fun RevisionsCard(
    entry: UiVaultEntry,
    onCompareRevision: (UiEntryRevision) -> Unit,
    onRequestRollback: (UiEntryRevision) -> Unit
) {
    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            entry.revisions.forEach { rev ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = rev.summary,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.detail_revision_meta, rev.modifiedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onCompareRevision(rev) }) {
                            Text(stringResource(R.string.detail_diff_compare), fontSize = 12.sp)
                        }
                        TextButton(onClick = { onRequestRollback(rev) }) {
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

/**
 * 备注区块
 *
 * ISSUE-P3-02：[notesText] 为状态层装配后的展示文案——`{REF:...}` 已按公开字段展开，
 * 受保护字段保持掩码，本组件不做任何解析（展示层零业务逻辑）。
 */
@Composable
internal fun NotesCard(notesText: String, updatedAt: String) {
    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column {
            Text(
                text = notesText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.detail_updated_meta, updatedAt),
                style = MaterialTheme.typography.labelSmall,
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
    val state = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(value) }
    LaunchedEffect(Unit) { state.value = value }
    return state
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@Preview(name = "详情页字段与附件区块 - 浅色", showBackground = true)
@Preview(name = "详情页字段与附件区块 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun CustomFieldsCardPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        val previewEntry = com.keepasskey.app.ui.preview.PreviewEntryLogin
        val previewUiState = previewStateOf(
            com.keepasskey.app.ui.screens.detail.EntryDetailUiState(
                entry = previewEntry,
                protectedFieldsVisibility = mapOf("preview-field-2" to false),
                revealedProtectedFields = mapOf("preview-field-2" to "预览受保护字段值")
            )
        ).value

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CustomFieldsCard(
                uiState = previewUiState,
                entry = previewEntry,
                onToggleCustomFieldVisibility = { _ -> },
                onCopyCustomField = { _, _ -> },
                onShowMessage = { _ -> }
            )
            AttachmentsCard(
                entry = previewEntry.copy(
                    attachments = com.keepasskey.app.ui.preview.PreviewAttachments
                ),
                onPreviewAttachment = { _ -> },
                onExportAttachment = { _ -> }
            )
            RevisionsCard(
                entry = previewEntry.copy(
                    revisions = com.keepasskey.app.ui.preview.PreviewRevisions
                ),
                onCompareRevision = { _ -> },
                onRequestRollback = { _ -> }
            )
            NotesCard(notesText = "预览用备注文本，仅用于界面排版展示。", updatedAt = "2026-01-02 12:00")
        }
    }
}
