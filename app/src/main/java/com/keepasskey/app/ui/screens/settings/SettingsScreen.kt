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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Storage
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.SecurePasswordField
import com.keepasskey.app.ui.theme.CapsuleShape
import com.keepasskey.app.ui.theme.KeePasskeyTheme
import com.keepasskey.app.ui.theme.LocalSecurityColors
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
    onNavigateToTotp: () -> Unit = {},
    onNavigateToDebug: () -> Unit = {},
    onNavigateToAbout: () -> Unit,
    onLockClick: () -> Unit = {},
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
        onNavigateToTotp = onNavigateToTotp,
        onNavigateToDebug = onNavigateToDebug,
        onNavigateToAbout = onNavigateToAbout,
        onLockClick = onLockClick,
        onChangeMasterPassword = { viewModel.changeMasterPassword(it) },
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
    onNavigateToTotp: () -> Unit = {},
    onNavigateToDebug: () -> Unit = {},
    onNavigateToAbout: () -> Unit,
    onLockClick: () -> Unit = {},
    onChangeMasterPassword: suspend (CharArray) -> com.keepasskey.core.result.KdbxResult<Unit> = { com.keepasskey.core.result.KdbxResult.Success(Unit) },
    onBackClick: () -> Unit = {},
    showBackButton: Boolean = false,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val securityColors = LocalSecurityColors.current
    val masterKeyUpdatedMsg = stringResource(R.string.set_master_key_updated)
    var showMasterKeyDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
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
                                contentDescription = stringResource(R.string.cd_back)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onLockClick) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = stringResource(R.string.cd_lock),
                            tint = MaterialTheme.colorScheme.error
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 分类 1: 密码库与存储 (Vault & Storage)
            ModernSectionHeader(title = stringResource(R.string.settings_cat_storage))
            SettingsGroupCard {
                ModernSettingsRow(
                    icon = Icons.Default.Storage,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.settings_database),
                    subtitle = stringResource(R.string.settings_database_sub),
                    onClick = onNavigateToDatabase
                )
                SettingsItemDivider()
                ModernSettingsRow(
                    icon = Icons.Default.CloudSync,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    title = stringResource(R.string.settings_sync),
                    subtitle = stringResource(R.string.settings_sync_sub),
                    onClick = onNavigateToSync
                )
                SettingsItemDivider()
                ModernSettingsRow(
                    icon = Icons.Default.VpnKey,
                    iconTint = securityColors.warning,
                    title = stringResource(R.string.settings_change_master_key),
                    subtitle = stringResource(R.string.settings_change_master_key_sub),
                    onClick = { showMasterKeyDialog = true }
                )
            }

            // 分类 2: 设备安全与两步验证 (Security & Authentication)
            ModernSectionHeader(title = stringResource(R.string.settings_cat_security))
            SettingsGroupCard {
                ModernSettingsRow(
                    icon = Icons.Default.Fingerprint,
                    iconTint = securityColors.passkey,
                    title = stringResource(R.string.settings_security),
                    subtitle = stringResource(R.string.settings_security_sub),
                    onClick = onNavigateToSecurity
                )
                SettingsItemDivider()
                ModernSettingsRow(
                    icon = Icons.Default.Password,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.set_totp_entry_title),
                    subtitle = stringResource(R.string.set_totp_entry_sub),
                    onClick = onNavigateToTotp
                )
                SettingsItemDivider()
                ModernSettingsRow(
                    icon = Icons.Default.HealthAndSafety,
                    iconTint = securityColors.success,
                    title = stringResource(R.string.settings_health),
                    subtitle = stringResource(R.string.settings_health_sub),
                    onClick = onNavigateToHealth
                )
            }

            // 分类 3: 自动填充与个性化偏好 (Autofill & Preferences)
            ModernSectionHeader(title = stringResource(R.string.settings_cat_preferences))
            SettingsGroupCard {
                ModernSettingsRow(
                    icon = Icons.AutoMirrored.Filled.Assignment,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    title = stringResource(R.string.settings_autofill),
                    subtitle = stringResource(R.string.settings_autofill_sub),
                    onClick = onNavigateToAutofill
                )
                SettingsItemDivider()
                ModernSettingsRow(
                    icon = Icons.Default.Palette,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    title = stringResource(R.string.settings_theme),
                    subtitle = stringResource(R.string.settings_theme_sub),
                    onClick = onNavigateToTheme
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 分类 4: 系统维护与关于 (System, Maintenance & About)
            ModernSectionHeader(title = stringResource(R.string.set_section_system))
            SettingsGroupCard {
                ModernSettingsRow(
                    icon = Icons.Default.BugReport,
                    iconTint = securityColors.warning,
                    title = stringResource(R.string.debug_title),
                    subtitle = stringResource(R.string.set_debug_entry_sub),
                    onClick = onNavigateToDebug
                )
                SettingsItemDivider()
                ModernSettingsRow(
                    icon = Icons.Default.Info,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.settings_about),
                    subtitle = stringResource(R.string.set_about_entry_sub, uiState.appVersion),
                    onClick = onNavigateToAbout
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // 现代主密钥更改对话框
    if (showMasterKeyDialog) {
        // M3 整改（加解密审查 2026-09）：新主密码以 CharArray 承载（SecurePasswordField 桥接），
        // 不进入 String 状态——String 副本不可擦除且驻留堆内存
        var newPasswordChars by remember { mutableStateOf(CharArray(0)) }
        var confirmPasswordChars by remember { mutableStateOf(CharArray(0)) }
        var passwordVisible by remember { mutableStateOf(false) }

        // 对话框关闭（确认/取消/点按外部）即擦除；下游 changeCredentials 不擦调用方数组，
        // 提交副本的擦除责任由本对话框承担
        fun wipeDialogPasswords() {
            newPasswordChars.fill('0')
            newPasswordChars = CharArray(0)
            confirmPasswordChars.fill('0')
            confirmPasswordChars = CharArray(0)
        }

        val passwordsMatch = newPasswordChars.isNotEmpty() &&
            newPasswordChars.contentEquals(confirmPasswordChars)

        AlertDialog(
            onDismissRequest = {
                wipeDialogPasswords()
                showMasterKeyDialog = false
            },
            shape = RoundedCornerShape(22.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            title = {
                Text(
                    text = stringResource(R.string.settings_change_master_key),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.set_master_key_dialog_desc, uiState.kdfAlgorithm),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    SecurePasswordField(
                        label = stringResource(R.string.set_new_master_password),
                        onPasswordChanged = { chars ->
                            newPasswordChars.fill('0')
                            newPasswordChars = chars.copyOf()
                        },
                        isPasswordVisible = passwordVisible,
                        onToggleVisibility = { passwordVisible = !passwordVisible }
                    )

                    SecurePasswordField(
                        label = stringResource(R.string.set_confirm_master_password),
                        onPasswordChanged = { chars ->
                            confirmPasswordChars.fill('0')
                            confirmPasswordChars = chars.copyOf()
                        },
                        isPasswordVisible = passwordVisible,
                        onToggleVisibility = { passwordVisible = !passwordVisible }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (passwordsMatch) {
                            val pwdChars = newPasswordChars.copyOf()
                            wipeDialogPasswords()
                            showMasterKeyDialog = false
                            coroutineScope.launch {
                                try {
                                    val result = onChangeMasterPassword(pwdChars)
                                    when (result) {
                                        is com.keepasskey.core.result.KdbxResult.Success<*> -> {
                                            snackbarHostState.showSnackbar(masterKeyUpdatedMsg)
                                        }
                                        is com.keepasskey.core.result.KdbxResult.Failure -> {
                                            snackbarHostState.showSnackbar(result.message)
                                        }
                                    }
                                } finally {
                                    // M3 整改：提交副本在任何结果路径用毕即清零
                                    pwdChars.fill('0')
                                }
                            }
                        }
                    },
                    enabled = passwordsMatch,
                    shape = CapsuleShape
                ) {
                    Text(stringResource(R.string.set_save_changes))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    wipeDialogPasswords()
                    showMasterKeyDialog = false
                }) {
                    Text(stringResource(R.string.btn_cancel))
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

// P3-23：以下 Preview name 为 IDE 预览标注（仅开发期可见，非运行时 UI），保留原样
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
            onNavigateToTotp = {},
            onNavigateToDebug = {},
            onNavigateToAbout = {}
        )
    }
}