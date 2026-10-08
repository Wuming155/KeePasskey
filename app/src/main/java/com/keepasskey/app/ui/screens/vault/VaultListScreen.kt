package com.keepasskey.app.ui.screens.vault

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.ui.screens.edit.TotpScanDialog
import com.keepasskey.app.ui.theme.AppThemeMode

/**
 * 密码库列表页壳（状态订阅 / 返回键语义 / 扫码与确认对话框宿主接线）。
 *
 * §280 规模门禁同批拆分：Scaffold 与 LazyColumn 分区装配（`VaultListContent`）逐字迁至
 * 同包 [VaultListContent.kt]，筛选 / 子库分区段落见 [VaultListScreenSections.kt]——
 * 公开 API 与渲染语义零变化。
 */
@Composable
fun VaultListScreen(
    currentTheme: AppThemeMode = AppThemeMode.SYSTEM,
    onThemeToggle: () -> Unit = {},
    onEntryClick: (String) -> Unit,
    onAddEntryClick: (String?) -> Unit,
    /**
     * `ISSUE-P3-337` 口径 4：扫码导入通行密钥成功后按 id 打开该条目编辑页。
     * 只传条目 id —— 私钥等凭据值**一律不经路由承载**（`P2-105` 同源红线）。
     */
    onNavigateToEntryEdit: (String) -> Unit = {},
    // ISSUE-P3-51：从模板新建（groupId 为新建落点，templateId 为选中模板）
    onAddFromTemplateClick: (String?, String) -> Unit = { _, _ -> },
    onLockClick: () -> Unit = {},
    onNavigateToConflictResolver: () -> Unit = {},
    /**
     * ISSUE-P3-17：`showKillAppOption` 开启且宿主可终止时非空——非空才呈现「彻底退出应用」入口。
     * 动作本体由 host（KeePasskeyApp）持有 Activity 上下文执行，本页只负责呈现与上行。
     */
    onKillApp: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: VaultListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // ISSUE-P2-356：搜索框显示态走未防抖通道（防抖只作用于过滤流，快速连打不回吞）
    val searchQuery by viewModel.searchQueryDisplay.collectAsStateWithLifecycle()
    // ISSUE-P2-89：TOTP 的两条实时通道只在此处**取状态对象本身**（不读 .value），
    // 读取动作下沉到列表行内的徽标——故本页组合作用域不会因每秒 tick 而失效。
    val totpNowSecondsState = viewModel.totpNowSeconds.collectAsStateWithLifecycle()
    val totpLiveCodesState = viewModel.totpLiveCodes.collectAsStateWithLifecycle()

    // 顶栏扫码对话框可见性（otpauth URI 解码后由 ViewModel 直接创建验证码条目）
    var showScanDialog by remember { mutableStateOf(false) }
    // PD-47：扫码对话框 FLAG_SECURE 跟随设置页「禁止截屏与录屏」开关
    val flagSecureEnabled by viewModel.flagSecureEnabled.collectAsStateWithLifecycle()

    // ISSUE-P3-17：页面每次进入组合时刷新进阶显示偏好快照
    // （ExtendedSettingsStore 只有同步快照 API，设置页改动返回本页即生效）
    LaunchedEffect(Unit) { viewModel.onScreenEntered() }

    val batchGuide = rememberBatchSelectGuideStore() // ISSUE-P3-360 AC④b：首次长按进入批量的一次性引导（持久化标记，全生命周期仅一次；消费点在 onEntryLongClick）
    // ISSUE-P3-359 AC④：消息发布已在 ViewModel 内直连全局通道（publishVaultMessage），
    // 呈现由外壳唯一宿主 AppGlobalSnackbarHost 承担——本页不再持有 SnackbarHost / 屏级编排

    // 优雅的返回键处理：批量选择 → 清空搜索 → 返回上一级 → 根目录交还系统。
    // ISSUE-P2-356：清空判定读即时回显态，与输入框同拍（防抖值会漏判窗口期）。
    BackHandler(
        enabled = uiState.isBatchMode ||
                searchQuery.isNotEmpty() ||
                uiState.currentGroupId != null
    ) {
        when {
            uiState.isBatchMode -> viewModel.clearBatchSelection()
            searchQuery.isNotEmpty() -> viewModel.onSearchQueryChange("")
            uiState.currentGroupId != null -> viewModel.navigateUp()
        }
    }

    VaultListContent(
        uiState = uiState,
        searchQuery = searchQuery,
        onSearchQueryChange = viewModel::onSearchQueryChange,
        onSortOptionSelect = viewModel::setSortOption,
        // ISSUE-P3-297 处置③：标签 / 收藏筛选档上行
        onFavoriteFilterChange = viewModel::onFavoriteFilterChange,
        onTagFilterChange = viewModel::onTagFilterChange,
        // ISSUE-P3-439 / ISSUE-P2-544：高级搜索选项上行（入口在顶栏溢出菜单 → 选项对话框；
        // 选项写回进阶偏好通道，状态与持久化编排在 VaultSearchAdvancedStore）
        onSearchFieldToggle = { viewModel.searchAdvancedStore.toggleField(it) },
        onSearchExcludeExpiredChange = viewModel.searchAdvancedStore::setExcludeExpired,
        onSearchCaseSensitiveChange = viewModel.searchAdvancedStore::setCaseSensitive,
        onGroupClick = viewModel::enterGroup,
        onNavigateUp = viewModel::navigateUp,
        onNavigateToBreadcrumb = viewModel::navigateToBreadcrumb,
        onEntryClick = { entryId ->
            if (uiState.isBatchMode) {
                viewModel.toggleEntrySelection(entryId)
            } else if (!viewModel.prepareSearchWriteBack(entryId)) {
                // ISSUE-P3-442：命中自愈条件时先询问（不导航），处置后再由下方对话框回调导航
                onEntryClick(entryId)
            }
        },
        onEntryLongClick = { entryId ->
            if (!uiState.isBatchMode) {
                viewModel.startBatchMode(entryId)
                if (batchGuide.consumeFirstGuide()) publishBatchSelectGuide()
            } else {
                viewModel.toggleEntrySelection(entryId)
            }
        },
        onCopyPassword = viewModel::copyPassword,
        onCopyUsername = viewModel::copyUsername,
        // ISSUE-P3-184：行内验证码徽标一次点击复制当前 TOTP 码
        onCopyTotp = viewModel::copyTotpCode,
        onAddEntryClick = { onAddEntryClick(uiState.currentGroupId) },
        onCreateEntryFromSearch = if (uiState.isReadOnly || uiState.isInsideRecycleBin) null else { {
            viewModel.beginCreateEntryFromSearch(); onAddEntryClick(uiState.currentGroupId)
        } },
        onClearSearch = { viewModel.onSearchQueryChange("") },
        onCreateFromTemplate = { templateId -> onAddFromTemplateClick(uiState.currentGroupId, templateId) },
        onCreateGroup = viewModel::createGroup,
        onRenameGroup = viewModel::renameGroup,
        onChangeGroupIcon = viewModel::changeGroupIcon,
        onDeleteGroup = viewModel::deleteGroup,
        onRestoreEntry = viewModel::restoreEntry,
        onPurgeEntry = viewModel::purgeEntry,
        onEmptyRecycleBin = viewModel::emptyRecycleBin,
        onTriggerSync = viewModel::triggerPullRefresh,
        onNavigateToConflictResolver = onNavigateToConflictResolver,
        onLockClick = onLockClick,
        onSelectAllBatch = viewModel::selectAllEntries,
        onClearBatch = viewModel::clearBatchSelection,
        onBatchDelete = viewModel::batchDeleteSelected,
        onBatchMove = viewModel::batchMoveSelected,
        onSelectEntriesBatch = { viewModel.startBatchMode("") }, // ISSUE-P3-360 AC④a：溢出菜单「选择」项进入批量模式（空 id = 不预选任何条目）
        onKillApp = onKillApp,
        onScanClick = if (uiState.isReadOnly) null else ({ showScanDialog = true }),
        onAutoActivateSearchConsumed = viewModel::consumeAutoActivateSearch,
        // ISSUE-P2-89：只传状态对象（此处不读 .value），秒级读取面收敛到列表行徽标
        totpNowSeconds = totpNowSecondsState,
        totpLiveCodes = totpLiveCodesState,
        modifier = modifier
    )

    // 顶栏扫码对话框（复用编辑页受保护取景组件；解码结果经 CharArray 上行直接落库，
    // 对话框解码命中后自行回调 onDismiss 关闭）
    if (showScanDialog) {
        TotpScanDialog(
            flagSecureEnabled = flagSecureEnabled,
            onDecoded = viewModel::onQrCodeDecoded,
            onDismiss = { showScanDialog = false }
        )
    }

    // ISSUE-P3-337 Q2：扫到通行密钥载荷 → **先确认再落库**（正文与一次性导航意图的消费
    // 收在 PasskeyImportConfirmationHost 内，见该函数 KDoc）
    PasskeyImportConfirmationHost(
        viewModel = viewModel,
        flagSecureEnabled = flagSecureEnabled,
        onNavigateToEntryEdit = onNavigateToEntryEdit
    )

    // ISSUE-P2-291 AC②：库身份绑定不符的显式二次确认（下拉刷新被拦截后置位；
    // 确认 = 整库覆盖并改绑，取消 = 保持本地与云端现状）
    // ISSUE-P2-529 AC①：第三条出口 = 先把云端副本另存为本地独立库，再覆盖（两份都留）
    val pendingBindingTakeover by viewModel.pendingBindingTakeover.collectAsStateWithLifecycle()
    if (pendingBindingTakeover) {
        VaultBindingTakeoverDialog(
            onConfirm = viewModel::confirmBindingTakeover,
            onConfirmKeepingCopy = viewModel::confirmBindingTakeoverKeepingCopy,
            onDismiss = viewModel::dismissBindingTakeover
        )
    }

    // ISSUE-P3-442：搜索词自愈询问宿主——两种处置（写入 / 取消）都以「打开该条目」收尾，
    // 用户点开条目的意图不因是否回灌 URL 而被改变。
    val writeBackPrompt by viewModel.searchWriteBackPrompt.collectAsStateWithLifecycle()
    writeBackPrompt?.let { prompt ->
        VaultSearchWriteBackDialog(
            prompt = prompt,
            onConfirm = { rememberChoice ->
                viewModel.confirmSearchWriteBack(rememberChoice)
                onEntryClick(prompt.entryId)
            },
            onDismiss = { rememberChoice ->
                viewModel.dismissSearchWriteBack(rememberChoice)
                onEntryClick(prompt.entryId)
            }
        )
    }
}
