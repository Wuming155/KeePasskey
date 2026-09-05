package com.keepasskey.database

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.cipher.CipherFactory
import com.keepasskey.crypto.hash.HashUtil
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.exception.KdbxInvalidCredentialsException
import com.keepasskey.database.exception.KdbxUnsupportedVersionException
import com.keepasskey.database.file.HmacBlockStream
import com.keepasskey.database.file.InnerHeader
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.io.LittleEndianUtil
import com.keepasskey.database.xml.KdbxXmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * 官方兼容性、旧派生兼容回退与类型化异常单测。
 */
class KdbxCompatibilityAndSecurityTest {

    @Test
    fun testDeriveKeysOfficialVsLegacy() {
        val header = KdbxHeader.createDefault()
        val password = "StrongPassword@2026".toCharArray()

        val (officialCipher, officialHmac) = KdbxFile.deriveKeys(header, password, null, isLegacy = false)
        val (legacyCipher, legacyHmac) = KdbxFile.deriveKeys(header, password, null, isLegacy = true)

        // HMAC-SHA512 在两者间保持一致
        assertTrue(officialHmac.contentEquals(legacyHmac))
        assertEquals(64, officialHmac.size)

        // cipherKey 长度均为 32 字节，但算法不同（SHA-512 截断 vs SHA-256）
        assertEquals(32, officialCipher.size)
        assertEquals(32, legacyCipher.size)
        assertFalse(officialCipher.contentEquals(legacyCipher))
    }

    @Test
    fun testLegacyCipherKeyFallbackAndAutoMigration() {
        val password = "LegacyPassword123".toCharArray()
        val header = KdbxHeader.createDefault(useArgon2 = false)

        val entry = KdbxEntry(
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("LegacyVaultItem"),
                KdbxConstants.Fields.PASSWORD to ProtectedString("LegacySecretPass", isProtected = true)
            )
        )
        val originalDb = KdbxDatabase(
            header = header,
            databaseName = "LegacyVault",
            rootGroup = KdbxGroup(name = "Root", entries = listOf(entry))
        )

        // 构造使用旧派生 (SHA-256) 加密的数据库字节流
        val legacyStream = ByteArrayOutputStream()
        writeLegacyKdbx(legacyStream, originalDb, password)
        val legacyBytes = legacyStream.toByteArray()

        // 1. 读取旧派生库：验证自动回退生效并成功打开
        val loadedDb = KdbxFile.load(ByteArrayInputStream(legacyBytes), password)
        assertEquals("LegacyVault", loadedDb.databaseName)
        assertEquals(1, loadedDb.rootGroup.entries.size)
        assertEquals("LegacyVaultItem", loadedDb.rootGroup.entries[0].title)
        assertEquals("LegacySecretPass", loadedDb.rootGroup.entries[0].password?.readString())

        // 2. 保存旧库：验证保存时自动迁移为官方派生
        val migratedStream = ByteArrayOutputStream()
        KdbxFile.save(migratedStream, loadedDb, password)
        val migratedBytes = migratedStream.toByteArray()

        // 3. 再次读取迁移后的文件：验证官方标准可直接打开
        val reopenedDb = KdbxFile.load(ByteArrayInputStream(migratedBytes), password)
        assertEquals("LegacyVault", reopenedDb.databaseName)
        assertEquals("LegacyVaultItem", reopenedDb.rootGroup.entries[0].title)
    }

    @Test
    fun testWrongPasswordThrowsKdbxInvalidCredentialsException() {
        val password = "CorrectPassword".toCharArray()
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root")
        )

        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, db, password)

        assertThrows(KdbxInvalidCredentialsException::class.java) {
            KdbxFile.load(ByteArrayInputStream(bos.toByteArray()), "IncorrectPassword".toCharArray())
        }
    }

    @Test
    fun testTamperedHeaderShaThrowsKdbxCorruptFileException() {
        val password = "Password".toCharArray()
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root")
        )

        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, db, password)
        val bytes = bos.toByteArray()

        // 篡改头部 SHA-256 的字节
        // 查找头字节长度并修改后续 SHA 存储区
        val (header, headerBytes) = KdbxHeader.deserialize(ByteArrayInputStream(bytes))
        val shaOffset = headerBytes.size
        bytes[shaOffset] = (bytes[shaOffset].toInt() xor 0xFF).toByte()

        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxFile.load(ByteArrayInputStream(bytes), password)
        }
    }

    @Test
    fun testInvalidSignatureThrowsKdbxCorruptFileException() {
        val bytes = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x00, 0x00, 0x00, 0x00)
        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxFile.load(ByteArrayInputStream(bytes), "pwd".toCharArray())
        }
    }

    @Test
    fun testUnsupportedVersionThrowsKdbxUnsupportedVersionException() {
        val bos = ByteArrayOutputStream()
        // 写入合法签名 1 与 2
        LittleEndianUtil.writeInt(bos, KdbxConstants.Signature.SIGNATURE_1)
        LittleEndianUtil.writeInt(bos, KdbxConstants.Signature.SIGNATURE_2_KDBX)
        // 写入 KDBX 3.1 主版本号 (0x00030001)
        LittleEndianUtil.writeInt(bos, KdbxConstants.Version.VERSION_3_1)

        assertThrows(KdbxUnsupportedVersionException::class.java) {
            KdbxFile.load(ByteArrayInputStream(bos.toByteArray()), "pwd".toCharArray())
        }
    }

    @Test
    fun testCorruptHmacBlockThrowsKdbxInvalidCredentialsException() {
        val password = "Password123".toCharArray()
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root")
        )

        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, db, password)
        val bytes = bos.toByteArray()

        // 篡改最后一个数据块（尾部倒数 10 字节）
        bytes[bytes.size - 10] = (bytes[bytes.size - 10].toInt() xor 0xFF).toByte()

        assertThrows(KdbxInvalidCredentialsException::class.java) {
            KdbxFile.load(ByteArrayInputStream(bytes), password)
        }
    }

    private fun writeLegacyKdbx(outputStream: ByteArrayOutputStream, db: KdbxDatabase, password: CharArray) {
        val innerHeader = InnerHeader.createDefault()
        val innerCipher = InnerRandomStreamCipher(
            innerHeader.innerRandomStreamId,
            innerHeader.innerRandomStreamKey
        )

        val xmlBos = ByteArrayOutputStream()
        KdbxXmlSerializer(innerCipher).serialize(xmlBos, db)
        val xmlBytes = xmlBos.toByteArray()

        val payloadBos = ByteArrayOutputStream()
        GZIPOutputStream(payloadBos).use { gzip ->
            innerHeader.serialize(gzip)
            gzip.write(xmlBytes)
        }
        val payload = payloadBos.toByteArray()

        val header = db.header
        val (legacyCipherKey, hmacKey64) = KdbxFile.deriveKeys(header, password, null, isLegacy = true)

        val cipherEngine = CipherFactory.getEngine(header.cipherUuid)
        val encryptedPayload = cipherEngine.encrypt(legacyCipherKey, header.encryptionIv, payload)

        val headerBytesStream = ByteArrayOutputStream()
        val headerBytes = header.serialize(headerBytesStream)
        outputStream.write(headerBytes)

        val headerSha = HashUtil.sha256(headerBytes)
        outputStream.write(headerSha)

        val headerHmacKey = HashUtil.sha512(LittleEndianUtil.longTo8Bytes(0xFFFFFFFFFFFFFFFFUL.toLong()), hmacKey64)
        val headerHmac = HashUtil.hmacSha256(headerHmacKey, headerBytes)
        outputStream.write(headerHmac)

        HmacBlockStream.writeAll(encryptedPayload, outputStream, hmacKey64)
    }
}
