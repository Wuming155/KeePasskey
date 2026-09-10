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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.screens.settings.ExportConfirmationPolicy
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
    onTanExpiresOnUseToggle: (Boolean) -> Unit = {},
    onCheckForDuplicateUuidsToggle: (Boolean) -> Unit = {},
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
    modifier: Modifier = Modifier
) {
    var showCipherDialog by remember { mutableStateOf(false) }
    var showKdfDialog by remember { mutableStateOf(false) }
    var showArgon2Dialog by remember { mutableStateOf(false) }
    var showTemplatesDialog by remember { mutableStateOf(false) }
    var showChildDbDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var operationFeedback by remember { mutableStateOf<UiMessage?>(null) }
    // ISSUE-P2-10 (ZT-15)：明文 XML 导出的待确认目标（SAF 选定后、写盘前强制二次确认）
    var pendingPlaintextXmlUri by remember { mutableStateOf<Uri?>(null) }
    var showPlaintextXmlConfirm by remember { mutableStateOf(false) }

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
                DatabaseIntegrityCard(
                    tanExpiresOnUse = uiState.tanExpiresOnUse,
                    onTanExpiresOnUseToggle = onTanExpiresOnUseToggle,
                    checkForDuplicateUuids = uiState.checkForDuplicateUuids,
                    onCheckForDuplicateUuidsToggle = onCheckForDuplicateUuidsToggle
                )
            }

            operationFeedback?.let { feedback ->
                item {
                    DatabaseFeedbackItem(message = feedback, isError = false)
                }
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

    // 对话框 5：子数据库挂载
    if (showChildDbDialog) {
        ChildDatabaseDialog(
            onSelectFile = {
                // TASK-13 整改：如实告知未实现——子库挂载需独立功能开发，
                // 不再谎报「挂载成功」（登记 STATUS TASK-43 预留功能清单）
                operationFeedback = UiMessage(R.string.dbset_child_db_not_supported)
            },
            onDismiss = { showChildDbDialog = false }
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
        AlertDialog(
            onDismissRequest = {
                showPlaintextXmlConfirm = false
                pendingPlaintextXmlUri = null
            },
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
                TextButton(onClick = {
                    showPlaintextXmlConfirm = false
                    pendingPlaintextXmlUri = null
                }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    // 对话框 7：导入数据源
    if (showImportDialog) {
        ImportSourceDialog(
            onSourceSelected = { source ->
                operationFeedback = UiMessage(R.string.dbset_import_preparing, listOf(source))
            },
            onDismiss = { showImportDialog = false }
        )
    }
}
