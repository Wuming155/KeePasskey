package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.security.BinaryStore
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream

/** ISSUE-P3-622：解析成功但流关闭/会话接管失败时，候选根库的 spill key 仍须回收。 */
class SessionOpenSpillHandoverTest {

    private class FakeStore : BinaryStore {
        val entries = LinkedHashMap<String, ByteArray>()
        val deleted = mutableListOf<String>()
        private var counter = 0

        override fun store(bytes: ByteArray): String = "new-${counter++}".also {
            entries[it] = bytes.copyOf()
        }

        override fun storeFromStream(input: InputStream, size: Long): String {
            val bytes = ByteArray(size.toInt())
            DataInputStream(input).readFully(bytes)
            return store(bytes)
        }

        override fun load(key: String): ByteArray = entries[key]?.copyOf() ?: ByteArray(0)

        override fun openStream(key: String): InputStream = ByteArrayInputStream(load(key))

        override fun sizeOf(key: String): Long = (entries[key]?.size ?: 0).toLong()

        override fun clear() = entries.clear()

        override fun delete(key: String) {
            deleted.add(key)
            entries.remove(key)
        }
    }

    @Test
    fun `解析成功后流关闭失败回收候选附件且不删活动对照项`() = runTest {
        val password = "handover-fake-password".toCharArray()
        val database = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(
                name = "Root",
                entries = listOf(
                    KdbxEntry(
                        fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("entry", false)),
                        attachments = listOf(
                            KdbxAttachment(
                                "large.bin",
                                data = ByteArray(1_200_000) { (it % 251).toByte() }
                            )
                        )
                    )
                )
            )
        )
        val bytes = ByteArrayOutputStream().also { KdbxFile.save(it, database, password) }.toByteArray()
        val store = FakeStore().apply { entries["active-root-key"] = byteArrayOf(21, 22, 23) }
        val session = DatabaseSession(store)
        val input = object : ByteArrayInputStream(bytes) {
            override fun close() {
                throw IOException("synthetic close failure after successful parse")
            }
        }

        val result = session.openStream(
            pathIdentifier = "root.kdbx",
            inputStreamProvider = { input },
            passwordChars = password
        )

        assertTrue("close failure must fail the session open", result.isFailure)
        assertEquals("candidate spill key must be deleted", 1, store.deleted.size)
        assertEquals(setOf("active-root-key"), store.entries.keys)
        assertArrayEquals(byteArrayOf(21, 22, 23), store.load("active-root-key"))
        assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)
    }
}
