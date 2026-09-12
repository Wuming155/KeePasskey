package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.security.BinaryStore
import com.keepasskey.crypto.cipher.CipherFactory
import com.keepasskey.crypto.hash.HashUtil
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
 *
 * ISSUE-P3-29：复合密钥派生与 cipherKey 派生裁决探针已分别拆至 `KdbxKeyDerivation.kt`
 * 与 `KdbxCipherKeyResolver.kt`；本类保留读写管线本体与 `deriveKeys` 门面委托
 * （同签名，既有调用方与单测零改动）。拆分为**纯结构性**，行为零变更。
 */
object KdbxFile {

    private val secureRandom = SecureRandom()

    /** KDBX4 内层流密码密钥长度（官方 InnerRandomStreamKey 恒为 64 字节） */
    private const val INNER_RANDOM_STREAM_KEY_SIZE = 64

    /**
     * 载荷解压输出（或未压缩载荷）累计字节数安全上限：128 MiB（Wave 12 解析炸弹防线；
     * ISSUE-P3-10 子项 1 由原 512 MiB 下调）。
     *
     * **本常量是三处解析资源上限的唯一真源（ISSUE-P3-27 子项 1）**：内层 Header 的
     * 单字段上限与二进制池累计上限均由本值派生（[InnerHeader.MAX_INNER_FIELD_BYTES] =
     * 本值 / 2；[InnerHeader.MAX_BINARY_POOL_TOTAL_BYTES] = min(设计值, 本值)），
     * 并在 [InnerHeader] 伴生对象初始化期以 `require` 断言
     * 「单字段 ≤ 池累计 ≤ 整包」。因此「内层某上限高于整包上限」这类语义不自洽
     * 在常量漂移时会立即暴露，而不会退化成永不生效的死守卫。
     *
     * 取值依据：
     * - **binding 级守卫**：本上限包住内层 Header（含附件二进制池）与 XML 正文的**全部**
     *   解压输出，恶意 .kdbx（导入场景，用户持有其密码）无法越过它解出更多明文，
     *   解析期峰值内存被约束在移动端可承受的常数界内（原 512 MiB 已属高危水位）；
     * - `guardPayloadSize` 对 GZip 与未压缩（Compression.NONE）载荷一视同仁，
     *   两种压缩模式共用同一预算，不存在绕过通道；
     * - 下限约束：不得低于内层 Header 单字段上限（本值 / 2 = 64 MiB，承载单个附件），
     *   否则「一个合法大附件 + XML 正文」即被误拒；本值为单字段上限留出同等量级余量；
     * - 正常 KDBX 库（含常见附件规模）远低于该界，且流式解析本就不整体物化载荷，
     *   保留同一量级的正常库解析能力；
     * - 取舍结论（ISSUE-P3-27 子项 1）：放宽本值会直接削弱解压炸弹防护，收紧会误拒
     *   合法大附件，故本值不动，只把内层各级上限收敛为它的派生值。
     */
    internal const val MAX_DECOMPRESSED_PAYLOAD_BYTES = 128L * 1024 * 1024

    /**
     * 为解密后的载荷流套上「解压输出尺寸护栏」。
     *
     * 独立成函数以便单测直接以生产常量（[MAX_DECOMPRESSED_PAYLOAD_BYTES]）验证
     * 「超限被拒 / 限额内通过」，无需构造超大真实库（见 KdbxParsingResourceLimitsTest）。
     */
    internal fun guardPayloadSize(raw: InputStream, isGzipCompressed: Boolean): InputStream {
        val decompressed = if (isGzipCompressed) GZIPInputStream(raw) else raw
        return SizeBoundedInputStream(decompressed, MAX_DECOMPRESSED_PAYLOAD_BYTES)
    }

    /**
     * 打开并解密 KDBX v4 数据库。
     * 先采用官方标准密钥派生校验头部 HMAC（凭据正确性在解密前的最终裁决）；
     * 历史旧派生（SHA-256 cipherKey）与官方派生的 hmacKey64 完全一致，头部 HMAC 无法区分二者，
     * 因此 cipherKey 变体由数据段首个块的解密探针裁决（见 [KdbxCipherKeyResolver.resolve]），
     * 保存时自动迁移官方标准。
     *
     * [passwordChars] 允许为 null 或空数组（表示无主密码、仅密钥文件解锁，
     * 对齐官方 KeyUtil.CreateKey 对空密码不添加密码分量的语义，详见 [deriveKeys]）。
     */
    fun load(
        inputStream: InputStream,
        passwordChars: CharArray?,
        keyFileData: ByteArray? = null,
        binaryStore: BinaryStore? = null
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

            return loadPayload(inputStream, header, passwordChars, keyFileData, cipherKey, hmacKey64, binaryStore)
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
        hmacKey64: ByteArray,
        binaryStore: BinaryStore?
    ): KdbxDatabase {
        val hmacBlockIn = HmacBlockInputStream(NonClosingInputStream(inputStream), hmacKey64)
        val cipherEngine = CipherFactory.getEngine(header.cipherUuid)

        // 旧派生裁决：取首个数据块做解密探针，官方派生不合法时回退旧派生
        val firstBlock = hmacBlockIn.readBlock()
        val isGzip = header.compression == KdbxConstants.Compression.GZIP
        val resolution = KdbxCipherKeyResolver.resolve(cipherEngine, header, firstBlock, cipherKey, isGzip) {
            // 旧派生重算：仅取 cipherKey 参与裁决，hmacKey64 属 transformedKey 直接派生物，用毕立即擦除
            KdbxKeyDerivation.deriveKeys(header, passwordChars, keyFileData, isLegacy = true).let { (legacyCipherKey, legacyHmacKey) ->
                try {
                    Pair(legacyCipherKey, legacyHmacKey)
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
        val cipherIn = cipherEngine.createDecryptingStream(payloadStream, resolution.activeKey, header.encryptionIv)
        // P2-10 整改（TASK-24）：解密流建立后（SecretKeySpec 构造时已克隆密钥材料），
        // 被选中的旧派生密钥原数组立即擦除；未被选中的旧派生密钥已在 resolve 内部任何
        // 结果路径（含裁决失败抛异常）统一清零——legacyCipherKey 不再残留 GC 堆。
        resolution.legacyKeyToWipe?.let { Arrays.fill(it, 0.toByte()) }

        // 官方载荷顺序（对齐 KeePass 2.x Read.cs / KeePassDX DatabaseInputKDBX）：
        // 解密 → GZIP 解压 → 内层头部（在解压流内、XML 之前）→ XML
        // Wave 12 解析炸弹防线：对解压输出（及未压缩载荷）做累计字节数封顶
        val xmlInputStream = guardPayloadSize(
            cipherIn,
            header.compression == KdbxConstants.Compression.GZIP
        )

        // ISSUE-P2-24：大附件在解析期即流式落盘（binaryStore 为 null 时行为与既往逐字一致）
        val innerHeader = InnerHeader.deserialize(xmlInputStream, binaryStore)

        val innerCipher = InnerRandomStreamCipher(
            innerHeader.innerRandomStreamId,
            innerHeader.innerRandomStreamKey
        )

        val parseResult = KdbxXmlParser(innerCipher).parse(xmlInputStream, innerHeader.binaries)

        // XML 解析可能在 GZip 尾部即停止拉取，显式确认 HMAC 终止块已被消费校验
        hmacBlockIn.verifyEndOfStream()

        return buildDatabase(header, innerHeader, parseResult)
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
        val freshInnerKey = ByteArray(INNER_RANDOM_STREAM_KEY_SIZE)
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
     * ISSUE-P3-29：实现已拆至 [KdbxKeyDerivation.deriveKeys]；本门面保持同签名与可见性，
     * 既有调用方（含单测）与 KDoc 契约（[KdbxKeyFile] 复合密钥一致性）零改动。
     */
    internal fun deriveKeys(
        header: KdbxHeader,
        passwordChars: CharArray?,
        keyFileData: ByteArray?,
        isLegacy: Boolean = false
    ): Pair<ByteArray, ByteArray> =
        KdbxKeyDerivation.deriveKeys(header, passwordChars, keyFileData, isLegacy)
}
