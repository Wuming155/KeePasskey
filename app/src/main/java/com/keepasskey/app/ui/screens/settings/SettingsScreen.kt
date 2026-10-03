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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.ChangeKeyFileIntent
import com.keepasskey.app.ui.AppSnackbarChannel
import com.keepasskey.app.ui.AppSnackbarEvent
import com.keepasskey.app.ui.screens.unlock.KeyFileReadResult

/**
 * 2026 现代化高阶设置主页（Route）
 * 摆脱传统老旧感，结合柔和色调、卡片式归类与层级结构
 */
@Composable
fun SettingsScreen(
    onNavigateToDatabase: () -> Unit,
    // ISSUE-P3-467：数据导入与导出自数据库属性页拆为独立二级入口（动作流与配置域分离）
    onNavigateToImportExport: () -> Unit,
    onNavigateToSync: () -> Unit,
    onNavigateToAutofill: () -> Unit,
    // ISSUE-P3-432：通行密钥 (Passkey) 独立设置项（CM 凭据管理器通道自自动填充入口拆出）
    onNavigateToPasskey: () -> Unit,
    onNavigateToSecurity: () -> Unit,
    onNavigateToTheme: () -> Unit,
    // ISSUE-P3-467：列表与导航偏好自主题页拆为独立二级入口
    onNavigateToListNav: () -> Unit,
    // ISSUE-P3-444 修订：界面偏好二级入口（自「界面与显示」组第三行进入）
    onNavigateToInterface: () -> Unit,
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
        onNavigateToImportExport = onNavigateToImportExport,
        onNavigateToSync = onNavigateToSync,
        onNavigateToAutofill = onNavigateToAutofill,
        onNavigateToPasskey = onNavigateToPasskey,
        onNavigateToSecurity = onNavigateToSecurity,
        onNavigateToTheme = onNavigateToTheme,
        onNavigateToListNav = onNavigateToListNav,
        onNavigateToInterface = onNavigateToInterface,
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
 *
 * `ISSUE-P3-444` AC③：新增设置项搜索（按关键词过滤分组卡片 + 命中高亮 + 零命中空态）。
 * 分组装配已下沉到 [settingsGroups]（`SettingsSearchSection.kt`）——搜索要求在渲染前就知道
 * 全部行的可搜索文本，故分组改为数据形态；本组件只保留页面壳、搜索态与对话框宿主。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    uiState: SettingsUiState,
    onNavigateToDatabase: () -> Unit,
    // ISSUE-P3-467：数据导入与导出独立二级入口
    onNavigateToImportExport: () -> Unit,
    onNavigateToSync: () -> Unit,
    onNavigateToAutofill: () -> Unit,
    // ISSUE-P3-432：通行密钥 (Passkey) 独立设置项
    onNavigateToPasskey: () -> Unit,
    onNavigateToSecurity: () -> Unit,
    onNavigateToTheme: () -> Unit,
    // ISSUE-P3-467：列表与导航独立二级入口
    onNavigateToListNav: () -> Unit,
    // ISSUE-P3-444 修订：界面偏好二级入口（两个开关已收进该页，主页不再内联）
    onNavigateToInterface: () -> Unit = {},
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
    /**
     * ISSUE-P3-444 AC③：设置项搜索词的**初值**（仅预览 / 截图用）。
     *
     * 生产路径恒取缺省空串（未过滤）；带字面量默认值的是 `String` 而非 `Boolean`，
     * 不属「可见性开关参数」口径，但两条搜索态预览（命中 / 零命中）仍同批补齐。
     */
    initialSearchQuery: String = "",
    onBackClick: () -> Unit = {},
    showBackButton: Boolean = false,
    modifier: Modifier = Modifier
) {
    var showMasterKeyDialog by remember { mutableStateOf(false) }
    // ISSUE-P3-444 AC③：设置项搜索词（页面会话态——设置页是低频页，刻意不持久化，
    // 每次进入以未过滤的完整分组呈现）
    var searchQuery by remember { mutableStateOf(initialSearchQuery) }

    // ISSUE-P2-354 AC③ + ISSUE-P3-359 AC④：换密结果反馈（成功/失败）转发全局通道——
    // 反馈存于 uiState，发出即交外壳唯一宿主呈现（切 Tab 离开也不丢），回执后一次性清位
    uiState.masterKeyChangeFeedback?.let { feedback ->
        LaunchedEffect(feedback) {
            AppSnackbarChannel.trySend(AppSnackbarEvent(feedback))
            onMasterKeyChangeFeedbackShown()
        }
    }

    val actions = SettingsActions(
        onNavigateToDatabase = onNavigateToDatabase,
        onNavigateToImportExport = onNavigateToImportExport,
        onNavigateToSync = onNavigateToSync,
        onNavigateToAutofill = onNavigateToAutofill,
        onNavigateToPasskey = onNavigateToPasskey,
        onNavigateToSecurity = onNavigateToSecurity,
        onNavigateToTheme = onNavigateToTheme,
        onNavigateToListNav = onNavigateToListNav,
        onNavigateToHealth = onNavigateToHealth,
        onNavigateToTotp = onNavigateToTotp,
        onNavigateToDebug = onNavigateToDebug,
        onNavigateToAbout = onNavigateToAbout,
        onOpenMasterKeyDialog = { showMasterKeyDialog = true },
        onNavigateToInterface = onNavigateToInterface
    )
    val groups = settingsGroups(uiState, actions)
    val query = searchQuery
    // AC③ 零命中空态：任一分组的任一行命中即不呈现空态（判定与分组渲染同源同函数）
    val hasMatch = groups.any { group ->
        group.rows.any { SettingsSearch.matchesQuery(query, it.searchTexts) }
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
            SettingsSearchField(query = searchQuery, onQueryChange = { searchQuery = it })

            if (query.isNotBlank() && !hasMatch) {
                SettingsSearchEmptyState()
            } else {
                groups.forEach { group ->
                    SettingsSearchGroup(
                        headerRes = group.headerRes,
                        query = query,
                        rows = group.rows
                    )
                }
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
