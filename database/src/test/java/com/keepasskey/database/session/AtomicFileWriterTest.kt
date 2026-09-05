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
}
