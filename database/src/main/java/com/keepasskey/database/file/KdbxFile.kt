package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.crypto.cipher.CipherFactory
import com.keepasskey.crypto.hash.HashUtil
import com.keepasskey.crypto.kdf.KdfFactory
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import com.keepasskey.database.io.LittleEndianUtil
import com.keepasskey.database.xml.KdbxXmlParser
import com.keepasskey.database.xml.KdbxXmlSerializer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Arrays
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * KDBX v4 文件读写解析引擎。
 * 遵循敏感数据铁律：主密码与派生密钥在完成变换后立即清零，绝不驻留 GC 堆。
 */
object KdbxFile {

    private val secureRandom = SecureRandom()

    /**
     * 打开并解密 KDBX v4 数据库
     */
    fun load(
        inputStream: InputStream,
        passwordChars: CharArray,
        keyFileData: ByteArray? = null
    ): KdbxDatabase {
        // 1. 读取并解析外层 Header
        val (header, headerBytes) = KdbxHeader.deserialize(inputStream)

        // 2. 读取并校验 Header SHA-256
        val storedHeaderSha = LittleEndianUtil.readBytes(inputStream, 32)
        val actualHeaderSha = HashUtil.sha256(headerBytes)
        if (!actualHeaderSha.contentEquals(storedHeaderSha)) {
            throw IOException("KDBX 头部 SHA-256 校验失败，文件已损坏或被篡改")
        }

        // 3. 密钥派生
        val (cipherKey, hmacKey64) = deriveKeys(header, passwordChars, keyFileData)

        try {
            // 4. 读取并校验 Header HMAC-SHA256
            val storedHeaderHmac = LittleEndianUtil.readBytes(inputStream, 32)
            val headerHmacKey = HashUtil.sha512(LittleEndianUtil.longTo8Bytes(0xFFFFFFFFFFFFFFFFUL.toLong()), hmacKey64)
            val actualHeaderHmac = HashUtil.hmacSha256(headerHmacKey, headerBytes)
            Arrays.fill(headerHmacKey, 0.toByte())

            if (!actualHeaderHmac.contentEquals(storedHeaderHmac)) {
                throw IOException("主密码错误或文件头部认证失败（HMAC 校验未通过）")
            }

            // 5. 读取并校验 HMAC 认证数据块流
            val encryptedPayload = HmacBlockStream.readAll(inputStream, hmacKey64)

            // 6. 解密负载数据
            val cipherEngine = CipherFactory.getEngine(header.cipherUuid)
            val decryptedPayload = cipherEngine.decrypt(cipherKey, header.encryptionIv, encryptedPayload)

            // 7. 解析内层 Header
            val decryptedStream = ByteArrayInputStream(decryptedPayload)
            val innerHeader = InnerHeader.deserialize(decryptedStream)

            // 8. 解压缩 XML 数据
            val xmlInputStream = if (header.compression == KdbxConstants.Compression.GZIP) {
                GZIPInputStream(decryptedStream)
            } else {
                decryptedStream
            }

            // 9. 初始化内层内存保护流解码器
            val innerCipher = InnerRandomStreamCipher(
                innerHeader.innerRandomStreamId,
                innerHeader.innerRandomStreamKey
            )

            // 10. 解析 XML DOM 数据树
            val parser = KdbxXmlParser(innerCipher)
            val parseResult = parser.parse(xmlInputStream)

            return KdbxDatabase(
                header = header,
                databaseName = parseResult.databaseName,
                databaseDescription = parseResult.databaseDescription,
                rootGroup = parseResult.rootGroup
            )
        } finally {
            // 敏感密钥擦除铁律
            Arrays.fill(cipherKey, 0.toByte())
            Arrays.fill(hmacKey64, 0.toByte())
        }
    }

    /**
     * 保存并序列化 KDBX v4 数据库
     */
    fun save(
        outputStream: OutputStream,
        database: KdbxDatabase,
        passwordChars: CharArray,
        keyFileData: ByteArray? = null
    ) {
        // 1. 初始化内层 Header 与内层流密码
        val innerHeader = InnerHeader.createDefault()
        val innerCipher = InnerRandomStreamCipher(
            innerHeader.innerRandomStreamId,
            innerHeader.innerRandomStreamKey
        )

        // 2. 序列化 XML 树
        val xmlBos = ByteArrayOutputStream()
        val serializer = KdbxXmlSerializer(innerCipher)
        serializer.serialize(xmlBos, database.databaseName, database.databaseDescription, database.rootGroup)
        val xmlBytes = xmlBos.toByteArray()

        // 3. GZip 压缩 XML
        val compressedBos = ByteArrayOutputStream()
        if (database.header.compression == KdbxConstants.Compression.GZIP) {
            GZIPOutputStream(compressedBos).use { gzip ->
                gzip.write(xmlBytes)
            }
        } else {
            compressedBos.write(xmlBytes)
        }
        val compressedXml = compressedBos.toByteArray()

        // 4. 组装待加密负载：InnerHeader + CompressedXml
        val payloadBos = ByteArrayOutputStream()
        innerHeader.serialize(payloadBos)
        payloadBos.write(compressedXml)
        val payload = payloadBos.toByteArray()

        // 5. 生成全新随机 MasterSeed 与 EncryptionIV，更新 KDF Salt
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

        // 6. 派生主加密与 HMAC 密钥
        val (cipherKey, hmacKey64) = deriveKeys(updatedHeader, passwordChars, keyFileData)

        try {
            // 7. 对称加密负载
            val cipherEngine = CipherFactory.getEngine(updatedHeader.cipherUuid)
            val encryptedPayload = cipherEngine.encrypt(cipherKey, updatedHeader.encryptionIv, payload)

            // 8. 写入外层 Header
            val headerBytesStream = ByteArrayOutputStream()
            val headerBytes = updatedHeader.serialize(headerBytesStream)
            outputStream.write(headerBytes)

            // 9. 写入 Header SHA-256
            val headerSha = HashUtil.sha256(headerBytes)
            outputStream.write(headerSha)

            // 10. 写入 Header HMAC-SHA256
            val headerHmacKey = HashUtil.sha512(LittleEndianUtil.longTo8Bytes(0xFFFFFFFFFFFFFFFFUL.toLong()), hmacKey64)
            val headerHmac = HashUtil.hmacSha256(headerHmacKey, headerBytes)
            outputStream.write(headerHmac)
            Arrays.fill(headerHmacKey, 0.toByte())

            // 11. 写入 HMAC 块流
            HmacBlockStream.writeAll(encryptedPayload, outputStream, hmacKey64)
            outputStream.flush()
        } finally {
            Arrays.fill(cipherKey, 0.toByte())
            Arrays.fill(hmacKey64, 0.toByte())
        }
    }

    /**
     * 派生用于数据加密的 cipherKey (32B) 与用于认证的 hmacKey (64B)
     */
    private fun deriveKeys(
        header: KdbxHeader,
        passwordChars: CharArray,
        keyFileData: ByteArray?
    ): Pair<ByteArray, ByteArray> {
        // 密码 UTF-8 哈希
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

        // cipherKey: SHA-256(cmpKey[0..63])
        val cipherKeyBytes = ByteArray(64)
        System.arraycopy(cmpKey, 0, cipherKeyBytes, 0, 64)
        val cipherKey = HashUtil.sha256(cipherKeyBytes)
        Arrays.fill(cipherKeyBytes, 0.toByte())

        // hmacKey: SHA-512(cmpKey[0..64])
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
