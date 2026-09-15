package com.keepasskey.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 「易失缓存清理面」的**设备侧（instrumented）**回归（ISSUE-P2-42 三项之第 2 项 +
 * ISSUE-P3-116 / §68 的退出清理面）。
 *
 * ## 本用例覆盖什么、不覆盖什么（如实声明）
 *
 * - **覆盖**：两个清理面（`cacheDir/attachments` 附件解密明文、`cacheDir/sync` KDBX 密文快照）
 *   在**真实 Android 文件系统**上确实被清空——走生产实现
 *   [MainApplication.purgeVolatileCachesBeforeExit]，而不是测试自建的替身。
 * - **不覆盖**：进程级**冷启动时序**（`Application.onCreate` 早于任何会话打开）。
 *   instrumented 用例运行在**已被创建**的应用进程内，无法自我重启进程；
 *   该时序由 ADB E2E 实测（`force-stop` → 冷启动 → `run-as ls cache/attachments` 为空）
 *   与同模块 JVM 接线守卫 `ColdStartAttachmentPurgeWiringTest` 共同覆盖（见 `RESOLVED_LOG.md` §71）。
 *
 * 之所以必须补这一层：`FileBinaryStore` / `SyncCacheEvictor` 的清理失败**只落脱敏日志、不外抛**，
 * 故「调用返回」不等于「目录已空」——必须断言**目录内容**而非返回值（本用例两者都断言）。
 */
@RunWith(AndroidJUnit4::class)
class PurgeVolatileCachesDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app: MainApplication
        get() = context.applicationContext as MainApplication

    private val attachmentsDir = File(context.cacheDir, "attachments")
    private val syncDir = File(context.cacheDir, "sync")

    @Test
    fun `退出前清理覆盖附件明文与同步密文快照两个面`() {
        attachmentsDir.mkdirs()
        syncDir.mkdirs()
        val attachmentProbe = File(attachmentsDir, "probe-attachment.bin")
        val syncProbe = File(syncDir, "probe-sync.kdbx")
        attachmentProbe.writeBytes(byteArrayOf(1, 2, 3, 4))
        syncProbe.writeBytes(byteArrayOf(5, 6, 7, 8))
        assertTrue("前置：探针必须真的落盘", attachmentProbe.exists() && syncProbe.exists())

        val bothCleared = app.purgeVolatileCachesBeforeExit()

        assertFalse("附件明文面必须被清空", attachmentProbe.exists())
        assertFalse("同步密文快照面必须被清空", syncProbe.exists())
        assertTrue("两个清理面均应成功（返回值仅为日志/观察用，仍须断言）", bothCleared)
    }

    @Test
    fun `清理实现可重复调用且不抛（冷启动与退出共用同一收敛路径）`() {
        attachmentsDir.mkdirs()
        val probe = File(attachmentsDir, "probe-idempotent.bin")
        probe.writeBytes(byteArrayOf(9))

        // 冷启动（MainApplication.onCreate → fileBinaryStore.clear()）与退出
        // （purgeVolatileCachesBeforeExit）共用同一底层实现，故此处验证「同一实现幂等且不外抛」
        app.purgeVolatileCachesBeforeExit()
        assertFalse(probe.exists())
        // 目录不存在时再次清理不得抛异常（冷启动常见状态）
        app.purgeVolatileCachesBeforeExit()
    }
}
