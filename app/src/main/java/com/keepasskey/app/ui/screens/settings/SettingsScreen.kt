package com.keepasskey.app.ui.screens.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.R
import com.keepasskey.app.ui.theme.KeePasskeyTheme
import com.keepasskey.app.ui.theme.LocalSecurityColors

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
        MasterKeyChangeDialog(
            kdfAlgorithm = uiState.kdfAlgorithm,
            coroutineScope = coroutineScope,
            snackbarHostState = snackbarHostState,
            masterKeyUpdatedMsg = masterKeyUpdatedMsg,
            onDismiss = { showMasterKeyDialog = false },
            onChangeMasterPassword = onChangeMasterPassword
        )
    }
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
