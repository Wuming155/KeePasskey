package com.keepasskey.app.ui.screens.settings.subscreens

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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.apps.InstalledAppOption
import com.keepasskey.app.apps.InstalledAppsCatalog
import com.keepasskey.app.ui.components.AppIconSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `PackageBlocklistManageDialog` 的段落组件（`ISSUE-P3-188` §205：原文件距 400 行余量仅 25 行，
 * 按 §192 / §196 的「余量逐案判」口径**另起新文件**；逐字搬动、零行为变更）。
 *
 * - [PackageBlocklistBody]：对话框 `text` 主体（说明 / 空态或名单 / 应用选择器入口 /
 *   新增错误提示 / 手工输入切换）——**不自持状态**，输入值与开关全部由对话框持有、经参数回传；
 * - [PackageBlocklistConfirmButton] / [PackageBlocklistDismissButton]：confirm / dismiss 槽位
 *   （手工输入模式下 confirm=新增、dismiss=关闭；名单模式下 confirm 槽让位给关闭）；
 * - [BlockedPackageRow] / [rememberAppOption]：名单行的图标 + 应用名 + 包名渲染
 *   （随 text 主体同迁，仍只服务于本对话框）。
 */

@Composable
internal fun PackageBlocklistBody(
    description: String,
    emptyText: String,
    addHint: String,
    blockedPackages: List<String>,
    showAddError: Boolean,
    manualEntry: Boolean,
    pendingPackage: String,
    onPickApp: () -> Unit,
    onPendingPackageChange: (String) -> Unit,
    onToggleManual: () -> Unit,
    onRemove: (String) -> Boolean
) {
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

        FilledTonalButton(
            onClick = onPickApp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Apps,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.autofill_blacklist_pick_app))
        }

        if (showAddError) {
            Text(
                text = stringResource(R.string.autofill_blacklist_add_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (manualEntry) {
            OutlinedTextField(
                value = pendingPackage,
                onValueChange = onPendingPackageChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(addHint) },
                singleLine = true,
                isError = showAddError
            )
        } else {
            TextButton(onClick = onToggleManual) {
                Text(stringResource(R.string.autofill_blacklist_manual_toggle))
            }
        }
    }
}

@Composable
internal fun PackageBlocklistConfirmButton(
    manualEntry: Boolean,
    pendingPackage: String,
    onConfirm: () -> Unit
) {
    if (manualEntry) {
        TextButton(
            onClick = onConfirm,
            enabled = pendingPackage.isNotBlank()
        ) {
            Text(stringResource(R.string.btn_add))
        }
    } else {
        TextButton(onClick = onConfirm) {
            Text(stringResource(R.string.btn_close))
        }
    }
}

@Composable
internal fun PackageBlocklistDismissButton(
    manualEntry: Boolean,
    onDismiss: () -> Unit
) {
    if (manualEntry) {
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.btn_close))
        }
    }
}

@Composable
internal fun BlockedPackageRow(
    packageName: String,
    onRemove: () -> Unit
) {
    val app = rememberAppOption(packageName)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        AppIconSlot(app = app, size = 28.dp)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            // 应用名不可读时回落为包名：此时不再重复渲染同一串，避免"同一串出现两遍"的噪声
            if (app.label != packageName) {
                Text(
                    text = packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
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
 * 解析包名的显示信息（应用名 + 图标，IO 线程）。
 *
 * 包名不可解析（未安装 / 受包可见性限制）时**如实回落为包名本身**并给出系统语义图标——
 * 不伪造应用名，也不假装该应用存在。
 */
@Composable
internal fun rememberAppOption(packageName: String): InstalledAppOption {
    val context = LocalContext.current
    val placeholder = remember(packageName) { InstalledAppOption(packageName, packageName) }
    val option = produceState(initialValue = placeholder, packageName) {
        val resolved = withContext(Dispatchers.IO) {
            InstalledAppsCatalog.lookup(context, packageName)
        }
        if (resolved != null) value = resolved
    }
    return option.value
}
