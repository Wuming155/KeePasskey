package com.keepasskey.app.ui.screens.database

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.R
import com.keepasskey.app.security.ApplyObscuredTouchFilter
import com.keepasskey.app.ui.model.VaultDatabaseInfo
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.theme.CapsuleShape

/**
 * 密码库选择页（ISSUE-P3-31 批次 B：本文件收敛为「页面编排 + 内容骨架」，
 * 卡片与两个向导对话框已按内聚边界拆至同包独立文件，纯结构性改动）。
 */
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

/** 从 SAF 选取结果解析展示名；失败时回退 URI 末段，绝不因异常阻断选择流程 */
internal fun queryDocumentDisplayName(context: android.content.Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()
}
