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
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.ui.screens.settings.CloudSyncProvider
import com.keepasskey.app.ui.screens.settings.ConflictResolution
import com.keepasskey.app.ui.screens.settings.SettingsUiState
import com.keepasskey.app.ui.theme.LocalSecurityColors
import kotlinx.coroutines.launch

/**
 * 云端同步与文件处理配置页 (全面整合 KeePass2Android 离线缓存、定时同步与容灾备份)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSyncScreen(
    uiState: SettingsUiState,
    onBackClick: () -> Unit,
    onAutoSyncToggle: (Boolean) -> Unit,
    onWifiOnlyToggle: (Boolean) -> Unit,
    onTriggerSync: () -> Unit,
    onProviderChange: (CloudSyncProvider) -> Unit = {},
    onUpdateWebDav: (url: String, username: String, password: String, remotePath: String) -> Unit = { _, _, _, _ -> },
    onUpdateS3: (endpoint: String, bucket: String, region: String, accessKey: String, secretKey: String, objectKey: String) -> Unit = { _, _, _, _, _, _ -> },
    // KP2A 扩展文件处理操作
    onUseOfflineCacheToggle: (Boolean) -> Unit = {},
    onPeriodicBackgroundSyncToggle: (Boolean) -> Unit = {},
    onPeriodicIntervalChange: (Int) -> Unit = {},
    onAllowedWifiSsidsChange: (String) -> Unit = {},
    onCreateBackupBeforeSaveToggle: (Boolean) -> Unit = {},
    onCheckRemoteChangesToggle: (Boolean) -> Unit = {},
    onConflictResolutionChange: (ConflictResolution) -> Unit = {},
    onUseFileTransactionsToggle: (Boolean) -> Unit = {},
    onAcceptAllCertificatesToggle: (Boolean) -> Unit = {},
    onCleartextTrafficPermittedToggle: (Boolean) -> Unit = {},
    onWebdavChunkedUploadToggle: (Boolean) -> Unit = {},
    onPreloadDatabaseEnabledToggle: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val securityColors = LocalSecurityColors.current

    var dropdownExpanded by remember { mutableStateOf(false) }

    var webdavUrl by remember(uiState.webdavUrl) { mutableStateOf(uiState.webdavUrl) }
    var webdavUsername by remember(uiState.webdavUsername) { mutableStateOf(uiState.webdavUsername) }
    var webdavPassword by remember(uiState.webdavPassword) { mutableStateOf(uiState.webdavPassword) }
    var webdavRemotePath by remember(uiState.webdavRemotePath) { mutableStateOf(uiState.webdavRemotePath) }
    var webdavPasswordVisible by remember { mutableStateOf(false) }

    var s3Endpoint by remember(uiState.s3Endpoint) { mutableStateOf(uiState.s3Endpoint) }
    var s3Bucket by remember(uiState.s3Bucket) { mutableStateOf(uiState.s3Bucket) }
    var s3Region by remember(uiState.s3Region) { mutableStateOf(uiState.s3Region) }
    var s3AccessKey by remember(uiState.s3AccessKey) { mutableStateOf(uiState.s3AccessKey) }
    var s3SecretKey by remember(uiState.s3SecretKey) { mutableStateOf(uiState.s3SecretKey) }
    var s3ObjectKey by remember(uiState.s3ObjectKey) { mutableStateOf(uiState.s3ObjectKey) }
    var s3SecretKeyVisible by remember { mutableStateOf(false) }

    var showIntervalDialog by remember { mutableStateOf(false) }
    var showConflictDialog by remember { mutableStateOf(false) }
    var saveFeedbackText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(saveFeedbackText) {
        if (saveFeedbackText != null) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(saveFeedbackText ?: "配置已保存")
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "云端同步与文件处理",
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
            // 1. 同步协议提供商选择
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "同步服务类型",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 2.dp)
                    )

                    ExposedDropdownMenuBox(
                        expanded = dropdownExpanded,
                        onExpandedChange = { dropdownExpanded = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = uiState.syncProvider.label,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("选择云同步协议") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded)
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        )

                        ExposedDropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false }
                        ) {
                            CloudSyncProvider.entries.forEach { provider ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                text = provider.label,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                            )
                                            Text(
                                                text = provider.desc,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        onProviderChange(provider)
                                        dropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 2. 详细凭据与连接端点表单
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (uiState.syncProvider == CloudSyncProvider.WEBDAV) "WebDAV 节点连接配置" else "S3 兼容对象存储配置",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 2.dp)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (uiState.syncProvider == CloudSyncProvider.WEBDAV) {
                            OutlinedTextField(
                                value = webdavUrl,
                                onValueChange = { webdavUrl = it },
                                label = { Text("WebDAV 服务器完整 URL") },
                                placeholder = { Text("https://cloud.example.com/remote.php/dav/files/user/") },
                                leadingIcon = { Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = webdavUsername,
                                onValueChange = { webdavUsername = it },
                                label = { Text("用户名 / 账号") },
                                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = webdavPassword,
                                onValueChange = { webdavPassword = it },
                                label = { Text("应用密码 / 凭据 Token") },
                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                trailingIcon = {
                                    IconButton(onClick = { webdavPasswordVisible = !webdavPasswordVisible }) {
                                        Icon(
                                            imageVector = if (webdavPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = null
                                        )
                                    }
                                },
                                visualTransformation = if (webdavPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = webdavRemotePath,
                                onValueChange = { webdavRemotePath = it },
                                label = { Text("远程数据库文件路径") },
                                placeholder = { Text("/Passkeys/keepasskey.kdbx") },
                                leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            OutlinedTextField(
                                value = s3Endpoint,
                                onValueChange = { s3Endpoint = it },
                                label = { Text("S3 Endpoint (端点 URL)") },
                                placeholder = { Text("https://<account_id>.r2.cloudflarestorage.com") },
                                leadingIcon = { Icon(Icons.Default.CloudQueue, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = s3Bucket,
                                    onValueChange = { s3Bucket = it },
                                    label = { Text("存储桶 Bucket") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f)
                                )

                                OutlinedTextField(
                                    value = s3Region,
                                    onValueChange = { s3Region = it },
                                    label = { Text("区域 Region") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            OutlinedTextField(
                                value = s3AccessKey,
                                onValueChange = { s3AccessKey = it },
                                label = { Text("Access Key ID") },
                                leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = s3SecretKey,
                                onValueChange = { s3SecretKey = it },
                                label = { Text("Secret Access Key") },
                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                trailingIcon = {
                                    IconButton(onClick = { s3SecretKeyVisible = !s3SecretKeyVisible }) {
                                        Icon(
                                            imageVector = if (s3SecretKeyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = null
                                        )
                                    }
                                },
                                visualTransformation = if (s3SecretKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = s3ObjectKey,
                                onValueChange = { s3ObjectKey = it },
                                label = { Text("对象存储文件 Key 路径") },
                                placeholder = { Text("passwords/master_vault.kdbx") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Button(
                            onClick = {
                                if (uiState.syncProvider == CloudSyncProvider.WEBDAV) {
                                    onUpdateWebDav(webdavUrl, webdavUsername, webdavPassword, webdavRemotePath)
                                    saveFeedbackText = "WebDAV 配置已保存在本地安全区"
                                } else {
                                    onUpdateS3(s3Endpoint, s3Bucket, s3Region, s3AccessKey, s3SecretKey, s3ObjectKey)
                                    saveFeedbackText = "S3 存储配置已保存在本地安全区"
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("保存当前连接配置")
                        }
                    }
                }
            }

            // 3. 同步状态卡片与手动测试
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "远端连接状态",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(securityColors.success.copy(alpha = 0.15f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = uiState.syncStatusText,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = securityColors.success
                            )
                        }
                    }

                    Text(
                        text = uiState.syncLastTime,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (uiState.syncFeedbackMessage != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = uiState.syncFeedbackMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onTriggerSync,
                            enabled = !uiState.isSyncing,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (uiState.isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("同步中...")
                            } else {
                                Icon(Icons.Default.Sync, contentDescription = "立即同步", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("立即同步")
                            }
                        }

                        OutlinedButton(
                            onClick = { onTriggerSync() },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.NetworkCheck, contentDescription = "测试连接", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("测试连接")
                        }
                    }
                }
            }

            // 4. 离线缓存与定时后台同步 (KP2A 特性)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "离线缓存与定时调度 (KP2A 特性)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 2.dp, top = 4.dp)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SyncSwitchItem(
                            icon = Icons.Default.Cached,
                            title = "启用本地离线安全缓存副本",
                            subtitle = "无网络时直接基于本地加密缓存打开和编辑，连网后自动合并",
                            checked = uiState.useOfflineCache,
                            onCheckedChange = onUseOfflineCacheToggle
                        )

                        SyncSwitchItem(
                            icon = Icons.Default.CloudSync,
                            title = "变更自动实时同步",
                            subtitle = "每次添加、修改或删除凭据后自动推送到云端",
                            checked = uiState.autoSyncEnabled,
                            onCheckedChange = onAutoSyncToggle
                        )

                        SyncSwitchItem(
                            icon = Icons.Default.Schedule,
                            title = "启用周期性定时后台同步",
                            subtitle = "在后台静默定期检查远端是否有新变更并自动同步拉取",
                            checked = uiState.periodicBackgroundSyncEnabled,
                            onCheckedChange = onPeriodicBackgroundSyncToggle
                        )

                        if (uiState.periodicBackgroundSyncEnabled) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showIntervalDialog = true }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text("后台定时同步频率", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                                        Text("当前：每 ${uiState.periodicBackgroundSyncIntervalMinutes} 分钟执行一次", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(14.dp))
                            }
                        }

                        SyncSwitchItem(
                            icon = Icons.Default.Wifi,
                            title = "仅在 Wi-Fi 网络下执行同步",
                            subtitle = "避免移动蜂窝网络下消耗流量与后台请求",
                            checked = uiState.wifiOnlySync,
                            onCheckedChange = onWifiOnlyToggle
                        )
                    }
                }
            }

            // 5. 文件安全、备份与冲突解决 (KP2A 特性)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "文件安全与冲突解决 (KP2A 特性)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 2.dp, top = 4.dp)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SyncSwitchItem(
                            icon = Icons.Default.Backup,
                            title = "覆盖保存前自动创建备份副本 (.bak)",
                            subtitle = "每次将修改写入磁盘前，在安全目录创建带时间戳的历史备份",
                            checked = uiState.createBackupBeforeSave,
                            onCheckedChange = onCreateBackupBeforeSaveToggle
                        )

                        SyncSwitchItem(
                            icon = Icons.Default.Security,
                            title = "保存前检查远端文件变更",
                            subtitle = "写入前校验远端 Hash/ETag，防止覆盖其他设备刚提交的新修改",
                            checked = uiState.checkRemoteChangesBeforeSave,
                            onCheckedChange = onCheckRemoteChangesToggle
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showConflictDialog = true }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("并发冲突解决策略", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                                    Text("当前：${uiState.conflictResolution.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(14.dp))
                        }

                        SyncSwitchItem(
                            icon = Icons.Default.Save,
                            title = "使用文件原子事务写入 (File Transactions)",
                            subtitle = "通过临时文件完成完整落盘后再原子重命名，杜绝断电损坏库文件",
                            checked = uiState.useFileTransactions,
                            onCheckedChange = onUseFileTransactionsToggle
                        )
                    }
                }
            }

            // 6. 网络兼容性与传输选项 (KP2A 特性)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "网络连接兼容性 (KP2A 特性)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 2.dp, top = 4.dp)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SyncSwitchItem(
                            icon = Icons.Default.Lock,
                            title = "接受自签名 SSL/TLS 证书",
                            subtitle = "针对家庭 NAS 或自建 WebDAV 服务器的非权威机构自签证书放行",
                            checked = uiState.acceptAllCertificates,
                            onCheckedChange = onAcceptAllCertificatesToggle
                        )

                        SyncSwitchItem(
                            icon = Icons.Default.Public,
                            title = "允许明文 HTTP 网络传输",
                            subtitle = "允许连接内网未经 HTTPS 加密的纯 http:// 局域网主机",
                            checked = uiState.cleartextTrafficPermitted,
                            onCheckedChange = onCleartextTrafficPermittedToggle
                        )

                        SyncSwitchItem(
                            icon = Icons.Default.CloudQueue,
                            title = "WebDAV 分块切片上传 (Chunked Upload)",
                            subtitle = "针对某些网盘大文件传输限制，以 10MB 分块分段上传",
                            checked = uiState.webdavChunkedUpload,
                            onCheckedChange = onWebdavChunkedUploadToggle
                        )

                        SyncSwitchItem(
                            icon = Icons.Default.Cached,
                            title = "预加载数据库加速解锁 (Preload)",
                            subtitle = "进入应用时提前读取 KDBX 文件头元数据，缩短输密码后的等待",
                            checked = uiState.preloadDatabaseEnabled,
                            onCheckedChange = onPreloadDatabaseEnabledToggle
                        )
                    }
                }
            }

            // 7. 零知识与端到端加密机制说明
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = "Security Note",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "零知识端到端保护机制",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "无论是 WebDAV 还是兼容 S3 对象存储节点，远端仅接收并存储被 Argon2id 和 ChaCha20-Poly1305 高度加密的 .kdbx 二进制包。任何云厂商与服务器管理员均无权读取或解析您的任何明文密码与 Passkey 私钥。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // 后台定时同步间隔弹窗
    if (showIntervalDialog) {
        val intervalOptions = listOf(15 to "每 15 分钟", 30 to "每 30 分钟 (推荐)", 60 to "每 1 小时", 120 to "每 2 小时", 360 to "每 6 小时")
        AlertDialog(
            onDismissRequest = { showIntervalDialog = false },
            title = { Text("后台定时同步频率") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    intervalOptions.forEach { (mins, label) ->
                        val isSelected = uiState.periodicBackgroundSyncIntervalMinutes == mins
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPeriodicIntervalChange(mins)
                                    showIntervalDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = isSelected, onClick = {
                                onPeriodicIntervalChange(mins)
                                showIntervalDialog = false
                            })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showIntervalDialog = false }) { Text("关闭") }
            }
        )
    }

    // 冲突解决策略选择弹窗
    if (showConflictDialog) {
        AlertDialog(
            onDismissRequest = { showConflictDialog = false },
            title = { Text("冲突解决策略") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConflictResolution.entries.forEach { res ->
                        val isSelected = uiState.conflictResolution == res
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onConflictResolutionChange(res)
                                    showConflictDialog = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = isSelected, onClick = {
                                onConflictResolutionChange(res)
                                showConflictDialog = false
                            })
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(res.label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                Text(res.desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showConflictDialog = false }) { Text("关闭") }
            }
        )
    }
}

/**
 * 兼容旧名称调用的别名函数
 */
@Composable
fun WebDavSyncScreen(
    uiState: SettingsUiState,
    onBackClick: () -> Unit,
    onAutoSyncToggle: (Boolean) -> Unit,
    onWifiOnlyToggle: (Boolean) -> Unit,
    onTriggerSync: () -> Unit,
    onProviderChange: (CloudSyncProvider) -> Unit = {},
    onUpdateWebDav: (url: String, username: String, password: String, remotePath: String) -> Unit = { _, _, _, _ -> },
    onUpdateS3: (endpoint: String, bucket: String, region: String, accessKey: String, secretKey: String, objectKey: String) -> Unit = { _, _, _, _, _, _ -> },
    onUseOfflineCacheToggle: (Boolean) -> Unit = {},
    onPeriodicBackgroundSyncToggle: (Boolean) -> Unit = {},
    onPeriodicIntervalChange: (Int) -> Unit = {},
    onAllowedWifiSsidsChange: (String) -> Unit = {},
    onCreateBackupBeforeSaveToggle: (Boolean) -> Unit = {},
    onCheckRemoteChangesToggle: (Boolean) -> Unit = {},
    onConflictResolutionChange: (ConflictResolution) -> Unit = {},
    onUseFileTransactionsToggle: (Boolean) -> Unit = {},
    onAcceptAllCertificatesToggle: (Boolean) -> Unit = {},
    onCleartextTrafficPermittedToggle: (Boolean) -> Unit = {},
    onWebdavChunkedUploadToggle: (Boolean) -> Unit = {},
    onPreloadDatabaseEnabledToggle: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    CloudSyncScreen(
        uiState = uiState,
        onBackClick = onBackClick,
        onAutoSyncToggle = onAutoSyncToggle,
        onWifiOnlyToggle = onWifiOnlyToggle,
        onTriggerSync = onTriggerSync,
        onProviderChange = onProviderChange,
        onUpdateWebDav = onUpdateWebDav,
        onUpdateS3 = onUpdateS3,
        onUseOfflineCacheToggle = onUseOfflineCacheToggle,
        onPeriodicBackgroundSyncToggle = onPeriodicBackgroundSyncToggle,
        onPeriodicIntervalChange = onPeriodicIntervalChange,
        onAllowedWifiSsidsChange = onAllowedWifiSsidsChange,
        onCreateBackupBeforeSaveToggle = onCreateBackupBeforeSaveToggle,
        onCheckRemoteChangesToggle = onCheckRemoteChangesToggle,
        onConflictResolutionChange = onConflictResolutionChange,
        onUseFileTransactionsToggle = onUseFileTransactionsToggle,
        onAcceptAllCertificatesToggle = onAcceptAllCertificatesToggle,
        onCleartextTrafficPermittedToggle = onCleartextTrafficPermittedToggle,
        onWebdavChunkedUploadToggle = onWebdavChunkedUploadToggle,
        onPreloadDatabaseEnabledToggle = onPreloadDatabaseEnabledToggle,
        modifier = modifier
    )
}

@Composable
private fun SyncSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}
