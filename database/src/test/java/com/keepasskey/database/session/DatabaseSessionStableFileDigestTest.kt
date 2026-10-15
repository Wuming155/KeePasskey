package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DatabaseSessionStableFileDigestTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `digest is bound to active committed database revision`() = runTest {
        val file = File(folder.root, "stable.kdbx")
        val password = "Fake#StableDigest".toCharArray()
        val session = DatabaseSession()
        try {
            assertTrue(session.create(file, "Stable", password, useArgon2 = false) is KdbxResult.Success)
            val initialDatabase = requireNotNull(session.databaseFlow.value)
            val initial = requireNotNull(session.writeIntents.digestStableFileFor(initialDatabase))
            assertEquals(file.absolutePath, initial.filePath)
            assertEquals(file.absolutePath, initial.pathIdentifier)

            session.saveEntry(
                KdbxEntry(
                    id = KdbxUuid.random(),
                    fields = mapOf(
                        KdbxConstants.Fields.TITLE to ProtectedString("fake", isProtected = false)
                    )
                )
            )
            val editedDatabase = requireNotNull(session.databaseFlow.value)
            assertNull("未落盘修订不能获得正式文件摘要", session.writeIntents.digestStableFileFor(editedDatabase))
            assertNull("旧数据库身份不能读取新会话的文件摘要", session.writeIntents.digestStableFileFor(initialDatabase))

            assertTrue(session.save() is KdbxResult.Success)
            val committed = requireNotNull(session.writeIntents.digestStableFileFor(editedDatabase))
            assertNotEquals("正式保存的新版本必须有不同文件摘要", initial.sha256, committed.sha256)
        } finally {
            session.close()
            password.fill('0')
        }
    }
}
