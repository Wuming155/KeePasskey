package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
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
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.ui.screens.settings.CloudSyncProvider
import com.keepasskey.app.ui.screens.settings.SettingsUiState
import kotlinx.coroutines.launch

/**
 * 云端同步配置页：
 * - 顶部使用下拉选项框 (ExposedDropdownMenuBox) 选择同步方式（WebDAV vs 兼容 S3 存储）；
 * - 下拉选择后，在下方直接以内嵌输入框形式展示并填写具体的配置信息；
 * - 包含“保存配置”、“测试连接”、“立即同步”以及同步策略开关。
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
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // 下拉框展开状态
    var dropdownExpanded by remember { mutableStateOf(false) }

    // WebDAV 本地可编辑状态（预填充自 uiState）
    var webdavUrl by remember(uiState.webdavUrl) { mutableStateOf(uiState.webdavUrl) }
    var webdavUsername by remember(uiState.webdavUsername) { mutableStateOf(uiState.webdavUsername) }
    var webdavPassword by remember(uiState.webdavPassword) { mutableStateOf(uiState.webdavPassword) }
    var webdavRemotePath by remember(uiState.webdavRemotePath) { mutableStateOf(uiState.webdavRemotePath) }
    var webdavPasswordVisible by remember { mutableStateOf(false) }

    // S3 本地可编辑状态（预填充自 uiState）
    var s3Endpoint by remember(uiState.s3Endpoint) { mutableStateOf(uiState.s3Endpoint) }
    var s3Bucket by remember(uiState.s3Bucket) { mutableStateOf(uiState.s3Bucket) }
    var s3Region by remember(uiState.s3Region) { mutableStateOf(uiState.s3Region) }
    var s3AccessKey by remember(uiState.s3AccessKey) { mutableStateOf(uiState.s3AccessKey) }
    var s3SecretKey by remember(uiState.s3SecretKey) { mutableStateOf(uiState.s3SecretKey) }
    var s3ObjectKey by remember(uiState.s3ObjectKey) { mutableStateOf(uiState.s3ObjectKey) }
    var s3SecretKeyVisible by remember { mutableStateOf(false) }

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
                        text = "云端同步",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
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
            // 1. 同步协议选择（改用下拉选项框）
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "同步方式",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFDD6B20), // KeePassDX 经典橙色分类标题
                        modifier = Modifier.padding(start = 2.dp)
                    )

                    ExposedDropdownMenuBox(
                        expanded = dropdownExpanded,
                        onExpandedChange = { dropdownExpanded = !dropdownExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = uiState.syncProvider.label,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("选择云存储协议") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded)
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
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
                                                style = MaterialTheme.typography.bodyLarge.copy(
                                                    fontWeight = if (uiState.syncProvider == provider) FontWeight.Bold else FontWeight.Normal
                                                ),
                                                color = if (uiState.syncProvider == provider) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = provider.desc,
                                                style = MaterialTheme.typography.bodySmall,
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

            // 2. 具体配置信息填写表单（直接内嵌在下方，无需弹窗）
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = when (uiState.syncProvider) {
                            CloudSyncProvider.WEBDAV -> "WebDAV 服务器连接参数"
                            CloudSyncProvider.S3_COMPATIBLE -> "兼容 S3 对象存储配置参数"
                        },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFDD6B20),
                        modifier = Modifier.padding(start = 2.dp, top = 4.dp)
                    )

                    when (uiState.syncProvider) {
                        CloudSyncProvider.WEBDAV -> {
                            // WebDAV 字段直接编辑填写
                            OutlinedTextField(
                                value = webdavUrl,
                                onValueChange = { webdavUrl = it },
                                label = { Text("服务器地址 (WebDAV URL)") },
                                placeholder = { Text("https://dav.example.com/remote.php/dav/files/user/") },
                                leadingIcon = {
                                    Icon(Icons.Default.CloudQueue, contentDescription = "URL", modifier = Modifier.size(20.dp))
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = webdavUsername,
                                onValueChange = { webdavUsername = it },
                                label = { Text("账户用户名 (Username)") },
                                placeholder = { Text("username") },
                                leadingIcon = {
                                    Icon(Icons.Default.Person, contentDescription = "Username", modifier = Modifier.size(20.dp))
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = webdavPassword,
                                onValueChange = { webdavPassword = it },
                                label = { Text("账户认证密码 (Password)") },
                                placeholder = { Text("密码或应用专用密码") },
                                leadingIcon = {
                                    Icon(Icons.Default.Lock, contentDescription = "Password", modifier = Modifier.size(20.dp))
                                },
                                trailingIcon = {
                                    IconButton(onClick = { webdavPasswordVisible = !webdavPasswordVisible }) {
                                        Icon(
                                            imageVector = if (webdavPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = if (webdavPasswordVisible) "隐藏密码" else "显示密码"
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
                                label = { Text("远程数据库路径 (Remote Path)") },
                                placeholder = { Text("/Passkeys/keepasskey.kdbx") },
                                leadingIcon = {
                                    Icon(Icons.Default.FolderOpen, contentDescription = "Remote Path", modifier = Modifier.size(20.dp))
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        CloudSyncProvider.S3_COMPATIBLE -> {
                            // S3 字段直接编辑填写
                            OutlinedTextField(
                                value = s3Endpoint,
                                onValueChange = { s3Endpoint = it },
                                label = { Text("终端节点 (Endpoint URL)") },
                                placeholder = { Text("https://<account_id>.r2.cloudflarestorage.com") },
                                leadingIcon = {
                                    Icon(Icons.Default.CloudQueue, contentDescription = "Endpoint", modifier = Modifier.size(20.dp))
                                },
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
                                    label = { Text("存储桶 (Bucket)") },
                                    placeholder = { Text("my-secure-vault") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Storage, contentDescription = "Bucket", modifier = Modifier.size(20.dp))
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1.3f)
                                )

                                OutlinedTextField(
                                    value = s3Region,
                                    onValueChange = { s3Region = it },
                                    label = { Text("区域 (Region)") },
                                    placeholder = { Text("auto") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Public, contentDescription = "Region", modifier = Modifier.size(20.dp))
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(0.9f)
                                )
                            }

                            OutlinedTextField(
                                value = s3AccessKey,
                                onValueChange = { s3AccessKey = it },
                                label = { Text("访问密钥 ID (Access Key ID)") },
                                placeholder = { Text("AKIAIOSFODNN7EXAMPLE") },
                                leadingIcon = {
                                    Icon(Icons.Default.VpnKey, contentDescription = "Access Key", modifier = Modifier.size(20.dp))
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = s3SecretKey,
                                onValueChange = { s3SecretKey = it },
                                label = { Text("私有密钥 (Secret Access Key)") },
                                placeholder = { Text("Secret Access Key") },
                                leadingIcon = {
                                    Icon(Icons.Default.Lock, contentDescription = "Secret Key", modifier = Modifier.size(20.dp))
                                },
                                trailingIcon = {
                                    IconButton(onClick = { s3SecretKeyVisible = !s3SecretKeyVisible }) {
                                        Icon(
                                            imageVector = if (s3SecretKeyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = if (s3SecretKeyVisible) "隐藏私钥" else "显示私钥"
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
                                label = { Text("对象路径 (Object Key)") },
                                placeholder = { Text("passwords/master_vault.kdbx") },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = "Object Key", modifier = Modifier.size(20.dp))
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // 保存配置按钮
                    Button(
                        onClick = {
                            when (uiState.syncProvider) {
                                CloudSyncProvider.WEBDAV -> {
                                    onUpdateWebDav(webdavUrl, webdavUsername, webdavPassword, webdavRemotePath)
                                    saveFeedbackText = "WebDAV 参数已成功保存"
                                }
                                CloudSyncProvider.S3_COMPATIBLE -> {
                                    onUpdateS3(s3Endpoint, s3Bucket, s3Region, s3AccessKey, s3SecretKey, s3ObjectKey)
                                    saveFeedbackText = "S3 存储参数已成功保存"
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = "保存配置",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("保存配置", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 3. 当前连接状态与操作按钮（测试连接 / 立即同步）
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudDone,
                                    contentDescription = "同步状态",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "${uiState.syncProvider.label} 云端连接",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = uiState.syncStatusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF2E7D32)
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color(0xFFE8F5E9))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "正常在线",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFF2E7D32)
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
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
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
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = "立即同步",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("立即同步")
                            }
                        }

                        OutlinedButton(
                            onClick = { onTriggerSync() },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.NetworkCheck,
                                contentDescription = "测试连接",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("测试连接")
                        }
                    }
                }
            }

            // 4. 同步触发策略
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "同步触发策略",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFDD6B20),
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
                            icon = Icons.Default.CloudSync,
                            title = "变更自动同步",
                            subtitle = "每次添加、修改或删除凭据后自动推送到云端",
                            checked = uiState.autoSyncEnabled,
                            onCheckedChange = onAutoSyncToggle
                        )

                        SyncSwitchItem(
                            icon = Icons.Default.Wifi,
                            title = "仅在 Wi-Fi 网络下同步",
                            subtitle = "避免移动蜂窝网络下消耗流量与后台请求",
                            checked = uiState.wifiOnlySync,
                            onCheckedChange = onWifiOnlyToggle
                        )
                    }
                }
            }

            // 5. 零知识与端到端加密机制说明
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
                            tint = Color(0xFFDD6B20),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "零知识数据保护机制",
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
        modifier = modifier
    )
}

/**
 * 开关项组件
 */
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
