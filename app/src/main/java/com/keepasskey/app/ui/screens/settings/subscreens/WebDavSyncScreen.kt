package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.keepasskey.app.ui.screens.settings.CloudSyncProvider
import com.keepasskey.app.ui.screens.settings.ConflictResolution
import com.keepasskey.app.ui.screens.settings.SettingsUiState

/**
 * 兼容旧名称调用的别名函数（实际实现见 CloudSyncScreen.kt）
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
    // Wave 15 整改：密码/SecretKey 以 CharArray 借用语义提交，返回保存结果
    onUpdateWebDav: (url: String, username: String, password: CharArray, remotePath: String) -> Boolean = { _, _, _, _ -> false },
    onUpdateS3: (endpoint: String, bucket: String, region: String, accessKey: String, secretKey: CharArray, objectKey: String, usePathStyle: Boolean) -> Boolean = { _, _, _, _, _, _, _ -> false },
    webdavPasswordPrefill: CharArray? = null,
    s3SecretKeyPrefill: CharArray? = null,
    onWebDavPasswordEdited: () -> Unit = {},
    onS3SecretKeyEdited: () -> Unit = {},
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
        webdavPasswordPrefill = webdavPasswordPrefill,
        s3SecretKeyPrefill = s3SecretKeyPrefill,
        onWebDavPasswordEdited = onWebDavPasswordEdited,
        onS3SecretKeyEdited = onS3SecretKeyEdited,
        onUseOfflineCacheToggle = onUseOfflineCacheToggle,
        onSyncOnColdStartToggle = onSyncOnColdStartToggle,
        onPeriodicBackgroundSyncToggle = onPeriodicBackgroundSyncToggle,
        onPeriodicIntervalChange = onPeriodicIntervalChange,
        onAllowedWifiSsidsChange = onAllowedWifiSsidsChange,
        onCreateBackupBeforeSaveToggle = onCreateBackupBeforeSaveToggle,
        onCheckRemoteChangesToggle = onCheckRemoteChangesToggle,
        onConflictResolutionChange = onConflictResolutionChange,
        onUseFileTransactionsToggle = onUseFileTransactionsToggle,
        onWebdavChunkedUploadToggle = onWebdavChunkedUploadToggle,
        onPreloadDatabaseEnabledToggle = onPreloadDatabaseEnabledToggle,
        modifier = modifier
    )
}
