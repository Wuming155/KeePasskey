package com.keepasskey.app.ui.screens.edit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.keepasskey.app.R
import com.keepasskey.app.security.ApplyObscuredTouchFilter
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.components.IconPickerDialog
import com.keepasskey.app.ui.components.PasswordStrengthBar
import com.keepasskey.app.ui.components.SecurePasswordField
import com.keepasskey.app.ui.components.getVaultIcon
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.theme.CapsuleShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** TASK-15：自定义图标降采样目标上限（KDBX 生态约定小尺寸 PNG，KeePassXC 默认 128px） */
private const val CUSTOM_ICON_MAX_PX = 128

/**
 * TASK-15：解码任意图片字节并降采样至 ≤[CUSTOM_ICON_MAX_PX] 的 PNG（KDBX CustomIcon 载荷）。
 * 解码失败（非图片/损坏数据）返回 null，调用方如实上浮错误。
 */
private fun decodeAndScaleToPng(bytes: ByteArray): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    // 先按 2 的幂次抽样粗降采样，再精确缩放至目标上限，控制峰值内存
    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= CUSTOM_ICON_MAX_PX) {
        sampleSize *= 2
    }
    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null

    val scale = minOf(1f, CUSTOM_ICON_MAX_PX.toFloat() / maxOf(src.width, src.height))
    val scaled = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true
        )
    } else {
        src
    }
    val output = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.PNG, 100, output)
    if (scaled !== src) scaled.recycle()
    src.recycle()
    return output.toByteArray()
}

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
    // 遮挡触摸过滤（ISSUE-P2-09 / P3-12）
    ApplyObscuredTouchFilter()
    LaunchedEffect(entryId) {
        if (entryId != null) {
            viewModel.loadEntry(entryId)
        }
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // M1 整改：既有条目密码的一次性预填通道（SecurePasswordField 消费后即清零）
    val loadedPassword by viewModel.loadedPassword.collectAsStateWithLifecycle()
    // TASK-10：TOTP 种子与受保护自定义字段明文的一次性预填通道（语义同 loadedPassword）
    val loadedTotpSecret by viewModel.loadedTotpSecret.collectAsStateWithLifecycle()
    val loadedProtectedFields by viewModel.loadedProtectedFields.collectAsStateWithLifecycle()
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

    // 断点5 整改：TOTP 二维码真实扫描（zxing-embedded），扫描结果直接回填种子输入框。
    // TASK-10：扫码结果（框架边界 String）即刻转为 CharArray 走安全桥接上行
    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { viewModel.onTotpSecretChangeSecure(it.toCharArray()) }
    }

    // TASK-15：自定义图标——系统相册（Photo Picker）选图，读取字节后降采样为 ≤128px PNG 上传
    val customIconOptions by viewModel.customIconOptions.collectAsStateWithLifecycle()
    var decodedCustomIcons by remember { mutableStateOf<List<com.keepasskey.app.ui.components.CustomIconItem>>(emptyList()) }
    LaunchedEffect(customIconOptions) {
        // PNG → ImageBitmap 解码为 CPU 操作，移出主线程
        decodedCustomIcons = withContext(Dispatchers.Default) {
            customIconOptions.mapNotNull { (id, bytes) ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                    com.keepasskey.app.ui.components.CustomIconItem(id, it.asImageBitmap())
                }
            }
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    val pngBytes = rawBytes?.let { decodeAndScaleToPng(it) }
                    withContext(Dispatchers.Main) {
                        if (pngBytes != null) {
                            viewModel.onCustomIconUploaded(pngBytes)
                        } else {
                            viewModel.showMessage(UiMessage(R.string.icon_invalid_not_png))
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        viewModel.showMessage(UiMessage(R.string.icon_invalid_not_png))
                    }
                }
            }
        }
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
        loadedTotpSecret = loadedTotpSecret,
        loadedProtectedFields = loadedProtectedFields,
        isDirty = uiState.isDirty,
        snackbarHostState = snackbarHostState,
        onBackClick = onBackClick,
        onSaveClick = viewModel::saveEntry,
        onGroupChange = viewModel::onGroupChange,
        onIconChange = viewModel::onIconChange,
        customIconOptions = decodedCustomIcons,
        onSelectCustomIcon = viewModel::onCustomIconSelected,
        onUploadCustomIcon = {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onTitleChange = viewModel::onTitleChange,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChangeSecure = viewModel::onPasswordChangeSecure,
        onUrlChange = viewModel::onUrlChange,
        onNotesChange = viewModel::onNotesChange,
        onTogglePasskey = viewModel::onTogglePasskey,
        onTotpSecretChangeSecure = viewModel::onTotpSecretChangeSecure,
        onUpdateProtectedFieldValue = viewModel::updateProtectedFieldValue,
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

/**
 * 无状态凭据编辑渲染组件
 *
 * TASK-21 拆分：TOTP/Passkey/自定义字段/附件/高级属性五个自包含区块
 * 及密码生成器微件已搬移至 EntryEditComponents.kt，组合顺序不变。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryEditContent(
    uiState: EntryEditUiState,
    loadedPassword: CharArray?,
    loadedTotpSecret: CharArray?,
    loadedProtectedFields: Map<String, CharArray>,
    isDirty: Boolean,
    snackbarHostState: SnackbarHostState,
    onBackClick: () -> Unit,
    onSaveClick: () -> Unit,
    onGroupChange: (String?) -> Unit,
    onIconChange: (String) -> Unit,
    // TASK-15：自定义图标扩展（库内图标池 / 选择回调 / 相册上传入口）
    customIconOptions: List<com.keepasskey.app.ui.components.CustomIconItem> = emptyList(),
    onSelectCustomIcon: (String) -> Unit = {},
    onUploadCustomIcon: () -> Unit = {},
    onTitleChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChangeSecure: (CharArray) -> Unit,
    onUrlChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onTogglePasskey: () -> Unit,
    onTotpSecretChangeSecure: (CharArray) -> Unit,
    onUpdateProtectedFieldValue: (String, CharArray) -> Unit,
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
                            // TASK-15：选中自定义图标时渲染位图，否则回退标准图标
                            val selectedCustom = customIconOptions.firstOrNull { it.id == uiState.customIconId }
                            if (selectedCustom != null) {
                                Image(
                                    bitmap = selectedCustom.bitmap,
                                    contentDescription = null,
                                    modifier = Modifier.size(34.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = getVaultIcon(uiState.iconName),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
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
            EntryEditTotpSection(
                entryId = uiState.entryId,
                loadedTotpSecret = loadedTotpSecret,
                onTotpSecretChangeSecure = onTotpSecretChangeSecure,
                onScanTotpQr = onScanTotpQr
            )

            // 通行密钥 Passkey 注册绑定
            EntryEditPasskeySection(
                isPasskey = uiState.isPasskey,
                onTogglePasskey = onTogglePasskey
            )

            // 自定义字段编辑区 (动态添加/修改/删除)
            EntryEditCustomFieldsSection(
                customFields = uiState.customFields,
                loadedProtectedFields = loadedProtectedFields,
                onAddCustomField = onAddCustomField,
                onUpdateCustomField = onUpdateCustomField,
                onUpdateProtectedFieldValue = onUpdateProtectedFieldValue,
                onRemoveCustomField = onRemoveCustomField
            )

            // 附件文件管理区
            EntryEditAttachmentsSection(
                attachments = uiState.attachments,
                onPickAttachmentFile = onPickAttachmentFile,
                onRemoveAttachment = onRemoveAttachment
            )

            // KP2A 能力补齐：高级属性（标签 / AutoType / Override URL）
            EntryEditExtraSection(
                tagsInput = uiState.tagsInput,
                onTagsInputChange = onTagsInputChange,
                autoTypeSequence = uiState.autoTypeSequence,
                onAutoTypeSequenceChange = onAutoTypeSequenceChange,
                overrideUrl = uiState.overrideUrl,
                onOverrideUrlChange = onOverrideUrlChange
            )

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
            onDismiss = { showIconPicker = false },
            // TASK-15：自定义图标扩展段
            customIcons = customIconOptions,
            selectedCustomIconId = uiState.customIconId,
            onSelectCustomIcon = {
                onSelectCustomIcon(it)
                showIconPicker = false
            },
            onUploadClick = onUploadCustomIcon
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
