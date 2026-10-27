package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.security.BinaryStore
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.core.session.SessionLockObserver
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream

/**
 * ENG-20261027-02 / ISSUE-P2-77 回归：
 * 换库/重载必须在候选读取、凭据验证与附件事务成功后才替换旧会话；
 * 失败（损坏、外部换密、读取异常、取消）保持旧树/脏态/凭据与附件。
 * 旧库附件定点清理，防误删新候选附件。
 */
class SessionReplacementReleaseTest {

    /** 记录调用次序的假 store：同时实现锁观察者（对齐生产 `FileBinaryStore` 的双身份）。 */
    private class RecordingBinaryStore : BinaryStore, SessionLockObserver {
        val events = mutableListOf<String>()
        private val entries = LinkedHashMap<String, ByteArray>()
        private var counter = 0

        override fun onSessionLocked() = clear()

        override fun store(bytes: ByteArray): String {
            events += "store"
            val key = "k${counter++}"
            entries[key] = bytes.copyOf()
            return key
        }

        override fun storeFromStream(input: InputStream, size: Long): String {
            events += "store"
            val bytes = ByteArray(size.toInt())
            DataInputStream(input).readFully(bytes)
            val key = "k${counter++}"
            entries[key] = bytes
            return key
        }

        override fun load(key: String): ByteArray =
            entries[key]?.copyOf() ?: throw IllegalStateException("store 中不存在 key=$key")

        override fun openStream(key: String): InputStream = ByteArrayInputStream(load(key))

        override fun sizeOf(key: String): Long = (entries[key]?.size ?: 0).toLong()

        override fun clear() {
            events += "clear"
            entries.values.forEach { it.fill(0) }
            entries.clear()
        }

        override fun delete(key: String) {
            events += "delete:$key"
            entries[key]?.fill(0)
            entries.remove(key)
        }
    }

    private lateinit var password: CharArray

    @Before
    fun setUp() {
        password = "SessionReplace#2026".toCharArray()
    }

    @After
    fun tearDown() {
        password.fill('0')
    }

    /** 生成一个含 >1 MiB 附件（解析期必然落盘）的单条目库字节。 */
    private fun dbBytes(title: String, attachmentSize: Int): ByteArray {
        val entry = KdbxEntry(
            fields = linkedMapOf(
                KdbxConstants.Fields.PASSWORD to ProtectedString("$title-Pass#2026", isProtected = true)
            ),
            attachments = listOf(KdbxAttachment(name = "$title.bin", data = ByteArray(attachmentSize) { 7 }))
        )
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root", entries = listOf(entry))
        )
        return try {
            ByteArrayOutputStream().also { KdbxFile.save(it, db, password) }.toByteArray()
        } finally {
            db.clearSensitiveData()
        }
    }

    private fun readOrNull(value: ProtectedString): String? =
        try {
            value.readString()
        } catch (_: IllegalStateException) {
            null
        }

    @Test
    fun `换库候选解析成功后才释放旧会话并定点清理旧附件`() = runBlocking {
        val store = RecordingBinaryStore()
        val session = DatabaseSession(store)
        session.addLockObserver(store)
        try {
            val first = session.openStream(
                pathIdentifier = "one.kdbx",
                inputStreamProvider = { ByteArrayInputStream(dbBytes("one", ATTACHMENT_SIZE)) },
                passwordChars = password
            )
            assertTrue(first.isSuccess)
            assertEquals(listOf("store"), store.events)
            assertTrue(store.sizeOf("k0") > 0)

            val oldDb = session.databaseFlow.first()!!

            store.events.clear()
            val second = session.openStream(
                pathIdentifier = "two.kdbx",
                inputStreamProvider = { ByteArrayInputStream(dbBytes("two", ATTACHMENT_SIZE)) },
                passwordChars = password
            )
            assertTrue(second.isSuccess)

            assertEquals(
                "换库必须先候选落盘再定点删除旧库附件，不得在解析前释放旧库更不得全局 clear",
                listOf("store", "delete:k0"),
                store.events
            )
            assertTrue("换库后新库附件仍应在 store 中存活", store.sizeOf("k1") > 0)
            assertEquals("旧库附件已定点删除", 0L, store.sizeOf("k0"))

            val oldPassword = oldDb.rootGroup.allEntries().first()
                .fields[KdbxConstants.Fields.PASSWORD]!!
            assertNull("换库必须擦除旧库的受保护字段", readOrNull(oldPassword))
        } finally {
            session.close()
        }
    }

    @Test
    fun `换库损坏文件失败时保留原会话明文树脏态凭据与附件`() = runBlocking {
        val store = RecordingBinaryStore()
        val session = DatabaseSession(store)
        session.addLockObserver(store)
        try {
            val first = session.openStream(
                pathIdentifier = "one.kdbx",
                inputStreamProvider = { ByteArrayInputStream(dbBytes("one", ATTACHMENT_SIZE)) },
                passwordChars = password
            )
            assertTrue(first.isSuccess)
            val oldDb = session.databaseFlow.first()!!
            val oldPassword = oldDb.rootGroup.allEntries().first()
                .fields[KdbxConstants.Fields.PASSWORD]!!

            session.updateDatabaseMeta { it.copy(databaseName = "DirtyName") }
            assertEquals(DatabaseSession.SessionState.DIRTY, session.state.value)

            store.events.clear()
            val corruptBytes = byteArrayOf(0x03, 0xd9.toByte(), 0xa2.toByte(), 0x9a.toByte(), 0, 0, 0, 0)
            val second = session.openStream(
                pathIdentifier = "corrupt.kdbx",
                inputStreamProvider = { ByteArrayInputStream(corruptBytes) },
                passwordChars = password
            )
            assertTrue("损坏文件打开必须失败", second.isFailure)

            assertEquals("会话状态必须保持 DIRTY", DatabaseSession.SessionState.DIRTY, session.state.value)
            assertEquals("内存修改必须保留", "DirtyName", session.databaseFlow.first()?.databaseName)
            assertEquals("旧树受保护字段必须可读", "one-Pass#2026", readOrNull(oldPassword))
            assertTrue("旧库附件必须仍在 store 中存活", store.sizeOf("k0") > 0)
            session.useCredentials { pwd, _ ->
                assertNotNull("凭据保留", pwd)
                assertArrayEquals(password, pwd)
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `换库外部换密失败时保留原会话与凭据`() = runBlocking {
        val store = RecordingBinaryStore()
        val session = DatabaseSession(store)
        session.addLockObserver(store)
        val wrongPassword = "WrongPassword#2026".toCharArray()
        try {
            val first = session.openStream(
                pathIdentifier = "one.kdbx",
                inputStreamProvider = { ByteArrayInputStream(dbBytes("one", ATTACHMENT_SIZE)) },
                passwordChars = password
            )
            assertTrue(first.isSuccess)
            val oldDb = session.databaseFlow.first()!!

            val second = session.openStream(
                pathIdentifier = "two.kdbx",
                inputStreamProvider = { ByteArrayInputStream(dbBytes("two", ATTACHMENT_SIZE)) },
                passwordChars = wrongPassword
            )
            assertTrue("换密/错密打开必须失败", second.isFailure)

            assertEquals(DatabaseSession.SessionState.OPENED, session.state.value)
            assertTrue("会话树必须保持原库", session.databaseFlow.first() === oldDb)
            assertTrue("原库附件必须存活", store.sizeOf("k0") > 0)
            session.useCredentials { pwd, _ ->
                assertNotNull("凭据保留", pwd)
                assertArrayEquals(password, pwd)
            }
        } finally {
            wrongPassword.fill('0')
            session.close()
        }
    }

    @Test
    fun `换库流读取异常时保留原会话`() = runBlocking {
        val store = RecordingBinaryStore()
        val session = DatabaseSession(store)
        session.addLockObserver(store)
        try {
            val first = session.openStream(
                pathIdentifier = "one.kdbx",
                inputStreamProvider = { ByteArrayInputStream(dbBytes("one", ATTACHMENT_SIZE)) },
                passwordChars = password
            )
            assertTrue(first.isSuccess)
            val oldDb = session.databaseFlow.first()!!

            val failingStreamProvider: suspend () -> InputStream = {
                object : InputStream() {
                    override fun read(): Int = throw java.io.IOException("simulated read failure")
                }
            }
            val second = session.openStream(
                pathIdentifier = "io_error.kdbx",
                inputStreamProvider = failingStreamProvider,
                passwordChars = password
            )
            assertTrue(second.isFailure)

            assertEquals(DatabaseSession.SessionState.OPENED, session.state.value)
            assertTrue(session.databaseFlow.first() === oldDb)
            assertTrue(store.sizeOf("k0") > 0)
        } finally {
            session.close()
        }
    }

    @Test
    fun `换库取消时保留原会话并传播取消异常`() = runBlocking {
        val store = RecordingBinaryStore()
        val session = DatabaseSession(store)
        session.addLockObserver(store)
        try {
            val first = session.openStream(
                pathIdentifier = "one.kdbx",
                inputStreamProvider = { ByteArrayInputStream(dbBytes("one", ATTACHMENT_SIZE)) },
                passwordChars = password
            )
            assertTrue(first.isSuccess)
            val oldDb = session.databaseFlow.first()!!

            val cancellingStreamProvider: suspend () -> InputStream = {
                throw kotlinx.coroutines.CancellationException("simulated user cancellation")
            }
            val error = runCatching {
                session.openStream(
                    pathIdentifier = "cancel.kdbx",
                    inputStreamProvider = cancellingStreamProvider,
                    passwordChars = password
                )
            }.exceptionOrNull()

            assertTrue("取消必须原样抛出", error is kotlinx.coroutines.CancellationException)
            assertEquals(DatabaseSession.SessionState.OPENED, session.state.value)
            assertTrue(session.databaseFlow.first() === oldDb)
            assertTrue(store.sizeOf("k0") > 0)
        } finally {
            session.close()
        }
    }

    private companion object {
        /** 严格大于 `BinaryStorePolicy.DEFAULT_THRESHOLD_BYTES`（1 MiB），确保解析期落盘。 */
        const val ATTACHMENT_SIZE = 1_200_000
    }
}
