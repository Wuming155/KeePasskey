package com.keepasskey.app.ui.screens.edit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.security.SecureDialogWindowEffect
import com.keepasskey.app.ui.components.CustomIconItem
import com.keepasskey.app.ui.components.IconPickerDialog
import com.keepasskey.app.ui.theme.CapsuleShape

/**
 * 凭据编辑页的**屏幕外壳**（§165 自 `EntryEditScreen.kt` 纯结构性抽出，UI 树与文案逐字未改）。
 *
 * 收录四件窄参数、不读 ViewModel、不自持任何状态的展示件：顶栏、底部保存大按钮、
 * 图标选择对话框与「丢弃未保存更改」确认弹窗。抽出后母文件只保留状态与回调的编排。
 *
 * **状态所有权未下沉**：`showIconPicker` / `showDiscardDialog` 仍由 `EntryEditContent` 持有——
 * 「选中图标后关闭对话框」这类复合动作因此留在同一处（父级传入的 lambda 里），
 * 不出现「关对话框」这件事有两处真相的漂移面。
 */

/** 顶栏：标题按 `entryId` 是否为空区分「编辑 / 新建」；只读会话或保存进行中禁用保存。 */
@Composable
internal fun EntryEditTopBar(
    entryId: String?,
    isReadOnly: Boolean,
    /** ISSUE-P2-354 AC①：保存进行中——按钮禁用并内嵌进度（无字面量默认值，调用方必须显式传） */
    isSaving: Boolean,
    /** ISSUE-P3-368 AC②：保存链进度（0..1；null = 分段不确定段），仅保存中渲染顶栏下沿进度条 */
    saveProgress: Float?,
    requestBack: () -> Unit,
    onSaveClick: () -> Unit
) {
    Column {
        TopAppBar(
            title = {
                Text(
                    text = if (entryId != null) stringResource(R.string.edit_title_edit) else stringResource(R.string.edit_title_new),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            navigationIcon = {
                IconButton(onClick = requestBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back)
                    )
                }
            },
            actions = {
                TextButton(onClick = onSaveClick, enabled = !isReadOnly && !isSaving) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(SAVE_INNER_PROGRESS_SIZE.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.cd_save),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface
            )
        )
        // ISSUE-P3-368 AC②：保存进度条（顶栏下沿）——确定段按 0..1，KDF 等不确定段跑马灯；
        // 仅 isSaving 期间呈现，按钮内嵌圈与 isSaving 语义保持原样
        if (isSaving) {
            val barModifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
            if (saveProgress == null) {
                LinearProgressIndicator(modifier = barModifier)
            } else {
                LinearProgressIndicator(
                    progress = { saveProgress.coerceIn(0f, 1f) },
                    modifier = barModifier
                )
            }
        }
    }
}

/** 底部保存大按钮（H4-只读整改：只读会话禁用保存；ISSUE-P2-354 AC①：保存中禁用并内嵌进度）。 */
@Composable
internal fun EntryEditSaveButton(
    isReadOnly: Boolean,
    /** ISSUE-P2-354 AC①：保存进行中——按钮禁用并内嵌进度（无字面量默认值，调用方必须显式传） */
    isSaving: Boolean,
    onSaveClick: () -> Unit
) {
    Button(
        onClick = onSaveClick,
        enabled = !isReadOnly && !isSaving,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = CapsuleShape
    ) {
        if (isSaving) {
            CircularProgressIndicator(
                modifier = Modifier.size(SAVE_INNER_PROGRESS_SIZE.dp),
                strokeWidth = 2.dp
            )
        } else {
            Text(
                text = stringResource(R.string.edit_save_btn),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
        }
    }
}

/** 图标选择对话框（TASK-15：含库内自定义图标池与相册上传入口）。 */
@Composable
internal fun EntryEditIconPickerDialog(
    iconName: String,
    customIconId: String?,
    customIcons: List<CustomIconItem>,
    onSelectIcon: (String) -> Unit,
    onSelectCustomIcon: (String) -> Unit,
    onUploadClick: () -> Unit,
    onDismiss: () -> Unit
) {
    IconPickerDialog(
        selectedIconName = iconName,
        onSelectIcon = onSelectIcon,
        onDismiss = onDismiss,
        // TASK-15：自定义图标扩展段
        customIcons = customIcons,
        selectedCustomIconId = customIconId,
        onSelectCustomIcon = onSelectCustomIcon,
        onUploadClick = onUploadClick
    )
}

/**
 * 解除通行密钥绑定确认弹窗（`ISSUE-P3-342`）。
 *
 * 存在的理由：旧「解除」按钮只翻草稿布尔、从不落盘，用户以为撤下了凭据而它仍可被
 * Credential Manager 捞出并签名。现在它是一条**不可逆的真实写操作**，故必须先经确认，
 * 且正文要写清"删的是什么、留的是什么"。
 *
 * `FLAG_SECURE` 跟随设置开关（`PD-48` 裁决三，与通行密钥导入确认框同口径）；
 * 反 overlay 与点击劫持两层由 [SecureDialogWindowEffect] 始终施加。
 */
@Composable
internal fun EntryEditUnbindPasskeyDialog(
    flagSecureEnabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    SecureDialogWindowEffect(flagSecure = flagSecureEnabled)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_passkey_unbind_dialog_title)) },
        text = { Text(stringResource(R.string.edit_passkey_unbind_dialog_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.edit_passkey_unbind_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}

/** 丢弃未保存更改确认弹窗：两条退出路径都必须显式表态（点「继续编辑」不丢改动）。 */
@Composable
internal fun EntryEditDiscardDialog(onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(stringResource(R.string.edit_discard_title)) },
        text = { Text(stringResource(R.string.edit_discard_desc)) },
        confirmButton = {
            TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.btn_discard))
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing) {
                Text(stringResource(R.string.btn_continue_edit))
            }
        }
    )
}

/** ISSUE-P2-354 AC①：保存按钮内嵌进度圈直径（dp） */
private const val SAVE_INNER_PROGRESS_SIZE = 18
