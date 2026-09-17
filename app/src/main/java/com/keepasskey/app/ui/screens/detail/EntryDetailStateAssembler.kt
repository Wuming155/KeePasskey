package com.keepasskey.app.ui.screens.detail

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.AutofillBlocklistStore
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.passkey.DomainMatcher
import com.keepasskey.app.ui.model.EntryDecorations
import com.keepasskey.app.ui.model.EntryDisplayPresenter
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import com.keepasskey.app.ui.screens.vault.GroupPathPresenter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/**
 * 详情页 UI 状态的装配器。
 *
 * ISSUE-P3-31 批次 C：由 `EntryDetailViewModel`（原 708 行）按**纯结构性拆分**搬出。
 * combine 的**嵌套形状、叠加顺序与每一层的字段映射逐字保留**——叠加顺序会影响
 * 后一层 `copy` 覆盖前一层的结果，故不做任何"看起来更整齐"的重排。
 */
internal class EntryDetailStateAssembler(
    private val vaultRepository: VaultRepository,
    private val settingsRepository: SettingsRepository,
    private val autofillBlocklistStore: AutofillBlocklistStore,
    private val displayDispatcher: CoroutineDispatcher,
    /**
     * `ISSUE-P3-176`：共享投影流（`shareIn`）所需的作用域。由调用方（ViewModel）传入
     * `viewModelScope`——共享的启停与 `uiState` 的 `WhileSubscribed(5000)` 同生命周期。
     */
    private val scope: CoroutineScope
) {

    /** 装配所需的上游输入（收敛参数表，避免 8 参裸列）。 */
    internal data class Inputs(
        val entryId: Flow<String?>,
        val secrets: EntryDetailSecrets,
        val isFavorite: Flow<Boolean>,
        val userMessage: Flow<UiMessage?>,
        val totpRemainingSeconds: Flow<Int>,
        val liveTotpCode: Flow<String?>,
        val passwordStrengthBits: Flow<Int?>,
        val extendedSettings: Flow<ExtendedSettings>
    )

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

    /**
     * `ISSUE-P3-176`：改为接收**已共享**的当前条目投影流——原实现自行 `getEntry(id)` 订阅一次，
     * 与核心 combine 及 [groupPathFlow] 合计**三处**消费者，而仓库侧是冷流 ⇒ 同一条目的投影
     * （逐字段解密 + 时间格式化）每次数据变更被做三遍。
     */
    private fun decorationsFlow(entryFlow: Flow<UiVaultEntry?>): Flow<EntryDecorations> = entryFlow
        .map { entry ->
            if (entry == null) EntryDecorations.EMPTY else entryDecorations.decorate(entry)
        }
        .flowOn(displayDispatcher)

    /**
     * ISSUE-P3-17：条目所属分组的完整路径（仅在 `showGroupInEntry` 开启时需要）。
     * 分组投影与条目各自独立变化，故与当前条目流组合后按 id 解析，避免依赖发射时序。
     *
     * `ISSUE-P3-176`：`entryFlow` 与 `groupsFlow` 均由调用方传入**已共享**的流
     * （原实现分别自行订阅 `getEntry(id)` 与 `getGroups()` 各一次）。
     */
    private fun groupPathFlow(
        entryFlow: Flow<UiVaultEntry?>,
        groupsFlow: Flow<List<VaultGroup>>
    ): Flow<String?> = combine(entryFlow, groupsFlow) { entry, groups ->
        GroupPathPresenter.fullPathOf(groups, entry?.groupId)
    }

    /**
     * ISSUE-P3-17：显示侧派生量——遮掩初始态决策（[FieldMaskPolicy]）+ 所属分组路径。
     * 偏好快照每次刷新都会重算，但用户显式意图（override 非 null）恒优先。
     *
     * `ISSUE-P3-176`：`entryFlow` / `groupsFlow` 为调用方传入的**已共享**流（原实现经
     * `groupPathFlow(inputs.entryId)` 又各自订阅一次）。
     */
    private fun displayPrefsFlow(
        inputs: Inputs,
        entryFlow: Flow<UiVaultEntry?>,
        groupsFlow: Flow<List<VaultGroup>>
    ): Flow<DetailDisplayPrefs> = combine(
        inputs.extendedSettings,
        inputs.secrets.passwordMaskOverride,
        inputs.secrets.totpMaskOverride,
        groupPathFlow(entryFlow, groupsFlow)
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
    fun assemble(inputs: Inputs): Flow<EntryDetailUiState> {
        val secrets = inputs.secrets
        // ISSUE-P3-176：当前条目投影与分组投影**各共享一份**——原实现里 `getEntry(id)` 有三处
        // 消费者（核心 combine / decorationsFlow / groupPathFlow）、`getGroups()` 有两处
        // （groupPathFlow / 下方 allGroups），而仓库侧是**冷流** ⇒ 同一条目与整库分组的投影
        // 每次数据变更被各做多遍。`replay = 1` 让 combine 立即拿到最近值。
        val currentEntry = inputs.entryId
            .flatMapLatest { id -> if (id != null) vaultRepository.getEntry(id) else flowOf(null) }
            .shareIn(scope, SharingStarted.WhileSubscribed(5000), replay = 1)
        val allGroups = vaultRepository.getGroups()
            .shareIn(scope, SharingStarted.WhileSubscribed(5000), replay = 1)
        return combine(
            currentEntry,
            secrets.passwordMaskOverride,
            secrets.revealedPassword,
            secrets.revealedRevisionPasswords,
            inputs.isFavorite
        ) { entry, passwordOverride, revealed, revPasswords, isFav ->
            DetailCore(entry, passwordOverride, revealed, revPasswords, isFav)
        }
            .combine(
                combine(
                    secrets.protectedVisibility,
                    secrets.revealedProtectedFields,
                    inputs.userMessage
                ) { visMap, revealedFields, message ->
                    DetailExtras(visMap, revealedFields, message)
                }.combine(
                    combine(inputs.totpRemainingSeconds, inputs.liveTotpCode) { r, c -> r to c }
                ) { extras, totp ->
                    extras.copy(totpRemainingSeconds = totp.first, liveTotpCode = totp.second)
                }.combine(inputs.passwordStrengthBits) { extras, strength ->
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
            .combine(displayPrefsFlow(inputs, currentEntry, allGroups)) { state, prefs ->
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
            .combine(decorationsFlow(currentEntry)) { state, decorations ->
                state.copy(decorations = decorations)
            }
            // ISSUE-P3-51：单条「移动到分组」的目标候选叠加（回收站分组由对话框统一过滤）
            .combine(allGroups) { state, groups ->
                state.copy(allGroups = groups)
            }
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

    private companion object {
        private const val SECONDS_PER_MINUTE = 60
    }
}
