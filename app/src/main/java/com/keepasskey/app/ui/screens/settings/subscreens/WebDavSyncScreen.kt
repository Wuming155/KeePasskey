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
    /** 「保存并同步」的后续动作（保存成功后触发；顺序编排在 ViewModel，见 `CloudSyncScreen`） */
    onSyncAfterSave: () -> Unit = {},
    // Wave 15 整改：密码/SecretKey 以 CharArray 借用语义提交，返回保存结果；
    // ISSUE-P2-01：AccessKey ID 亦改为 CharArray 借用语义提交
    onUpdateWebDav: (url: String, username: String, password: CharArray, remotePath: String) -> Boolean = { _, _, _, _ -> false },
    onUpdateS3: (endpoint: String, bucket: String, region: String, accessKey: CharArray, secretKey: CharArray, objectKey: String, usePathStyle: Boolean) -> Boolean = { _, _, _, _, _, _, _ -> false },
    webdavPasswordPrefill: CharArray? = null,
    s3SecretKeyPrefill: CharArray? = null,
    s3AccessKeyPrefill: CharArray? = null,
    onWebDavPasswordEdited: () -> Unit = {},
    onS3SecretKeyEdited: () -> Unit = {},
    onS3AccessKeyEdited: () -> Unit = {},
    onSyncOnColdStartToggle: (Boolean) -> Unit = {},
    onPeriodicBackgroundSyncToggle: (Boolean) -> Unit = {},
    onPeriodicIntervalChange: (Int) -> Unit = {},
    onCreateBackupBeforeSaveToggle: (Boolean) -> Unit = {},
    onCheckRemoteChangesToggle: (Boolean) -> Unit = {},
    onConflictResolutionChange: (ConflictResolution) -> Unit = {},
    onWebdavChunkedUploadToggle: (Boolean) -> Unit = {},
    // ISSUE-P3-387：远端目录浏览
    browseState: com.keepasskey.app.sync.RemoteBrowseUiState = com.keepasskey.app.sync.RemoteBrowseUiState.Idle,
    onBrowseWebDav: (url: String, username: String, password: CharArray, remotePath: String, cursor: String?) -> Unit = { _, _, _, _, _ -> },
    onBrowseS3: (
        endpoint: String,
        bucket: String,
        region: String,
        accessKey: CharArray,
        secretKey: CharArray,
        objectKey: String,
        usePathStyle: Boolean,
        cursor: String?
    ) -> Unit = { _, _, _, _, _, _, _, _ -> },
    onDismissBrowse: () -> Unit = {},
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
        onSyncAfterSave = onSyncAfterSave,
        onUpdateWebDav = onUpdateWebDav,
        onUpdateS3 = onUpdateS3,
        webdavPasswordPrefill = webdavPasswordPrefill,
        s3SecretKeyPrefill = s3SecretKeyPrefill,
        s3AccessKeyPrefill = s3AccessKeyPrefill,
        onWebDavPasswordEdited = onWebDavPasswordEdited,
        onS3SecretKeyEdited = onS3SecretKeyEdited,
        onS3AccessKeyEdited = onS3AccessKeyEdited,
        onSyncOnColdStartToggle = onSyncOnColdStartToggle,
        onPeriodicBackgroundSyncToggle = onPeriodicBackgroundSyncToggle,
        onPeriodicIntervalChange = onPeriodicIntervalChange,
        onCreateBackupBeforeSaveToggle = onCreateBackupBeforeSaveToggle,
        onCheckRemoteChangesToggle = onCheckRemoteChangesToggle,
        onConflictResolutionChange = onConflictResolutionChange,
        onWebdavChunkedUploadToggle = onWebdavChunkedUploadToggle,
        browseState = browseState,
        onBrowseWebDav = onBrowseWebDav,
        onBrowseS3 = onBrowseS3,
        onDismissBrowse = onDismissBrowse,
        modifier = modifier
    )
}

// ISSUE-P3-519：本文件是 `CloudSyncScreen` 的兼容别名（生产调用走 NavGraph），曾有的
// `WebDavSyncScreenPreview` 与「云端同步设置页」预览传同一默认态、导出图逐像素相同，
// 已删除——该屏的预览以 `CloudSyncScreenPreviewScreenshotExport` 单一来源维护。
