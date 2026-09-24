package com.keepasskey.app.ui.screens.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.security.ApplyObscuredTouchFilter
import com.keepasskey.app.ui.components.CustomIconItem
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.resolveText

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

    // ISSUE-P3-31 批次 C：三个系统选择器（SAF 附件 / TOTP 扫码 / 相册图标）
    // 与图标池解码已收敛至 EntryEditPickers.kt，语义逐条不变
    val scope = rememberCoroutineScope()
    val pickers = rememberEntryEditPickers(viewModel = viewModel, scope = scope)

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
        customIconOptions = pickers.decodedCustomIcons,
        onSelectCustomIcon = viewModel::onCustomIconSelected,
        onUploadCustomIcon = pickers.pickCustomIcon,
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
        onToggleExpiry = viewModel::onToggleExpiry,
        onExpiryDateSelected = viewModel::onExpiryDateSelected,
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
        onPickAttachmentFile = pickers.pickAttachment,
        onScanTotpQr = pickers.scanTotpQr,
        modifier = modifier
    )
}

/**
 * 无状态凭据编辑渲染组件
 *
 * TASK-21 拆分：TOTP/Passkey/自定义字段/附件/高级属性五个自包含区块
 * 及密码生成器微件已搬移至 EntryEditComponents.kt，组合顺序不变。
 *
 * ISSUE-P3-31 批次 C：基础表单五分节（分组 / 只读横幅 / 基本信息 / 账户与密码 / 备注）
 * 进一步搬至 EntryEditFormSections.kt，图片降采样搬至 EntryEditIconCodec.kt；
 * 本函数仅保留 Scaffold 骨架、分节装配顺序与两个弹窗，**渲染顺序逐条不变**。
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
    customIconOptions: List<CustomIconItem> = emptyList(),
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
    // ISSUE-P3-310：过期编辑两态
    onToggleExpiry: (Boolean) -> Unit = {},
    onExpiryDateSelected: (java.time.LocalDate) -> Unit = {},
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
            EntryEditTopBar(
                entryId = uiState.entryId,
                isReadOnly = uiState.isReadOnly,
                requestBack = requestBack,
                onSaveClick = onSaveClick
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
            EntryEditGroupSection(
                uiState = uiState,
                onGroupChange = onGroupChange
            )

            // H4-只读整改：只读会话提示横幅
            EntryEditReadOnlyBanner(isReadOnly = uiState.isReadOnly)

            // 基本信息（带图标选择器）
            EntryEditBasicInfoSection(
                uiState = uiState,
                customIconOptions = customIconOptions,
                onIconClick = { showIconPicker = true },
                onTitleChange = onTitleChange,
                onUrlChange = onUrlChange
            )

            // 账户与密码（含密码生成器）
            EntryEditAccountSection(
                uiState = uiState,
                loadedPassword = loadedPassword,
                onUsernameChange = onUsernameChange,
                onPasswordChangeSecure = onPasswordChangeSecure,
                onTogglePasswordVisibility = onTogglePasswordVisibility,
                onToggleGenerator = onToggleGenerator,
                onPassLengthChange = onPassLengthChange,
                onGeneratePassword = onGeneratePassword,
                onToggleUpper = onToggleUpper,
                onToggleLower = onToggleLower,
                onToggleDigits = onToggleDigits,
                onToggleSymbols = onToggleSymbols
            )

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

            // KP2A 能力补齐：高级属性（标签 / AutoType / Override URL / 过期编辑）
            EntryEditExtraSection(
                tagsInput = uiState.tagsInput,
                onTagsInputChange = onTagsInputChange,
                autoTypeSequence = uiState.autoTypeSequence,
                onAutoTypeSequenceChange = onAutoTypeSequenceChange,
                overrideUrl = uiState.overrideUrl,
                onOverrideUrlChange = onOverrideUrlChange,
                expiresEnabled = uiState.expiresEnabled,
                expiryDate = uiState.expiryDate,
                onToggleExpiry = onToggleExpiry,
                onExpiryDateSelected = onExpiryDateSelected
            )

            // 安全备注
            EntryEditNotesSection(
                notes = uiState.notes,
                onNotesChange = onNotesChange
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 底部保存大按钮（§165 抽为 EntryEditSaveButton）
            EntryEditSaveButton(isReadOnly = uiState.isReadOnly, onSaveClick = onSaveClick)

            Spacer(modifier = Modifier.height(30.dp))
        }
    }

    if (showIconPicker) {
        EntryEditIconPickerDialog(
            iconName = uiState.iconName,
            customIconId = uiState.customIconId,
            customIcons = customIconOptions,
            onSelectIcon = { onIconChange(it); showIconPicker = false },
            onSelectCustomIcon = { onSelectCustomIcon(it); showIconPicker = false },
            onUploadClick = onUploadCustomIcon,
            onDismiss = { showIconPicker = false }
        )
    }

    // 丢弃未保存更改确认弹窗（§165 抽为 EntryEditDiscardDialog）
    if (showDiscardDialog) {
        EntryEditDiscardDialog(
            onDiscard = onBackClick,
            onKeepEditing = { showDiscardDialog = false }
        )
    }
}
