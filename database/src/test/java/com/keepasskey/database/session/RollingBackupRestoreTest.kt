package com.keepasskey.database.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `ISSUE-P2-521`：从滚动备份（.bak）恢复的宿主判据。
 *
 * 语义契约（AC②）：
 * ① 恢复＝备份字节经「临时文件 → fsync → 原子替换」写回主文件，**不轮换备份**
 *    （绝不把损坏的主文件覆盖成新 .bak）且**不删除备份**（.bak 仍在原处，可再次恢复）；
 * ② 无备份 ⇒ 返回 false 且主文件一字节不动；
 * ③ 主文件缺失而备份在场 ⇒ 恢复重建主文件（断电窗口内主文件被删的边角场景）。
 *
 * 恢复的失败口径（原子替换降级拒绝无保护覆盖等）为 fail-closed：返回 false 且两侧文件
 * 均保持原状，由 [SessionFileWriter] 的语义化告警与 app 层用户可见提示承接。
 */
class RollingBackupRestoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** 备份偏好开启的会话写者（恢复路径不使用该偏好，仅构造注入）。 */
    private fun writer(createBackup: Boolean = true) = SessionFileWriter { createBackup }

    private fun read(file: File): String = String(file.readBytes())

    @Test
    fun testRestoreWritesBakContentAndKeepsBak() {
        val target = File(tempFolder.root, "vault.kdbx")
        val bak = File(tempFolder.root, "vault.kdbx.bak")
        target.writeBytes("corrupted-content".toByteArray())
        bak.writeBytes("last-stable-backup".toByteArray())

        val restored = writer().restoreFromRollingBackup(target)

        assertTrue("存在备份时必须恢复成功", restored)
        assertArrayEquals(
            "主文件必须等于备份字节（上次成功保存的版本）",
            "last-stable-backup".toByteArray(),
            target.readBytes()
        )
        assertTrue("备份必须保留（可再次恢复）", bak.exists())
        assertArrayEquals(
            "备份内容不得被损坏的主文件轮换覆盖",
            "last-stable-backup".toByteArray(),
            bak.readBytes()
        )
        assertFalse("恢复后不得残留临时文件", File(tempFolder.root, "vault.kdbx.tmp").exists())
    }

    @Test
    fun testRestoreWithoutBakReturnsFalseAndLeavesTargetUntouched() {
        val target = File(tempFolder.root, "vault.kdbx")
        target.writeBytes("untouched".toByteArray())

        val restored = writer().restoreFromRollingBackup(target)

        assertFalse("无备份时必须返回 false（不静默成功）", restored)
        assertArrayEquals(
            "无备份时主文件必须一字节不动",
            "untouched".toByteArray(),
            target.readBytes()
        )
        assertFalse("不得因失败产生临时文件", File(tempFolder.root, "vault.kdbx.tmp").exists())
    }

    @Test
    fun testRestoreRecreatesMissingTargetFromBak() {
        val target = File(tempFolder.root, "vault.kdbx")
        val bak = File(tempFolder.root, "vault.kdbx.bak")
        bak.writeBytes("only-backup".toByteArray())

        val restored = writer().restoreFromRollingBackup(target)

        assertTrue("主文件缺失但备份在场时必须恢复成功", restored)
        assertTrue("恢复必须重建主文件", target.exists())
        assertTrue("备份必须保留", bak.exists())
        org.junit.Assert.assertEquals("恢复内容必须等于备份", "only-backup", read(target))
    }
}
