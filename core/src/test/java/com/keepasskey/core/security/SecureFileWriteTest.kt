package com.keepasskey.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * `ISSUE-P3-582`：core 落盘原语 [SecureFileWrite] 的**唯一临时名 / 原子交付 / 权限调用边界**用例
 * （纯宿主 JVM 可测）。
 *
 * ## 本项要防的失效形态
 * 1. **固定名互踩**：同一目标两次落盘若共用同一临时名，并发写会交错覆盖、失败清理还会删掉
 *    并发者正在写的临时件——本项把「同一目标两次唯一名不同」钉死；
 * 2. **落点漂移**：临时件若不与目标同目录，`rename` 可能跨文件系统而退化为非原子拷贝；
 * 3. **交付不原子**：`moveAtomically` 必须覆盖既有目标、返回成功且不留临时件。
 *
 * ## 权限面（**如实声明**）
 * 宿主 JVM 无法证明 0600 / 0700 的真值：Windows 开发机走非 POSIX 降级分支、POSIX 宿主又不代表
 * Android `umask` 语境。故本项只锁「[SecureFileWrite.restrictToOwnerOnly] 调用不抛异常」这一
 * 结构性契约；**权限真值由设备侧 `stat` 断言承担**——本批不新增 `androidTest`（禁跑设备），
 * 该取证缺口如实登记于批次 §497 与 `已知工程限界.md` §43。
 */
class SecureFileWriteTest {

    private fun tempDir(): File =
        Files.createTempDirectory("secure-file-write").toFile().apply { deleteOnExit() }

    @Test
    fun `唯一临时名与目标同目录且同目标多次调用互不相同`() {
        val dir = tempDir()
        val target = File(dir, "vault.kdbx")
        val first = SecureFileWrite.uniqueTmpFor(target, SUFFIX_IMPORTING)
        val second = SecureFileWrite.uniqueTmpFor(target, SUFFIX_IMPORTING)

        assertEquals("临时件须与目标同目录（rename 原子性的前提）", dir, first.parentFile)
        assertTrue("临时名须以目标名为前缀: ${first.name}", first.name.startsWith("vault.kdbx."))
        assertTrue("临时名须带指定后缀: ${first.name}", first.name.endsWith(SUFFIX_IMPORTING))
        assertNotEquals(
            "同一目标两次调用必须给出不同临时名（消除并发互踩与误删他人 tmp）",
            first.name, second.name
        )
    }

    @Test
    fun `默认临时后缀为 tmp`() {
        val dir = tempDir()
        val tmp = SecureFileWrite.uniqueTmpFor(File(dir, "a.kdbx"))
        assertTrue("默认后缀须为 .tmp: ${tmp.name}", tmp.name.endsWith(SecureFileWrite.SUFFIX_TMP))
    }

    @Test
    fun `writeSynced 写入字节并落盘`() {
        val dir = tempDir()
        val tmp = File(dir, "x.tmp")
        SecureFileWrite.writeSynced(tmp, byteArrayOf(1, 2, 3))
        assertTrue(tmp.isFile)
        assertArrayEquals(byteArrayOf(1, 2, 3), tmp.readBytes())
    }

    @Test
    fun `moveAtomically 原子交付覆盖既有目标且不留临时件`() {
        val dir = tempDir()
        val target = File(dir, "vault.kdbx").apply { writeText("OLD") }
        val tmp = File(dir, "vault.kdbx.00000000-0000-0000-0000-000000000000.tmp").apply { writeText("NEW") }

        assertTrue(SecureFileWrite.moveAtomically(tmp, target))
        assertEquals("交付须覆盖既有目标", "NEW", target.readText())
        assertFalse("交付后临时件不应存在", tmp.exists())
    }

    @Test
    fun `restrictToOwnerOnly 对文件与目录均不抛异常且不改内容`() {
        val dir = tempDir()
        val file = File(dir, "secret.kdbx").apply { writeText("payload") }

        SecureFileWrite.restrictToOwnerOnly(file, isDirectory = false)
        SecureFileWrite.restrictToOwnerOnly(dir, isDirectory = true)

        assertTrue("收敛权限不得触碰文件内容", file.isFile)
        assertEquals("payload", file.readText())
    }

    private companion object {
        const val SUFFIX_IMPORTING = ".importing"
    }
}
