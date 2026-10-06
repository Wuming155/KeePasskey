package com.keepasskey.app.sync

/**
 * ISSUE-P3-381：回前台 / 网络恢复时的远端变更探测策略（纯函数，JVM 可测）。
 *
 * 裁决（批次留痕）：轻量远端探测（ETag / metadata 比对）+ 提示，不直接 syncNow——
 * 与解锁后自动同步形成单一写路径竞争，避免双写；探测只在开关开启时挂。
 * 周期 WorkManager 默认关闭的现状不变。
 */
object ResumeSyncProbePolicy {

    /**
     * 探测节流：两次探测之间的最小间隔（毫秒）。
     * 默认 30s：覆盖快速切后台/前台抖动，又不会每次 resume 都打远端。
     */
    const val MIN_PROBE_INTERVAL_MILLIS = 30_000L

    /**
     * 判定本次 resume / 网络恢复是否应触发远端探测。
     *
     * @param probeEnabled 设置开关（resume 探测总开关）
     * @param isLocked 会话是否已锁定（锁定态不探测，解锁后既有链路负责）
     * @param lastProbeAtMillis 上次探测时刻；null/0 表示从未探测
     * @param nowMillis 当前时刻（可注入供单测）
     * @return true = 应探测
     */
    fun shouldProbe(
        probeEnabled: Boolean,
        isLocked: Boolean,
        lastProbeAtMillis: Long?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (!probeEnabled) return false
        if (isLocked) return false
        val last = lastProbeAtMillis ?: 0L
        if (last <= 0L) return true
        return nowMillis - last >= MIN_PROBE_INTERVAL_MILLIS
    }

    /**
     * 单次探测的**编排计划**（ISSUE-P2-496）。
     *
     * @param shouldProbe 本次是否应发起探测
     * @param classifyBaselineMillis 供 [classify] 使用的**探测前**基线时间戳；不应探测时为 null。
     *   必须是调用时传入的 [lastProbeAtMillis] 快照，**绝不**是 [nowMillis]——否则 [classify]
     *   内的节流复核会把「本次探测刚刷新的时刻」当成「上次探测」⇒ `nowMillis - last < 30s`
     *   ⇒ 恒判 [ProbeOutcome.Skipped]（自败节流）。
     */
    data class ProbePlan(val shouldProbe: Boolean, val classifyBaselineMillis: Long?)

    /**
     * 计算单次探测计划：是否探测 + 供 [classify] 使用的**探测前**基线快照。
     *
     * 关键契约（ISSUE-P2-496 根因）：[ProbePlan.classifyBaselineMillis] 恒等于本次传入的
     * [lastProbeAtMillis]（探测前基线）。节流时间戳由协调器在探测**完成后**才回写，
     * 探测期间 [classify] 复核的始终是旧基线。
     */
    fun planProbe(
        probeEnabled: Boolean,
        isLocked: Boolean,
        lastProbeAtMillis: Long?,
        nowMillis: Long = System.currentTimeMillis()
    ): ProbePlan {
        val should = shouldProbe(probeEnabled, isLocked, lastProbeAtMillis, nowMillis)
        return ProbePlan(
            shouldProbe = should,
            classifyBaselineMillis = if (should) lastProbeAtMillis else null
        )
    }

    /** 探测结果语义 */
    sealed class ProbeOutcome {
        /** 远端有变化，提示用户手动同步或触发既有 syncNow 入口 */
        data class RemoteChanged(val remoteEtag: String? = null) : ProbeOutcome()

        /** 远端与本地一致，无需动作 */
        object Unchanged : ProbeOutcome()

        /** 探测失败（网络/认证）；不覆盖、不静默 syncNow */
        data class Failed(val reason: String) : ProbeOutcome()

        /** 开关关闭 / 会话锁定 / 节流未到 —— 不探测 */
        object Skipped : ProbeOutcome()
    }

    /**
     * 解码探测结果 → 用户提示语义。
     */
    fun classify(
        probeEnabled: Boolean,
        isLocked: Boolean,
        lastProbeAtMillis: Long?,
        hasRemote: Boolean,
        remoteChanged: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): ProbeOutcome {
        if (!shouldProbe(probeEnabled, isLocked, lastProbeAtMillis, nowMillis)) {
            return ProbeOutcome.Skipped
        }
        return when {
            !hasRemote -> ProbeOutcome.Failed("远端元数据不可用")
            remoteChanged -> ProbeOutcome.RemoteChanged()
            else -> ProbeOutcome.Unchanged
        }
    }

    /** 距上次探测的间隔（毫秒）；从未探测返回 Long.MAX_VALUE */
    fun elapsedSince(lastProbeAtMillis: Long?, nowMillis: Long = System.currentTimeMillis()): Long {
        val last = lastProbeAtMillis ?: return Long.MAX_VALUE
        if (last <= 0L) return Long.MAX_VALUE
        return nowMillis - last
    }

    /** 探测节流是否已到期 */
    fun throttleElapsed(lastProbeAtMillis: Long?, nowMillis: Long = System.currentTimeMillis()): Boolean {
        return elapsedSince(lastProbeAtMillis, nowMillis) >= MIN_PROBE_INTERVAL_MILLIS
    }
}
