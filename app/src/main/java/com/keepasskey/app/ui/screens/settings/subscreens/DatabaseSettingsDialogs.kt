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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R

/**
 * 密码库设置页的各功能对话框（原 DatabaseSettingsScreen 内联对话框整体搬移）。
 */

// 对话框 1：加密算法选择
@Composable
internal fun CipherAlgorithmDialog(
    currentAlgorithm: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlgorithmOptionDialog(
        titleRes = R.string.dbset_cipher_dialog_title,
        options = listOf(
            "ChaCha20-Poly1305 (256-bit)" to R.string.dbset_cipher_chacha_desc,
            "AES-256 (KDBX 4.1)" to R.string.dbset_cipher_aes_desc,
            "Twofish (256-bit)" to R.string.dbset_cipher_twofish_desc
        ),
        isSelected = { currentAlgorithm.startsWith(it.split(" ")[0]) },
        onSelect = onSelect,
        onDismiss = onDismiss
    )
}

// 对话框 2：KDF 密钥派生算法选择
@Composable
internal fun KdfAlgorithmDialog(
    currentAlgorithm: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlgorithmOptionDialog(
        titleRes = R.string.dbset_kdf_dialog_title,
        options = listOf(
            "Argon2id" to R.string.dbset_kdf_argon2id_desc,
            "Argon2d" to R.string.dbset_kdf_argon2d_desc,
            "AES-KDF" to R.string.dbset_kdf_aeskdf_desc
        ),
        isSelected = { currentAlgorithm == it },
        onSelect = onSelect,
        onDismiss = onDismiss
    )
}

/** 算法单选对话框通用骨架：加密算法与 KDF 派生算法共用（结构与原内联实现一致） */
@Composable
private fun AlgorithmOptionDialog(
    titleRes: Int,
    options: List<Pair<String, Int>>,
    isSelected: (String) -> Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (name, desc) ->
                    val selected = isSelected(name)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onSelect(name)
                                onDismiss()
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = {
                                onSelect(name)
                                onDismiss()
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_close))
            }
        }
    )
}

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

// 对话框 5：子数据库挂载
@Composable
internal fun ChildDatabaseDialog(
    onSelectFile: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dbset_child_db_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.dbset_child_db_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onSelectFile()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.dbset_child_db_select_file))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dbset_btn_done))
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
@Composable
internal fun ImportSourceDialog(
    onSourceSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dbset_import_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    stringResource(R.string.dbset_src_1pux),
                    stringResource(R.string.dbset_src_bitwarden),
                    stringResource(R.string.dbset_src_keepass),
                    stringResource(R.string.dbset_src_browser)
                ).forEach { source ->
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
                        Text(source, style = MaterialTheme.typography.bodyMedium)
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
