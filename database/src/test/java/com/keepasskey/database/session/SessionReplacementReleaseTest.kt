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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream

/**
 * ISSUE-P2-77 回归：切换 / 新建密码库必须先释放旧会话（擦除旧库树 + 通知锁观察者驱逐派生数据），
 * **再**装载新库。
 *
 * 顺序是本项的**硬约束**：`FileBinaryStore`（生产侧的 `BinaryStore` 实现）同时是
 * `SessionLockObserver`，其 `onSessionLocked()` 会清空 `cacheDir/attachments`；若释放发生在
 * 新库附件落盘**之后**，刚写下的新库附件会被一并删除（静默数据损坏）。
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
    }

    private val password = "SessionReplace#2026".toCharArray()

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
        return ByteArrayOutputStream().also { KdbxFile.save(it, db, password) }.toByteArray()
    }

    private fun readOrNull(value: ProtectedString): String? =
        try {
            value.readString()
        } catch (_: IllegalStateException) {
            null
        }

    @Test
    fun `换库先释放旧会话再落盘新库附件`() = runBlocking {
        val store = RecordingBinaryStore()
        val session = DatabaseSession(store)
        // 对齐生产装配（DatabaseModule）：附件缓存同时注册为会话终止观察者
        session.addLockObserver(store)

        // 首次打开：换库前置释放（clear，此时无旧库）→ 新库附件落盘（store）
        val first = session.openStream(
            pathIdentifier = "one.kdbx",
            inputStreamProvider = { ByteArrayInputStream(dbBytes("one", ATTACHMENT_SIZE)) },
            passwordChars = password
        )
        assertTrue(first.isSuccess)
        assertEquals(listOf("clear", "store"), store.events)

        val oldDb = session.databaseFlow.first()!!

        // 换库：必须"先 clear 后 store"
        store.events.clear()
        val second = session.openStream(
            pathIdentifier = "two.kdbx",
            inputStreamProvider = { ByteArrayInputStream(dbBytes("two", ATTACHMENT_SIZE)) },
            passwordChars = password
        )
        assertTrue(second.isSuccess)

        assertEquals(
            "换库必须先释放旧会话（clear）再落盘新库附件（store），否则新库附件被别人清空",
            listOf("clear", "store"),
            store.events
        )
        assertTrue("换库后新库附件仍应在 store 中存活", store.sizeOf("k1") > 0)

        // 旧库树已擦除（受保护字段不可再读）
        val oldPassword = oldDb.rootGroup.allEntries().first()
            .fields[KdbxConstants.Fields.PASSWORD]!!
        assertNull("换库必须擦除旧库的受保护字段", readOrNull(oldPassword))
    }

    private companion object {
        /** 严格大于 `BinaryStorePolicy.DEFAULT_THRESHOLD_BYTES`（1 MiB），确保解析期落盘。 */
        const val ATTACHMENT_SIZE = 1_200_000
    }
}
