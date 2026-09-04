package com.keepasskey.app.ui.screens.unlock

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.components.ThemeToggleCapsule
import com.keepasskey.app.ui.theme.AppThemeMode
import com.keepasskey.app.ui.theme.CapsuleShape
import com.keepasskey.app.ui.theme.KeePasskeyTheme
import com.keepasskey.app.ui.theme.MonospacePasswordStyle

/**
 * 有状态解锁页面（Route），负责收集 ViewModel 状态与事件转发
 */
@Composable
fun UnlockScreen(
    currentTheme: AppThemeMode,
    onThemeToggle: () -> Unit,
    onUnlockSuccess: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: UnlockViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is UnlockEvent.UnlockSuccess -> onUnlockSuccess()
                is UnlockEvent.ShowMessage -> { /* 预留错误弹窗 */ }
            }
        }
    }

    UnlockContent(
        uiState = uiState,
        currentTheme = currentTheme,
        onThemeToggle = onThemeToggle,
        onPasswordChange = viewModel::onPasswordChange,
        onTogglePasswordVisibility = viewModel::onTogglePasswordVisibility,
        onToggleKeyFile = viewModel::onToggleKeyFile,
        onUnlock = viewModel::unlock,
        onBiometricUnlock = viewModel::unlockWithBiometric,
        modifier = modifier
    )
}

/**
 * 无状态解锁内容渲染组件（纯声明式 UI，便于单元测试与 Compose 预览）
 */
@Composable
fun UnlockContent(
    uiState: UnlockUiState,
    currentTheme: AppThemeMode,
    onThemeToggle: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onToggleKeyFile: () -> Unit,
    onUnlock: () -> Unit,
    onBiometricUnlock: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxSize()
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
                .padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            // 密码库锁 Logo 与呼吸光晕底座
            Box(
                modifier = Modifier
                    .size(100.dp)
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
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Vault Locked",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "KeePasskey",
                style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "密码库已锁定 • KDBX v4 (Argon2d + AES-256)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(28.dp))

            // 当前数据库概要条目
            BentoCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Active DB",
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
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 主密码输入框
            OutlinedTextField(
                value = uiState.password,
                onValueChange = onPasswordChange,
                label = { Text("主密码 (Master Password)") },
                placeholder = { Text("输入主密码以解锁") },
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
                            contentDescription = if (uiState.isPasswordVisible) "隐藏密码" else "显示密码",
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

            Spacer(modifier = Modifier.height(14.dp))

            // 附加密钥文件切换
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onToggleKeyFile() }
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.AttachFile,
                    contentDescription = "Keyfile",
                    tint = if (uiState.hasKeyFile) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (uiState.hasKeyFile) "已载入密钥文件: ${uiState.keyFileName}" else "附加密钥文件 (.key) [可选]",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (uiState.hasKeyFile) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 主操作：解锁密码库
            Button(
                onClick = onUnlock,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = CapsuleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    text = "解 锁 密 码 库",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 生物识别快捷解锁
            OutlinedButton(
                onClick = onBiometricUnlock,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = CapsuleShape,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                    brush = Brush.horizontalGradient(
                        listOf(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.colorScheme.outlineVariant)
                    )
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = "Biometric",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "使用指纹 / 面容快速解锁",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 底部辅助入口
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "切换数据库",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(CapsuleShape)
                        .clickable { }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                Text(
                    text = "新建密码库",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(CapsuleShape)
                        .clickable { }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Preview(name = "浅色模式", showBackground = true)
@Preview(name = "深色模式", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun UnlockContentPreview() {
    KeePasskeyTheme {
        UnlockContent(
            uiState = UnlockUiState(
                password = "SamplePassword123",
                hasKeyFile = true
            ),
            currentTheme = AppThemeMode.SYSTEM,
            onThemeToggle = {},
            onPasswordChange = {},
            onTogglePasswordVisibility = {},
            onToggleKeyFile = {},
            onUnlock = {},
            onBiometricUnlock = {}
        )
    }
}
