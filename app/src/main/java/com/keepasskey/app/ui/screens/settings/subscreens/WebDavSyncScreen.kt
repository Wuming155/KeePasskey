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
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.resolveText
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
    onTestConnection: () -> Unit = onTriggerSync,
    onProviderChange: (CloudSyncProvider) -> Unit = {},
    onUpdateWebDav: (url: String, username: String, password: String, remotePath: String) -> Unit = { _, _, _, _ -> },
    onUpdateS3: (endpoint: String, bucket: String, region: String, accessKey: String, secretKey: String, objectKey: String, usePathStyle: Boolean) -> Unit = { _, _, _, _, _, _, _ -> },
    // KP2A 扩展文件处理操作
    onUseOfflineCacheToggle: (Boolean) -> Unit = {},
    onSyncOnColdStartToggle: (Boolean) -> Unit = {},
    onPeriodicBackgroundSyncToggle: (Boolean) -> Unit = {},
    onPeriodicIntervalChange: (Int) -> Unit = {},
    onAllowedWifiSsidsChange: (String) -> Unit = {},
    onCreateBackupBeforeSaveToggle: (Boolean) -> Unit = {},
    onCheckRemoteChangesToggle: (Boolean) -> Unit = {},
    onConflictResolutionChange: (ConflictResolution) -> Unit = {},
    onUseFileTransactionsToggle: (Boolean) -> Unit = {},
    // Wave 12：明文/自签证书假开关已移除（TLS-only 恒定）；新增 WebDAV 证书锁定独立设置项
    onWebDavCertPinsChange: (String) -> Unit = {},
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
    var s3UsePathStyle by remember(uiState.s3UsePathStyle) { mutableStateOf(uiState.s3UsePathStyle) }
    var s3SecretKeyVisible by remember { mutableStateOf(false) }

    var showIntervalDialog by remember { mutableStateOf(false) }
    var showConflictDialog by remember { mutableStateOf(false) }
    var saveFeedbackMessage by remember { mutableStateOf<UiMessage?>(null) }
    val saveFeedbackText = saveFeedbackMessage?.resolveText()

    LaunchedEffect(saveFeedbackText) {
        if (saveFeedbackText != null) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(saveFeedbackText)
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
                        text = stringResource(R.string.sync_screen_title),
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
                        text = stringResource(R.string.sync_section_provider),
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
                            value = stringResource(uiState.syncProvider.labelRes),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.sync_provider_field_label)) },
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
                                                text = stringResource(provider.labelRes),
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                            )
                                            Text(
                                                text = stringResource(provider.descRes),
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
                        text = if (uiState.syncProvider == CloudSyncProvider.WEBDAV) {
                            stringResource(R.string.sync_section_webdav)
                        } else {
                            stringResource(R.string.sync_section_s3)
                        },
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
                                label = { Text(stringResource(R.string.sync_webdav_url_label)) },
                                placeholder = { Text("https://cloud.example.com/remote.php/dav/files/user/") },
                                leadingIcon = { Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = webdavUsername,
                                onValueChange = { webdavUsername = it },
                                label = { Text(stringResource(R.string.sync_webdav_username_label)) },
                                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = webdavPassword,
                                onValueChange = { webdavPassword = it },
                                label = { Text(stringResource(R.string.sync_webdav_password_label)) },
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
                                label = { Text(stringResource(R.string.sync_webdav_path_label)) },
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
                                label = { Text(stringResource(R.string.sync_s3_endpoint_label)) },
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
                                    label = { Text(stringResource(R.string.sync_s3_bucket_label)) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f)
                                )

                                OutlinedTextField(
                                    value = s3Region,
                                    onValueChange = { s3Region = it },
                                    label = { Text(stringResource(R.string.sync_s3_region_label)) },
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
                                label = { Text(stringResource(R.string.sync_s3_objectkey_label)) },
                                placeholder = { Text("passwords/master_vault.kdbx") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            // path-style 寻址开关：自建 MinIO / 反向代理 / IP 直连等场景必须开启
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.sync_s3_path_style_title),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = stringResource(R.string.sync_s3_path_style_sub),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = s3UsePathStyle,
                                    onCheckedChange = { s3UsePathStyle = it }
                                )
                            }
                        }

                        Button(
                            onClick = {
                                if (uiState.syncProvider == CloudSyncProvider.WEBDAV) {
                                    onUpdateWebDav(webdavUrl, webdavUsername, webdavPassword, webdavRemotePath)
                                    saveFeedbackMessage = UiMessage(R.string.sync_webdav_saved)
                                } else {
                                    onUpdateS3(s3Endpoint, s3Bucket, s3Region, s3AccessKey, s3SecretKey, s3ObjectKey, s3UsePathStyle)
                                    saveFeedbackMessage = UiMessage(R.string.sync_s3_saved)
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.sync_save_config_btn))
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
                            text = stringResource(R.string.sync_connection_status),
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
                                    text = uiState.syncFeedbackMessage?.resolveText() ?: "",
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
                                Text(stringResource(R.string.sync_btn_syncing))
                            } else {
                                Icon(Icons.Default.Sync, contentDescription = stringResource(R.string.sync_cd_sync_now), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.sync_sync_now_btn))
                            }
                        }

                        OutlinedButton(
                            onClick = { onTestConnection() },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.NetworkCheck, contentDescription = stringResource(R.string.sync_cd_test_connection), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.sync_test_connection_btn))
                        }
                    }
                }
            }

            // 4. 离线缓存与定时后台同步 (KP2A 特性)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.sync_section_offline),
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
                            title = stringResource(R.string.sync_offline_cache_title),
                            subtitle = stringResource(R.string.sync_offline_cache_sub),
                            checked = uiState.useOfflineCache,
                            onCheckedChange = onUseOfflineCacheToggle
                        )

                        SyncSwitchItem(
                            icon = Icons.Default.Sync,
                            title = stringResource(R.string.sync_cold_start_title),
                            subtitle = stringResource(R.string.sync_cold_start_sub),
                            checked = uiState.syncOnColdStart,
                            onCheckedChange = onSyncOnColdStartToggle
                        )

                        SyncSwitchItem(
                            icon = Icons.Default.CloudSync,
                            title = stringResource(R.string.sync_auto_sync_title),
                            subtitle = stringResource(R.string.sync_auto_sync_sub),
                            checked = uiState.autoSyncEnabled,
                            onCheckedChange = onAutoSyncToggle
                        )

                        SyncSwitchItem(
                            icon = Icons.Default.Schedule,
                            title = stringResource(R.string.sync_periodic_title),
                            subtitle = stringResource(R.string.sync_periodic_sub),
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
                                        Text(stringResource(R.string.sync_interval_title), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                                        Text(
                                            stringResource(R.string.sync_interval_current, uiState.periodicBackgroundSyncIntervalMinutes),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(14.dp))
                            }
                        }

                        SyncSwitchItem(
                            icon = Icons.Default.Wifi,
                            title = stringResource(R.string.sync_wifi_only_title),
                            subtitle = stringResource(R.string.sync_wifi_only_sub),
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
                        text = stringResource(R.string.sync_section_file_safety),
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
                            title = stringResource(R.string.sync_backup_title),
                            subtitle = stringResource(R.string.sync_backup_sub),
                            checked = uiState.createBackupBeforeSave,
                            onCheckedChange = onCreateBackupBeforeSaveToggle
                        )

                        SyncSwitchItem(
                            icon = Icons.Default.Security,
                            title = stringResource(R.string.sync_check_remote_title),
                            subtitle = stringResource(R.string.sync_check_remote_sub),
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
                                    Text(stringResource(R.string.sync_conflict_title), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                                    Text(
                                        stringResource(R.string.sync_conflict_current, stringResource(uiState.conflictResolution.labelRes)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(14.dp))
                        }

                        SyncSwitchItem(
                            icon = Icons.Default.Save,
                            title = stringResource(R.string.sync_file_tx_title),
                            subtitle = stringResource(R.string.sync_file_tx_sub),
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
                        text = stringResource(R.string.sync_section_network),
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
                        // Wave 12 传输加固：同步客户端恒定 TLS-only（明文与自签证书假开关已移除）；
                        // 证书锁定为可选防御纵深，由用户显式配置（官方警示：勿强制锁定以免阻碍证书轮换）
                        var webdavCertPinsDraft by remember(uiState.webdavCertPins) {
                            mutableStateOf(uiState.webdavCertPins)
                        }
                        OutlinedTextField(
                            value = webdavCertPinsDraft,
                            onValueChange = { webdavCertPinsDraft = it },
                            label = { Text(stringResource(R.string.sync_webdav_cert_pins_label)) },
                            placeholder = { Text(stringResource(R.string.sync_webdav_cert_pins_hint)) },
                            supportingText = { Text(stringResource(R.string.sync_webdav_cert_pins_sub)) },
                            minLines = 2,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextButton(
                            onClick = { onWebDavCertPinsChange(webdavCertPinsDraft) },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(stringResource(R.string.sync_webdav_cert_pins_save))
                        }

                        SyncSwitchItem(
                            icon = Icons.Default.CloudQueue,
                            title = stringResource(R.string.sync_chunked_title),
                            subtitle = stringResource(R.string.sync_chunked_sub),
                            checked = uiState.webdavChunkedUpload,
                            onCheckedChange = onWebdavChunkedUploadToggle
                        )

                        SyncSwitchItem(
                            icon = Icons.Default.Cached,
                            title = stringResource(R.string.sync_preload_title),
                            subtitle = stringResource(R.string.sync_preload_sub),
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
                            text = stringResource(R.string.sync_zeroknowledge_title),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = stringResource(R.string.sync_zeroknowledge_desc),
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
        val intervalOptions = listOf(
            15 to R.string.sync_interval_15,
            30 to R.string.sync_interval_30,
            60 to R.string.sync_interval_60,
            120 to R.string.sync_interval_120,
            360 to R.string.sync_interval_360
        )
        AlertDialog(
            onDismissRequest = { showIntervalDialog = false },
            title = { Text(stringResource(R.string.sync_interval_title)) },
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
                            Text(text = stringResource(label), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showIntervalDialog = false }) { Text(stringResource(R.string.btn_close)) }
            }
        )
    }

    // 冲突解决策略选择弹窗
    if (showConflictDialog) {
        AlertDialog(
            onDismissRequest = { showConflictDialog = false },
            title = { Text(stringResource(R.string.sync_conflict_dialog_title)) },
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
                                Text(stringResource(res.labelRes), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                Text(stringResource(res.descRes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showConflictDialog = false }) { Text(stringResource(R.string.btn_close)) }
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
    onTestConnection: () -> Unit = onTriggerSync,
    onProviderChange: (CloudSyncProvider) -> Unit = {},
    onUpdateWebDav: (url: String, username: String, password: String, remotePath: String) -> Unit = { _, _, _, _ -> },
    onUpdateS3: (endpoint: String, bucket: String, region: String, accessKey: String, secretKey: String, objectKey: String, usePathStyle: Boolean) -> Unit = { _, _, _, _, _, _, _ -> },
    onUseOfflineCacheToggle: (Boolean) -> Unit = {},
    onSyncOnColdStartToggle: (Boolean) -> Unit = {},
    onPeriodicBackgroundSyncToggle: (Boolean) -> Unit = {},
    onPeriodicIntervalChange: (Int) -> Unit = {},
    onAllowedWifiSsidsChange: (String) -> Unit = {},
    onCreateBackupBeforeSaveToggle: (Boolean) -> Unit = {},
    onCheckRemoteChangesToggle: (Boolean) -> Unit = {},
    onConflictResolutionChange: (ConflictResolution) -> Unit = {},
    onUseFileTransactionsToggle: (Boolean) -> Unit = {},
    onWebDavCertPinsChange: (String) -> Unit = {},
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
        onTestConnection = onTestConnection,
        onProviderChange = onProviderChange,
        onUpdateWebDav = onUpdateWebDav,
        onUpdateS3 = onUpdateS3,
        onUseOfflineCacheToggle = onUseOfflineCacheToggle,
        onSyncOnColdStartToggle = onSyncOnColdStartToggle,
        onPeriodicBackgroundSyncToggle = onPeriodicBackgroundSyncToggle,
        onPeriodicIntervalChange = onPeriodicIntervalChange,
        onAllowedWifiSsidsChange = onAllowedWifiSsidsChange,
        onCreateBackupBeforeSaveToggle = onCreateBackupBeforeSaveToggle,
        onCheckRemoteChangesToggle = onCheckRemoteChangesToggle,
        onConflictResolutionChange = onConflictResolutionChange,
        onUseFileTransactionsToggle = onUseFileTransactionsToggle,
        onWebDavCertPinsChange = onWebDavCertPinsChange,
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
