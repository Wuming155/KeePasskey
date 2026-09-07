package com.keepasskey.app.ui.screens.edit

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.runtime.rememberCoroutineScope
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.components.IconPickerDialog
import com.keepasskey.app.ui.components.PasswordStrengthBar
import com.keepasskey.app.ui.components.SecurePasswordField
import com.keepasskey.app.ui.components.getVaultIcon
import com.keepasskey.app.ui.theme.CapsuleShape
import com.keepasskey.app.ui.theme.LocalSecurityColors

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
    // M1 整改：既有条目密码的一次性预填通道（SecurePasswordField 消费后即清零）
    val loadedPassword by viewModel.loadedPassword.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 断点1 整改：真实 SAF 附件选择器——读取所选文件字节后随编辑会话提交
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null || bytes.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            viewModel.showMessage(UiMessage(R.string.edit_attachment_empty))
                        }
                        return@launch
                    }
                    val displayName = queryDisplayName(context, uri) ?: "attachment.bin"
                    withContext(Dispatchers.Main) {
                        viewModel.addAttachment(displayName, formatAttachmentSize(bytes.size), bytes)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        viewModel.showMessage(UiMessage(R.string.edit_attachment_empty))
                    }
                }
            }
        }
    }

    // 断点5 整改：TOTP 二维码真实扫描（zxing-embedded），扫描结果直接回填种子输入框
    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { viewModel.onTotpSecretChange(it) }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is EntryEditEvent.SaveSuccess -> onSaveSuccess()
            }
        }
    }

    uiState.userMessage?.let { message ->
        val text = message.resolveText()
        LaunchedEffect(message, text) {
            snackbarHostState.showSnackbar(text)
            viewModel.clearUserMessage()
        }
    }

    val scanPrompt = stringResource(R.string.edit_scan_totp_qr)

    EntryEditContent(
        uiState = uiState,
        loadedPassword = loadedPassword,
        isDirty = uiState.isDirty,
        snackbarHostState = snackbarHostState,
        onBackClick = onBackClick,
        onSaveClick = viewModel::saveEntry,
        onGroupChange = viewModel::onGroupChange,
        onIconChange = viewModel::onIconChange,
        onTitleChange = viewModel::onTitleChange,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChangeSecure = viewModel::onPasswordChangeSecure,
        onUrlChange = viewModel::onUrlChange,
        onNotesChange = viewModel::onNotesChange,
        onTogglePasskey = viewModel::onTogglePasskey,
        onTotpSecretChange = viewModel::onTotpSecretChange,
        onTagsInputChange = viewModel::onTagsInputChange,
        onAutoTypeSequenceChange = viewModel::onAutoTypeSequenceChange,
        onOverrideUrlChange = viewModel::onOverrideUrlChange,
        onAddCustomField = viewModel::addCustomField,
        onUpdateCustomField = viewModel::updateCustomField,
        onRemoveCustomField = viewModel::removeCustomField,
        onRemoveAttachment = viewModel::removeAttachment,
        onTogglePasswordVisibility = viewModel::onTogglePasswordVisibility,
        onToggleGenerator = viewModel::onToggleGenerator,
        onPassLengthChange = viewModel::onPassLengthChange,
        onGeneratePassword = viewModel::generatePassword,
        onToggleUpper = viewModel::onToggleUpper,
        onToggleLower = viewModel::onToggleLower,
        onToggleDigits = viewModel::onToggleDigits,
        onToggleSymbols = viewModel::onToggleSymbols,
        onShowMessage = viewModel::showMessage,
        onPickAttachmentFile = { attachmentPicker.launch("*/*") },
        onScanTotpQr = {
            val options = ScanOptions()
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            // LINT 修正：在 Composable 内部使用 StringResource 避免 stale 引用
            options.setPrompt(scanPrompt)
            options.setBeepEnabled(false)
            options.setOrientationLocked(true)
            qrScanner.launch(options)
        },
        modifier = modifier
    )
}

/** SAF 附件显示名查询 */
private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (e: Exception) {
        null
    }
}

/** 附件尺寸格式化 */
private fun formatAttachmentSize(bytes: Int): String {
    return if (bytes < 1024) "$bytes B" else "${bytes / 1024} KB"
}

/**
 * 无状态凭据编辑渲染组件
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryEditContent(
    uiState: EntryEditUiState,
    loadedPassword: CharArray?,
    isDirty: Boolean,
    snackbarHostState: SnackbarHostState,
    onBackClick: () -> Unit,
    onSaveClick: () -> Unit,
    onGroupChange: (String?) -> Unit,
    onIconChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChangeSecure: (CharArray) -> Unit,
    onUrlChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onTogglePasskey: () -> Unit,
    onTotpSecretChange: (String) -> Unit,
    onTagsInputChange: (String) -> Unit,
    onAutoTypeSequenceChange: (String) -> Unit,
    onOverrideUrlChange: (String) -> Unit,
    onAddCustomField: () -> Unit,
    onUpdateCustomField: (String, String, String, Boolean) -> Unit,
    onRemoveCustomField: (String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onToggleGenerator: () -> Unit,
    onPassLengthChange: (Float) -> Unit,
    onGeneratePassword: () -> Unit,
    onToggleUpper: () -> Unit,
    onToggleLower: () -> Unit,
    onToggleDigits: () -> Unit,
    onToggleSymbols: () -> Unit,
    onShowMessage: (UiMessage) -> Unit,
    onPickAttachmentFile: () -> Unit,
    onScanTotpQr: () -> Unit,
    modifier: Modifier = Modifier
) {
    val securityColors = LocalSecurityColors.current
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showIconPicker by remember { mutableStateOf(false) }

    val requestBack: () -> Unit = { if (isDirty) showDiscardDialog = true else onBackClick() }
    BackHandler(onBack = requestBack)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (uiState.entryId != null) stringResource(R.string.edit_title_edit) else stringResource(R.string.edit_title_new),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = requestBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSaveClick, enabled = !uiState.isReadOnly) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.cd_save),
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
            // 所属群组 / 文件夹选择
            if (uiState.availableGroups.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.edit_group_label),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = uiState.groupId == null,
                            onClick = { onGroupChange(null) },
                            label = { Text(stringResource(R.string.edit_group_root)) },
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
                    items(uiState.availableGroups.filter { !it.isRecycleBin }) { grp ->
                        val selected = uiState.groupId == grp.id
                        FilterChip(
                            selected = selected,
                            onClick = { onGroupChange(grp.id) },
                            label = { Text(grp.name) },
                            shape = CapsuleShape,
                            leadingIcon = {
                                Icon(
                                    imageVector = getVaultIcon(grp.iconName),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
            }

            // H4-只读整改：只读会话提示横幅
            if (uiState.isReadOnly) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.readonly_banner),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // 基本信息（带图标选择器）
            Text(
                text = stringResource(R.string.edit_basic_info),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )

            BentoCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .clickable { showIconPicker = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = getVaultIcon(uiState.iconName),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        OutlinedTextField(
                            value = uiState.title,
                            onValueChange = onTitleChange,
                            label = { Text(stringResource(R.string.edit_title_hint)) },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    OutlinedTextField(
                        value = uiState.url,
                        onValueChange = onUrlChange,
                        label = { Text(stringResource(R.string.edit_url_hint)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 账户与密码
            Text(
                text = stringResource(R.string.edit_account_pwd),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )

            BentoCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = uiState.username,
                        onValueChange = onUsernameChange,
                        label = { Text(stringResource(R.string.edit_username_hint)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // M1 整改：密码输入走 SecurePasswordField——显示用 String 仅存活于组件内部，
                    // CharArray 直达 ViewModel；既有密码经 loadedPassword 一次性预填
                    SecurePasswordField(
                        label = stringResource(R.string.edit_password_hint),
                        onPasswordChanged = onPasswordChangeSecure,
                        isPasswordVisible = uiState.isPasswordVisible,
                        initialPassword = loadedPassword,
                        initialKey = uiState.entryId ?: "new-entry",
                        trailingIcon = {
                            Row {
                                IconButton(onClick = onTogglePasswordVisibility) {
                                    Icon(
                                        imageVector = if (uiState.isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = stringResource(R.string.cd_toggle_password_visibility),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = onToggleGenerator) {
                                    Icon(
                                        imageVector = Icons.Default.ElectricBolt,
                                        contentDescription = stringResource(R.string.cd_password_generator),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    )

                    if (uiState.passwordLength > 0) {
                        PasswordStrengthBar(
                            entropyBits = (uiState.passwordLength * 4.5).toInt(),
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
                text = stringResource(R.string.edit_totp_section),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )

            BentoCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                OutlinedTextField(
                    value = uiState.totpSecret,
                    onValueChange = onTotpSecretChange,
                    label = { Text(stringResource(R.string.edit_totp_hint)) },
                    trailingIcon = {
                        // 断点5 整改：按钮直接呼起真实扫码
                        IconButton(onClick = onScanTotpQr) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = stringResource(R.string.cd_scan_qr),
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
                text = stringResource(R.string.edit_passkey_section),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )

            BentoCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = if (uiState.isPasskey) securityColors.passkeyContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainerLow,
                borderColor = if (uiState.isPasskey) securityColors.passkey.copy(alpha = 0.5f) else null
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (uiState.isPasskey) stringResource(R.string.edit_passkey_has_bound) else stringResource(R.string.edit_passkey_create),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.edit_passkey_desc),
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
                        Text(if (uiState.isPasskey) stringResource(R.string.edit_passkey_unbind) else stringResource(R.string.edit_passkey_bind))
                    }
                }
            }

            // 自定义字段编辑区 (动态添加/修改/删除)
            Text(
                text = stringResource(R.string.edit_custom_fields),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )

            BentoCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    uiState.customFields.forEach { field ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                OutlinedTextField(
                                    value = field.key,
                                    onValueChange = { onUpdateCustomField(field.id, it, field.value, field.isProtected) },
                                    label = { Text(stringResource(R.string.edit_field_key)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { onRemoveCustomField(field.id) }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.btn_delete),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = field.value,
                                onValueChange = { onUpdateCustomField(field.id, field.key, it, field.isProtected) },
                                label = { Text(stringResource(R.string.edit_field_value)) },
                                visualTransformation = if (field.isProtected) PasswordVisualTransformation() else VisualTransformation.None,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable {
                                    onUpdateCustomField(field.id, field.key, field.value, !field.isProtected)
                                }
                            ) {
                                Checkbox(
                                    checked = field.isProtected,
                                    onCheckedChange = { onUpdateCustomField(field.id, field.key, field.value, it) }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.edit_field_protected),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = onAddCustomField,
                        shape = CapsuleShape,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.edit_add_field))
                    }
                }
            }

            // 附件文件管理区
            Text(
                text = stringResource(R.string.edit_attachments),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )

            BentoCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    uiState.attachments.forEach { att ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${att.fileName} (${att.fileSizeFormatted})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            IconButton(onClick = { onRemoveAttachment(att.id) }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.btn_delete),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        // 断点1 整改：移除写死假附件名，呼起真实 SAF 文件选择器
                        onClick = onPickAttachmentFile,
                        shape = CapsuleShape,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.edit_add_attachment))
                    }
                }
            }

            // KP2A 能力补齐：高级属性（标签 / AutoType / Override URL）
            Text(
                text = stringResource(R.string.edit_extra_section),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )

            BentoCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = uiState.tagsInput,
                        onValueChange = onTagsInputChange,
                        label = { Text(stringResource(R.string.edit_tags_hint)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = uiState.autoTypeSequence,
                        onValueChange = onAutoTypeSequenceChange,
                        label = { Text(stringResource(R.string.edit_autotype_hint)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = uiState.overrideUrl,
                        onValueChange = onOverrideUrlChange,
                        label = { Text(stringResource(R.string.edit_override_url_hint)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 安全备注
            Text(
                text = stringResource(R.string.edit_notes),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )

            BentoCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                OutlinedTextField(
                    value = uiState.notes,
                    onValueChange = onNotesChange,
                    label = { Text(stringResource(R.string.edit_notes_hint)) },
                    minLines = 3,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 底部保存大按钮（H4-只读整改：只读会话禁用保存）
            Button(
                onClick = onSaveClick,
                enabled = !uiState.isReadOnly,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = CapsuleShape
            ) {
                Text(
                    text = stringResource(R.string.edit_save_btn),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }

    if (showIconPicker) {
        IconPickerDialog(
            selectedIconName = uiState.iconName,
            onSelectIcon = {
                onIconChange(it)
                showIconPicker = false
            },
            onDismiss = { showIconPicker = false }
        )
    }

    // 丢弃未保存更改确认弹窗
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.edit_discard_title)) },
            text = { Text(stringResource(R.string.edit_discard_desc)) },
            confirmButton = {
                TextButton(onClick = onBackClick) {
                    Text(stringResource(R.string.btn_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.btn_continue_edit))
                }
            }
        )
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
                text = stringResource(R.string.edit_generator_title, passLength.toInt()),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            IconButton(onClick = onRegenerate) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.cd_regenerate),
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
                label = { Text("#$%") }
            )
        }
    }
}
