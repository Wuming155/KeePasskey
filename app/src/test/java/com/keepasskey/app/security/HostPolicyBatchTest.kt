package com.keepasskey.app.security

import com.keepasskey.app.sync.ResumeSyncProbePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import java.nio.file.Path

/**
 * ISSUE-P2-378 / P2-379 / P3-381：主机可测策略面（JVM）。
 */
class HostPolicyBatchTest {

    @Test
    fun `mtime 容差内且 size 相同不判漂移`() {
        val base = VaultFileBaseline.fromMetadata("/tmp/a.kdbx", lastModifiedMillis = 1_000_000L, sizeBytes = 10)
        val same = VaultFileBaseline.fromMetadata("/tmp/a.kdbx", lastModifiedMillis = 1_000_500L, sizeBytes = 10)
        assertFalse(VaultFileDriftPolicy.isDrifted(base, same))
        assertFalse(VaultFileDriftPolicy.shouldAbortSave(base, same))
    }

    @Test
    fun `size 变化判漂移`() {
        val base = VaultFileBaseline.fromMetadata("/tmp/a.kdbx", 1_000_000L, 10)
        val cur = VaultFileBaseline.fromMetadata("/tmp/a.kdbx", 1_000_000L, 11)
        assertTrue(VaultFileDriftPolicy.shouldAbortSave(base, cur))
    }

    @Test
    fun `mtime 超 1 秒粒度判漂移`() {
        val base = VaultFileBaseline.fromMetadata("/tmp/a.kdbx", 1_000_000L, 10)
        val cur = VaultFileBaseline.fromMetadata("/tmp/a.kdbx", 1_001_000L, 10)
        assertTrue(VaultFileDriftPolicy.isDrifted(base, cur))
    }

    @Test
    fun `current 为 null 判漂移`() {
        val base = VaultFileBaseline.fromMetadata("/tmp/a.kdbx", 1L, 1)
        assertTrue(VaultFileDriftPolicy.shouldAbortSave(base, null))
    }

    @Test
    fun `本地 File 基线读取`() {
        val path: Path = createTempFile(prefix = "kdbx", suffix = ".kdbx")
        try {
            path.writeText("hello")
            val file = path.toFile()
            val base = VaultFileBaseline.fromFile(file)!!
            assertTrue(base.sizeBytes > 0)
            assertTrue(VaultFileBaseline.fromFile(File("/definitely/not/exists.kdbx")) == null)
        } finally {
            path.toFile().delete()
        }
    }

    @Test
    fun `前台闲置超时锁定`() {
        val now = 1_000_000L
        val decision = ForegroundIdleLockPolicy.decide(
            idleTimeoutSeconds = 300,
            lastInteractionMillis = now - 300_000L,
            nowMillis = now
        )
        assertTrue(decision is ForegroundIdleLockPolicy.IdleDecision.LockNow)
    }

    @Test
    fun `前台交互刷新后未到点则保活`() {
        val now = 1_000_000L
        val decision = ForegroundIdleLockPolicy.decide(
            idleTimeoutSeconds = 300,
            lastInteractionMillis = now - 1_000L,
            nowMillis = now
        )
        assertTrue(decision is ForegroundIdleLockPolicy.IdleDecision.KeepAlive)
    }

    @Test
    fun `时钟回拨 fail-closed 锁定`() {
        val decision = ForegroundIdleLockPolicy.decide(
            idleTimeoutSeconds = 300,
            lastInteractionMillis = 2_000_000L,
            nowMillis = 1_000_000L
        )
        assertTrue(decision is ForegroundIdleLockPolicy.IdleDecision.LockNow)
    }

    @Test
    fun `永不档不因前台闲置锁定`() {
        val decision = ForegroundIdleLockPolicy.decide(-1, lastInteractionMillis = 0L, nowMillis = 999_999L)
        assertTrue(decision is ForegroundIdleLockPolicy.IdleDecision.NeverIdleLock)
    }

    @Test
    fun `锁定态交互不刷新`() {
        assertFalse(ForegroundIdleLockPolicy.shouldRefreshOnInteraction(true))
        assertTrue(ForegroundIdleLockPolicy.shouldRefreshOnInteraction(false))
    }

    @Test
    fun `resume 探测节流与锁定态跳过`() {
        val now = 10_000L
        assertTrue(ResumeSyncProbePolicy.shouldProbe(true, false, null, now))
        assertFalse(ResumeSyncProbePolicy.shouldProbe(false, false, null, now))
        assertFalse(ResumeSyncProbePolicy.shouldProbe(true, true, null, now))
        assertFalse(
            ResumeSyncProbePolicy.shouldProbe(
                true,
                false,
                lastProbeAtMillis = now - 1_000L,
                nowMillis = now
            )
        )
        assertTrue(
            ResumeSyncProbePolicy.shouldProbe(
                true,
                false,
                lastProbeAtMillis = now - ResumeSyncProbePolicy.MIN_PROBE_INTERVAL_MILLIS,
                nowMillis = now
            )
        )
    }

    @Test
    fun `resume 探测分类`() {
        assertTrue(
            ResumeSyncProbePolicy.classify(true, false, null, hasRemote = true, remoteChanged = true)
                    is ResumeSyncProbePolicy.ProbeOutcome.RemoteChanged
        )
        assertTrue(
            ResumeSyncProbePolicy.classify(true, false, null, hasRemote = true, remoteChanged = false)
                    is ResumeSyncProbePolicy.ProbeOutcome.Unchanged
        )
        assertTrue(
            ResumeSyncProbePolicy.classify(true, false, null, hasRemote = false, remoteChanged = false)
                    is ResumeSyncProbePolicy.ProbeOutcome.Failed
        )
        assertTrue(
            ResumeSyncProbePolicy.classify(false, false, null, hasRemote = true, remoteChanged = true)
                    is ResumeSyncProbePolicy.ProbeOutcome.Skipped
        )
    }

    /**
     * ISSUE-P2-496 主判据：节流已过（now = baseline + 30s）时，[ResumeSyncProbePolicy.planProbe]
     * 返回的 classify 基线必须是**探测前** baseline，而非当下 now。
     *
     * 判别力：若 planProbe 误把「本次刷新的 now」当基线（缺陷排序），
     * `classifyBaselineMillis == baseline` 立刻失败；且以 now 为基线分类会退化为 [Skipped]。
     */
    @Test
    fun `ISSUE-P2-496 探测计划基线取探测前值而非当下`() {
        val baseline = 1_000_000L
        val now = baseline + ResumeSyncProbePolicy.MIN_PROBE_INTERVAL_MILLIS
        val plan = ResumeSyncProbePolicy.planProbe(
            probeEnabled = true,
            isLocked = false,
            lastProbeAtMillis = baseline,
            nowMillis = now
        )
        assertTrue(plan.shouldProbe)
        // 基线必须是探测前的 baseline（不是 now）
        assertTrue(plan.classifyBaselineMillis == baseline)
        // 以该基线分类 ⇒ 真实结论 Unchanged（自败节流下会退化成 Skipped）
        assertTrue(
            ResumeSyncProbePolicy.classify(
                true, false, plan.classifyBaselineMillis,
                hasRemote = true, remoteChanged = false, nowMillis = now
            ) is ResumeSyncProbePolicy.ProbeOutcome.Unchanged
        )
    }

    /**
     * ISSUE-P2-496 回归形态锁定：把「刚刷新的 now」当上次探测时刻再复核节流 ⇒ 恒 [Skipped]。
     * 这条即缺陷的成因形态，防其经任何路径复活。
     */
    @Test
    fun `ISSUE-P2-496 回归形态：以当下为基线必判 Skipped`() {
        val baseline = 1_000_000L
        val now = baseline + ResumeSyncProbePolicy.MIN_PROBE_INTERVAL_MILLIS
        assertTrue(
            ResumeSyncProbePolicy.classify(
                true, false, lastProbeAtMillis = now,
                hasRemote = true, remoteChanged = false, nowMillis = now
            ) is ResumeSyncProbePolicy.ProbeOutcome.Skipped
        )
    }
}
