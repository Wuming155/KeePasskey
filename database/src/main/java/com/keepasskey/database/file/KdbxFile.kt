package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.crypto.cipher.CipherEngine
import com.keepasskey.crypto.cipher.CipherFactory
import com.keepasskey.crypto.hash.HashUtil
import com.keepasskey.crypto.kdf.KdfFactory
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.exception.KdbxInvalidCredentialsException
import com.keepasskey.database.io.LittleEndianUtil
import com.keepasskey.database.io.NonClosingInputStream
import com.keepasskey.database.io.NonClosingOutputStream
import com.keepasskey.database.xml.KdbxMetaData
import com.keepasskey.database.xml.KdbxXmlParser
import com.keepasskey.database.xml.KdbxXmlSerializer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
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
 * - cipherKey: SHA-256(masterSeed ‖ transformedKey)
 * - hmacKey64: SHA-512(masterSeed ‖ transformedKey ‖ 0x01)
 * - 兼顾读取历史旧派生产物（SHA-512 截断公式），保存自动迁移至官方标准。
 *
 * 全链路流式管线（对应官方 Read/Write 流栈：HmacBlockStream → Cipher → GZip → XML 状态机）：
 * 读写均不再将整条密文/明文/压缩数据物化为字节数组，大库加载与保存的峰值内存
 * 由「数倍库体积」降为「单个块缓冲 + 对象树」。
 */
object KdbxFile {

    private val secureRandom = SecureRandom()

    /** 内层 Header 字段头大小：1 字节字段 ID + 4 字节小端长度 */
    private const val FIELD_HEADER_SIZE = 5

    /** 解密探针所需的最小块大小（一个 AES 分组） */
    private const val MIN_PROBE_BLOCK_SIZE = 16

    /** 解密探针的分块读取缓冲 */
    private const val PROBE_READ_BUFFER_SIZE = 8192

    private val INNER_HEADER_FIELD_IDS = setOf(
        KdbxConstants.InnerHeaderFieldId.END.toInt(),
        KdbxConstants.InnerHeaderFieldId.INNER_RANDOM_STREAM_ID.toInt(),
        KdbxConstants.InnerHeaderFieldId.INNER_RANDOM_STREAM_KEY.toInt(),
        KdbxConstants.InnerHeaderFieldId.BINARY.toInt()
    )

    /**
     * 打开并解密 KDBX v4 数据库。
     * 先采用官方标准密钥派生校验头部 HMAC（凭据正确性在解密前的最终裁决）；
     * 历史旧派生（SHA-256 cipherKey）与官方派生的 hmacKey64 完全一致，头部 HMAC 无法区分二者，
     * 因此 cipherKey 变体由数据段首个块的解密探针裁决（见 [resolveCipherKey]），保存时自动迁移官方标准。
     *
     * [passwordChars] 允许为 null 或空数组（表示无主密码、仅密钥文件解锁，
     * 对齐官方 KeyUtil.CreateKey 对空密码不添加密码分量的语义，详见 [deriveKeys]）。
     */
    fun load(
        inputStream: InputStream,
        passwordChars: CharArray?,
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
        if (!java.security.MessageDigest.isEqual(actualHeaderSha, storedHeaderSha)) {
            throw KdbxCorruptFileException("KDBX 头部 SHA-256 校验失败，文件已损坏或被篡改")
        }

        val storedHeaderHmac = try {
            LittleEndianUtil.readBytes(inputStream, 32)
        } catch (e: Exception) {
            throw KdbxCorruptFileException("读取头部 HMAC 意外中断", e)
        }

        // 3. 官方标准派生
        val (cipherKey, hmacKey64) = deriveKeys(header, passwordChars, keyFileData, isLegacy = false)

        try {
            // 4. 读取并校验 Header HMAC-SHA256
            // 官方规范 / 对齐 KeePass 2.x、pykeepass：headerKey = SHA-512(LE64(0xFFFFFFFFFFFFFFFF) ‖ hmacKey64)
            val headerHmacKey = HashUtil.sha512(
                LittleEndianUtil.longTo8Bytes(0xFFFFFFFFFFFFFFFFUL.toLong()),
                hmacKey64
            )
            val actualHeaderHmac = HashUtil.hmacSha256(headerHmacKey, headerBytes)
            Arrays.fill(headerHmacKey, 0.toByte())

            if (!java.security.MessageDigest.isEqual(actualHeaderHmac, storedHeaderHmac)) {
                throw KdbxInvalidCredentialsException("主密码错误或文件头部认证失败（HMAC 校验未通过）")
            }

            return loadPayload(inputStream, header, passwordChars, keyFileData, cipherKey, hmacKey64)
        } finally {
            Arrays.fill(cipherKey, 0.toByte())
            Arrays.fill(hmacKey64, 0.toByte())
        }
    }

    /**
     * 流式解密负载数据：HMAC 块流（逐块校验）→ 解密流 → 内层 Header → 可选 GZip 解压 → 流式 XML 解析。
     */
    private fun loadPayload(
        inputStream: InputStream,
        header: KdbxHeader,
        passwordChars: CharArray?,
        keyFileData: ByteArray?,
        cipherKey: ByteArray,
        hmacKey64: ByteArray
    ): KdbxDatabase {
        val hmacBlockIn = HmacBlockInputStream(NonClosingInputStream(inputStream), hmacKey64)
        val cipherEngine = CipherFactory.getEngine(header.cipherUuid)

        // 旧派生裁决：取首个数据块做解密探针，官方派生不合法时回退旧派生
        val firstBlock = hmacBlockIn.readBlock()
        val isGzip = header.compression == KdbxConstants.Compression.GZIP
        val activeKey = resolveCipherKey(cipherEngine, header, firstBlock, cipherKey, isGzip) {
            // 旧派生重算：仅取 cipherKey 参与裁决，hmacKey64 属 transformedKey 直接派生物，用毕立即擦除
            deriveKeys(header, passwordChars, keyFileData, isLegacy = true).let { (legacyCipherKey, legacyHmacKey) ->
                try {
                    legacyCipherKey
                } finally {
                    Arrays.fill(legacyHmacKey, 0.toByte())
                }
            }
        }

        val payloadStream = if (firstBlock != null) {
            SequenceInputStream(ByteArrayInputStream(firstBlock), hmacBlockIn)
        } else {
            hmacBlockIn
        }
        val cipherIn = cipherEngine.createDecryptingStream(payloadStream, activeKey, header.encryptionIv)

        // 官方载荷顺序（对齐 KeePass 2.x Read.cs / KeePassDX DatabaseInputKDBX）：
        // 解密 → GZIP 解压 → 内层头部（在解压流内、XML 之前）→ XML
        val xmlInputStream = if (header.compression == KdbxConstants.Compression.GZIP) {
            GZIPInputStream(cipherIn)
        } else {
            cipherIn
        }

        val innerHeader = InnerHeader.deserialize(xmlInputStream)

        val innerCipher = InnerRandomStreamCipher(
            innerHeader.innerRandomStreamId,
            innerHeader.innerRandomStreamKey
        )

        val parseResult = KdbxXmlParser(innerCipher).parse(xmlInputStream, innerHeader.binaries)

        // XML 解析可能在 GZip 尾部即停止拉取，显式确认 HMAC 终止块已被消费校验
        hmacBlockIn.verifyEndOfStream()

        return buildDatabase(header, innerHeader, parseResult)
    }

    /**
     * 用首个数据块的解密结果裁决 cipherKey 派生变体：
     * GZIP 压缩库（官方默认）解密产物以 GZIP 魔数 1F 8B 08 开始；未压缩库解密产物
     * 呈现合法的内层 Header 字段序列。错误密钥的解密产物几乎不可能通过结构校验。
     * 两种派生均不合法时按凭据错误处理。
     */
    private fun resolveCipherKey(
        cipherEngine: CipherEngine,
        header: KdbxHeader,
        firstBlock: ByteArray?,
        officialKey: ByteArray,
        isGzipCompressed: Boolean,
        deriveLegacyKey: () -> ByteArray
    ): ByteArray {
        if (firstBlock == null || firstBlock.size < MIN_PROBE_BLOCK_SIZE) {
            // 块过小无法构成有效探针（正常 KDBX 负载远大于此），按官方派生继续，由后续解析暴露问题
            return officialKey
        }
        if (isPlausibleInnerHeaderPrefix(cipherEngine, header.encryptionIv, officialKey, firstBlock, isGzipCompressed)) {
            return officialKey
        }
        val legacyKey = deriveLegacyKey()
        if (isPlausibleInnerHeaderPrefix(cipherEngine, header.encryptionIv, legacyKey, firstBlock, isGzipCompressed)) {
            return legacyKey
        }
        throw KdbxInvalidCredentialsException("数据解密失败：主密码错误或文件已损坏")
    }

    /**
     * 试解密首块并校验其前缀结构：
     * GZIP 压缩库校验魔数（1F 8B 08）；未压缩库校验内层 Header 字段序列前缀。
     * 首块通常并非消息结尾，AES-PKCS5 在收尾 doFinal 时会触发 BadPadding——
     * 逐块读取并在该异常处停止，已解出的前缀对结构校验依然有效。
     */
    private fun isPlausibleInnerHeaderPrefix(
        cipherEngine: CipherEngine,
        encryptionIv: ByteArray,
        cipherKey: ByteArray,
        firstBlock: ByteArray,
        isGzipCompressed: Boolean
    ): Boolean {
        val prefix = try {
            val probe = cipherEngine.createDecryptingStream(ByteArrayInputStream(firstBlock), cipherKey, encryptionIv)
            probe.use { stream ->
                val buffer = ByteArray(PROBE_READ_BUFFER_SIZE)
                val collected = ByteArrayOutputStream()
                try {
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        collected.write(buffer, 0, count)
                    }
                } catch (_: java.io.IOException) {
                    // 解密流收尾异常：截取已解出的前缀继续校验
                }
                collected.toByteArray()
            }
        } catch (_: Exception) {
            return false
        }
        return if (isGzipCompressed) {
            prefix.size >= 3 &&
                    prefix[0] == 0x1F.toByte() &&
                    prefix[1] == 0x8B.toByte() &&
                    prefix[2] == 0x08.toByte()
        } else {
            isPlausibleFieldSequence(prefix)
        }
    }

    /**
     * 校验字节序列是否为合法的内层 Header 字段序列前缀：
     * 字段 ID 必须属于已知集合，字段长度非负且有界，END 字段正常终止；
     * 仅 BINARY 字段允许「长度超出前缀剩余量」——大二进制池可合法跨越后续 HMAC 块延续。
     */
    private fun isPlausibleFieldSequence(prefix: ByteArray): Boolean {
        var offset = 0
        while (offset + FIELD_HEADER_SIZE <= prefix.size) {
            val fieldId = prefix[offset].toInt() and 0xFF
            if (fieldId !in INNER_HEADER_FIELD_IDS) return false
            val length = LittleEndianUtil.bytesToInt(prefix, offset + 1)
            if (length < 0) return false
            offset += FIELD_HEADER_SIZE + length
            if (fieldId == KdbxConstants.InnerHeaderFieldId.END.toInt()) return true
            if (offset > prefix.size) {
                return fieldId == KdbxConstants.InnerHeaderFieldId.BINARY.toInt()
            }
        }
        return offset <= prefix.size
    }

    private fun buildDatabase(
        header: KdbxHeader,
        innerHeader: InnerHeader,
        parseResult: KdbxXmlParser.ParseResult
    ): KdbxDatabase {
        val meta: KdbxMetaData = parseResult.meta
        return KdbxDatabase(
            header = header,
            databaseName = meta.databaseName,
            databaseNameChanged = meta.databaseNameChanged,
            databaseDescription = meta.databaseDescription,
            databaseDescriptionChanged = meta.databaseDescriptionChanged,
            defaultUserName = meta.defaultUserName,
            defaultUserNameChanged = meta.defaultUserNameChanged,
            maintenanceHistoryDays = meta.maintenanceHistoryDays,
            color = meta.color,
            masterKeyChanged = meta.masterKeyChanged,
            masterKeyChangeRec = meta.masterKeyChangeRec,
            masterKeyChangeForce = meta.masterKeyChangeForce,
            settingsChanged = meta.settingsChanged,
            rootGroup = parseResult.rootGroup,
            binaries = innerHeader.binaries,
            recycleBinUuid = meta.recycleBinUuid,
            recycleBinEnabled = meta.recycleBinEnabled,
            recycleBinChanged = meta.recycleBinChanged,
            entryTemplatesGroup = meta.entryTemplatesGroup,
            entryTemplatesGroupChanged = meta.entryTemplatesGroupChanged,
            customIcons = meta.customIcons,
            deletedObjects = meta.deletedObjects,
            memoryProtection = meta.memoryProtection,
            customData = meta.customData,
            historyMaxItems = meta.historyMaxItems,
            historyMaxSize = meta.historyMaxSize,
            lastSelectedGroup = meta.lastSelectedGroup,
            lastTopVisibleGroup = meta.lastTopVisibleGroup,
            generator = meta.generator
        )
    }

    /**
     * 保存并序列化 KDBX v4 数据库（全链路流式：XML 流式写出 → GZip → 加密流 → HMAC 块流）。
     * 恒用官方标准派生进行加密落盘，自动迁移旧派生库。
     *
     * [passwordChars] 允许为 null 或空数组（仅密钥文件库，对齐官方 KeePass 语义，详见 [deriveKeys]）。
     */
    fun save(
        outputStream: OutputStream,
        database: KdbxDatabase,
        passwordChars: CharArray?,
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

        // 3. 生成全新随机 MasterSeed 与 EncryptionIV，更新 KDF Salt
        // P0-4 整改：EncryptionIV 长度必须由加密算法决定（KDBX4 规范要求 Header 字段 7 的
        // 长度等于所选算法的 IV 长度，官方 KeePass 2.61.1 各 Cipher 构造器硬校验）——
        // ChaCha20 (Cipher.CHACHA20) 为 12 字节 nonce（RFC 7539），
        // AES-256-CBC (Cipher.AES_256_CBC) / Twofish (Cipher.TWOFISH) 为 16 字节 IV。
        // 原实现恒写 16 字节，凡以 ChaCha20 保存的库均为官方客户端打不开的文件。
        val freshMasterSeed = ByteArray(32)
        val freshEncryptionIv = ByteArray(
            CipherFactory.getEngine(database.header.cipherUuid).ivLength
        )
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

        // 4. 恒用官方派生标准计算主加密与 HMAC 密钥
        val (cipherKey, hmacKey64) = deriveKeys(updatedHeader, passwordChars, keyFileData, isLegacy = false)

        try {
            // 5. 写入外层 Header + Header SHA-256 + Header HMAC
            val headerBytesStream = ByteArrayOutputStream()
            val headerBytes = updatedHeader.serialize(headerBytesStream)
            outputStream.write(headerBytes)
            outputStream.write(HashUtil.sha256(headerBytes))

            // 与读取侧一致：SHA-512(LE64(0xFFFFFFFFFFFFFFFF) ‖ hmacKey64)
            val headerHmacKey = HashUtil.sha512(
                LittleEndianUtil.longTo8Bytes(0xFFFFFFFFFFFFFFFFUL.toLong()),
                hmacKey64
            )
            val headerHmac = HashUtil.hmacSha256(headerHmacKey, headerBytes)
            outputStream.write(headerHmac)
            Arrays.fill(headerHmacKey, 0.toByte())

            // 6. 流式加密写出负载：内层 Header ‖ XML 一同经 GZip 压缩后写入加密流，再由 HMAC 块流封装
            savePayload(outputStream, updatedHeader, innerHeader, innerCipher, updatedDatabase, cipherKey, hmacKey64)
            outputStream.flush()
        } finally {
            Arrays.fill(cipherKey, 0.toByte())
            Arrays.fill(hmacKey64, 0.toByte())
        }
    }

    private fun savePayload(
        outputStream: OutputStream,
        header: KdbxHeader,
        innerHeader: InnerHeader,
        innerCipher: InnerRandomStreamCipher,
        database: KdbxDatabase,
        cipherKey: ByteArray,
        hmacKey64: ByteArray
    ) {
        val blockOut = HmacBlockOutputStream(NonClosingOutputStream(outputStream), hmacKey64)
        val cipherEngine = CipherFactory.getEngine(header.cipherUuid)
        val cipherOut = cipherEngine.createEncryptingStream(blockOut, cipherKey, header.encryptionIv)

        // 官方载荷顺序（对齐 KeePass 2.x Write.cs / KeePassDX DatabaseOutputKDBX）：
        // 内层头部写在 GZIP 流之内（与 XML 一同被压缩），读取侧先解压再读内层头部
        val gzipOut = if (header.compression == KdbxConstants.Compression.GZIP) {
            GZIPOutputStream(cipherOut)
        } else {
            null
        }
        val bodyOut = gzipOut ?: cipherOut
        try {
            innerHeader.serialize(bodyOut)
            KdbxXmlSerializer(innerCipher).serialize(bodyOut, database)
        } finally {
            // 级联收尾：GZip finish → 加密流 doFinal → HMAC 块流写入终止块
            gzipOut?.close() ?: cipherOut.close()
        }
    }

    /**
     * 派生用于数据加密的 cipherKey (32B) 与用于认证的 hmacKey (64B)。
     *
     * 复合密钥（P1-10 整改，对齐官方 KeePass 2.61.1 CompositeKey.CreateRawCompositeKey32：
     * 只拼接实际存在的凭据分量后整体 SHA-256；官方解锁框对空密码不添加密码分量 KeyUtil.CreateKey，
     * 因此 [passwordChars] 为 null 或空数组时必须跳过密码分量，绝不把 SHA-256("") 拼入复合密钥）：
     * - 仅主密码：SHA-256(SHA-256(password))（密码分量本身即 SHA-256(UTF-8 密码)，KcpPassword）
     * - 仅密钥文件：SHA-256(keyFileKey)（keyFileKey 经 [KdbxKeyFile.extractKey] 官方解析梯子提取）
     * - 密码 + 密钥文件：SHA-256(SHA-256(password) ‖ keyFileKey)
     *
     * 官方标准（KeePass 2.61.1 KdbxFile.ComputeKeys + CryptoUtil.ResizeKey，
     * 与 pykeepass compute_master / KeePassDX DatabaseInputKDBX 交叉验证一致）：
     * - cipherKey = SHA-256(masterSeed ‖ transformedKey)
     * - hmacKey64 = SHA-512(masterSeed ‖ transformedKey ‖ 0x01)
     *
     * 历史兼容（isLegacy = true，本应用早期版本写出的文件）：
     * - cipherKey = SHA-512(masterSeed ‖ transformedKey)[0..32)
     * - hmacKey64 同官方
     */
    internal fun deriveKeys(
        header: KdbxHeader,
        passwordChars: CharArray?,
        keyFileData: ByteArray?,
        isLegacy: Boolean = false
    ): Pair<ByteArray, ByteArray> {
        val hasPassword = passwordChars != null && passwordChars.isNotEmpty()
        val hasKeyFile = keyFileData != null && keyFileData.isNotEmpty()

        val compositeKey = when {
            hasPassword && hasKeyFile -> {
                // 密码 + 密钥文件：SHA-256(SHA-256(password) ‖ keyFileKey)
                val passwordHash = HashUtil.sha256(charsToUtf8(passwordChars!!))
                try {
                    // 按官方语义解析密钥文件（XML .keyx 取 <Data> / 裸 32 字节 /
                    // 64 位 hex 文本 / 任意二进制整文件 SHA-256），
                    // 原实现对 XML 密钥文件整文件哈希导致复合密钥错误（虚假开关整改）
                    val keyFileKey = KdbxKeyFile.extractKey(keyFileData!!)
                    try {
                        HashUtil.sha256(passwordHash, keyFileKey)
                    } finally {
                        Arrays.fill(keyFileKey, 0.toByte())
                    }
                } finally {
                    Arrays.fill(passwordHash, 0.toByte())
                }
            }
            hasKeyFile -> {
                // P1-10：仅密钥文件 —— 直接 SHA-256(keyFileKey)，不拼入空密码分量 SHA-256("")。
                // 原实现恒拼入 SHA-256("")，官方仅密钥文件库 100% 派生错误密钥、报「主密码错误」
                val keyFileKey = KdbxKeyFile.extractKey(keyFileData!!)
                try {
                    HashUtil.sha256(keyFileKey)
                } finally {
                    Arrays.fill(keyFileKey, 0.toByte())
                }
            }
            else -> {
                // 仅密码：SHA-256(SHA-256(password))。
                // 密码与密钥文件均缺失时退化为 SHA-256(SHA-256(""))——本应用历史「空密码库」
                // 语义（官方客户端无法创建此类库），保持既有空密码库读写兼容，
                // 凭据校验由头部 HMAC 给出明确失败。
                val passwordBytes = if (hasPassword) charsToUtf8(passwordChars!!) else ByteArray(0)
                val passwordHash = HashUtil.sha256(passwordBytes)
                Arrays.fill(passwordBytes, 0.toByte())
                try {
                    HashUtil.sha256(passwordHash)
                } finally {
                    Arrays.fill(passwordHash, 0.toByte())
                }
            }
        }

        val kdfEngine = KdfFactory.getEngine(header.kdfParameters.kdfUuid)
        val transformedKey = kdfEngine.transform(compositeKey, header.kdfParameters)
        Arrays.fill(compositeKey, 0.toByte())

        // 组装 65 字节复合种子：MasterSeed (32B) + TransformedKey (32B) + 1 (1B)
        val cmpKey = ByteArray(65)
        System.arraycopy(header.masterSeed, 0, cmpKey, 0, 32)
        System.arraycopy(transformedKey, 0, cmpKey, 32, 32)
        Arrays.fill(transformedKey, 0.toByte())

        // 官方 KDBX4 派生（对齐 KeePass 2.x / pykeepass / KeePassXC）：
        // cipherKey  = SHA-256(masterSeed ‖ transformedKey)
        // hmacKey64  = SHA-512(masterSeed ‖ transformedKey ‖ 0x01)
        // 历史 bug 回放：本应用曾把 cipherKey 误实现为 SHA-512(seed‖tk)[0..32)（无尾部常量），
        // 该错误公式保留为旧文件探针回退路径（isLegacy = true）
        val cipherKeyBytes = ByteArray(64)
        System.arraycopy(cmpKey, 0, cipherKeyBytes, 0, 64)
        val cipherKey = if (isLegacy) {
            HashUtil.sha512(cipherKeyBytes).copyOfRange(0, 32)
        } else {
            HashUtil.sha256(cipherKeyBytes)
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
