package com.keepasskey.app.ui.screens.unlock

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.components.ThemeToggleCapsule
import com.keepasskey.app.ui.theme.AppThemeMode
import com.keepasskey.app.ui.theme.CapsuleShape
import com.keepasskey.app.ui.theme.HeroTitleStyle
import com.keepasskey.app.ui.components.SecurePasswordField

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

            // 密码库锁 Logo 与呼吸光晕底座
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                Color.Transparent
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (!uiState.hasDatabase) Icons.Default.Lock else if (uiState.unlockMode == UnlockMode.QUICK_UNLOCK) Icons.Default.FlashOn else Icons.Default.Lock,
                        contentDescription = stringResource(R.string.cd_vault_locked),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (!uiState.hasDatabase) {
                // 空状态：当前未配置或选择任何密码库
                Text(
                    text = stringResource(R.string.unlock_empty_vault_title),
                    style = HeroTitleStyle,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = stringResource(R.string.unlock_empty_vault_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                uiState.errorMessage?.let { message ->
                    Text(
                        text = message.resolveText(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                Button(
                    onClick = onNavigateToDatabasePicker,
                    shape = CapsuleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.unlock_empty_create_btn),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                androidx.compose.material3.OutlinedButton(
                    onClick = onOpenExistingVault,
                    shape = CapsuleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.unlock_empty_open_btn),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
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
                    // QuickUnlock 卡片区域 (KP2A / KeePassDX 风格)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // H1 整改：仅在拿到真实数据时展示，不再渲染写死的假硬件声明/假剩余时长
                            if (uiState.hardwareBackedSecurity.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = uiState.hardwareBackedSecurity,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            Spacer(modifier = Modifier.height(16.dp))

                            // ISSUE-P1-08 统一快速解锁：仅 Class 3 强生物识别经硬件密钥解封（锁屏凭据不再可解封）——
                            // 认证入口由系统 BiometricPrompt 承载，不再提供自研 PIN 输入
                            Button(
                                onClick = onBiometricUnlock,
                                enabled = !uiState.isLoading,
                                shape = CapsuleShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                            ) {
                                if (uiState.isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.5.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Fingerprint,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.unlock_biometric_primary_btn),
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            TextButton(
                                onClick = { onSwitchMode(UnlockMode.STANDARD) },
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) {
                                Text(stringResource(R.string.unlock_switch_to_full), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                } else {
                    // 完整主密码输入框（SecurePasswordField：显示 String 仅存活于组件内部，CharArray 直达 ViewModel）
                    SecurePasswordField(
                        label = stringResource(R.string.unlock_master_password),
                        placeholder = stringResource(R.string.unlock_master_password_hint),
                        onPasswordChanged = onPasswordChange,
                        isError = uiState.errorMessage != null,
                        supportingText = {
                            uiState.errorMessage?.let { message ->
                                Text(
                                    text = message.resolveText(),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            uiState.infoMessage?.let { message ->
                                Text(
                                    text = message.resolveText(),
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        },
                        isPasswordVisible = uiState.isPasswordVisible,
                        onToggleVisibility = onTogglePasswordVisibility,
                        onDone = onUnlock,
                        // ISSUE-P1-04：失败/锁定后令牌递增，驱动输入框擦除显示态，与 VM 主密码清零同步
                        wipeToken = uiState.clearPasswordFieldToken,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 附加密钥文件切换（修复虚假开关整改：开启即唤起真实 SAF 选择器，关闭即擦除字节）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable {
                                if (uiState.hasKeyFile) onClearKeyFile() else onSelectKeyFile()
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = stringResource(R.string.unlock_keyfile),
                            tint = if (uiState.hasKeyFile) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.unlock_keyfile),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (uiState.hasKeyFile && uiState.keyFileName.isNotBlank()) {
                                Text(
                                    text = stringResource(R.string.unlock_keyfile_selected, uiState.keyFileName),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        androidx.compose.material3.Switch(
                            checked = uiState.hasKeyFile,
                            onCheckedChange = { checked ->
                                if (checked) onSelectKeyFile() else onClearKeyFile()
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // H4-只读整改：只读打开开关（KeePassDX/KP2A 同款能力）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.unlock_readonly),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.unlock_readonly_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = uiState.openReadOnly,
                            onCheckedChange = { onToggleReadOnly() }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 解锁主操作按钮
                    Button(
                        onClick = onUnlock,
                        enabled = !uiState.isLoading,
                        shape = CapsuleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.5.dp
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.unlock_btn_unlock),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }

                    if (uiState.isQuickUnlockAvailable) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { onSwitchMode(UnlockMode.QUICK_UNLOCK) }) {
                            Icon(Icons.Default.FlashOn, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.unlock_switch_back_quick), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
