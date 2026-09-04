package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.screens.settings.SettingsUiState

/**
 * 密码库安全策略与锁定规则二级设置页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecuritySettingsScreen(
    uiState: SettingsUiState,
    onBackClick: () -> Unit,
    onBiometricToggle: (Boolean) -> Unit,
    onAutoLockToggle: (Boolean) -> Unit,
    onFlagSecureToggle: (Boolean) -> Unit,
    onAutoClearClipboardToggle: (Boolean) -> Unit,
    onAutoLockTimeoutChange: (Int, String) -> Unit = { _, _ -> },
    onClipboardTimeoutChange: (Int, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var showAutoLockDialog by remember { mutableStateOf(false) }
    var showClipboardDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "安全与锁定策略",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "锁定与用户验证",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = androidx.compose.ui.graphics.Color(0xFFDD6B20),
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SecuritySwitchRow(
                            icon = Icons.Default.Fingerprint,
                            title = "生物识别验证",
                            subtitle = "支持系统级指纹或面容快速解封 Android Keystore 衍生密钥",
                            checked = uiState.biometricEnabled,
                            onCheckedChange = onBiometricToggle
                        )

                        SecuritySwitchRow(
                            icon = Icons.Default.ScreenLockPortrait,
                            title = "切出应用立即锁定",
                            subtitle = "离开前台或设备锁屏时立即在 RAM 内存中覆盖抹除解密主密钥",
                            checked = uiState.autoLockBackground,
                            onCheckedChange = onAutoLockToggle
                        )

                        SecurityClickableRow(
                            icon = Icons.Default.LockClock,
                            title = "自动锁定等待时间",
                            subtitle = "当前：${uiState.autoLockTimeoutLabel} (无交互后自动锁库)",
                            onClick = { showAutoLockDialog = true }
                        )
                    }
                }
            }

            item {
                Text(
                    text = "系统环境防泄露保护 (FLAG_SECURE / 剪贴板)",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = androidx.compose.ui.graphics.Color(0xFFDD6B20),
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SecuritySwitchRow(
                            icon = Icons.Default.LockClock,
                            title = "防截屏防录屏保护 (FLAG_SECURE)",
                            subtitle = "阻止第三方系统多任务快照预览与截屏，规避幽灵应用窃取凭据",
                            checked = uiState.flagSecureEnabled,
                            onCheckedChange = onFlagSecureToggle
                        )

                        SecuritySwitchRow(
                            icon = Icons.Default.ContentPasteGo,
                            title = "剪贴板自动清空",
                            subtitle = "复制密码或 TOTP 动态验证码后，超时自动从剪贴板抹除",
                            checked = uiState.autoClearClipboard,
                            onCheckedChange = onAutoClearClipboardToggle
                        )

                        if (uiState.autoClearClipboard) {
                            SecurityClickableRow(
                                icon = Icons.Default.ContentPasteGo,
                                title = "剪贴板清空倒计时",
                                subtitle = "当前：${uiState.clipboardTimeoutLabel} 后自动注销剪贴板明文",
                                onClick = { showClipboardDialog = true }
                            )
                        }
                    }
                }
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = "Security Engine",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "纯原生安全防护架构",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "KeePasskey 在底层不依赖任何远程验证服务，敏感内存数据均由 ByteArray 原地零化（Zeroization）清洗，绝不产生持久化明文垃圾回收留存。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // 自动锁定超时选择弹窗
    if (showAutoLockDialog) {
        val lockOptions = listOf(
            0 to "立即锁定",
            30 to "30 秒",
            60 to "1 分钟",
            300 to "5 分钟",
            900 to "15 分钟",
            -1 to "从不锁定"
        )
        AlertDialog(
            onDismissRequest = { showAutoLockDialog = false },
            title = {
                Text(
                    text = "自动锁定等待时间",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "当应用退至后台或设备闲置无操作达到以下时长，自动抹除主密钥并锁定密码库：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    lockOptions.forEach { (seconds, label) ->
                        val isSelected = uiState.autoLockTimeoutSeconds == seconds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onAutoLockTimeoutChange(seconds, label)
                                    showAutoLockDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    onAutoLockTimeoutChange(seconds, label)
                                    showAutoLockDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                ),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAutoLockDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 剪贴板清空超时选择弹窗
    if (showClipboardDialog) {
        val clipboardOptions = listOf(
            15 to "15 秒",
            30 to "30 秒 (推荐)",
            60 to "60 秒",
            120 to "2 分钟",
            -1 to "从不清空"
        )
        AlertDialog(
            onDismissRequest = { showClipboardDialog = false },
            title = {
                Text(
                    text = "剪贴板自动清空时长",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "从凭据详情复制密码或验证码后，将在以下时长后从 Android 系统剪贴板中擦除：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    clipboardOptions.forEach { (seconds, label) ->
                        val isSelected = uiState.clipboardTimeoutSeconds == seconds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onClipboardTimeoutChange(seconds, label.replace(" (推荐)", ""))
                                    showClipboardDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    onClipboardTimeoutChange(seconds, label.replace(" (推荐)", ""))
                                    showClipboardDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                ),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showClipboardDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun SecurityClickableRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = "选择",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(15.dp)
        )
    }
}

@Composable
private fun SecuritySwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}
