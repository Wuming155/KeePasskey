package com.keepasskey.database.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * AtomicFileWriter 降级路径安全回归测试（审计 P2-7）。
 *
 * 旧缺陷：Files.move 抛出异常后的降级分支先 targetFile.delete() 再 renameTo——
 * 一旦 delete 成功而 rename 失败，原文件彻底丢失。
 * 修复后降级遵循安全铁律：先直接标准 rename（POSIX rename(2) 原子替换已存在
 * 目标，无需删除原文件）；rename 失败时仅在 .bak 备份兜底下 copy 覆盖，
 * 否则宁可失败也绝不破坏原文件；备份与降级失败均记录日志。
 *
 * ISSUE-P3-13：目录项 fsync 经 [DirectorySync] 抽象注入，本测试用记录型假实现
 * 断言四条落盘路径（① .bak 滚动备份 copy 后、② 主路径原子 move 后、
 * ③ 降级标准 rename 成功后、④ 降级 copy 覆盖后）**均触达目录 fsync 钩子**，
 * 从而在无法真实执行目录通道的 Windows 宿主上也能核验该崩溃安全路径。
 * ISSUE-P3-26 追加的第五条路径（⑤ 删除滚动备份 .bak 后，unlink 同属目录项变更）由
 * 同包测试类 `AtomicFileWriterBackupDeletionTest` 覆盖；本类与其共用
 * `DirectorySyncTestDoubles.kt` 中的记录型替身，使两个测试文件均保持在行数阈值内。
 * 说明：本测试类不存在 `Assume` 跳过用例；测试基线中「Windows 无 POSIX 权限视图」
 * 的跳过项位于 sync 模块 `SyncCacheTest`，与本模块无关。
 */
class AtomicFileWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Windows 判定（ISSUE-P3-13）：Windows 宿主目录通道不可用，目录 fsync 必然降级。 */
    private val isWindowsHost = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private fun read(file: File): String = String(file.readBytes())

    @Test
    fun testFirstWriteCreatesTargetFile() {
        val target = File(tempFolder.root, "vault.kdbx")

        AtomicFileWriter.writeAtomic(target) { it.write("first".toByteArray()) }

        assertEquals("first", read(target))
        assertFalse("临时文件不应残留", File(tempFolder.root, "vault.kdbx.tmp").exists())
    }

    @Test
    fun testOverwriteKeepsBakBackupAndNoTmpLeftover() {
        val target = File(tempFolder.root, "vault.kdbx")
        target.writeBytes("old-stable".toByteArray())

        AtomicFileWriter.writeAtomic(target) { it.write("new-version".toByteArray()) }

        assertEquals("new-version", read(target))
        assertEquals("old-stable", read(File(tempFolder.root, "vault.kdbx.bak")))
        assertFalse(File(tempFolder.root, "vault.kdbx.tmp").exists())
    }

    @Test
    fun testFallbackWithoutBackupRefusesOverwriteAndKeepsOriginal() {
        val target = File(tempFolder.root, "vault.kdbx")
        target.writeBytes("precious-original".toByteArray())
        // 临时文件不存在：标准 rename 必然失败，模拟降级分支 rename 失败场景
        val tmp = File(tempFolder.root, "vault.kdbx.tmp")

        assertThrows(IOException::class.java) {
            AtomicFileWriter.fallbackReplace(tmp, target, backupAvailable = false, cause = IOException("atomic move failed"))
        }

        // 核心回归断言：原文件绝不允许被无保护删除
        // （旧实现此处先 delete 原文件、rename 再失败，原文件已彻底丢失）
        assertEquals("precious-original", read(target))
        assertTrue(target.exists())
    }

    @Test
    fun testFallbackWithBackupReplacesWithNewContent() {
        val target = File(tempFolder.root, "vault.kdbx")
        target.writeBytes("old-stable".toByteArray())
        val tmp = File(tempFolder.root, "vault.kdbx.tmp")
        tmp.writeBytes("fresh-new".toByteArray())
        val dirSync = RecordingDirectorySync()

        AtomicFileWriter.fallbackReplace(
            tmp,
            target,
            backupAvailable = true,
            cause = IOException("atomic move failed"),
            directorySync = dirSync
        )

        // 无论经标准 rename（POSIX）还是 copy 覆盖（其他平台语义），结果一致
        assertEquals("fresh-new", read(target))
        assertFalse("降级替换成功后临时文件不应残留", tmp.exists())
        // ISSUE-P3-13：既有目标下无论实际走哪条降级分支，目录 fsync 钩子都必须恰好触达一次
        assertEquals("降级替换成功后必须恰好触达一次目录 fsync 钩子", 1, dirSync.syncedDirectories.size)
    }

    @Test
    fun testFallbackCopyFailureKeepsOriginal() {
        val target = File(tempFolder.root, "vault.kdbx")
        target.writeBytes("precious-original".toByteArray())
        // 临时文件不存在 → rename 与 copy 均失败
        val tmp = File(tempFolder.root, "vault.kdbx.tmp")

        assertThrows(IOException::class.java) {
            AtomicFileWriter.fallbackReplace(tmp, target, backupAvailable = true, cause = IOException("atomic move failed"))
        }

        assertEquals("precious-original", read(target))
    }

    @Test
    fun testBackupFailureDoesNotBlockMainFlow() {
        val target = File(tempFolder.root, "vault.kdbx")
        target.writeBytes("old-stable".toByteArray())
        // 将 .bak 占位为非空目录：Files.copy(..., REPLACE_EXISTING) 必然失败
        // → 备份失败被记录（logger.warning）且主流程继续，原子 move 不依赖备份
        val bakDir = File(tempFolder.root, "vault.kdbx.bak")
        assertTrue(bakDir.mkdirs())
        File(bakDir, "blocked").writeBytes(byteArrayOf(1))

        AtomicFileWriter.writeAtomic(target) { it.write("saved-anyway".toByteArray()) }

        assertEquals("saved-anyway", read(target))
        assertFalse(File(tempFolder.root, "vault.kdbx.tmp").exists())
    }

    // ===== ISSUE-P2-05：异常/中断模拟与降级路径 fsync 回归 =====

    @Test
    fun testWriterFailureOnFirstWriteLeavesNoEmptyTarget() {
        val target = File(tempFolder.root, "vault.kdbx")

        assertThrows(IOException::class.java) {
            AtomicFileWriter.writeAtomic(target) { throw IOException("模拟写入中断（断电/崩溃）") }
        }

        // 崩溃安全核心断言：中断后绝不残留空目标文件，也不残留 .tmp 垃圾
        assertFalse("中断后不得残留空目标文件", target.exists())
        assertFalse("中断后不得残留临时文件", File(tempFolder.root, "vault.kdbx.tmp").exists())
    }

    @Test
    fun testWriterFailureKeepsOriginalContentAndCleansTmp() {
        val target = File(tempFolder.root, "vault.kdbx")
        target.writeBytes("stable-original".toByteArray())

        assertThrows(IOException::class.java) {
            AtomicFileWriter.writeAtomic(target) { os ->
                os.write("partial-write".toByteArray())
                throw IOException("模拟写入中途失败")
            }
        }

        assertEquals("写入失败后原文件内容必须保持稳定版本", "stable-original", read(target))
        assertFalse("异常退出后不得残留临时文件", File(tempFolder.root, "vault.kdbx.tmp").exists())
    }

    @Test
    fun testWriteAtomicWithoutBackupDoesNotCreateBak() {
        val target = File(tempFolder.root, "vault.kdbx")
        target.writeBytes("old-stable".toByteArray())

        AtomicFileWriter.writeAtomic(target, createBackup = false) { it.write("new-version".toByteArray()) }

        assertEquals("new-version", read(target))
        assertFalse("关闭备份偏好时不得生成 .bak", File(tempFolder.root, "vault.kdbx.bak").exists())
        assertFalse(File(tempFolder.root, "vault.kdbx.tmp").exists())
    }

    @Test
    fun testFallbackCopyBranchReplacesAndCleansTmpWithoutEmptyTarget() {
        val target = File(tempFolder.root, "vault.kdbx")
        target.writeBytes("old-stable".toByteArray())
        val tmp = File(tempFolder.root, "vault.kdbx.tmp")
        tmp.writeBytes("fresh-new".toByteArray())

        AtomicFileWriter.fallbackReplace(
            renameFailing(tmp),
            target,
            backupAvailable = true,
            cause = IOException("atomic move failed")
        )

        assertEquals("fresh-new", read(target))
        assertTrue("替换后目标文件不得为空", target.length() > 0)
        assertFalse("降级 copy 成功后临时文件必须清理", tmp.exists())
    }

    // ===== ISSUE-P3-13：目录项 fsync 四条路径的可注入验证（Windows 宿主同样可核验） =====

    @Test
    fun testMainAtomicMovePathInvokesDirectorySyncHook() {
        val target = File(tempFolder.root, "vault.kdbx")
        val dirSync = RecordingDirectorySync()

        AtomicFileWriter.writeAtomic(target, createBackup = true, directorySync = dirSync) {
            it.write("first".toByteArray())
        }

        // 首次写入无稳定版本可滚动备份 → 仅主路径原子 move 后触达钩子②
        assertEquals("主路径原子 move 后必须触达目录 fsync 钩子", 1, dirSync.syncedDirectories.size)
        assertEquals(tempFolder.root.absolutePath, dirSync.syncedDirectories.single().absolutePath)
    }

    @Test
    fun testBackupRollingPathInvokesDirectorySyncHook() {
        val target = File(tempFolder.root, "vault.kdbx")
        target.writeBytes("old-stable".toByteArray())
        val dirSync = RecordingDirectorySync()

        AtomicFileWriter.writeAtomic(target, createBackup = true, directorySync = dirSync) {
            it.write("new-version".toByteArray())
        }

        // 既有稳定版本 + 开启备份 → 钩子①（.bak copy 后）+ 钩子②（原子 move 后）各一次
        assertEquals("备份滚动与原子 move 两条路径都必须触达目录 fsync 钩子", 2, dirSync.syncedDirectories.size)
        assertTrue(dirSync.syncedDirectories.all { it.absolutePath == tempFolder.root.absolutePath })
        assertEquals("old-stable", read(File(tempFolder.root, "vault.kdbx.bak")))
    }

    @Test
    fun testFallbackStandardRenamePathInvokesDirectorySyncHook() {
        // 目标不存在 → 标准 rename 在所有平台必然成功，确定性进入钩子③路径
        val target = File(tempFolder.root, "vault.kdbx")
        val tmp = File(tempFolder.root, "vault.kdbx.tmp")
        tmp.writeBytes("fresh-new".toByteArray())
        val dirSync = RecordingDirectorySync()

        AtomicFileWriter.fallbackReplace(
            tmp,
            target,
            backupAvailable = true,
            cause = IOException("atomic move failed"),
            directorySync = dirSync
        )

        assertEquals("降级标准 rename 成功后必须触达目录 fsync 钩子", 1, dirSync.syncedDirectories.size)
        assertEquals("fresh-new", read(target))
    }

    @Test
    fun testFallbackCopyOverwritePathInvokesDirectorySyncHook() {
        // 确定性进入 Files.copy 降级分支：覆写 renameTo 恒返回 false
        val target = File(tempFolder.root, "vault.kdbx")
        target.writeBytes("old-stable".toByteArray())
        val tmp = File(tempFolder.root, "vault.kdbx.tmp")
        tmp.writeBytes("fresh-new".toByteArray())
        val dirSync = RecordingDirectorySync()

        AtomicFileWriter.fallbackReplace(
            renameFailing(tmp),
            target,
            backupAvailable = true,
            cause = IOException("atomic move failed"),
            directorySync = dirSync
        )

        assertEquals("降级 copy 覆盖后必须触达目录 fsync 钩子", 1, dirSync.syncedDirectories.size)
        assertEquals("fresh-new", read(target))
        assertFalse(tmp.exists())
    }

    @Test
    fun testRefusedUnprotectedOverwriteDoesNotInvokeDirectorySyncHook() {
        // 诚实性断言：拒绝无保护覆盖时没有任何落盘动作，钩子绝不能被触达（不得伪报已同步）
        val target = File(tempFolder.root, "vault.kdbx")
        target.writeBytes("precious-original".toByteArray())
        val tmp = File(tempFolder.root, "vault.kdbx.tmp")
        val dirSync = RecordingDirectorySync()

        assertThrows(IOException::class.java) {
            AtomicFileWriter.fallbackReplace(
                tmp,
                target,
                backupAvailable = false,
                cause = IOException("atomic move failed"),
                directorySync = dirSync
            )
        }

        assertTrue("拒绝覆盖分支不得触达目录 fsync 钩子", dirSync.syncedDirectories.isEmpty())
        assertEquals("precious-original", read(target))
    }

    @Test
    fun testDegradedDirectorySyncNeverBlocksWrite() {
        // 平台降级（Windows 必然发生）时写盘必须照常成功：ISSUE-P3-13 验收标准 3
        val target = File(tempFolder.root, "vault.kdbx")
        target.writeBytes("old-stable".toByteArray())
        val dirSync = RecordingDirectorySync(DirectorySyncOutcome.DEGRADED)

        AtomicFileWriter.writeAtomic(target, createBackup = true, directorySync = dirSync) {
            it.write("saved-anyway".toByteArray())
        }

        assertEquals("目录 fsync 降级不得阻断写盘", "saved-anyway", read(target))
        assertEquals("old-stable", read(File(tempFolder.root, "vault.kdbx.bak")))
        assertEquals("降级路径同样要触达钩子（否则无从探测降级）", 2, dirSync.outcomes.size)
    }

    @Test
    fun testFourPathsDirectorySyncOutcomeOnRealHost() {
        // 委派真实实现的探针覆盖四条路径，核验宿主平台语义：
        // POSIX 宿主四条路径必须全部 SYNCED（验收标准 1 的真实运行时断言）；
        // Windows 宿主必然 DEGRADED，且四条路径的写盘结果均不受影响（验收标准 3）。
        val probe = ProbingDirectorySync()

        // 钩子①+②：既有稳定版本 + 开启备份
        val backedUp = File(tempFolder.root, "with-bak.kdbx")
        backedUp.writeBytes("old-stable".toByteArray())
        AtomicFileWriter.writeAtomic(backedUp, createBackup = true, directorySync = probe) {
            it.write("new-version".toByteArray())
        }

        // 钩子③：目标不存在 → 标准 rename 必然成功
        val renamed = File(tempFolder.root, "renamed.kdbx")
        val renameTmp = File(tempFolder.root, "renamed.kdbx.tmp")
        renameTmp.writeBytes("renamed-content".toByteArray())
        AtomicFileWriter.fallbackReplace(
            renameTmp,
            renamed,
            backupAvailable = true,
            cause = IOException("atomic move failed"),
            directorySync = probe
        )

        // 钩子④：覆写 renameTo 恒 false → 确定性进入 copy 覆盖分支
        val copied = File(tempFolder.root, "copied.kdbx")
        copied.writeBytes("old-stable".toByteArray())
        val copyTmp = File(tempFolder.root, "copied.kdbx.tmp")
        copyTmp.writeBytes("copied-content".toByteArray())
        AtomicFileWriter.fallbackReplace(
            renameFailing(copyTmp),
            copied,
            backupAvailable = true,
            cause = IOException("atomic move failed"),
            directorySync = probe
        )

        assertEquals("四条路径必须全部触达目录 fsync 钩子", 4, probe.outcomes.size)
        val expectedOutcome =
            if (isWindowsHost) DirectorySyncOutcome.DEGRADED else DirectorySyncOutcome.SYNCED
        assertEquals(
            "宿主平台目录通道能力与实测结果不符（Windows 必然降级 / POSIX 必须真实 fsync）",
            listOf(expectedOutcome),
            probe.outcomes.distinct()
        )
        // 无论降级与否，四条路径的写盘结果都必须成功
        assertEquals("new-version", read(backedUp))
        assertEquals("old-stable", read(File(tempFolder.root, "with-bak.kdbx.bak")))
        assertEquals("renamed-content", read(renamed))
        assertEquals("copied-content", read(copied))
    }

    /** 构造标准 rename 恒失败的文件句柄（模拟跨卷 / 目标被占用），确定性进入 copy 降级分支。 */
    private fun renameFailing(file: File): File = object : File(file.path) {
        override fun renameTo(dest: File): Boolean = false
    }
}
