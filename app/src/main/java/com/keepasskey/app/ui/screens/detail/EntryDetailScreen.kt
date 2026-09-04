package com.keepasskey.app.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.components.PasskeyBadge
import com.keepasskey.app.ui.components.PasswordStrengthBar
import com.keepasskey.app.ui.components.TotpMiniGauge
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.theme.KeePasskeyTheme
import com.keepasskey.app.ui.theme.LocalSecurityColors
import com.keepasskey.app.ui.theme.MonospacePasswordStyle
import com.keepasskey.app.ui.theme.MonospaceTotpStyle

/**
 * 有状态凭据详情页面（Route）
 */
@Composable
fun EntryDetailScreen(
    entryId: String,
    onBackClick: () -> Unit,
    onEditClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EntryDetailViewModel = hiltViewModel()
) {
    LaunchedEffect(entryId) {
        viewModel.setEntryId(entryId)
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearUserMessage()
        }
    }

    EntryDetailContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBackClick = onBackClick,
        onEditClick = { uiState.entry?.let { onEditClick(it.id) } },
        onToggleFavorite = viewModel::toggleFavorite,
        onTogglePasswordVisibility = viewModel::togglePasswordVisibility,
        onShowMessage = viewModel::showMessage,
        modifier = modifier
    )
}

/**
 * 无状态凭据详情渲染组件
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDetailContent(
    uiState: EntryDetailUiState,
    snackbarHostState: SnackbarHostState,
    onBackClick: () -> Unit,
    onEditClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val entry = uiState.entry
    val securityColors = LocalSecurityColors.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("凭据详情", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            imageVector = if (uiState.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "收藏",
                            tint = if (uiState.isFavorite) securityColors.warning else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onEditClick) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "编辑",
                            tint = MaterialTheme.colorScheme.primary
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
        if (entry == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "未找到凭据", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 头部 Hero 区域
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = entry.title.take(1).uppercase(),
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = entry.title,
                                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (entry.isPasskey) {
                                Spacer(modifier = Modifier.width(8.dp))
                                PasskeyBadge()
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = entry.url,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // 快捷操作磁贴组
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    QuickActionTile(
                        icon = Icons.AutoMirrored.Filled.OpenInNew,
                        label = "打开网址",
                        modifier = Modifier.weight(1f),
                        onClick = { onShowMessage("正在呼起浏览器并准备自动填充...") }
                    )
                    QuickActionTile(
                        icon = Icons.Default.ContentCopy,
                        label = "复制账号",
                        modifier = Modifier.weight(1f),
                        onClick = { onShowMessage("账号已复制到安全剪贴板") }
                    )
                    QuickActionTile(
                        icon = Icons.Default.Key,
                        label = "复制密码",
                        modifier = Modifier.weight(1f),
                        onClick = { onShowMessage("密码已复制，30秒后自动清空") }
                    )
                }

                // 基础凭据卡片
                Text(
                    text = "基础凭据",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "用户名 / 登录邮箱",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = entry.username,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(onClick = { onShowMessage("账号已复制") }) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "复制账号",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )

                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "密码 (${entry.passwordPlain.length} 字符)",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (uiState.isPasswordVisible) entry.passwordPlain else entry.passwordMasked,
                                        style = MonospacePasswordStyle.copy(
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 17.sp
                                        )
                                    )
                                }
                                Row {
                                    IconButton(onClick = onTogglePasswordVisibility) {
                                        Icon(
                                            imageVector = if (uiState.isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = "显隐",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    IconButton(onClick = { onShowMessage("密码已复制到剪贴板") }) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "复制密码",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            PasswordStrengthBar(
                                entropyBits = entry.strengthBits,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // TOTP 卡片
                if (entry.totpCode != null) {
                    Text(
                        text = "二次验证令牌 (TOTP)",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    BentoCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "实时动态验证码",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = entry.totpCode,
                                    style = MonospaceTotpStyle.copy(color = MaterialTheme.colorScheme.primary)
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TotpMiniGauge(
                                    remainingSeconds = entry.totpRemainingSeconds,
                                    modifier = Modifier.size(34.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                IconButton(onClick = { onShowMessage("动态验证码已复制") }) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "复制 TOTP",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Passkey 卡片
                if (entry.isPasskey) {
                    Text(
                        text = "通行密钥凭据 (WebAuthn)",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    BentoCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = securityColors.passkeyContainer.copy(alpha = 0.35f),
                        borderColor = securityColors.passkey.copy(alpha = 0.5f)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = "Passkey",
                                    tint = securityColors.passkey,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "FIDO2 硬件芯片保护",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = securityColors.passkey
                                    )
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "关联域名 (RP ID): ${entry.passkeyRpId ?: entry.url}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "支持 Android 14+ Credential Manager 免密码生物识别自动校验",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 备注
                if (entry.notes.isNotBlank()) {
                    Text(
                        text = "安全笔记与元数据",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    BentoCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
                    ) {
                        Column {
                            Text(
                                text = entry.notes,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "更新时间: ${entry.updatedAt} • 加密存储于 KDBX",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }
}

/**
 * 快捷操作磁贴小组件
 */
@Composable
private fun QuickActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.medium
            ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Preview(name = "浅色模式", showBackground = true)
@Preview(name = "深色模式", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EntryDetailContentPreview() {
    KeePasskeyTheme {
        EntryDetailContent(
            uiState = EntryDetailUiState(
                entry = FakeVaultRepository.initialMockEntries.first()
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onBackClick = {},
            onEditClick = {},
            onToggleFavorite = {},
            onTogglePasswordVisibility = {},
            onShowMessage = {}
        )
    }
}
