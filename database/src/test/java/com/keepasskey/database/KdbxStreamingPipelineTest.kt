package com.keepasskey.database

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.exception.KdbxInvalidCredentialsException
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * KDBX 全链路流式读写管线专项测试。
 * 使用超过单个 HMAC 块（1MB）的附件数据，验证多块切分/逐块校验与跨块往返一致性。
 */
class KdbxStreamingPipelineTest {

    private val testPassword = "StreamingPipeline#2026".toCharArray()

    /** 2.5MB：跨越两个 1MB HMAC 块，并覆盖边界对齐场景 */
    private val largeAttachmentSize = 2_500_000

    @Test
    fun testMultiBlockRoundtrip() {
        val attachmentData = ByteArray(largeAttachmentSize)
        Random(42).nextBytes(attachmentData)

        val db = buildDatabaseWithAttachment(attachmentData)
        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, db, testPassword)

        val loaded = KdbxFile.load(ByteArrayInputStream(bos.toByteArray()), testPassword)

        assertEquals(db.rootGroup.entries.size, loaded.rootGroup.entries.size)
        val loadedEntry = loaded.rootGroup.entries.first()
        assertEquals("LargeEntry", loadedEntry.fields[KdbxConstants.Fields.TITLE]?.readString())
        assertEquals(1, loadedEntry.attachments.size)

        val loadedAttachment = loadedEntry.attachments.first()
        assertEquals("big.bin", loadedAttachment.name)
        assertArrayEquals(attachmentData, loadedAttachment.data)
    }

    @Test
    fun testMultiBlockWrongPasswordThrowsInvalidCredentials() {
        val attachmentData = ByteArray(largeAttachmentSize)
        Random(42).nextBytes(attachmentData)

        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, buildDatabaseWithAttachment(attachmentData), testPassword)

        assertThrows(KdbxInvalidCredentialsException::class.java) {
            KdbxFile.load(ByteArrayInputStream(bos.toByteArray()), "WrongPassword".toCharArray())
        }
    }

    @Test
    fun testExactlyOneBlockPayloadRoundtrip() {
        // 恰好一个 HMAC 块的附件（1MB），验证块边界精确对齐
        val attachmentData = ByteArray(1024 * 1024)
        Random(7).nextBytes(attachmentData)

        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, buildDatabaseWithAttachment(attachmentData), testPassword)

        val loaded = KdbxFile.load(ByteArrayInputStream(bos.toByteArray()), testPassword)
        assertArrayEquals(attachmentData, loaded.rootGroup.entries.first().attachments.first().data)
    }

    private fun buildDatabaseWithAttachment(attachmentData: ByteArray): KdbxDatabase {
        val entry = KdbxEntry(
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("LargeEntry", isProtected = false)),
            attachments = listOf(
                KdbxAttachment(name = "big.bin", refIndex = 0, isProtected = true, data = attachmentData)
            )
        )
        return KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root", entries = listOf(entry))
        )
    }
}
