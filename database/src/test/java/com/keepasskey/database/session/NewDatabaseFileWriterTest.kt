package com.keepasskey.database.session

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 排他发布及临时件所有权；实际宿主文件系统参与测试，不模拟 exists/rename。 */
class NewDatabaseFileWriterTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun collisionPreservesTargetBackupAndForeignTemporaryFile() {
        val target = File(folder.root, "vault.kdbx").apply { writeBytes(byteArrayOf(1, 2)) }
        val backup = File(folder.root, "vault.kdbx.bak").apply { writeBytes(byteArrayOf(3, 4)) }
        val foreign = File(folder.root, "vault.kdbx.tmp").apply { writeBytes(byteArrayOf(5)) }
        assertThrows(FileAlreadyExistsException::class.java) { NewDatabaseFileWriter.write(target) { it.write(9) } }
        assertArrayEquals(byteArrayOf(1, 2), target.readBytes())
        assertArrayEquals(byteArrayOf(3, 4), backup.readBytes())
        assertArrayEquals(byteArrayOf(5), foreign.readBytes())
    }

    @Test fun writerFailureLeavesNoTargetAndOnlyOwnTemporaryFileIsRemoved() {
        val target = File(folder.root, "vault.kdbx")
        val foreign = File(folder.root, "vault.kdbx.tmp").apply { writeBytes(byteArrayOf(5)) }
        assertThrows(IOException::class.java) { NewDatabaseFileWriter.write(target) { it.write(8); throw IOException("注入失败") } }
        assertFalse(target.exists())
        assertArrayEquals(byteArrayOf(5), foreign.readBytes())
        assertEquals(listOf(foreign.name), folder.root.listFiles()!!.map { it.name })
    }

    @Test fun targetClaimedAfterWritingStartedIsNeverReplaced() {
        val target = File(folder.root, "vault.kdbx")
        assertThrows(FileAlreadyExistsException::class.java) {
            NewDatabaseFileWriter.write(target) {
                it.write(8)
                target.writeBytes(byteArrayOf(9)) // 确定性复现 exists 与最终发布之间被抢占。
            }
        }
        assertArrayEquals(byteArrayOf(9), target.readBytes())
        assertEquals(listOf(target.name), folder.root.listFiles()!!.map { it.name })
    }

    @Test fun twoConcurrentWritersPublishExactlyOneCompleteProduct() {
        val target = File(folder.root, "vault.kdbx")
        val ready = CountDownLatch(2)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = listOf(1, 2).map { marker ->
                pool.submit<Boolean> {
                    try {
                        NewDatabaseFileWriter.write(target) {
                            it.write(ByteArray(1024) { marker.toByte() })
                            ready.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                        }
                        true
                    } catch (e: FileAlreadyExistsException) { false }
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            release.countDown()
            assertEquals(1, jobs.count { it.get(10, TimeUnit.SECONDS) })
            val actual = target.readBytes()
            assertEquals(1024, actual.size)
            assertTrue(actual.contentEquals(ByteArray(1024) { 1 }) || actual.contentEquals(ByteArray(1024) { 2 }))
            assertEquals(listOf(target.name), folder.root.listFiles()!!.map { it.name })
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun directoryTargetIsRejectedWithoutTouchingChildren() {
        val target = folder.newFolder("vault.kdbx")
        val child = File(target, "retained").apply { writeBytes(byteArrayOf(1)) }
        assertThrows(FileAlreadyExistsException::class.java) { NewDatabaseFileWriter.write(target) { it.write(9) } }
        assertTrue(target.isDirectory)
        assertArrayEquals(byteArrayOf(1), child.readBytes())
    }
}
