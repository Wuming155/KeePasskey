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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
 * 表单自动填充与通行密钥 (Passkey) 二级设置页 (整合 KeePass2Android 完整自动填充策略)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutofillSettingsScreen(
    uiState: SettingsUiState,
    onBackClick: () -> Unit,
    onCredentialProviderToggle: (Boolean) -> Unit,
    onPasskeySupportToggle: (Boolean) -> Unit,
    onAutofillServiceToggle: (Boolean) -> Unit,
    onAutoClearClipboardToggle: (Boolean) -> Unit,
    // KP2A 扩展自动填充操作
    onOfferSaveCredentialsToggle: (Boolean) -> Unit = {},
    onInlineSuggestionsToggle: (Boolean) -> Unit = {},
    onAutoReturnFromQueryToggle: (Boolean) -> Unit = {},
    onAutofillCopyTotpToggle: (Boolean) -> Unit = {},
    onAutofillShowTotpNotificationToggle: (Boolean) -> Unit = {},
    onSkipDalVerificationToggle: (Boolean) -> Unit = {},
    onOverrideNoAutofillToggle: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showBlacklistDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "自动填充与 Passkey",
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
            // 1. Android 系统级凭据提供程序 (Credential Provider)
            item {
                Text(
                    text = "系统凭据服务与 Passkey",
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
                        AutofillSwitchRow(
                            icon = Icons.Default.VpnKey,
                            title = "Credential Manager 凭据提供程序",
                            subtitle = "允许系统在浏览器与原生 App 登录时直接拉起本密码库进行身份认证",
                            checked = uiState.credentialProviderEnabled,
                            onCheckedChange = onCredentialProviderToggle
                        )

                        AutofillSwitchRow(
                            icon = Icons.Default.Key,
                            title = "Passkey 通行密钥管理 (FIDO2 / WebAuthn)",
                            subtitle = "支持生成并同步存储 Passkey 公私钥，免密码免动态码一触即登",
                            checked = uiState.passkeySupportEnabled,
                            onCheckedChange = onPasskeySupportToggle
                        )

                        AutofillSwitchRow(
                            icon = Icons.Default.Password,
                            title = "传统 Android 自动填充服务 (Framework)",
                            subtitle = "兼容 Android 8 ~ 13 传统登录表单的自动捕获与注入",
                            checked = uiState.autofillServiceEnabled,
                            onCheckedChange = onAutofillServiceToggle
                        )
                    }
                }
            }

            // 2. 体验与输入法协同 (KP2A 特性)
            item {
                Text(
                    text = "交互体验与输入协同 (KP2A 特性)",
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
                        AutofillSwitchRow(
                            icon = Icons.Default.Keyboard,
                            title = "键盘上方内联候选建议 (Inline Suggestions)",
                            subtitle = "在支持的输入法 (如 Gboard) 候选字栏上方直接显示匹配的账号与 Passkey 胶囊",
                            checked = uiState.inlineSuggestionsEnabled,
                            onCheckedChange = onInlineSuggestionsToggle
                        )

                        AutofillSwitchRow(
                            icon = Icons.AutoMirrored.Filled.Undo,
                            title = "选定条目后自动返回原请求应用",
                            subtitle = "在手动选择或搜索匹配凭据后，立即自动返回并填入原应用，无需手动按返回键",
                            checked = uiState.autoReturnFromQuery,
                            onCheckedChange = onAutoReturnFromQueryToggle
                        )

                        AutofillSwitchRow(
                            icon = Icons.Default.ContentPasteGo,
                            title = "自动清空填充剪贴板",
                            subtitle = "若自动填充降级使用剪贴板中转，填充完毕后立即清空内存暂存",
                            checked = uiState.autoClearClipboard,
                            onCheckedChange = onAutoClearClipboardToggle
                        )
                    }
                }
            }

            // 3. 两步验证与 TOTP 联动 (KP2A 特性)
            item {
                Text(
                    text = "两步验证 (TOTP) 联动动作 (KP2A 特性)",
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
                        AutofillSwitchRow(
                            icon = Icons.Default.ContentPasteGo,
                            title = "自动填充后将 TOTP 复制到剪贴板",
                            subtitle = "填入用户名和密码后，自动将条目的 6 位动态验证码暂存至剪贴板供二次粘贴",
                            checked = uiState.autofillCopyTotp,
                            onCheckedChange = onAutofillCopyTotpToggle
                        )

                        AutofillSwitchRow(
                            icon = Icons.Default.NotificationsActive,
                            title = "自动填充后在通知栏显示 TOTP 验证码",
                            subtitle = "在系统通知抽屉常驻显示当前账号的 TOTP 动态码，方便多步验证确认",
                            checked = uiState.autofillShowTotpNotification,
                            onCheckedChange = onAutofillShowTotpNotificationToggle
                        )
                    }
                }
            }

            // 4. 智能识别、凭证保存与兼容策略 (KP2A 特性)
            item {
                Text(
                    text = "智能识别与凭证捕获 (KP2A 特性)",
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
                        AutofillSwitchRow(
                            icon = Icons.Default.Save,
                            title = "提示保存新登录凭证 (Offer to Save)",
                            subtitle = "在外部网页或应用提交新注册/修改的账号密码时，主动弹出保存到密码库的浮窗",
                            checked = uiState.offerSaveCredentials,
                            onCheckedChange = onOfferSaveCredentialsToggle
                        )

                        AutofillSwitchRow(
                            icon = Icons.Default.Security,
                            title = "强制识别被禁止自动填充的字段",
                            subtitle = "忽略部分银行/政务应用恶意的 NOT_IMPORTANT_FOR_AUTOFILL 限制标记",
                            checked = uiState.overrideNoAutofill,
                            onCheckedChange = onOverrideNoAutofillToggle
                        )

                        AutofillSwitchRow(
                            icon = Icons.Default.Block,
                            title = "跳过数字资产链接 (DAL) 严格校验",
                            subtitle = "解决国内网络或私有域名无法访问 Google 关联验证服务器导致匹配失败的问题",
                            checked = uiState.skipDalVerification,
                            onCheckedChange = onSkipDalVerificationToggle
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showBlacklistDialog = true }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Block, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "已禁用的自动填充黑名单管理",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (uiState.disabledAutofillQueriesCount > 0) "已排除 ${uiState.disabledAutofillQueriesCount} 个域名/应用" else "暂无已排除黑名单，点击查看与管理",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // 黑名单管理对话框
    if (showBlacklistDialog) {
        AlertDialog(
            onDismissRequest = { showBlacklistDialog = false },
            title = { Text("自动填充黑名单管理") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "以下应用或域名曾在自动填充弹窗中被标记为“从不对此应用/网址进行填充”：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    listOf("com.example.bankapp (示例银行)", "internal-portal.local (内部局域网)").forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(item, style = MaterialTheme.typography.bodySmall)
                            IconButton(onClick = { }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "解除", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBlacklistDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
}

@Composable
private fun AutofillSwitchRow(
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
