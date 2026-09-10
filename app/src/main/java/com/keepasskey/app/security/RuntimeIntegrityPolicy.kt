package com.keepasskey.app.security

/**
 * 运行环境完整性风险等级（ISSUE-P2-08 / ZT-13）。
 *
 * 零信任「Assume Breach」：设备健康状态是访问决策的输入（NIST SP 800-207），
 * 不以「未检测到即安全」的自证方式放行。等级由信号经 [RuntimeIntegrityPolicy] 纯函数裁决。
 */
enum class RuntimeRiskLevel {
    /** 首次后台扫描尚未完成（未判定）：按保守策略短暂 fail-closed，避免静默放行 */
    UNDETERMINED,

    /** 未发现任何风险信号 */
    TRUSTED,

    /** 存在可疑信号（调试构建、非受信任安装来源等），尚未构成直接动态攻击特征 */
    ELEVATED,

    /** 存在明确的动态攻击特征（调试器附加、root / Magisk、Frida / Xposed 注入） */
    COMPROMISED
}

/**
 * 完整性探测原始信号集合（纯数据，无 Android 依赖，便于 JVM 单测构造）。
 *
 * 说明：安装来源与调试签名同属「可选」信号——仅安装来源无法判定（installer 为 null，
 * 如 adb 直装）时不升级风险，避免误报；调试签名已由 [appDebuggable]（FLAG_DEBUGGABLE）覆盖。
 */
data class IntegritySignals(
    val debuggerAttached: Boolean = false,
    val appDebuggable: Boolean = false,
    val rootArtifactsDetected: Boolean = false,
    val magiskDetected: Boolean = false,
    val hookFrameworkDetected: Boolean = false,
    val untrustedInstallSource: Boolean = false
) {
    companion object {
        /** 全无命中的干净信号 */
        val NONE = IntegritySignals()
    }
}

/**
 * 风险等级到敏感通道的策略映射（fail-closed 分级）。
 *
 * - [disableBiometricQuickUnlock]：禁用生物识别快速解锁（解封主密码凭据的高价值通道）；
 * - [disableAutofill]：禁用自动填充下发的数据集与保存落库；
 * - [requireRiskNotice]：要求 UI 给出明确风险提示（不得静默放行）。
 */
data class IntegrityEnforcement(
    val disableBiometricQuickUnlock: Boolean,
    val disableAutofill: Boolean,
    val requireRiskNotice: Boolean
) {
    companion object {
        /** 无风险：全部通道放行 */
        val ALLOWED = IntegrityEnforcement(
            disableBiometricQuickUnlock = false,
            disableAutofill = false,
            requireRiskNotice = false
        )

        /**
         * 扫描未完成时的保守策略：短暂禁用生物快速解锁与自动填充
         * （敏感通道等首次扫描结果，而不是先放行后补救）。
         * 不要求风险提示——未判定不等于已判定为风险。
         */
        val UNDETERMINED = IntegrityEnforcement(
            disableBiometricQuickUnlock = true,
            disableAutofill = true,
            requireRiskNotice = false
        )
    }
}

/**
 * 完整性扫描快照（风险等级 + 原始信号 + 生效策略）。
 * 经 [RuntimeIntegrityDetector.report] 暴露给 UI 用于风险提示。
 */
data class RuntimeIntegrityReport(
    val level: RuntimeRiskLevel,
    val signals: IntegritySignals,
    val enforcement: IntegrityEnforcement
) {
    companion object {
        /** 首次扫描完成前的中性快照：等级未判定、按保守策略执行 */
        val UNDETERMINED = RuntimeIntegrityReport(
            level = RuntimeRiskLevel.UNDETERMINED,
            signals = IntegritySignals.NONE,
            enforcement = IntegrityEnforcement.UNDETERMINED
        )
    }
}

/**
 * 完整性风险裁决内核（纯 Kotlin，零 Android 依赖，单测全覆盖映射矩阵）。
 *
 * 映射规则（fail-closed 分级）：
 * 1. 任一「动态攻击特征」命中（调试器附加 / root 痕迹 / Magisk 痕迹 / 钩子框架）→
 *    [RuntimeRiskLevel.COMPROMISED]：同时禁用生物快速解锁与自动填充，并要求风险提示；
 * 2. 仅「静态可疑特征」命中（应用可调试 / 非受信任安装来源）→
 *    [RuntimeRiskLevel.ELEVATED]：禁用生物快速解锁并要求风险提示，但保留自动填充可用；
 * 3. 全无命中 → [RuntimeRiskLevel.TRUSTED]：全部放行。
 */
object RuntimeIntegrityPolicy {

    fun evaluate(signals: IntegritySignals): RuntimeIntegrityReport {
        val compromised = signals.debuggerAttached ||
            signals.rootArtifactsDetected ||
            signals.magiskDetected ||
            signals.hookFrameworkDetected
        val elevated = signals.appDebuggable || signals.untrustedInstallSource

        return when {
            compromised -> RuntimeIntegrityReport(
                level = RuntimeRiskLevel.COMPROMISED,
                signals = signals,
                enforcement = IntegrityEnforcement(
                    disableBiometricQuickUnlock = true,
                    disableAutofill = true,
                    requireRiskNotice = true
                )
            )

            elevated -> RuntimeIntegrityReport(
                level = RuntimeRiskLevel.ELEVATED,
                signals = signals,
                enforcement = IntegrityEnforcement(
                    disableBiometricQuickUnlock = true,
                    disableAutofill = false,
                    requireRiskNotice = true
                )
            )

            else -> RuntimeIntegrityReport(
                level = RuntimeRiskLevel.TRUSTED,
                signals = signals,
                enforcement = IntegrityEnforcement.ALLOWED
            )
        }
    }
}
