package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R

/**
 * ISSUE-P3-385：库级 Meta 编辑对话框（库名 / 描述 / 默认用户名）。
 * 保存后经 ViewModel → SettingsDatabaseMetaController 写库 Meta 并立即 save()。
 * 合并语义：PD-35 裁定这些字段「以本地为准」，本对话框不改变该口径。
 */
@Composable
internal fun DatabaseMetaEditDialog(
    databaseName: String,
    databaseDescription: String,
    defaultUserName: String,
    onSave: (databaseName: String, databaseDescription: String, defaultUserName: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(databaseName) { mutableStateOf(databaseName) }
    var description by remember(databaseDescription) { mutableStateOf(databaseDescription) }
    var user by remember(defaultUserName) { mutableStateOf(defaultUserName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dbset_meta_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.dbset_meta_edit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.dbset_field_db_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.dbset_field_db_desc)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text(stringResource(R.string.dbset_field_default_user)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name, description, user) }) {
                Text(stringResource(R.string.dbset_meta_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dbset_meta_cancel))
            }
        }
    )
}
