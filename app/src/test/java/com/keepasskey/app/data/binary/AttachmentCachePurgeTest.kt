package com.keepasskey.app.data.binary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * F-13（P1）附件明文缓存清理的失败收敛策略单测。
 *
 * 背景：`cacheDir/attachments` 落盘的是**附件解密后的明文**，清理有两条时机——
 * 会话锁定（`SessionLockObserver`）与进程冷启动（`MainApplication.onCreate`）。
 * 两条路径都**没有**上层兜底：锁定回调抛异常会被会话锁定流程吞掉且无从告警，
 * 冷启动路径抛出则直接阻断应用启动。故清理的失败收敛必须是纯函数可断言的行为：
 *
 * 1. 清理成功 → 不告警、返回 true；
 * 2. 存在删除失败项（返回 false，无异常）→ 告警一次、返回 false；
 * 3. 清理过程抛异常 → **收敛为告警**、返回 false，绝不外抛。
 *
 * 说明：真正的文件删除与 Compose/Context 装配在 JVM 侧不可构造（`SyncCache` 需要真实目录），
 * 故此处只覆盖可判定部分；Android 侧的冷启动时序（Application.onCreate 早于任何会话打开）
 * 由 `ColdStartAttachmentPurgeWiringTest` 以源码接线断言守护。
 */
class AttachmentCachePurgeTest {

    @Test
    fun `清理成功时不告警且返回 true`() {
        var failureCalls = 0

        val cleared = purgeAttachmentCache(purge = { true }, onFailure = { failureCalls++ })

        assertTrue(cleared)
        assertEquals("清理成功不得产生失败告警", 0, failureCalls)
    }

    @Test
    fun `存在删除失败项时告警一次且返回 false`() {
        var failureCalls = 0
        var causeSeen: Throwable? = null

        val cleared = purgeAttachmentCache(
            purge = { false },
            onFailure = { cause ->
                failureCalls++
                causeSeen = cause
            }
        )

        assertFalse(cleared)
        assertEquals(1, failureCalls)
        assertNull("删除失败项（非异常）不得伪造异常对象", causeSeen)
    }

    @Test
    fun `清理抛异常时收敛为告警并返回 false 绝不外抛`() {
        var failureCalls = 0
        var causeSeen: Throwable? = null

        // 不 try/catch：若实现把异常外抛，本用例即失败（这正是契约要禁止的行为）
        val cleared = purgeAttachmentCache(
            purge = { throw IOException("附件缓存目录删除失败") },
            onFailure = { cause ->
                failureCalls++
                causeSeen = cause
            }
        )

        assertFalse(cleared)
        assertEquals(1, failureCalls)
        assertTrue("告警须带出异常类名以便脱敏留痕", causeSeen is IOException)
    }

    @Test
    fun `清理抛 Error 同样收敛不外抛`() {
        var failureCalls = 0

        val cleared = purgeAttachmentCache(
            purge = { throw OutOfMemoryError("模拟清理期内存不足") },
            onFailure = { failureCalls++ }
        )

        assertFalse("Error 亦不得反噬冷启动 / 锁定流程", cleared)
        assertEquals(1, failureCalls)
    }
}
