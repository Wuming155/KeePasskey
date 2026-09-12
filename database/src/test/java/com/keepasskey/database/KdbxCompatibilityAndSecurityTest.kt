package com.keepasskey.database

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.cipher.CipherFactory
import com.keepasskey.crypto.hash.HashUtil
import com.keepasskey.crypto.kdf.KdfFactory
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
import org.junit.Assert.assertArrayEquals
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

    /**
     * D20 回归锁：**数据块/终止块** HMAC 不符必须报「完整性失败」（[KdbxCorruptFileException]），
     * 与官方语义一致（KeePass 2.61.1 `HmacBlockStream.cs:233/264` 抛
     * `InvalidDataException(FileCorrupted)`）；同时**必须不是**凭据异常。
     *
     * 为什么「不是凭据异常」是独立的语义断言：头部 HMAC 与本块 HMAC 使用同一 hmacKey64，
     * 头部 HMAC 通过即已证明凭据正确；若此处仍报凭据错误，上层
     * （`UnlockViewModel`）会把「文件被篡改」显示为「主密码错误」**并计入解锁失败节流**，
     * 用户永远无法得知库已被篡改。
     */
    @Test
    fun testCorruptHmacBlockThrowsCorruptFileNotInvalidCredentials() {
        val password = "Password123".toCharArray()
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root")
        )

        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, db, password)
        val bytes = bos.toByteArray()

        // 篡改终止块 HMAC 校验和区域（文件末尾 36 字节 = 32B HMAC + 4B blockSize(0)）
        bytes[bytes.size - 20] = (bytes[bytes.size - 20].toInt() xor 0xFF).toByte()

        val failure = assertThrows(KdbxCorruptFileException::class.java) {
            KdbxFile.load(ByteArrayInputStream(bytes), password)
        }
        // Kotlin 拒绝「对已定型的类型再判其兄弟类型」（IMPOSSIBLE_IS_CHECK_ERROR），
        // 故先向上转型到二者共同基类 IOException 再判——语义与直接判 is 完全一致
        val failureAsIo: java.io.IOException = failure
        assertFalse(
            "数据/终止块 HMAC 失败绝不能被判定为凭据错误（否则会被计入解锁失败节流）",
            failureAsIo is KdbxInvalidCredentialsException
        )
        assertTrue(
            "异常文案须体现文件损坏/篡改而非主密码错误：${failure.message}",
            failure.message!!.contains("损坏") || failure.message!!.contains("篡改")
        )
    }

    /**
     * D20 回归锁：**首个数据块** HMAC 不符同样属完整性失败。
     * 该路径在 `readBlock()`（旧派生裁决探针取首块）中即抛出，发生在
     * cipherKey 探针与 XML 解析之前，但已在头部 HMAC 通过之后。
     */
    @Test
    fun testCorruptFirstDataBlockHmacThrowsCorruptFile() {
        val password = "FirstBlockPass#2026".toCharArray()
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            databaseName = "FirstBlockVault",
            rootGroup = KdbxGroup(name = "Root")
        )

        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, db, password)
        val bytes = bos.toByteArray()

        // 头部长度 = 序列化头部 + 32B SHA-256 + 32B HMAC；其后即第 0 个数据块的 32B HMAC
        val (_, headerBytes) = KdbxHeader.deserialize(ByteArrayInputStream(bytes))
        val firstBlockHmacOffset = headerBytes.size + 32 + 32
        bytes[firstBlockHmacOffset] = (bytes[firstBlockHmacOffset].toInt() xor 0xFF).toByte()

        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxFile.load(ByteArrayInputStream(bytes), password)
        }
    }

    /**
     * D20 回归锁（负向对照）：**错误口令**必须仍然得到 [KdbxInvalidCredentialsException]。
     *
     * 理由：hmacKey64 由复合密钥派生，错误凭据在**头部 HMAC**（唯一凭据出口）即失败，
     * 永远走不到数据块校验。若本用例改为 `KdbxCorruptFileException`，说明
     * 「凭据错误」与「文件篡改」的分型被倒置，上层将不再计入解锁失败节流——
     * 暴力破解防线被静默拆除。真实官方语料侧的同类断言见
     * [RealKdbxInteroperabilityTest.loadRealVaultWithWrongPassword_failsWithInvalidCredentials]。
     */
    @Test
    fun testWrongPasswordStillThrowsInvalidCredentials() {
        val password = "CorrectPassword#2026".toCharArray()
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root")
        )

        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, db, password)

        val failure = assertThrows(KdbxInvalidCredentialsException::class.java) {
            KdbxFile.load(ByteArrayInputStream(bos.toByteArray()), "WrongPassword#2026".toCharArray())
        }
        assertTrue(
            "凭据错误必须仍是凭据异常：${failure::class.java.simpleName}",
            failure is KdbxInvalidCredentialsException
        )
    }

    /**
     * D20：篡改**首个数据块的密文**（块 HMAC 随之不符）同样必须在头部 HMAC 通过后
     * 以完整性异常收场，且不得退化为凭据异常。
     */
    @Test
    fun testTamperedFirstBlockPayloadIsNotCredentialFailure() {
        val password = "TamperPayload#2026".toCharArray()
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            databaseName = "TamperVault",
            rootGroup = KdbxGroup(name = "Root")
        )

        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, db, password)
        val bytes = bos.toByteArray()

        val (_, headerBytes) = KdbxHeader.deserialize(ByteArrayInputStream(bytes))
        // 定位第 0 个数据块数据区：头部 + SHA(32) + HMAC(32) + 块 HMAC(32) + blockSize(4)
        val firstBlockDataOffset = headerBytes.size + 32 + 32 + 32 + 4
        bytes[firstBlockDataOffset] = (bytes[firstBlockDataOffset].toInt() xor 0xFF).toByte()

        val failure = assertThrows(KdbxCorruptFileException::class.java) {
            KdbxFile.load(ByteArrayInputStream(bytes), password)
        }
        val failureAsIo: java.io.IOException = failure
        assertFalse(
            "篡改数据块绝不能被判定为凭据错误",
            failureAsIo is KdbxInvalidCredentialsException
        )
    }


    /**
     * P0-4 回归锁：以 ChaCha20 保存的库，落盘外层 Header 的 EncryptionIV 字段必须为 12 字节
     * （官方 KeePass 2.61.1 ChaCha20Cipher 构造器硬校验 / RFC 7539）；
     * AES-256-CBC 默认库保持 16 字节。若回退到「恒写 16 字节」旧行为，本用例必须失败。
     */
    @Test
    fun testChaCha20SaveWrites12ByteEncryptionIv() {
        val password = "ChaChaVault@2026".toCharArray()
        val header = KdbxHeader.createDefault(
            cipherUuid = KdbxConstants.Cipher.CHACHA20,
            useArgon2 = false
        )
        val db = KdbxDatabase(
            header = header,
            databaseName = "ChaChaVault",
            rootGroup = KdbxGroup(
                name = "Root",
                entries = listOf(
                    KdbxEntry(
                        fields = mapOf(
                            KdbxConstants.Fields.TITLE to ProtectedString("ChaChaEntry"),
                            KdbxConstants.Fields.PASSWORD to ProtectedString("ChaChaSecret!42", isProtected = true)
                        )
                    )
                )
            )
        )

        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, db, password)
        val bytes = bos.toByteArray()

        // 直接从落盘字节解析外层 Header，锁定 EncryptionIV 字段长度
        val (savedHeader, _) = KdbxHeader.deserialize(ByteArrayInputStream(bytes))
        assertEquals(KdbxConstants.Cipher.CHACHA20, savedHeader.cipherUuid)
        assertEquals(KdbxConstants.Cipher.CHACHA20_NONCE_LENGTH, savedHeader.encryptionIv.size)

        // 完整往返：受保护字段经 12 字节 nonce 正确解密
        val loaded = KdbxFile.load(ByteArrayInputStream(bytes), password)
        assertEquals("ChaChaEntry", loaded.rootGroup.entries[0].title)
        assertEquals("ChaChaSecret!42", loaded.rootGroup.entries[0].password?.readString())

        // 对照：AES-256-CBC 默认库保持 16 字节 IV
        val aesDb = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            databaseName = "AesVault",
            rootGroup = KdbxGroup(name = "Root")
        )
        val aesBos = ByteArrayOutputStream()
        KdbxFile.save(aesBos, aesDb, password)
        val (aesHeader, _) = KdbxHeader.deserialize(ByteArrayInputStream(aesBos.toByteArray()))
        assertEquals(KdbxConstants.Cipher.BLOCK_CIPHER_IV_LENGTH, aesHeader.encryptionIv.size)
    }

    /**
     * P1-10 回归锁：复合密钥必须逐字节对齐官方 KeePass 2.61.1 CompositeKey.CreateRawCompositeKey32
     * ——只拼接实际存在的凭据分量后整体 SHA-256。此处用独立手算向量锁定三种凭据组合，
     * 并埋入「旧缺陷公式（仅密钥文件时拼入 SHA-256("")）」不等性陷阱：
     * 若 deriveKeys 回退到旧行为，本用例必须失败。
     */
    @Test
    fun testCompositeKeyMatchesOfficialFormula() {
        val header = KdbxHeader.createDefault(useArgon2 = false)
        val passwordUtf8 = "KeyFileOnlyPass@123".toByteArray(Charsets.UTF_8)
        val password = "KeyFileOnlyPass@123".toCharArray()
        // 官方裸 32 字节密钥文件：LoadKeyFile 语义下密钥即文件内容本身
        val keyFileData = ByteArray(32) { (it * 11 + 7).toByte() }
        val kdfEngine = KdfFactory.getEngine(header.kdfParameters.kdfUuid)

        // —— 仅密钥文件（无主密码）：composite = SHA-256(keyFileKey) ——
        val (keyFileOnlyCipherKey, _) = KdbxFile.deriveKeys(header, null, keyFileData)
        val expectedTransformed = kdfEngine.transform(HashUtil.sha256(keyFileData), header.kdfParameters)
        val expectedCipherKey = HashUtil.sha256(header.masterSeed, expectedTransformed)
        assertArrayEquals(expectedCipherKey, keyFileOnlyCipherKey)

        // 旧缺陷向量：SHA-256(SHA-256("") ‖ keyFileKey) 必须派生出不同密钥（回归陷阱）
        val buggyComposite = HashUtil.sha256(HashUtil.sha256(ByteArray(0)), keyFileData)
        val buggyTransformed = kdfEngine.transform(buggyComposite, header.kdfParameters)
        assertFalse(HashUtil.sha256(header.masterSeed, buggyTransformed).contentEquals(keyFileOnlyCipherKey))

        // 空密码数组与 null 等价（官方解锁框对空密码不添加密码分量）
        val (emptyPwdCipherKey, _) = KdbxFile.deriveKeys(header, CharArray(0), keyFileData)
        assertArrayEquals(keyFileOnlyCipherKey, emptyPwdCipherKey)

        // —— 仅主密码：composite = SHA-256(SHA-256(password)) ——
        val (passwordOnlyCipherKey, _) = KdbxFile.deriveKeys(header, password, null)
        val pwdTransformed = kdfEngine.transform(
            HashUtil.sha256(HashUtil.sha256(passwordUtf8)),
            header.kdfParameters
        )
        assertArrayEquals(HashUtil.sha256(header.masterSeed, pwdTransformed), passwordOnlyCipherKey)

        // —— 密码 + 密钥文件：composite = SHA-256(SHA-256(password) ‖ keyFileKey) ——
        val (combinedCipherKey, _) = KdbxFile.deriveKeys(header, password, keyFileData)
        val combinedTransformed = kdfEngine.transform(
            HashUtil.sha256(HashUtil.sha256(passwordUtf8), keyFileData),
            header.kdfParameters
        )
        assertArrayEquals(HashUtil.sha256(header.masterSeed, combinedTransformed), combinedCipherKey)
    }

    /**
     * P1-10 回归锁：仅密钥文件（无主密码）库的完整读写往返——
     * null/空密码 + 密钥文件可解锁；错误密码或缺失密钥文件必须凭据失败。
     */
    @Test
    fun testKeyFileOnlyDatabaseRoundtrip() {
        val keyFileData = ByteArray(32) { (it * 13 + 5).toByte() }
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            databaseName = "KeyFileOnlyVault",
            rootGroup = KdbxGroup(
                name = "Root",
                entries = listOf(
                    KdbxEntry(
                        fields = mapOf(
                            KdbxConstants.Fields.TITLE to ProtectedString("KeyFileOnlyEntry"),
                            KdbxConstants.Fields.PASSWORD to ProtectedString("NoMasterPassword!777", isProtected = true)
                        )
                    )
                )
            )
        )

        // 仅密钥文件保存（无主密码分量）
        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, db, null, keyFileData)
        val bytes = bos.toByteArray()

        // null 密码 + 密钥文件：解锁成功，受保护字段完整
        val loaded = KdbxFile.load(ByteArrayInputStream(bytes), null, keyFileData)
        assertEquals("KeyFileOnlyVault", loaded.databaseName)
        assertEquals("KeyFileOnlyEntry", loaded.rootGroup.entries[0].title)
        assertEquals("NoMasterPassword!777", loaded.rootGroup.entries[0].password?.readString())

        // 空密码数组与 null 等价
        val loadedEmpty = KdbxFile.load(ByteArrayInputStream(bytes), CharArray(0), keyFileData)
        assertEquals("KeyFileOnlyVault", loadedEmpty.databaseName)

        // 错误密码 + 密钥文件：必须拒绝
        assertThrows(KdbxInvalidCredentialsException::class.java) {
            KdbxFile.load(ByteArrayInputStream(bytes), "WrongPassword".toCharArray(), keyFileData)
        }

        // 缺失密钥文件（无任何凭据分量）：必须拒绝
        assertThrows(KdbxInvalidCredentialsException::class.java) {
            KdbxFile.load(ByteArrayInputStream(bytes), null, null)
        }
    }

    /**
     * P3-1 回归锁：InnerRandomStreamID = 0 (None) 的合法 KDBX4 库必须能打开——
     * 受保护字段仅 Base64 编码、不做内层流加密，读出即明文。
     */
    @Test
    fun testInnerRandomStreamNoneFileRoundtrip() {
        val password = "NoneStreamPass@2026".toCharArray()
        val header = KdbxHeader.createDefault(useArgon2 = false)
        val db = KdbxDatabase(
            header = header,
            databaseName = "NoneStreamVault",
            rootGroup = KdbxGroup(
                name = "Root",
                entries = listOf(
                    KdbxEntry(
                        fields = mapOf(
                            KdbxConstants.Fields.TITLE to ProtectedString("NoneStreamEntry"),
                            KdbxConstants.Fields.PASSWORD to ProtectedString("PlainTextProtected!001", isProtected = true)
                        )
                    )
                )
            )
        )

        val bytes = buildKdbxBytes(db, password, KdbxConstants.InnerRandomStream.NONE)
        val loaded = KdbxFile.load(ByteArrayInputStream(bytes), password)

        assertEquals("NoneStreamVault", loaded.databaseName)
        assertEquals("NoneStreamEntry", loaded.rootGroup.entries[0].title)
        assertEquals("PlainTextProtected!001", loaded.rootGroup.entries[0].password?.readString())
    }

    /** 手工组装指定内层流类型的官方派生 KDBX 字节流（供内层流算法回归用例使用） */
    private fun buildKdbxBytes(db: KdbxDatabase, password: CharArray, innerStreamId: Int): ByteArray {
        val innerHeader = InnerHeader(
            innerRandomStreamId = innerStreamId,
            innerRandomStreamKey = ByteArray(64) { 7 }
        )
        val innerCipher = InnerRandomStreamCipher(innerStreamId, innerHeader.innerRandomStreamKey)

        val xmlBos = ByteArrayOutputStream()
        KdbxXmlSerializer(innerCipher).serialize(xmlBos, db)

        val payloadBos = ByteArrayOutputStream()
        GZIPOutputStream(payloadBos).use { gzip ->
            innerHeader.serialize(gzip)
            gzip.write(xmlBos.toByteArray())
        }

        val header = db.header
        val (cipherKey, hmacKey64) = KdbxFile.deriveKeys(header, password, null)
        val cipherEngine = CipherFactory.getEngine(header.cipherUuid)
        val encryptedPayload = cipherEngine.encrypt(cipherKey, header.encryptionIv, payloadBos.toByteArray())

        val out = ByteArrayOutputStream()
        val headerBytes = header.serialize(out)
        out.write(HashUtil.sha256(headerBytes))

        val headerHmacKey = HashUtil.sha512(
            LittleEndianUtil.longTo8Bytes(0xFFFFFFFFFFFFFFFFUL.toLong()),
            hmacKey64
        )
        out.write(HashUtil.hmacSha256(headerHmacKey, headerBytes))

        HmacBlockStream.writeAll(encryptedPayload, out, hmacKey64)
        return out.toByteArray()
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

    /**
     * HMAC 块尺寸上限回归（J 项整改）：读取侧单块尺寸不得超过 1 MB，
     * 防止恶意/损坏文件以超大 blockSize 触发大块内存分配（DoS）。
     * 构造仅含「32 字节伪 HMAC + 4 字节 blockSize(=2 MiB)」的流，
     * 该校验在读取 blockData 之前触发，故无需有效 HMAC。
     */
    @Test
    fun readAll_rejectsOversizedBlockSize() {
        val oversized = (2 * 1024 * 1024)
        val badStream = ByteArrayInputStream(
            ByteArray(32) + LittleEndianUtil.intTo4Bytes(oversized)
        )
        assertThrows(KdbxCorruptFileException::class.java) {
            HmacBlockStream.readAll(badStream, ByteArray(64))
        }
    }
}
