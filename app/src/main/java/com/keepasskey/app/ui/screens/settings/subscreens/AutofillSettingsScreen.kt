package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
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
    // ISSUE-P3-42：会话授权宽限（默认关闭）
    onAutofillSessionGrantToggle: (Boolean) -> Unit = {},
    // TASK-44：自动填充黑名单真实条目与增删通道（替代原无写入方的禁用计数）
    blockedPackages: List<String> = emptyList(),
    onBlockAutofillPackage: (String) -> Boolean = { false },
    onUnblockAutofillPackage: (String) -> Boolean = { false },
    // ISSUE-P3-43 ③：保存侧独立黑名单（与填充黑名单分离：只禁保存、不禁填充）
    saveBlockedPackages: List<String> = emptyList(),
    onBlockSavePackage: (String) -> Boolean = { false },
    onUnblockSavePackage: (String) -> Boolean = { false },
    // ISSUE-P3-43 ②：字段签名级屏蔽（签名不可逆，故仅可回显条数并整体清除）
    blockedFieldCount: Int = 0,
    onClearBlockedFields: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showBlacklistDialog by remember { mutableStateOf(false) }
    var showSaveBlacklistDialog by remember { mutableStateOf(false) }
    var showFieldBlockDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_autofill),
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
                AutofillSectionHeader(R.string.autofill_section_provider)
            }

            // ISSUE-P3-41：服务健康自检（实时探测系统侧与本应用侧链路状态并给出修复指引）
            item {
                AutofillHealthCard(appEnabled = uiState.autofillServiceEnabled)
            }

            item {
                AutofillProviderCard(
                    uiState = uiState,
                    onCredentialProviderToggle = onCredentialProviderToggle,
                    onPasskeySupportToggle = onPasskeySupportToggle,
                    onAutofillServiceToggle = onAutofillServiceToggle,
                    onAutofillSessionGrantToggle = onAutofillSessionGrantToggle
                )
            }

            // 2. 体验与输入法协同 (KP2A 特性)
            item {
                AutofillSectionHeader(R.string.autofill_section_ux)
            }

            item {
                AutofillUxCard(
                    uiState = uiState,
                    onInlineSuggestionsToggle = onInlineSuggestionsToggle,
                    onAutoReturnFromQueryToggle = onAutoReturnFromQueryToggle,
                    onAutoClearClipboardToggle = onAutoClearClipboardToggle
                )
            }

            // 3. 两步验证与 TOTP 联动 (KP2A 特性)
            item {
                AutofillSectionHeader(R.string.autofill_section_totp)
            }

            item {
                AutofillTotpCard(
                    uiState = uiState,
                    onAutofillCopyTotpToggle = onAutofillCopyTotpToggle,
                    onAutofillShowTotpNotificationToggle = onAutofillShowTotpNotificationToggle
                )
            }

            // 4. 智能识别、凭证保存与兼容策略 (KP2A 特性)
            item {
                AutofillSectionHeader(R.string.autofill_section_capture)
            }

            item {
                AutofillCaptureCard(
                    uiState = uiState,
                    onOfferSaveCredentialsToggle = onOfferSaveCredentialsToggle,
                    onOverrideNoAutofillToggle = onOverrideNoAutofillToggle,
                    onSkipDalVerificationToggle = onSkipDalVerificationToggle,
                    blockedPackages = blockedPackages,
                    saveBlockedPackages = saveBlockedPackages,
                    blockedFieldCount = blockedFieldCount,
                    onOpenBlacklist = { showBlacklistDialog = true },
                    onOpenSaveBlacklist = { showSaveBlacklistDialog = true },
                    onOpenFieldBlock = { showFieldBlockDialog = true }
                )
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // 填充黑名单管理对话框
    // TASK-36 整改：此前渲染两条写死的示例条目（银行/门户）并挂空 onClick 删除按钮，属假数据回显，
    // 已诚实化下架；TASK-44 补齐真实生命周期——展示持久化包名条目、支持删除与按包名新增，
    // 填充侧（AutofillService / CredentialProviderService）命中即 fail-closed 不下发。
    // ISSUE-P3-43：对话框实现搬至 AutofillBlocklistDialogs.kt 并与保存侧共用（文案参数化）。
    if (showBlacklistDialog) {
        PackageBlocklistManageDialog(
            title = stringResource(R.string.autofill_blacklist_dialog_title),
            description = stringResource(R.string.autofill_blacklist_dialog_desc),
            emptyText = stringResource(R.string.autofill_blacklist_empty),
            addHint = stringResource(R.string.autofill_blacklist_add_hint),
            blockedPackages = blockedPackages,
            onDismiss = { showBlacklistDialog = false },
            onAdd = onBlockAutofillPackage,
            onRemove = onUnblockAutofillPackage
        )
    }

    // ISSUE-P3-43 ③：保存侧黑名单管理（命中后 onSaveRequest 静默跳过，不影响填充）
    if (showSaveBlacklistDialog) {
        PackageBlocklistManageDialog(
            title = stringResource(R.string.autofill_save_blacklist_dialog_title),
            description = stringResource(R.string.autofill_save_blacklist_dialog_desc),
            emptyText = stringResource(R.string.autofill_save_blacklist_empty),
            addHint = stringResource(R.string.autofill_blacklist_add_hint),
            blockedPackages = saveBlockedPackages,
            onDismiss = { showSaveBlacklistDialog = false },
            onAdd = onBlockSavePackage,
            onRemove = onUnblockSavePackage
        )
    }

    // ISSUE-P3-43 ②：字段签名级屏蔽——签名不可逆，只提供条数回显与整体清除
    if (showFieldBlockDialog) {
        FieldBlocklistClearDialog(
            blockedFieldCount = blockedFieldCount,
            onDismiss = { showFieldBlockDialog = false },
            onConfirmClear = onClearBlockedFields
        )
    }
}
