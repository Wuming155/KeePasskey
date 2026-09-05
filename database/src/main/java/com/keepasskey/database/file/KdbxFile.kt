package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.crypto.cipher.CipherFactory
import com.keepasskey.crypto.hash.HashUtil
import com.keepasskey.crypto.kdf.KdfFactory
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.exception.KdbxInvalidCredentialsException
import com.keepasskey.database.io.LittleEndianUtil
import com.keepasskey.database.xml.KdbxXmlParser
import com.keepasskey.database.xml.KdbxXmlSerializer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Arrays
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * KDBX v4 文件读写解析引擎。
 *
 * 遵循敏感数据铁律：主密码与派生密钥在完成变换后立即清零，绝不驻留 GC 堆。
 * 遵循官方标准（KeePass 2.61.1 §4 与 §8.1 / KeePassDX §5）：
 * - cipherKey: SHA-512(masterSeed ‖ transformedKey)[0..31]
 * - hmacKey64: SHA-512(masterSeed ‖ transformedKey ‖ 0x01)
 * - 兼顾读取历史旧派生产物，保存自动迁移至官方标准。
 */
object KdbxFile {

    private val secureRandom = SecureRandom()

    /**
     * 打开并解密 KDBX v4 数据库。
     * 先采用官方标准密钥派生校验解密；若失败则尝试旧派生（SHA-256）兼容回退。
     */
    fun load(
        inputStream: InputStream,
        passwordChars: CharArray,
        keyFileData: ByteArray? = null
    ): KdbxDatabase {
        // 1. 读取并解析外层 Header
        val (header, headerBytes) = KdbxHeader.deserialize(inputStream)

        // 2. 读取并校验 Header SHA-256
        val storedHeaderSha = try {
            LittleEndianUtil.readBytes(inputStream, 32)
        } catch (e: Exception) {
            throw KdbxCorruptFileException("读取头部 SHA-256 意外中断", e)
        }
        val actualHeaderSha = HashUtil.sha256(headerBytes)
        if (!actualHeaderSha.contentEquals(storedHeaderSha)) {
            throw KdbxCorruptFileException("KDBX 头部 SHA-256 校验失败，文件已损坏或被篡改")
        }

        val storedHeaderHmac = try {
            LittleEndianUtil.readBytes(inputStream, 32)
        } catch (e: Exception) {
            throw KdbxCorruptFileException("读取头部 HMAC 意外中断", e)
        }

        // 3. 密钥派生：先尝试官方派生标准
        var isLegacyDerivation = false
        var (cipherKey, hmacKey64) = deriveKeys(header, passwordChars, keyFileData, isLegacy = false)

        try {
            // 4. 读取并校验 Header HMAC-SHA256
            val headerHmacKey = HashUtil.sha512(LittleEndianUtil.longTo8Bytes(0xFFFFFFFFFFFFFFFFUL.toLong()), hmacKey64)
            val actualHeaderHmac = HashUtil.hmacSha256(headerHmacKey, headerBytes)
            Arrays.fill(headerHmacKey, 0.toByte())

            if (!actualHeaderHmac.contentEquals(storedHeaderHmac)) {
                // 官方派生未通过头部 HMAC，尝试旧版派生
                Arrays.fill(cipherKey, 0.toByte())
                Arrays.fill(hmacKey64, 0.toByte())
                val legacyKeys = deriveKeys(header, passwordChars, keyFileData, isLegacy = true)
                cipherKey = legacyKeys.first
                hmacKey64 = legacyKeys.second

                val legacyHmacKey = HashUtil.sha512(LittleEndianUtil.longTo8Bytes(0xFFFFFFFFFFFFFFFFUL.toLong()), hmacKey64)
                val actualLegacyHmac = HashUtil.hmacSha256(legacyHmacKey, headerBytes)
                Arrays.fill(legacyHmacKey, 0.toByte())

                if (!actualLegacyHmac.contentEquals(storedHeaderHmac)) {
                    throw KdbxInvalidCredentialsException("主密码错误或文件头部认证失败（HMAC 校验未通过）")
                }
                isLegacyDerivation = true
            }

            // 5. 读取并校验 HMAC 认证数据块流
            val encryptedPayload = HmacBlockStream.readAll(inputStream, hmacKey64)

            // 6. 解密负载数据并解析内层 Header (支持旧派生自动回退)
            val cipherEngine = CipherFactory.getEngine(header.cipherUuid)
            val (resolvedPayload, resolvedInnerHeader) = try {
                val candidatePayload = cipherEngine.decrypt(cipherKey, header.encryptionIv, encryptedPayload)
                val testStream = ByteArrayInputStream(candidatePayload)
                val candidateHeader = InnerHeader.deserialize(testStream)
                Pair(candidatePayload, candidateHeader)
            } catch (e: Exception) {
                if (!isLegacyDerivation) {
                    // 若官方密钥解密失败，尝试旧派生 (SHA-256) 回退
                    Arrays.fill(cipherKey, 0.toByte())
                    val legacyKeys = deriveKeys(header, passwordChars, keyFileData, isLegacy = true)
                    cipherKey = legacyKeys.first
                    Arrays.fill(legacyKeys.second, 0.toByte())

                    try {
                        val legacyCandidate = cipherEngine.decrypt(cipherKey, header.encryptionIv, encryptedPayload)
                        val testStream = ByteArrayInputStream(legacyCandidate)
                        val candidateHeader = InnerHeader.deserialize(testStream)
                        isLegacyDerivation = true
                        Pair(legacyCandidate, candidateHeader)
                    } catch (e2: Exception) {
                        throw KdbxInvalidCredentialsException("数据解密失败：主密码错误或文件已损坏", e2)
                    }
                } else {
                    throw KdbxInvalidCredentialsException("数据解密失败：主密码错误或文件已损坏", e)
                }
            }

            // 7. 定位并解压缩 XML 数据
            val payloadStream = ByteArrayInputStream(resolvedPayload)
            InnerHeader.deserialize(payloadStream) // 跳过已解析的内层 Header

            val xmlInputStream = if (header.compression == KdbxConstants.Compression.GZIP) {
                GZIPInputStream(payloadStream)
            } else {
                payloadStream
            }

            // 8. 初始化内层内存保护流解码器
            val innerCipher = InnerRandomStreamCipher(
                resolvedInnerHeader.innerRandomStreamId,
                resolvedInnerHeader.innerRandomStreamKey
            )

            // 9. 解析 XML DOM 数据树
            val parser = KdbxXmlParser(innerCipher)
            val parseResult = parser.parse(xmlInputStream, resolvedInnerHeader.binaries)

            return KdbxDatabase(
                header = header,
                databaseName = parseResult.meta.databaseName,
                databaseNameChanged = parseResult.meta.databaseNameChanged,
                databaseDescription = parseResult.meta.databaseDescription,
                databaseDescriptionChanged = parseResult.meta.databaseDescriptionChanged,
                rootGroup = parseResult.rootGroup,
                binaries = resolvedInnerHeader.binaries,
                recycleBinUuid = parseResult.meta.recycleBinUuid,
                recycleBinEnabled = parseResult.meta.recycleBinEnabled,
                recycleBinChanged = parseResult.meta.recycleBinChanged,
                entryTemplatesGroup = parseResult.meta.entryTemplatesGroup,
                entryTemplatesGroupChanged = parseResult.meta.entryTemplatesGroupChanged,
                customIcons = parseResult.meta.customIcons,
                deletedObjects = parseResult.meta.deletedObjects,
                memoryProtection = parseResult.meta.memoryProtection,
                customData = parseResult.meta.customData,
                historyMaxItems = parseResult.meta.historyMaxItems,
                historyMaxSize = parseResult.meta.historyMaxSize,
                lastSelectedGroup = parseResult.meta.lastSelectedGroup,
                lastTopVisibleGroup = parseResult.meta.lastTopVisibleGroup,
                generator = parseResult.meta.generator
            )
        } finally {
            Arrays.fill(cipherKey, 0.toByte())
            Arrays.fill(hmacKey64, 0.toByte())
        }
    }

    /**
     * 保存并序列化 KDBX v4 数据库。
     * 恒用官方标准派生进行加密落盘，自动迁移旧派生库。
     */
    fun save(
        outputStream: OutputStream,
        database: KdbxDatabase,
        passwordChars: CharArray,
        keyFileData: ByteArray? = null
    ) {
        // 1. 附件去重并组装二进制池 (ProtectedBinarySet 语义)
        val (dedupRootGroup, dedupBinaries) = KdbxBinaryDeduplicator.deduplicate(database.rootGroup, database.binaries)
        val updatedDatabase = database.copy(
            rootGroup = dedupRootGroup,
            binaries = dedupBinaries
        )

        // 2. 初始化全新内层 Header 与内层流密码
        val freshInnerKey = ByteArray(64)
        secureRandom.nextBytes(freshInnerKey)
        val innerHeader = InnerHeader(
            innerRandomStreamId = KdbxConstants.InnerRandomStream.CHACHA20,
            innerRandomStreamKey = freshInnerKey,
            binaries = dedupBinaries
        )
        val innerCipher = InnerRandomStreamCipher(
            innerHeader.innerRandomStreamId,
            innerHeader.innerRandomStreamKey
        )

        // 3. 序列化 XML 树
        val xmlBos = ByteArrayOutputStream()
        val serializer = KdbxXmlSerializer(innerCipher)
        serializer.serialize(xmlBos, updatedDatabase)
        val xmlBytes = xmlBos.toByteArray()

        // 4. GZip 压缩 XML
        val compressedBos = ByteArrayOutputStream()
        if (database.header.compression == KdbxConstants.Compression.GZIP) {
            GZIPOutputStream(compressedBos).use { gzip ->
                gzip.write(xmlBytes)
            }
        } else {
            compressedBos.write(xmlBytes)
        }
        val compressedXml = compressedBos.toByteArray()

        // 5. 组装待加密负载：InnerHeader + CompressedXml
        val payloadBos = ByteArrayOutputStream()
        innerHeader.serialize(payloadBos)
        payloadBos.write(compressedXml)
        val payload = payloadBos.toByteArray()

        // 6. 生成全新随机 MasterSeed 与 EncryptionIV，更新 KDF Salt
        val freshMasterSeed = ByteArray(32)
        val freshEncryptionIv = ByteArray(16)
        secureRandom.nextBytes(freshMasterSeed)
        secureRandom.nextBytes(freshEncryptionIv)

        val freshKdfParams = when (val p = database.header.kdfParameters) {
            is KdfParameters.Aes -> {
                val freshSeed = ByteArray(32)
                secureRandom.nextBytes(freshSeed)
                p.copy(seed = freshSeed)
            }
            is KdfParameters.Argon2 -> {
                val freshSalt = ByteArray(32)
                secureRandom.nextBytes(freshSalt)
                p.copy(salt = freshSalt)
            }
        }

        val updatedHeader = database.header.copy(
            masterSeed = freshMasterSeed,
            encryptionIv = freshEncryptionIv,
            kdfParameters = freshKdfParams
        )

        // 7. 恒用官方派生标准计算主加密与 HMAC 密钥
        val (cipherKey, hmacKey64) = deriveKeys(updatedHeader, passwordChars, keyFileData, isLegacy = false)

        try {
            // 8. 对称加密负载
            val cipherEngine = CipherFactory.getEngine(updatedHeader.cipherUuid)
            val encryptedPayload = cipherEngine.encrypt(cipherKey, updatedHeader.encryptionIv, payload)

            // 9. 写入外层 Header
            val headerBytesStream = ByteArrayOutputStream()
            val headerBytes = updatedHeader.serialize(headerBytesStream)
            outputStream.write(headerBytes)

            // 10. 写入 Header SHA-256
            val headerSha = HashUtil.sha256(headerBytes)
            outputStream.write(headerSha)

            // 11. 写入 Header HMAC-SHA256
            val headerHmacKey = HashUtil.sha512(LittleEndianUtil.longTo8Bytes(0xFFFFFFFFFFFFFFFFUL.toLong()), hmacKey64)
            val headerHmac = HashUtil.hmacSha256(headerHmacKey, headerBytes)
            outputStream.write(headerHmac)
            Arrays.fill(headerHmacKey, 0.toByte())

            // 12. 写入 HMAC 块流
            HmacBlockStream.writeAll(encryptedPayload, outputStream, hmacKey64)
            outputStream.flush()
        } finally {
            Arrays.fill(cipherKey, 0.toByte())
            Arrays.fill(hmacKey64, 0.toByte())
        }
    }

    /**
     * 派生用于数据加密的 cipherKey (32B) 与用于认证的 hmacKey (64B)。
     *
     * 官方标准（KeePass 2.61.1 §8.1，KdbxFile.ComputeKeys）：
     * - cipherKey = SHA-512(masterSeed ‖ transformedKey)[0..31]（前 32 字节）
     * - hmacKey64 = SHA-512(masterSeed ‖ transformedKey ‖ 0x01)
     *
     * 旧版兼容（isLegacy = true）：
     * - cipherKey = SHA-256(masterSeed ‖ transformedKey)
     * - hmacKey64 = SHA-512(masterSeed ‖ transformedKey ‖ 0x01)
     */
    internal fun deriveKeys(
        header: KdbxHeader,
        passwordChars: CharArray,
        keyFileData: ByteArray?,
        isLegacy: Boolean = false
    ): Pair<ByteArray, ByteArray> {
        val passwordBytes = charsToUtf8(passwordChars)
        val passwordHash = HashUtil.sha256(passwordBytes)
        Arrays.fill(passwordBytes, 0.toByte())

        val compositeKey = if (keyFileData != null && keyFileData.isNotEmpty()) {
            val keyFileHash = HashUtil.sha256(keyFileData)
            val comp = HashUtil.sha256(passwordHash, keyFileHash)
            Arrays.fill(keyFileHash, 0.toByte())
            comp
        } else {
            HashUtil.sha256(passwordHash)
        }
        Arrays.fill(passwordHash, 0.toByte())

        val kdfEngine = KdfFactory.getEngine(header.kdfParameters.kdfUuid)
        val transformedKey = kdfEngine.transform(compositeKey, header.kdfParameters)
        Arrays.fill(compositeKey, 0.toByte())

        // 组装 65 字节复合种子：MasterSeed (32B) + TransformedKey (32B) + 1 (1B)
        val cmpKey = ByteArray(65)
        System.arraycopy(header.masterSeed, 0, cmpKey, 0, 32)
        System.arraycopy(transformedKey, 0, cmpKey, 32, 32)
        Arrays.fill(transformedKey, 0.toByte())

        val cipherKeyBytes = ByteArray(64)
        System.arraycopy(cmpKey, 0, cipherKeyBytes, 0, 64)
        val cipherKey = if (isLegacy) {
            HashUtil.sha256(cipherKeyBytes)
        } else {
            val sha512 = HashUtil.sha512(cipherKeyBytes)
            val truncated = sha512.copyOfRange(0, 32)
            Arrays.fill(sha512, 0.toByte())
            truncated
        }
        Arrays.fill(cipherKeyBytes, 0.toByte())

        cmpKey[64] = 1.toByte()
        val hmacKey64 = HashUtil.sha512(cmpKey)
        Arrays.fill(cmpKey, 0.toByte())

        return Pair(cipherKey, hmacKey64)
    }

    private fun charsToUtf8(chars: CharArray): ByteArray {
        val charBuffer = java.nio.CharBuffer.wrap(chars)
        val byteBuffer = StandardCharsets.UTF_8.encode(charBuffer)
        val bytes = ByteArray(byteBuffer.remaining())
        byteBuffer.get(bytes)
        return bytes
    }
}
