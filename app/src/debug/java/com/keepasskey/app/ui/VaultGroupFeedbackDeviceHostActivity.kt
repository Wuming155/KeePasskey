package com.keepasskey.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.keepasskey.app.di.AutofillDeviceGraph
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.app.ui.screens.vault.VaultListActionController
import com.keepasskey.app.ui.screens.vault.VaultListActionHost
import com.keepasskey.app.ui.screens.vault.VaultListBatchModeTopBar
import com.keepasskey.app.ui.screens.vault.VaultListContent
import com.keepasskey.app.ui.screens.vault.VaultListUiState
import com.keepasskey.app.ui.theme.KeePasskeyTheme
import dagger.hilt.android.EntryPointAccessors

/**
 * `ISSUE-P3-644`：设备侧用例的**列表页反馈宿主**（最小 UI 测试基建）。
 *
 * 挂载的是**生产**组合件：`VaultListContent`（含分组行菜单、图标选择对话框、批量顶栏）
 * 与 `VaultListActionController`（真实写编排），消息经生产的 `AppSnackbarChannel` →
 * `AppGlobalSnackbarHost` 呈现，数据来自生产 `VaultRepository` 与真实活动会话。
 * 因此用例点到的是真实按钮、真实文案资源，落盘也是真实文件。
 *
 * 与生产 `VaultListScreen` 的差别仅两处，且都在「装配」而不在「行为」：
 * 1. 状态由本宿主直接订阅仓库流装配（生产由 `VaultListProjection` 装配），
 *    只填本项验收所需字段；
 * 2. 不注入 ViewModel（导航参数与设置页依赖图与本项链路无关）。
 *
 * 为什么落在 `src/debug`：`ActivityScenario.launch` 只能启动**被测应用进程**内的组件
 * （同 `DriftDialogHostActivity` 的实测依据）。release 变体不含 debug 源集 ⇒ 生产包一字未改。
 */
class VaultGroupFeedbackDeviceHostActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MODE = "vaultGroupFeedbackMode"

        /** 可写会话的列表页（成功 / 失败 / 漂移三态共用） */
        const val MODE_LIST = "list"

        /** 只读会话的列表页（菜单禁用 + 原因 + 无批量入口） */
        const val MODE_LIST_READ_ONLY = "list_read_only"

        /** 只读会话的批量顶栏（三个写按钮置灰 + 原因） */
        const val MODE_BATCH_READ_ONLY = "batch_read_only"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_LIST
        val repository = EntryPointAccessors.fromApplication(
            applicationContext,
            AutofillDeviceGraph::class.java
        ).vaultRepository()

        setContent {
            KeePasskeyTheme {
                when (mode) {
                    MODE_BATCH_READ_ONLY -> BatchReadOnlyPreview()
                    else -> VaultListHost(repository = repository)
                }
            }
        }
    }

    @Composable
    private fun BatchReadOnlyPreview() {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                VaultListBatchModeTopBar(
                    selectedCount = 2,
                    onClearBatch = {},
                    onSelectAllBatch = {},
                    onBatchMoveClick = {},
                    onBatchDelete = {},
                    isReadOnly = true
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        }
    }

    @Composable
    private fun VaultListHost(repository: com.keepasskey.app.data.repository.VaultRepository) {
        val scope = rememberCoroutineScope()
        // 会话只读态是**可变**的（解锁 / 锁库 / 只读打开），不能只在组合首次采样一次；
        // 此处按会话状态流持续观察，避免设备用例在锁库后仍拿到锁前的 false。
        val session = EntryPointAccessors.fromApplication(
            applicationContext,
            com.keepasskey.app.di.AutofillDeviceGraph::class.java
        ).databaseSession()
        val sessionState by session.state.collectAsState()
        // 只读态随「解锁 / 锁库 / 只读打开」变化；以会话状态作为重组触发点后重新取真实值。
        // 宿主的 MODE_LIST_READ_ONLY 只表达意图，真实来源是仓库/会话——两者不一致时以仓库为准。
        val readOnly = remember(sessionState) { repository.isSessionReadOnly() }
        var groups by remember { mutableStateOf<List<VaultGroup>>(emptyList()) }
        var entries by remember { mutableStateOf<List<UiVaultEntry>>(emptyList()) }

        LaunchedEffect(repository) {
            repository.getGroups().collect { groups = it }
        }
        LaunchedEffect(repository) {
            repository.getEntries().collect { entries = it }
        }

        val controller = remember(repository) {
            VaultListActionController(
                repository = repository,
                scope = scope,
                strings = StringsProvider { id, args -> getString(id, *args) },
                clipboardSecurityManager = null,
                host = VaultListActionHost(
                    isReadOnly = { readOnly },
                    currentGroupId = { null },
                    currentGroups = { groups },
                    currentEntryIds = { entries.map { it.id } },
                    // 与生产同一条消息通道：AppSnackbarChannel → AppGlobalSnackbarHost
                    onMessage = { AppSnackbarChannel.trySend(AppSnackbarEvent(it)) }
                )
            )
        }
        val isBatchMode by controller.isBatchMode.collectAsState()
        val selectedIds by controller.selectedEntryIds.collectAsState()

        val uiState = VaultListUiState(
            isLoading = false,
            databaseName = "P3-644 device vault",
            currentGroups = groups,
            allGroups = groups,
            entries = entries,
            totalEntriesCount = entries.size,
            isReadOnly = readOnly,
            isBatchMode = isBatchMode,
            selectedEntryIds = selectedIds
        )

        // VaultListContent 自身就是完整 Scaffold（含顶栏 / FAB / 对话框宿主），故本层只叠
        // 生产全局 Snackbar 宿主——与 `KeePasskeyApp` 的 `snackbarHost` 槽同一件、同一位置语义。
        Box(modifier = Modifier.fillMaxSize()) {
            VaultListContent(
                uiState = uiState,
                onSearchQueryChange = {},
                onSortOptionSelect = {},
                onGroupClick = {},
                onNavigateUp = {},
                onNavigateToBreadcrumb = {},
                onEntryClick = {},
                onEntryLongClick = { id -> controller.startBatchMode(id) },
                onCopyPassword = {},
                onCopyUsername = {},
                onAddEntryClick = {},
                onCreateGroup = { name, icon -> controller.createGroup(name, icon) },
                onRenameGroup = { group, name -> controller.renameGroup(group, name) },
                onChangeGroupIcon = { group, icon -> controller.changeGroupIcon(group, icon) },
                onDeleteGroup = { id -> controller.deleteGroup(id) },
                onRestoreEntry = { id -> controller.restoreEntry(id) },
                onPurgeEntry = { id -> controller.purgeEntry(id) },
                onEmptyRecycleBin = { controller.emptyRecycleBin() },
                onTriggerSync = {},
                onSelectAllBatch = { controller.selectAllEntries() },
                onClearBatch = { controller.clearBatchSelection() },
                onBatchDelete = { controller.batchDeleteSelected() },
                onBatchMove = { target -> controller.batchMoveSelected(target) },
                onSelectEntriesBatch = { controller.startBatchMode("") }
            )
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                AppGlobalSnackbarHost()
            }
        }
    }
}
