package com.keepasskey.app.ui.screens.edit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.components.PasswordStrengthBar
import com.keepasskey.app.ui.model.EntryCategory
import com.keepasskey.app.ui.theme.CapsuleShape
import com.keepasskey.app.ui.theme.KeePasskeyTheme
import com.keepasskey.app.ui.theme.LocalSecurityColors
import com.keepasskey.app.ui.theme.MonospacePasswordStyle

/**
 * 有状态凭据编辑/添加页面（Route）
 */
@Composable
fun EntryEditScreen(
    entryId: String?,
    onBackClick: () -> Unit,
    onSaveSuccess: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EntryEditViewModel = hiltViewModel()
) {
    LaunchedEffect(entryId) {
        if (entryId != null) {
            viewModel.loadEntry(entryId)
        }
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is EntryEditEvent.SaveSuccess -> onSaveSuccess()
            }
        }
    }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearUserMessage()
        }
    }

    EntryEditContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBackClick = onBackClick,
        onSaveClick = viewModel::saveEntry,
        onGroupChange = viewModel::onGroupChange,
        onTitleChange = viewModel::onTitleChange,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onUrlChange = viewModel::onUrlChange,
        onNotesChange = viewModel::onNotesChange,
        onTogglePasskey = viewModel::onTogglePasskey,
        onTotpSecretChange = viewModel::onTotpSecretChange,
        onCategoryChange = viewModel::onCategoryChange,
        onTogglePasswordVisibility = viewModel::onTogglePasswordVisibility,
        onToggleGenerator = viewModel::onToggleGenerator,
        onPassLengthChange = viewModel::onPassLengthChange,
        onGeneratePassword = viewModel::generatePassword,
        onToggleUpper = viewModel::onToggleUpper,
        onToggleLower = viewModel::onToggleLower,
        onToggleDigits = viewModel::onToggleDigits,
        onToggleSymbols = viewModel::onToggleSymbols,
        onShowMessage = viewModel::showMessage,
        modifier = modifier
    )
}

/**
 * 无状态凭据编辑渲染组件
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryEditContent(
    uiState: EntryEditUiState,
    snackbarHostState: SnackbarHostState,
    onBackClick: () -> Unit,
    onSaveClick: () -> Unit,
    onGroupChange: (String?) -> Unit,
    onTitleChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onTogglePasskey: () -> Unit,
    onTotpSecretChange: (String) -> Unit,
    onCategoryChange: (EntryCategory) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onToggleGenerator: () -> Unit,
    onPassLengthChange: (Float) -> Unit,
    onGeneratePassword: () -> Unit,
    onToggleUpper: () -> Unit,
    onToggleLower: () -> Unit,
    onToggleDigits: () -> Unit,
    onToggleSymbols: () -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val securityColors = LocalSecurityColors.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (uiState.entryId != null) "编辑条目" else "新建条目",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "取消"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSaveClick) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "保存",
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 分类选择
            Text(
                text = "凭据分类",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(EntryCategory.LOGIN, EntryCategory.PASSKEY, EntryCategory.NOTE, EntryCategory.CARD).forEach { cat ->
                    val selected = uiState.selectedCategory == cat
                    FilterChip(
                        selected = selected,
                        onClick = { onCategoryChange(cat) },
                        label = { Text(cat.label) },
                        shape = CapsuleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            // 所属群组 / 文件夹选择
            if (uiState.availableGroups.isNotEmpty()) {
                Text(
                    text = "所属群组 (文件夹)",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = uiState.groupId == null,
                            onClick = { onGroupChange(null) },
                            label = { Text("根目录 (未分类)") },
                            shape = CapsuleShape,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                    items(uiState.availableGroups) { grp ->
                        val selected = uiState.groupId == grp.id
                        FilterChip(
                            selected = selected,
                            onClick = { onGroupChange(grp.id) },
                            label = { Text(grp.name) },
                            shape = CapsuleShape,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
            }

            // 基本信息
            Text(
                text = "基本信息",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )

            BentoCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = uiState.title,
                        onValueChange = onTitleChange,
                        label = { Text("标题 (例如 Google / GitHub)") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = uiState.url,
                        onValueChange = onUrlChange,
                        label = { Text("网址 URL (https://...)") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 账户与密码
            Text(
                text = "账户与密码",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )

            BentoCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = uiState.username,
                        onValueChange = onUsernameChange,
                        label = { Text("用户名 / 邮箱 / 账号") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = uiState.password,
                        onValueChange = onPasswordChange,
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = if (uiState.isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation('●'),
                        trailingIcon = {
                            Row {
                                IconButton(onClick = onTogglePasswordVisibility) {
                                    Icon(
                                        imageVector = if (uiState.isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "显隐",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = onToggleGenerator) {
                                    Icon(
                                        imageVector = Icons.Default.ElectricBolt,
                                        contentDescription = "密码生成器",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        },
                        textStyle = MonospacePasswordStyle.copy(
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (uiState.password.isNotEmpty()) {
                        PasswordStrengthBar(
                            entropyBits = (uiState.password.length * 4.5).toInt(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 密码生成器模块
                    AnimatedVisibility(visible = uiState.showGenerator) {
                        PasswordGeneratorWidget(
                            passLength = uiState.passLength,
                            useUpper = uiState.useUpper,
                            useLower = uiState.useLower,
                            useDigits = uiState.useDigits,
                            useSymbols = uiState.useSymbols,
                            onPassLengthChange = onPassLengthChange,
                            onRegenerate = onGeneratePassword,
                            onToggleUpper = onToggleUpper,
                            onToggleLower = onToggleLower,
                            onToggleDigits = onToggleDigits,
                            onToggleSymbols = onToggleSymbols
                        )
                    }
                }
            }

            // TOTP 配置
            Text(
                text = "二次验证令牌 (TOTP)",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )

            BentoCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
            ) {
                OutlinedTextField(
                    value = uiState.totpSecret,
                    onValueChange = onTotpSecretChange,
                    label = { Text("TOTP 密钥 (Secret Key / Base32)") },
                    trailingIcon = {
                        IconButton(onClick = { onShowMessage("呼起相机扫描 TOTP 二维码") }) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = "扫码",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 通行密钥 Passkey 注册绑定
            Text(
                text = "通行密钥 (Passkey / WebAuthn)",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )

            BentoCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = if (uiState.isPasskey) securityColors.passkeyContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainerLowest,
                borderColor = if (uiState.isPasskey) securityColors.passkey.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (uiState.isPasskey) "已绑定 Passkey 硬件凭据" else "为此网站创建 Passkey",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "基于 Android 14+ 凭据管理器，支持端到端公私钥验证",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = onTogglePasskey,
                        shape = CapsuleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.isPasskey) securityColors.passkey else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(if (uiState.isPasskey) "解除" else "绑定")
                    }
                }
            }

            // 安全备注
            Text(
                text = "安全笔记与自定义字段",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )

            BentoCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
            ) {
                OutlinedTextField(
                    value = uiState.notes,
                    onValueChange = onNotesChange,
                    label = { Text("加密安全备注 (可选)") },
                    minLines = 3,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 底部保存大按钮
            Button(
                onClick = onSaveClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = CapsuleShape
            ) {
                Text(
                    text = "保 存 凭 据",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

/**
 * 密码生成器微件（抽离组件提高模块化）
 */
@Composable
private fun PasswordGeneratorWidget(
    passLength: Float,
    useUpper: Boolean,
    useLower: Boolean,
    useDigits: Boolean,
    useSymbols: Boolean,
    onPassLengthChange: (Float) -> Unit,
    onRegenerate: () -> Unit,
    onToggleUpper: () -> Unit,
    onToggleLower: () -> Unit,
    onToggleDigits: () -> Unit,
    onToggleSymbols: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "密码生成器 (长度: ${passLength.toInt()})",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            IconButton(onClick = onRegenerate) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "重新生成",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Slider(
            value = passLength,
            onValueChange = onPassLengthChange,
            valueRange = 8f..48f,
            steps = 39,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = useUpper,
                onClick = onToggleUpper,
                label = { Text("A-Z") }
            )
            FilterChip(
                selected = useLower,
                onClick = onToggleLower,
                label = { Text("a-z") }
            )
            FilterChip(
                selected = useDigits,
                onClick = onToggleDigits,
                label = { Text("0-9") }
            )
            FilterChip(
                selected = useSymbols,
                onClick = onToggleSymbols,
                label = { Text("!@#") }
            )
        }
    }
}

@Preview(name = "浅色模式 - 编辑", showBackground = true)
@Preview(name = "深色模式 - 编辑", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EntryEditContentPreview() {
    KeePasskeyTheme {
        EntryEditContent(
            uiState = EntryEditUiState(
                entryId = "1",
                title = "Google Workspace",
                username = "alex.developer@gmail.com",
                password = "SuperSecretPassword123!",
                isPasskey = true,
                showGenerator = true
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onBackClick = {},
            onSaveClick = {},
            onGroupChange = {},
            onTitleChange = {},
            onUsernameChange = {},
            onPasswordChange = {},
            onUrlChange = {},
            onNotesChange = {},
            onTogglePasskey = {},
            onTotpSecretChange = {},
            onCategoryChange = {},
            onTogglePasswordVisibility = {},
            onToggleGenerator = {},
            onPassLengthChange = {},
            onGeneratePassword = {},
            onToggleUpper = {},
            onToggleLower = {},
            onToggleDigits = {},
            onToggleSymbols = {},
            onShowMessage = {}
        )
    }
}
