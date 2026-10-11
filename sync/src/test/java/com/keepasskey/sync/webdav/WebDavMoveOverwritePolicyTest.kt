package com.keepasskey.sync.webdav

import com.keepasskey.sync.model.RemoteFileMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P2-572：WebDAV 409/423 兜底 DELETE 前置元数据重探守卫的纯 JVM 判定单测。
 *
 * 四分支：ETag 相同（放行）/ 漂移（禁 DELETE，携当前远端 ETag）/ 缺失（放行，无 ETag 服务器）/
 * 探测失败（fail-closed）；另锁「无预期基线不判漂移」与弱 ETag 原形态比较两条边界。
 */
class WebDavMoveOverwritePolicyTest {

    private fun meta(etag: String) = RemoteFileMetadata(
        path = "vault.kdbx",
        etag = etag,
        contentLength = 10L,
        lastModifiedMillis = 0L
    )

    @Test
    fun `兜底守卫分支1 重探ETag与预期相同 放行DELETE重试`() {
        assertEquals(
            OverwriteFallbackGuard.Proceed,
            WebDavMoveOverwritePolicy.fallbackGuard(currentMeta = meta("etag-7"), expectedEtag = "etag-7")
        )
    }

    @Test
    fun `兜底守卫分支2 重探ETag漂移 禁DELETE并携当前远端ETag`() {
        when (val verdict = WebDavMoveOverwritePolicy.fallbackGuard(currentMeta = meta("etag-9"), expectedEtag = "etag-7")) {
            is OverwriteFallbackGuard.Drifted ->
                assertEquals("ConflictError 须携当前远端 ETag（他端版本）", "etag-9", verdict.currentRemoteEtag)
            else -> throw AssertionError("漂移必须禁 DELETE: $verdict")
        }
    }

    @Test
    fun `兜底守卫分支3 重探ETag缺失即无ETag服务器 放行DELETE重试`() {
        assertEquals(
            OverwriteFallbackGuard.Proceed,
            WebDavMoveOverwritePolicy.fallbackGuard(currentMeta = meta(""), expectedEtag = "etag-7")
        )
    }

    @Test
    fun `兜底守卫分支4 重探失败 failClosed`() {
        assertEquals(
            OverwriteFallbackGuard.ProbeFailed,
            WebDavMoveOverwritePolicy.fallbackGuard(currentMeta = null, expectedEtag = "etag-7")
        )
    }

    @Test
    fun `兜底守卫 无预期基线时不判漂移 放行既有兜底`() {
        // expectedEtag 空白 = 本就是无预条件的覆盖上传，无「可失效的锁」可护
        assertEquals(
            OverwriteFallbackGuard.Proceed,
            WebDavMoveOverwritePolicy.fallbackGuard(currentMeta = meta("etag-9"), expectedEtag = null)
        )
        assertEquals(
            OverwriteFallbackGuard.Proceed,
            WebDavMoveOverwritePolicy.fallbackGuard(currentMeta = meta("etag-9"), expectedEtag = "  ")
        )
    }

    @Test
    fun `兜底守卫 弱ETag按原形态比较 弱漂移判漂移弱相同放行`() {
        // ISSUE-P1-275：cleanEtag 保留 W/ 标记——弱形态两侧一致化后逐字比较
        when (val drifted = WebDavMoveOverwritePolicy.fallbackGuard(currentMeta = meta("W/weak-2"), expectedEtag = "W/weak-1")) {
            is OverwriteFallbackGuard.Drifted ->
                assertEquals("弱漂移必须携当前远端 ETag", "W/weak-2", drifted.currentRemoteEtag)
            else -> throw AssertionError("弱漂移必须禁 DELETE: $drifted")
        }
        assertEquals(
            OverwriteFallbackGuard.Proceed,
            WebDavMoveOverwritePolicy.fallbackGuard(currentMeta = meta("W/weak-1"), expectedEtag = "W/weak-1")
        )
    }

    @Test
    fun `漂移谓词 任意一侧空白均不判漂移`() {
        assertFalse(WebDavMoveOverwritePolicy.isOverwriteDrift(currentRemoteEtag = null, expectedEtag = "e1"))
        assertFalse(WebDavMoveOverwritePolicy.isOverwriteDrift(currentRemoteEtag = "", expectedEtag = "e1"))
        assertFalse(WebDavMoveOverwritePolicy.isOverwriteDrift(currentRemoteEtag = "e1", expectedEtag = null))
        assertFalse(WebDavMoveOverwritePolicy.isOverwriteDrift(currentRemoteEtag = "e1", expectedEtag = ""))
        assertTrue(WebDavMoveOverwritePolicy.isOverwriteDrift(currentRemoteEtag = "e2", expectedEtag = "e1"))
        assertFalse(WebDavMoveOverwritePolicy.isOverwriteDrift(currentRemoteEtag = "e1", expectedEtag = "e1"))
    }

    @Test
    fun `漂移文案与412区分且携带实际状态码`() {
        assertTrue(WebDavMoveOverwritePolicy.overwriteDriftMessage(409).contains("(HTTP 409)"))
        assertTrue(WebDavMoveOverwritePolicy.overwriteDriftMessage(423).contains("(HTTP 423)"))
        assertFalse(
            "409/423 漂移文案不得冒用 412 字样",
            WebDavMoveOverwritePolicy.overwriteDriftMessage(409).contains("HTTP 412")
        )
    }
}
