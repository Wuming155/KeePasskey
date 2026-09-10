package com.keepasskey.app.ui.screens.settings.subscreens

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
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.data.importer.ImportSource

/**
 * 密码库设置页的功能对话框（原 DatabaseSettingsScreen 内联对话框整体搬移）。
 *
 * ISSUE-P3-29：本文件为纯结构性拆分后保留的「模板 / 导出 / 导入」三组对话框；
 * 算法选择对话框已迁 `DatabaseAlgorithmDialogs.kt`，子库挂载对话框已迁 `ChildDatabaseDialogs.kt`。
 */

// 对话框 4：模板库管理对话框
@Composable
internal fun DatabaseTemplatesDialog(
    onInstallTemplates: () -> Unit,
    onDismiss: () -> Unit
) {
    val templates = listOf(
        stringResource(R.string.dbset_tpl_web) to Icons.Default.Lock,
        stringResource(R.string.dbset_tpl_credit_card) to Icons.Default.CreditCard,
        stringResource(R.string.dbset_tpl_wifi) to Icons.Default.Wifi,
        stringResource(R.string.dbset_tpl_note) to Icons.AutoMirrored.Filled.Notes,
        stringResource(R.string.dbset_tpl_ssh) to Icons.Default.Terminal
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dbset_templates_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.dbset_templates_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                templates.forEach { (name, icon) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(text = name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onDismiss()
                // TASK-13 整改：真实创建模板分组与模板条目（幂等）
                onInstallTemplates()
            }) {
                Text(stringResource(R.string.dbset_install_templates))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_close))
            }
        }
    )
}

// 对话框 6：导出密码库
@Composable
internal fun ExportDatabaseDialog(
    databaseName: String,
    onExportKdbx: (String) -> Unit,
    onExportXml: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dbset_export_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.dbset_export_dialog_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Button(
                    onClick = {
                        onDismiss()
                        // TASK-13 整改：呼起 SAF 另存为，当前内存数据库经仓库真实序列化落盘
                        onExportKdbx(databaseName.ifBlank { "keepasskey-export.kdbx" })
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.dbset_export_kdbx_btn))
                }
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        // TASK-13 整改：呼起 SAF 另存为，明文 XML 经 KeePassXmlExporter 真实生成
                        val baseName = databaseName.removeSuffix(".kdbx")
                            .ifBlank { "keepasskey" }
                        onExportXml("$baseName-export.xml")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.dbset_export_xml_btn))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

// 对话框 7：导入数据源
//
// ISSUE-P3-19：解析器（KeePass XML / Bitwarden JSON / 浏览器 CSV / 1PUX）已真实落地并经 40 例单测覆盖，
// 故：
// 1. 删除原 `dbset_import_reserved_note`（「解析器预留，暂未生效」）提示块——该提示已反向失真；
// 2. `onSourceSelected` 由「本地化显示字符串」改为**传 `ImportSource` 枚举**：文案与枚举在同一处绑定，
//    杜绝「拿本地化文案反查枚举」的脆弱映射（改文案就会静默失配）。
@Composable
internal fun ImportSourceDialog(
    onSourceSelected: (ImportSource) -> Unit,
    onDismiss: () -> Unit
) {
    // 选项与枚举同处绑定；顺序与用户心智一致（1PUX / Bitwarden / KeePass / 浏览器）
    val options = listOf(
        ImportSource.ONEPASSWORD_1PUX to R.string.dbset_src_1pux,
        ImportSource.BITWARDEN_JSON to R.string.dbset_src_bitwarden,
        ImportSource.KEEPASS_XML to R.string.dbset_src_keepass,
        ImportSource.BROWSER_CSV to R.string.dbset_src_browser
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dbset_import_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (source, labelRes) ->
                    val label = stringResource(labelRes)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onDismiss()
                                onSourceSelected(source)
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}
