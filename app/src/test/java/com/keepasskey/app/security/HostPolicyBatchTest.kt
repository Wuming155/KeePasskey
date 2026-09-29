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
}
