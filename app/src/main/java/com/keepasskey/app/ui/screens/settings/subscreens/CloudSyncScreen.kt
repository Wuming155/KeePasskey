package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.screens.settings.CloudSyncProvider
import com.keepasskey.app.ui.screens.settings.ConflictResolution
import com.keepasskey.app.ui.screens.settings.SettingsUiState
import kotlinx.coroutines.launch

/**
 * 云端同步与文件处理配置页 (全面整合 KeePass2Android 离线缓存、定时同步与容灾备份)
 *
 * 区块展示组件见 CloudSyncComponents.kt / CloudSyncSections.kt。
 * 注意：表单 remember 状态与离场擦除必须留在本函数顶层（LazyColumn item 滚动离场会被销毁），
 * 因此抽出的字段组件均为无状态组件。
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
    // Wave 15 整改：密码/SecretKey 以 CharArray 借用语义提交，返回保存结果（false = 保存被拒绝或封印失败）；
    // ISSUE-P2-01：AccessKey ID 亦改为 CharArray 借用语义提交
    onUpdateWebDav: (url: String, username: String, password: CharArray, remotePath: String) -> Boolean = { _, _, _, _ -> false },
    onUpdateS3: (endpoint: String, bucket: String, region: String, accessKey: CharArray, secretKey: CharArray, objectKey: String, usePathStyle: Boolean) -> Boolean = { _, _, _, _, _, _, _ -> false },
    // Wave 15 整改：既有凭据经一次性预填通道下发（SecurePasswordField 消费后即清零）；
    // 用户开始编辑时经回调终结预填通道生命周期
    webdavPasswordPrefill: CharArray? = null,
    s3SecretKeyPrefill: CharArray? = null,
    s3AccessKeyPrefill: CharArray? = null,
    onWebDavPasswordEdited: () -> Unit = {},
    onS3SecretKeyEdited: () -> Unit = {},
    onS3AccessKeyEdited: () -> Unit = {},
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
    onWebdavChunkedUploadToggle: (Boolean) -> Unit = {},
    onPreloadDatabaseEnabledToggle: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var dropdownExpanded by remember { mutableStateOf(false) }

    var webdavUrl by remember(uiState.webdavUrl) { mutableStateOf(uiState.webdavUrl) }
    var webdavUsername by remember(uiState.webdavUsername) { mutableStateOf(uiState.webdavUsername) }
    // Wave 15 整改：密码以 CharArray 本地承载（显示用 String 仅存活于 SecurePasswordField 组件内部），
    // 离开组合时立即擦除
    var webdavPasswordChars by remember { mutableStateOf(CharArray(0)) }
    var webdavRemotePath by remember(uiState.webdavRemotePath) { mutableStateOf(uiState.webdavRemotePath) }
    var webdavPasswordVisible by remember { mutableStateOf(false) }

    var s3Endpoint by remember(uiState.s3Endpoint) { mutableStateOf(uiState.s3Endpoint) }
    var s3Bucket by remember(uiState.s3Bucket) { mutableStateOf(uiState.s3Bucket) }
    var s3Region by remember(uiState.s3Region) { mutableStateOf(uiState.s3Region) }
    // ISSUE-P2-01：AccessKey ID 以 CharArray 本地承载（显示用 String 仅存活于 SecurePasswordField
    // 组件内部），离开组合时立即擦除——不再从 UiState 的 String 回显
    var s3AccessKeyChars by remember { mutableStateOf(CharArray(0)) }
    var s3AccessKeyVisible by remember { mutableStateOf(false) }
    var s3SecretKeyChars by remember { mutableStateOf(CharArray(0)) }
    var s3ObjectKey by remember(uiState.s3ObjectKey) { mutableStateOf(uiState.s3ObjectKey) }
    var s3UsePathStyle by remember(uiState.s3UsePathStyle) { mutableStateOf(uiState.s3UsePathStyle) }
    var s3SecretKeyVisible by remember { mutableStateOf(false) }

    // Wave 15 整改：离场（离开组合）擦除本地密码驻留
    DisposableEffect(Unit) {
        onDispose {
            webdavPasswordChars.fill('0')
            s3AccessKeyChars.fill('0')
            s3SecretKeyChars.fill('0')
        }
    }

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
                ProviderSelectionSection(
                    expanded = dropdownExpanded,
                    onExpandedChange = { dropdownExpanded = it },
                    selectedProvider = uiState.syncProvider,
                    onProviderChange = onProviderChange
                )
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
                            WebDavConfigFields(
                                url = webdavUrl,
                                onUrlChange = { webdavUrl = it },
                                username = webdavUsername,
                                onUsernameChange = { webdavUsername = it },
                                isPasswordVisible = webdavPasswordVisible,
                                onTogglePasswordVisibility = { webdavPasswordVisible = !webdavPasswordVisible },
                                passwordPrefill = webdavPasswordPrefill,
                                onPasswordCharsChange = { chars ->
                                    webdavPasswordChars.fill('0')
                                    webdavPasswordChars = chars.copyOf()
                                    onWebDavPasswordEdited()
                                },
                                remotePath = webdavRemotePath,
                                onRemotePathChange = { webdavRemotePath = it }
                            )
                        } else {
                            S3ConfigFields(
                                endpoint = s3Endpoint,
                                onEndpointChange = { s3Endpoint = it },
                                bucket = s3Bucket,
                                onBucketChange = { s3Bucket = it },
                                region = s3Region,
                                onRegionChange = { s3Region = it },
                                isAccessKeyVisible = s3AccessKeyVisible,
                                onToggleAccessKeyVisibility = { s3AccessKeyVisible = !s3AccessKeyVisible },
                                accessKeyPrefill = s3AccessKeyPrefill,
                                onAccessKeyCharsChange = { chars ->
                                    s3AccessKeyChars.fill('0')
                                    s3AccessKeyChars = chars.copyOf()
                                    onS3AccessKeyEdited()
                                },
                                isSecretKeyVisible = s3SecretKeyVisible,
                                onToggleSecretKeyVisibility = { s3SecretKeyVisible = !s3SecretKeyVisible },
                                secretKeyPrefill = s3SecretKeyPrefill,
                                onSecretKeyCharsChange = { chars ->
                                    s3SecretKeyChars.fill('0')
                                    s3SecretKeyChars = chars.copyOf()
                                    onS3SecretKeyEdited()
                                },
                                objectKey = s3ObjectKey,
                                onObjectKeyChange = { s3ObjectKey = it },
                                usePathStyle = s3UsePathStyle,
                                onUsePathStyleChange = { s3UsePathStyle = it }
                            )
                        }

                        Button(
                            onClick = {
                                if (uiState.syncProvider == CloudSyncProvider.WEBDAV) {
                                    // Wave 15 整改：借用语义——调用后密码数组已被 ViewModel 消费擦除；
                                    // 保存失败（https 拒绝/封印失败）由 ViewModel 上浮反馈，本地不再谎报「已保存」
                                    val saved = onUpdateWebDav(webdavUrl, webdavUsername, webdavPasswordChars, webdavRemotePath)
                                    if (saved) {
                                        saveFeedbackMessage = UiMessage(R.string.sync_webdav_saved)
                                        webdavPasswordChars = CharArray(0)
                                    }
                                } else {
                                    // ISSUE-P2-01：借用语义——调用后 AccessKey/SecretKey 数组均已被
                                    // ViewModel 消费擦除；保存失败由 ViewModel 上浮反馈
                                    val saved = onUpdateS3(s3Endpoint, s3Bucket, s3Region, s3AccessKeyChars, s3SecretKeyChars, s3ObjectKey, s3UsePathStyle)
                                    if (saved) {
                                        saveFeedbackMessage = UiMessage(R.string.sync_s3_saved)
                                        s3AccessKeyChars.fill('0')
                                        s3AccessKeyChars = CharArray(0)
                                        s3SecretKeyChars.fill('0')
                                        s3SecretKeyChars = CharArray(0)
                                    }
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
                SyncStatusCard(
                    uiState = uiState,
                    onTriggerSync = onTriggerSync,
                    onTestConnection = onTestConnection
                )
            }

            // 4. 离线缓存与定时后台同步 (KP2A 特性)
            item {
                OfflineSyncSection(
                    uiState = uiState,
                    onUseOfflineCacheToggle = onUseOfflineCacheToggle,
                    onSyncOnColdStartToggle = onSyncOnColdStartToggle,
                    onAutoSyncToggle = onAutoSyncToggle,
                    onPeriodicBackgroundSyncToggle = onPeriodicBackgroundSyncToggle,
                    onShowIntervalDialog = { showIntervalDialog = true },
                    onWifiOnlyToggle = onWifiOnlyToggle
                )
            }

            // 5. 文件安全、备份与冲突解决 (KP2A 特性)
            item {
                FileSafetySection(
                    uiState = uiState,
                    onCreateBackupBeforeSaveToggle = onCreateBackupBeforeSaveToggle,
                    onCheckRemoteChangesToggle = onCheckRemoteChangesToggle,
                    onShowConflictDialog = { showConflictDialog = true },
                    onUseFileTransactionsToggle = onUseFileTransactionsToggle
                )
            }

            // 6. 网络兼容性与传输选项 (KP2A 特性)
            item {
                NetworkOptionsSection(
                    uiState = uiState,
                    onWebdavChunkedUploadToggle = onWebdavChunkedUploadToggle,
                    onPreloadDatabaseEnabledToggle = onPreloadDatabaseEnabledToggle
                )
            }

            // 7. 零知识与端到端加密机制说明
            item {
                ZeroKnowledgeCard()
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // 后台定时同步间隔弹窗
    if (showIntervalDialog) {
        SyncIntervalDialog(
            currentIntervalMinutes = uiState.periodicBackgroundSyncIntervalMinutes,
            onIntervalSelect = { mins ->
                onPeriodicIntervalChange(mins)
                showIntervalDialog = false
            },
            onDismiss = { showIntervalDialog = false }
        )
    }

    // 冲突解决策略选择弹窗
    if (showConflictDialog) {
        ConflictResolutionDialog(
            currentResolution = uiState.conflictResolution,
            onResolutionSelect = { res ->
                onConflictResolutionChange(res)
                showConflictDialog = false
            },
            onDismiss = { showConflictDialog = false }
        )
    }
}
