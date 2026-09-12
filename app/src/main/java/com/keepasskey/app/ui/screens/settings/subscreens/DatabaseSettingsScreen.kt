package com.keepasskey.app.ui.screens.settings.subscreens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.data.importer.ImportSource
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.screens.importer.ImportReportDialog
import com.keepasskey.app.ui.screens.importer.ImportUiState
import com.keepasskey.app.ui.screens.settings.ChildDatabaseUiState
import com.keepasskey.app.ui.screens.settings.ExportConfirmationPolicy
import com.keepasskey.app.ui.screens.settings.KdfBenchmarkUiState
import com.keepasskey.app.ui.screens.settings.SafDocumentCleanup
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
    onExportXml: (android.net.Uri) -> Unit = {},
    onExportKeyFile: (android.net.Uri) -> Unit = {},
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
    // ISSUE-P3-20：子库 SAF 选择结果（非敏感元数据）+ 待解锁的挂载身份
    var childDbSourceUri by remember { mutableStateOf<String?>(null) }
    var childDbMountKeyFileUri by remember { mutableStateOf<String?>(null) }
    var childDbUnlockKeyFileUri by remember { mutableStateOf<String?>(null) }
    var childDbUnlockTargetId by remember { mutableStateOf<String?>(null) }

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
    val exportKeyFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let(onExportKeyFile) }

    // ISSUE-P3-20：子库来源与（可选）密钥文件的 SAF 选择器。
    // 选择器置于本屏而非对话框内：对话框在 SAF 交互期间保持组合，表单输入（别名/主密码）因此不丢失。
    val childDbSourceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { childDbSourceUri = it.toString() } }
    val childDbMountKeyFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { childDbMountKeyFileUri = it.toString() } }
    val childDbUnlockKeyFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { childDbUnlockKeyFileUri = it.toString() } }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_database),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
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

    // 对话框 5：子数据库挂载（ISSUE-P3-20：真实挂载/解锁/卸载；原「尚未实现」假提示已移除）
    if (showChildDbDialog) {
        ChildDatabaseDialog(
            state = childDatabaseState,
            selectedSourceUri = childDbSourceUri,
            selectedKeyFileUri = childDbMountKeyFileUri,
            onPickSource = { childDbSourceLauncher.launch(arrayOf("*/*")) },
            onPickKeyFile = { childDbMountKeyFileLauncher.launch(arrayOf("*/*")) },
            onMount = onMountChildDatabase,
            onUnlockRequest = { mountId -> childDbUnlockTargetId = mountId },
            onUnmount = onUnmountChildDatabase,
            onDismiss = {
                showChildDbDialog = false
                childDbSourceUri = null
                childDbMountKeyFileUri = null
                onChildDatabaseFeedbackDismiss()
            }
        )
    }

    // 对话框 5b：子库凭据补录（凭据被清零后重新解锁；不卸载即重开）
    val unlockTargetId = childDbUnlockTargetId
    if (unlockTargetId != null) {
        ChildDatabaseCredentialDialog(
            alias = childDatabaseState.mounts
                .firstOrNull { it.mountId == unlockTargetId }
                ?.alias
                .orEmpty(),
            selectedKeyFileUri = childDbUnlockKeyFileUri,
            onPickKeyFile = { childDbUnlockKeyFileLauncher.launch(arrayOf("*/*")) },
            onConfirm = { passwordChars, keyFileUri ->
                onUnlockChildDatabase(unlockTargetId, passwordChars, keyFileUri)
                childDbUnlockTargetId = null
                childDbUnlockKeyFileUri = null
            },
            onDismiss = {
                childDbUnlockTargetId = null
                childDbUnlockKeyFileUri = null
            }
        )
    }

    // 对话框 6：导出密码库
    if (showExportDialog) {
        ExportDatabaseDialog(
            databaseName = uiState.databaseName,
            onExportKdbx = { exportKdbxLauncher.launch(it) },
            onExportXml = { exportXmlLauncher.launch(it) },
            onDismiss = { showExportDialog = false }
        )
    }

    // 对话框 6b：明文 XML 导出二次确认（ISSUE-P2-10 / ZT-15）
    if (showPlaintextXmlConfirm) {
        // ISSUE-P2-20：取消分支清理 SAF 已创建的空目标文档，不留 0 字节残留
        val localContext = LocalContext.current
        fun cleanupCancelledXmlTarget() {
            pendingPlaintextXmlUri?.let {
                SafDocumentCleanup.deleteCreatedDocument(localContext, it)
            }
            showPlaintextXmlConfirm = false
            pendingPlaintextXmlUri = null
        }
        AlertDialog(
            onDismissRequest = { cleanupCancelledXmlTarget() },
            title = { Text(stringResource(R.string.dbset_export_plain_warn_title)) },
            text = {
                Text(
                    text = stringResource(R.string.dbset_export_plain_warn_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = pendingPlaintextXmlUri
                    showPlaintextXmlConfirm = false
                    pendingPlaintextXmlUri = null
                    // 决策走可单测的 ExportConfirmationPolicy：确认后才放行，
                    // 取消/未确认分支不调用 onExportXml（fail-closed）
                    val allowed = ExportConfirmationPolicy.allows(
                        risk = ExportConfirmationPolicy.Risk.PLAINTEXT,
                        confirmed = true
                    )
                    if (target != null && allowed) onExportXml(target)
                }) {
                    Text(stringResource(R.string.dbset_export_plain_warn_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { cleanupCancelledXmlTarget() }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    // 对话框 7：导入数据源 → SAF 打开文件 → 交控制器（解析 / 落库 / 出报告）
    var pendingImportSource by remember { mutableStateOf<ImportSource?>(null) }
    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        // 选源与选文件是两步：Uri 回调时把二者一并交给控制器（URI 过滤交给解析器的扩展名闸门）
        val source = pendingImportSource
        pendingImportSource = null
        if (uri != null && source != null) onImportFileSelected(source, uri)
    }
    if (showImportDialog) {
        ImportSourceDialog(
            onSourceSelected = { source ->
                pendingImportSource = source
                importFileLauncher.launch(arrayOf("*/*"))
            },
            onDismiss = { showImportDialog = false }
        )
    }

    // 导入报告对话框：状态全来自控制器 StateFlow（Idle 时不渲染）
    ImportReportDialog(state = importState, onDismiss = onImportReportDismiss)
}
