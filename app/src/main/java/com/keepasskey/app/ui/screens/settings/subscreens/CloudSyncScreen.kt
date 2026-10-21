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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.AppSnackbarChannel
import com.keepasskey.app.ui.AppSnackbarEvent
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.screens.settings.CloudSyncProvider
import com.keepasskey.app.ui.screens.settings.ConflictResolution
import com.keepasskey.app.ui.screens.settings.SettingsUiState

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
    onRecoverSyncByKeepingLocal: () -> Unit = {},
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
    onSyncOnColdStartToggle: (Boolean) -> Unit = {},
    onPeriodicBackgroundSyncToggle: (Boolean) -> Unit = {},
    onPeriodicIntervalChange: (Int) -> Unit = {},
    onCreateBackupBeforeSaveToggle: (Boolean) -> Unit = {},
    onCheckRemoteChangesToggle: (Boolean) -> Unit = {},
    onConflictResolutionChange: (ConflictResolution) -> Unit = {},
    onWebdavChunkedUploadToggle: (Boolean) -> Unit = {},
    // ISSUE-P3-387：远端目录浏览（表单凭据 + 当前路径；选中文件后由宿主回填 remotePath）
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

    var dropdownExpanded by remember { mutableStateOf(false) }

    // 表单草稿：`uiState` 的同步配置字段是**异步**水合出来的（`hydrate()` 在页面组合之后
    // 从 IO 线程回灌）。若直接把 uiState 当 `remember` 的 key，晚到的回灌会重建本地状态并
    // 丢弃用户正在输入的草稿——真实表现为「刚敲完端点就变回默认地址，保存提交了旧值」。
    // 故草稿归本页面，只在字段尚未被用户编辑时接受播种（见 SyncFormDrafts）。
    val drafts = rememberSyncFormDrafts()
    drafts.syncFrom(uiState)

    var webdavUrl by drafts.stringField(SyncFormDrafts.KEY_WEBDAV_URL)
    var webdavUsername by drafts.stringField(SyncFormDrafts.KEY_WEBDAV_USERNAME)
    var webdavRemotePath by drafts.stringField(SyncFormDrafts.KEY_WEBDAV_REMOTE_PATH)
    var webdavPasswordVisible by remember { mutableStateOf(false) }
    var showBrowseDialog by remember { mutableStateOf(false) }

    var s3Endpoint by drafts.stringField(SyncFormDrafts.KEY_S3_ENDPOINT)
    var s3Bucket by drafts.stringField(SyncFormDrafts.KEY_S3_BUCKET)
    var s3Region by drafts.stringField(SyncFormDrafts.KEY_S3_REGION)
    var s3AccessKeyVisible by remember { mutableStateOf(false) }
    var s3ObjectKey by drafts.stringField(SyncFormDrafts.KEY_S3_OBJECT_KEY)
    var s3UsePathStyle by drafts.booleanField(SyncFormDrafts.KEY_S3_USE_PATH_STYLE)
    var s3SecretKeyVisible by remember { mutableStateOf(false) }
    var credentialFieldWipeGeneration by remember { mutableStateOf(0) }

    // Wave 15 整改：离场（离开组合）擦除本地密码驻留。
    // 秘密草稿由 `drafts` 持有（页面级）：表单 item 被 LazyColumn 回收时不得丢草稿，
    // 只有离页才擦除——否则「填好密码→滚到下方看状态→滚回来」密码已空，提交必然失败。
    DisposableEffect(Unit) {
        onDispose { drafts.wipeSecrets() }
    }

    var showIntervalDialog by remember { mutableStateOf(false) }
    var showConflictDialog by remember { mutableStateOf(false) }
    var saveFeedbackMessage by remember { mutableStateOf<UiMessage?>(null) }

    /**
     * 持久化当前表单的同步配置，返回是否保存成功（提交/清理语义见 [SyncFormPersistence]）。
     */
    // 提交与成功后清理收敛在 `SyncFormPersistence`：失败保留草稿供只改端点重试，
    // 成功才清空并提示（ISSUE-P2-651 / P2-652）。
    val persistence = SyncFormPersistence(
        provider = { uiState.syncProvider },
        webDav = SyncFormPersistence.WebDavFormAccess(
            url = { webdavUrl },
            username = { webdavUsername },
            remotePath = { webdavRemotePath },
            password = { drafts.secret(SyncFormDrafts.KEY_WEBDAV_PASSWORD) },
            update = { u, n, pwd, path -> onUpdateWebDav(u, n, pwd, path) },
            clearPassword = { pwd ->
                pwd.fill('0')
                drafts.clearSecret(SyncFormDrafts.KEY_WEBDAV_PASSWORD)
            }
        ),
        s3 = SyncFormPersistence.S3FormAccess(
            endpoint = { s3Endpoint },
            bucket = { s3Bucket },
            region = { s3Region },
            objectKey = { s3ObjectKey },
            usePathStyle = { s3UsePathStyle },
            accessKey = { drafts.secret(SyncFormDrafts.KEY_S3_ACCESS_KEY) },
            secretKey = { drafts.secret(SyncFormDrafts.KEY_S3_SECRET_KEY) },
            update = { e, b, r, ak, sk, key, pathStyle ->
                onUpdateS3(e, b, r, ak, sk, key, pathStyle)
            },
            clearSecrets = { ak, sk ->
                ak.fill('0')
                sk.fill('0')
                drafts.clearSecret(SyncFormDrafts.KEY_S3_ACCESS_KEY)
                drafts.clearSecret(SyncFormDrafts.KEY_S3_SECRET_KEY)
            }
        ),
        onSaved = { saveFeedbackMessage = it },
        onFieldWiped = {
            credentialFieldWipeGeneration++
            // 保存成功：落盘值已与表单一致（端点还被归一化），解除「已编辑」标记，
            // 让归一化后的外部值重新接管显示（与旧 `remember(uiState.x)` 行为逐字一致）。
            drafts.resetEdits()
        }
    )
    val persistConfig: () -> Boolean = { persistence.persist() }

    // ISSUE-P3-359 AC④：同步配置保存反馈转发全局通道（外壳唯一宿主呈现），回执后清位防重复发
    LaunchedEffect(saveFeedbackMessage) {
        saveFeedbackMessage?.let { message ->
            AppSnackbarChannel.trySend(AppSnackbarEvent(message))
            saveFeedbackMessage = null
        }
    }

    SettingsSubscreenScaffold(
        titleRes = R.string.settings_sync,
        onBackClick = onBackClick,
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ISSUE-P3-445 AC①：「测试连接」高困惑点一次性可关闭提示（关闭态持久化，非模态）
            item {
                com.keepasskey.app.ui.components.DismissibleHelpTip(
                    tip = com.keepasskey.app.ui.components.HelpTip.CLOUD_SYNC_TEST_CONNECTION
                )
            }

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
                            key(credentialFieldWipeGeneration) {
                                WebDavConfigFields(
                                    url = webdavUrl,
                                    onUrlChange = { webdavUrl = it },
                                    username = webdavUsername,
                                    onUsernameChange = { webdavUsername = it },
                                    isPasswordVisible = webdavPasswordVisible,
                                    onTogglePasswordVisibility = { webdavPasswordVisible = !webdavPasswordVisible },
                                    // LazyColumn 回收后组件重建，需把页面级草稿作为恢复预填回灌，
                                    // 否则滚回来密码框即空（ISSUE-P2-651）。
                                    passwordPrefill = drafts.secret(SyncFormDrafts.KEY_WEBDAV_PASSWORD).takeIf { it.isNotEmpty() }
                                        ?: webdavPasswordPrefill,
                                    onPasswordCharsChange = { chars ->
                                        drafts.setSecret(SyncFormDrafts.KEY_WEBDAV_PASSWORD, chars)
                                        onWebDavPasswordEdited()
                                    },
                                    remotePath = webdavRemotePath,
                                    onRemotePathChange = { webdavRemotePath = it },
                                    onBrowseRemote = {
                                        showBrowseDialog = true
                                        // ISSUE-P3-395/396：宿主清零入参故传 copyOf；路径字段可能是文件，浏览其父目录
                                        onBrowseWebDav(
                                            webdavUrl,
                                            webdavUsername,
                                            drafts.secret(SyncFormDrafts.KEY_WEBDAV_PASSWORD).copyOf(),
                                            com.keepasskey.app.sync.parentDirectoryPath(webdavRemotePath),
                                            null
                                        )
                                    }
                                )
                            }
                        } else {
                            key(credentialFieldWipeGeneration) {
                                S3ConfigFields(
                                    endpoint = s3Endpoint,
                                    onEndpointChange = { s3Endpoint = it },
                                    bucket = s3Bucket,
                                    onBucketChange = { s3Bucket = it },
                                    region = s3Region,
                                    onRegionChange = { s3Region = it },
                                    isAccessKeyVisible = s3AccessKeyVisible,
                                    onToggleAccessKeyVisibility = { s3AccessKeyVisible = !s3AccessKeyVisible },
                                    accessKeyPrefill = drafts.secret(SyncFormDrafts.KEY_S3_ACCESS_KEY).takeIf { it.isNotEmpty() }
                                        ?: s3AccessKeyPrefill,
                                    onAccessKeyCharsChange = { chars ->
                                        drafts.setSecret(SyncFormDrafts.KEY_S3_ACCESS_KEY, chars)
                                        onS3AccessKeyEdited()
                                    },
                                    isSecretKeyVisible = s3SecretKeyVisible,
                                    onToggleSecretKeyVisibility = { s3SecretKeyVisible = !s3SecretKeyVisible },
                                    secretKeyPrefill = drafts.secret(SyncFormDrafts.KEY_S3_SECRET_KEY).takeIf { it.isNotEmpty() }
                                        ?: s3SecretKeyPrefill,
                                    onSecretKeyCharsChange = { chars ->
                                        drafts.setSecret(SyncFormDrafts.KEY_S3_SECRET_KEY, chars)
                                        onS3SecretKeyEdited()
                                    },
                                    objectKey = s3ObjectKey,
                                    onObjectKeyChange = { s3ObjectKey = it },
                                    usePathStyle = s3UsePathStyle,
                                    onUsePathStyleChange = { s3UsePathStyle = it },
                                    onBrowseRemote = {
                                        showBrowseDialog = true
                                        // ISSUE-P3-395/396：对象键可能是文件，浏览其父目录
                                        onBrowseS3(
                                            s3Endpoint,
                                            s3Bucket,
                                            s3Region,
                                            drafts.secret(SyncFormDrafts.KEY_S3_ACCESS_KEY).copyOf(),
                                            drafts.secret(SyncFormDrafts.KEY_S3_SECRET_KEY).copyOf(),
                                            com.keepasskey.app.sync.parentDirectoryPath(s3ObjectKey),
                                            s3UsePathStyle,
                                            null
                                        )
                                    }
                                )
                            }
                        }

                        // ISSUE-P3-387：远端目录浏览对话框（段落组件见 RemoteBrowseSection.kt）
                        RemoteBrowseSection(
                            visible = showBrowseDialog,
                            provider = uiState.syncProvider,
                            browseState = browseState,
                            webdavUrl = webdavUrl,
                            webdavUsername = webdavUsername,
                            webdavPasswordChars = drafts.secret(SyncFormDrafts.KEY_WEBDAV_PASSWORD),
                            webdavRemotePath = webdavRemotePath,
                            s3Endpoint = s3Endpoint,
                            s3Bucket = s3Bucket,
                            s3Region = s3Region,
                            s3AccessKeyChars = drafts.secret(SyncFormDrafts.KEY_S3_ACCESS_KEY),
                            s3SecretKeyChars = drafts.secret(SyncFormDrafts.KEY_S3_SECRET_KEY),
                            s3ObjectKey = s3ObjectKey,
                            s3UsePathStyle = s3UsePathStyle,
                            onBrowseWebDav = onBrowseWebDav,
                            onBrowseS3 = onBrowseS3,
                            onSelectFile = { entry ->
                                if (uiState.syncProvider == CloudSyncProvider.WEBDAV) {
                                    webdavRemotePath = entry.path
                                } else {
                                    s3ObjectKey = entry.path
                                }
                                showBrowseDialog = false
                                onDismissBrowse()
                            },
                            // ISSUE-P3-452：保存/首传态——选目录回填 `<目录>/<库名>.kdbx`（可编辑）
                            saveTarget = uiState.databaseName.ifBlank { "KeePass" }.let { "$it.kdbx" },
                            onSelectDirectory = { fullPath ->
                                if (uiState.syncProvider == CloudSyncProvider.WEBDAV) {
                                    webdavRemotePath = fullPath
                                } else {
                                    s3ObjectKey = fullPath
                                }
                                showBrowseDialog = false
                                onDismissBrowse()
                            },
                            onDismissBrowse = {
                                showBrowseDialog = false
                                onDismissBrowse()
                            }
                        )

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
                    onRecoverByKeepingLocal = onRecoverSyncByKeepingLocal,
                    // 先保存表单（含刚输入的密码），再走顺序编排（测连通过则同步），
                    // 让 lastSyncTime 真正写入，避免「徽章已同步 / 下方尚未同步」矛盾
                    onTestConnection = { persistThenContinue(persistConfig, onSyncAfterSave) }
                )
            }

            // 4. 离线缓存与定时后台同步 (KP2A 特性)
            item {
                OfflineSyncSection(
                    uiState = uiState,
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
                    onShowConflictDialog = { showConflictDialog = true }
                )
            }

            // 6. 网络兼容性与传输选项 (KP2A 特性)
            item {
                NetworkOptionsSection(
                    uiState = uiState,
                    onWebdavChunkedUploadToggle = onWebdavChunkedUploadToggle
                )
            }

            // ISSUE-P3-416：零知识说明卡已迁「关于」页（设置项单一位置；ZeroKnowledgeCard 宿主随迁）

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
