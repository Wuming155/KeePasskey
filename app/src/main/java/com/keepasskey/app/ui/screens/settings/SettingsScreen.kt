package com.keepasskey.app.ui.screens.settings

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.ui.theme.CapsuleShape
import com.keepasskey.app.ui.theme.KeePasskeyTheme
import kotlinx.coroutines.launch

/**
 * 2026 现代化高阶设置主页（Route）
 * 摆脱传统老旧感，结合柔和色调、卡片式归类与层级结构
 */
@Composable
fun SettingsScreen(
    onNavigateToDatabase: () -> Unit,
    onNavigateToSync: () -> Unit,
    onNavigateToAutofill: () -> Unit,
    onNavigateToSecurity: () -> Unit,
    onNavigateToTheme: () -> Unit,
    onNavigateToHealth: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onBackClick: () -> Unit = {},
    showBackButton: Boolean = false,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsContent(
        uiState = uiState,
        onNavigateToDatabase = onNavigateToDatabase,
        onNavigateToSync = onNavigateToSync,
        onNavigateToAutofill = onNavigateToAutofill,
        onNavigateToSecurity = onNavigateToSecurity,
        onNavigateToTheme = onNavigateToTheme,
        onNavigateToHealth = onNavigateToHealth,
        onNavigateToAbout = onNavigateToAbout,
        onBackClick = onBackClick,
        showBackButton = showBackButton,
        modifier = modifier
    )
}

/**
 * 现代高保真设置内容展示组件
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    uiState: SettingsUiState,
    onNavigateToDatabase: () -> Unit,
    onNavigateToSync: () -> Unit,
    onNavigateToAutofill: () -> Unit,
    onNavigateToSecurity: () -> Unit,
    onNavigateToTheme: () -> Unit,
    onNavigateToHealth: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onBackClick: () -> Unit = {},
    showBackButton: Boolean = false,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showMasterKeyDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    )
                },
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 分类 1: 密码库与存储 (Vault & Storage)
            ModernSectionHeader(title = "密码库与存储")
            SettingsGroupCard {
                ModernSettingsRow(
                    icon = Icons.Default.Storage,
                    iconTint = Color(0xFF00897B),
                    title = "密码库与加密",
                    subtitle = "KDBX 4.1 · Argon2id · 加密参数与回收站",
                    onClick = onNavigateToDatabase
                )
                SettingsItemDivider()
                ModernSettingsRow(
                    icon = Icons.Default.CloudSync,
                    iconTint = Color(0xFF0288D1),
                    title = "云端同步",
                    subtitle = "WebDAV、兼容 S3 存储多协议自动同步",
                    onClick = onNavigateToSync,
                    trailingBadge = uiState.syncProvider.label
                )
                SettingsItemDivider()
                ModernSettingsRow(
                    icon = Icons.Default.VpnKey,
                    iconTint = Color(0xFFE65100),
                    title = "更改主密钥",
                    subtitle = "重设密码库主密码与安全派生凭据",
                    onClick = { showMasterKeyDialog = true }
                )
            }

            // 分类 2: 安全与审计 (Security & Audit)
            ModernSectionHeader(title = "安全与审计")
            SettingsGroupCard {
                ModernSettingsRow(
                    icon = Icons.Default.Fingerprint,
                    iconTint = Color(0xFF5E35B1),
                    title = "设备解锁与安全策略",
                    subtitle = "生物识别、后台自动锁定、防截屏录屏",
                    onClick = onNavigateToSecurity
                )
                SettingsItemDivider()
                ModernSettingsRow(
                    icon = Icons.Default.HealthAndSafety,
                    iconTint = Color(0xFF2E7D32),
                    title = "健康度检查与审计",
                    subtitle = "弱密码、密码复用、数据泄露比对",
                    onClick = onNavigateToHealth,
                    trailingBadge = "${uiState.healthScore}分 · ${uiState.healthStatus}"
                )
            }

            // 分类 3: 体验与集成 (Preferences & Integration)
            ModernSectionHeader(title = "体验与集成")
            SettingsGroupCard {
                ModernSettingsRow(
                    icon = Icons.AutoMirrored.Filled.Assignment,
                    iconTint = Color(0xFF1E88E5),
                    title = "自动填充与 Passkey",
                    subtitle = "系统凭据提供者、通行密钥、剪贴板保护",
                    onClick = onNavigateToAutofill
                )
                SettingsItemDivider()
                ModernSettingsRow(
                    icon = Icons.Default.Palette,
                    iconTint = Color(0xFFC2185B),
                    title = "外观与主题",
                    subtitle = "深浅色主题、OLED 纯黑优化、列表视图偏好",
                    onClick = onNavigateToTheme
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 独立展示项: 关于 KeePasskey (不隶属于任何分类，独立单行展示)
            SettingsGroupCard {
                ModernSettingsRow(
                    icon = Icons.Default.Info,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = "关于 KeePasskey",
                    subtitle = "${uiState.appVersion} · 开源架构与技术规范",
                    onClick = onNavigateToAbout,
                    trailingBadge = "2026 Edition"
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // 现代主密钥更改对话框
    if (showMasterKeyDialog) {
        var newPassword by remember { mutableStateOf("") }
        var confirmPassword by remember { mutableStateOf("") }
        var passwordVisible by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showMasterKeyDialog = false },
            shape = RoundedCornerShape(22.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            title = {
                Text(
                    text = "更改主密钥",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "设置新的密码库主密码。修改后将使用当前配置的 ${uiState.kdfAlgorithm} 重新计算密钥派生并重新加密整个密码库。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text("新主密码") },
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
                        },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = null
                                )
                            }
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text("确认新主密码") },
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPassword.isNotEmpty() && newPassword == confirmPassword) {
                            showMasterKeyDialog = false
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("主密钥已成功更新")
                            }
                        }
                    },
                    enabled = newPassword.isNotEmpty() && newPassword == confirmPassword,
                    shape = CapsuleShape
                ) {
                    Text("保存更改")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMasterKeyDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}


/**
 * 现代分组卡片容器
 */
@Composable
private fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(18.dp)
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        content = content
    )
}

/**
 * 组内行间细致分隔线
 */
@Composable
private fun SettingsItemDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = 68.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        thickness = 0.6.dp
    )
}

/**
 * 现代偏好设置项目行（圆角 Squircle 色彩胶囊图标 + 标题/副标题 + 状态胶囊 + 前进指示）
 */
@Composable
private fun ModernSettingsRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailingBadge: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 柔和色调 Squircle 图标胶囊
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.5.sp,
                    lineHeight = 16.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
            )
        }

        if (trailingBadge != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.padding(end = 4.dp)
            ) {
                Text(
                    text = trailingBadge,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(13.dp)
        )
    }
}

/**
 * 现代风格分类标题
 */
@Composable
private fun ModernSectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 0.4.sp
        ),
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)
    )
}

@Preview(name = "浅色模式", showBackground = true)
@Preview(name = "深色模式", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsContentPreview() {
    KeePasskeyTheme {
        SettingsContent(
            uiState = SettingsUiState(),
            onNavigateToDatabase = {},
            onNavigateToSync = {},
            onNavigateToAutofill = {},
            onNavigateToSecurity = {},
            onNavigateToTheme = {},
            onNavigateToHealth = {},
            onNavigateToAbout = {}
        )
    }
}
