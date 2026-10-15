package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.security.BinaryStore
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.file.AttachmentSpillTransaction
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.core.security.ProtectedString
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException

/**
 * ISSUE-P3-622：按次 spill 事务必须覆盖晚期解析失败、取消、成功移交及清理重试。
 */
class SpillRecordingBinaryStoreTest {

    private class FakeStore : BinaryStore {
        val entries = LinkedHashMap<String, ByteArray>()
        val deleted = mutableListOf<String>()
        var failNextDelete = false
        var cancelOnStoreFromStreamCall: Int? = null
        private var storeFromStreamCalls = 0
        private var counter = 0

        override fun store(bytes: ByteArray): String = "spill-${counter++}".also {
            entries[it] = bytes.copyOf()
        }

        override fun storeFromStream(input: java.io.InputStream, size: Long): String {
            storeFromStreamCalls++
            val bytes = ByteArray(size.toInt())
            DataInputStream(input).readFully(bytes)
            val key = store(bytes)
            if (storeFromStreamCalls == cancelOnStoreFromStreamCall) {
                // 模拟 delegate 在 key 尚未返回时取消；BinaryStore 自己撤销该未交付写入。
                delete(key)
                throw CancellationException("test cancellation during second spill")
            }
            return key
        }

        override fun load(key: String): ByteArray = entries[key]?.copyOf() ?: ByteArray(0)

        override fun openStream(key: String): java.io.InputStream = ByteArrayInputStream(load(key))

        override fun sizeOf(key: String): Long = (entries[key]?.size ?: 0).toLong()

        override fun clear() {
            entries.clear()
        }

        override fun delete(key: String) {
            deleted.add(key)
            if (failNextDelete) {
                failNextDelete = false
                throw IOException("synthetic delete failure")
            }
            entries.remove(key)
        }
    }

    private data class Vault(val bytes: ByteArray, val password: CharArray, val attachments: List<ByteArray>)

    private fun vaultWithLargeAttachment(attachmentCount: Int = 1): Vault {
        val attachments = List(attachmentCount) { index ->
            ByteArray(1_200_000).also { java.util.Random(622L + index).nextBytes(it) }
        }
        val password = "P3-622-fake-password".toCharArray()
        val database = KdbxDatabase(
            // NONE 避免 gzip 在终止块认证前读取 trailer，使测试确实观察到已完成的附件 spill。
            header = KdbxHeader.createDefault(useArgon2 = false)
                .copy(compression = KdbxConstants.Compression.NONE),
            rootGroup = KdbxGroup(
                name = "Root",
                entries = listOf(
                    KdbxEntry(
                        fields = mapOf(
                            KdbxConstants.Fields.TITLE to ProtectedString("fixture", isProtected = false),
                            // 让附件 spill 完成后仍有多个认证数据块供 XML 解析，失败发生在真正晚期。
                            KdbxConstants.Fields.NOTES to ProtectedString(
                                "tail".repeat(700_000),
                                isProtected = false
                            )
                        ),
                        attachments = attachments.mapIndexed { index, bytes ->
                            KdbxAttachment("large-$index.bin", data = bytes)
                        }
                    )
                )
            )
        )
        val bytes = ByteArrayOutputStream().also { KdbxFile.save(it, database, password) }.toByteArray()
        return Vault(bytes, password, attachments)
    }

    @Test
    fun `终止块认证晚失败时仅回收本次spill且保留活动根库附件`() {
        AttachmentSpillTransaction.retryPendingCleanup()
        val vault = vaultWithLargeAttachment()
        // HMAC 终止块结构为 32 字节 MAC + 4 字节零长度；改 MAC 而保留长度字段。
        val corrupt = vault.bytes.copyOf().also {
            val terminalMacLastByte = it.lastIndex - Int.SIZE_BYTES
            it[terminalMacLastByte] = (it[terminalMacLastByte].toInt() xor 1).toByte()
        }
        val store = FakeStore().apply { entries["active-root-key"] = byteArrayOf(7, 8, 9) }
        val transaction = KdbxFile.beginAttachmentSpillTransaction(store)

        val error = runCatching {
            transaction.load(ByteArrayInputStream(corrupt), vault.password)
        }.exceptionOrNull()

        assertTrue("应由终止块认证发现晚期损坏: ${error?.message}", error is KdbxCorruptFileException)
        assertEquals(
            "晚失败时本次附件已 spill; error=${error?.message}; entries=${store.entries.keys}",
            1,
            store.deleted.size
        )
        assertEquals("只允许活动根库对照项保留", setOf("active-root-key"), store.entries.keys)
        assertArrayEquals(byteArrayOf(7, 8, 9), store.load("active-root-key"))
    }

    @Test
    fun `认证终止前取消仍传播且回收此前完成的spill`() {
        AttachmentSpillTransaction.retryPendingCleanup()
        val vault = vaultWithLargeAttachment(attachmentCount = 2)
        val store = FakeStore().apply { entries["active-root-key"] = byteArrayOf(1, 2, 3) }
        store.cancelOnStoreFromStreamCall = 2
        val transaction = KdbxFile.beginAttachmentSpillTransaction(store)
        val error = runCatching {
            transaction.load(ByteArrayInputStream(vault.bytes), vault.password)
        }.exceptionOrNull()

        assertTrue("流式写入取消必须原样传播", error is CancellationException)
        assertEquals("第二个未交付 key 由 store 撤销，第一个由事务回收", 2, store.deleted.size)
        assertEquals(setOf("active-root-key"), store.entries.keys)
    }

    @Test
    fun `成功根库解析显式移交后保留附件并可读`() {
        val vault = vaultWithLargeAttachment()
        val store = FakeStore()
        val transaction = KdbxFile.beginAttachmentSpillTransaction(store)

        val database = transaction.load(ByteArrayInputStream(vault.bytes), vault.password)
        transaction.commit()

        assertEquals(1, store.entries.size)
        assertArrayEquals(vault.attachments.single(), database.rootGroup.entries.single().attachments.single().data)
        assertTrue("移交后的事务不得删除会话附件", store.deleted.isEmpty())
    }

    @Test
    fun `移交后的数据库释放仅删除不再被存活副本引用的spill`() {
        val vault = vaultWithLargeAttachment()
        val store = FakeStore().apply { entries["active-other-vault-key"] = byteArrayOf(31, 32) }
        val transaction = KdbxFile.beginAttachmentSpillTransaction(store)
        val parsed = transaction.load(ByteArrayInputStream(vault.bytes), vault.password)
        transaction.commit()
        val liveCopy = parsed.copy()

        parsed.clearBinaryPool(liveCopy.binaries)

        assertEquals("活动副本仍引用的 spill 不得被回收", 2, store.entries.size)
        assertTrue(store.deleted.isEmpty())

        liveCopy.clearSensitiveData()

        assertEquals("活动副本释放后应定点删除其 spill", setOf("active-other-vault-key"), store.entries.keys)
        assertEquals(1, store.deleted.size)
    }

    @Test
    fun `liveRoot历史附件source能保护不在live binaries池中的spill`() {
        val vault = vaultWithLargeAttachment()
        val store = FakeStore()
        val transaction = KdbxFile.beginAttachmentSpillTransaction(store)
        val parsed = transaction.load(ByteArrayInputStream(vault.bytes), vault.password)
        transaction.commit()
        val sharedSource = parsed.rootGroup.entries.single().attachments.single().binarySource()
            ?: error("fixture must use a spilled BinaryItem")
        val liveHistory = KdbxEntry(
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("live", false)),
            history = listOf(
                KdbxEntry(
                    fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("history", false)),
                    attachments = listOf(KdbxAttachment("history.bin", source = sharedSource))
                )
            )
        )
        val liveDatabase = parsed.copy(
            rootGroup = KdbxGroup(name = "Live", entries = listOf(liveHistory)),
            binaries = emptyList()
        )
        val discarded = parsed.copy(rootGroup = KdbxGroup(name = "Discarded"))

        discarded.clearBinaryPool(liveDatabase)

        assertEquals("history source 仍由活動樹引用，spill 必須保留", 1, store.entries.size)
        liveDatabase.clearSensitiveData()
        assertTrue("活動樹生命週期結束後應按 source owner 釋放 spill", store.entries.isEmpty())
    }

    @Test
    fun `移交后的单键删除失败保留状态并由后续清扫重试`() {
        AttachmentSpillTransaction.retryPendingCleanup()
        val vault = vaultWithLargeAttachment()
        val store = FakeStore()
        val transaction = KdbxFile.beginAttachmentSpillTransaction(store)
        val database = transaction.load(ByteArrayInputStream(vault.bytes), vault.password)
        transaction.commit()
        store.failNextDelete = true

        database.clearSensitiveData()

        assertEquals("删除失败时必须保留待清理文件", 1, store.entries.size)
        assertEquals("清理失败必须登记定点删除请求", 1, store.deleted.size)
        assertEquals("后续清扫必须重试删除且不留下待清理项", 0, AttachmentSpillTransaction.retryPendingCleanup())
        assertTrue("重试后删除完成", store.entries.isEmpty())
        assertEquals("重试必须再次发出定点删除", 2, store.deleted.size)
        assertEquals(0, AttachmentSpillTransaction.retryPendingCleanup())
    }

    @Test
    fun `投影不再使用附件时purge幂等且只删除本次key`() {
        val vault = vaultWithLargeAttachment()
        val store = FakeStore().apply { entries["active-root-key"] = byteArrayOf(4, 5, 6) }
        val transaction = KdbxFile.beginAttachmentSpillTransaction(store)
        val database = transaction.load(ByteArrayInputStream(vault.bytes), vault.password)
        database.clearSensitiveData()

        transaction.purge()
        transaction.purge()

        assertEquals(1, store.deleted.size)
        assertEquals(setOf("active-root-key"), store.entries.keys)
        assertArrayEquals(byteArrayOf(4, 5, 6), store.load("active-root-key"))
    }

    @Test
    fun `删除失败保留待清理key并可由后续安全清扫重试`() {
        AttachmentSpillTransaction.retryPendingCleanup()
        val vault = vaultWithLargeAttachment()
        val store = FakeStore().apply { failNextDelete = true }
        val transaction = KdbxFile.beginAttachmentSpillTransaction(store)
        transaction.load(ByteArrayInputStream(vault.bytes), vault.password)

        val error = runCatching { transaction.purge() }.exceptionOrNull()
        assertTrue("删除失败必须可见", error is IllegalStateException)
        assertEquals("失败 key 必须保留", 1, store.entries.size)

        assertEquals("后续安全清扫应完成回收", 0, AttachmentSpillTransaction.retryPendingCleanup())
        assertTrue(store.entries.isEmpty())
        assertFalse("成功重试后事务应退出待清理登记", AttachmentSpillTransaction.retryPendingCleanup() > 0)
    }
}
