package com.keepasskey.app.ui.screens.unlock

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import com.keepasskey.app.ui.theme.MonospacePasswordStyle

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

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is UnlockEvent.UnlockSuccess -> onUnlockSuccess()
            }
        }
    }

    // 已输入过密码且开启生物认证时，进入解锁页优先唤起生物识别验证
    LaunchedEffect(uiState.isBiometricEnabled, uiState.isQuickUnlockAvailable) {
        if (uiState.isBiometricEnabled && uiState.isQuickUnlockAvailable &&
            uiState.unlockMode == UnlockMode.QUICK_UNLOCK
        ) {
            viewModel.unlockWithBiometric()
        }
    }

    UnlockContent(
        uiState = uiState,
        currentTheme = currentTheme,
        onThemeToggle = onThemeToggle,
        onPasswordChange = viewModel::onPasswordChange,
        onQuickUnlockPinChange = viewModel::onQuickUnlockPinChange,
        onTogglePasswordVisibility = viewModel::onTogglePasswordVisibility,
        onToggleKeyFile = viewModel::onToggleKeyFile,
        onSwitchMode = viewModel::switchUnlockMode,
        onUnlock = viewModel::unlock,
        onQuickUnlock = viewModel::unlockWithQuickUnlock,
        onBiometricUnlock = viewModel::unlockWithBiometric,
        onNavigateToDatabasePicker = onNavigateToDatabasePicker,
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
    onPasswordChange: (String) -> Unit,
    onQuickUnlockPinChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onToggleKeyFile: () -> Unit,
    onSwitchMode: (UnlockMode) -> Unit,
    onUnlock: () -> Unit,
    onQuickUnlock: () -> Unit,
    onBiometricUnlock: () -> Unit,
    onNavigateToDatabasePicker: () -> Unit,
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
                .verticalScroll(scrollState)
                .imePadding()
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
                        imageVector = if (uiState.unlockMode == UnlockMode.QUICK_UNLOCK) Icons.Default.FlashOn else Icons.Default.Lock,
                        contentDescription = stringResource(R.string.cd_vault_locked),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (uiState.unlockMode == UnlockMode.QUICK_UNLOCK) stringResource(R.string.unlock_quick_title) else stringResource(R.string.unlock_title),
                style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp),
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.unlock_quick_cache_validity, uiState.quickUnlockRemainingMinutes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // 已开启生物认证：优先使用生物识别解锁
                        if (uiState.isBiometricEnabled) {
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

                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        // PIN / 短密码快捷输入
                        OutlinedTextField(
                            value = uiState.quickUnlockPin,
                            onValueChange = onQuickUnlockPinChange,
                            label = { Text(stringResource(R.string.unlock_quick_pin_label)) },
                            placeholder = { Text(stringResource(R.string.unlock_quick_pin_placeholder)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation('●'),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { onQuickUnlock() }),
                            textStyle = MonospacePasswordStyle.copy(fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        if (uiState.isBiometricEnabled) {
                            // 生物认证开启时快速解锁 PIN 退居次要方式
                            OutlinedButton(
                                onClick = onQuickUnlock,
                                enabled = !uiState.isLoading && uiState.quickUnlockPin.isNotBlank(),
                                shape = CapsuleShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FlashOn,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.unlock_quick_btn))
                            }
                        } else {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = onQuickUnlock,
                                    enabled = !uiState.isLoading && uiState.quickUnlockPin.isNotBlank(),
                                    shape = CapsuleShape,
                                    modifier = Modifier.weight(1f).height(46.dp)
                                ) {
                                    if (uiState.isLoading) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                    } else {
                                        Text(stringResource(R.string.unlock_quick_btn), fontWeight = FontWeight.Bold)
                                    }
                                }

                                OutlinedButton(
                                    onClick = onBiometricUnlock,
                                    shape = CapsuleShape,
                                    modifier = Modifier.height(46.dp)
                                ) {
                                    Icon(Icons.Default.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.unlock_biometric_btn))
                                }
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
                // 完整主密码输入框
                OutlinedTextField(
                    value = uiState.password,
                    onValueChange = onPasswordChange,
                    label = { Text(stringResource(R.string.unlock_master_password)) },
                    placeholder = { Text(stringResource(R.string.unlock_master_password_hint)) },
                    isError = uiState.errorMessage != null,
                    supportingText = {
                        uiState.errorMessage?.let { message ->
                            Text(
                                text = message.resolveText(),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    singleLine = true,
                    visualTransformation = if (uiState.isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation('●'),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { onUnlock() }),
                    trailingIcon = {
                        IconButton(onClick = onTogglePasswordVisibility) {
                            Icon(
                                imageVector = if (uiState.isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (uiState.isPasswordVisible) stringResource(R.string.cd_hide_password) else stringResource(R.string.cd_show_password),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    textStyle = MonospacePasswordStyle.copy(
                        fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 附加密钥文件切换
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { onToggleKeyFile() }
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
                        if (uiState.hasKeyFile) {
                            Text(
                                text = uiState.keyFileName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    androidx.compose.material3.Switch(
                        checked = uiState.hasKeyFile,
                        onCheckedChange = { onToggleKeyFile() }
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

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
