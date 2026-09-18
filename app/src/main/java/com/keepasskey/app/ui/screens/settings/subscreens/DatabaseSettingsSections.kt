package com.keepasskey.app.ui.screens.settings.subscreens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.keepasskey.app.data.importer.ImportSource
import com.keepasskey.app.ui.screens.importer.ImportReportDialog
import com.keepasskey.app.ui.screens.importer.ImportUiState
import com.keepasskey.app.ui.screens.settings.ChildDatabaseUiState

/**
 * 密码库设置页的**子库挂载段**（ISSUE-P3-188 剩余清单第 1 项 · 第二段：自 `DatabaseSettingsScreen` 下沉）。
 *
 * 承载原「对话框 5 / 5b」及其配套状态：子库来源与密钥文件的 SAF 选择结果、待补录凭据的挂载身份，
 * 以及三个 `OpenDocument` 选择器。**状态所有权变化**（如实登记）：这四项此前挂在整页上，
 * 现由本段自持——本段在父级**无条件组合**（不受 `showDialog` 门控），故其存活期与下沉前一致，
 * 「对话框开着时 SAF 回来不丢表单输入」的原语义保持（选择器置于段落而非对话框内，理由同原注释）。
 *
 * @param showDialog 由父级持有「是否打开子库对话框」（入口按钮在父级的扩展卡上）
 * @param onDialogDismiss 关闭对话框时回写父级状态
 */
@Composable
internal fun ChildDatabaseSection(
    state: ChildDatabaseUiState,
    showDialog: Boolean,
    onDialogDismiss: () -> Unit,
    onMount: (
        alias: String,
        sourceUri: String,
        passwordChars: CharArray,
        keyFileUri: String?
    ) -> Unit,
    onUnlock: (
        mountId: String,
        passwordChars: CharArray,
        keyFileUri: String?
    ) -> Unit,
    onUnmount: (mountId: String) -> Unit,
    onFeedbackDismiss: () -> Unit
) {
    // ISSUE-P3-20：子库 SAF 选择结果（非敏感元数据）+ 待解锁的挂载身份
    var childDbSourceUri by remember { mutableStateOf<String?>(null) }
    var childDbMountKeyFileUri by remember { mutableStateOf<String?>(null) }
    var childDbUnlockKeyFileUri by remember { mutableStateOf<String?>(null) }
    var childDbUnlockTargetId by remember { mutableStateOf<String?>(null) }

    // ISSUE-P3-20：子库来源与（可选）密钥文件的 SAF 选择器。
    // 选择器置于本段而非对话框内：对话框在 SAF 交互期间保持组合，表单输入（别名/主密码）因此不丢失。
    val childDbSourceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { childDbSourceUri = it.toString() } }
    val childDbMountKeyFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { childDbMountKeyFileUri = it.toString() } }
    val childDbUnlockKeyFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { childDbUnlockKeyFileUri = it.toString() } }

    // 对话框 5：子数据库挂载（ISSUE-P3-20：真实挂载/解锁/卸载；原「尚未实现」假提示已移除）
    if (showDialog) {
        ChildDatabaseDialog(
            state = state,
            selectedSourceUri = childDbSourceUri,
            selectedKeyFileUri = childDbMountKeyFileUri,
            onPickSource = { childDbSourceLauncher.launch(arrayOf(WILDCARD_MIME)) },
            onPickKeyFile = { childDbMountKeyFileLauncher.launch(arrayOf(WILDCARD_MIME)) },
            onMount = onMount,
            onUnlockRequest = { mountId -> childDbUnlockTargetId = mountId },
            onUnmount = onUnmount,
            onDismiss = {
                childDbSourceUri = null
                childDbMountKeyFileUri = null
                onDialogDismiss()
                onFeedbackDismiss()
            }
        )
    }

    // 对话框 5b：子库凭据补录（凭据被清零后重新解锁；不卸载即重开）
    val unlockTargetId = childDbUnlockTargetId
    if (unlockTargetId != null) {
        ChildDatabaseCredentialDialog(
            alias = state.mounts
                .firstOrNull { it.mountId == unlockTargetId }
                ?.alias
                .orEmpty(),
            selectedKeyFileUri = childDbUnlockKeyFileUri,
            onPickKeyFile = { childDbUnlockKeyFileLauncher.launch(arrayOf(WILDCARD_MIME)) },
            onConfirm = { passwordChars, keyFileUri ->
                onUnlock(unlockTargetId, passwordChars, keyFileUri)
                childDbUnlockTargetId = null
                childDbUnlockKeyFileUri = null
            },
            onDismiss = {
                childDbUnlockTargetId = null
                childDbUnlockKeyFileUri = null
            }
        )
    }
}

/**
 * 密码库设置页的**导入段**（同批下沉：原「对话框 7 + 导入报告」）。
 *
 * 语义保持点：**选源与选文件是两步**——`pendingImportSource` 必须在 SAF 往返期间存活，
 * 回调时把二者一并交给控制器（URI 过滤交给解析器的扩展名闸门，本段不做二次过滤）。
 */
@Composable
internal fun VaultImportSection(
    state: ImportUiState,
    showDialog: Boolean,
    onDialogDismiss: () -> Unit,
    onFileSelected: (ImportSource, Uri) -> Unit,
    onReportDismiss: () -> Unit
) {
    var pendingImportSource by remember { mutableStateOf<ImportSource?>(null) }
    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val source = pendingImportSource
        pendingImportSource = null
        if (uri != null && source != null) onFileSelected(source, uri)
    }

    if (showDialog) {
        ImportSourceDialog(
            onSourceSelected = { source ->
                pendingImportSource = source
                importFileLauncher.launch(arrayOf(WILDCARD_MIME))
            },
            onDismiss = { onDialogDismiss() }
        )
    }

    // 导入报告对话框：状态全来自控制器 StateFlow（Idle 时不渲染）
    ImportReportDialog(state = state, onDismiss = onReportDismiss)
}

/** SAF 通配 MIME 过滤器（原三处内联展开，收敛为一个文件级常量） */
private const val WILDCARD_MIME = "*/*"
