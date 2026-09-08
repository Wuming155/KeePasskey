package com.keepasskey.app.ui.screens.settings.subscreens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.resolveText
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

    // TASK-13 整改：SAF CreateDocument 真实另存为（此前导出/密钥文件仅弹假成功提示）
    val exportKdbxLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let(onExportKdbx) }
    val exportXmlLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/xml")
    ) { uri -> uri?.let(onExportXml) }
    val exportKeyFileLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
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
            item {
                Text(
                    text = stringResource(R.string.dbset_section_basic),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DatabaseFieldRow(label = stringResource(R.string.dbset_field_db_name), value = uiState.databaseName)
                        DatabaseFieldRow(label = stringResource(R.string.dbset_field_db_path), value = uiState.databasePath)
                        DatabaseFieldRow(label = stringResource(R.string.dbset_field_default_user), value = uiState.databaseDefaultUsername)
                        DatabaseFieldRow(label = stringResource(R.string.dbset_field_compression), value = uiState.compressionAlgorithm)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.dbset_recycle_bin_title),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.dbset_recycle_bin_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = uiState.recycleBinEnabled,
                                onCheckedChange = onRecycleBinToggle,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }
                }
            }

            // 2. 密码学与 KDF 派生
            item {
                Text(
                    text = stringResource(R.string.dbset_section_crypto),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DatabaseFieldRow(
                            label = stringResource(R.string.dbset_field_cipher),
                            value = uiState.encryptionAlgorithm,
                            onClick = { showCipherDialog = true }
                        )
                        DatabaseFieldRow(
                            label = stringResource(R.string.dbset_field_kdf),
                            value = uiState.kdfAlgorithm,
                            onClick = { showKdfDialog = true }
                        )
                        DatabaseFieldRow(
                            label = stringResource(R.string.dbset_field_argon2),
                            value = stringResource(
                                R.string.dbset_argon2_value,
                                uiState.argon2MemoryMb,
                                uiState.argon2Iterations,
                                uiState.argon2Parallelism
                            ),
                            onClick = { showArgon2Dialog = true }
                        )
                    }
                }
            }

            // 3. 条目模板库与子数据库配置 (KP2A 特性)
            item {
                Text(
                    text = stringResource(R.string.dbset_section_extensions),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DatabaseActionRow(
                            icon = Icons.AutoMirrored.Filled.Notes,
                            title = stringResource(R.string.dbset_templates_title),
                            subtitle = stringResource(R.string.dbset_templates_sub),
                            onClick = { showTemplatesDialog = true }
                        )

                        DatabaseActionRow(
                            icon = Icons.Default.FolderShared,
                            title = stringResource(R.string.dbset_child_db_title),
                            subtitle = if (uiState.childDatabasesCount > 0) {
                                stringResource(R.string.dbset_child_db_linked, uiState.childDatabasesCount)
                            } else {
                                stringResource(R.string.dbset_child_db_none)
                            },
                            onClick = { showChildDbDialog = true }
                        )
                    }
                }
            }

            // 4. 数据导入与导出 (KP2A 特性)
            item {
                Text(
                    text = stringResource(R.string.dbset_section_import_export),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DatabaseActionRow(
                            icon = Icons.Default.Download,
                            title = stringResource(R.string.dbset_import_title),
                            subtitle = stringResource(R.string.dbset_import_sub),
                            onClick = { showImportDialog = true }
                        )

                        DatabaseActionRow(
                            icon = Icons.Default.Upload,
                            title = stringResource(R.string.dbset_export_title),
                            subtitle = stringResource(R.string.dbset_export_sub),
                            onClick = { showExportDialog = true }
                        )

                        DatabaseActionRow(
                            icon = Icons.Default.VpnKey,
                            title = stringResource(R.string.dbset_keyfile_export_title),
                            subtitle = stringResource(R.string.dbset_keyfile_export_sub),
                            onClick = {
                                // TASK-13 整改：呼起 SAF 另存为，会话绑定密钥文件经仓库真实导出
                                exportKeyFileLauncher.launch("keepasskey.keyx")
                            }
                        )
                    }
                }
            }

            // 5. 完整性与高级规则 (KP2A 特性)
            item {
                Text(
                    text = stringResource(R.string.dbset_section_integrity),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.dbset_tan_title),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.dbset_tan_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = uiState.tanExpiresOnUse,
                                onCheckedChange = onTanExpiresOnUseToggle,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.dbset_uuid_title),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.dbset_uuid_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = uiState.checkForDuplicateUuids,
                                onCheckedChange = onCheckForDuplicateUuidsToggle,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }
                }
            }

            if (operationFeedback != null) {
                item {
                    Text(
                        text = operationFeedback!!.resolveText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            // TASK-13 整改：导出/模板动作结果反馈（点击清除），真实动作的结果如实上浮
            exportFeedback?.let { feedback ->
                item {
                    Text(
                        text = feedback.resolveText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (feedback.resolveText().contains("失败")) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .clickable { onClearExportFeedback() }
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
        val cipherOptions = listOf(
            "ChaCha20-Poly1305 (256-bit)" to R.string.dbset_cipher_chacha_desc,
            "AES-256 (KDBX 4.1)" to R.string.dbset_cipher_aes_desc,
            "Twofish (256-bit)" to R.string.dbset_cipher_twofish_desc
        )
        AlertDialog(
            onDismissRequest = { showCipherDialog = false },
            title = { Text(stringResource(R.string.dbset_cipher_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cipherOptions.forEach { (name, desc) ->
                        val isSelected = uiState.encryptionAlgorithm.startsWith(name.split(" ")[0])
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    onEncryptionAlgorithmChange(name)
                                    showCipherDialog = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    onEncryptionAlgorithmChange(name)
                                    showCipherDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(desc),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCipherDialog = false }) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        )
    }

    // 对话框 2：KDF 密钥派生算法选择
    if (showKdfDialog) {
        val kdfOptions = listOf(
            "Argon2id" to R.string.dbset_kdf_argon2id_desc,
            "Argon2d" to R.string.dbset_kdf_argon2d_desc,
            "AES-KDF" to R.string.dbset_kdf_aeskdf_desc
        )
        AlertDialog(
            onDismissRequest = { showKdfDialog = false },
            title = { Text(stringResource(R.string.dbset_kdf_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    kdfOptions.forEach { (name, desc) ->
                        val isSelected = uiState.kdfAlgorithm == name
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    onKdfAlgorithmChange(name)
                                    showKdfDialog = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    onKdfAlgorithmChange(name)
                                    showKdfDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(desc),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showKdfDialog = false }) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        )
    }

    // 对话框 3：Argon2 参数详细调节
    if (showArgon2Dialog) {
        var tempIterations by remember { mutableLongStateOf(uiState.argon2Iterations) }
        var tempMemoryMb by remember { mutableLongStateOf(uiState.argon2MemoryMb) }
        var tempParallelism by remember { mutableIntStateOf(uiState.argon2Parallelism) }

        AlertDialog(
            onDismissRequest = { showArgon2Dialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.dbset_argon2_dialog_title))
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.dbset_argon2_iterations_label), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                            Text(stringResource(R.string.dbset_argon2_rounds_value, tempIterations), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { if (tempIterations > 1) tempIterations-- },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.dbset_cd_decrease_rounds))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.dbset_argon2_rounds_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = { if (tempIterations < 50) tempIterations++ },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.dbset_cd_increase_rounds))
                            }
                        }
                    }

                    Column {
                        Text(stringResource(R.string.dbset_argon2_memory_label, tempMemoryMb), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(16L, 32L, 64L, 128L, 256L).forEach { mb ->
                                FilterChip(
                                    selected = tempMemoryMb == mb,
                                    onClick = { tempMemoryMb = mb },
                                    label = { Text(stringResource(R.string.dbset_argon2_memory_chip, mb)) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                )
                            }
                        }
                    }

                    Column {
                        Text(stringResource(R.string.dbset_argon2_parallelism_label, tempParallelism), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1, 2, 4, 8).forEach { threads ->
                                FilterChip(
                                    selected = tempParallelism == threads,
                                    onClick = { tempParallelism = threads },
                                    label = { Text(stringResource(R.string.dbset_argon2_threads_chip, threads)) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = onRunKdfBenchmark,
                        enabled = kdfBenchmarkState?.isRunning != true,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.dbset_benchmark_btn))
                    }

                    // M6 整改：真实基准状态展示——运行中 / 推荐参数（自动填入上方调节项）/ 失败原因
                    val benchmarkState = kdfBenchmarkState
                    if (benchmarkState != null) {
                        if (benchmarkState.isRunning) {
                            Text(
                                text = stringResource(R.string.dbset_benchmark_running),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        benchmarkState.recommendedIterations?.let { recommendedIterations ->
                            LaunchedEffect(recommendedIterations) {
                                tempIterations = recommendedIterations
                                benchmarkState.recommendedMemoryMb?.let { tempMemoryMb = it }
                                benchmarkState.recommendedParallelism?.let { tempParallelism = it }
                            }
                            Text(
                                text = stringResource(
                                    R.string.dbset_benchmark_result,
                                    recommendedIterations,
                                    benchmarkState.recommendedMemoryMb ?: 0L,
                                    benchmarkState.recommendedParallelism ?: 0
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        benchmarkState.errorMessage?.let { error ->
                            Text(
                                text = stringResource(R.string.dbset_benchmark_failed, error),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onArgon2ParametersChange(tempIterations, tempMemoryMb, tempParallelism)
                    showArgon2Dialog = false
                }) {
                    Text(stringResource(R.string.dbset_apply_params))
                }
            },
            dismissButton = {
                TextButton(onClick = { showArgon2Dialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    // 对话框 4：模板库管理对话框
    if (showTemplatesDialog) {
        val templates = listOf(
            stringResource(R.string.dbset_tpl_web) to Icons.Default.Lock,
            stringResource(R.string.dbset_tpl_credit_card) to Icons.Default.CreditCard,
            stringResource(R.string.dbset_tpl_wifi) to Icons.Default.Wifi,
            stringResource(R.string.dbset_tpl_note) to Icons.AutoMirrored.Filled.Notes,
            stringResource(R.string.dbset_tpl_ssh) to Icons.Default.Terminal
        )
        AlertDialog(
            onDismissRequest = { showTemplatesDialog = false },
            title = { Text(stringResource(R.string.dbset_templates_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.dbset_templates_dialog_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    templates.forEach { (name, icon) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(text = name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showTemplatesDialog = false
                    // TASK-13 整改：真实创建模板分组与模板条目（幂等）
                    onInstallTemplates()
                }) {
                    Text(stringResource(R.string.dbset_install_templates))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTemplatesDialog = false }) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        )
    }

    // 对话框 5：子数据库挂载
    if (showChildDbDialog) {
        AlertDialog(
            onDismissRequest = { showChildDbDialog = false },
            title = { Text(stringResource(R.string.dbset_child_db_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.dbset_child_db_dialog_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            showChildDbDialog = false
                            // TASK-13 整改：如实告知未实现——子库挂载需独立功能开发，
                            // 不再谎报「挂载成功」（登记 STATUS TASK-43 预留功能清单）
                            operationFeedback = UiMessage(R.string.dbset_child_db_not_supported)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.dbset_child_db_select_file))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showChildDbDialog = false }) {
                    Text(stringResource(R.string.dbset_btn_done))
                }
            }
        )
    }

    // 对话框 6：导出密码库
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(stringResource(R.string.dbset_export_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.dbset_export_dialog_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(
                        onClick = {
                            showExportDialog = false
                            // TASK-13 整改：呼起 SAF 另存为，当前内存数据库经仓库真实序列化落盘
                            exportKdbxLauncher.launch(
                                uiState.databaseName.ifBlank { "keepasskey-export.kdbx" }
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.dbset_export_kdbx_btn))
                    }
                    OutlinedButton(
                        onClick = {
                            showExportDialog = false
                            // TASK-13 整改：呼起 SAF 另存为，明文 XML 经 KeePassXmlExporter 真实生成
                            val baseName = uiState.databaseName.removeSuffix(".kdbx")
                                .ifBlank { "keepasskey" }
                            exportXmlLauncher.launch("$baseName-export.xml")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.dbset_export_xml_btn))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    // 对话框 7：导入数据源
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text(stringResource(R.string.dbset_import_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        stringResource(R.string.dbset_src_1pux),
                        stringResource(R.string.dbset_src_bitwarden),
                        stringResource(R.string.dbset_src_keepass),
                        stringResource(R.string.dbset_src_browser)
                    ).forEach { source ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    showImportDialog = false
                                    operationFeedback = UiMessage(R.string.dbset_import_preparing, listOf(source))
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(source, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

@Composable
private fun DatabaseFieldRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onClick)
                        .padding(vertical = 4.dp, horizontal = 2.dp)
                } else {
                    Modifier
                }
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
        if (onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = stringResource(R.string.dbset_cd_modify),
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun DatabaseActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(14.dp)
        )
    }
}
