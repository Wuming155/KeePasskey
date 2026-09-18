package com.keepasskey.app.ui.screens.settings.subscreens

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
    /**
     * 「保存并同步」的后续动作：**仅在保存成功后**触发。
     *
     * 顺序编排（未验证连接则先测连接、通过才同步、失败即停并上浮）由 ViewModel 承担，
     * 本组件只保证「保存成功才调用」这一条 —— 同步因此走的是**已持久化的配置**，
     * 而非本组件的表单内存态（凭据 `CharArray` 在保存成功时已交出并被清空）。
     */
    onSyncAfterSave: () -> Unit = {},
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

    /**
     * 持久化当前表单的同步配置，返回是否保存成功。
     *
     * · **借用语义**（Wave 15 / ISSUE-P2-01）：保存成功即把凭据数组交给 ViewModel 消费，
     *   并**立即清空本地副本**——不在组件内留下第二份明文；
     * · 保存失败（https 校验拒绝 / 凭据封印失败）由 ViewModel 上浮反馈，本地不谎报「已保存」；
     * · 抽为局部函数供「保存并同步」与「保存配置」两个入口共用：两条路径若各写一份，
     *   迟早出现「一个入口清了凭据数组、另一个没清」这类偏差。
     */
    val persistConfig: () -> Boolean = {
        if (uiState.syncProvider == CloudSyncProvider.WEBDAV) {
            val saved = onUpdateWebDav(webdavUrl, webdavUsername, webdavPasswordChars, webdavRemotePath)
            if (saved) {
                saveFeedbackMessage = UiMessage(R.string.sync_webdav_saved)
                webdavPasswordChars = CharArray(0)
            }
            saved
        } else {
            val saved = onUpdateS3(
                s3Endpoint, s3Bucket, s3Region, s3AccessKeyChars,
                s3SecretKeyChars, s3ObjectKey, s3UsePathStyle
            )
            if (saved) {
                saveFeedbackMessage = UiMessage(R.string.sync_s3_saved)
                s3AccessKeyChars.fill('0')
                s3AccessKeyChars = CharArray(0)
                s3SecretKeyChars.fill('0')
                s3SecretKeyChars = CharArray(0)
            }
            saved
        }
    }

    LaunchedEffect(saveFeedbackText) {
        if (saveFeedbackText != null) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(saveFeedbackText)
            }
        }
    }

    SettingsSubscreenScaffold(
        titleRes = R.string.sync_screen_title,
        onBackClick = onBackClick,
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
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

                        // 本批整改：主操作由「只保存」上移为「保存并同步」——填完配置后用户的真实意图是
                        // 「让它生效」，而原流程须「保存配置 → 测试连接 → 立即同步」三次点击（且「立即同步」
                        // 在未验证连接时禁用）。守卫**不变**，改为**顺序动作的前置步骤**：保存成功 →
                        // 未验证则先测连接 → 通过才同步；任一环节失败即停并上浮。
                        // 「保存配置」保留为次级入口：只想改配置、暂不联网的用户仍可单独保存。
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { if (persistConfig()) onSyncAfterSave() },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.sync_save_and_sync_btn))
                            }
                            OutlinedButton(
                                onClick = { persistConfig() },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.sync_save_config_btn))
                            }
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

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@androidx.compose.ui.tooling.preview.Preview(name = "云端同步设置页 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "云端同步设置页 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun CloudSyncScreenPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        CloudSyncScreen(
            uiState = com.keepasskey.app.ui.screens.settings.SettingsUiState(),
            onBackClick = {},
            onAutoSyncToggle = {},
            onWifiOnlyToggle = {},
            onTriggerSync = {}
        )
    }
}
