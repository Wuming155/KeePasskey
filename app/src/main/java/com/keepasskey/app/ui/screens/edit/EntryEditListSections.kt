package com.keepasskey.app.ui.screens.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.components.SecurePasswordField
import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.theme.CapsuleShape

/**
 * 编辑页的列表型分节（自定义字段管理 / 附件文件管理）。
 *
 * 纯结构性拆分：自 [EntryEditComponents] 整体搬移，逐字保留原有实现、UI 文案与
 * 敏感数据链路（受保护字段值仍以 CharArray 直达 ViewModel）。
 */

/**
 * 自定义字段编辑区（动态添加/修改/删除）：区块标题 + 字段卡片（TASK-21 拆分：
 * 自 [EntryEditContent] 整体搬移，在原 Column 位置发射标题与卡片两个兄弟节点，
 * 组合结构不变）
 */
@Composable
internal fun EntryEditCustomFieldsSection(
    customFields: List<UiCustomField>,
    loadedProtectedFields: Map<String, CharArray>,
    onAddCustomField: () -> Unit,
    onUpdateCustomField: (String, String, String, Boolean) -> Unit,
    onUpdateProtectedFieldValue: (String, CharArray) -> Unit,
    onRemoveCustomField: (String) -> Unit
) {
    Text(
        text = stringResource(R.string.edit_custom_fields),
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface
    )

    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            customFields.forEach { field ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        OutlinedTextField(
                            value = field.key,
                            onValueChange = { onUpdateCustomField(field.id, it, field.value, field.isProtected) },
                            label = { Text(stringResource(R.string.edit_field_key)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onRemoveCustomField(field.id) }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.btn_delete),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    if (field.isProtected) {
                        // TASK-10 整改（加解密审查 B9）：受保护字段明文输入走
                        // SecurePasswordField——显示用 String 仅存活于组件内部，
                        // CharArray 直达 ViewModel 私有链路；既有值经预填通道一次性注入
                        var protectedVisible by remember(field.id) { mutableStateOf(false) }
                        SecurePasswordField(
                            label = stringResource(R.string.edit_field_value),
                            onPasswordChanged = { chars ->
                                onUpdateProtectedFieldValue(field.id, chars)
                            },
                            isPasswordVisible = protectedVisible,
                            onToggleVisibility = { protectedVisible = !protectedVisible },
                            initialPassword = loadedProtectedFields[field.id],
                            initialKey = field.id,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedTextField(
                            value = field.value,
                            onValueChange = { onUpdateCustomField(field.id, field.key, it, field.isProtected) },
                            label = { Text(stringResource(R.string.edit_field_value)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            onUpdateCustomField(field.id, field.key, field.value, !field.isProtected)
                        }
                    ) {
                        Checkbox(
                            checked = field.isProtected,
                            onCheckedChange = { onUpdateCustomField(field.id, field.key, field.value, it) }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.edit_field_protected),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onAddCustomField,
                shape = CapsuleShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.edit_add_field))
            }
        }
    }
}

/**
 * 附件文件管理区：区块标题 + 附件列表卡片（TASK-21 拆分：自 [EntryEditContent]
 * 整体搬移的自包含区块，在原 Column 位置发射标题与卡片两个兄弟节点，组合结构不变）
 */
@Composable
internal fun EntryEditAttachmentsSection(
    attachments: List<UiAttachment>,
    onPickAttachmentFile: () -> Unit,
    onRemoveAttachment: (String) -> Unit
) {
    Text(
        text = stringResource(R.string.edit_attachments),
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface
    )

    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            attachments.forEach { att ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${att.fileName} (${att.fileSizeFormatted})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    IconButton(onClick = { onRemoveAttachment(att.id) }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.btn_delete),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            OutlinedButton(
                // 断点1 整改：移除写死假附件名，呼起真实 SAF 文件选择器
                onClick = onPickAttachmentFile,
                shape = CapsuleShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.edit_add_attachment))
            }
        }
    }
}
