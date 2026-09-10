package com.keepasskey.app.ui.screens.detail

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.AutofillBlockState
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
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
    private val clipboardSecurityManager: com.keepasskey.app.security.ClipboardSecurityManager? = null,
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
    private val extendedSettingsSource: ExtendedSettingsSource? = null
) : ViewModel() {

    // P3-23：文案解析通道（优先 stringsProvider，其次经 appContext 转发，均缺省时回退空串实现）
    private val strings: StringsProvider = stringsProvider
        ?: appContext?.let { ctx -> StringsProvider { id, args -> ctx.getString(id, *args) } }
        ?: StringsProvider { _, _ -> "" }

    // ISSUE-P2-10 (ZT-15)：明文附件导出审计（复用进程内日志缓冲，仅记类型与脱敏目标标识）
    private val exportAuditRecorder: ExportAuditRecorder? = debugLog?.let { ExportAuditRecorder(it) }

    private val entryIdFlow = MutableStateFlow<String?>(savedStateHandle.get<String>("entryId"))

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
        displayDispatcher = displayDispatcher
    )

    private val attachmentExporter = EntryDetailAttachmentExporter(
        vaultRepository = vaultRepository,
        appContext = appContext,
        exportAuditRecorder = exportAuditRecorder,
        debugLog = debugLog
    )

    private val totpTicker = EntryDetailTotpTicker(vaultRepository)

    private val revisionController = EntryDetailRevisionController(
        vaultRepository = vaultRepository,
        secrets = secrets,
        strings = strings
    )

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
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = EntryDetailUiState(isLoading = true)
        )

    init {
        // 断点6 整改：每秒驱动 TOTP 倒计时；周期翻转（剩余秒数不降反升）时重算实时验证码
        viewModelScope.launch(Dispatchers.Default) {
            totpTicker.run(
                currentEntry = { uiState.value.entry },
                onRemaining = { totpRemainingSecondsFlow.value = it },
                onLiveCode = { liveTotpCodeFlow.value = it }
            )
        }
    }

    fun setEntryId(id: String?) {
        // M1 整改：切换条目时立即擦除上一条目按需解密出的全部明文（密码/修订密码/受保护字段），
        // 杜绝「查看 A 条目密码后切到 B，A 的明文仍驻留 ViewModel 状态」的跨条目残留
        if (id == entryIdFlow.value) return
        entryIdFlow.value = id
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
     * 克隆当前条目（TASK-16）：全字段保真复制 + 新 UUID + 清历史，落库后
     * 详情页就地切换至克隆体。失败如实上浮（H3 语义）。
     */
    fun duplicateEntry() {
        val entryId = entryIdFlow.value ?: return
        viewModelScope.launch {
            when (val result = vaultRepository.duplicateEntry(entryId)) {
                is com.keepasskey.core.result.KdbxResult.Success -> {
                    userMessageFlow.value = UiMessage(R.string.detail_duplicate_success)
                    setEntryId(result.data)
                }
                is com.keepasskey.core.result.KdbxResult.Failure ->
                    userMessageFlow.value = UiMessage(R.string.edit_save_failed, listOf(result.message))
            }
        }
    }

    /**
     * TASK-44：切换「为本应用禁用自动填充」——写入/移出自动填充黑名单。
     *
     * 仅当条目 URL 携带 `android://<包名>` 绑定（即凭据确有明确归属应用）时可用；
     * 未绑定应用的条目（如纯 Web 凭据）本入口不呈现。
     * 结果经 [userMessageFlow] 如实告知用户（屏蔽 / 恢复），不做乐观谎报。
     *
     * ISSUE-P3-15：判定改用三态 [AutofillBlockState]。绑定包名缺失或非法（不可识别）时，
     * **不执行任何写操作**（不调 add / remove），亦不产出「已屏蔽 / 已恢复」语义，
     * 只如实提示「无法识别应用标识」——填充侧 fail-closed 判定不受本改动影响。
     */
    fun toggleAutofillBlockForApp() {
        val packageName = uiState.value.autofillBoundPackage
        if (packageName == null) {
            showUnidentifiablePackageMessage()
            return
        }
        when (autofillBlocklistStore.resolveBlockState(packageName)) {
            AutofillBlockState.UnidentifiablePackage -> showUnidentifiablePackageMessage()
            AutofillBlockState.Blocked -> {
                autofillBlocklistStore.remove(packageName)
                userMessageFlow.value = UiMessage(R.string.detail_autofill_unblocked, listOf(packageName))
            }
            AutofillBlockState.NotBlocked -> {
                autofillBlocklistStore.add(packageName)
                userMessageFlow.value = UiMessage(R.string.detail_autofill_blocked, listOf(packageName))
            }
        }
    }

    /** ISSUE-P3-15：不可识别包名的如实提示（不含任何「已屏蔽 / 已恢复」语义） */
    private fun showUnidentifiablePackageMessage() {
        userMessageFlow.value = UiMessage(R.string.autofill_block_unidentifiable_package)
    }

    /**
     * ISSUE-P3-02（TASK-49）：删除当前条目绑定的库级自定义图标。
     *
     * 自定义图标是**库级共享资源**：删除会移除 KDBX Meta 图标池条目，并把全部引用该图标的
     * 条目回退为默认图标，故 Screen 侧必须先经确认弹窗（[EntryDetailScreen] 的删除确认）；
     * 只读会话、无绑定图标或缺少注入通道时为 no-op / 如实失败，绝不谎报成功。
     */
    fun deleteCustomIcon() {
        val iconId = uiState.value.entry?.customIconId ?: return
        if (uiState.value.isReadOnly) return
        val admin = customIconAdmin
        if (admin == null) {
            userMessageFlow.value = UiMessage(R.string.vault_icon_delete_failed)
            return
        }
        viewModelScope.launch {
            when (val result = admin.deleteCustomIcon(iconId)) {
                is com.keepasskey.core.result.KdbxResult.Success ->
                    userMessageFlow.value = UiMessage(R.string.vault_icon_delete_done)
                is com.keepasskey.core.result.KdbxResult.Failure ->
                    userMessageFlow.value = UiMessage(R.string.vault_op_failed, listOf(result.message))
            }
        }
    }

    /**
     * 复制受保护自定义字段（F2 整改）：按需解密后写入受保护剪贴板，
     * 不再依赖条目投影中的明文（投影层受保护字段恒为空）。
     */
    fun copyCustomField(fieldId: String, fieldKey: String) {
        val entryId = entryIdFlow.value ?: return
        viewModelScope.launch {
            // TASK-10 + ISSUE-P2-15：仓库读取走 CharArray 独占副本，并直通受保护剪贴板的
            // CharArray 通道（不经中间 String），副本用毕清零
            val chars = vaultRepository.getEntryProtectedFieldChars(entryId, fieldKey)
            if (chars != null) {
                try {
                    clipboardSecurityManager?.copySensitiveChars(fieldKey, chars)
                } finally {
                    chars.fill('0')
                }
                userMessageFlow.value = UiMessage(R.string.detail_field_copied, listOf(fieldKey))
            }
        }
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

    fun showMessage(message: UiMessage) {
        userMessageFlow.value = message
    }

    /**
     * 复制密码：按需解密后写入受保护剪贴板（M1 整改：不再从条目投影取明文）。
     */
    fun copyPassword(title: String) {
        val entryId = entryIdFlow.value ?: return
        viewModelScope.launch {
            // TASK-17：复制前解析 {REF:...} 引用（密码可能指向其他条目的字段）。
            // ISSUE-P2-15：先经 CharArray 借用通道读取；{REF:...} 引擎为 String 文本语义，
            // 此处的 String 物化属引用解析边界，副本已即时清零
            val raw = vaultRepository.getEntryPasswordChars(entryId).toDisplayString().orEmpty()
            val password = vaultRepository.resolveFieldReferences(entryId, raw) ?: raw
            clipboardSecurityManager?.copySensitiveText(title, password)
            userMessageFlow.value = uiState.value.passwordCopyMessage
        }
    }

    fun copyUsername(title: String, username: String) {
        val entryId = entryIdFlow.value ?: return
        viewModelScope.launch {
            // TASK-17：用户名可能为 {REF:U@...} 引用，复制前解析
            val resolved = vaultRepository.resolveFieldReferences(entryId, username) ?: username
            clipboardSecurityManager?.copyPlainText(title, resolved)
            userMessageFlow.value = UiMessage(R.string.detail_username_copied_short)
        }
    }

    fun clearUserMessage() {
        userMessageFlow.value = null
    }
}
