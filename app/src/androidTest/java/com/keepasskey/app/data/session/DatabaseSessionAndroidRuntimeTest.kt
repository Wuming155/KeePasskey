package com.keepasskey.app.data.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.data.binary.FileBinaryStore
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * 解锁端到端 + 大附件落盘的**设备侧（instrumented）**回归（ISSUE-P2-27 解锁链路 / ISSUE-P2-24 AC②③）。
 *
 * ## 为什么必须有这一层
 *
 * 解锁链路（复合密钥派生 → 外层 Header 校验 → HMAC 块流 → 解密 → 解压 → 内层 Header → XML）
 * 在设备侧依赖 Rust 原生内核、`MessageDigest`/`Cipher` 的平台实现与真实文件系统语义；
 * 而 ISSUE-P2-24 的「附件落盘 + 权限 0600/0700 + 锁定即清理」只能由真实 POSIX 文件系统验证
 * （宿主 JVM 走的是降级分支，验不到真实权限位）。
 *
 * 本用例用**生产写入管线**现场产出 `.kdbx`，再经生产 [DatabaseSession] 解锁，
 * 断言：大附件落盘且权限收敛、字节往返等价、锁定后缓存目录被清空。
 */
@RunWith(AndroidJUnit4::class)
class DatabaseSessionAndroidRuntimeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cacheDir = File(context.cacheDir, "attachments")

    private fun cacheFiles(): List<File> =
        cacheDir.listFiles { file -> file.isFile && file.name.endsWith(".cache") }?.toList() ?: emptyList()

    private fun kdbxFileWith(attachment: ByteArray): File {
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(
                name = "Root",
                entries = listOf(
                    KdbxEntry(
                        fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("设备侧", isProtected = false)),
                        attachments = listOf(KdbxAttachment("blob.bin", data = attachment))
                    )
                )
            )
        )
        val file = File(context.cacheDir, "device-session-test.kdbx")
        file.delete()
        FileOutputStream(file).use { KdbxFile.save(it, db, PASSWORD) }
        return file
    }

    @Test
    fun `带大附件密码库在设备侧解锁后附件落盘且锁定即清理`() = runBlocking<Unit> {
        cacheDir.deleteRecursively()
        val big = ByteArray(1_200_000) { (it % 251).toByte() }
        val file = kdbxFileWith(big)

        val store = FileBinaryStore(context)
        val session = DatabaseSession(store).apply { addLockObserver(store) }

        val openResult = session.open(file, PASSWORD)
        assertTrue(
            "解锁失败: ${(openResult as? KdbxResult.Failure)?.error?.javaClass?.name}",
            openResult is KdbxResult.Success
        )
        assertEquals(DatabaseSession.SessionState.OPENED, session.state.value)

        val cached = cacheFiles()
        assertEquals("超过 1 MiB 的附件必须落盘", 1, cached.size)
        assertEquals(
            "落盘文件必须收敛为仅属主可读写",
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(cached.single().toPath())
        )
        assertEquals(
            "缓存目录必须收敛为 0700",
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            ),
            Files.getPosixFilePermissions(cacheDir.toPath())
        )

        val attachment = session.databaseFlow.value!!.rootGroup.entries.single().attachments.single()
        assertArrayEquals("设备侧落盘往返必须逐字节等价", big, attachment.data)

        session.lock()
        assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)
        assertTrue("锁定后附件缓存必须清空", cacheFiles().isEmpty())

        file.delete()
    }

    @Test
    fun `阈值以下附件不落盘`() = runBlocking<Unit> {
        cacheDir.deleteRecursively()
        val file = kdbxFileWith(ByteArray(4096) { it.toByte() })

        val store = FileBinaryStore(context)
        val session = DatabaseSession(store).apply { addLockObserver(store) }

        assertTrue(session.open(file, PASSWORD) is KdbxResult.Success)
        assertTrue("阈值以内的附件不得落盘", cacheFiles().isEmpty())

        session.close()
        assertTrue(cacheFiles().isEmpty())

        file.delete()
    }

    private companion object {
        /** 伪造测试口令（敏感纪律：不写入断言消息）。 */
        val PASSWORD = "DeviceSession#2026".toCharArray()
    }
}
