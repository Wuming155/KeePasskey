package com.keepasskey.app.sync

import com.keepasskey.app.R
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.sync.model.SyncException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远端失败归类的单测（ISSUE-P2-548）：口径**只有一处**（[remoteFailureOutcome]），
 * 首传探测 / openRemote 降级 / 上传失败三条路径共用，杜绝「同一起因不同口径」。
 *
 * 整改前只有首传探测（`ISSUE-P2-402`）做了三分法，`openRemote` 的降级结果把
 * 401/403 与「真断网」混成同一个结果 ⇒ UI 显示「离线，已保留本地副本」。
 */
class SyncRemoteFailureOutcomeTest {

    /** 纯 JVM 假通道：把资源 ID 与实参原样呈现，便于断言「用了哪条文案、带了什么参数」。 */
    private val strings = StringsProvider { id, args -> "res=$id|args=${args.joinToString(",")}" }

    @Test
    fun `真断网才判离线`() {
        assertEquals(
            SyncRemoteFailureKind.OFFLINE,
            classifyRemoteFailure(SyncException.NetworkError("Network down"))
        )
    }

    @Test
    fun `鉴权失败单独归类`() {
        assertEquals(
            SyncRemoteFailureKind.AUTH,
            classifyRemoteFailure(SyncException.AuthenticationError("401"))
        )
    }

    @Test
    fun `协议与服务端失败归为被拒绝`() {
        assertEquals(
            SyncRemoteFailureKind.REJECTED,
            classifyRemoteFailure(SyncException.ProtocolError(503, "Service Unavailable"))
        )
        assertEquals(
            SyncRemoteFailureKind.REJECTED,
            classifyRemoteFailure(SyncException.CacheCorruptedError("cache gone"))
        )
    }

    @Test
    fun `未知起因不得落到离线口径`() {
        assertEquals("起因缺失时按『被拒绝』处置，绝不静默降级成离线", SyncRemoteFailureKind.REJECTED, classifyRemoteFailure(null))
        assertEquals(SyncRemoteFailureKind.REJECTED, classifyRemoteFailure(IllegalStateException("boom")))
    }

    @Test
    fun `用户可见标识只取状态码或类名绝不取 message`() {
        assertEquals("503", remoteFailureDescriptor(SyncException.ProtocolError(503, "Service Unavailable")))
        assertEquals("AuthenticationError", remoteFailureDescriptor(SyncException.AuthenticationError("401 secret")))
        assertEquals("Unknown", remoteFailureDescriptor(null))
        val descriptor = remoteFailureDescriptor(SyncException.ProtocolError(403, "host=evil.example token=abc"))
        assertTrue("标识不得携带服务器可控串：$descriptor", descriptor == "403")
    }

    @Test
    fun `结论映射与既有文案一致`() {
        assertTrue(
            strings.remoteFailureOutcome(SyncException.NetworkError("down")) is SyncOutcome.Offline
        )

        val auth = strings.remoteFailureOutcome(SyncException.AuthenticationError("401"))
        assertTrue(auth is SyncOutcome.Error)
        assertEquals(
            "res=${R.string.sync_error_auth_failed}|args=",
            (auth as SyncOutcome.Error).message
        )

        val rejected = strings.remoteFailureOutcome(SyncException.ProtocolError(503, "Service Unavailable"))
        assertTrue(rejected is SyncOutcome.Error)
        assertEquals(
            "res=${R.string.sync_error_remote_rejected}|args=503",
            (rejected as SyncOutcome.Error).message
        )
    }
}
