package com.keepasskey.app.ui.screens.database

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.keepasskey.app.ui.components.SecurePasswordField
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.R
import com.keepasskey.app.security.ApplyObscuredTouchFilter
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.model.VaultDatabaseInfo
import com.keepasskey.app.ui.theme.CapsuleShape
import com.keepasskey.app.ui.theme.LocalSecurityColors

@Composable
fun DatabasePickerScreen(
    onBackClick: () -> Unit,
    onDatabaseSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DatabasePickerViewModel = hiltViewModel()
) {
    // 遮挡触摸过滤（ISSUE-P2-09 / P3-12）
    ApplyObscuredTouchFilter()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // ISSUE-P3-21：生成型密钥文件的一次性交付状态（复合密钥第二因子，丢失即无法解锁）
    val keyFileDelivery by viewModel.keyFileDelivery.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is DatabasePickerEvent.DatabaseSelected -> {
                    onDatabaseSelected(event.id)
                }
            }
        }
    }

    uiState.userMessage?.let { message ->
        val text = message.resolveText()
        LaunchedEffect(message, text) {
            snackbarHostState.showSnackbar(text)
            viewModel.clearUserMessage()
        }
    }

    DatabasePickerContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        keyFileDelivery = keyFileDelivery,
        onBackClick = onBackClick,
        onSelectDatabase = viewModel::selectDatabase,
        onOpenCreateDialog = viewModel::openCreateDialog,
        onCloseCreateDialog = viewModel::closeCreateDialog,
        onCreateDatabase = viewModel::createDatabase,
        onSaveKeyFile = viewModel::saveGeneratedKeyFileTo,
        onKeyFileDeliveryDismissed = viewModel::dismissKeyFileDelivery,
        onOpenExistingClick = viewModel::openOpenSourceDialog,
        onCloseOpenSourceDialog = viewModel::closeOpenSourceDialog,
        onImportFromSource = viewModel::importDatabaseFromSource,
        onRemoveDatabase = viewModel::removeDatabase,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatabasePickerContent(
    uiState: DatabasePickerUiState,
    snackbarHostState: SnackbarHostState,
    onBackClick: () -> Unit,
    onSelectDatabase: (String) -> Unit,
    onOpenCreateDialog: () -> Unit,
    onCloseCreateDialog: () -> Unit,
    onCreateDatabase: (
        name: String,
        pwd: CharArray,
        keyFile: Boolean,
        preset: String,
        keyFileSourceUri: String?
    ) -> Unit,
    onOpenExistingClick: () -> Unit,
    onCloseOpenSourceDialog: () -> Unit,
    onImportFromSource: (source: OpenVaultSourceType, name: String, path: String) -> Unit,
    onRemoveDatabase: (String) -> Unit,
    // ISSUE-P3-21：生成型密钥文件的一次性交付（默认值便于预览与既有调用点复用）
    keyFileDelivery: KeyFileDeliveryState = KeyFileDeliveryState.None,
    onSaveKeyFile: (Uri) -> Unit = {},
    onKeyFileDeliveryDismissed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var dbToRemove by remember { mutableStateOf<VaultDatabaseInfo?>(null) }

    // ISSUE-P3-21：SAF 另存为生成型密钥文件（仅传 Uri 上行，写盘由 ViewModel 复用既有导出通道完成）
    val keyFileSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let(onSaveKeyFile) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.db_picker_title),
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = stringResource(R.string.db_picker_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            // 快速新建与打开已有操作栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onOpenCreateDialog,
                    shape = CapsuleShape,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.db_picker_create_new))
                }

                OutlinedButton(
                    onClick = onOpenExistingClick,
                    shape = CapsuleShape,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.db_picker_open_external))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 数据库卡片列表
            if (uiState.databases.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = stringResource(R.string.picker_empty_databases),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.databases, key = { it.id }) { db ->
                        VaultDatabaseCard(
                            database = db,
                            onSelect = { onSelectDatabase(db.id) },
                            onDelete = { dbToRemove = db }
                        )
                    }
                }
            }
        }
    }

    // 新建密码库向导对话框 (支持生成或选择已有密钥文件)
    if (uiState.showCreateDialog) {
        CreateVaultWizardDialog(
            onDismiss = onCloseCreateDialog,
            onConfirm = onCreateDatabase
        )
    }

    // ISSUE-P3-21：生成型密钥文件的一次性保存提示——复合密钥第二因子必须当场交付
    (keyFileDelivery as? KeyFileDeliveryState.PendingSave)?.let { pending ->
        KeyFileOneTimeSaveDialog(
            suggestedFileName = pending.suggestedFileName,
            onSaveClick = { keyFileSaveLauncher.launch(pending.suggestedFileName) },
            onSkipClick = onKeyFileDeliveryDismissed
        )
    }

    // 打开已有 KDBX 文件对话框 (支持本地/WebDAV/S3 完整配置项填写)
    if (uiState.showOpenSourceDialog) {
        OpenExistingVaultDialog(
            onDismiss = onCloseOpenSourceDialog,
            onConfirm = onImportFromSource
        )
    }

    // 移除密码库确认对话框
    dbToRemove?.let { db ->
        AlertDialog(
            onDismissRequest = { dbToRemove = null },
            title = { Text(stringResource(R.string.db_picker_delete_confirm_title)) },
            text = { Text(stringResource(R.string.db_picker_delete_confirm_desc)) },
            confirmButton = {
                Button(
                    onClick = {
                        onRemoveDatabase(db.id)
                        dbToRemove = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.btn_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { dbToRemove = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
            shape = RoundedCornerShape(18.dp)
        )
    }
}

@Composable
private fun VaultDatabaseCard(
    database: VaultDatabaseInfo,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val securityColors = LocalSecurityColors.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = if (database.isActive) 2.dp else 1.dp,
                color = if (database.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable { onSelect() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (database.isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (database.isRemote) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (database.isRemote) Icons.Default.CloudDone else Icons.Default.Storage,
                            contentDescription = null,
                            tint = if (database.isRemote) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = database.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${database.syncType} • ${database.fileSizeFormatted}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (database.isActive) {
                        Box(
                            modifier = Modifier
                                .clip(CapsuleShape)
                                .background(securityColors.success.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.db_picker_current_active),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = securityColors.success
                            )
                        }
                    } else {
                        IconButton(onClick = onDelete) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = stringResource(R.string.btn_delete),
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = database.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = database.lastOpenedAt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

private fun queryDocumentDisplayName(context: android.content.Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()
}

/**
 * 打开已有 KDBX 文件对话框 (支持本地/WebDAV/S3 自动展示并填写连接配置)
 */
@Composable
private fun OpenExistingVaultDialog(
    onDismiss: () -> Unit,
    onConfirm: (source: OpenVaultSourceType, name: String, path: String) -> Unit
) {
    val context = LocalContext.current
    var selectedSource by remember { mutableStateOf(OpenVaultSourceType.LOCAL) }

    // 本地字段（彻底去除硬编码假路径，通过 SAF 选择器获取真实 URI 与文件名）
    var localPath by remember { mutableStateOf("") }
    var localName by remember { mutableStateOf("") }

    val kdbxPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val displayName = queryDocumentDisplayName(context, uri)
            localName = displayName
            localPath = uri.toString()
        }
    }

    // WebDAV 字段（Wave 15 假桩清零：仅保留有消费者的展示名称与 URL，凭据统一在同步设置中配置）
    var webdavUrl by remember { mutableStateOf("") }
    var webdavName by remember { mutableStateOf("") }

    // S3 兼容字段（同上）
    var s3Endpoint by remember { mutableStateOf("") }
    var s3Bucket by remember { mutableStateOf("") }
    var s3Name by remember { mutableStateOf("") }

    val isConfirmEnabled = when (selectedSource) {
        OpenVaultSourceType.LOCAL -> localPath.isNotBlank() && localName.isNotBlank()
        OpenVaultSourceType.WEBDAV -> webdavName.isNotBlank() && webdavUrl.isNotBlank()
        OpenVaultSourceType.S3_COMPATIBLE -> s3Name.isNotBlank() && s3Endpoint.isNotBlank() && s3Bucket.isNotBlank()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.picker_open_vault_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.picker_open_vault_source_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 来源模式切换 Chip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OpenVaultSourceType.entries.forEach { source ->
                        FilterChip(
                            selected = selectedSource == source,
                            onClick = { selectedSource = source },
                            label = {
                                Text(
                                    text = when (source) {
                                        OpenVaultSourceType.LOCAL -> stringResource(R.string.picker_chip_local)
                                        OpenVaultSourceType.WEBDAV -> stringResource(R.string.picker_chip_webdav)
                                        OpenVaultSourceType.S3_COMPATIBLE -> stringResource(R.string.picker_chip_s3)
                                    },
                                    fontSize = 12.sp
                                )
                            },
                            shape = CapsuleShape
                        )
                    }
                }

                // 根据选中的源展示对应的配置表单
                when (selectedSource) {
                    OpenVaultSourceType.LOCAL -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.picker_local_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            OutlinedButton(
                                onClick = { kdbxPickerLauncher.launch(arrayOf("*/*")) },
                                shape = CapsuleShape,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.picker_browse_file))
                            }

                            if (localName.isNotBlank()) {
                                Text(
                                    text = stringResource(R.string.picker_file_selected, localName),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.picker_file_not_selected),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }

                            OutlinedTextField(
                                value = localName,
                                onValueChange = { localName = it },
                                label = { Text(stringResource(R.string.picker_vault_id_name)) },
                                placeholder = { Text("passwords.kdbx") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = localPath,
                                onValueChange = { localPath = it },
                                label = { Text(stringResource(R.string.picker_local_path_label)) },
                                placeholder = { Text("content://... 或 /path/to/vault.kdbx") },
                                leadingIcon = { Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    OpenVaultSourceType.WEBDAV -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.picker_webdav_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            OutlinedTextField(
                                value = webdavName,
                                onValueChange = { webdavName = it },
                                label = { Text(stringResource(R.string.picker_vault_display_name)) },
                                placeholder = { Text("cloud_vault.kdbx") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = webdavUrl,
                                onValueChange = { webdavUrl = it },
                                label = { Text(stringResource(R.string.picker_webdav_url_label)) },
                                placeholder = { Text("https://example.com/dav/passwords.kdbx") },
                                leadingIcon = { Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    OpenVaultSourceType.S3_COMPATIBLE -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.picker_s3_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            OutlinedTextField(
                                value = s3Name,
                                onValueChange = { s3Name = it },
                                label = { Text(stringResource(R.string.picker_vault_display_name)) },
                                placeholder = { Text("s3_vault.kdbx") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = s3Endpoint,
                                onValueChange = { s3Endpoint = it },
                                label = { Text(stringResource(R.string.picker_s3_endpoint_label)) },
                                placeholder = { Text("https://<account>.r2.cloudflarestorage.com") },
                                leadingIcon = { Icon(Icons.Default.CloudQueue, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = s3Bucket,
                                onValueChange = { s3Bucket = it },
                                label = { Text(stringResource(R.string.picker_s3_bucket_label)) },
                                placeholder = { Text("my-vault/keepass.kdbx") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (selectedSource) {
                        OpenVaultSourceType.LOCAL -> onConfirm(selectedSource, localName, localPath)
                        OpenVaultSourceType.WEBDAV -> onConfirm(selectedSource, webdavName, webdavUrl)
                        OpenVaultSourceType.S3_COMPATIBLE -> onConfirm(selectedSource, s3Name, "$s3Endpoint/$s3Bucket")
                    }
                },
                enabled = isConfirmEnabled,
                shape = CapsuleShape
            ) {
                Text(stringResource(R.string.picker_open_and_load))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

/**
 * 新建库向导中密钥文件来源的选择态（ISSUE-P3-21：替换原裸字符串 `"GENERATE"` / `"SELECT_EXISTING"`）。
 */
private enum class KeyFileSourceChoice {
    /** 生成全新密钥文件（由 database 模块唯一生成器产出 KeePass 2.x XML v2.0） */
    GENERATE,

    /** 使用用户从设备选取的既有密钥文件 */
    SELECT_EXISTING
}

/**
 * 生成型密钥文件的一次性保存提示（ISSUE-P3-21 验收 2）。
 *
 * 该密钥文件是复合密钥的第二因子：**不保存即永久无法解锁**（会话锁定后内存副本立即清零，
 * 且该文件不会被再次生成）。因此：
 * - 主按钮直达 SAF 另存为（写盘复用既有导出通道 `exportKeyFileBytes`）；
 * - 次按钮文案如实写出后果，不提供「假装已保存」的第三条路径；
 * - 点击弹窗外部不关闭（`onDismissRequest` 不做任何事），杜绝误触导致第二因子静默丢失。
 */
@Composable
private fun KeyFileOneTimeSaveDialog(
    suggestedFileName: String,
    onSaveClick: () -> Unit,
    onSkipClick: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            // 必须显式选择：误触外部若静默关闭，用户将永久失去该密码库的第二因子
        },
        title = {
            Text(
                text = stringResource(R.string.db_picker_keyfile_backup_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.db_picker_keyfile_backup_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = suggestedFileName,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            Button(onClick = onSaveClick, shape = CapsuleShape) {
                Text(stringResource(R.string.db_picker_keyfile_backup_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onSkipClick) {
                Text(stringResource(R.string.db_picker_keyfile_backup_skip))
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun CreateVaultWizardDialog(
    onDismiss: () -> Unit,
    // 形参顺序与 DatabasePickerViewModel.createDatabase 严格一致，便于直接方法引用接线
    onConfirm: (
        name: String,
        pwd: CharArray,
        keyFile: Boolean,
        preset: String,
        keyFileSourceUri: String?
    ) -> Unit
) {
    val context = LocalContext.current
    var vaultName by remember { mutableStateOf("passwords.kdbx") }
    // H2 整改：主密码以 CharArray 承载（SecurePasswordField 桥接），不进入 String / UiState / StateFlow
    var passwordChars by remember { mutableStateOf(CharArray(0)) }
    var confirmChars by remember { mutableStateOf(CharArray(0)) }
    var passwordVisible by remember { mutableStateOf(false) }
    var useKeyFile by remember { mutableStateOf(false) }
    var keyFileChoice by remember { mutableStateOf(KeyFileSourceChoice.GENERATE) }
    var selectedKeyFilePath by remember { mutableStateOf("") }
    var selectedKeyFileName by remember { mutableStateOf("") }
    var selectedPreset by remember { mutableStateOf("ChaCha20 + Argon2id") }
    val presets = listOf("ChaCha20 + Argon2id", "AES-256 + Argon2id", "Twofish + AES-KDF")

    val keyPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val displayName = queryDocumentDisplayName(context, uri)
            selectedKeyFileName = displayName
            selectedKeyFilePath = uri.toString()
        }
    }

    val isKeyFileValid = !useKeyFile || keyFileChoice == KeyFileSourceChoice.GENERATE ||
        selectedKeyFilePath.isNotBlank()
    val isFormValid = vaultName.isNotBlank() && passwordChars.isNotEmpty() && passwordChars.contentEquals(confirmChars) && isKeyFileValid

    // 弹窗离场（确认 / 取消 / 进程回收）时擦除组件内持有的全部密码副本
    DisposableEffect(Unit) {
        onDispose {
            passwordChars.fill('0')
            confirmChars.fill('0')
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.db_picker_create_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = vaultName,
                    onValueChange = { vaultName = it },
                    label = { Text(stringResource(R.string.db_picker_vault_name)) },
                    placeholder = { Text(stringResource(R.string.db_picker_vault_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                SecurePasswordField(
                    label = stringResource(R.string.db_picker_new_pwd),
                    onPasswordChanged = { chars ->
                        passwordChars.fill('0')
                        passwordChars = chars.copyOf()
                    },
                    isPasswordVisible = passwordVisible,
                    onToggleVisibility = { passwordVisible = !passwordVisible },
                    modifier = Modifier.fillMaxWidth()
                )

                SecurePasswordField(
                    label = stringResource(R.string.db_picker_confirm_pwd),
                    onPasswordChanged = { chars ->
                        confirmChars.fill('0')
                        confirmChars = chars.copyOf()
                    },
                    isError = confirmChars.isNotEmpty() && !confirmChars.contentEquals(passwordChars),
                    supportingText = {
                        if (confirmChars.isNotEmpty() && !confirmChars.contentEquals(passwordChars)) {
                            Text(stringResource(R.string.db_picker_pwd_mismatch), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    isPasswordVisible = passwordVisible,
                    onToggleVisibility = { passwordVisible = !passwordVisible },
                    modifier = Modifier.fillMaxWidth()
                )

                // 文件密钥选择区域 (可选项：可生成新密钥，或选择已有密钥文件)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { useKeyFile = !useKeyFile }
                    ) {
                        Checkbox(checked = useKeyFile, onCheckedChange = { useKeyFile = it })
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.picker_keyfile_toggle),
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = stringResource(R.string.picker_keyfile_toggle_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (useKeyFile) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = keyFileChoice == KeyFileSourceChoice.GENERATE,
                                onClick = { keyFileChoice = KeyFileSourceChoice.GENERATE },
                                label = { Text(stringResource(R.string.picker_keyfile_generate), fontSize = 11.sp) },
                                shape = CapsuleShape
                            )
                            FilterChip(
                                selected = keyFileChoice == KeyFileSourceChoice.SELECT_EXISTING,
                                onClick = { keyFileChoice = KeyFileSourceChoice.SELECT_EXISTING },
                                label = { Text(stringResource(R.string.picker_keyfile_select_existing), fontSize = 11.sp) },
                                shape = CapsuleShape
                            )
                        }

                        if (keyFileChoice == KeyFileSourceChoice.GENERATE) {
                            // ISSUE-P3-21：原文案声称「自动保存至安全存储」，实际并无自动保存——
                            // 现改为如实描述「生成后强制一次性交付」
                            Text(
                                text = stringResource(R.string.db_picker_keyfile_generate_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = { keyPickerLauncher.launch(arrayOf("*/*")) },
                                    shape = CapsuleShape,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.picker_keyfile_pick), fontSize = 12.sp)
                                }

                                if (selectedKeyFileName.isNotBlank()) {
                                    Text(
                                        text = stringResource(R.string.picker_file_selected, selectedKeyFileName),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                OutlinedTextField(
                                    value = selectedKeyFilePath,
                                    onValueChange = { selectedKeyFilePath = it },
                                    label = { Text(stringResource(R.string.picker_keyfile_path_label)) },
                                    placeholder = { Text("content://... 或 /path/to/keyfile.key") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.db_picker_encryption_preset),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEach { preset ->
                        FilterChip(
                            selected = selectedPreset == preset,
                            onClick = { selectedPreset = preset },
                            label = { Text(preset.split(" ")[0], fontSize = 11.sp) },
                            shape = CapsuleShape
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                // H2 整改：直接移交组件持有的 CharArray（ViewModel 复制私有副本并自行擦除）
                // ISSUE-P3-21：SELECT_EXISTING 时上行选中的密钥文件 Uri，其字节真实参与复合密钥
                onClick = {
                    onConfirm(
                        vaultName,
                        passwordChars,
                        useKeyFile,
                        selectedPreset,
                        if (keyFileChoice == KeyFileSourceChoice.SELECT_EXISTING) selectedKeyFilePath else null
                    )
                },
                enabled = isFormValid,
                shape = CapsuleShape
            ) {
                Text(stringResource(R.string.btn_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}
