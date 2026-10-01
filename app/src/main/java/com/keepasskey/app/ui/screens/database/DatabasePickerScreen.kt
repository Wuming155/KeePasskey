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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.CreateVaultPreset
import com.keepasskey.app.security.ApplyObscuredTouchFilter
import com.keepasskey.app.ui.model.VaultDatabaseInfo
import com.keepasskey.app.ui.model.VaultRemovalKind
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
    // ISSUE-P2-424 AC③：打开对话框的云账号预填包（对话框消费后即弃持）
    val openVaultPrefill by viewModel.openVaultPrefill.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is DatabasePickerEvent.DatabaseSelected -> {
                    onDatabaseSelected(event.id)
                }
            }
        }
    }

    DatabasePickerContent(
        uiState = uiState,
        keyFileDelivery = keyFileDelivery,
        openVaultPrefill = openVaultPrefill,
        onBackClick = onBackClick,
        onSelectDatabase = viewModel::selectDatabase,
        onOpenCreateDialog = viewModel::openCreateDialog,
        onCloseCreateDialog = viewModel::closeCreateDialog,
        onCreateDatabase = viewModel::createDatabase,
        onWeakPasswordConfirmed = viewModel::noteWeakMasterPasswordConfirmed,
        onSaveKeyFile = viewModel::saveGeneratedKeyFileTo,
        onKeyFileDeliveryDismissed = viewModel::dismissKeyFileDelivery,
        onOpenExistingClick = viewModel::openOpenSourceDialog,
        onCloseOpenSourceDialog = viewModel::closeOpenSourceDialog,
        onConsumeOpenVaultPrefill = viewModel::consumeOpenVaultPrefill,
        onNoteOpenVaultSource = viewModel::noteOpenVaultSource,
        onImportFromSource = viewModel::importDatabaseFromSource,
        onRemoveDatabase = viewModel::removeDatabase,
        // ISSUE-P3-400：远端目录浏览（对话框「浏览远端目录」与设置页共用同一控制器单例）
        browseState = viewModel.remoteBrowseState,
        onBrowseWebDav = viewModel::browseRemoteWebDav,
        onBrowseS3 = viewModel::browseRemoteS3,
        onDismissBrowse = viewModel::dismissRemoteBrowse,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatabasePickerContent(
    uiState: DatabasePickerUiState,
    onBackClick: () -> Unit,
    onSelectDatabase: (String) -> Unit,
    onOpenCreateDialog: () -> Unit,
    onCloseCreateDialog: () -> Unit,
    /** ISSUE-P2-288：弱主口令显式确认后的留痕回调（不落明文） */
    onWeakPasswordConfirmed: () -> Unit = {},
    onCreateDatabase: (
        name: String,
        pwd: CharArray,
        keyFile: Boolean,
        preset: CreateVaultPreset,
        keyFileSourceUri: String?,
        targetUri: String?,
        storageLocation: VaultStorageLocation
    ) -> Unit,
    onOpenExistingClick: () -> Unit,
    onCloseOpenSourceDialog: () -> Unit,
    // ISSUE-P2-424 AC③：打开对话框的云账号预填（消费确认 + 来源留痕）
    openVaultPrefill: OpenVaultPrefill? = null,
    onConsumeOpenVaultPrefill: () -> Unit = {},
    onNoteOpenVaultSource: (OpenVaultSourceType) -> Unit = {},
    onImportFromSource: (submission: OpenVaultSubmission) -> Unit,
    // ISSUE-P1-241：第二个参数即该动作的**真实对象**（应用私有库 = 真删文件 / 外部库 = 只摘登记），
    // 与确认弹窗所用文案同一枚判据，数据层据此决定是否删除物理文件
    onRemoveDatabase: (String, VaultRemovalKind) -> Unit,
    // ISSUE-P3-21：生成型密钥文件的一次性交付（默认值便于预览与既有调用点复用）
    keyFileDelivery: KeyFileDeliveryState = KeyFileDeliveryState.None,
    onSaveKeyFile: (Uri) -> Unit = {},
    onKeyFileDeliveryDismissed: () -> Unit = {},
    // ISSUE-P3-400：远端目录浏览（状态与动作均经 ViewModel 透传）
    browseState: kotlinx.coroutines.flow.StateFlow<com.keepasskey.app.sync.RemoteBrowseUiState> = kotlinx.coroutines.flow.MutableStateFlow(com.keepasskey.app.sync.RemoteBrowseUiState.Idle),
    onBrowseWebDav: (String, String, CharArray, String, String?) -> Unit = { _, _, _, _, _ -> },
    onBrowseS3: (String, String, String, CharArray, CharArray, String, Boolean, String?) -> Unit = { _, _, _, _, _, _, _, _ -> },
    onDismissBrowse: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var dbToRemove by remember { mutableStateOf<VaultDatabaseInfo?>(null) }

    // ISSUE-P1-241：判定「应用私有库 / 外部库」所需的应用私有目录（`filesDir` 取值不做 I/O）。
    // 该枚判据同时驱动两件事——确认弹窗的措辞与下行给数据层的删除开关——故在此取一次共用。
    val context = LocalContext.current
    val filesDirPath = remember(context) { context.filesDir?.absolutePath }

    // ISSUE-P3-230 AC②：缺持久化授权的库「重新授权」——由用户重选**同一个**文件以重新取得
    // 长期授权（SAF 无「原地续期」原语，必须经用户操作）。授权后复用既有导入路径登记
    // （同一 path 幂等覆盖条目），不新开写盘或登记分支。
    var pendingRestore by remember { mutableStateOf<VaultDatabaseInfo?>(null) }
    val restorePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val target = pendingRestore
        pendingRestore = null
        if (uri != null && target != null) {
            onImportFromSource(OpenVaultSubmission.Local(name = target.name, path = uri.toString()))
        }
    }

    // ISSUE-P3-21：SAF 另存为生成型密钥文件（仅传 Uri 上行，写盘由 ViewModel 复用既有导出通道完成）
    val keyFileSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let(onSaveKeyFile) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
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

            // ISSUE-P2-354 AC①：isLoading 死字段接线为真实渲染——建库（Argon2 秒级）进行中
            // 展示进度与文案，下方两个入口一并禁用（防止向导外再开第二条写库路径）
            if (uiState.isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = stringResource(R.string.db_picker_creating),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ISSUE-P3-426：「添加密码库」信息架构重整——新建（主动作，实心主按钮）与导入
            // （次动作，描边按钮）上下分区、全宽排布，不再同排并列混排；
            // 解锁页空状态同为「上下两张卡片」的分区口径，两屏语义一致
            Button(
                onClick = onOpenCreateDialog,
                enabled = !uiState.isLoading,
                shape = CapsuleShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.db_picker_create_new))
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onOpenExistingClick,
                enabled = !uiState.isLoading,
                shape = CapsuleShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.db_picker_open_external))
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
                            onDelete = { dbToRemove = db },
                            onRestoreAccess = {
                                pendingRestore = db
                                restorePermissionLauncher.launch(arrayOf("*/*"))
                            }
                        )
                    }
                }
            }
        }
    }

    // 新建密码库向导对话框 (支持生成或选择已有密钥文件；ISSUE-P3-425 云端直建位置)
    if (uiState.showCreateDialog) {
        CreateVaultWizardDialog(
            onDismiss = onCloseCreateDialog,
            onConfirm = onCreateDatabase,
            onWeakPasswordConfirmed = onWeakPasswordConfirmed,
            // ISSUE-P2-354 AC①：busy 真相源是 isLoading（ViewModel 同步守卫的投影）
            isBusy = uiState.isLoading,
            cloudSnapshot = uiState.cloudSnapshot
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

    // 打开已有 KDBX 文件对话框 (本地/WebDAV/S3 三来源 + ISSUE-P2-424 云账号预填与来源预选)
    if (uiState.showOpenSourceDialog) {
        OpenExistingVaultDialog(
            onDismiss = onCloseOpenSourceDialog,
            onConfirm = onImportFromSource,
            browseState = browseState.collectAsState().value,
            onBrowseWebDav = onBrowseWebDav,
            onBrowseS3 = onBrowseS3,
            onDismissBrowse = onDismissBrowse,
            prefill = openVaultPrefill,
            onPrefillConsumed = onConsumeOpenVaultPrefill,
            onSourceSelected = onNoteOpenVaultSource
        )
    }

    // 移除密码库确认对话框（ISSUE-P1-241）：文案与动作按**存储类型**分列两套。
    // 判据与投影见 VaultRemovalPresentation.kt、渲染见 VaultRemovalConfirmDialog.kt；
    // 下行给数据层的删除开关必须取同一枚判据（confirmation.kind）。
    dbToRemove?.let { db ->
        val confirmation = VaultRemovalConfirmation.of(database = db, filesDirPath = filesDirPath)
        VaultRemovalConfirmDialog(
            confirmation = confirmation,
            onConfirm = {
                onRemoveDatabase(db.id, confirmation.kind)
                dbToRemove = null
            },
            onDismiss = { dbToRemove = null }
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
