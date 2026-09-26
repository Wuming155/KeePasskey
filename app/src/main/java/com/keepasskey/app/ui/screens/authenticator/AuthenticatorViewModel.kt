package com.keepasskey.app.ui.screens.authenticator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.ClipboardSecurityManager
import com.keepasskey.app.ui.model.EntryDisplayDispatcher
import com.keepasskey.app.ui.model.TotpCountdownTracker
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.UiVaultEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthenticatorViewModel @Inject constructor(
    private val vaultRepository: VaultRepository,
    // 允许为 null 仅用于 JVM 单测注入（测试环境无法提供系统剪贴板服务）；生产 DI 恒定注入真实实现
    private val clipboardSecurityManager: ClipboardSecurityManager? = null,
    // ISSUE-P3-182：展示装配调度器（生产 Dispatchers.Default；单测注入测试调度器保证断言确定性）
    @EntryDisplayDispatcher private val displayDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    companion object {
        /** 写入剪贴板时展示给系统的标签（不参与任何安全判定，仅作提示） */
        private const val TOTP_CLIP_LABEL = "TOTP Token"
    }

    private val searchQueryFlow = MutableStateFlow("")
    private val userMessageFlow = MutableStateFlow<UiMessage?>(null)

    /**
     * ISSUE-P3-175 ② / ISSUE-P3-182：**本页内容流**——带 TOTP 的条目（已按搜索词过滤）。
     *
     * 独立成流的唯一目的是把「内容」与「秒级节拍」拆成两条通道：原实现把秒级 tick 作为
     * combine 的占位参数（变换体内以 `_` 丢弃），其唯一作用是**触发整页重建**——
     * 每拍都新建整份 `items` 且 `remainingSeconds` 每拍都变，全部卡片因此每秒重组；
     * 现节拍与实时码只经 [nowSeconds] / [liveCodes] 两条窄通道下发，本流仅在
     * 「条目或搜索词变化」时发射 ⇒ **周期内零重建**。
     *
     * 取码（种子解析 + HMAC）不在本流内：交给 [TotpCountdownTracker] 的周期边界通道，
     * 本流只做过滤与映射（无解密、无计算）。
     */
    private val totpEntriesFlow: StateFlow<List<UiVaultEntry>> =
        combine(vaultRepository.getEntries(), searchQueryFlow) { entries, query ->
            val totpEntries = entries.filter { it.totpCode != null }
            if (query.isBlank()) {
                totpEntries
            } else {
                totpEntries.filter {
                    it.title.contains(query, ignoreCase = true) ||
                            it.username.contains(query, ignoreCase = true) ||
                            it.url.contains(query, ignoreCase = true)
                }
            }
        }
            .flowOn(displayDispatcher)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    /**
     * ISSUE-P3-182：与列表页**同一实现**的周期边界通道（`ISSUE-P3-29` 拆出的共享协作者）。
     * 复用而非再写一份的理由：仓内既有 P1 整改已把「三份逐字重复的秒级节拍」收敛为一，
     * 再复制一份即为回归。种子解析与验证码计算全在数据层完成，本类只持有结果。
     */
    private val totpTracker = TotpCountdownTracker(
        vaultRepository = vaultRepository,
        scope = viewModelScope,
        currentEntries = { totpEntriesFlow.value },
        dispatcher = displayDispatcher
    )

    /** 窄通道：秒级**刻度**（倒计时由卡片按条目自身周期现算，ISSUE-P3-158） */
    val nowSeconds: StateFlow<Long> get() = totpTracker.nowSeconds

    /** 窄通道：`entryId → 本周期实时验证码`（仅在周期边界重算） */
    val liveCodes: StateFlow<Map<String, String>> get() = totpTracker.liveCodes

    val uiState: StateFlow<AuthenticatorUiState> = combine(
        totpEntriesFlow,
        searchQueryFlow,
        userMessageFlow
    ) { entries, query, message ->
        // ISSUE-P3-182：本变换**不再含任何解密 / HMAC / 逐条挂起调用**——内容来自
        // totpEntriesFlow（条目或搜索词变化才发射），秒级与周期性的动态只走窄通道。
        // 搜索框回显仍直接取 searchQueryFlow（键入即时回显，不等过滤流水完成）。
        val items = entries.map { entry ->
            TotpCardItem(
                entryId = entry.id,
                title = entry.title,
                account = entry.username,
                // F2 整改：TOTP 种子不随条目投影下发（UiVaultEntry 已移除 totpSecret）。
                // 此处只取投影层**即时计算**的兜底之码（归一到纯数字，与窄通道之码同形态）；
                // 本周期实时之码由 totpTracker 下发，卡片取 `liveCodes[entryId] ?: codeRaw`。
                codeRaw = entry.totpCode?.replace(" ", ""),
                periodSeconds = entry.totpPeriod,
                iconName = entry.iconName,
                url = entry.url,
                // ISSUE-P3-182：HOTP 之码由用户显式推进计数器决定（推进后重新投影即得新码），
                // 不在时间通道内——与详情页 `EntryDetailTotpTicker` 只跟 TOTP 的口径一致。
                isHotp = entry.isHotp
            )
        }

        AuthenticatorUiState(
            items = items,
            searchQuery = query,
            userMessage = message
        )
    }
        // TASK-42 整改（P2-30）：显式 flowOn 使全部上游变换脱离 Main——不依赖上游实现的
        // 调度选择，兜底防 ANR。ISSUE-P3-182 后本变换已无种子解析与 HMAC，但条目映射与
        // 状态构造仍不落在主线程（与列表页 §174 同一调度器口径）
        .flowOn(displayDispatcher)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AuthenticatorUiState()
        )

    fun onSearchQueryChange(query: String) {
        searchQueryFlow.value = query
    }

    /**
     * 复制 TOTP 动态码至受保护剪贴板。
     *
     * P0 整改：此前复制逻辑直接写在 AuthenticatorScreen 的 Composable 内部并直连 ClipboardManager，
     * 绕过了 [ClipboardSecurityManager] 的定时擦除链路。验证码生命周期短、泄露即为第二因子失效，
     * 滞留剪贴板等同于长期敞口；现统一走受保护复制（敏感标记 + 按用户配置超时自动清空）。
     */
    fun copyTotpCode(code: String) {
        clipboardSecurityManager?.copySensitiveText(TOTP_CLIP_LABEL, code)
        userMessageFlow.value = UiMessage(R.string.auth_totp_copied, listOf(code))
    }

    /**
     * ISSUE-P3-49：HOTP 取码——推进计数器（**先落库成功**）并复制本次所出之码。
     *
     * 与 TOTP 的 `copyTotpCode`（复制当前码、无副作用）语义不同：HOTP 每次取码都会消费一个
     * 计数器值，故必须先持久化计数器推进、成功后才交付该码；失败如实上浮，不产出未推进的码。
     */
    fun advanceHotpAndCopy(entryId: String) {
        viewModelScope.launch {
            when (val result = vaultRepository.advanceEntryHotpCounter(entryId)) {
                is com.keepasskey.core.result.KdbxResult.Success -> {
                    val code = result.data.code
                    clipboardSecurityManager?.copySensitiveText(TOTP_CLIP_LABEL, code)
                    userMessageFlow.value = UiMessage(R.string.auth_totp_copied, listOf(code))
                }
                is com.keepasskey.core.result.KdbxResult.Failure ->
                    userMessageFlow.value = UiMessage(R.string.op_failed, listOf(result.message))
            }
        }
    }

    fun clearUserMessage() {
        userMessageFlow.value = null
    }
}
