package com.keepasskey.app.ui.screens.detail

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.CustomIconAdmin
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.screens.settings.ExportArtifactKind
import com.keepasskey.app.ui.screens.settings.ExportAuditRecorder
import com.keepasskey.app.ui.screens.settings.ExportConfirmationPolicy
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import com.keepasskey.app.ui.model.EntryDisplayDispatcher
import com.keepasskey.app.ui.screens.vault.ExtendedSettingsSource
import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiEntryRevision
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * 凭据详情状态容器 ViewModel。
 * M1 整改：密码明文不随条目投影下发，仅在用户显式查看 / 复制 / 对比时经仓库按需单条解密。
 *
 * ISSUE-P3-31 批次 C：状态装配搬至 [EntryDetailStateAssembler]、明文驻留与清零点搬至
 * [EntryDetailSecrets]、TOTP 节拍搬至 [EntryDetailTotpTicker]、附件写出搬至
 * [EntryDetailAttachmentExporter]；**公开 API 与行为逐条不变**。
 */
@HiltViewModel
class EntryDetailViewModel @Inject constructor(
    // 允许为 null 仅用于单测注入；生产 DI 注入 @ApplicationContext
    @ApplicationContext private val appContext: Context?,
    savedStateHandle: SavedStateHandle,
    private val vaultRepository: VaultRepository,
    private val settingsRepository: SettingsRepository,
    // ISSUE-P1-25：注入复制通道接口（生产绑定 ClipboardSecurityManager；单测注入记录桩）
    private val clipboardSecurityManager: com.keepasskey.app.security.ClipboardSecurityChannel? = null,
    // TASK-44：自动填充黑名单仓库（详情页「为本应用禁用自动填充」入口的写入方）
    private val autofillBlocklistStore: com.keepasskey.app.data.repository.AutofillBlocklistStore,
    // TASK-21：非 Compose 层文案资源解析通道（生产 DI 注入真实现；单测注入假实现）
    private val stringsProvider: StringsProvider? = null,
    // ISSUE-P2-10 (ZT-15)：导出审计记录通道（生产 DI 注入；单测可缺省）
    private val debugLog: DebugLogBuffer? = null,
    // ISSUE-P3-02：库级自定义图标删除通道（生产 DI 经 CustomIconModule 注入；单测注入假实现）
    private val customIconAdmin: CustomIconAdmin? = null,
    // ISSUE-P3-02：展示装配调度器（生产 Dispatchers.Default；单测注入测试调度器保证断言确定性）
    @EntryDisplayDispatcher private val displayDispatcher: CoroutineDispatcher = Dispatchers.Default,
    // ISSUE-P3-17：进阶显示偏好通道（遮掩默认值 / 详情页所属分组）。
    // 该通道只有同步快照读取（无 Flow），故以 StateFlow 承载快照，页面进入时刷新；
    // null 仅用于纯 JVM 单测（生产 DI 经 ExtendedSettingsSourceModule 恒注入）
    private val extendedSettingsSource: ExtendedSettingsSource? = null,
    // ISSUE-P2-65：会话锁定观察者注册点（null 仅用于纯 JVM 单测）。
    // 本 ViewModel 持有按需解密明文（密码 / 修订密码 / 受保护字段）与实时 TOTP 码，
    // 锁定 / 关闭时必须立即擦除，不得仅依赖导航离开（`onScreenDisposed`）。
    private val databaseSession: com.keepasskey.database.session.DatabaseSession? = null
) : ViewModel() {

    // P3-23：文案解析通道（优先 stringsProvider，其次经 appContext 转发，均缺省时回退空串实现）
    private val strings: StringsProvider = stringsProvider
        ?: appContext?.let { ctx -> StringsProvider { id, args -> ctx.getString(id, *args) } }
        ?: StringsProvider { _, _ -> "" }

    // ISSUE-P2-10 (ZT-15)：明文附件导出审计（复用进程内日志缓冲，仅记类型与脱敏目标标识）
    private val exportAuditRecorder: ExportAuditRecorder? = debugLog?.let { ExportAuditRecorder(it) }

    private val entryIdFlow = MutableStateFlow<String?>(savedStateHandle.get<String>("entryId"))

    // ISSUE-P3-48：单条删除成功的一次性导航事件（Screen 消费即回退列表）
    private val entryDeletedFlow = MutableStateFlow(false)

    /** 条目已删除（移入回收站 / 站内彻底删除）的一次性信号 */
    val entryDeleted: StateFlow<Boolean> = entryDeletedFlow

    /** 按需解密明文与用户显式遮掩意图的唯一持有者（含唯一清零入口）。 */
    private val secrets = EntryDetailSecrets()

    // ISSUE-P3-17：进阶显示偏好快照（构造期读取一次；页面进入组合时经 onScreenEntered 刷新）
    private val extendedSettingsFlow = MutableStateFlow(
        extendedSettingsSource?.load() ?: ExtendedSettings()
    )
    private val isFavoriteFlow = MutableStateFlow(false)
    private val userMessageFlow = MutableStateFlow<UiMessage?>(null)

    // 断点6 整改：详情页 TOTP 每秒倒计时（原为投影一次性值，进度环静止）
    private val totpRemainingSecondsFlow = MutableStateFlow(0)
    // 断点6 整改：周期翻转时经仓库按需重算的实时验证码（null=沿用投影值）
    private val liveTotpCodeFlow = MutableStateFlow<String?>(null)

    /** 按需揭示 / 遮掩的行为控制器（持有由明文派生的强度读数）。 */
    private val revealController = EntryDetailRevealController(
        vaultRepository = vaultRepository,
        scope = viewModelScope,
        secrets = secrets,
        settingsSnapshot = { extendedSettingsFlow.value },
        currentEntryId = { entryIdFlow.value },
        currentEntry = { uiState.value.entry }
    )

    private val stateAssembler = EntryDetailStateAssembler(
        vaultRepository = vaultRepository,
        settingsRepository = settingsRepository,
        autofillBlocklistStore = autofillBlocklistStore,
        displayDispatcher = displayDispatcher,
        // ISSUE-P3-176：共享投影流（shareIn）落在 viewModelScope，启停与 uiState 同生命周期
        scope = viewModelScope
    )

    private val attachmentExporter = EntryDetailAttachmentExporter(
        vaultRepository = vaultRepository,
        appContext = appContext,
        exportAuditRecorder = exportAuditRecorder,
        debugLog = debugLog
    )

    private val totpTicker = EntryDetailTotpTicker(vaultRepository)

    /** ISSUE-P3-188：复制 / 取码动作簇下沉至协作者，本类保留事件入口与状态持有。 */
    private val copyCoordinator = EntryDetailCopyCoordinator(
        vaultRepository = vaultRepository,
        clipboardSecurityManager = clipboardSecurityManager,
        scope = viewModelScope,
        currentEntryId = { entryIdFlow.value },
        isReadOnly = { uiState.value.isReadOnly },
        entryTitle = { uiState.value.entry?.title.orEmpty() },
        passwordCopyMessage = { uiState.value.passwordCopyMessage },
        liveTotpCode = { uiState.value.liveTotpCode },
        projectedTotpCode = { uiState.value.entry?.totpCode },
        showMessage = { userMessageFlow.value = it }
    )

    /** ISSUE-P3-188：条目动作簇（克隆 / 删除 / 移动 / 图标 / 自动填充屏蔽）下沉至协作者。 */
    private val entryActions = EntryDetailEntryActions(
        vaultRepository = vaultRepository,
        autofillBlocklistStore = autofillBlocklistStore,
        customIconAdmin = customIconAdmin,
        scope = viewModelScope,
        currentEntryId = { entryIdFlow.value },
        isReadOnly = { uiState.value.isReadOnly },
        boundPackage = { uiState.value.autofillBoundPackage },
        boundCustomIconId = { uiState.value.entry?.customIconId },
        onEntrySwitched = { setEntryId(it) },
        onEntryDeleted = { entryDeletedFlow.value = true },
        showMessage = { userMessageFlow.value = it }
    )

    private val revisionController = EntryDetailRevisionController(
        vaultRepository = vaultRepository,
        secrets = secrets,
        strings = strings
    )

    /**
     * `ISSUE-P3-175`：节拍收集任务的句柄。
     *
     * 启停由 [uiState] 的**订阅期**决定（见其 `onStart` / `onCompletion`）：
     * `stateIn(WhileSubscribed(5000))` 在最后一个订阅者离开（宽限 5 s）后取消上游收集 ⇒
     * `onCompletion` 停表；重新订阅时 `onStart` 再启表。原先在 `init` 里常驻启动，
     * 页面退到后台栈（Activity stopped、ViewModel 未销毁）时仍每秒唤醒并做一次仓库调用。
     */
    private var totpTickJob: Job? = null

    /** 当前条目快照（供节拍读取；读 `uiState.value` 不会额外启动其上游）。 */
    private fun currentEntryOrNull() = uiState.value.entry

    val uiState: StateFlow<EntryDetailUiState> = stateAssembler
        .assemble(
            EntryDetailStateAssembler.Inputs(
                entryId = entryIdFlow,
                secrets = secrets,
                isFavorite = isFavoriteFlow,
                userMessage = userMessageFlow,
                totpRemainingSeconds = totpRemainingSecondsFlow,
                liveTotpCode = liveTotpCodeFlow,
                passwordStrengthBits = revealController.passwordStrengthBits,
                extendedSettings = extendedSettingsFlow
            )
        )
        // 断点6 整改：每秒驱动 TOTP 倒计时；周期翻转（剩余秒数不降反升）时重算实时验证码。
        // ISSUE-P3-175：改为随本链的订阅期启停（原为 `init` 常驻）。
        .onStart {
            totpTickJob?.cancel()
            totpTickJob = viewModelScope.launch(Dispatchers.Default) {
                totpTicker.run(
                    currentEntry = { currentEntryOrNull() },
                    onRemaining = { totpRemainingSecondsFlow.value = it },
                    onLiveCode = { liveTotpCodeFlow.value = it }
                )
            }
        }
        .onCompletion { totpTickJob?.cancel() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = EntryDetailUiState(isLoading = true)
        )

    /**
     * ISSUE-P2-65：会话锁定 / 关闭回调——擦除全部明文驻留点
     * （[SessionLockObserver] 契约：非阻塞、幂等、自容错）。
     * 声明必须在 [init] 之前：Kotlin 按类体顺序执行初始化器。
     */
    private val sessionLockObserver = com.keepasskey.core.session.SessionLockObserver {
        liveTotpCodeFlow.value = null
        clearAllRevealedSecrets()
    }

    init {
        // ISSUE-P2-65：注册会话锁定观察者——锁库 / 关库（含切库、后台超时、熄屏熔断）时
        // 立即擦除按需解密明文与实时 TOTP 码，不依赖导航离开时机。
        // （节拍启停已移交 `uiState` 的 onStart / onCompletion，见该属性 KDoc——ISSUE-P3-175）
        databaseSession?.addLockObserver(sessionLockObserver)
    }

    override fun onCleared() {
        databaseSession?.removeLockObserver(sessionLockObserver)
        super.onCleared()
    }

    fun setEntryId(id: String?) {
        // M1 整改：切换条目时立即擦除上一条目按需解密出的全部明文（密码/修订密码/受保护字段），
        // 杜绝「查看 A 条目密码后切到 B，A 的明文仍驻留 ViewModel 状态」的跨条目残留
        if (id == entryIdFlow.value) return
        entryIdFlow.value = id
        entryDeletedFlow.value = false
        // ISSUE-P3-49：切换条目即清空上一 TOTP 条目的实时码，避免 HOTP 条目（不由节拍驱动）
        // 误显上一条目的验证码
        liveTotpCodeFlow.value = null
        clearAllRevealedSecrets()
        // ISSUE-P3-17：偏好声明「默认不遮掩」时，新条目同样按默认态补齐明文
        revealPasswordIfVisibleByDefault()
    }

    /**
     * Screen 离开组合（返回导航 / 目的地销毁）时调用：擦除全部按需解密明文。
     * 对比 KeePassDX「解密即用即弃」语义——明文仅允许在显式查看期间驻留。
     */
    fun onScreenDisposed() {
        clearAllRevealedSecrets()
    }

    private fun clearAllRevealedSecrets() {
        revealController.clearAll()
    }

    /**
     * ISSUE-P3-17：页面每次进入组合时刷新进阶显示偏好快照
     * （偏好通道只有同步 `load()`，无 Flow）。
     *
     * 刷新**不会**覆盖用户已做出的显式展开/收起：可见性由
     * [FieldMaskPolicy.initialMaskState] 以 override 优先合成。
     */
    fun onScreenEntered() {
        extendedSettingsSource?.let { source -> extendedSettingsFlow.value = source.load() }
        // 覆盖「离开详情页已清零明文 → 再次进入」的路径（此时条目 id 未变化，setEntryId 提前返回）
        revealPasswordIfVisibleByDefault()
    }

    private fun revealPasswordIfVisibleByDefault() {
        revealController.revealPasswordIfVisibleByDefault()
    }

    /**
     * 切换密码可见性（委托 [EntryDetailRevealController]）：
     * 展开时按需解密并估算真实熵，收起时立即撤回明文与熵读数。
     */
    fun togglePasswordVisibility() {
        revealController.togglePasswordVisibility()
    }

    /** ISSUE-P3-17：切换 TOTP 验证码可见性（委托 [EntryDetailRevealController]）。 */
    fun toggleTotpVisibility() {
        revealController.toggleTotpVisibility()
    }

    /**
     * 切换受保护自定义字段可见性（F2 整改，委托 [EntryDetailRevealController]）。
     * 展开时经仓库按需单条解密该字段明文，收起时立即从驻留状态中移除。
     */
    fun toggleCustomFieldVisibility(fieldId: String) {
        revealController.toggleCustomFieldVisibility(fieldId)
    }

    fun toggleFavorite() {
        isFavoriteFlow.update { !it }
    }

    /**
     * 克隆当前条目（TASK-16，委托 [EntryDetailEntryActions]）：全字段保真复制 + 新 UUID + 清历史，
     * 落库后详情页就地切换至克隆体。
     */
    fun duplicateEntry() {
        entryActions.duplicateEntry()
    }

    /**
     * TASK-44：切换「为本应用禁用自动填充」（委托 [EntryDetailEntryActions]）——
     * 写入/移出自动填充黑名单；包名不可识别时不执行任何写操作并如实提示（ISSUE-P3-15）。
     */
    fun toggleAutofillBlockForApp() {
        entryActions.toggleAutofillBlockForApp()
    }

    /**
     * ISSUE-P3-02（TASK-49，委托 [EntryDetailEntryActions]）：删除当前条目绑定的库级自定义图标；
     * 只读会话 / 无绑定图标 / 缺少注入通道时为 no-op 或如实失败。
     */
    fun deleteCustomIcon() {
        entryActions.deleteCustomIcon()
    }

    /**
     * 复制受保护自定义字段（F2 整改，委托 [EntryDetailCopyCoordinator]）：
     * 按需解密后写入受保护剪贴板，不再依赖条目投影中的明文（投影层受保护字段恒为空）。
     */
    fun copyCustomField(fieldId: String, fieldKey: String) {
        copyCoordinator.copyCustomField(fieldId, fieldKey)
    }

    /**
     * 回滚到历史修订（断点8 整改，委托 [EntryDetailRevisionController]）。
     * 取整修订快照（含解密后的受保护字段与 TOTP 配置）全字段回滚；
     * 快照缺失时如实暴露失败，绝不谎报「已回滚」（TASK-31）。
     */
    fun rollbackToRevision(revision: UiEntryRevision) {
        val current = uiState.value.entry ?: return
        viewModelScope.launch {
            userMessageFlow.value = revisionController.rollback(current, revision)
        }
    }

    /** 打开历史修订对比弹窗前按需解密：当前密码 + 目标修订密码。 */
    fun prepareRevisionDiff(revisionId: String) {
        val entryId = entryIdFlow.value ?: return
        viewModelScope.launch {
            revisionController.prepareDiff(entryId, revisionId)
        }
    }

    fun clearRevisionDiff() {
        revisionController.clearDiff()
    }

    /**
     * 断点3 整改：真实附件导出——按需解析附件字节并写入 SAF 目标 Uri。
     * [targetUri] 由 Screen 层 CreateDocument 选择器产生；此前该方法仅发 Toast。
     *
     * ISSUE-P2-10 (ZT-15)：附件是解密后的明文，属高风险出域。调用方必须先经确认弹窗
     * 取得用户显式授权并传 [confirmed] = true；缺省或缺失确认时 fail-closed——不解析、
     * 不写出任何字节，仅提示用户（判定走可单测的 [ExportConfirmationPolicy]）。
     */
    fun exportAttachment(attachment: UiAttachment, targetUri: Uri, confirmed: Boolean = false) {
        val entryId = entryIdFlow.value ?: return
        val allowed = ExportConfirmationPolicy.allows(
            risk = ExportConfirmationPolicy.riskOf(ExportArtifactKind.ATTACHMENT),
            confirmed = confirmed
        )
        if (!allowed) {
            // fail-closed：确认缺失即不导出
            userMessageFlow.value = UiMessage(R.string.detail_attachment_export_warn_title)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            userMessageFlow.value = attachmentExporter.export(entryId, attachment, targetUri)
        }
    }

    /**
     * ISSUE-P3-48：删除当前条目（单条入口，委托 [EntryDetailEntryActions]）。
     * 成功置一次性 [entryDeleted] 供 Screen 回退导航，失败如实上浮。
     */
    fun deleteEntry() {
        entryActions.deleteEntry()
    }

    /**
     * ISSUE-P3-51：把当前条目移动到目标分组（null = 根目录，委托 [EntryDetailEntryActions]）。
     */
    fun moveEntryToGroup(targetGroupId: String?) {
        entryActions.moveEntryToGroup(targetGroupId)
    }

    /**
     * ISSUE-P3-49：HOTP 取码（委托 [EntryDetailCopyCoordinator]）——推进计数器（**先落库成功**）
     * 并把本次所出之码写入受保护剪贴板；失败如实上浮，绝不产出「未推进」的码。
     */
    fun advanceHotp() {
        copyCoordinator.advanceHotp()
    }

    fun showMessage(message: UiMessage) {
        userMessageFlow.value = message
    }

    /**
     * 复制密码（委托 [EntryDetailCopyCoordinator]）：按需解密后写入受保护剪贴板
     * （M1 整改：不再从条目投影取明文）。
     */
    fun copyPassword(title: String) {
        copyCoordinator.copyPassword(title)
    }

    fun copyUsername(title: String, username: String) {
        copyCoordinator.copyUsername(title, username)
    }

    /**
     * ISSUE-P3-184：TOTP 取码（委托 [EntryDetailCopyCoordinator]）——把**当前有效验证码**
     * 写入受保护剪贴板；全部取不到时不谎报成功。只读会话不设门槛（纯读）。
     */
    fun copyTotpCode() {
        copyCoordinator.copyTotpCode()
    }

    fun clearUserMessage() {
        userMessageFlow.value = null
    }
}
