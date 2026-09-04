package com.keepasskey.app.ui.screens.settings.subscreens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VpnKey
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.screens.settings.SettingsUiState

/**
 * 表单自动填充与通行密钥 (Passkey) 二级设置页 (对应 KeePassDX 表单填充)
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
    modifier: Modifier = Modifier
) {
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
            // 1. Android 系统级凭据提供程序 (Credential Provider)
            item {
                Text(
                    text = "凭据提供程序与 Passkey",
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
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        AutofillSwitchRow(
                            icon = Icons.Default.VpnKey,
                            title = "Credential Manager 凭据提供程序",
                            subtitle = "允许系统在浏览器与原生 App 登录时直接拉起本密码库进行验证",
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
                    }
                }
            }

            // 2. 经典表单自动填充服务
            item {
                Text(
                    text = "表单自动填充与剪贴板",
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
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        AutofillSwitchRow(
                            icon = Icons.Default.Password,
                            title = "Android 原生自动填充服务",
                            subtitle = "在网页或应用登录框聚焦时高亮提供密码与用户名匹配填充建议",
                            checked = uiState.autofillServiceEnabled,
                            onCheckedChange = onAutofillServiceToggle
                        )

                        AutofillSwitchRow(
                            icon = Icons.Default.ContentPasteGo,
                            title = "剪贴板敏感数据 30 秒自动清空",
                            subtitle = "通过快速复制提取密码后，后台倒计时结束自动抹除系统剪贴板",
                            checked = uiState.autoClearClipboard,
                            onCheckedChange = onAutoClearClipboardToggle
                        )
                    }
                }
            }

            // 3. 系统配置直达
            item {
                Button(
                    onClick = { },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "系统设置",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("前往 Android 系统默认自动填充服务设置")
                }
            }

            // 4. 安全说明
            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "Security tips",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp).padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "仅在目标应用的域名或签名与密码库记录精准匹配时才会提供自动填充，严格规避恶意钓鱼软件仿冒窃取凭据。",
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
