package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 自动填充三级屏蔽的管理组件（包级 / 保存侧 / 字段签名级）。
 *
 * ISSUE-P3-43：由 `AutofillSettingsScreen.kt` 搬出并扩展。
 * 包名列表型管理对话框（包级填充黑名单、保存侧黑名单）**共用**同一实现并参数化文案，
 * 避免两份几乎相同的对话框各自演化；字段签名级因签名不可逆，只能提供「计数 + 全部清除」。
 */

/** 可点击的「进入管理」列表行（右侧箭头样式与设置页其它二级入口一致）。 */
@Composable
internal fun AutofillManageEntryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(14.dp)
        )
    }
}

/**
 * 包名名单管理对话框：条目化展示（应用名 + 包名）与删除，底部按包名新增。
 * 新增失败（包名非法或已存在）如实上浮错误提示，不谎报成功。
 *
 * TASK-36 整改：此前渲染两条写死的示例条目并挂空 onClick 删除按钮，属假数据回显，已诚实化下架；
 * TASK-44 补齐真实生命周期；ISSUE-P3-43 参数化文案后由「填充黑名单」与「保存黑名单」共用。
 */
@Composable
internal fun PackageBlocklistManageDialog(
    title: String,
    description: String,
    emptyText: String,
    addHint: String,
    blockedPackages: List<String>,
    onDismiss: () -> Unit,
    onAdd: (String) -> Boolean,
    onRemove: (String) -> Boolean
) {
    var pendingPackage by remember { mutableStateOf("") }
    var showAddError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (blockedPackages.isEmpty()) {
                    Text(
                        text = emptyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        items(blockedPackages, key = { it }) { packageName ->
                            BlockedPackageRow(
                                packageName = packageName,
                                onRemove = { onRemove(packageName) }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = pendingPackage,
                    onValueChange = {
                        pendingPackage = it
                        showAddError = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(addHint) },
                    singleLine = true,
                    isError = showAddError
                )
                if (showAddError) {
                    Text(
                        text = stringResource(R.string.autofill_blacklist_add_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (onAdd(pendingPackage)) {
                        pendingPackage = ""
                        showAddError = false
                    } else {
                        // 真实失败如实反馈（包名非法 / 已存在于名单）
                        showAddError = true
                    }
                },
                enabled = pendingPackage.isNotBlank()
            ) {
                Text(stringResource(R.string.btn_add))
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
 * 字段签名级屏蔽的清除确认（ISSUE-P3-43 ②）。
 *
 * 只提供「全部清除」而非列表管理：签名是**不可逆**的（见 `AutofillFieldSignature`），
 * 本应用无法把它还原成「某应用某网址」再回显——伪造一份可读列表才是失真。
 */
@Composable
internal fun FieldBlocklistClearDialog(
    blockedFieldCount: Int,
    onDismiss: () -> Unit,
    onConfirmClear: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.autofill_field_block_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.autofill_field_block_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.autofill_field_block_count, blockedFieldCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirmClear()
                    onDismiss()
                },
                enabled = blockedFieldCount > 0
            ) {
                Text(stringResource(R.string.autofill_field_block_clear_btn))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_close))
            }
        }
    )
}

@Composable
private fun BlockedPackageRow(
    packageName: String,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = rememberAppLabel(packageName),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                text = packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.autofill_blacklist_delete_cd),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 解析已安装应用的可读名称（IO 线程）。包名不可解析（未安装 / 受包可见性限制）时
 * 如实回落为包名本身——不伪造应用名。
 */
@Composable
private fun rememberAppLabel(packageName: String): String {
    val context = LocalContext.current
    val label = produceState(initialValue = packageName, packageName) {
        value = withContext(Dispatchers.IO) {
            try {
                val info = context.packageManager.getApplicationInfo(packageName, 0)
                context.packageManager.getApplicationLabel(info).toString()
            } catch (_: Exception) {
                packageName
            }
        }
    }
    return label.value
}
