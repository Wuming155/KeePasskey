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
 * 说明：安装来源信号（[untrustedInstallSource]）已于 ISSUE-P3-231 移除——`installingPackageName`
 * 可被任意应用伪造、无法防伪，且误伤正常侧载 / 第三方商店用户，故不再参与等级判定；篡改防护交给
 * 签名指纹核对与未接入的 Play Integrity。现存静态可疑特征仅余 [appDebuggable]（FLAG_DEBUGGABLE）。
 */
data class IntegritySignals(
    val debuggerAttached: Boolean = false,
    val appDebuggable: Boolean = false,
    val rootArtifactsDetected: Boolean = false,
    val magiskDetected: Boolean = false,
    val hookFrameworkDetected: Boolean = false,
    /**
     * 已启用**本应用以外**的无障碍服务（ISSUE-P2-44）。
     *
     * **判定口径（保守、如实声明）**：`AccessibilityManager.getEnabledAccessibilityServiceList()`
     * 返回的任一服务，其包名 **≠ 本应用包名** 即为真——**包含系统预装的 TalkBack 等**。
     * 之所以不排除系统应用：无障碍服务**同等具备读取（乃至代填）任意输入内容的能力**，
     * 系统签名并不改变这一能力；对密码管理器而言这是必须向用户披露的信号。
     *
     * **不参与风险等级判定**：启用无障碍是**合法且必要的可及性配置**（视障用户依赖它），
     * 若因此降级生物解锁 / 自动填充，等于用安全名义剥夺可及性。故本信号只置
     * [IntegrityEnforcement.requireAccessibilityNotice]，**不改变** [RuntimeRiskLevel]。
     */
    val thirdPartyAccessibilityEnabled: Boolean = false,
    /**
     * 正被其他进程 `ptrace`（`/proc/self/status` 的 `TracerPid > 0`，ISSUE-P3-83）。
     *
     * **口径**：由 [ProcTracerPid] 解析、[TracedProcessProbe] 同步读取。该信号覆盖
     * `Debug.isDebuggerConnected()` / JDWP 位**看不到**的一类注入——`ptrace` 系
     * （`process_vm_readv` / `/proc/<pid>/mem`、Frida inject）不产生新映射也不置调试位。
     *
     * **参与等级判定**：与 [debuggerAttached] 同属「动态攻击特征」⇒ 命中即
     * [RuntimeRiskLevel.COMPROMISED]（禁用生物快速解锁 + 自动填充 + 风险提示）。
     *
     * **非阻断承诺**（ISSUE-P3-83 定级依据）：拦不住不依赖 ptrace 的攻击，且可被 hook
     * `open`/`read` 伪造为 `0`。本信号只**提高攻击成本**，不改变既有设计边界；
     * 探测失败（`null`）按「未检测到」处理，**不** fail-closed——否则一个读不到
     * `/proc/self/status` 的 ROM 就会以「纸面加固」换掉整机可用性。
     */
    val beingTraced: Boolean = false
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
    val requireRiskNotice: Boolean,
    /**
     * 是否需在设置页安全分区展示「已启用无障碍服务」状态
     * （ISSUE-P2-44；ISSUE-P3-215 自解锁页迁入——该信号与主密码输入无交互关系，常驻首页属冗余）。
     *
     * 与 [requireRiskNotice] **分离**：后者由完整性等级（ELEVATED / COMPROMISED）驱动并伴随通道降级；
     * 本项由**合法可及性配置**驱动，**只提示、不降级**——判据见
     * [IntegritySignals.thirdPartyAccessibilityEnabled] 的 KDoc。
     */
    val requireAccessibilityNotice: Boolean = false,
    /**
     * 生物识别快速解锁被禁用时的**具体命中信号**，按危害度降序（ISSUE-P2-227）。
     *
     * 与 [disableBiometricQuickUnlock] 同源产出，消费侧据此把「为什么被禁」如实告知用户，
     * 不再只给一句笼统的「设备存在安全风险」。放行态恒为空清单。
     */
    val biometricBlockReasons: List<IntegrityBlockReason> = emptyList()
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
            requireRiskNotice = false,
            biometricBlockReasons = listOf(IntegrityBlockReason.SCAN_UNDETERMINED)
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

    /**
     * 是否必须向用户给出明确风险提示——[IntegrityEnforcement.requireRiskNotice] 的**唯一消费点**
     * （ISSUE-P2-08 验收：「不得静默放行」）。
     *
     * 生产接线：设置页安全分区（`SecuritySettingsScreen`）在返回 true 时渲染
     * `IntegrityRiskCard` 风险说明。数据来源仍是既有 `SettingsViewModel` 下发的
     * [RuntimeIntegrityReport] 快照，无并行数据源。
     *
     * 判定语义与 [evaluate] 的映射严格一致：
     * - [RuntimeRiskLevel.COMPROMISED] / [RuntimeRiskLevel.ELEVATED] → true（此时 [RuntimeIntegrityReport.level]
     *   必为这两档之一，UI 可安全按等级取文案）；
     * - [RuntimeRiskLevel.TRUSTED] / [RuntimeRiskLevel.UNDETERMINED] → false
     *   （未判定不等于已判定为风险，避免误报与 UI 闪烁）；
     * - 未注入快照（null，仅单测/异常装配）→ false，绝不回填「有风险」假值。
     */
    fun requiresRiskNotice(report: RuntimeIntegrityReport?): Boolean =
        report?.enforcement?.requireRiskNotice == true

    /**
     * 是否必须在**设置页安全分区**展示「已启用无障碍服务」状态
     * （ISSUE-P2-44 的唯一消费点；ISSUE-P3-215 自解锁页迁入，判定与「只提示、不降级」语义不变）。
     *
     * 与 [requiresRiskNotice] 正交：本项**不**随等级变化，故 `TRUSTED` 等级下也可能为 true；
     * 未注入快照（null，仅单测 / 异常装配）恒为 false，绝不回填「有风险」假值。
     */
    fun requiresAccessibilityNotice(report: RuntimeIntegrityReport?): Boolean =
        report?.enforcement?.requireAccessibilityNotice == true

    fun evaluate(
        signals: IntegritySignals,
        /**
         * 用户是否启用了运行环境完整性检测（ISSUE-P3-236 / PD-15，出厂默认**关闭**）。
         *
         * `false` 时**只解除通道降级**（生物快速解锁 / 自动填充照常放行、不再要求风险提示），
         * 风险等级与命中清单仍如实产出——`report` 不得因用户关掉阻断而谎报「未发现风险」。
         * 默认 `true` 供纯信号单测与既有调用点沿用原 fail-closed 语义。
         */
        enforcementEnabled: Boolean = true
    ): RuntimeIntegrityReport {
        // ISSUE-P2-44：无障碍信号只影响「是否提示」（设置页安全分区展示），不影响等级与通道降级
        val accessibilityNotice = signals.thirdPartyAccessibilityEnabled
        // ISSUE-P2-227：命中信号清单与等级同源产出（声明顺序即危害度降序），供 UI 点名归因
        val blockReasons = IntegrityBlockReason.from(signals, undetermined = false)
        val level = riskLevelOf(signals)

        return RuntimeIntegrityReport(
            level = level,
            signals = signals,
            enforcement = if (enforcementEnabled) {
                enforcingEnforcement(level, accessibilityNotice, blockReasons)
            } else {
                // ISSUE-P3-236 / PD-15：用户已显式关闭检测 ⇒ 不降级任何通道。
                // `biometricBlockReasons` 按既有约定在放行态恒为空清单——即使信号命中，
                // 也不得向「为什么被禁」的消费侧提供本就未生效的归因。
                IntegrityEnforcement(
                    disableBiometricQuickUnlock = false,
                    disableAutofill = false,
                    requireRiskNotice = false,
                    requireAccessibilityNotice = accessibilityNotice
                )
            }
        )
    }

    /**
     * 信号 → 风险等级（ISSUE-P3-236：自 [evaluate] 提为独立纯函数，判定口径**逐字未改**）。
     *
     * ISSUE-P3-231：安装来源信号已移除（可被伪造、误伤正常侧载），仅 debug 构建属静态可疑特征。
     */
    private fun riskLevelOf(signals: IntegritySignals): RuntimeRiskLevel {
        val compromised = signals.debuggerAttached ||
            signals.beingTraced ||
            signals.rootArtifactsDetected ||
            signals.magiskDetected ||
            signals.hookFrameworkDetected
        return when {
            compromised -> RuntimeRiskLevel.COMPROMISED
            signals.appDebuggable -> RuntimeRiskLevel.ELEVATED
            else -> RuntimeRiskLevel.TRUSTED
        }
    }

    /**
     * 等级 → 阻断策略（ISSUE-P3-236：自 [evaluate] 提为独立纯函数，映射**逐字未改**）。
     *
     * 仅检测启用时使用；[RuntimeRiskLevel.UNDETERMINED] 由 [RuntimeIntegrityReport.UNDETERMINED]
     * 与 [RuntimeIntegrityDetector] 的保守分支单独产出，本函数入参恒为三档真实等级。
     */
    private fun enforcingEnforcement(
        level: RuntimeRiskLevel,
        accessibilityNotice: Boolean,
        blockReasons: List<IntegrityBlockReason>
    ): IntegrityEnforcement = when (level) {
        RuntimeRiskLevel.COMPROMISED -> IntegrityEnforcement(
            disableBiometricQuickUnlock = true,
            disableAutofill = true,
            requireRiskNotice = true,
            requireAccessibilityNotice = accessibilityNotice,
            biometricBlockReasons = blockReasons
        )

        RuntimeRiskLevel.ELEVATED -> IntegrityEnforcement(
            disableBiometricQuickUnlock = true,
            disableAutofill = false,
            requireRiskNotice = true,
            requireAccessibilityNotice = accessibilityNotice,
            biometricBlockReasons = blockReasons
        )

        else -> IntegrityEnforcement(
            disableBiometricQuickUnlock = false,
            disableAutofill = false,
            requireRiskNotice = false,
            requireAccessibilityNotice = accessibilityNotice
        )
    }

    /**
     * 以实时信号升级缓存的完整性快照（ISSUE-P3-53 / ISSUE-P3-83，纯函数）。
     *
     * 一次性扫描后缓存时变信号（调试器附加 / 钩子框架 / **ptrace**）必然失真——冷启动后再附加
     * 调试器或 tracer 不会被既有快照捕获。敏感操作前把**实时求值**的信号与缓存信号按「或」合并
     * 后重新裁决，保证「后续判定可捕获」且分级（COMPROMISED / ELEVATED / TRUSTED）与 fail-closed
     * 语义一致。
     *
     * @param base 缓存快照（首次扫描结果）
     * @param debuggerAttached 实时调试器信号（`Debug.isDebuggerConnected()` 等）
     * @param hookFrameworkDetected 实时钩子框架信号（磁盘扫描结果；非 suspend 路径可不提供）
     * @param beingTraced 实时 ptrace 信号（ISSUE-P3-83；`TracerPid > 0`）。
     *   **刻意无默认值**：安全信号不允许「忘记传参即放行」。
     * @param enforcementEnabled 用户是否启用了完整性检测（ISSUE-P3-236 / PD-15）。
     *   默认 `true`（既有 fail-closed 语义）；关闭时仅重算等级与命中项，不降级任何通道。
     */
    fun escalateForLiveSignals(
        base: RuntimeIntegrityReport,
        debuggerAttached: Boolean,
        hookFrameworkDetected: Boolean,
        beingTraced: Boolean,
        enforcementEnabled: Boolean = true
    ): RuntimeIntegrityReport {
        if (!debuggerAttached && !hookFrameworkDetected && !beingTraced) return base
        return evaluate(
            base.signals.copy(
                debuggerAttached = base.signals.debuggerAttached || debuggerAttached,
                hookFrameworkDetected = base.signals.hookFrameworkDetected || hookFrameworkDetected,
                beingTraced = base.signals.beingTraced || beingTraced
            ),
            enforcementEnabled = enforcementEnabled
        )
    }

    /**
     * ISSUE-P3-83：`TracerPid` 原始值 → 「是否正被 trace」的判定（纯函数）。
     *
     * **边界即在此处**：`null`（读不到 / 字段缺失）按「未检测到」处理并**不**判为被 trace——
     * 这是明示的 fail-open 取舍，理由见 [IntegritySignals.beingTraced] KDoc。
     * 之所以不把 `!= 0` 写成判据：内核对未被 trace 的进程填 `0`，若实现返回负值（非标准），
     * `!= 0` 会把它误判为被 trace。
     */
    fun isTraced(tracerPid: Int?): Boolean = tracerPid != null && tracerPid > 0

    /**
     * ISSUE-P2-63：非 suspend 门控读到的快照是否已「陈旧」。
     *
     * 快照由一次性扫描（冷启动）与后台周期重扫共同维护；超过新鲜度窗口仍未重扫，
     * 说明后台重扫未能推进（进程被挂起 / IO 受限），此时**不得**用旧快照为敏感通道放行——
     * 否则「启动后附加注入框架」的窗口会被静默放过（本项即该缺陷）。
     *
     * @param snapshotAtMillis 上次扫描完成时刻（0 = 从未扫描）
     * @param nowMillis 当前时刻
     * @param freshnessWindowMillis 新鲜度窗口
     */
    fun isSnapshotStale(
        snapshotAtMillis: Long,
        nowMillis: Long,
        freshnessWindowMillis: Long
    ): Boolean = snapshotAtMillis <= 0L || nowMillis - snapshotAtMillis > freshnessWindowMillis
}
