package com.keepasskey.app.ui.screens.database

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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.R
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
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
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
        onBackClick = onBackClick,
        onSelectDatabase = viewModel::selectDatabase,
        onOpenCreateDialog = viewModel::openCreateDialog,
        onCloseCreateDialog = viewModel::closeCreateDialog,
        onCreateDatabase = viewModel::createDatabase,
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
    onCreateDatabase: (name: String, pwd: String, keyFile: Boolean, preset: String) -> Unit,
    onOpenExistingClick: () -> Unit,
    onCloseOpenSourceDialog: () -> Unit,
    onImportFromSource: (source: OpenVaultSourceType, name: String, path: String) -> Unit,
    onRemoveDatabase: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var dbToRemove by remember { mutableStateOf<VaultDatabaseInfo?>(null) }

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

    // 新建密码库向导对话框 (支持生成或选择已有密钥文件)
    if (uiState.showCreateDialog) {
        CreateVaultWizardDialog(
            onDismiss = onCloseCreateDialog,
            onConfirm = onCreateDatabase
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

/**
 * 打开已有 KDBX 文件对话框 (支持本地/WebDAV/S3 自动展示并填写连接配置)
 */
@Composable
private fun OpenExistingVaultDialog(
    onDismiss: () -> Unit,
    onConfirm: (source: OpenVaultSourceType, name: String, path: String) -> Unit
) {
    var selectedSource by remember { mutableStateOf(OpenVaultSourceType.LOCAL) }

    // 本地字段
    var localPath by remember { mutableStateOf("/storage/emulated/0/Documents/passwords.kdbx") }
    var localName by remember { mutableStateOf("passwords.kdbx") }

    // WebDAV 字段及连接凭据
    var webdavUrl by remember { mutableStateOf("") }
    var webdavName by remember { mutableStateOf("cloud_vault.kdbx") }
    var webdavUser by remember { mutableStateOf("") }
    var webdavPassword by remember { mutableStateOf("") }

    // S3 兼容字段及连接凭据（M2 整改：默认值一律空串，杜绝示例凭据被静默导入）
    var s3Endpoint by remember { mutableStateOf("") }
    var s3Bucket by remember { mutableStateOf("") }
    var s3Name by remember { mutableStateOf("s3_vault.kdbx") }
    var s3AccessKey by remember { mutableStateOf("") }
    var s3SecretKey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "打开已有 KDBX 密码库",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "请选择已有密码库文件的存储源位置：",
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
                                        OpenVaultSourceType.LOCAL -> "本地设备"
                                        OpenVaultSourceType.WEBDAV -> "WebDAV"
                                        OpenVaultSourceType.S3_COMPATIBLE -> "兼容 S3"
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
                                text = "从本机设备存储或系统 SAF 选择器导入已存在的 .kdbx 数据库：",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            OutlinedTextField(
                                value = localName,
                                onValueChange = { localName = it },
                                label = { Text("密码库标识名称") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = localPath,
                                onValueChange = { localPath = it },
                                label = { Text("本地绝对路径 / 虚拟 URI") },
                                leadingIcon = { Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedButton(
                                onClick = {
                                    localPath = "/storage/emulated/0/Download/personal.kdbx"
                                    localName = "personal.kdbx"
                                },
                                shape = CapsuleShape,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("启动系统文件选择器定位")
                            }
                        }
                    }

                    OpenVaultSourceType.WEBDAV -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "连接私有 WebDAV 服务器 (Nextcloud / 坚果云 / 群晖) 打开远端库：",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            OutlinedTextField(
                                value = webdavName,
                                onValueChange = { webdavName = it },
                                label = { Text("密码库展示名称") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = webdavUrl,
                                onValueChange = { webdavUrl = it },
                                label = { Text("WebDAV 服务器完整路径 (URL)") },
                                placeholder = { Text("https://example.com/dav/passwords.kdbx") },
                                leadingIcon = { Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = webdavUser,
                                onValueChange = { webdavUser = it },
                                label = { Text("WebDAV 认证用户名") },
                                placeholder = { Text("username") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = webdavPassword,
                                onValueChange = { webdavPassword = it },
                                label = { Text("WebDAV 密码 / 应用专用 Token") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    OpenVaultSourceType.S3_COMPATIBLE -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "连接兼容 AWS S3 规范的对象存储桶 (Cloudflare R2 / MinIO) 打开已有库：",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            OutlinedTextField(
                                value = s3Name,
                                onValueChange = { s3Name = it },
                                label = { Text("密码库展示名称") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = s3Endpoint,
                                onValueChange = { s3Endpoint = it },
                                label = { Text("S3 Endpoint 接入端点 URL") },
                                placeholder = { Text("https://<account>.r2.cloudflarestorage.com") },
                                leadingIcon = { Icon(Icons.Default.CloudQueue, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = s3Bucket,
                                onValueChange = { s3Bucket = it },
                                label = { Text("存储桶名称 (Bucket) 及路径") },
                                placeholder = { Text("my-vault/keepass.kdbx") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = s3AccessKey,
                                    onValueChange = { s3AccessKey = it },
                                    label = { Text("Access Key") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = s3SecretKey,
                                    onValueChange = { s3SecretKey = it },
                                    label = { Text("Secret Key") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
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
                shape = CapsuleShape
            ) {
                Text("打开并加载")
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

@Composable
private fun CreateVaultWizardDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, pwd: String, keyFile: Boolean, preset: String) -> Unit
) {
    var vaultName by remember { mutableStateOf("my_vault.kdbx") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var useKeyFile by remember { mutableStateOf(false) }
    var keyFileMode by remember { mutableStateOf("GENERATE") } // "GENERATE" or "SELECT_EXISTING"
    var selectedKeyFilePath by remember { mutableStateOf("/storage/emulated/0/Documents/my_key.key") }
    var selectedPreset by remember { mutableStateOf("ChaCha20 + Argon2id") }
    val presets = listOf("ChaCha20 + Argon2id", "AES-256 + Argon2id", "Twofish + AES-KDF")

    val isFormValid = vaultName.isNotBlank() && password.isNotEmpty() && password == confirmPassword

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

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.db_picker_new_pwd)) },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text(stringResource(R.string.db_picker_confirm_pwd)) },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    isError = confirmPassword.isNotEmpty() && confirmPassword != password,
                    supportingText = {
                        if (confirmPassword.isNotEmpty() && confirmPassword != password) {
                            Text(stringResource(R.string.db_picker_pwd_mismatch), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    singleLine = true,
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
                                text = "启用文件密钥 (KeyFile / 可选项)",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = "主密码结合物理密钥文件，构成真正的双重鉴权",
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
                                selected = keyFileMode == "GENERATE",
                                onClick = { keyFileMode = "GENERATE" },
                                label = { Text("生成新密钥文件", fontSize = 11.sp) },
                                shape = CapsuleShape
                            )
                            FilterChip(
                                selected = keyFileMode == "SELECT_EXISTING",
                                onClick = { keyFileMode = "SELECT_EXISTING" },
                                label = { Text("选择已有密钥文件", fontSize = 11.sp) },
                                shape = CapsuleShape
                            )
                        }

                        if (keyFileMode == "GENERATE") {
                            Text(
                                text = "创建密码库时将自动生成一份 256-bit 高熵随机 .key 密钥文件并保存至安全存储。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = selectedKeyFilePath,
                                    onValueChange = { selectedKeyFilePath = it },
                                    label = { Text("已有密钥文件路径") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedButton(
                                    onClick = {
                                        selectedKeyFilePath = "/storage/emulated/0/Download/custom_vault.key"
                                    },
                                    shape = CapsuleShape,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("从设备选取已有密钥", fontSize = 12.sp)
                                }
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
                onClick = { onConfirm(vaultName, password, useKeyFile, selectedPreset) },
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
