package com.keepasskey.app.sync

import com.keepasskey.sync.engine.SyncCacheEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SyncFailureSignal] 决策单测（ISSUE-P3-298 ④）：
 * 引擎级失败事件与同步周期结果的「发不发通知」口径。
 */
class SyncFailureSignalTest {

    @Test
    fun `六事件中仅两类读写失败触发通知`() {
        assertTrue(
            SyncFailureSignal.isEngineFailure(
                SyncCacheEvent.CouldntSaveToRemote(remotePath = "/vault.kdbx", cause = null)
            )
        )
        assertTrue(
            SyncFailureSignal.isEngineFailure(
                SyncCacheEvent.CouldntOpenFromRemote(remotePath = "/vault.kdbx", cause = null)
            )
        )
        // 其余四类是正常同步足迹，不得触发失败通知
        assertFalse(
            SyncFailureSignal.isEngineFailure(
                SyncCacheEvent.UpdatedCachedFileOnLoad(remotePath = "/vault.kdbx")
            )
        )
        assertFalse(
            SyncFailureSignal.isEngineFailure(
                SyncCacheEvent.UpdatedRemoteFileOnLoad(remotePath = "/vault.kdbx")
            )
        )
        assertFalse(
            SyncFailureSignal.isEngineFailure(
                SyncCacheEvent.OpenedFromLocalDueToConflict(remotePath = "/vault.kdbx")
            )
        )
        assertFalse(
            SyncFailureSignal.isEngineFailure(
                SyncCacheEvent.LoadedFromRemoteInSync(remotePath = "/vault.kdbx")
            )
        )
    }

    @Test
    fun `仅 Error 结果触发通知其余结果撤下`() {
        assertTrue(SyncFailureSignal.shouldNotifyOutcome(SyncOutcome.Error("同步失败")))
        // 冲突有前台冲突界面承接；离线 / 成功 / 绑定不符各有既有承接面
        assertFalse(SyncFailureSignal.shouldNotifyOutcome(SyncOutcome.ConflictNeedsUser(emptyList())))
        assertFalse(SyncFailureSignal.shouldNotifyOutcome(SyncOutcome.Offline))
        assertFalse(SyncFailureSignal.shouldNotifyOutcome(SyncOutcome.UpToDate))
        assertFalse(SyncFailureSignal.shouldNotifyOutcome(SyncOutcome.UploadedLocal))
        assertFalse(SyncFailureSignal.shouldNotifyOutcome(SyncOutcome.MergedAndUploaded))
        assertFalse(SyncFailureSignal.shouldNotifyOutcome(SyncOutcome.VaultBindingMismatch("/vault.kdbx")))
    }
}
