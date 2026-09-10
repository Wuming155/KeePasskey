package com.keepasskey.database.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ISSUE-P3-26 回归测试：删除滚动备份 `.bak` 后的目录项 fsync（目录项变更路径⑤）。
 *
 * 缺陷：ISSUE-P3-13 已把四条落盘路径（.bak copy 后、原子 move 后、降级 rename 后、
 * 降级 copy 覆盖后）统一经可注入的 [DirectorySync] 触达，但 `deleteBackup` 删除 `.bak`
 * 后未执行目录 fsync——unlink 与 rename/copy 同属「目录项变更」，不固化则该删除在断电时
 * 可能尚未落盘，**已清理的旧密文快照会重新出现**（旧口令仍可解开）。
 *
 * 断言范式沿用 ISSUE-P3-13：注入记录型替身 [RecordingDirectorySync]（见
 * `DirectorySyncTestDoubles.kt`），在无法真实执行目录通道的 Windows 宿主上也能量化核验
 * 「删除路径确实触达目录同步钩子」；同时以诚实性断言锁死「无目录项变更不得触达钩子」
 * 与「删除失败 / 目录 fsync 降级或异常均不得阻断、不得抛出」。
 */
class AtomicFileWriterBackupDeletionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** 造出「稳定版本 + 已存在的滚动备份」现场，返回 (目标文件, 备份文件)。 */
    private fun vaultWithBackup(): Pair<File, File> {
        val target = File(tempFolder.root, "vault.kdbx")
        target.writeBytes("stable".toByteArray())
        val bak = File(tempFolder.root, "vault.kdbx.bak")
        bak.writeBytes("old-stable".toByteArray())
        return target to bak
    }

    @Test
    fun testDeleteBackupInvokesDirectorySyncHook() {
        val (target, bak) = vaultWithBackup()
        val dirSync = RecordingDirectorySync()

        val deleted = AtomicFileWriter.deleteBackup(target, dirSync)

        assertTrue("删除成功必须返回 true", deleted)
        assertFalse("滚动备份必须已被删除", bak.exists())
        // ISSUE-P3-26 验收标准 1 & 2：unlink 后必须恰好触达一次目录 fsync 钩子，且落在父目录
        assertEquals("删除 .bak 后必须恰好触达一次目录 fsync 钩子", 1, dirSync.syncedDirectories.size)
        assertEquals(tempFolder.root.absolutePath, dirSync.syncedDirectories.single().absolutePath)
    }

    @Test
    fun testDeleteAbsentBackupDoesNotInvokeDirectorySyncHook() {
        // 诚实性断言：没有发生任何目录项变更就不得触达钩子（不伪报已同步）
        val target = File(tempFolder.root, "vault.kdbx")
        val dirSync = RecordingDirectorySync()

        val deleted = AtomicFileWriter.deleteBackup(target, dirSync)

        assertTrue("备份本就不存在视为删除成功", deleted)
        assertTrue("无目录项变更时不得触达目录 fsync 钩子", dirSync.syncedDirectories.isEmpty())
    }

    @Test
    fun testDeleteBackupFailureDoesNotThrowNorInvokeHook() {
        // .bak 占位为非空目录 → File.delete() 在 Windows/Linux 上均恒失败（跨平台确定性）
        val target = File(tempFolder.root, "vault.kdbx")
        val bakDir = File(tempFolder.root, "vault.kdbx.bak")
        assertTrue(bakDir.mkdirs())
        File(bakDir, "blocked").writeBytes(byteArrayOf(1))
        val dirSync = RecordingDirectorySync()

        val deleted = AtomicFileWriter.deleteBackup(target, dirSync)

        assertFalse("删除失败必须返回 false 且绝不抛出", deleted)
        assertTrue("删除失败（无目录项变更）不得触达目录 fsync 钩子", dirSync.syncedDirectories.isEmpty())
    }

    @Test
    fun testDegradedDirectorySyncNeverBlocksBackupDeletion() {
        // ISSUE-P3-26 验收标准 3：Windows 等平台目录通道必然降级，删除语义不得改变
        val (target, bak) = vaultWithBackup()
        val dirSync = RecordingDirectorySync(DirectorySyncOutcome.DEGRADED)

        val deleted = AtomicFileWriter.deleteBackup(target, dirSync)

        assertTrue("目录 fsync 降级不得改变删除结果", deleted)
        assertFalse(bak.exists())
        assertEquals("降级路径同样要触达钩子（否则无从探测降级）", 1, dirSync.outcomes.size)
    }

    @Test
    fun testThrowingDirectorySyncNeverBlocksBackupDeletion() {
        // [DirectorySync] 契约要求「禁止抛异常」；此处验证最后一道防御：
        // 实现若违反契约，删除结果与返回值也不得受影响（只告警）
        val (target, bak) = vaultWithBackup()
        var invoked = 0
        val throwing = DirectorySync {
            invoked++
            throw IllegalStateException("模拟目录通道异常")
        }

        val deleted = AtomicFileWriter.deleteBackup(target, throwing)

        assertTrue("目录 fsync 异常不得阻断删除结果", deleted)
        assertEquals("钩子必须被调用到（异常发生在调用内部）", 1, invoked)
        assertFalse(bak.exists())
    }
}
