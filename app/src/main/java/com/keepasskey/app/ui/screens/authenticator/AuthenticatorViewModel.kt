package com.keepasskey.app.ui.screens.authenticator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.ClipboardSecurityManager
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.util.tickerFlow
import com.keepasskey.core.otp.OtpEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AuthenticatorViewModel @Inject constructor(
    private val vaultRepository: VaultRepository,
    // 允许为 null 仅用于 JVM 单测注入（测试环境无法提供系统剪贴板服务）；生产 DI 恒定注入真实实现
    private val clipboardSecurityManager: ClipboardSecurityManager? = null
) : ViewModel() {

    companion object {
        /** 写入剪贴板时展示给系统的标签（不参与任何安全判定，仅作提示） */
        private const val TOTP_CLIP_LABEL = "TOTP Token"
    }

    private val searchQueryFlow = MutableStateFlow("")
    private val userMessageFlow = MutableStateFlow<UiMessage?>(null)

    /**
     * 秒级 TOTP 倒计时触发器：在下方 uiState 的 combine 中并不消费其值（占位参数），
     * 唯一职责是每秒推动各卡片按自身 period 重算剩余秒数与动态码。
     *
     * P1 整改：原实现为手写 `while (isActive) { delay(1000); ... }` 常驻循环，
     * 与 VaultList / EntryDetail 三份逐字重复且各自的 delay 起点互不对齐；
     * 现改由官方 tickerFlow 冷流 + stateIn 驱动——随 UI 订阅自动启停（WhileSubscribed），
     * 取消即终止，且可被 kotlinx-coroutines-test 虚拟时钟（advanceTimeBy）精确推进。
     */
    private val timerSecondsFlow: StateFlow<Int> = tickerFlow()
        .map { OtpEngine.getRemainingSeconds() }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = OtpEngine.getRemainingSeconds()
        )

    val uiState: StateFlow<AuthenticatorUiState> = combine(
        vaultRepository.getEntries(),
        searchQueryFlow,
        timerSecondsFlow,
        userMessageFlow
    ) { entries, query, _, message ->
        // F2 整改：TOTP 种子不再随条目投影下发（UiVaultEntry 已移除 totpSecret）。
        // 依据投影层即时计算出的 totpCode 识别 TOTP 条目，验证码经仓库 calculateEntryTotp
        // 按需单条重算——种子解析与计算均在数据层内完成，绝不外泄到 UI 层。
        val totpEntries = entries.filter { it.totpCode != null }

        val filtered = if (query.isBlank()) {
            totpEntries
        } else {
            totpEntries.filter {
                it.title.contains(query, ignoreCase = true) ||
                        it.username.contains(query, ignoreCase = true) ||
                        it.url.contains(query, ignoreCase = true)
            }
        }

        val items = filtered.map { entry ->
            val snapshot = vaultRepository.calculateEntryTotp(entry.id)
            val period = snapshot?.periodSeconds ?: entry.totpPeriod
            val remaining = OtpEngine.getRemainingSeconds(periodSeconds = period)

            // TASK-33 整改：快照缺失（种子缺失/解析失败/计算异常）时不再回退假码 "000000"，
            // 改为下发 null + 占位符 "------"，UI 端禁用复制——假码会诱导用户复制无效第二因子
            val raw = snapshot?.code

            val formatted = raw?.let {
                when (it.length) {
                    6 -> "${it.substring(0, 3)} ${it.substring(3)}"
                    8 -> "${it.substring(0, 4)} ${it.substring(4)}"
                    else -> it
                }
            } ?: "------"

            TotpCardItem(
                entryId = entry.id,
                title = entry.title,
                account = entry.username,
                codeFormatted = formatted,
                codeRaw = raw,
                remainingSeconds = remaining,
                iconName = entry.iconName,
                url = entry.url
            )
        }

        AuthenticatorUiState(
            items = items,
            searchQuery = query,
            userMessage = message
        )
    }
        // TASK-42 整改（P2-30）：combine 变换内含 calculateEntryTotp（种子解析+HMAC 计算），
        // 显式 flowOn(Default) 使全部上游变换脱离主线程——不依赖上游实现的调度选择，兜底防 ANR
        .flowOn(Dispatchers.Default)
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

    fun clearUserMessage() {
        userMessageFlow.value = null
    }
}
