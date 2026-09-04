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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.screens.settings.SettingsUiState

/**
 * 密码库安全策略与锁定规则二级设置页 (全面整合 KeePass2Android 快速解锁与锁定规则)
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
    // KP2A 扩展安全操作
    onQuickUnlockToggle: (Boolean) -> Unit = {},
    onQuickUnlockLengthChange: (Int) -> Unit = {},
    onQuickUnlockObscureInputToggle: (Boolean) -> Unit = {},
    onQuickUnlockHideLengthToggle: (Boolean) -> Unit = {},
    onQuickUnlockRequireDeviceLockToggle: (Boolean) -> Unit = {},
    onQuickUnlockUseDedicatedKeyToggle: (Boolean) -> Unit = {},
    onLockWhenScreenOffToggle: (Boolean) -> Unit = {},
    onLockWhenNavigateBackToggle: (Boolean) -> Unit = {},
    onClearPasswordOnLeaveToggle: (Boolean) -> Unit = {},
    onRememberRecentFilesToggle: (Boolean) -> Unit = {},
    onRememberKeyFileLocationToggle: (Boolean) -> Unit = {},
    onShowKillAppOptionToggle: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showAutoLockDialog by remember { mutableStateOf(false) }
    var showClipboardDialog by remember { mutableStateOf(false) }
    var showQuickUnlockLengthDialog by remember { mutableStateOf(false) }

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
                            contentDescription = stringResource(R.string.cd_back)
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
            // 1. 快速解锁体系 (KP2A 核心特色)
            item {
                Text(
                    text = "快速解锁 (QuickUnlock - KP2A 特性)",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SecuritySwitchRow(
                            icon = Icons.Default.Pin,
                            title = "启用快速解锁 (QuickUnlock)",
                            subtitle = "首次输入完整主密码后，后续只需输入短 PIN 码即可快速解锁密码库",
                            checked = uiState.quickUnlockEnabled,
                            onCheckedChange = onQuickUnlockToggle
                        )

                        if (uiState.quickUnlockEnabled) {
                            SecurityClickableRow(
                                icon = Icons.Default.Pin,
                                title = "快速解锁 PIN 码截取长度",
                                subtitle = "当前：截取主密码末尾 ${uiState.quickUnlockLength} 位字符",
                                onClick = { showQuickUnlockLengthDialog = true }
                            )

                            SecuritySwitchRow(
                                icon = Icons.Default.VisibilityOff,
                                title = "隐蔽输入模式 (防肩窥旁观)",
                                subtitle = "输入短 PIN 时屏幕不回显任何圆点或光标，彻底防偷窥",
                                checked = uiState.quickUnlockObscureInput,
                                onCheckedChange = onQuickUnlockObscureInputToggle
                            )

                            SecuritySwitchRow(
                                icon = Icons.Default.LockClock,
                                title = "隐藏所需输入字符位数",
                                subtitle = "快速解锁界面不展示预设字符槽位，避免暴露主密码末尾长度",
                                checked = uiState.quickUnlockHideLength,
                                onCheckedChange = onQuickUnlockHideLengthToggle
                            )

                            SecuritySwitchRow(
                                icon = Icons.Default.Security,
                                title = "设备未设系统锁屏时禁用",
                                subtitle = "手机未启用锁屏密码时强制降级为完整主密码解锁，防范设备遗失",
                                checked = uiState.quickUnlockRequireDeviceLock,
                                onCheckedChange = onQuickUnlockRequireDeviceLockToggle
                            )

                            SecuritySwitchRow(
                                icon = Icons.Default.Key,
                                title = "使用库内独立快速解锁密钥",
                                subtitle = "单独指定专属快速解锁密钥，而非截取主密码末尾",
                                checked = uiState.quickUnlockUseDedicatedKey,
                                onCheckedChange = onQuickUnlockUseDedicatedKeyToggle
                            )
                        }
                    }
                }
            }

            // 2. 锁定规则与触发条件
            item {
                Text(
                    text = "自动锁定与触发条件",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SecuritySwitchRow(
                            icon = Icons.Default.Fingerprint,
                            title = "生物识别验证",
                            subtitle = "支持系统级指纹或面容快速解封 Android Keystore 硬件安全凭证",
                            checked = uiState.biometricEnabled,
                            onCheckedChange = onBiometricToggle
                        )

                        SecuritySwitchRow(
                            icon = Icons.Default.ScreenLockPortrait,
                            title = "切出应用立即锁定",
                            subtitle = "退至后台或切至其他应用时立即在 RAM 内存中覆盖抹除解密主密钥",
                            checked = uiState.autoLockBackground,
                            onCheckedChange = onAutoLockToggle
                        )

                        SecuritySwitchRow(
                            icon = Icons.Default.LockClock,
                            title = "熄屏时立即锁定 (KP2A)",
                            subtitle = "手机按电源键息屏或超时待机时立即锁定密码库",
                            checked = uiState.lockWhenScreenOff,
                            onCheckedChange = onLockWhenScreenOffToggle
                        )

                        SecuritySwitchRow(
                            icon = Icons.Default.Cancel,
                            title = "返回退出应用时锁定 (KP2A)",
                            subtitle = "在应用主页按返回键退出时直接触发锁定",
                            checked = uiState.lockWhenNavigateBack,
                            onCheckedChange = onLockWhenNavigateBackToggle
                        )

                        SecurityClickableRow(
                            icon = Icons.Default.LockClock,
                            title = "自动锁定等待时间",
                            subtitle = "当前：${uiState.autoLockTimeoutLabel} (无交互后自动锁库)",
                            onClick = { showAutoLockDialog = true }
                        )

                        SecuritySwitchRow(
                            icon = Icons.Default.VisibilityOff,
                            title = "离开密码页清除输入 (KP2A)",
                            subtitle = "在解锁页面输入过程中若切离应用，自动抹除输入框内的残留字符",
                            checked = uiState.clearPasswordOnLeave,
                            onCheckedChange = onClearPasswordOnLeaveToggle
                        )
                    }
                }
            }

            // 3. 系统环境防泄露保护 (FLAG_SECURE / 剪贴板)
            item {
                Text(
                    text = "系统环境防泄露保护 (FLAG_SECURE / 剪贴板)",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SecuritySwitchRow(
                            icon = Icons.Default.Security,
                            title = "防截屏防录屏保护 (FLAG_SECURE)",
                            subtitle = "阻止第三方系统多任务快照预览与截屏，规避幽灵应用窃取凭据",
                            checked = uiState.flagSecureEnabled,
                            onCheckedChange = onFlagSecureToggle
                        )

                        SecuritySwitchRow(
                            icon = Icons.Default.ContentPasteGo,
                            title = "剪贴板自动清空",
                            subtitle = "复制密码或 TOTP 动态验证码后，超时自动从系统剪贴板抹除",
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

            // 4. 凭据记忆与历史记录 (KP2A 特性)
            item {
                Text(
                    text = "凭据记忆与进程控制 (KP2A 特性)",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SecuritySwitchRow(
                            icon = Icons.Default.History,
                            title = "记住最近使用的密码库文件",
                            subtitle = "在应用启动和抽屉列表中记住历史打开过的数据库路径",
                            checked = uiState.rememberRecentFiles,
                            onCheckedChange = onRememberRecentFilesToggle
                        )

                        SecuritySwitchRow(
                            icon = Icons.Default.Bookmark,
                            title = "记住密钥文件 (KeyFile) 关联",
                            subtitle = "记住上次解锁选定的密钥文件路径，免去重复浏览定位文件",
                            checked = uiState.rememberKeyFileLocation,
                            onCheckedChange = onRememberKeyFileLocationToggle
                        )

                        SecuritySwitchRow(
                            icon = Icons.Default.PowerSettingsNew,
                            title = "显示彻底终止应用选项 (Kill Application)",
                            subtitle = "在菜单和通知中提供立即结束所有后台服务并终止应用进程的安全按钮",
                            checked = uiState.showKillAppOption,
                            onCheckedChange = onShowKillAppOptionToggle
                        )
                    }
                }
            }

            // 5. 架构说明
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
                                text = "纯原生零内存驻留防护",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "KeePasskey 所有锁定动作均同步触发 JVM 堆外内存重写零化，结合 Android 14+ 隔离执行环境，确保密码库锁定后主密钥绝无任何残余镜像。",
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
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
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

    // 剪贴板清空倒计时弹窗
    if (showClipboardDialog) {
        val clipOptions = listOf(
            15 to "15 秒",
            30 to "30 秒 (推荐)",
            60 to "1 分钟",
            120 to "2 分钟",
            -1 to "不自动清空"
        )
        AlertDialog(
            onDismissRequest = { showClipboardDialog = false },
            title = {
                Text(
                    text = "剪贴板清空倒计时",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "复制密码或动态码到剪贴板后，设定自动注销并抹除的时间间隔：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    clipOptions.forEach { (seconds, label) ->
                        val isSelected = uiState.clipboardTimeoutSeconds == seconds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onClipboardTimeoutChange(seconds, label)
                                    showClipboardDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    onClipboardTimeoutChange(seconds, label)
                                    showClipboardDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
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

    // 快速解锁 PIN 截取长度弹窗
    if (showQuickUnlockLengthDialog) {
        val pinOptions = listOf(3, 4, 5, 6)
        AlertDialog(
            onDismissRequest = { showQuickUnlockLengthDialog = false },
            title = {
                Text(
                    text = "选择快速解锁 PIN 截取长度",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "设置快速解锁时需验证的主密码末尾字符位数 (默认 3 位)：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    pinOptions.forEach { len ->
                        val isSelected = uiState.quickUnlockLength == len
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onQuickUnlockLengthChange(len)
                                    showQuickUnlockLengthDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    onQuickUnlockLengthChange(len)
                                    showQuickUnlockLengthDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "$len 位字符 (主密码最后 $len 位)",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQuickUnlockLengthDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun SecuritySwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
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
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
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

@Composable
private fun SecurityClickableRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(14.dp)
        )
    }
}
