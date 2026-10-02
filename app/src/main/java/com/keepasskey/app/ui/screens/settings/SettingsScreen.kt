package com.keepasskey.app.ui.screens.settings

import android.content.res.Configuration
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
import androidx.compose.material.icons.filled.Key
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
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.BuildConfig
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.ChangeKeyFileIntent
import com.keepasskey.app.ui.AppSnackbarChannel
import com.keepasskey.app.ui.AppSnackbarEvent
import com.keepasskey.app.ui.screens.unlock.KeyFileReadResult
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
    // ISSUE-P3-432：通行密钥 (Passkey) 独立设置项（CM 凭据管理器通道自自动填充入口拆出）
    onNavigateToPasskey: () -> Unit,
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
    // ISSUE-P2-398：改密成功后的重封印弹窗宿主。经 `LocalActivity` 直取（而非
    // `LocalContext.current as? Activity`）——后者触发 AndroidLint `ContextCastToActivity`，
    // 且 LocalActivity 已由宿主 Activity 精确提供（与 SecuritySettingsScreen 同一口径）
    val hostActivity = LocalActivity.current as? FragmentActivity

    SettingsContent(
        uiState = uiState,
        onNavigateToDatabase = onNavigateToDatabase,
        onNavigateToSync = onNavigateToSync,
        onNavigateToAutofill = onNavigateToAutofill,
        onNavigateToPasskey = onNavigateToPasskey,
        onNavigateToSecurity = onNavigateToSecurity,
        onNavigateToTheme = onNavigateToTheme,
        onNavigateToHealth = onNavigateToHealth,
        onNavigateToTotp = onNavigateToTotp,
        onNavigateToDebug = onNavigateToDebug,
        onNavigateToAbout = onNavigateToAbout,
        onChangeMasterPassword = { chars, intent ->
            viewModel.masterKeyChangeController.submit(chars, intent, hostActivity)
        },
        onReadKeyFile = viewModel.keyFileReader,
        onWeakPasswordConfirmed = { viewModel.noteWeakMasterPasswordConfirmed() },
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
    // ISSUE-P3-432：通行密钥 (Passkey) 独立设置项
    onNavigateToPasskey: () -> Unit,
    onNavigateToSecurity: () -> Unit,
    onNavigateToTheme: () -> Unit,
    onNavigateToHealth: () -> Unit,
    onNavigateToTotp: () -> Unit = {},
    onNavigateToDebug: () -> Unit = {},
    onNavigateToAbout: () -> Unit,
    /**
     * ISSUE-P2-354 AC③：提交新主口令与密钥文件意图——数组所有权移交 ViewModel
     * （viewModelScope 任务负责清零）；ISSUE-P3-428：`Use` 字节清零责任同移交。
     */
    onChangeMasterPassword: (CharArray, ChangeKeyFileIntent) -> Unit = { chars, _ -> chars.fill('0') },
    /** ISSUE-P3-428：改密对话框「绑定/更换」的密钥文件读取通道（全仓唯一 SAF 读取） */
    onReadKeyFile: suspend (String) -> KeyFileReadResult = { KeyFileReadResult.Unreadable },
    /** ISSUE-P2-288：弱主口令显式确认后的留痕回调（不落明文） */
    onWeakPasswordConfirmed: () -> Unit = {},
    /** ISSUE-P2-354 AC③：换密反馈经 Snackbar 展示后的一次性清除 */
    onMasterKeyChangeFeedbackShown: () -> Unit = {},
    onBackClick: () -> Unit = {},
    showBackButton: Boolean = false,
    modifier: Modifier = Modifier
) {
    val securityColors = LocalSecurityColors.current
    var showMasterKeyDialog by remember { mutableStateOf(false) }

    // ISSUE-P2-354 AC③ + ISSUE-P3-359 AC④：换密结果反馈（成功/失败）转发全局通道——
    // 反馈存于 uiState，发出即交外壳唯一宿主呈现（切 Tab 离开也不丢），回执后一次性清位
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
                SettingsItemDivider()
                // ISSUE-P3-413：凭据操作归安全域——六路调研无一家把改密与存储/同步并列（keepass2android 归 Database security、Bitwarden 归 Account security）
                ModernSettingsRow(
                    icon = Icons.Default.VpnKey,
                    iconTint = securityColors.warning,
                    title = stringResource(R.string.settings_change_master_key),
                    subtitle = stringResource(R.string.settings_change_master_key_sub),
                    onClick = { showMasterKeyDialog = true }
                )
            }

            // 分类 3: 自动填充 (Autofill)——ISSUE-P3-432：自动填充与通行密钥拆为两个设置项
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
                    icon = Icons.Default.Key,
                    iconTint = securityColors.passkey,
                    title = stringResource(R.string.settings_passkey),
                    subtitle = stringResource(R.string.settings_passkey_sub),
                    onClick = onNavigateToPasskey
                )
            }

            // 分类 3b: 界面与显示 (Interface & Display)——ISSUE-P3-413：外观独立成组，不与自动填充混排
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

            // 分类 4: 系统维护与关于 (System, Maintenance & About)
            ModernSectionHeader(title = stringResource(R.string.set_section_system))
            SettingsGroupCard {
                // ISSUE-P3-413：诊断日志闸门（DiagnosticLogGate）含 BuildConfig.DEBUG，release 日志缓冲恒空、导出恒禁用，入口仅 debug 构建露出
                if (BuildConfig.DEBUG) {
                    ModernSettingsRow(
                        icon = Icons.Default.BugReport,
                        iconTint = securityColors.warning,
                        title = stringResource(R.string.debug_title),
                        subtitle = stringResource(R.string.set_debug_entry_sub),
                        onClick = onNavigateToDebug
                    )
                    SettingsItemDivider()
                }
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

    // 现代主密钥更改对话框（ISSUE-P2-354 AC③：busy 下行自 uiState，完成才自行关闭）
    if (showMasterKeyDialog) {
        MasterKeyChangeDialog(
            kdfAlgorithm = uiState.kdfAlgorithm,
            isBusy = uiState.isChangingMasterKey,
            onChangeMasterPassword = onChangeMasterPassword,
            onReadKeyFile = onReadKeyFile,
            onDismiss = { showMasterKeyDialog = false },
            onWeakPasswordConfirmed = onWeakPasswordConfirmed
        )
    }
}

// P3-23：以下 Preview name 为 IDE 预览标注（仅开发期可见，非运行时 UI），保留原样
@Preview(name = "浅色模式", showBackground = true)
@Preview(name = "深色模式", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun SettingsContentPreview() {
    KeePasskeyTheme {
        SettingsContent(
            uiState = SettingsUiState(),
            onNavigateToDatabase = {},
            onNavigateToSync = {},
            onNavigateToAutofill = {},
            onNavigateToPasskey = {},
            onNavigateToSecurity = {},
            onNavigateToTheme = {},
            onNavigateToHealth = {},
            onNavigateToTotp = {},
            onNavigateToDebug = {},
            onNavigateToAbout = {}
        )
    }
}

/**
 * `ISSUE-P3-340`：`showBackButton = true` 那一态此前从未被预览画过（默认 `false` 态才是）。
 * 单独开一个预览函数而不是在同一张图里叠两个整屏：整屏组件叠在一起会把各自的高度都压没，
 * 导出的 PNG 也就无从比对。
 */
@Preview(name = "浅色模式-带返回键", showBackground = true)
@Preview(name = "深色模式-带返回键", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun SettingsContentWithBackPreview() {
    KeePasskeyTheme {
        SettingsContent(
            uiState = SettingsUiState(),
            onNavigateToDatabase = {},
            onNavigateToSync = {},
            onNavigateToAutofill = {},
            onNavigateToPasskey = {},
            onNavigateToSecurity = {},
            onNavigateToTheme = {},
            onNavigateToHealth = {},
            onNavigateToTotp = {},
            onNavigateToDebug = {},
            onNavigateToAbout = {},
            showBackButton = true
        )
    }
}
