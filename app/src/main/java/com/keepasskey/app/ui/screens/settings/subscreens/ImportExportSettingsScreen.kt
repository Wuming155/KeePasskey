package com.keepasskey.app.ui.screens.settings.subscreens

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.data.importer.ImportSource
import com.keepasskey.app.data.repository.ChangeKeyFileIntent
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.screens.importer.ImportUiState
import com.keepasskey.app.ui.screens.unlock.KeyFileReadResult
import com.keepasskey.app.ui.screens.settings.ExportArtifactKind
import com.keepasskey.app.ui.screens.settings.ExportTicket
import com.keepasskey.app.ui.screens.settings.SettingsUiState

/**
 * 数据导入与导出二级设置页 (ISSUE-P3-467 自 DatabaseSettingsScreen **纯迁位**拆出——
 * 导入/导出是一次性动作流，与加密参数等一次性配置分属「动作 vs 配置」两域)。
 *
 * SAF launcher、导出二次确认、导入/并入链路全部原样迁入；
 * 回调签名与状态通道零变更，状态仍走共享 `SettingsViewModel`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportExportSettingsScreen(
    uiState: SettingsUiState,
    onBackClick: () -> Unit,
    // TASK-13 整改：导出动作经 ViewModel 真实序列化与 SAF 落盘（原样迁自数据库页）
    exportFeedback: UiMessage? = null,
    onClearExportFeedback: () -> Unit = {},
    // ISSUE-P3-437 AC②：导出长操作进行中（序列化 + SAF 写盘期间呈现过程反馈段）
    isExportInProgress: Boolean = false,
    onExportKdbx: (android.net.Uri) -> Unit = {},
    // ISSUE-P3-110：明文导出必须携带确认令牌（由本屏二次确认弹窗经 ExportConfirmationPolicy 签发）
    onExportXml: (android.net.Uri, ExportTicket) -> Unit = { _, _ -> },
    // ISSUE-P3-73：通用明文 CSV 导出（同明文 XML 语义，需二次确认）
    onExportCsv: (android.net.Uri, ExportTicket) -> Unit = { _, _ -> },
    onExportKeyFile: (android.net.Uri, ExportTicket) -> Unit = { _, _ -> },
    // ISSUE-P3-436：导入密钥文件（SAF 读取走 KeyFileAccess 通道，提交复用仅改绑分支 changeKeyFileOnly）
    onReadKeyFile: suspend (String) -> KeyFileReadResult = { _ -> KeyFileReadResult.Unreadable },
    onKeyFileImport: (ChangeKeyFileIntent) -> Unit = {},
    keyFileImportBusy: Boolean = false,
    // §411（P3-448）：先收编记忆文件，false = 无记忆回落 SAF 手选
    onImportRememberedKeyFile: suspend () -> Boolean = { false },
    keyFileImportFeedback: UiMessage? = null,
    onClearKeyFileImportFeedback: () -> Unit = {},
    // §411 走查续（P3-448）：私有目录副本常驻状态（null = 无副本，卡片不渲染该段）
    keyFileCopyDisplayName: String? = null,
    // ISSUE-P3-19：导入链路（对话框选源 → SAF 选文件 → 控制器解析/落库 → 报告对话框；状态上抬，本屏只透传）。
    // ISSUE-P3-384：`.kdbx` 并入状态（AwaitingMergeCredentials 等）
    importState: ImportUiState = ImportUiState.Idle,
    mergeState: ImportUiState = ImportUiState.Idle,
    onImportFileSelected: (ImportSource, Uri) -> Unit = { _, _ -> },
    onImportReportDismiss: () -> Unit = {},
    // ISSUE-P2-354 AC④：取消进行中的导入（协程 cancellation）
    onImportCancel: () -> Unit = {},
    // ISSUE-P3-384：提交第二库密码 + 可选密钥文件 Uri
    onMergeSubmit: (passwordChars: CharArray, keyFileUri: Uri?) -> Unit = { _, _ -> },
    onCancelMerge: () -> Unit = {},
    onMergeReportDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    // ISSUE-P2-10 (ZT-15)：明文 XML 导出的待确认目标（SAF 选定后、写盘前强制二次确认）
    var pendingPlaintextXmlUri by remember { mutableStateOf<Uri?>(null) }
    var showPlaintextXmlConfirm by remember { mutableStateOf(false) }
    // ISSUE-P3-73：明文 CSV 导出的待确认目标（同明文 XML 的二次确认语义）
    var pendingPlaintextCsvUri by remember { mutableStateOf<Uri?>(null) }
    var showPlaintextCsvConfirm by remember { mutableStateOf(false) }
    // ISSUE-P3-128：密钥文件导出的待确认目标（同属明文风险等级）
    var pendingKeyFileUri by remember { mutableStateOf<Uri?>(null) }
    var showKeyFileExportConfirm by remember { mutableStateOf(false) }

    // TASK-13 整改：SAF CreateDocument 真实另存为（此前导出/密钥文件仅弹假成功提示）
    val exportKdbxLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let(onExportKdbx) }
    // ISSUE-P2-10 (ZT-15)：明文 XML 不直接导出——SAF 选定目标后先弹二次确认
    val exportXmlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/xml")
    ) { uri ->
        if (uri != null) {
            pendingPlaintextXmlUri = uri
            showPlaintextXmlConfirm = true
        }
    }
    // ISSUE-P3-73：明文 CSV 同样不直接导出——SAF 选定目标后先弹二次确认
    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            pendingPlaintextCsvUri = uri
            showPlaintextCsvConfirm = true
        }
    }
    // ISSUE-P3-128：密钥文件导出同属明文风险等级——SAF 选定目标后**先弹二次确认**，
    // 确认时经 ExportConfirmationPolicy 签发令牌，未确认分支清理空目标文档且不导出
    val exportKeyFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            pendingKeyFileUri = uri
            showKeyFileExportConfirm = true
        }
    }

    // 对话框 6d：密钥文件导出二次确认（ISSUE-P3-128，同属 PLAINTEXT 风险等级；实现见 ExportConfirmationDialog）
    if (showKeyFileExportConfirm) {
        ExportConfirmationDialog(
            titleResId = R.string.dbset_keyfile_export_warn_title,
            messageResId = R.string.dbset_keyfile_export_warn_message,
            artifactKind = ExportArtifactKind.KEY_FILE,
            targetUri = pendingKeyFileUri,
            onCancel = { showKeyFileExportConfirm = false },
            onTargetConsumed = { pendingKeyFileUri = null },
            onConfirmed = onExportKeyFile
        )
    }

    SettingsSubscreenScaffold(
        titleRes = R.string.settings_import_export,
        onBackClick = onBackClick,
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 数据导入与导出（ISSUE-P3-467：整块自数据库页原样迁入）
            item { SectionHeader(title = stringResource(R.string.dbset_section_import_export)) }
            item {
                DatabaseImportExportCard(
                    onImportClick = { showImportDialog = true },
                    onExportClick = { showExportDialog = true },
                    onKeyFileExportClick = {
                        // TASK-13 整改：呼起 SAF 另存为，会话绑定密钥文件经仓库真实导出
                        exportKeyFileLauncher.launch("keepasskey.keyx")
                    },
                    // ISSUE-P3-436：导入密钥文件（选取 / 确认 / 反馈在卡内闭环）
                    onReadKeyFile = onReadKeyFile,
                    onKeyFileImport = onKeyFileImport,
                    keyFileImportBusy = keyFileImportBusy,
                    onImportRememberedKeyFile = onImportRememberedKeyFile,
                    keyFileImportFeedback = keyFileImportFeedback,
                    onClearKeyFileImportFeedback = onClearKeyFileImportFeedback,
                    // §411 走查续（P3-448）：副本常驻状态段（有副本时在导入行下方呈现）
                    keyFileCopyDisplayName = keyFileCopyDisplayName
                )
            }

            // TASK-13 整改：导出动作结果反馈（点击清除），真实动作的结果如实上浮。
            // ISSUE-P2-353 AC④：错误样式改读类型化 isError 字段——按文案 contains("失败")
            // 判定在英文语言下会把失败条目渲染成成功样式。
            // ISSUE-P3-437 AC②：导出进行中先挂过程反馈段（与解锁/云同步共用同一通道）。
            if (isExportInProgress) {
                item {
                    com.keepasskey.app.ui.components.OperationProgressSection(
                        label = stringResource(R.string.dbset_export_running)
                    )
                }
            }
            exportFeedback?.let { feedback ->
                item {
                    DatabaseFeedbackItem(
                        message = feedback,
                        isError = feedback.isError,
                        onClick = onClearExportFeedback
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showExportDialog) {
        ExportDatabaseDialog(
            databaseName = uiState.databaseName,
            onExportKdbx = { exportKdbxLauncher.launch(it) },
            onExportXml = { exportXmlLauncher.launch(it) },
            onExportCsv = { exportCsvLauncher.launch(it) },
            onDismiss = { showExportDialog = false }
        )
    }

    // 对话框 6b：明文 XML 导出二次确认（ISSUE-P2-10 / ZT-15；实现见 ExportConfirmationDialog）
    if (showPlaintextXmlConfirm) {
        ExportConfirmationDialog(
            titleResId = R.string.dbset_export_plain_warn_title,
            messageResId = R.string.dbset_export_plain_warn_message,
            artifactKind = ExportArtifactKind.PLAINTEXT_XML,
            targetUri = pendingPlaintextXmlUri,
            onCancel = { showPlaintextXmlConfirm = false },
            onTargetConsumed = { pendingPlaintextXmlUri = null },
            onConfirmed = onExportXml
        )
    }

    // 对话框 6c：明文 CSV 导出二次确认（ISSUE-P3-73，语义同明文 XML；实现见 ExportConfirmationDialog）
    if (showPlaintextCsvConfirm) {
        ExportConfirmationDialog(
            titleResId = R.string.dbset_export_csv_plain_warn_title,
            messageResId = R.string.dbset_export_csv_plain_warn_message,
            artifactKind = ExportArtifactKind.PLAINTEXT_CSV,
            targetUri = pendingPlaintextCsvUri,
            onCancel = { showPlaintextCsvConfirm = false },
            onTargetConsumed = { pendingPlaintextCsvUri = null },
            onConfirmed = onExportCsv
        )
    }

    // 对话框 7 + 导入报告（§159 下沉至 VaultImportSection；两步式选源-选文件的中间态由该段自持）
    VaultImportSection(
        state = importState,
        mergeState = mergeState,
        showDialog = showImportDialog,
        onDialogDismiss = { showImportDialog = false },
        onFileSelected = onImportFileSelected,
        onReportDismiss = onImportReportDismiss,
        onCancelImport = onImportCancel,
        onMergeSubmit = onMergeSubmit,
        onCancelMerge = onCancelMerge,
        onMergeReportDismiss = onMergeReportDismiss
    )
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
// 说明：为遵守「不新增 import 语句」约束，@Preview 采用全限定名写法
@androidx.compose.ui.tooling.preview.Preview(name = "数据导入与导出页 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "数据导入与导出页 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun ImportExportSettingsScreenPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        ImportExportSettingsScreen(
            uiState = com.keepasskey.app.ui.screens.settings.SettingsUiState().copy(
                databaseName = "预览示例密码库"
            ),
            onBackClick = {},
            // §411 走查续（P3-448）：预览「已存入应用私有目录」态（遮蔽 + 眼睛按钮）——
            // 该段仅在确有副本时渲染，默认参数（null）的预览看不到它
            keyFileCopyDisplayName = "预览密钥.keyx"
        )
    }
}
