package com.keepasskey.app.sync

import com.keepasskey.sync.engine.SyncCacheEvent
import com.keepasskey.sync.model.SyncException
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

    /**
     * ISSUE-P2-548 AC②：真·网络不可达（`NetworkError`）是**设计内降级**，不得亮失败通知，
     * 否则每个同步周期都会在断网时闪一次通知。鉴权 / 协议失败仍必须亮出。
     */
    @Test
    fun `网络不可达起因不触发通知鉴权与协议失败仍触发`() {
        assertFalse(
            "真断网属设计内降级，不得亮失败通知",
            SyncFailureSignal.isEngineFailure(
                SyncCacheEvent.CouldntOpenFromRemote(
                    remotePath = "/vault.kdbx",
                    cause = SyncException.NetworkError("Network down")
                )
            )
        )
        assertFalse(
            SyncFailureSignal.isEngineFailure(
                SyncCacheEvent.CouldntSaveToRemote(
                    remotePath = "/vault.kdbx",
                    cause = SyncException.NetworkError("Network down")
                )
            )
        )
        assertTrue(
            "鉴权失败用户必须改凭据才能恢复，必须亮通知",
            SyncFailureSignal.isEngineFailure(
                SyncCacheEvent.CouldntOpenFromRemote(
                    remotePath = "/vault.kdbx",
                    cause = SyncException.AuthenticationError("401")
                )
            )
        )
        assertTrue(
            SyncFailureSignal.isEngineFailure(
                SyncCacheEvent.CouldntSaveToRemote(
                    remotePath = "/vault.kdbx",
                    cause = SyncException.ProtocolError(503, "Service Unavailable")
                )
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
