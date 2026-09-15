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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream

/**
 * ISSUE-P2-67 回归：**同步路径解析远端库时，大附件必须落盘而非内联进堆**。
 *
 * 缺陷形态：`SyncDatabaseCodec` 直接调 `KdbxFile.load(...)` 而**未传** `binaryStore`，
 * 于是远端库里超过落盘阈值的附件无论多大都内联在 `InnerHeader` 池中（解密态明文常驻），
 * 随后仅置空引用、从不零化。整改后解析收口到 [DatabaseSession.parseExternalDatabase]，
 * 由构造关系保证「与主会话同一个 store」。
 *
 * 断言口径（对齐 AC②「>1 MiB 远程附件解析后落盘而非内联」）：
 * 1. 解析期间 store 必须收到写入（落盘发生）；
 * 2. 解析树中的附件必须**不持有**内联副本（`inlineBytes()` 为空、`binarySource()` 非空）；
 * 3. 落盘字节读回后与原文逐字一致（落盘不得损坏内容）。
 */
class ExternalDatabaseParseSpillTest {

    /** 内存版 [BinaryStore] 替身：只记录键与字节，足以判定「落盘 / 未落盘」。 */
    private class RecordingBinaryStore : BinaryStore {
        val keys = mutableListOf<String>()
        private val entries = LinkedHashMap<String, ByteArray>()
        private var counter = 0

        override fun store(bytes: ByteArray): String {
            val key = "k${counter++}"
            entries[key] = bytes.copyOf()
            keys += key
            return key
        }

        override fun storeFromStream(input: InputStream, size: Long): String {
            val bytes = ByteArray(size.toInt())
            DataInputStream(input).readFully(bytes)
            val key = "k${counter++}"
            entries[key] = bytes
            keys += key
            return key
        }

        override fun load(key: String): ByteArray =
            entries[key]?.copyOf() ?: throw IllegalStateException("store 中不存在 key=$key")

        override fun openStream(key: String): InputStream = ByteArrayInputStream(load(key))

        override fun sizeOf(key: String): Long = (entries[key]?.size ?: 0).toLong()

        override fun clear() {
            entries.values.forEach { it.fill(0) }
            entries.clear()
        }
    }

    private val password = "SyncSpill#2026".toCharArray()

    @Test
    fun `同步路径解析的远端大附件必须落盘而非内联进堆`() = runBlocking {
        val store = RecordingBinaryStore()
        val session = DatabaseSession(store)
        val remoteBytes = dbBytesWithBigAttachment()

        // 建立会话凭据（`parseExternalDatabase` 需要凭据克隆）
        assertTrue(
            "会话打开失败，用例前提不成立",
            session.openStream(
                pathIdentifier = "local.kdbx",
                inputStreamProvider = { ByteArrayInputStream(remoteBytes) },
                passwordChars = password
            ).isSuccess
        )

        // 隔离本次解析：只观察「同步路径解析」产生的落盘
        store.keys.clear()
        val parsedDb = session.parseExternalDatabase(remoteBytes).getOrThrow()

        val attachment = parsedDb.rootGroup.allEntries().first().attachments.first()
        assertTrue(
            "解析远端库必须把 > 落盘阈值的附件写入会话同一个 BinaryStore（否则即内联进堆）",
            store.keys.isNotEmpty()
        )
        assertTrue(
            "落盘附件不得在解析树中保留内联副本（inlineData 应为空）",
            attachment.inlineBytes().isEmpty()
        )
        assertTrue(
            "落盘附件必须携带 BinarySource 引用（按需读回）",
            attachment.binarySource() != null
        )
        assertEquals("附件字节数须与原文一致", ATTACHMENT_SIZE.toLong(), attachment.size)
        val readBack = attachment.data
        assertTrue(
            "落盘后再读回的字节须与原文逐字一致（落盘不得损坏内容）",
            readBack.size == ATTACHMENT_SIZE && readBack.all { it == ATTACHMENT_FILL }
        )
    }

    /** 生成含 > 1 MiB 附件（解析期必然落盘）的单条目库字节。 */
    private fun dbBytesWithBigAttachment(): ByteArray {
        val entry = KdbxEntry(
            fields = linkedMapOf(
                KdbxConstants.Fields.PASSWORD to ProtectedString("Remote#2026", isProtected = true)
            ),
            attachments = listOf(
                KdbxAttachment(
                    name = "big.bin",
                    data = ByteArray(ATTACHMENT_SIZE) { ATTACHMENT_FILL }
                )
            )
        )
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root", entries = listOf(entry))
        )
        return ByteArrayOutputStream().also { KdbxFile.save(it, db, password) }.toByteArray()
    }

    private companion object {
        /** 严格大于 `BinaryStorePolicy.DEFAULT_THRESHOLD_BYTES`（1 MiB），确保解析期落盘。 */
        const val ATTACHMENT_SIZE = 1_200_000

        const val ATTACHMENT_FILL: Byte = 7
    }
}
