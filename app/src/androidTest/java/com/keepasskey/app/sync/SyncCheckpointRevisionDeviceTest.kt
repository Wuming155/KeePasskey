package com.keepasskey.app.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** ISSUE-P2-624：设备文件回归检查点摘要必须绑定已提交的数据库修订。 */
@RunWith(AndroidJUnit4::class)
class SyncCheckpointRevisionDeviceTest {

    @Test
    fun committedCheckpointDigestBindsTheExactPersistedDatabaseRevision() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseFile = File(context.cacheDir, "checkpoint-revision-device.kdbx")
        val password = "Fake#CheckpointRevision".toCharArray()
        val session = DatabaseSession()
        val reopenedSession = DatabaseSession()

        try {
            assertTrue(
                "synthetic database creation must succeed",
                session.create(databaseFile, "Checkpoint Revision", password, useArgon2 = false) is KdbxResult.Success
            )
            val baseDatabase = requireNotNull(session.databaseFlow.value)
            val baseRevision = requireNotNull(session.writeIntents.digestStableFileFor(baseDatabase))

            session.saveEntry(
                KdbxEntry(
                    id = KdbxUuid.random(),
                    parentGroupId = baseDatabase.rootGroup.id,
                    fields = mapOf(
                        KdbxConstants.Fields.TITLE to ProtectedString("synthetic-revision-B", false)
                    )
                )
            )
            val pendingDatabase = requireNotNull(session.databaseFlow.value)
            assertNull("旧数据库身份不得读取新修订的文件摘要", session.writeIntents.digestStableFileFor(baseDatabase))
            assertNull("尚未提交的 B 不得取得正式文件摘要", session.writeIntents.digestStableFileFor(pendingDatabase))

            assertTrue("B must commit to the formal file", session.save() is KdbxResult.Success)
            val committedDatabase = requireNotNull(session.databaseFlow.value)
            val committedRevision = requireNotNull(
                session.writeIntents.digestStableFileFor(committedDatabase)
            )
            assertNotEquals("committed B must have a distinct digest", baseRevision.sha256, committedRevision.sha256)
            assertEquals(databaseFile.absolutePath, committedRevision.filePath)

            session.close()
            assertTrue(
                "committed file must re-open after a cold session",
                reopenedSession.open(databaseFile, password) is KdbxResult.Success
            )
            val reopenedDatabase = requireNotNull(reopenedSession.databaseFlow.value)
            assertEquals(
                "re-opened content must be B",
                "synthetic-revision-B",
                reopenedDatabase.rootGroup.entries.single().title
            )
            val reopenedRevision = requireNotNull(
                reopenedSession.writeIntents.digestStableFileFor(reopenedDatabase)
            )
            assertEquals("same committed bytes must have the same digest after reopen", committedRevision.sha256, reopenedRevision.sha256)
        } finally {
            session.close()
            reopenedSession.close()
            password.fill('0')
            databaseFile.delete()
        }
    }
}
