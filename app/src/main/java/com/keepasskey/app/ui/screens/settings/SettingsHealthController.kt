package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.database.audit.HealthCheckEngine
import com.keepasskey.database.audit.PasswordRiskLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * TASK-21 拆分：设置页「密码库健康度检查」状态控制器。
 * 职责单一：真实审计扫描（HealthCheckEngine）与分数/分档/汇总文案生成；
 * 文案经 [StringsProvider] 资源解析（P3-23），ViewModel 仅做委托转发。
 */
internal class SettingsHealthController(
    private val vaultRepository: VaultRepository,
    private val strings: StringsProvider,
    private val scope: CoroutineScope
) {

    internal data class HealthCheckUiState(
        val healthScore: Int,
        val healthStatus: String,
        val healthMessage: String,
        val weakPasswordCount: Int,
        val reusedPasswordCount: Int,
        val compromisedPasswordCount: Int,
        val lastHealthScanTime: String,
        val isHealthScanning: Boolean
    )

    private val healthStateFlow = MutableStateFlow(initialState())

    val state: StateFlow<HealthCheckUiState> = healthStateFlow.asStateFlow()

    private fun initialState(): HealthCheckUiState = HealthCheckUiState(
        healthScore = 0,
        healthStatus = strings.get(R.string.health_status_not_scanned),
        healthMessage = strings.get(R.string.health_scan_hint_idle),
        weakPasswordCount = 0,
        reusedPasswordCount = 0,
        compromisedPasswordCount = 0,
        lastHealthScanTime = strings.get(R.string.health_status_not_scanned),
        isHealthScanning = false
    )

    fun rescanHealth() {
        if (healthStateFlow.value.isHealthScanning) return
        scope.launch {
            healthStateFlow.update { it.copy(isHealthScanning = true) }
            try {
                val entries = vaultRepository.getKdbxEntries()
                val issues = HealthCheckEngine.analyzeEntries(entries)

                val weakCount = issues.count { it.riskLevel == PasswordRiskLevel.WEAK }
                val reusedCount = issues.count { it.riskLevel == PasswordRiskLevel.REUSED }
                val expiredCount = issues.count { it.riskLevel == PasswordRiskLevel.EXPIRED }

                val calculatedScore = (HEALTH_SCORE_BASE -
                        weakCount * HEALTH_PENALTY_WEAK -
                        reusedCount * HEALTH_PENALTY_REUSED -
                        expiredCount * HEALTH_PENALTY_EXPIRED).coerceIn(0, 100)

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
                        compromisedPasswordCount = 0,
                        lastHealthScanTime = lastScanText
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

    companion object {
        private const val HEALTH_SCORE_BASE = 100
        private const val HEALTH_PENALTY_WEAK = 5
        private const val HEALTH_PENALTY_REUSED = 10
        private const val HEALTH_PENALTY_EXPIRED = 15
    }
}
