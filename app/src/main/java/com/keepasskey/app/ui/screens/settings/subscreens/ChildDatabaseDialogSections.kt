package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.screens.settings.ChildDatabaseFeedback
import com.keepasskey.app.ui.screens.settings.ChildDatabaseMountUiState
import com.keepasskey.app.ui.screens.settings.ChildDatabaseStatus
import com.keepasskey.app.ui.screens.settings.childDatabaseSourceDisplayName

/**
 * `ChildDatabaseDialog` 的非保密段落组件（§193 自 `ChildDatabaseDialogs.kt` 下沉，窄参数、
 * 不读页面级 `UiState`、不自持状态）。
 *
 * **刻意留在原对话框的两样东西**：
 * 1. `SecureDialogWindowEffect()`——`SecureDialogFlagPolicyTest` 按**文件**计数
 *    （该文件必须恰有 2 处），把它跟段落组件一起搬走会让该页的 FLAG_SECURE 接线失去可断言落点；
 * 2. 子库主密码链路（`password` 的 `CharArray` 副本、`SecurePasswordField`、
 *    提交后即 `fill('0')` 清空、`credentialEpoch` 擦除通知）——明文所有权与擦除义务必须同处一屏，
 *    与 §179 / §186 的「保密钥链路留本体」同一口径。
 */

/** 对话框说明段：两条真实语义说明 + 分隔线（原样搬移）。 */
@Composable
internal fun ChildDatabaseDialogIntro() {
    Text(
        text = stringResource(R.string.dbset_child_db_dialog_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        text = stringResource(R.string.dbset_child_db_credential_dialog_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * 来源文件与密钥文件两条选择入口，各带回显。
 *
 * 两处的 `enabled` 由调用方传入（控制器缺失时整体禁用，即事实本身）。
 */
@Composable
internal fun ChildDatabaseSourcePickers(
    enabled: Boolean,
    selectedSourceUri: String?,
    selectedKeyFileUri: String?,
    onPickSource: () -> Unit,
    onPickKeyFile: () -> Unit
) {
    OutlinedButton(
        onClick = onPickSource,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.dbset_child_db_select_file))
    }
    // 已选来源回显：仅文件名（非敏感元数据），避免用户对「选了哪个文件」无据可依
    selectedSourceUri?.let { source ->
        Text(
            text = childDatabaseSourceDisplayName(source),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    OutlinedButton(
        onClick = onPickKeyFile,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = selectedKeyFileUri?.let {
                stringResource(
                    R.string.unlock_keyfile_selected,
                    childDatabaseSourceDisplayName(it)
                )
            } ?: stringResource(R.string.unlock_keyfile_none)
        )
    }
}

/**
 * 已挂载子库清单与控制器反馈。
 *
 * 「已挂载」不等于「已解锁」：状态文案来自核心层真实流转，`Opening` 用不确定进度呈现，
 * 既不伪造成「未解锁」也不提前报「已解锁」。
 */
@Composable
internal fun ChildDatabaseMountListSection(
    available: Boolean,
    mounts: List<ChildDatabaseMountUiState>,
    feedback: ChildDatabaseFeedback?,
    onUnlockRequest: (String) -> Unit,
    onUnmount: (String) -> Unit
) {
    // 控制器缺失（仅单测/异常装配）时上方全部控件已被禁用，即事实本身；
    // 此处不再补文案（无对应资源），生产装配下 available 恒为 true。
    if (available) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (mounts.isEmpty()) {
            Text(
                text = stringResource(R.string.dbset_child_db_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            mounts.forEach { mount ->
                ChildDatabaseMountRow(
                    mount = mount,
                    onUnlock = { onUnlockRequest(mount.mountId) },
                    onUnmount = { onUnmount(mount.mountId) }
                )
            }
            Text(
                text = stringResource(R.string.dbset_child_db_unmount_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        feedback?.let { message ->
            Text(
                text = message.message.resolveText(),
                style = MaterialTheme.typography.bodySmall,
                color = if (message.isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
        }
    }
}

/** 单条已挂载子库：别名 + 真实状态（或「正在打开」进度）+ 解锁/卸载入口 */
@Composable
private fun ChildDatabaseMountRow(
    mount: ChildDatabaseMountUiState,
    onUnlock: () -> Unit,
    onUnmount: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mount.alias,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            when (val status = mount.status) {
                is ChildDatabaseStatus.Text -> Text(
                    text = status.message.resolveText(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                ChildDatabaseStatus.Opening -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp
                )
            }
        }
        // 「已挂载」不等于「已解锁」：未解锁状态下必须给出真实的重试入口
        if (mount.canRetryWithCredentials) {
            TextButton(onClick = onUnlock) {
                Text(stringResource(R.string.dbset_child_db_btn_unlock))
            }
        }
        TextButton(onClick = onUnmount) {
            Text(stringResource(R.string.dbset_child_db_btn_unmount))
        }
    }
}
