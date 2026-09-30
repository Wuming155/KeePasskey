package com.keepasskey.app.ui.screens.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.BuildConfig
import com.keepasskey.app.R
import com.keepasskey.app.ui.AppSnackbarChannel
import com.keepasskey.app.ui.AppSnackbarEvent
import com.keepasskey.app.ui.components.SystemSettingsNavigation
import com.keepasskey.app.ui.theme.LocalSecurityColors

/**
 * 2026 现代化高阶设置主页（Route）
 *
 * ISSUE-P3-410/411：按同类项目 IA 重分桶——改主密码归安全与凭据；
 * 调试与关于分离（生产包不展示调试）；顶部增加状态摘要与系统自动填充深链。
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
    onBackClick: () -> Unit = {},
    showBackButton: Boolean = false,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val hostActivity = LocalActivity.current as? FragmentActivity

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
        onChangeMasterPassword = { viewModel.changeMasterPassword(it, hostActivity) },
        onWeakPasswordConfirmed = viewModel::noteWeakMasterPasswordConfirmed,
        onMasterKeyChangeFeedbackShown = viewModel::clearMasterKeyChangeFeedback,
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
    /** ISSUE-P2-354 AC③：提交新主口令——数组所有权移交 ViewModel（viewModelScope 任务负责清零） */
    onChangeMasterPassword: (CharArray) -> Unit = { chars -> chars.fill('0') },
    /** ISSUE-P2-288：弱主口令显式确认后的留痕回调（不落明文） */
    onWeakPasswordConfirmed: () -> Unit = {},
    /** ISSUE-P2-354 AC③：换密结果反馈经 Snackbar 展示后的一次性清除 */
    onMasterKeyChangeFeedbackShown: () -> Unit = {},
    onBackClick: () -> Unit = {},
    showBackButton: Boolean = false,
    modifier: Modifier = Modifier
) {
    val securityColors = LocalSecurityColors.current
    val context = LocalContext.current
    var showMasterKeyDialog by remember { mutableStateOf(false) }

    uiState.masterKeyChangeFeedback?.let { feedback ->
        LaunchedEffect(feedback) {
            AppSnackbarChannel.trySend(AppSnackbarEvent(feedback))
            onMasterKeyChangeFeedbackShown()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
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
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ModernSectionHeader(title = stringResource(R.string.settings_status_title))
            SettingsGroupCard {
                SettingsStatusRow(
                    icon = Icons.Default.Fingerprint,
                    iconTint = securityColors.passkey,
                    title = stringResource(R.string.settings_status_biometric),
                    value = stringResource(
                        if (uiState.biometricEnabled) {
                            R.string.settings_status_biometric_on
                        } else {
                            R.string.settings_status_biometric_off
                        }
                    ),
                    onClick = onNavigateToSecurity
                )
                SettingsItemDivider()
                SettingsStatusRow(
                    icon = Icons.AutoMirrored.Filled.Assignment,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    title = stringResource(R.string.settings_status_autofill),
                    value = stringResource(
                        if (uiState.autofillServiceEnabled) {
                            R.string.settings_status_autofill_on
                        } else {
                            R.string.settings_status_autofill_off
                        }
                    ),
                    onClick = onNavigateToAutofill,
                    trailingActionLabel = stringResource(R.string.settings_status_autofill_open_system),
                    onTrailingAction = {
                        val intent = SystemSettingsNavigation.autofillServiceIntent(context)
                        if (intent != null) {
                            SystemSettingsNavigation.launchSafely(context, intent)
                        }
                    }
                )
                SettingsItemDivider()
                SettingsStatusRow(
                    icon = Icons.Default.CloudSync,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    title = stringResource(R.string.settings_status_sync),
                    value = uiState.syncStatusText.ifEmpty {
                        stringResource(R.string.settings_status_sync_unknown)
                    },
                    onClick = onNavigateToSync
                )
            }

            // 安全与凭据（ISSUE-P3-410：改主密码归位，不再与存储混排）
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
                    icon = Icons.Default.VpnKey,
                    iconTint = securityColors.warning,
                    title = stringResource(R.string.settings_change_master_key),
                    subtitle = stringResource(R.string.settings_change_master_key_sub),
                    onClick = { showMasterKeyDialog = true }
                )
            }

            // 库与同步
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
                    icon = Icons.Default.HealthAndSafety,
                    iconTint = securityColors.success,
                    title = stringResource(R.string.settings_health),
                    subtitle = stringResource(R.string.settings_health_sub),
                    onClick = onNavigateToHealth
                )
            }

            // 填充与验证码
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
                    icon = Icons.Default.Password,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.set_totp_entry_title),
                    subtitle = stringResource(R.string.set_totp_entry_sub),
                    onClick = onNavigateToTotp
                )
            }

            // 界面与显示
            ModernSectionHeader(title = stringResource(R.string.settings_cat_display))
            SettingsGroupCard {
                ModernSettingsRow(
                    icon = Icons.Default.Palette,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    title = stringResource(R.string.settings_theme),
                    subtitle = stringResource(R.string.settings_theme_sub),
                    onClick = onNavigateToTheme
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 关于与维护（ISSUE-P3-410：调试仅 debug 构建，生产包不展示）
            ModernSectionHeader(title = stringResource(R.string.set_section_system))
            SettingsGroupCard {
                ModernSettingsRow(
                    icon = Icons.Default.Info,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.settings_about),
                    subtitle = stringResource(R.string.set_about_entry_sub, uiState.appVersion),
                    onClick = onNavigateToAbout
                )
                if (BuildConfig.DEBUG) {
                    SettingsItemDivider()
                    ModernSettingsRow(
                        icon = Icons.Default.BugReport,
                        iconTint = securityColors.warning,
                        title = stringResource(R.string.debug_title),
                        subtitle = stringResource(R.string.set_debug_entry_sub),
                        onClick = onNavigateToDebug
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showMasterKeyDialog) {
        MasterKeyChangeDialog(
            kdfAlgorithm = uiState.kdfAlgorithm,
            isBusy = uiState.isChangingMasterKey,
            onChangeMasterPassword = onChangeMasterPassword,
            onWeakPasswordConfirmed = onWeakPasswordConfirmed,
            onDismiss = { showMasterKeyDialog = false }
        )
    }
}
