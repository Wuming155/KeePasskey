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
 */
class AtomicFileWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

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

        AtomicFileWriter.fallbackReplace(tmp, target, backupAvailable = true, cause = IOException("atomic move failed"))

        // 无论经标准 rename（POSIX）还是 copy 覆盖（其他平台语义），结果一致
        assertEquals("fresh-new", read(target))
        assertFalse("降级替换成功后临时文件不应残留", tmp.exists())
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
        // 确定性进入 Files.copy 降级分支：java.io.File 非 final，覆写 renameTo 恒返回 false
        // （模拟跨卷 / 目标被占用等标准 rename 失败场景），避免依赖平台 rename 语义。
        val renameFailingTmp = object : File(tmp.path) {
            override fun renameTo(dest: File): Boolean = false
        }

        AtomicFileWriter.fallbackReplace(
            renameFailingTmp,
            target,
            backupAvailable = true,
            cause = IOException("atomic move failed")
        )

        assertEquals("fresh-new", read(target))
        assertTrue("替换后目标文件不得为空", target.length() > 0)
        assertFalse("降级 copy 成功后临时文件必须清理", tmp.exists())
    }
}
