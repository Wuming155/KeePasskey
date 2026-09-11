package com.keepasskey.app.ui.screens.unlock

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.components.ThemeToggleCapsule
import com.keepasskey.app.ui.theme.AppThemeMode
import com.keepasskey.app.ui.theme.HeroTitleStyle

/**
 * 有状态解锁页面（Route），负责收集 ViewModel 状态与事件转发
 */
@Composable
fun UnlockScreen(
    currentTheme: AppThemeMode,
    onThemeToggle: () -> Unit,
    onUnlockSuccess: () -> Unit,
    onNavigateToDatabasePicker: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: UnlockViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val onToggleReadOnly = viewModel::onToggleReadOnly
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = androidx.compose.runtime.remember(context) { context as? androidx.fragment.app.FragmentActivity }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is UnlockEvent.UnlockSuccess -> onUnlockSuccess()
            }
        }
    }

    // ISSUE-P3-01 整改：进入解锁页的生物识别自动唤起收敛为「ViewModel 显式一次性意图」。
    // 此处只透传状态位（Screen 不做业务判断），条件判定与「已消费」守卫全部在 ViewModel：
    // - pending 由 ViewModel 在「开关开启 + 存在已封印凭据 + 活动库 + 快速解锁模式」时置位，
    //   且在数据库流/设置流任一路径抵达后重算，判定与两条异步源的抵达顺序无关
    //   （原实现只在设置流抵达时用当时的可用性算一次，先到者定格终态 → 真机第二次解锁不弹窗）；
    // - 意图消费后状态机进入 CONSUMED 终态，PENDING 无法再从 CONSUMED 抵达，
    //   因此重组、切后台回前台、onResume 类钩子都不可能重复弹窗（死循环来源已被结构性消除）；
    // - ViewModel 对非 PENDING 状态幂等空操作，故本调用可无条件透传。
    LaunchedEffect(uiState.biometricAutoPrompt) {
        viewModel.onBiometricAutoPromptRequested(activity)
    }

    // 修复虚假开关整改：真实 SAF 选择器——密钥文件字节立即读入内存交给 ViewModel，
    // 不做任何路径/文件名假填充；读取失败显式反馈，绝不静默忽略
    // TASK-39 整改：SAF 流读取与 DISPLAY_NAME 查询均为阻塞 IO，移至 Dispatchers.IO
    // 执行（原实现在主线程回调内同步读取，大文件/慢提供方会卡死 UI 线程）
    // ISSUE-P3-04 整改：读取/上限/擦除/持久化读授权全部下沉 ViewModel 侧 KeyFileAccess
    // （Compose 层零业务逻辑，且 SAF 读取实现全仓唯一）；组合层只上传 SAF 返回的 Uri。
    val keyFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.onKeyFileSelected(uri.toString())
    }

    val kdbxImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val displayName = querySafDisplayName(context, uri)
        viewModel.importExternalDatabase(displayName, uri.toString())
    }

    UnlockContent(
        uiState = uiState,
        currentTheme = currentTheme,
        onThemeToggle = onThemeToggle,
        onPasswordChange = viewModel::onPasswordChangeSecure,
        onTogglePasswordVisibility = viewModel::onTogglePasswordVisibility,
        onSelectKeyFile = { keyFilePickerLauncher.launch(arrayOf("*/*")) },
        onClearKeyFile = viewModel::clearKeyFile,
        onToggleReadOnly = viewModel::onToggleReadOnly,
        onSwitchMode = viewModel::switchUnlockMode,
        onUnlock = { viewModel.unlock(activity) },
        onBiometricUnlock = { viewModel.unlockWithBiometric(activity) },
        onNavigateToDatabasePicker = onNavigateToDatabasePicker,
        onOpenExistingVault = { kdbxImportLauncher.launch(arrayOf("*/*")) },
        modifier = modifier
    )
}

/**
 * 无状态解锁内容渲染组件 (集成 QuickUnlock 状态与感知)
 */
@Composable
fun UnlockContent(
    uiState: UnlockUiState,
    currentTheme: AppThemeMode,
    onThemeToggle: () -> Unit,
    onPasswordChange: (CharArray) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onSelectKeyFile: () -> Unit,
    onClearKeyFile: () -> Unit,
    onToggleReadOnly: () -> Unit,
    onSwitchMode: (UnlockMode) -> Unit,
    onUnlock: () -> Unit,
    onBiometricUnlock: () -> Unit,
    onNavigateToDatabasePicker: () -> Unit,
    onOpenExistingVault: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 右上角快速主题切换
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, end = 20.dp),
            horizontalArrangement = Arrangement.End
        ) {
            ThemeToggleCapsule(
                currentTheme = currentTheme,
                onThemeToggle = onThemeToggle
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                // P0 整改：官方 edge-to-edge 约束下 imePadding 必须置于 verticalScroll 之前，
                // 使滚动容器先被 IME 压缩高度再滚动；置于其后会导致容器不参与避让、输入框被键盘遮挡。
                .imePadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(10.dp))

            UnlockVaultLogo(uiState = uiState)

            Spacer(modifier = Modifier.height(16.dp))

            if (!uiState.hasDatabase) {
                UnlockEmptyVaultContent(
                    uiState = uiState,
                    onNavigateToDatabasePicker = onNavigateToDatabasePicker,
                    onOpenExistingVault = onOpenExistingVault
                )
            } else {
                Text(
                    text = if (uiState.unlockMode == UnlockMode.QUICK_UNLOCK) stringResource(R.string.unlock_quick_title) else stringResource(R.string.unlock_title),
                    style = HeroTitleStyle,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = if (uiState.unlockMode == UnlockMode.QUICK_UNLOCK) stringResource(R.string.unlock_quick_subtitle) else stringResource(R.string.unlock_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(20.dp))

                // 当前数据库概要条目
                BentoCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { onNavigateToDatabasePicker() },
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = stringResource(R.string.cd_active_database),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = uiState.databaseName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = uiState.databaseStatus,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        TextButton(onClick = onNavigateToDatabasePicker) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.unlock_switch_vault), fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // QuickUnlock 模式与完整解锁模式切换
                if (uiState.unlockMode == UnlockMode.QUICK_UNLOCK) {
                    UnlockQuickUnlockCard(
                        uiState = uiState,
                        onBiometricUnlock = onBiometricUnlock,
                        onSwitchMode = onSwitchMode
                    )
                } else {
                    UnlockStandardUnlockContent(
                        uiState = uiState,
                        onPasswordChange = onPasswordChange,
                        onTogglePasswordVisibility = onTogglePasswordVisibility,
                        onSelectKeyFile = onSelectKeyFile,
                        onClearKeyFile = onClearKeyFile,
                        onToggleReadOnly = onToggleReadOnly,
                        onUnlock = onUnlock,
                        onSwitchMode = onSwitchMode
                    )
                }
            }
        }
    }
}
