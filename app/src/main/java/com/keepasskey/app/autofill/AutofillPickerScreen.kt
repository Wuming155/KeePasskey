package com.keepasskey.app.autofill

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.core.model.KdbxEntry

/**
 * 自动填充「手动选择器」界面（ISSUE-P3-40）。
 *
 * 自动匹配无候选或候选不含目标条目时的兜底入口：用户可搜索全库条目并选择填充。
 *
 * 零秘密热路径：列表只渲染标题 / 用户名 / 网址等**非敏感元数据**，
 * 密码在用户点选某一行后才由 [AutofillPickerViewModel.resolveCredentials] 按需解密。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutofillPickerScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<KdbxEntry>,
    onPick: (String) -> Unit,
    onCancel: () -> Unit,
    // ISSUE-P3-43 ②：字段签名级屏蔽入口。仅在本次请求识别到对应框时可用，
    // 否则不呈现（杜绝无对象的假按钮）
    canBlockUsername: Boolean = false,
    canBlockPassword: Boolean = false,
    onBlockField: (AutofillFieldRole) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var pendingBlockRole by remember { mutableStateOf<AutofillFieldRole?>(null) }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.autofill_picker_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                }
            )

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text(stringResource(R.string.autofill_picker_query_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // ISSUE-P3-43 ②：字段级屏蔽入口（点击后先经确认弹窗，避免误触导致后续不再填充）
            if (canBlockUsername || canBlockPassword) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                ) {
                    if (canBlockUsername) {
                        TextButton(onClick = { pendingBlockRole = AutofillFieldRole.USERNAME }) {
                            Text(stringResource(R.string.autofill_picker_block_username))
                        }
                    }
                    if (canBlockPassword) {
                        TextButton(onClick = { pendingBlockRole = AutofillFieldRole.PASSWORD }) {
                            Text(stringResource(R.string.autofill_picker_block_password))
                        }
                    }
                }
            }

            if (results.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.autofill_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = results, key = { it.id.toHexString() }) { entry ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(entry.id.toHexString()) }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text(
                                text = entry.title.ifBlank { entry.userName },
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val subtitle = entry.userName.ifBlank { entry.url }
                            if (subtitle.isNotBlank()) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 屏蔽确认弹窗：如实说明「同一应用 + 同一网址 + 同一角色」不再填充，且可在设置中整体清除
    pendingBlockRole?.let { role ->
        AlertDialog(
            onDismissRequest = { pendingBlockRole = null },
            title = { Text(stringResource(R.string.autofill_picker_block_confirm_title)) },
            text = { Text(stringResource(R.string.autofill_picker_block_confirm_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingBlockRole = null
                    onBlockField(role)
                }) {
                    Text(stringResource(R.string.autofill_picker_block_confirm_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBlockRole = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}
