package com.keepasskey.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime

/**
 * `ISSUE-P2-529` AC①/AC②/AC④：库文件「保留副本」的**命名、落点与拷贝行为**用例
 * （纯宿主 JVM 可测；命名是纯函数，拷贝只碰临时目录）。
 *
 * ## 本项要防的失效形态
 * 1. **命名漂移**：四处出口若各写一份命名，真机走查与用户检索都会对不上——本项把
 *    `<原名> (副本 yyyyMMdd-HHmm).kdbx` 与「同分钟重名递增」钉成唯一口径；
 * 2. **落点漂移**：副本必须与源文件**同目录**——落到别处的话，按目录扫描的密码库列表
 *    就看不见它，用户会以为「另存了但找不到」（AC② 的可见性要求）；
 * 3. **副本不完整**：拷贝必须是**逐字节副本**（源是密文 `.kdbx`，副本也必须是密文），
 *    且源文件**一字不动**；
 * 4. **失败仍动源**：源不存在时必须返回 null 且不产生任何文件——这正是「另存后移除」这条
 *    破坏性动作的 fail-closed 前提（副本没落地就绝不允许删原件）。
 */
class VaultFileCopyTest {

    private val at: LocalDateTime = LocalDateTime.of(2026, 10, 8, 9, 4)

    @Test
    fun `副本命名遵循原名加时刻并保留 kdbx 扩展名`() {
        assertEquals(
            "passwords (副本 20261008-0904).kdbx",
            VaultCopyNaming.copyFileName("passwords.kdbx", at)
        )
        assertEquals(
            "密码库 (副本 20261008-0904).kdbx",
            VaultCopyNaming.copyFileName("密码库.kdbx", at)
        )
        // 大小写不敏感的扩展名同样只保留一份（与全仓 endsWith(".kdbx", ignoreCase = true) 同口径）
        assertEquals(
            "Vault (副本 20261008-0904).kdbx",
            VaultCopyNaming.copyFileName("Vault.KDBX", at)
        )
        // 无扩展名时补上 .kdbx（其余落盘入口的既有口径）
        assertEquals(
            "vault (副本 20261008-0904).kdbx",
            VaultCopyNaming.copyFileName("vault", at)
        )
    }

    @Test
    fun `副本落点与源同目录_同分钟内重名自动递增`() {
        val dir = tempDir()
        val source = File(dir, "passwords.kdbx").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val first = VaultCopyNaming.uniqueCopyTarget(source, at)
        assertEquals("副本必须与源文件同目录（否则列表扫不到）", dir, first.parentFile)
        assertEquals("passwords (副本 20261008-0904).kdbx", first.name)

        first.writeBytes(byteArrayOf(9))
        val second = VaultCopyNaming.uniqueCopyTarget(source, at)
        assertEquals("passwords (副本 20261008-0904) 2.kdbx", second.name)

        second.writeBytes(byteArrayOf(9))
        assertEquals(
            "passwords (副本 20261008-0904) 3.kdbx",
            VaultCopyNaming.uniqueCopyTarget(source, at).name
        )
    }

    @Test
    fun `拷贝产生同目录逐字节副本_源文件一字不动`() {
        val dir = tempDir()
        val bytes = ByteArray(64) { it.toByte() }
        val source = File(dir, "vault.kdbx").apply { writeBytes(bytes) }

        val copy = VaultFileCopy.copyBeside(source, at)

        assertNotNull("正常文件必须拷贝成功", copy)
        assertEquals("副本必须与源同目录", source.parentFile, copy!!.parentFile)
        assertEquals("vault (副本 20261008-0904).kdbx", copy.name)
        assertTrue("副本必须是源的逐字节副本（源是密文，副本也必须是密文）", bytes.contentEquals(copy.readBytes()))
        assertTrue("源文件必须原样保留", source.isFile)
        assertTrue("源文件内容不得被改动", bytes.contentEquals(source.readBytes()))
        assertFalse("不得遗留临时件", File(dir, "${copy.name}.tmp").exists())
    }

    @Test
    fun `源文件不存在时返回 null 且不产生任何文件`() {
        val dir = tempDir()
        val missing = File(dir, "missing.kdbx")

        assertNull("源不存在必须返回 null（不得凭空造出文件）", VaultFileCopy.copyBeside(missing, at))
        assertTrue("失败路径不得留下任何文件", dir.listFiles()?.isEmpty() == true)
    }

    private fun tempDir(): File =
        Files.createTempDirectory("keepasskey-vault-copy").toFile().apply { deleteOnExit() }
}
