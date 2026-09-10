package com.keepasskey.database.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * DirectorySync 抽象与生产实现（ISSUE-P3-13）的平台语义回归测试。
 *
 * 背景：ISSUE-P2-05 的目录项 fsync 在 Windows 宿主上必然降级为告警，
 * 即「目录 fsync 真实执行」这条崩溃安全路径此前在 Windows 上从未被断言。
 * 本测试把平台差异固化为可核验断言，避免「仅靠代码审查背书」：
 * - POSIX（Linux / macOS / CI Linux runner）宿主：默认实现必须返回
 *   [DirectorySyncOutcome.SYNCED]，即**不得**走降级分支（ISSUE-P3-13 验收标准 1）；
 * - Windows 宿主：目录通道不可用（`AccessDeniedException`）属已知平台事实，
 *   断言其降级结果且**不抛异常**（验收标准 3「降级不阻断」的前提）。
 *
 * 平台差异说明：本测试**不使用 `Assume` 跳过**——Windows 上的降级本身就是被断言的
 * 真实运行时事实，无需跳过；因此数据库模块测试在任何宿主上都全量执行。
 * 末例进一步经唯一消费方 [AtomicFileWriter] 真实落盘，实证「降级不阻断」。
 */
class DirectorySyncTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Windows 判定：仅 Windows 关闭目录通道，其余宿主一律按 POSIX 语义要求真实 fsync。 */
    private val isWindowsHost = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    @Test
    fun testDefaultImplementationIsProductionPosixImpl() {
        // 默认实现必须指向生产实现，防止有人把测试假实现误挂到默认值上
        assertSame(PosixDirectorySync, DirectorySync.default)
    }

    @Test
    fun testSyncOutcomeMatchesHostDirectoryChannelSupport() {
        val outcome = DirectorySync.default.sync(tempFolder.root)

        if (isWindowsHost) {
            assertEquals(
                "Windows 宿主目录 FileChannel 不可用属已知平台事实，必须降级而非抛异常",
                DirectorySyncOutcome.DEGRADED,
                outcome
            )
        } else {
            assertEquals(
                "POSIX 宿主（Linux/macOS/CI runner）上目录 fsync 必须真实执行，不得降级",
                DirectorySyncOutcome.SYNCED,
                outcome
            )
        }
    }

    @Test
    fun testSyncOfMissingDirectoryDegradesWithoutThrowing() {
        // 实现契约「禁止抛异常」：目录不存在时同样降级返回，绝不把异常抛给写盘主流程
        val missing = File(tempFolder.root, "not-created-dir")

        assertEquals(DirectorySyncOutcome.DEGRADED, DirectorySync.default.sync(missing))
    }

    @Test
    fun testDefaultImplementationNeverBlocksAtomicWriteOnThisHost() {
        // 生产默认实现经唯一消费方 AtomicFileWriter 真实落盘：
        // 本环境（Windows）目录通道必然不可用，但写盘结果必须完全不受影响（验收标准 3）。
        val target = File(tempFolder.root, "vault.kdbx")
        target.writeBytes("old-stable".toByteArray())

        AtomicFileWriter.writeAtomic(target, createBackup = true, directorySync = DirectorySync.default) {
            it.write("new-version".toByteArray())
        }

        assertEquals("目录 fsync 降级不得阻断写盘", "new-version", String(target.readBytes()))
        assertEquals("old-stable", String(File(tempFolder.root, "vault.kdbx.bak").readBytes()))
        assertFalse(File(tempFolder.root, "vault.kdbx.tmp").exists())
    }
}
