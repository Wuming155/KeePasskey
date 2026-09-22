package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.R
import com.keepasskey.app.data.breach.BreachCheckCoordinator
import com.keepasskey.app.data.breach.BreachCheckOutcome
import com.keepasskey.app.data.breach.BreachCheckStatus
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.database.audit.HealthCheckEngine
import com.keepasskey.database.audit.PasswordRiskLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TASK-21 拆分：设置页「密码库健康度检查」状态控制器。
 * 职责单一：真实审计扫描（HealthCheckEngine）与分数/分档/汇总文案生成；
 * 文案经 [StringsProvider] 资源解析（P3-23），ViewModel 仅做委托转发。
 *
 * TASK-47：新增已泄露密码检测（HIBP k-匿名范围查询）接线——
 * - **开关门控**：[breachCheckEnabled] 为 false 时完全不触碰网络（关闭态零外联）；
 * - **失败如实上浮**：查询失败转 [BreachCheckStatus.FAILED] 并透出原因，绝不静默回落 0；
 * - **无值即无值**：未启用 / 未检测时计数为 null，由 UI 如实展示而非以 0 冒充「安全」。
 */
internal class SettingsHealthController(
    private val vaultRepository: VaultRepository,
    private val breachCheckCoordinator: BreachCheckCoordinator,
    private val strings: StringsProvider,
    private val breachCheckEnabled: () -> Boolean,
    private val scope: CoroutineScope,
    /**
     * 「开启泄露检测并就地扫描」的偏好持久化通道（由 ViewModel 注入
     * `extendedPreferences.setBreachCheckEnabled`，形态同其 `persistLockWhenScreenOff` 的
     * lambda 注入；ISSUE-P3-257 下沉时新增）。默认空实现仅为既有单测构造点兼容，
     * 生产接线由 `SettingsThinOrchestrationTest` / `OneTapInteractionWiringTest` 锁定。
     */
    private val setBreachCheckEnabled: (Boolean) -> Unit = {}
) {

    internal data class HealthCheckUiState(
        val healthScore: Int,
        val healthStatus: String,
        val healthMessage: String,
        val weakPasswordCount: Int,
        val reusedPasswordCount: Int,
        /** 命中泄露库的条目数；null = 未检测（未启用或失败），绝不回填 0 冒充「未泄露」 */
        val compromisedPasswordCount: Int?,
        val breachCheckStatus: BreachCheckStatus,
        /** 失败原因等补充文案（仅 FAILED 态非空） */
        val breachCheckMessage: String,
        val lastHealthScanTime: String,
        val isHealthScanning: Boolean,
        /** ISSUE-P3-61：是否已完成过一次扫描——未扫描时审计行徽标必须保持中性「未扫描」，
         *  不得以「安全 / 需注意」这类有数据才能支撑的结论误导用户 */
        val hasScanned: Boolean = false
    )

    private val healthStateFlow = MutableStateFlow(initialState())

    val state: StateFlow<HealthCheckUiState> = healthStateFlow.asStateFlow()

    private fun initialState(): HealthCheckUiState = HealthCheckUiState(
        healthScore = 0,
        healthStatus = strings.get(R.string.health_status_not_scanned),
        healthMessage = strings.get(R.string.health_scan_hint_idle),
        weakPasswordCount = 0,
        reusedPasswordCount = 0,
        compromisedPasswordCount = null,
        breachCheckStatus = BreachCheckStatus.DISABLED,
        breachCheckMessage = "",
        lastHealthScanTime = strings.get(R.string.health_status_not_scanned),
        isHealthScanning = false
    )

    fun rescanHealth() {
        if (healthStateFlow.value.isHealthScanning) return
        scope.launch {
            healthStateFlow.update { it.copy(isHealthScanning = true) }
            try {
                // ISSUE-P2-58 AC④：`getKdbxEntries()`（全库投影）与 `HealthCheckEngine.analyzeEntries`
                // （对**每条口令**跑一遍模式扫描）都是纯 CPU 工作——原先在 `scope`（调用方为
                // `viewModelScope`，即 Main 派发器）上直接执行，会阻塞主线程直至整库扫描完毕。
                // 现整体移入 `Dispatchers.Default`：仅结果回写发生在原派发器上。
                val (entries, issues) = withContext(Dispatchers.Default) {
                    val fetched = vaultRepository.getKdbxEntries()
                    fetched to HealthCheckEngine.analyzeEntries(fetched)
                }

                val weakCount = issues.count { it.riskLevel == PasswordRiskLevel.WEAK }
                val reusedCount = issues.count { it.riskLevel == PasswordRiskLevel.REUSED }
                val expiredCount = issues.count { it.riskLevel == PasswordRiskLevel.EXPIRED }

                // TASK-47：泄露检测由开关门控；关闭态不发起任何网络请求
                val breachOutcome = runBreachCheck(entries)

                val breachedCount = breachOutcome.breachedCount

                val calculatedScore = (HEALTH_SCORE_BASE -
                        weakCount * HEALTH_PENALTY_WEAK -
                        reusedCount * HEALTH_PENALTY_REUSED -
                        expiredCount * HEALTH_PENALTY_EXPIRED -
                        breachedCount * HEALTH_PENALTY_BREACHED).coerceIn(0, 100)

                val status = when {
                    calculatedScore >= 90 -> strings.get(R.string.health_status_excellent)
                    calculatedScore >= 70 -> strings.get(R.string.health_status_good)
                    calculatedScore >= 50 -> strings.get(R.string.health_status_fair)
                    else -> strings.get(R.string.health_status_needs_improvement)
                }

                val nowTime = java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.getDefault())
                    .format(java.time.LocalTime.now())
                val lastScanText = "${strings.get(R.string.time_today)} $nowTime"

                val message = when {
                    breachedCount > 0 -> strings.get(
                        R.string.health_msg_breach, breachedCount, weakCount, reusedCount
                    )
                    breachOutcome.status == BreachCheckStatus.FAILED -> strings.get(
                        R.string.health_scan_failed, breachOutcome.errorMessage
                            ?: strings.get(R.string.health_breach_error_unknown)
                    )
                    expiredCount > 0 -> strings.get(
                        R.string.health_msg_expired, expiredCount, weakCount, reusedCount
                    )
                    weakCount == 0 && reusedCount == 0 -> strings.get(R.string.health_msg_clean)
                    reusedCount > 0 && weakCount > 0 -> strings.get(
                        R.string.health_msg_weak_and_reused, weakCount, reusedCount
                    )
                    reusedCount > 0 -> strings.get(R.string.health_msg_reused_only, reusedCount)
                    else -> strings.get(R.string.health_msg_weak_only, weakCount)
                }

                healthStateFlow.update {
                    it.copy(
                        isHealthScanning = false,
                        healthScore = calculatedScore,
                        healthStatus = status,
                        healthMessage = message,
                        weakPasswordCount = weakCount,
                        reusedPasswordCount = reusedCount,
                        compromisedPasswordCount = if (breachOutcome.status == BreachCheckStatus.BREACHED ||
                            breachOutcome.status == BreachCheckStatus.CLEAN
                        ) breachedCount else null,
                        breachCheckStatus = breachOutcome.status,
                        breachCheckMessage = breachOutcome.errorMessage.orEmpty(),
                        lastHealthScanTime = lastScanText,
                        hasScanned = true
                    )
                }
            } catch (e: Exception) {
                healthStateFlow.update {
                    it.copy(
                        isHealthScanning = false,
                        healthMessage = strings.get(R.string.health_scan_failed, e.message ?: "")
                    )
                }
            }
        }
    }

    /**
     * 开启泄露检测并**就地扫描一次**。
     *
     * 立规缘由：该开关位于健康检查页**最底部**（全部审计行之后），而触发它的「重新扫描」按钮在
     * 页面**顶部**的评分卡里；而 [setBreachCheckEnabled] 只写偏好、不触发扫描 ⇒ 用户开启后
     * 开关看着「已开」却什么都没发生，必须自己滚回顶部再点一次才知道结果。
     *
     * 安全边界（TASK-47「联网特性显式开关 + 默认关闭」的裁决**不变**）：
     * 1. **仅开启方向触发**——关闭一律走 [setBreachCheckEnabled]，`breachCheckEnabled()` 为 false 时
     *    `runBreachCheck` 直接返回 `DISABLED`，**零外联**；
     * 2. **并发互斥**：`SettingsHealthController.rescanHealth` 首行即以 `isHealthScanning` 早退，
     *    重复触发不会并发扫描（本方法不另设门控，避免两套状态互不同步）；
     * 3. **无竞态**：偏好写入经 `ExtendedSettingsStore.publish` **同步发布**到内存设置流，
     *    紧随其后的扫描读到的一定是新值（不会退化成「开关开了却没联网」）；
     * 4. **失败如实上浮**：查询失败由控制器转 `BreachCheckStatus.FAILED` 并经 `breachCheckMessage`
     *    透出，绝不以「已防护」掩盖；
     * 5. 仍走既有 k-匿名范围查询路径，**不新增**任何明文 / 完整哈希外发面。
     *
     * ISSUE-P3-257：实现体随 KDoc 自 `SettingsViewModel` 整体下沉，公开 API 形状不变；
     * [setBreachCheckEnabled] 即原 ViewModel 侧同一偏好 setter 的持久化回调。
     */
    fun enableBreachCheckAndScan() {
        setBreachCheckEnabled(true)
        rescanHealth()
    }

    /**
     * 按开关执行泄露比对：关闭态直接返回 [BreachCheckStatus.DISABLED]（零外联）；
     * 开启态失败转 [BreachCheckStatus.FAILED] 并透出原因。
     */
    private suspend fun runBreachCheck(
        entries: List<com.keepasskey.core.model.KdbxEntry>
    ): BreachCheckOutcome {
        if (!breachCheckEnabled()) return BreachCheckOutcome(BreachCheckStatus.DISABLED)
        healthStateFlow.update { it.copy(breachCheckStatus = BreachCheckStatus.CHECKING) }
        return try {
            breachCheckCoordinator.check(entries)
        } catch (e: Exception) {
            BreachCheckOutcome(
                status = BreachCheckStatus.FAILED,
                errorMessage = e.message ?: strings.get(R.string.health_breach_error_unknown)
            )
        }
    }

    companion object {
        private const val HEALTH_SCORE_BASE = 100
        private const val HEALTH_PENALTY_WEAK = 5
        private const val HEALTH_PENALTY_REUSED = 10
        private const val HEALTH_PENALTY_EXPIRED = 15
        private const val HEALTH_PENALTY_BREACHED = 20
    }
}
