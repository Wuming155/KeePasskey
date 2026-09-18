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
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.screens.importer.ImportUiState
import com.keepasskey.app.ui.screens.settings.ChildDatabaseUiState
import com.keepasskey.app.ui.screens.settings.ExportArtifactKind
import com.keepasskey.app.ui.screens.settings.ExportTicket
import com.keepasskey.app.ui.screens.settings.KdfBenchmarkUiState
import com.keepasskey.app.ui.screens.settings.SettingsUiState

/**
 * 密码库属性与加密参数配置二级页面 (整合 KeePass2Android 与 KeePassDX 全部属性)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatabaseSettingsScreen(
    uiState: SettingsUiState,
    onBackClick: () -> Unit,
    onRecycleBinToggle: (Boolean) -> Unit,
    onEncryptionAlgorithmChange: (String) -> Unit = {},
    onKdfAlgorithmChange: (String) -> Unit = {},
    onArgon2ParametersChange: (iterations: Long, memoryMb: Long, parallelism: Int) -> Unit = { _, _, _ -> },
    // M6 整改：真实 KDF 基准状态与触发（原按钮仅展示假完成消息）
    kdfBenchmarkState: KdfBenchmarkUiState? = null,
    onRunKdfBenchmark: () -> Unit = {},
    // TASK-13 整改：导出/模板动作经 ViewModel 真实序列化与 SAF 落盘
    exportFeedback: UiMessage? = null,
    onClearExportFeedback: () -> Unit = {},
    onExportKdbx: (android.net.Uri) -> Unit = {},
    // ISSUE-P3-110：明文导出必须携带确认令牌（由本屏二次确认弹窗经 ExportConfirmationPolicy 签发）
    onExportXml: (android.net.Uri, ExportTicket) -> Unit = { _, _ -> },
    // ISSUE-P3-73：通用明文 CSV 导出（同明文 XML 语义，需二次确认）
    onExportCsv: (android.net.Uri, ExportTicket) -> Unit = { _, _ -> },
    onExportKeyFile: (android.net.Uri, ExportTicket) -> Unit = { _, _ -> },
    onInstallTemplates: () -> Unit = {},
    // ISSUE-P3-19：导入链路（对话框选源 → SAF 选文件 → 控制器解析/落库 → 报告对话框）。
    // 状态由 VaultImportController 的 StateFlow 上抬，本屏只透传与呈现，不含业务逻辑。
    importState: ImportUiState = ImportUiState.Idle,
    onImportFileSelected: (ImportSource, Uri) -> Unit = { _, _ -> },
    onImportReportDismiss: () -> Unit = {},
    // ISSUE-P3-20：子库挂载（核心层 ChildDatabaseSessionManager 已落地，本屏为真实入口）。
    // 状态与动作全部上抬自 SettingsViewModel；本屏只维护 SAF 选择结果与表单开关，
    // 不含任何解密/挂载业务逻辑。
    childDatabaseState: ChildDatabaseUiState = ChildDatabaseUiState(),
    onMountChildDatabase: (
        alias: String,
        sourceUri: String,
        passwordChars: CharArray,
        keyFileUri: String?
    ) -> Unit = { _, _, _, _ -> },
    onUnlockChildDatabase: (
        mountId: String,
        passwordChars: CharArray,
        keyFileUri: String?
    ) -> Unit = { _, _, _ -> },
    onUnmountChildDatabase: (mountId: String) -> Unit = {},
    onChildDatabaseFeedbackDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showCipherDialog by remember { mutableStateOf(false) }
    var showKdfDialog by remember { mutableStateOf(false) }
    var showArgon2Dialog by remember { mutableStateOf(false) }
    var showTemplatesDialog by remember { mutableStateOf(false) }
    var showChildDbDialog by remember { mutableStateOf(false) }
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
        titleRes = R.string.settings_database,
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
            // 1. 常规与基础属性
            item { SectionHeader(title = stringResource(R.string.dbset_section_basic)) }
            item {
                DatabaseBasicCard(
                    uiState = uiState,
                    onRecycleBinToggle = onRecycleBinToggle
                )
            }

            // 2. 密码学与 KDF 派生
            item { SectionHeader(title = stringResource(R.string.dbset_section_crypto)) }
            item {
                DatabaseCryptoCard(
                    uiState = uiState,
                    onCipherClick = { showCipherDialog = true },
                    onKdfClick = { showKdfDialog = true },
                    onArgonClick = { showArgon2Dialog = true }
                )
            }

            // 3. 条目模板库与子数据库配置 (KP2A 特性)
            item { SectionHeader(title = stringResource(R.string.dbset_section_extensions)) }
            item {
                DatabaseExtensionsCard(
                    childDatabasesCount = uiState.childDatabasesCount,
                    onTemplatesClick = { showTemplatesDialog = true },
                    onChildDbClick = { showChildDbDialog = true }
                )
            }

            // 4. 数据导入与导出 (KP2A 特性)
            item { SectionHeader(title = stringResource(R.string.dbset_section_import_export)) }
            item {
                DatabaseImportExportCard(
                    onImportClick = { showImportDialog = true },
                    onExportClick = { showExportDialog = true },
                    onKeyFileExportClick = {
                        // TASK-13 整改：呼起 SAF 另存为，会话绑定密钥文件经仓库真实导出
                        exportKeyFileLauncher.launch("keepasskey.keyx")
                    }
                )
            }

            // 5. 完整性与高级规则 (KP2A 特性)
            item { SectionHeader(title = stringResource(R.string.dbset_section_integrity)) }
            item {
                DatabaseIntegrityCard()
            }

            // TASK-13 整改：导出/模板动作结果反馈（点击清除），真实动作的结果如实上浮
            exportFeedback?.let { feedback ->
                item {
                    DatabaseFeedbackItem(
                        message = feedback,
                        isError = feedback.resolveText().contains("失败"),
                        onClick = onClearExportFeedback
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // 对话框 1：加密算法选择
    if (showCipherDialog) {
        CipherAlgorithmDialog(
            currentAlgorithm = uiState.encryptionAlgorithm,
            onSelect = onEncryptionAlgorithmChange,
            onDismiss = { showCipherDialog = false }
        )
    }

    // 对话框 2：KDF 密钥派生算法选择
    if (showKdfDialog) {
        KdfAlgorithmDialog(
            currentAlgorithm = uiState.kdfAlgorithm,
            onSelect = onKdfAlgorithmChange,
            onDismiss = { showKdfDialog = false }
        )
    }

    // 对话框 3：Argon2 参数详细调节
    if (showArgon2Dialog) {
        Argon2ParametersDialog(
            uiState = uiState,
            kdfBenchmarkState = kdfBenchmarkState,
            onRunKdfBenchmark = onRunKdfBenchmark,
            onApplyParameters = onArgon2ParametersChange,
            onDismiss = { showArgon2Dialog = false }
        )
    }

    // 对话框 4：模板库管理对话框
    if (showTemplatesDialog) {
        DatabaseTemplatesDialog(
            onInstallTemplates = onInstallTemplates,
            onDismiss = { showTemplatesDialog = false }
        )
    }

    // 对话框 5 / 5b：子库挂载与凭据补录（§159 下沉至 ChildDatabaseSection，四项状态由该段自持）
    ChildDatabaseSection(
        state = childDatabaseState,
        showDialog = showChildDbDialog,
        onDialogDismiss = { showChildDbDialog = false },
        onMount = onMountChildDatabase,
        onUnlock = onUnlockChildDatabase,
        onUnmount = onUnmountChildDatabase,
        onFeedbackDismiss = onChildDatabaseFeedbackDismiss
    )


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
        showDialog = showImportDialog,
        onDialogDismiss = { showImportDialog = false },
        onFileSelected = onImportFileSelected,
        onReportDismiss = onImportReportDismiss
    )
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
// 说明：为遵守「不新增 import 语句」约束，@Preview 采用全限定名写法
@androidx.compose.ui.tooling.preview.Preview(name = "密码库属性设置页 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "密码库属性设置页 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun DatabaseSettingsScreenPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        DatabaseSettingsScreen(
            uiState = com.keepasskey.app.ui.screens.settings.SettingsUiState().copy(
                databaseName = "预览示例密码库",
                databasePath = "/预览目录/预览示例.kdbx",
                databaseDefaultUsername = "demo@example.com"
            ),
            onBackClick = {},
            onRecycleBinToggle = {}
        )
    }
}
