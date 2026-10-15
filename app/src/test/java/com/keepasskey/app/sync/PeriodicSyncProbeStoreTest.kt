package com.keepasskey.app.sync

import com.keepasskey.app.testutil.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodicSyncProbeStoreTest {

    @Test
    fun `锁态元数据观察只把ETag当作不透明变化信号`() {
        assertEquals(
            PeriodicSyncProbePolicy.Observation.Unchanged,
            PeriodicSyncProbePolicy.observe("etag-a", "\"etag-a\"")
        )
        assertEquals(
            PeriodicSyncProbePolicy.Observation.NeedsUnlock(PeriodicSyncNeedsUnlockReason.REMOTE_CHANGED),
            PeriodicSyncProbePolicy.observe("etag-a", "etag-b")
        )
        assertEquals(
            PeriodicSyncProbePolicy.Observation.NeedsUnlock(PeriodicSyncNeedsUnlockReason.INITIAL_BASELINE_REQUIRED),
            PeriodicSyncProbePolicy.observe(null, "etag-a")
        )
    }

    @Test
    fun `待解锁记录跨实例持久化并阻止后续网络探测`() {
        val preferences = InMemorySharedPreferences()
        val firstProcessStore = PeriodicSyncProbeStore(preferences.context())
        val secondProcessStore = PeriodicSyncProbeStore(preferences.context())
        val vaultId = "/private/vault.kdbx"
        val target = "a".repeat(64)
        val record = PeriodicSyncProbeRecord(target, "etag-a", needsUnlock = true)

        assertTrue(firstProcessStore.write(vaultId, record))
        assertEquals(PeriodicSyncProbeRead.Stored(record), secondProcessStore.read(vaultId))
        assertEquals(
            PeriodicSyncProbePolicy.Preflight.NeedsUnlock(PeriodicSyncNeedsUnlockReason.PENDING_UNLOCK),
            PeriodicSyncProbePolicy.preflight(secondProcessStore.read(vaultId), target)
        )
        assertEquals(
            PeriodicSyncProbePolicy.Preflight.NeedsUnlock(PeriodicSyncNeedsUnlockReason.TARGET_CHANGED),
            PeriodicSyncProbePolicy.preflight(secondProcessStore.read(vaultId), "b".repeat(64))
        )
    }

    @Test
    fun `损坏或不完整探测状态关闭网络请求且库身份隔离`() {
        val preferences = InMemorySharedPreferences()
        val store = PeriodicSyncProbeStore(preferences.context())
        val vaultA = "/vault-a.kdbx"
        val vaultB = "/vault-b.kdbx"
        val target = "c".repeat(64)
        assertTrue(store.write(vaultA, PeriodicSyncProbeRecord(target, "etag-a", needsUnlock = false)))

        val pendingKey = preferences.storage.keys.single { it.endsWith("_pending") }
        preferences.storage.remove(pendingKey)

        assertEquals(PeriodicSyncProbeRead.Corrupt, store.read(vaultA))
        assertEquals(PeriodicSyncProbeRead.Missing, store.read(vaultB))
        assertEquals(
            PeriodicSyncProbePolicy.Preflight.NeedsUnlock(PeriodicSyncNeedsUnlockReason.PROBE_STATE_CORRUPT),
            PeriodicSyncProbePolicy.preflight(store.read(vaultA), target)
        )
        assertFalse(store.write(vaultA, PeriodicSyncProbeRecord(target, "bad\netag", needsUnlock = true)))
    }

    @Test
    fun `Worker输出保留锁定态分类且不输出同步错误消息`() {
        val needsUnlock = PeriodicSyncWorkResult.NeedsUnlock(PeriodicSyncNeedsUnlockReason.REMOTE_CHANGED)
            .toWorkData()
        assertEquals(PeriodicSyncWorker.STATUS_NEEDS_UNLOCK, needsUnlock.getString(PeriodicSyncWorker.OUTPUT_STATUS))
        assertEquals("REMOTE_CHANGED", needsUnlock.getString(PeriodicSyncWorker.OUTPUT_REASON))

        val fullSync = PeriodicSyncWorkResult.FullSync(SyncOutcome.Error("secret server response"))
            .toWorkData()
        assertEquals(PeriodicSyncWorker.STATUS_FULL_SYNC, fullSync.getString(PeriodicSyncWorker.OUTPUT_STATUS))
        assertEquals("Error", fullSync.getString(PeriodicSyncWorker.OUTPUT_SYNC_OUTCOME))
        assertEquals(null, fullSync.getString(PeriodicSyncWorker.OUTPUT_REASON))
    }
}
