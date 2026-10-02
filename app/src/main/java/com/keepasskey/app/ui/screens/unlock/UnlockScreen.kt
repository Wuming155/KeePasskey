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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.keepasskey.app.ui.screens.database.DatabasePickerViewModel
import com.keepasskey.app.ui.theme.AppThemeMode
import com.keepasskey.app.ui.theme.HeroTitleStyle
import com.keepasskey.app.ui.unwrapToFragmentActivity

/**
 * 有状态解锁页面（Route），负责收集 ViewModel 状态与事件转发
 */
@Composable
fun UnlockScreen(
    currentTheme: AppThemeMode,
    onThemeToggle: () -> Unit,
    onUnlockSuccess: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToDatabasePicker: () -> Unit = {},
    viewModel: UnlockViewModel = hiltViewModel(),
    /**
     * 用户裁决 2026-10-01：空状态两枚入口**就地弹框**，不再跳转库管理页——原直达路径
     * 会在弹框背后露出库管理页自带的同义入口（嵌套重复）。对话框组件与业务动作复用
     * 库管理页同一套实现，承载见 [UnlockVaultDialogsHost]。
     */
    pickerViewModel: DatabasePickerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    // §411 装机走查 P1 修复：本地化上下文是 ContextWrapper（§409）——`as? FragmentActivity`
    // 直转必然失败 → 全部生物识别入口 fail-closed「验证未通过或已取消」。必须沿链解包。
    val activity = androidx.compose.runtime.remember(context) { context.unwrapToFragmentActivity() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is UnlockEvent.UnlockSuccess -> onUnlockSuccess()
            }
        }
    }

    // ISSUE-P3-117：接线「离开密码页清空已输入字符」开关——页面进入后台（ON_STOP）或离开组合
    // （onDispose）时通知 ViewModel 清空**未提交**的主密码缓冲区；是否真的清由该开关与
    // ViewModel 内的偏好判定决定（Screen 不做业务判断，只透传生命周期事件）。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) viewModel.onScreenLeft()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onScreenLeft()
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

    // ISSUE-P2-424 AC①：空状态「导入已有库」不再走本页仅本地的 SAF 导入器，统一跳转
    // 密码库管理页并自动展开三来源导入对话框（本地 / WebDAV / S3，云账号预填）——
    // 文案「从本机存储或云盘导入」自此与行为一致，云端打开能力不再缺失

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
        onDowngradeDecision = viewModel::onQuickUnlockDowngradeDecision,
        onNavigateToDatabasePicker = onNavigateToDatabasePicker,
        onOpenExistingVault = pickerViewModel::openOpenSourceDialog,
        onCreateNewVault = pickerViewModel::openCreateDialog,
        modifier = modifier
    )

    // 空状态两枚入口的就地对话框宿主（新建向导 / 三来源导入 / 密钥文件一次性交付）
    UnlockVaultDialogsHost(pickerViewModel)
}

/**
 * 无状态解锁内容渲染组件 (集成 QuickUnlock 状态与感知)
 */
// imePadding 为 Compose 官方 API 名，拼写检查误报
@Suppress("SpellCheckingInspection")
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
    modifier: Modifier = Modifier,
    onDowngradeDecision: (Boolean) -> Unit = {},
    onNavigateToDatabasePicker: () -> Unit,
    onOpenExistingVault: () -> Unit,
    /** 用户裁决 2026-10-01：空状态「新建密码库」就地在解锁页弹新建向导（与「切换库」分流） */
    onCreateNewVault: () -> Unit = onNavigateToDatabasePicker
) {
    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ISSUE-P3-349：顶部品牌渐变锚点——primaryContainer → 透明的静态幕布，
        // 为锁圆 / 标题提供视觉锚，消除顶部约 1/3 的无层次空白。
        // 取色仅用语义令牌（动态取色 / OLED 盘自动跟随），非常驻动画、零 GPU 常耗。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .background(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0f)
                        )
                    )
                )
        )

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
                    onOpenExistingVault = onOpenExistingVault,
                    onCreateNewVault = onCreateNewVault
                )
            } else {
                Text(
                    text = if (uiState.unlockMode == UnlockMode.QUICK_UNLOCK) stringResource(R.string.unlock_quick_title) else stringResource(R.string.unlock_title),
                    style = HeroTitleStyle,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    // ISSUE-P2-285 AC①：硬件声明按实测安全等级条件渲染——软件级降级态
                    // （quickUnlockDowngraded，P1-22 实测回填）下如实呈现「无硬件隔离」，
                    // 禁硬件 / 软件一律渲染硬件文案
                    text = when {
                        uiState.unlockMode != UnlockMode.QUICK_UNLOCK -> stringResource(R.string.unlock_subtitle)
                        uiState.quickUnlockDowngraded -> stringResource(R.string.unlock_quick_subtitle_software)
                        else -> stringResource(R.string.unlock_quick_subtitle)
                    },
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

                // ISSUE-P3-438：上次会话未正常关闭（进程死亡时库仍处于解锁态）的一次性轻提示——
                // 文案如实中性，不渲染为错误告警（onSurfaceVariant，区别于下方丢弃编辑的 error 色）
                if (uiState.lastSessionAbnormalCloseNotice) {
                    Text(
                        text = stringResource(R.string.unlock_last_session_abnormal_close),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // ISSUE-P2-355 AC③：锁定丢弃未保存编辑的一次性告知（UnlockViewModel.init 消费注册表后置位）
                if (uiState.unsavedEditsDiscardedNotice) {
                    Text(
                        text = stringResource(R.string.unlock_unsaved_edits_discarded),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // QuickUnlock 模式与完整解锁模式切换
                if (uiState.unlockMode == UnlockMode.QUICK_UNLOCK) {
                    UnlockQuickUnlockCard(
                        uiState = uiState,
                        onBiometricUnlock = onBiometricUnlock,
                        onSwitchMode = onSwitchMode,
                        onToggleReadOnly = onToggleReadOnly
                    )
                } else {
                    // ISSUE-P3-215 曾在此承载无障碍提示、后迁设置页；ISSUE-P3-324 该提示与
                    // 整条信号链路一并移除（含系统预装服务的口径对未开无障碍用户构成假提示），
                    // 解锁页同样不得回潮渲染任何无障碍状态卡。
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

    // ISSUE-P1-22：软件级 Keystore 快速解锁降级确认弹窗——未经用户显式确认不建立封印
    if (uiState.quickUnlockDowngradeConsentPending) {
        QuickUnlockDowngradeConsentDialog(
            onConfirm = { onDowngradeDecision(true) },
            onDecline = { onDowngradeDecision(false) }
        )
    }
}

/**
 * ISSUE-P1-22：软件级 Keystore 快速解锁降级确认弹窗（AC②：建立封印前给出明确风险提示，
 * 用户显式选择后方可封印；取消 / 关闭弹窗一律按「不启用」处理，fail-closed 不留确认记录）。
 */
@Composable
private fun QuickUnlockDowngradeConsentDialog(
    onConfirm: () -> Unit,
    onDecline: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(text = stringResource(R.string.unlock_downgrade_dialog_title)) },
        text = { Text(text = stringResource(R.string.unlock_downgrade_dialog_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.unlock_downgrade_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(text = stringResource(R.string.unlock_downgrade_decline))
            }
        }
    )
}
