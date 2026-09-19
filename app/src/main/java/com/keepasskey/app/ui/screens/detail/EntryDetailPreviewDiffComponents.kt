package com.keepasskey.app.ui.screens.detail

import android.content.res.Configuration
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.security.SecureDialog
import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiEntryRevision
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.theme.CapsuleShape
import com.keepasskey.app.ui.theme.MonospacePasswordStyle

/**
 * 历史版本差异对比对话框 (Visual Diff Dialog)
 */
@Composable
fun RevisionVisualDiffDialog(
    currentEntry: UiVaultEntry,
    revision: UiEntryRevision,
    // M1 整改：密码明文由调用方按需解密后传入，不再随条目/修订投影携带
    currentPassword: String = "",
    revisionPassword: String = "",
    onDismiss: () -> Unit,
    onRollback: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Difference, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.diff_dialog_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        text = {
            // FLAG_SECURE 是窗口级属性：AlertDialog 是独立窗口，其内容含密码明文差异，
            // 必须在对话框自身内容里施加（见 SecureDialog KDoc 与官方说明）
            SecureDialog {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = stringResource(R.string.diff_compare_summary, revision.modifiedAt, revision.summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 密码差异字段
                    DiffFieldCard(
                        fieldLabel = stringResource(R.string.diff_field_password),
                        currentValue = currentPassword,
                        historicalValue = revisionPassword,
                        isSensitive = true
                    )

                    // 用户名差异字段
                    DiffFieldCard(
                        fieldLabel = stringResource(R.string.diff_field_username),
                        currentValue = currentEntry.username,
                        historicalValue = revision.username,
                        isSensitive = false
                    )

                    // 备注差异字段
                    if (currentEntry.notes != revision.notes) {
                        DiffFieldCard(
                            fieldLabel = stringResource(R.string.diff_field_notes),
                            currentValue = currentEntry.notes.ifBlank { stringResource(R.string.diff_notes_empty) },
                            historicalValue = revision.notes.ifBlank { stringResource(R.string.diff_notes_empty) },
                            isSensitive = false
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onRollback,
                shape = CapsuleShape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.diff_rollback_btn))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

@Composable
private fun DiffFieldCard(
    fieldLabel: String,
    currentValue: String,
    historicalValue: String,
    isSensitive: Boolean
) {
    val isChanged = currentValue != historicalValue

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = fieldLabel, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                if (isChanged) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        shape = CapsuleShape
                    ) {
                        Text(
                            text = stringResource(R.string.diff_changed),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.diff_unchanged),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 历史值
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = stringResource(R.string.diff_old_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                Text(
                    text = if (isSensitive) historicalValue else historicalValue,
                    style = if (isSensitive) MonospacePasswordStyle.copy(fontSize = 13.sp) else MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // 当前值
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = stringResource(R.string.diff_new_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    text = if (isSensitive) currentValue else currentValue,
                    style = if (isSensitive) MonospacePasswordStyle.copy(fontSize = 13.sp) else MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * 内置安全附件预览器对话框 (Safe Attachment Preview Dialog)
 */
@Composable
fun SafeAttachmentPreviewDialog(
    attachment: UiAttachment,
    onDismiss: () -> Unit,
    onExport: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = if (attachment.mimeType.startsWith("image/")) Icons.Default.Image else Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = attachment.fileName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.btn_close), modifier = Modifier.size(20.dp))
                }
            }
        },
        text = {
            // 同上：附件预览对话框是独立窗口，预览区渲染库内敏感内容，须自带 FLAG_SECURE
            SecureDialog {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    SafeAttachmentIsolationNotice()

                    SafeAttachmentPreviewCanvas(attachment = attachment)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onExport,
                shape = CapsuleShape
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.diff_export_btn))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_close))
            }
        }
    )
}

/**
 * 「安全隔离提示」横幅（§210 自 [SafeAttachmentPreviewDialog] 下沉，逐字搬动、零行为变更）。
 */
@Composable
private fun SafeAttachmentIsolationNotice() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.diff_attachment_isolated),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * 预览区画布（锁形图标 + MIME 类型 + 大小；真实内容不落预览——只渲染元信息）。
 * §210 自 [SafeAttachmentPreviewDialog] 下沉（逐字搬动、零行为变更）；
 * `SecureDialog {` 包裹留在宿主——本文件在该守卫的调用点计数清单内（expectedCalls=2），不得迁出。
 */
@Composable
private fun SafeAttachmentPreviewCanvas(attachment: UiAttachment) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = attachment.mimeType,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.diff_attachment_size, attachment.fileSizeFormatted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
// ISSUE-P3-135：两个对话框各自独立成图——此前把二者堆进同一张预览，
// 导出图观感成了「同一个对话框底部两排按钮」，无法辨识各自的操作区
@Preview(name = "历史版本差异对比 - 浅色", showBackground = true)
@Preview(name = "历史版本差异对比 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun RevisionVisualDiffDialogPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        RevisionVisualDiffDialog(
            currentEntry = com.keepasskey.app.ui.preview.PreviewEntryLogin,
            revision = com.keepasskey.app.ui.preview.PreviewRevisions.first(),
            currentPassword = "预览当前假密码",
            revisionPassword = "预览历史假密码",
            onDismiss = {},
            onRollback = {}
        )
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@Preview(name = "安全附件预览对话框 - 浅色", showBackground = true)
@Preview(name = "安全附件预览对话框 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun SafeAttachmentPreviewDialogPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        SafeAttachmentPreviewDialog(
            attachment = com.keepasskey.app.ui.preview.PreviewAttachments.first(),
            onDismiss = {},
            onExport = {}
        )
    }
}
