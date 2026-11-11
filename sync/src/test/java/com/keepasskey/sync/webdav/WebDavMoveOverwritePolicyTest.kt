package com.keepasskey.sync.webdav

import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebDAV 409/423「拒绝覆盖」裁决的纯 JVM 判定单测（ISSUE-P2-572 引入，ENG-20261028-20 收口）。
 *
 * 四分支：ETag 相同（远端未变 ⇒ 协议错误 + 保留远端）/ 漂移（ConflictError，携当前远端 ETag）/
 * 缺失（无 ETag 服务器，同「远端未变」）/ 探测失败（fail-closed）；
 * 另锁「无预期基线不判漂移」「弱 ETag 原形态比较」两条边界，以及最终错误码与文案映射。
 */
class WebDavMoveOverwritePolicyTest {

    private fun meta(etag: String) = RemoteFileMetadata(
        path = "vault.kdbx",
        etag = etag,
        contentLength = 10L,
        lastModifiedMillis = 0L
    )

    @Test
    fun `覆盖被拒裁决分支1 重探ETag与预期相同 判为服务器不支持覆盖`() {
        assertEquals(
            OverwriteRefusalGuard.RemoteUnchanged,
            WebDavMoveOverwritePolicy.refusalGuard(currentMeta = meta("etag-7"), expectedEtag = "etag-7")
        )
    }

    @Test
    fun `覆盖被拒裁决分支2 重探ETag漂移 转冲突并携当前远端ETag`() {
        when (
            val verdict = WebDavMoveOverwritePolicy.refusalGuard(
                currentMeta = meta("etag-9"),
                expectedEtag = "etag-7"
            )
        ) {
            is OverwriteRefusalGuard.Drifted ->
                assertEquals("ConflictError 须携当前远端 ETag（他端版本）", "etag-9", verdict.currentRemoteEtag)
            else -> throw AssertionError("漂移必须转冲突而非直接报错: $verdict")
        }
    }

    @Test
    fun `覆盖被拒裁决分支3 重探ETag缺失即无ETag服务器 判为服务器不支持覆盖`() {
        assertEquals(
            OverwriteRefusalGuard.RemoteUnchanged,
            WebDavMoveOverwritePolicy.refusalGuard(currentMeta = meta(""), expectedEtag = "etag-7")
        )
    }

    @Test
    fun `覆盖被拒裁决分支4 重探失败 failClosed`() {
        assertEquals(
            OverwriteRefusalGuard.ProbeFailed,
            WebDavMoveOverwritePolicy.refusalGuard(currentMeta = null, expectedEtag = "etag-7")
        )
    }

    @Test
    fun `覆盖被拒裁决 无预期基线时不判漂移`() {
        // expectedEtag 空白 = 本就是无预条件的覆盖上传，无「可失效的锁」可护
        assertEquals(
            OverwriteRefusalGuard.RemoteUnchanged,
            WebDavMoveOverwritePolicy.refusalGuard(currentMeta = meta("etag-9"), expectedEtag = null)
        )
        assertEquals(
            OverwriteRefusalGuard.RemoteUnchanged,
            WebDavMoveOverwritePolicy.refusalGuard(currentMeta = meta("etag-9"), expectedEtag = "  ")
        )
    }

    @Test
    fun `覆盖被拒裁决 弱ETag按原形态比较 弱漂移判漂移弱相同不判`() {
        // ISSUE-P1-275：cleanEtag 保留 W/ 标记——弱形态两侧一致化后逐字比较
        when (
            val drifted = WebDavMoveOverwritePolicy.refusalGuard(
                currentMeta = meta("W/weak-2"),
                expectedEtag = "W/weak-1"
            )
        ) {
            is OverwriteRefusalGuard.Drifted ->
                assertEquals("弱漂移必须携当前远端 ETag", "W/weak-2", drifted.currentRemoteEtag)
            else -> throw AssertionError("弱漂移必须转冲突: $drifted")
        }
        assertEquals(
            OverwriteRefusalGuard.RemoteUnchanged,
            WebDavMoveOverwritePolicy.refusalGuard(currentMeta = meta("W/weak-1"), expectedEtag = "W/weak-1")
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

    @Test
    fun `拒绝覆盖错误携服务器实际状态码且文案声明未删除远端`() {
        val err = WebDavMoveOverwritePolicy.protocolErrorFor(overwriteRefusedCode = 423, lastMoveCode = 423)
        assertEquals("错误码必须是服务器返回的 423", 423, err.statusCode)
        val message = err.message.orEmpty()
        assertTrue("文案须说明远端旧文件被保留（ENG-20261028-20）: $message", message.contains("远端旧文件未删除"))
        assertFalse("文案不得再声称已 DELETE 目标: $message", message.contains("已 DELETE 目标"))
    }

    @Test
    fun `非覆盖拒绝的MOVE失败仍归通用文案与500码`() {
        val err = WebDavMoveOverwritePolicy.protocolErrorFor(overwriteRefusedCode = null, lastMoveCode = 502)
        assertEquals(500, err.statusCode)
        assertTrue(err.message.orEmpty().contains("(HTTP 502)"))
        assertFalse(err.message.orEmpty().contains("拒绝覆盖"))
    }

    @Test
    fun `兼容写入准入 仅开关开启且双方ETag可核验并一致`() {
        assertTrue(WebDavMoveOverwritePolicy.canDeleteBeforeMove(true, "e1", "e1"))
        assertFalse("开关关闭不得删", WebDavMoveOverwritePolicy.canDeleteBeforeMove(false, "e1", "e1"))
        assertFalse("无预期基线不得删", WebDavMoveOverwritePolicy.canDeleteBeforeMove(true, null, "e1"))
        assertFalse("空白预期基线不得删", WebDavMoveOverwritePolicy.canDeleteBeforeMove(true, "  ", "e1"))
        assertFalse("无 ETag 服务器不得删", WebDavMoveOverwritePolicy.canDeleteBeforeMove(true, "e1", ""))
        assertFalse("重探 ETag 为空不得删", WebDavMoveOverwritePolicy.canDeleteBeforeMove(true, "e1", null))
        assertFalse("ETag 不一致不得删", WebDavMoveOverwritePolicy.canDeleteBeforeMove(true, "e1", "e2"))
        assertTrue(
            "弱 ETag 一致形态按逐字比较",
            WebDavMoveOverwritePolicy.canDeleteBeforeMove(true, "W/w1", "W/w1")
        )
    }

    @Test
    fun `兼容写入已删除旧文件时文案不得谎称旧文件仍在`() {
        val err = WebDavMoveOverwritePolicy.protocolErrorFor(
            overwriteRefusedCode = 409, lastMoveCode = 409, remoteDeleted = true
        )
        assertTrue(err.message.orEmpty().contains("云端旧文件已按兼容策略删除"))
        assertFalse(err.message.orEmpty().contains("远端旧文件未删除"))
    }
}
