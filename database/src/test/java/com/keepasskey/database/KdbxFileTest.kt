package com.keepasskey.database

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.crypto.VariantDictionary
import com.keepasskey.database.file.HmacBlockStream
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

class KdbxFileTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testVariantDictionaryRoundtrip() {
        val vd = VariantDictionary()
        vd.setString("name", "KeePasskey")
        vd.setUInt32("version", 0x13L)
        vd.setUInt64("rounds", 600000L)
        vd.setBool("enabled", true)
        vd.setInt32("iconId", 42)
        vd.setInt64("timestamp", 1234567890123L)
        vd.setByteArray("salt", byteArrayOf(1, 2, 3, 4, 5))

        val bytes = vd.toByteArray()
        val deserialized = VariantDictionary.deserialize(bytes)

        assertEquals("KeePasskey", deserialized.getString("name"))
        assertEquals(0x13L, deserialized.getUInt32("version"))
        assertEquals(600000L, deserialized.getUInt64("rounds"))
        assertEquals(true, deserialized.getBool("enabled"))
        assertEquals(42, deserialized.getInt32("iconId"))
        assertEquals(1234567890123L, deserialized.getInt64("timestamp"))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), deserialized.getByteArray("salt"))
    }

    @Test
    fun testHmacBlockStreamRoundtrip() {
        val hmacKey = ByteArray(64) { (it * 7).toByte() }
        val testData = "HmacBlockStream authenticated block stream test payload".toByteArray(StandardCharsets.UTF_8)

        val bos = ByteArrayOutputStream()
        HmacBlockStream.writeAll(testData, bos, hmacKey, blockSize = 16)

        val encoded = bos.toByteArray()
        val bis = ByteArrayInputStream(encoded)
        val decoded = HmacBlockStream.readAll(bis, hmacKey)

        assertArrayEquals(testData, decoded)
    }

    @Test
    fun testHmacBlockStreamTamperDetection() {
        val hmacKey = ByteArray(64) { 1 }
        val testData = "Secret authenticated data".toByteArray(StandardCharsets.UTF_8)

        val bos = ByteArrayOutputStream()
        HmacBlockStream.writeAll(testData, bos, hmacKey)
        val encoded = bos.toByteArray()

        // 篡改首个数据块的 HMAC 签名
        encoded[0] = (encoded[0].toInt() xor 0xFF).toByte()

        val bis = ByteArrayInputStream(encoded)
        try {
            HmacBlockStream.readAll(bis, hmacKey)
            fail("应当抛出 HMAC 篡改校验异常")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("HMAC") == true)
        }
    }

    @Test
    fun testKdbxFileArgon2Roundtrip() {
        val password = "MyMasterPassword@2026".toCharArray()

        val header = KdbxHeader.createDefault(useArgon2 = true)
        // 缩减测试内存与轮数以加速单测
        val fastKdf = (header.kdfParameters as KdfParameters.Argon2).copy(
            memoryInBytes = 1024L * 1024L,
            iterations = 1L
        )
        val testHeader = header.copy(kdfParameters = fastKdf)

        val entry1 = KdbxEntry(
            id = KdbxUuid.random(),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("GitHub Account", isProtected = false),
                KdbxConstants.Fields.USER_NAME to ProtectedString("developer", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("p@ssw0rd!SuperSecret", isProtected = true),
                KdbxConstants.Fields.URL to ProtectedString("https://github.com", isProtected = false),
                KdbxConstants.Fields.NOTES to ProtectedString("Important note", isProtected = false)
            ),
            customFields = listOf(
                KdbxCustomField("PIN", ProtectedString("9876", isProtected = true))
            ),
            tags = listOf("dev", "git")
        )

        val rootGroup = KdbxGroup(
            id = KdbxUuid.random(),
            name = "RootGroup",
            entries = listOf(entry1)
        )

        val database = KdbxDatabase(
            header = testHeader,
            databaseName = "MySecureVault",
            databaseDescription = "Test Vault",
            rootGroup = rootGroup
        )

        val fileOut = ByteArrayOutputStream()
        KdbxFile.save(fileOut, database, password)

        val fileBytes = fileOut.toByteArray()
        assertTrue("文件尺寸应当大于头部与签名", fileBytes.size > 200)

        // 读取与校验
        val loadedDb = KdbxFile.load(ByteArrayInputStream(fileBytes), password)

        assertEquals("MySecureVault", loadedDb.databaseName)
        assertEquals("Test Vault", loadedDb.databaseDescription)
        assertEquals("RootGroup", loadedDb.rootGroup.name)
        assertEquals(1, loadedDb.rootGroup.entries.size)

        val loadedEntry = loadedDb.rootGroup.entries[0]
        assertEquals("GitHub Account", loadedEntry.title)
        assertEquals("developer", loadedEntry.userName)
        assertEquals("p@ssw0rd!SuperSecret", loadedEntry.password?.readString())
        assertEquals("https://github.com", loadedEntry.url)
        assertEquals("Important note", loadedEntry.notes)
        assertEquals(listOf("dev", "git"), loadedEntry.tags)
        assertEquals(1, loadedEntry.customFields.size)
        assertEquals("PIN", loadedEntry.customFields[0].key)
        assertEquals("9876", loadedEntry.customFields[0].value.readString())

        // 测试错误密码拒绝
        try {
            KdbxFile.load(ByteArrayInputStream(fileBytes), "WrongPassword".toCharArray())
            fail("错误密码必须抛出异常")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("密码") == true || e.message?.contains("HMAC") == true)
        }
    }

    @Test
    fun testKdbxFileAesKdfRoundtrip() {
        val password = "AesPassword123".toCharArray()
        val header = KdbxHeader.createDefault(useArgon2 = false)
        val testHeader = header.copy(
            kdfParameters = KdfParameters.Aes(seed = ByteArray(32) { 9 }, rounds = 50L)
        )

        val entry = KdbxEntry(
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Email", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("MailPass!99", isProtected = true)
            )
        )
        val db = KdbxDatabase(
            header = testHeader,
            databaseName = "AesVault",
            rootGroup = KdbxGroup(name = "Root", entries = listOf(entry))
        )

        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, db, password)

        val loaded = KdbxFile.load(ByteArrayInputStream(bos.toByteArray()), password)
        assertEquals("AesVault", loaded.databaseName)
        assertEquals("Email", loaded.rootGroup.entries[0].title)
        assertEquals("MailPass!99", loaded.rootGroup.entries[0].password?.readString())
    }

    @Test
    fun testDatabaseSessionAndAtomicFileWriter() = runBlocking {
        val testFile = File(tempFolder.root, "test_vault.kdbx")
        val session = DatabaseSession()

        assertEquals(DatabaseSession.SessionState.CLOSED, session.state.value)

        // 1. 创建数据库
        val password = "SessionPassword123".toCharArray()
        val createResult = session.create(testFile, "SessionVault", password, useArgon2 = false)
        assertTrue(createResult.isSuccess)
        assertEquals(DatabaseSession.SessionState.OPENED, session.state.value)
        assertTrue(testFile.exists())

        // 2. 新增条目
        val newEntry = KdbxEntry(
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Banking", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("Bank1234", isProtected = true)
            )
        )
        session.saveEntry(newEntry)
        assertEquals(DatabaseSession.SessionState.DIRTY, session.state.value)

        // 3. 保存数据库
        val saveResult = session.save()
        assertTrue(saveResult.isSuccess)
        assertEquals(DatabaseSession.SessionState.OPENED, session.state.value)

        // 验证 .bak 备份文件存在
        val bakFile = File(tempFolder.root, "test_vault.kdbx.bak")
        assertTrue("保存后应当自动保留 .bak 备份", bakFile.exists())

        // 4. 锁定
        session.lock()
        assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)
        assertEquals(null, session.databaseFlow.value)

        // 5. 重新解锁打开
        val openResult = session.open(testFile, password)
        assertTrue(openResult.isSuccess)
        assertEquals(DatabaseSession.SessionState.OPENED, session.state.value)
        val loadedEntries = session.databaseFlow.value?.rootGroup?.allEntries()
        assertNotNull(loadedEntries)
        assertEquals(1, loadedEntries!!.size)
        assertEquals("Banking", loadedEntries[0].title)
        assertEquals("Bank1234", loadedEntries[0].password?.readString())

        // 6. 关闭
        session.close()
        assertEquals(DatabaseSession.SessionState.CLOSED, session.state.value)
    }

    /**
     * P1-10 回归锁：仅密钥文件（无主密码）的会话全生命周期——
     * null 密码解锁 → 无密码分量的保存（passwordCache 为 null、keyFileCache 在场）→ 重解锁读取。
     */
    @Test
    fun testDatabaseSessionKeyFileOnlyLifecycle() = runBlocking {
        val testFile = File(tempFolder.root, "keyfile_only_vault.kdbx")
        val keyFileData = ByteArray(32) { (it * 17 + 3).toByte() }
        val session = DatabaseSession()

        // 1. 以仅密钥文件（无主密码分量）直接落盘建库
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            databaseName = "KeyFileOnlySession",
            rootGroup = KdbxGroup(name = "Root")
        )
        testFile.outputStream().use { fos ->
            KdbxFile.save(fos, db, null, keyFileData)
        }

        // 2. null 密码 + 密钥文件解锁会话
        val openResult = session.open(testFile, null, keyFileData)
        assertTrue(openResult.isSuccess)
        assertEquals(DatabaseSession.SessionState.OPENED, session.state.value)

        // 3. 新增受保护条目并保存（仅密钥文件会话的保存路径）
        session.saveEntry(
            KdbxEntry(
                fields = mapOf(
                    KdbxConstants.Fields.TITLE to ProtectedString("KeyFileOnlySessionEntry", isProtected = false),
                    KdbxConstants.Fields.PASSWORD to ProtectedString("SessionSecret!99", isProtected = true)
                )
            )
        )
        val saveResult = session.save()
        assertTrue(saveResult.isSuccess)

        // 4. 锁定后仅密钥文件重新解锁，条目与受保护字段完整
        session.lock()
        val reopenResult = session.open(testFile, null, keyFileData)
        assertTrue(reopenResult.isSuccess)
        val entries = session.databaseFlow.value?.rootGroup?.allEntries()
        assertNotNull(entries)
        assertEquals(1, entries!!.size)
        assertEquals("KeyFileOnlySessionEntry", entries[0].title)
        assertEquals("SessionSecret!99", entries[0].password?.readString())

        session.close()
        assertEquals(DatabaseSession.SessionState.CLOSED, session.state.value)
    }
}
