package com.keepasskey.app.data.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/** Android filesDir 的真实硬链接支持与排他会话契约；不允许以宿主绿替代。 */
@RunWith(AndroidJUnit4::class)
class NewVaultCreationAndroidTest {
    @Test fun exclusiveCreationInFilesDirReopensAndCollisionKeepsOldSession() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.filesDir.toPath(), "new-vault-probe-").toFile()
        val target = File(directory, "vault.kdbx")
        val pwd = "Fake#AndroidCreate".toCharArray()
        val session = DatabaseSession()
        val reopened = DatabaseSession()
        try {
            val created = session.create(target, "First", pwd, false)
            if (created is KdbxResult.Failure) throw AssertionError("安全新建失败 code=${created.code}", created.error)
            assertTrue("Android 私有目录必须支持安全新建", created.isSuccess)
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(target.toPath()))
            assertTrue(reopened.open(target, pwd).isSuccess)
            assertEquals("First", reopened.databaseFlow.value!!.databaseName)
            val oldTree = session.databaseFlow.value
            val oldBytes = target.readBytes()
            val bak = File(directory, "vault.kdbx.bak").apply { writeBytes(byteArrayOf(1, 2)) }
            for (enabled in listOf(false, true)) {
                session.createBackupBeforeSave = enabled
                val result = session.create(target, "Wrong", pwd, false)
                assertTrue(result is KdbxResult.Failure)
                assertEquals(KdbxError.CREATE_COLLISION, (result as KdbxResult.Failure).code)
                assertSame(oldTree, session.databaseFlow.value)
                assertArrayEquals(oldBytes, target.readBytes())
                assertArrayEquals(byteArrayOf(1, 2), bak.readBytes())
            }
            assertTrue(session.save().isSuccess)
        } finally {
            reopened.close()
            session.close()
            pwd.fill('0')
            // 唯一测试目录全部由本用例创建，只清理已知文件，不递归删除未知路径。
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }
}
