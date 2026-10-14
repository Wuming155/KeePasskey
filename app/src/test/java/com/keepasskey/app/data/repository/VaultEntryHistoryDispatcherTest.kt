package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.BinarySource
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.Executors

class VaultEntryHistoryDispatcherTest {

    @Test
    fun `历史尺寸读取在调用线程之外执行`() = runBlocking {
        val source = RecordingBinarySource(20L)
        val fixture = fixture(source, historyMaxSize = 33L)
        val mainDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "Main") }
            .asCoroutineDispatcher()

        try {
            withContext(mainDispatcher) {
                val callerThread = Thread.currentThread()
                val result = fixture.coordinator.saveEntryWithEraseContract(
                    uiEntry(fixture.entryId), null, null, emptyMap()
                )

                assertTrue(result is KdbxResult.Success)
                assertTrue("历史尺寸必须离开调用方 Main 线程", source.sizeThread != null && source.sizeThread !== callerThread)
                assertTrue(callerThread.name.startsWith("Main"))
                assertEquals(0, source.loadCalls)
                assertEquals(0, source.openStreamCalls)
                assertEquals(0, source.contentHashCalls)
            }
        } finally {
            fixture.session.close()
            mainDispatcher.close()
        }
    }

    @Test
    fun `无效历史尺寸返回保存失败且拒绝前不发布树或持久化`() = runBlocking {
        listOf(-1L, Long.MAX_VALUE).forEach { invalidSize ->
            val source = RecordingBinarySource(invalidSize)
            val fixture = fixture(source)
            val password = "owned-secret".toCharArray()
            val totp = "owned-totp".toCharArray()
            val custom = "owned-custom".toCharArray()

            try {
                val result = fixture.coordinator.saveEntryWithEraseContract(
                    uiEntry(fixture.entryId, groupId = KdbxUuid.random().toHexString()),
                    password,
                    totp,
                    mapOf("field" to custom)
                )

                assertTrue(result is KdbxResult.Failure)
                result as KdbxResult.Failure
                assertEquals(KdbxError.SAVE_FAILED, result.code)
                assertTrue(result.error is IllegalArgumentException)
                assertEquals(0, fixture.persistCalls())
                assertSame(fixture.initialDatabase, fixture.session.databaseFlow.value)
                assertTrue(password.all { it == '0' })
                assertTrue(totp.all { it == '0' })
                assertTrue(custom.all { it == '0' })
                assertEquals(0, source.loadCalls)
                assertEquals(0, source.openStreamCalls)
                assertEquals(0, source.contentHashCalls)
            } finally {
                password.fill('0')
                totp.fill('0')
                custom.fill('0')
                fixture.session.close()
            }
        }
    }

    @Test
    fun `保存取消沿协程传播并清零调用方自有副本`() = runBlocking {
        val fixture = fixture(RecordingBinarySource(0L), cancelPersist = true)
        val password = "cancel-secret".toCharArray()
        val totp = "cancel-totp".toCharArray()
        val custom = "cancel-custom".toCharArray()

        try {
            val cancellation = try {
                fixture.coordinator.saveEntryWithEraseContract(
                    uiEntry(fixture.entryId), password, totp, mapOf("field" to custom)
                )
                null
            } catch (error: CancellationException) {
                error
            }

            assertTrue(cancellation != null)
            assertTrue(password.all { it == '0' })
            assertTrue(totp.all { it == '0' })
            assertTrue(custom.all { it == '0' })
        } finally {
            password.fill('0')
            totp.fill('0')
            custom.fill('0')
            fixture.session.close()
        }
    }

    private fun fixture(
        source: RecordingBinarySource,
        historyMaxSize: Long = 1024L,
        cancelPersist: Boolean = false
    ): Fixture {
        val rootId = KdbxUuid.random()
        val entryId = KdbxUuid.random()
        val existing = KdbxEntry(
            id = entryId,
            parentGroupId = rootId,
            attachments = listOf(KdbxAttachment("a", refIndex = 0, source = source))
        )
        val database = KdbxDatabase(
            header = KdbxHeader.createDefault(),
            rootGroup = KdbxGroup(id = rootId, name = "Root", entries = listOf(existing)),
            historyMaxItems = 10,
            historyMaxSize = historyMaxSize
        )
        val session = DatabaseSession().also { it.setDatabaseForTesting(database) }
        var persistCalls = 0
        val strings = StringsProvider { _, _ -> "" }
        val coordinator = VaultEntryWriteCoordinator(
            strings = strings,
            databaseSession = session,
            entryMapper = VaultEntryMapper(strings)
        ) {
            persistCalls++
            if (cancelPersist) throw CancellationException("test cancellation")
            KdbxResult.Success(Unit)
        }
        return Fixture(coordinator, session, database, entryId, { persistCalls })
    }

    private fun uiEntry(entryId: KdbxUuid, groupId: String? = null): UiVaultEntry = UiVaultEntry(
        id = entryId.toHexString(),
        title = "title",
        username = "",
        url = "",
        attachments = listOf(
            UiAttachment(
                id = "${entryId.toHexString()}_0",
                fileName = "a",
                fileSizeFormatted = "20 B",
                refIndex = 0
            )
        ),
        groupId = groupId
    )

    private data class Fixture(
        val coordinator: VaultEntryWriteCoordinator,
        val session: DatabaseSession,
        val initialDatabase: KdbxDatabase,
        val entryId: KdbxUuid,
        val persistCalls: () -> Int
    )

    private class RecordingBinarySource(private val declaredSize: Long) : BinarySource {
        var sizeThread: Thread? = null
            private set
        var loadCalls = 0
            private set
        var openStreamCalls = 0
            private set
        var contentHashCalls = 0
            private set

        override val size: Long
            get() {
                sizeThread = Thread.currentThread()
                return declaredSize
            }

        override fun load(): ByteArray {
            loadCalls++
            error("load must not be called")
        }

        override fun openStream(): InputStream {
            openStreamCalls++
            error("openStream must not be called")
        }

        override fun contentHash(): Int {
            contentHashCalls++
            error("contentHash must not be called")
        }
    }
}
