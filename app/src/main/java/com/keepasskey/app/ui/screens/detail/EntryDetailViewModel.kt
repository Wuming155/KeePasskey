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
import com.keepasskey.app.passkey.DomainMatcher
import com.keepasskey.app.ui.model.EntryDecorations
import com.keepasskey.app.ui.model.EntryDisplayDispatcher
import com.keepasskey.app.ui.model.EntryDisplayPresenter
import com.keepasskey.app.ui.screens.vault.ExtendedSettingsSource
import com.keepasskey.app.ui.screens.vault.GroupPathPresenter
import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiEntryRevision
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.util.tickerFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 凭据详情状态容器 ViewModel。
 * M1 整改：密码明文不随条目投影下发，仅在用户显式查看 / 复制 / 对比时经仓库按需单条解密。
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
    // ISSUE-P3-17：密码当前是否明文可见不再直接存布尔“状态”，而是存**用户的显式遮掩意图**：
    // null = 本次会话尚未操作（遵从 maskPasswordsDefault 默认值），
    // true = 用户显式收起，false = 用户显式展开——偏好流再次发射不得覆盖非 null 的用户意图
    private val passwordMaskOverrideFlow = MutableStateFlow<Boolean?>(null)
    // ISSUE-P3-17：TOTP 验证码的同构显式遮掩意图（默认值来自 maskTotpDefault）
    private val totpMaskOverrideFlow = MutableStateFlow<Boolean?>(null)
    // ISSUE-P3-17：进阶显示偏好快照（构造期读取一次；页面进入组合时经 onScreenEntered 刷新）
    private val extendedSettingsFlow = MutableStateFlow(
        extendedSettingsSource?.load() ?: ExtendedSettings()
    )
    // 按需解密出的当前密码明文（仅在查看期间驻留，隐藏即清空）
    private val revealedPasswordFlow = MutableStateFlow<String?>(null)
    // 按需解密出的历史修订密码（对比弹窗打开期间驻留），键为修订 id
    private val revealedRevisionPasswordsFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    private val isFavoriteFlow = MutableStateFlow(false)
    private val protectedVisibilityFlow = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    // F2 整改：受保护自定义字段按需解密出的明文（仅查看期间驻留，收起即清空），键为字段 id
    private val revealedProtectedFieldsFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    private val userMessageFlow = MutableStateFlow<UiMessage?>(null)

    // 断点6 整改：详情页 TOTP 每秒倒计时（原为投影一次性值，进度环静止）
    private val totpRemainingSecondsFlow = MutableStateFlow(0)
    // 断点6 整改：周期翻转时经仓库按需重算的实时验证码（null=沿用投影值）
    private val liveTotpCodeFlow = MutableStateFlow<String?>(null)
    // TASK-32 整改：按需解密估算的真实密码熵（bit）。null=无密码或尚未计算完成；
    // 明文仅在计算期间以 CharArray 副本瞬时存在，用毕立即清零，绝不驻留
    private val passwordStrengthBitsFlow = MutableStateFlow<Int?>(null)

    /** combine 中间聚合体（避开 5 流以上的元组嵌套） */
    private data class DetailCore(
        val entry: UiVaultEntry?,
        val passwordMaskOverride: Boolean?,
        val revealedPassword: String?,
        val revisionPasswords: Map<String, String>,
        val isFavorite: Boolean
    )

    /**
     * ISSUE-P3-17：显示侧派生量聚合体。
     * [isPasswordVisible] / [isTotpVisible] 是「偏好默认值 + 用户显式意图」的合成结果，
     * [groupPath] 仅在 `showGroupInEntry` 开启时非空。
     */
    private data class DetailDisplayPrefs(
        val isPasswordVisible: Boolean,
        val isTotpVisible: Boolean,
        val groupPath: String?
    )

    /** combine 中间聚合体：可见性 / 已揭示字段明文 / 用户消息 / TOTP 实时态 / 密码熵 */
    private data class DetailExtras(
        val protectedVisibility: Map<String, Boolean>,
        val revealedProtectedFields: Map<String, String>,
        val userMessage: UiMessage?,
        val totpRemainingSeconds: Int? = null,
        val liveTotpCode: String? = null,
        val strengthBits: Int? = null
    )

    // ISSUE-P3-02（TASK-49）：展示装饰装配器 —— 自定义图标投影（PNG 解码 + 有界缓存复用）
    // 与 Notes/URL 字段引用展开（仅公开字段，受保护字段恒为掩码）。装配跑 Default 调度器。
    private val entryDecorations = EntryDisplayPresenter(
        loadIconBytes = { vaultRepository.getCustomIconBytes() },
        loadEntries = { vaultRepository.getKdbxEntries() }
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val decorationsFlow: Flow<EntryDecorations> = entryIdFlow
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(EntryDecorations.EMPTY)
            } else {
                vaultRepository.getEntry(id).map { entry ->
                    if (entry == null) EntryDecorations.EMPTY else entryDecorations.decorate(entry)
                }
            }
        }
        .flowOn(displayDispatcher)

    /**
     * ISSUE-P3-17：条目所属分组的完整路径（仅在 `showGroupInEntry` 开启时需要）。
     * 分组投影与条目各自独立变化，故与 entryIdFlow 组合后按 id 解析，避免依赖发射时序。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val groupPathFlow: Flow<String?> = combine(
        entryIdFlow.flatMapLatest { id ->
            if (id != null) vaultRepository.getEntry(id) else flowOf(null)
        },
        vaultRepository.getGroups()
    ) { entry, groups ->
        GroupPathPresenter.fullPathOf(groups, entry?.groupId)
    }

    /**
     * ISSUE-P3-17：显示侧派生量——遮掩初始态决策（[FieldMaskPolicy]）+ 所属分组路径。
     * 偏好快照每次刷新都会重算，但用户显式意图（override 非 null）恒优先。
     */
    private val displayPrefsFlow: Flow<DetailDisplayPrefs> = combine(
        extendedSettingsFlow,
        passwordMaskOverrideFlow,
        totpMaskOverrideFlow,
        groupPathFlow
    ) { settings, passwordOverride, totpOverride, groupPath ->
        DetailDisplayPrefs(
            isPasswordVisible = !FieldMaskPolicy.initialMaskState(
                settings.maskPasswordsDefault, passwordOverride
            ),
            isTotpVisible = !FieldMaskPolicy.initialMaskState(
                settings.maskTotpDefault, totpOverride
            ),
            groupPath = groupPath.takeIf { settings.showGroupInEntry }
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<EntryDetailUiState> = combine(
        entryIdFlow.flatMapLatest { id ->
            if (id != null) vaultRepository.getEntry(id) else flowOf(null)
        },
        passwordMaskOverrideFlow,
        revealedPasswordFlow,
        revealedRevisionPasswordsFlow,
        isFavoriteFlow
    ) { entry, passwordOverride, revealed, revPasswords, isFav ->
        DetailCore(entry, passwordOverride, revealed, revPasswords, isFav)
    }
        .combine(
            combine(
                protectedVisibilityFlow,
                revealedProtectedFieldsFlow,
                userMessageFlow
            ) { visMap, revealedFields, message ->
                DetailExtras(visMap, revealedFields, message)
            }.combine(combine(totpRemainingSecondsFlow, liveTotpCodeFlow) { r, c -> r to c }) { extras, totp ->
                extras.copy(totpRemainingSeconds = totp.first, liveTotpCode = totp.second)
            }.combine(passwordStrengthBitsFlow) { extras, strength ->
                extras.copy(strengthBits = strength)
            }
        ) { core, extras ->
            core to extras
        }
        .combine(settingsRepository.getSettings()) { (core, extras), settings ->
            EntryDetailUiState(
                entry = core.entry,
                revealedPassword = core.revealedPassword,
                revealedRevisionPasswords = core.revisionPasswords,
                isFavorite = core.isFavorite,
                protectedFieldsVisibility = extras.protectedVisibility,
                revealedProtectedFields = extras.revealedProtectedFields,
                userMessage = extras.userMessage,
                totpRemainingSeconds = extras.totpRemainingSeconds,
                liveTotpCode = extras.liveTotpCode,
                passwordStrengthBits = extras.strengthBits,
                isReadOnly = vaultRepository.isSessionReadOnly(),
                passwordCopyMessage = buildPasswordCopyMessage(settings.clipboardTimeoutSeconds)
            )
        }
        // ISSUE-P3-17：叠加遮掩初始态决策（偏好的「默认值」语义）与所属分组路径
        .combine(displayPrefsFlow) { state, prefs ->
            state.copy(
                isPasswordVisible = prefs.isPasswordVisible,
                isTotpVisible = prefs.isTotpVisible,
                groupPath = prefs.groupPath
            )
        }
        // TASK-44：黑名单状态叠加——条目绑定的应用包名 + 该包名当前是否被屏蔽
        .combine(autofillBlocklistStore.blockedPackages) { state, blockedPackages ->
            val boundPackage = state.entry?.url
                ?.let { DomainMatcher.extractAndroidBoundPackage(it) }
            state.copy(
                autofillBoundPackage = boundPackage,
                isAutofillBlockedForApp = boundPackage != null && blockedPackages.contains(boundPackage)
            )
        }
        // ISSUE-P3-02：叠加图标投影与 Notes/URL 引用展开文案（状态层装配，UI 只做纯绘制）
        .combine(decorationsFlow) { state, decorations ->
            state.copy(decorations = decorations)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = EntryDetailUiState(isLoading = true)
        )

    init {
        // 断点6 整改：每秒驱动 TOTP 倒计时；周期翻转（剩余秒数不降反升）时重算实时验证码
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            var previous = -1
            tickerFlow(TOTP_TICK_MS).collect {
                val snapshot = uiState.value.entry?.takeIf { it.totpCode != null } ?: return@collect
                val fresh = vaultRepository.calculateEntryTotp(snapshot.id)
                val period = fresh?.periodSeconds ?: snapshot.totpPeriod
                val remaining = if (fresh != null) {
                    val nowSec = (System.currentTimeMillis() / 1000L).toInt()
                    val r = period - (nowSec % period)
                    if (r == 0) period else r
                } else {
                    (snapshot.totpRemainingSeconds - 1).coerceAtLeast(0)
                }
                totpRemainingSecondsFlow.value = remaining
                if (previous in 1..remaining) {
                    // 剩余秒数回跳到满值 → 新周期开始，刷新验证码
                    liveTotpCodeFlow.value = fresh?.code
                } else if (previous == -1 && fresh != null) {
                    liveTotpCodeFlow.value = fresh.code
                }
                previous = remaining
            }
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
        revealedPasswordFlow.value = null
        revealedRevisionPasswordsFlow.value = emptyMap()
        revealedProtectedFieldsFlow.value = emptyMap()
        // ISSUE-P3-17：清空「用户显式意图」而非直接置为遮掩——切换条目后回到偏好声明的默认态
        passwordMaskOverrideFlow.value = null
        totpMaskOverrideFlow.value = null
        protectedVisibilityFlow.value = emptyMap()
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

    /**
     * ISSUE-P3-17：`maskPasswordsDefault = false` 是用户以偏好形式给出的**明文查看指令**，
     * 此时进入详情页即按需解密当前条目，使「默认不遮掩」在 UI 上真实可见（而非空字段）。
     *
     * 安全边界不变：仍是单条、仍在屏幕生命周期内驻留（离开即清零），
     * 且偏好为「默认遮掩」（生产默认值 true）时**不做任何解密**——绝不无授权预解密。
     * TOTP 无需此路径：验证码由投影/countdown 流按周期下发，不涉及额外解密。
     */
    private fun revealPasswordIfVisibleByDefault() {
        if (revealedPasswordFlow.value != null) return
        val masked = currentMaskState(
            defaultMasked = extendedSettingsFlow.value.maskPasswordsDefault,
            override = passwordMaskOverrideFlow.value
        )
        if (!masked) decryptPasswordForDisplay()
    }

    /**
     * 切换密码可见性。展开时按需解密当前条目密码，收起时立即置空驻留明文。
     *
     * ISSUE-P3-17：切换写入的是**用户显式遮掩意图**（override），
     * 因此后续任何偏好快照刷新都不会把手动展开的密码重新盖上。
     */
    fun togglePasswordVisibility() {
        val currentlyMasked = currentMaskState(
            defaultMasked = extendedSettingsFlow.value.maskPasswordsDefault,
            override = passwordMaskOverrideFlow.value
        )
        passwordMaskOverrideFlow.value = !currentlyMasked
        if (currentlyMasked) {
            decryptPasswordForDisplay()
        } else {
            revealedPasswordFlow.value = null
        }
    }

    /** 展开分支：按需解密（ISSUE-P2-15 CharArray 借用通道，String 物化收敛在展示边界） */
    private fun decryptPasswordForDisplay() {
        val entryId = entryIdFlow.value ?: return
        viewModelScope.launch {
            // ISSUE-P2-15：仓库读取走 CharArray 借用通道；revealedPassword 为 Compose 展示态，
            // String 物化属 UI 显示边界（不可擦），故仅在最小作用域内转换并立即清零副本
            revealedPasswordFlow.value = vaultRepository.getEntryPasswordChars(entryId).toDisplayString()
        }
    }

    /**
     * ISSUE-P3-17：切换 TOTP 验证码可见性（默认态来自 `maskTotpDefault`）。
     * 与密码同构：只翻转用户显式意图，验证码本身仍由倒计时流驱动，不因遮掩而停算。
     */
    fun toggleTotpVisibility() {
        val currentlyMasked = currentMaskState(
            defaultMasked = extendedSettingsFlow.value.maskTotpDefault,
            override = totpMaskOverrideFlow.value
        )
        totpMaskOverrideFlow.value = !currentlyMasked
    }

    private fun currentMaskState(defaultMasked: Boolean, override: Boolean?): Boolean =
        FieldMaskPolicy.initialMaskState(defaultMasked, override)

    /**
     * ISSUE-P2-15：把仓库返回的 CharArray 独占副本转成 UI 展示 String。
     * String 一旦物化不可擦除（Compose Text 显示边界），但借用的 CharArray 副本用毕立即清零。
     */
    private fun CharArray?.toDisplayString(): String? {
        if (this == null) return null
        return try {
            String(this)
        } finally {
            fill('0')
        }
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
     * 切换受保护自定义字段可见性（F2 整改）。
     * 展开时经仓库按需单条解密该字段明文，收起时立即从驻留状态中移除。
     */
    fun toggleCustomFieldVisibility(fieldId: String) {
        val becomingVisible = protectedVisibilityFlow.value[fieldId] != true
        protectedVisibilityFlow.update { current -> current + (fieldId to becomingVisible) }
        if (!becomingVisible) {
            revealedProtectedFieldsFlow.update { it - fieldId }
            return
        }
        val entry = uiState.value.entry ?: return
        val field = entry.customFields.firstOrNull { it.id == fieldId } ?: return
        viewModelScope.launch {
            // TASK-10：仓库读取改走 CharArray 独占副本；展示用 String 为 UI 显示边界
            // （与 getEntryPassword 同一边界语义），副本即时清零
            val chars = vaultRepository.getEntryProtectedFieldChars(entry.id, field.key)
            val value = if (chars != null) {
                val revealed = String(chars)
                chars.fill('0')
                revealed
            } else ""
            revealedProtectedFieldsFlow.update { it + (fieldId to value) }
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
     * 回滚到历史修订（断点8 整改）。
     * 原实现仅恢复 username/notes/password 三字段，title/url/自定义字段/TOTP 全部丢失；
     * 现改为取整修订快照（含解密后的受保护字段与 TOTP 配置）全字段回滚，
     * 保存时由 HistoryManager 把回滚前的当前版本归档为最新历史。
     */
    fun rollbackToRevision(revision: UiEntryRevision) {
        val current = uiState.value.entry ?: return
        val entryId = current.id
        viewModelScope.launch {
            val snapshot = vaultRepository.getEntryRevisionSnapshot(entryId, revision.id)
            if (snapshot == null) {
                // TASK-31 整改：历史快照缺失（已被修剪/清理）时不得谎报「已回滚」，如实暴露失败
                userMessageFlow.value = UiMessage(R.string.detail_history_rollback_failed)
                return@launch
            }
            // M2 整改（加解密审查 2026-09）：回滚路径全程 CharArray，不再经 String 中转；
            // 副本按 saveEntry 擦除契约由仓库用毕清零
            val revisionPasswordChars = vaultRepository.getEntryRevisionPasswordChars(entryId, revision.id)
            val updated = snapshot.entry.copy(
                groupId = current.groupId,
                updatedAt = strings.get(R.string.detail_rollback_updated_at)
            )
            // ISSUE-P2-15：TOTP 原文已由仓库以 CharArray 独占副本返回，直接转交 saveEntry
            // （由其擦除契约用毕清零），不再经 String 中转
            val result = vaultRepository.saveEntry(
                updated,
                passwordChars = revisionPasswordChars,
                totpSecretChars = snapshot.totpSecretChars
            )
            userMessageFlow.value = if (result is com.keepasskey.core.result.KdbxResult.Success) {
                UiMessage(R.string.detail_history_rolled_back)
            } else {
                UiMessage(R.string.edit_save_failed, listOf((result as com.keepasskey.core.result.KdbxResult.Failure).message))
            }
        }
    }

    /**
     * 打开历史修订对比弹窗前按需解密：当前密码 + 目标修订密码。
     */
    fun prepareRevisionDiff(revisionId: String) {
        val entryId = entryIdFlow.value ?: return
        viewModelScope.launch {
            // ISSUE-P2-15：对比路径同样改走 CharArray 借用通道，String 物化收敛在展示边界
            val currentPw = revealedPasswordFlow.value
                ?: vaultRepository.getEntryPasswordChars(entryId).toDisplayString()
            val revisionPw = vaultRepository.getEntryRevisionPasswordChars(entryId, revisionId)
                .toDisplayString()
            revealedPasswordFlow.value = currentPw
            revealedRevisionPasswordsFlow.update { it + (revisionId to revisionPw.orEmpty()) }
        }
    }

    fun clearRevisionDiff() {
        // M1 整改：关闭对比弹窗时必须连修订密码映射一并清空
        // （原实现只清当前密码，revealedRevisionPasswords 跨弹窗累积驻留明文）
        revealedPasswordFlow.value = null
        revealedRevisionPasswordsFlow.value = emptyMap()
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
        val rawTarget = targetUri.toString()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val bytes = vaultRepository.getAttachmentData(entryId, attachment.fileName)
                if (bytes == null) {
                    exportAuditRecorder?.record(ExportArtifactKind.ATTACHMENT, rawTarget, success = false)
                    userMessageFlow.value = UiMessage(R.string.detail_attachment_export_failed)
                    return@launch
                }
                val resolver = appContext?.contentResolver
                if (resolver == null) {
                    exportAuditRecorder?.record(ExportArtifactKind.ATTACHMENT, rawTarget, success = false)
                    userMessageFlow.value = UiMessage(R.string.detail_attachment_export_failed)
                    return@launch
                }
                val stream = resolver.openOutputStream(targetUri)
                if (stream == null) {
                    exportAuditRecorder?.record(ExportArtifactKind.ATTACHMENT, rawTarget, success = false)
                    userMessageFlow.value = UiMessage(R.string.detail_attachment_export_failed)
                    return@launch
                }
                stream.use { os ->
                    os.write(bytes)
                    os.flush()
                }
                exportAuditRecorder?.record(ExportArtifactKind.ATTACHMENT, rawTarget, success = true)
                userMessageFlow.value = UiMessage(R.string.detail_attachment_export_toast, listOf(attachment.fileName))
            } catch (e: Exception) {
                // 只留痕异常类型，不落异常消息或附件名（防御性，避免敏感内容回流日志缓冲）
                exportAuditRecorder?.record(ExportArtifactKind.ATTACHMENT, rawTarget, success = false)
                debugLog?.warn(TAG, "附件导出失败: ${e.javaClass.simpleName}")
                userMessageFlow.value = UiMessage(R.string.detail_attachment_export_failed)
            }
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

    /**
     * 依据剪贴板自动清空时长生成对应的资源化提示消息
     */
    private fun buildPasswordCopyMessage(timeoutSeconds: Int): UiMessage = when {
        timeoutSeconds >= SECONDS_PER_MINUTE * 2 ->
            UiMessage(R.string.detail_password_copied_timeout_minutes, listOf(timeoutSeconds / SECONDS_PER_MINUTE))
        timeoutSeconds > 0 ->
            UiMessage(R.string.detail_password_copied_timeout_seconds, listOf(timeoutSeconds))
        else ->
            UiMessage(R.string.detail_password_copied_no_clear)
    }

    companion object {
        private const val SECONDS_PER_MINUTE = 60
        private const val TOTP_TICK_MS = 1000L
        private const val TAG = "EntryDetailViewModel"
    }
}
