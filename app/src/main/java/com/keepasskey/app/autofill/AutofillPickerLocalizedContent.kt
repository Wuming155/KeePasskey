package com.keepasskey.app.autofill

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.ApplyObscuredTouchFilter

/**
 * 选择器页内容树（`ISSUE-P3-390` 自 [AutofillPickerActivity] 拆出以控行数分档）。
 *
 * 语言接线：本地化上下文由 Activity 经 `AppShellLocalization.localizedContextForAppLanguage`
 * 派生后下传；本组合体只负责提供 `LocalContext` / `LocalConfiguration` 与页面装配，
 * 不再自行读取语言设置。
 */
@Composable
internal fun AutofillPickerLocalizedContent(
    localizedContext: Context,
    requester: AutofillPickerRequester?,
    intent: Intent,
    viewModel: AutofillPickerViewModel,
    vaultRepository: VaultRepository,
    settingsStore: ExtendedSettingsStore,
    isCompleted: () -> Boolean,
    onPick: (String) -> Unit,
    onCancel: () -> Unit,
    onBlockField: (AutofillFieldRole) -> Unit,
    onCreateNew: () -> Unit
) {
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedContext.resources.configuration
    ) {
        var query by remember { mutableStateOf("") }
        val entries by viewModel.entries.collectAsStateWithLifecycle()
        val results = remember(query, entries) { AutofillEntrySearch.filter(entries, query) }
        // ISSUE-P3-571 方案A：写回询问状态由 VM 持有（交付链经此挂起等待用户处置）
        val bindingWriteBackAsk by viewModel.bindingWriteBackAsk.collectAsStateWithLifecycle()

        ApplyObscuredTouchFilter()
        AutofillPickerScreen(
            query = query,
            onQueryChange = { query = it },
            results = results,
            onPick = onPick,
            onCancel = onCancel,
            bindingWriteBackAsk = bindingWriteBackAsk,
            onBindingWriteBackResult = viewModel::completeBindingWriteBack,
            // ISSUE-P2-70：强制展示请求方身份（包名 / 应用名 / 签名摘要 / 表单自报域）
            requester = requester,
            // 仅在本次请求确实识别到对应框时提供屏蔽入口（否则是无对象的假按钮）
            canBlockUsername = intent.readAutofillId(AutofillPickerActivity.EXTRA_USERNAME_ID) != null,
            canBlockPassword = intent.readAutofillId(AutofillPickerActivity.EXTRA_PASSWORD_ID) != null,
            onBlockField = onBlockField,
            // ISSUE-P3-345 / PD-51：只读会话不呈现新建入口（控件不许骗人）；
            // 入口 Intent 构造集中在 PasswordDraftActivity.createIntent
            // ISSUE-P3-376：无匹配就地新建入口开关（默认开启）——关闭即空态不呈现新建按钮
            canCreateNew = !vaultRepository.isSessionReadOnly() &&
                settingsStore.isAutofillOfferCreateEntryEnabled(),
            onCreateNew = {
                if (!isCompleted()) onCreateNew()
            }
        )
    }
}
